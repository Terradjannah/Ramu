package com.assistant.adi.data.catalog

import android.content.Context
import android.util.AtomicFile
import com.assistant.adi.BuildConfig
import com.assistant.adi.data.model.AiModelCatalog
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.Base64
import java.util.concurrent.TimeUnit

interface CatalogTransport {
    fun get(path: String, limit: Int): ByteArray
}

class HttpsCatalogTransport(baseUrl: String, private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
    .callTimeout(30, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
) : CatalogTransport {
    private val base = URI(baseUrl)

    init {
        require(base.scheme == "https" && base.host != null && base.userInfo == null &&
            base.query == null && base.fragment == null && base.port == -1 &&
            base.path.endsWith("/catalog/v1/")) { "Invalid catalog endpoint" }
    }

    override fun get(path: String, limit: Int): ByteArray {
        require(path == "index.json" || path == "index.sig" ||
            Regex("generations/[a-z0-9]+(?:-[a-z0-9]+)*\\.json").matches(path) ||
            Regex("signatures/[a-z0-9]+(?:-[a-z0-9]+)*\\.sig").matches(path))
        val url = base.resolve(path)
        require(url.scheme == base.scheme && url.host == base.host && url.port == base.port &&
            url.path.startsWith(base.path) && url.query == null && url.fragment == null)
        val request = Request.Builder().url(url.toString()).get().build()
        client.newCall(request).execute().use { response ->
            require(response.code == 200 && !response.isRedirect) { "Catalog HTTP ${response.code}" }
            val body = response.body ?: error("Empty catalog response")
            require(body.contentLength() <= limit) { "Catalog response too large" }
            body.byteStream().use { stream ->
                val result = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    require(result.size() + count <= limit) { "Catalog response too large" }
                    result.write(buffer, 0, count)
                }
                return result.toByteArray()
            }
        }
    }
}

interface CatalogCache {
    fun read(): ByteArray?
    fun write(bytes: ByteArray)
}

class AndroidCatalogCache(context: Context) : CatalogCache {
    private val file = AtomicFile(File(context.filesDir, "verified-model-catalog.json"))
    override fun read(): ByteArray? = try { file.openRead().use { it.readBytes() } } catch (_: Exception) { null }
    override fun write(bytes: ByteArray) {
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) } catch (error: Exception) { file.failWrite(stream); throw error }
    }
}

class CatalogRepository(
    private val transport: CatalogTransport?,
    private val verifier: CatalogVerifier?,
    private val cache: CatalogCache,
    private val fallback: CatalogSnapshot = localFallback()
) {
    private val mutex = Mutex()
    private var cacheLoaded = false
    private val _state = MutableStateFlow<CatalogState>(CatalogState.OfflineCached(fallback))
    val state: StateFlow<CatalogState> = _state

    suspend fun loadCache() = withContext(Dispatchers.IO) {
        mutex.withLock {
            loadCacheLocked()
        }
    }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        mutex.withLock {
            loadCacheLocked()
            _state.value = CatalogState.Loading(_state.value.snapshot)
            try {
                val network = requireNotNull(transport) { "Catalog endpoint unavailable" }
                requireNotNull(verifier) { "Catalog signing key unavailable" }
                val index = network.get("index.json", 65536)
                val signature = network.get("index.sig", 1024)
                val parts = linkedMapOf("index.json" to index, "index.sig" to signature)
                verifier.verify(index, signature)
                val parsed = verifier.index(index)
                require(parsed.version >= _state.value.snapshot.version) { "Catalog rollback" }
                val generations = parsed.generations.map { ref ->
                    val json = network.get(ref.path, 262144)
                    val sigPath = "signatures/${ref.id}.sig"
                    val sig = network.get(sigPath, 1024)
                    verifier.checkDigest(json, ref)
                    verifier.verify(json, sig)
                    parts[ref.path] = json
                    parts[sigPath] = sig
                    verifier.generation(json, ref.id)
                }
                validateIds(generations)
                val snapshot = CatalogSnapshot(parsed.version, generations, CatalogSource.VERIFIED)
                cache.write(encode(parts))
                _state.value = CatalogState.Verified(snapshot)
            } catch (error: Exception) {
                _state.value = CatalogState.Failed(_state.value.snapshot, error.message ?: "Catalog refresh failed")
            }
        }
    }

    private fun loadCacheLocked() {
        if (cacheLoaded) return
        cacheLoaded = true
        val cached = runCatching { cache.read()?.let { decode(it) } }.getOrNull()
        if (cached != null) _state.value = CatalogState.OfflineCached(cached.first)
    }

    private fun decode(bytes: ByteArray): Pair<CatalogSnapshot, Map<String, ByteArray>> {
        require(bytes.size <= 12_000_000)
        val root = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
        require(root.keySet() == setOf("catalogVersion", "files"))
        val files = root.getAsJsonObject("files").entrySet().associate { it.key to Base64.getDecoder().decode(it.value.asString) }
        val check = requireNotNull(verifier)
        val index = files.getValue("index.json")
        check.verify(index, files.getValue("index.sig"))
        val parsed = check.index(index)
        require(root.get("catalogVersion").asLong == parsed.version)
        val expectedPaths = mutableSetOf("index.json", "index.sig")
        val generations = parsed.generations.map { ref ->
            val sigPath = "signatures/${ref.id}.sig"
            expectedPaths.add(ref.path); expectedPaths.add(sigPath)
            val json = files.getValue(ref.path)
            check.checkDigest(json, ref)
            check.verify(json, files.getValue(sigPath))
            check.generation(json, ref.id)
        }
        require(files.keys == expectedPaths)
        validateIds(generations)
        return CatalogSnapshot(parsed.version, generations, CatalogSource.VERIFIED) to files
    }

    private fun validateIds(generations: List<CatalogGeneration>) {
        val models = generations.flatMap { it.models }
        require(models.distinctBy { it.modelId }.size == models.size)
        val variants = models.flatMap { it.variants }
        require(variants.distinctBy { it.variantId }.size == variants.size)
        require(variants.distinctBy { Triple(it.repoId, it.revision, it.path) }.size == variants.size)
    }

    private fun encode(files: Map<String, ByteArray>): ByteArray {
        val root = JsonObject()
        root.addProperty("catalogVersion", verifier!!.index(files.getValue("index.json")).version)
        val data = JsonObject()
        files.forEach { (path, bytes) -> data.addProperty(path, Base64.getEncoder().encodeToString(bytes)) }
        root.add("files", data)
        return root.toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        @Volatile private var shared: CatalogRepository? = null

        fun create(context: Context): CatalogRepository {
            val endpoint = BuildConfig.CATALOG_BASE_URL
            val key = BuildConfig.CATALOG_PUBLIC_KEY_SPKI_BASE64
            val verifier = runCatching { key.takeIf { it.isNotBlank() }?.let { CatalogVerifier(Base64.getDecoder().decode(it)) } }.getOrNull()
            val transport = runCatching { endpoint.takeIf { it.isNotBlank() }?.let(::HttpsCatalogTransport) }.getOrNull()
            return CatalogRepository(transport, verifier, AndroidCatalogCache(context.applicationContext))
        }

        fun shared(context: Context): CatalogRepository = shared ?: synchronized(this) {
            shared ?: create(context.applicationContext).also { shared = it }
        }

        private fun localFallback(): CatalogSnapshot = CatalogSnapshot(0, listOf(
            CatalogGeneration("gemma", "gemma-3", "Gemma 3n", "unavailable",
                AiModelCatalog.AVAILABLE_MODELS.map {
                    CatalogModel(it.id, "gemma", "gemma-3", it.name, it.description, "unavailable", emptyList())
                })
        ), CatalogSource.LOCAL_FALLBACK)
    }
}

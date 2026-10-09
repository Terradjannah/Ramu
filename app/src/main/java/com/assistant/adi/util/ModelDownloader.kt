package com.assistant.adi.util

import com.assistant.adi.data.catalog.CatalogVariant
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl

object ModelDownloader {
    private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    private val deliveryHosts = setOf(
        "huggingface.co", "cdn-lfs.huggingface.co", "cdn-lfs-us-1.hf.co",
        "cas-bridge.xethub.hf.co", "cas-server.xethub.hf.co", "transfer.xethub.hf.co"
    )

    suspend fun download(
        file: File, variant: CatalogVariant, token: String, legacyFile: File? = null,
        progress: (Long, Long, Float, Long) -> Unit
    ): File = downloadFrom(file, variant, token, legacyFile, "https://huggingface.co".toHttpUrl(),
        deliveryHosts, client, progress)

    internal suspend fun downloadFrom(
        file: File, variant: CatalogVariant, token: String, legacyFile: File?,
        base: HttpUrl, allowedHosts: Set<String>, http: OkHttpClient,
        progress: (Long, Long, Float, Long) -> Unit
    ): File = ModelFiles.lock(file).withLock {
        withContext(Dispatchers.IO) {
            DownloadValidation.validate(variant)
            require(base.host in allowedHosts && (base.isHttps || base.host == "localhost" || base.host == "127.0.0.1"))
            require(base.encodedPath == "/" && base.query == null && base.fragment == null)
            if (ModelFiles.verifyLocal(file, variant)) {
                progress(variant.sizeBytes, variant.sizeBytes, 0f, 0)
                return@withContext file
            }
            val staleMarker = File(file.path + ".complete")
            if (staleMarker.exists() && !staleMarker.delete())
                throw IOException("Tidak dapat membatalkan status model lama.")
            if (legacyFile != null && legacyFile != file && ModelFiles.verifyLocal(legacyFile, variant)) {
                progress(variant.sizeBytes, variant.sizeBytes, 0f, 0)
                return@withContext legacyFile
            }
            if (variant.gated && token.isBlank())
                throw IOException("Masukkan token Hugging Face untuk mengunduh model gated.")
            file.parentFile?.mkdirs()
            val part = ModelFiles.partial(file)
            val identityFile = ModelFiles.partialIdentity(file)
            val identity = DownloadValidation.partialIdentity(variant)
            if (part.exists() && (!identityFile.isFile || identityFile.readText() != identity ||
                    part.length() > variant.sizeBytes)) {
                if (!part.delete()) throw IOException("Tidak dapat membuang unduhan lama.")
            }
            identityFile.writeText(identity)
            var offset = part.length()
            if (file.parentFile!!.usableSpace < variant.sizeBytes - offset + 128L * 1024 * 1024)
                throw IOException("Ruang penyimpanan tidak cukup untuk sisa unduhan.")
            if (offset == variant.sizeBytes) {
                publish(file, part, variant)
                progress(variant.sizeBytes, variant.sizeBytes, 0f, 0)
                return@withContext file
            }
            val segments = variant.repoId.split('/') + "resolve" + variant.revision + variant.path.split('/')
            val url = base.newBuilder().apply { segments.forEach(::addPathSegment) }.build()
            var redirectCount = 0
            var currentUrl = url
            var sendToken = variant.gated
            while (true) {
                val request = Request.Builder().url(currentUrl).header("Accept-Encoding", "identity").apply {
                    if (offset > 0) header("Range", "bytes=$offset-")
                    if (sendToken) header("Authorization", "Bearer $token")
                }.build()
                val response = http.newCall(request).execute()
                if (response.isRedirect) {
                    response.use {
                        if (++redirectCount > 5) throw IOException("Terlalu banyak redirect Hugging Face.")
                        val next = currentUrl.resolve(response.header("Location") ?: "")
                            ?: throw IOException("Redirect Hugging Face tidak valid.")
                        if ((!next.isHttps && next.host != "localhost" && next.host != "127.0.0.1") || next.host !in allowedHosts ||
                            next.username.isNotEmpty() || next.password.isNotEmpty() ||
                            (next.port != 443 && next.host != "localhost" && next.host != "127.0.0.1"))
                            throw IOException("Host redirect Hugging Face tidak diizinkan.")
                        currentUrl = next
                        sendToken = false
                    }
                    continue
                }
                response.use {
                    when (response.code) {
                        401 -> throw IOException("Token Hugging Face tidak valid atau kedaluwarsa.")
                        403 -> throw IOException("Akses gated model belum disetujui di Hugging Face.")
                        200, 206 -> Unit
                        else -> throw IOException("Jaringan Hugging Face gagal (HTTP ${response.code}).")
                    }
                    val start = if (response.code == 200) 0L else offset
                    if (response.code == 206) {
                        val range = DownloadValidation.range(response.header("Content-Range"))
                            ?: throw IOException("Content-Range tidak valid.")
                        if (range.start != offset || range.total != variant.sizeBytes ||
                            range.end != variant.sizeBytes - 1)
                            throw IOException("Rentang unduhan berubah; coba lagi.")
                    }
                    val body = response.body ?: throw IOException("Respons unduhan kosong.")
                    if (body.contentLength() >= 0 && body.contentLength() != variant.sizeBytes - start)
                        throw IOException("Ukuran respons tidak sesuai katalog.")
                    RandomAccessFile(part, "rw").use { output ->
                        if (start == 0L) output.setLength(0)
                        output.seek(start)
                        var current = start
                        val started = System.nanoTime()
                        val buffer = ByteArray(64 * 1024)
                        progress(current, variant.sizeBytes, 0f, 0)
                        body.byteStream().use { input ->
                            while (true) {
                                ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (current + count > variant.sizeBytes) throw IOException("Respons melebihi ukuran katalog.")
                                output.write(buffer, 0, count)
                                current += count
                                val elapsed = ((System.nanoTime() - started) / 1e9).coerceAtLeast(0.001)
                                val speed = ((current - start) / 1048576.0 / elapsed).toFloat()
                                progress(current, variant.sizeBytes, speed,
                                    if (speed > 0) ((variant.sizeBytes - current) / 1048576.0 / speed).toLong() else 0)
                            }
                        }
                        output.fd.sync()
                        if (current != variant.sizeBytes) throw IOException("Unduhan terputus; lanjutkan untuk menyelesaikan.")
                    }
                }
                publish(file, part, variant)
                progress(variant.sizeBytes, variant.sizeBytes, 0f, 0)
                return@withContext file
            }
            @Suppress("UNREACHABLE_CODE")
            file
        }
    }

    private fun publish(file: File, part: File, variant: CatalogVariant) {
        if (part.length() != variant.sizeBytes || ModelFiles.sha256(part) != variant.sha256) {
            part.delete()
            ModelFiles.partialIdentity(file).delete()
            throw IOException("Hash model tidak cocok dengan katalog. Coba unduh lagi.")
        }
        val marker = File(file.path + ".complete")
        if (marker.exists() && !marker.delete()) throw IOException("Tidak dapat membatalkan status model lama.")
        if (file.exists() && !file.delete()) throw IOException("Tidak dapat mengganti berkas model lama.")
        if (!part.renameTo(file)) throw IOException("Tidak dapat menyelesaikan file model.")
        ModelFiles.markReady(file, variant)
        ModelFiles.partialIdentity(file).delete()
    }
}

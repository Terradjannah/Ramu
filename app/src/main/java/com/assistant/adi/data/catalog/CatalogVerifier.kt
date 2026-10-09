package com.assistant.adi.data.catalog

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.Strictness
import java.io.StringReader
import java.security.KeyFactory
import java.security.AlgorithmParameters
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

class CatalogVerifier(spki: ByteArray) {
    private val key: PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))

    init {
        val expected = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
        val actual = (key as? ECPublicKey)?.params
        require(actual != null && actual.curve == expected.curve && actual.generator == expected.generator &&
            actual.order == expected.order && actual.cofactor == expected.cofactor) { "Catalog key must use P-256" }
    }

    fun verify(document: ByteArray, signature: ByteArray) {
        require(signature.size in 1..1024) { "Invalid signature size" }
        val encoded = signature.toString(Charsets.US_ASCII).trim()
        require(encoded.matches(Regex("[A-Za-z0-9+/]+={0,2}"))) { "Invalid signature encoding" }
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(key)
        verifier.update(document)
        require(verifier.verify(Base64.getDecoder().decode(encoded))) { "Signature mismatch" }
    }

    data class GenerationRef(val id: String, val path: String, val size: Long, val sha256: String)
    data class Index(val version: Long, val generations: List<GenerationRef>)

    fun index(raw: ByteArray): Index {
        require(raw.size in 1..65536) { "Index size" }
        val root = parse(raw)
        root.fields("schemaVersion", "catalogVersion", "generations")
        require(root.long("schemaVersion") == 1L) { "Schema version" }
        val version = root.long("catalogVersion").also { require(it > 0) }
        val rows = root.array("generations", 1, 32).map { element ->
            val row = element.asJsonObject
            row.fields("generationId", "path", "sizeBytes", "sha256")
            val id = row.string("generationId", ID, 64)
            val path = row.string("path", GENERATION_PATH, 96)
            require(path == "generations/$id.json") { "Generation path mismatch" }
            val size = row.long("sizeBytes").also { require(it in 1..262144) }
            GenerationRef(id, path, size, row.string("sha256", SHA, 64))
        }
        require(rows.distinctBy { it.id }.size == rows.size && rows.distinctBy { it.path }.size == rows.size)
        return Index(version, rows)
    }

    fun generation(raw: ByteArray, expectedId: String): CatalogGeneration {
        require(raw.size in 1..262144) { "Generation size" }
        val root = parse(raw)
        root.fields("schemaVersion", "familyId", "generationId", "name", "status", "models")
        require(root.long("schemaVersion") == 1L)
        val family = root.string("familyId", ID, 64)
        val id = root.string("generationId", ID, 64)
        require(id == expectedId)
        val status = root.enum("status", "available", "unavailable")
        val models = root.array("models", 1, 64).map { element ->
            val row = element.asJsonObject
            row.fields("modelId", "familyId", "generationId", "name", "description", "status", "variants")
            val modelId = row.string("modelId", ID, 64)
            require(row.string("familyId", ID, 64) == family && row.string("generationId", ID, 64) == id)
            val modelStatus = row.enum("status", "available", "unavailable")
            val variants = row.array("variants", 0, 16).map { variant(it.asJsonObject) }
            require((modelStatus == "available") == variants.isNotEmpty())
            CatalogModel(modelId, family, id, row.string("name", 120), row.string("description", 500), modelStatus, variants)
        }
        require(models.distinctBy { it.modelId }.size == models.size)
        require((status == "available") == models.any { it.status == "available" })
        return CatalogGeneration(family, id, root.string("name", 120), status, models)
    }

    private fun variant(row: JsonObject): CatalogVariant {
        row.fields("variantId", "artifactVersion", "format", "runtime", "testedBackends", "minApi", "abis", "minRamBytes", "recommendedRamBytes", "minStorageBytes", "hardware", "repoId", "revision", "path", "sizeBytes", "sha256", "evidence")
        val runtime = row.obj("runtime").also { it.fields("minVersion", "maxVersion") }
        val hardware = row.obj("hardware").also { it.fields("socIds", "driverIds") }
        val evidence = row.obj("evidence").also { it.fields("artifactSha256Verified", "artifactSource", "license", "gated", "runtimeTest", "backendTests") }
        require(evidence.bool("artifactSha256Verified"))
        val backends = row.array("testedBackends", 1, 3).map { it.asString.also { value -> require(value in setOf("cpu", "gpu", "npu")) } }
        val tests = evidence.obj("backendTests")
        require(tests.keySet() == backends.toSet() && backends.distinct().size == backends.size)
        tests.keySet().forEach { tests.string(it, 300) }
        val abis = row.array("abis", 1, 4).map { it.asString.also { value -> require(value in setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")) } }
        require(abis.distinct().size == abis.size)
        val minRam = row.long("minRamBytes").also { require(it > 0) }
        val recommendedRam = row.long("recommendedRamBytes").also { require(it >= minRam) }
        val size = row.long("sizeBytes").also { require(it > 0) }
        val storage = row.long("minStorageBytes").also { require(it >= size) }
        val minRuntime = runtime.string("minVersion", VERSION, 24)
        val maxRuntime = runtime.string("maxVersion", VERSION, 24)
        require(compareVersions(minRuntime, maxRuntime) <= 0)
        require(row.enum("format", "litertlm") == "litertlm")
        val api = row.long("minApi").also { require(it in 29..100) }.toInt()
        val artifactVersion = row.long("artifactVersion").also { require(it > 0) }
        return CatalogVariant(row.string("variantId", ID, 64), artifactVersion, "litertlm", minRuntime, maxRuntime,
            backends, api, abis, minRam, recommendedRam, storage,
            hardware.ids("socIds"), hardware.ids("driverIds"), row.string("repoId", REPO, 128),
            row.string("revision", COMMIT, 40), row.string("path", ARTIFACT_PATH, 200), size,
            row.string("sha256", SHA, 64), evidence.string("license", 100).also {
                evidence.string("artifactSource", 300); evidence.string("runtimeTest", 300)
            }, evidence.bool("gated"))
    }

    fun checkDigest(raw: ByteArray, ref: GenerationRef) {
        require(raw.size.toLong() == ref.size && sha256(raw) == ref.sha256) { "Generation digest mismatch" }
    }

    private fun sha256(raw: ByteArray) = MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }
    private fun parse(raw: ByteArray): JsonObject {
        val reader = JsonReader(StringReader(raw.toString(Charsets.UTF_8))).apply { strictness = Strictness.STRICT }
        val root = readValue(reader, 0).asJsonObject
        require(reader.peek() == JsonToken.END_DOCUMENT) { "Trailing JSON" }
        return root
    }

    private fun readValue(reader: JsonReader, depth: Int): JsonElement {
        require(depth <= 12) { "Catalog nesting too deep" }
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                val result = JsonObject()
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    require(!result.has(name)) { "Duplicate JSON field" }
                    result.add(name, readValue(reader, depth + 1))
                }
                reader.endObject()
                result
            }
            JsonToken.BEGIN_ARRAY -> {
                val result = JsonArray()
                reader.beginArray()
                while (reader.hasNext()) result.add(readValue(reader, depth + 1))
                reader.endArray()
                result
            }
            JsonToken.STRING -> JsonPrimitive(reader.nextString())
            JsonToken.NUMBER -> JsonPrimitive(java.math.BigDecimal(reader.nextString()))
            JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
            JsonToken.NULL -> error("Null catalog field")
            else -> error("Invalid JSON token")
        }
    }
    private fun JsonObject.fields(vararg names: String) = require(keySet() == names.toSet()) { "Unexpected fields" }
    private fun JsonObject.obj(name: String) = getAsJsonObject(name) ?: error("Missing $name")
    private fun JsonObject.array(name: String, min: Int, max: Int): JsonArray = getAsJsonArray(name).also { require(it.size() in min..max) }
    private fun JsonObject.long(name: String): Long = get(name).asJsonPrimitive.also { require(it.isNumber && it.asString.matches(Regex("[0-9]+"))) }.asLong
    private fun JsonObject.bool(name: String): Boolean = get(name).asJsonPrimitive.also { require(it.isBoolean) }.asBoolean
    private fun JsonObject.string(name: String, max: Int): String = get(name).asJsonPrimitive.also { require(it.isString) }.asString.also { require(it.isNotEmpty() && it.length <= max) }
    private fun JsonObject.string(name: String, pattern: Regex, max: Int) = string(name, max).also { require(pattern.matches(it)) }
    private fun JsonObject.enum(name: String, vararg choices: String) = string(name, 32).also { require(it in choices) }
    private fun JsonObject.ids(name: String) = array(name, 0, 16).map { it.asString.also { value -> require(value.length <= 64 && ID.matches(value)) } }.also { require(it.distinct().size == it.size) }
    private fun compareVersions(left: String, right: String): Int {
        val a = left.split('.').map(String::toLong)
        val b = right.split('.').map(String::toLong)
        for (index in 0..2) {
            val comparison = a[index].compareTo(b[index])
            if (comparison != 0) return comparison
        }
        return 0
    }

    companion object {
        private val ID = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
        private val GENERATION_PATH = Regex("generations/[a-z0-9]+(?:-[a-z0-9]+)*\\.json")
        private val SHA = Regex("[a-f0-9]{64}")
        private val COMMIT = Regex("[a-f0-9]{40}")
        private val VERSION = Regex("[0-9]+\\.[0-9]+\\.[0-9]+")
        private val REPO = Regex("[A-Za-z0-9][A-Za-z0-9_.-]*/[A-Za-z0-9][A-Za-z0-9_.-]*")
        private val ARTIFACT_PATH = Regex("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*\\.litertlm")
    }
}

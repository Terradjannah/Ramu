package com.assistant.adi.data.catalog

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import okhttp3.mockwebserver.MockWebServer
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64

class CatalogRepositoryTest {
    private val keys = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    private val verifier = CatalogVerifier(keys.public.encoded)
    private val generation = """{"schemaVersion":1,"familyId":"gemma","generationId":"gemma-3","name":"Gemma 3n","status":"unavailable","models":[{"modelId":"gemma-3n-e2b","familyId":"gemma","generationId":"gemma-3","name":"E2B","description":"Local","status":"unavailable","variants":[]}]}""".toByteArray()

    private fun signed(bytes: ByteArray): ByteArray {
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(keys.private)
        signer.update(bytes)
        return (Base64.getEncoder().encodeToString(signer.sign()) + "\n").toByteArray()
    }

    private fun release(version: Int, child: ByteArray = generation): MutableMap<String, ByteArray> {
        val digest = MessageDigest.getInstance("SHA-256").digest(child).joinToString("") { "%02x".format(it) }
        val index = """{"schemaVersion":1,"catalogVersion":$version,"generations":[{"generationId":"gemma-3","path":"generations/gemma-3.json","sizeBytes":${child.size},"sha256":"$digest"}]}""".toByteArray()
        return mutableMapOf("index.json" to index, "index.sig" to signed(index),
            "generations/gemma-3.json" to child, "signatures/gemma-3.sig" to signed(child))
    }

    private class MemoryCache(var bytes: ByteArray? = null) : CatalogCache {
        override fun read() = bytes
        override fun write(bytes: ByteArray) { this.bytes = bytes }
    }

    private class FakeTransport(var files: Map<String, ByteArray>) : CatalogTransport {
        override fun get(path: String, limit: Int): ByteArray = files.getValue(path).also { require(it.size <= limit) }
    }

    @Test fun verifiedReleaseSurvivesOfflineAndRejectsRollback() = runBlocking {
        val cache = MemoryCache()
        val transport = FakeTransport(release(2))
        val repository = CatalogRepository(transport, verifier, cache)
        repository.refresh()
        assertTrue(repository.state.value is CatalogState.Verified)
        assertEquals(2, repository.state.value.snapshot.version)
        val offline = CatalogRepository(null, verifier, cache)
        offline.loadCache()
        assertTrue(offline.state.value is CatalogState.OfflineCached)
        assertEquals(2, offline.state.value.snapshot.version)
        offline.refresh()
        assertTrue(offline.state.value is CatalogState.Failed)
        assertEquals(2, offline.state.value.snapshot.version)
        transport.files = release(1)
        repository.refresh()
        assertTrue(repository.state.value is CatalogState.Failed)
        assertEquals(2, repository.state.value.snapshot.version)
    }

    @Test fun rejectedResponsesKeepLastSnapshot() = runBlocking {
        val cases = listOf<(MutableMap<String, ByteArray>) -> Unit>(
            { it["index.sig"] = "bad".toByteArray() },
            { it["signatures/gemma-3.sig"] = "bad".toByteArray() },
            { it["generations/gemma-3.json"] = "tampered".toByteArray() },
            { it["index.json"] = ByteArray(65537) },
            { it.clear() }
        )
        for (mutate in cases) {
            val cache = MemoryCache()
            val transport = FakeTransport(release(2))
            val repository = CatalogRepository(transport, verifier, cache)
            repository.refresh()
            val files = release(3)
            mutate(files)
            transport.files = files
            repository.refresh()
            assertTrue(repository.state.value is CatalogState.Failed)
            assertEquals(2, repository.state.value.snapshot.version)
        }
    }

    @Test fun badCacheFallsBackAndTraversalIsRejected() = runBlocking {
        val badCache = MemoryCache("corrupt".toByteArray())
        val repository = CatalogRepository(null, verifier, badCache)
        repository.loadCache()
        assertEquals(CatalogSource.LOCAL_FALLBACK, repository.state.value.snapshot.source)
        val unsafe = """{"schemaVersion":1,"catalogVersion":2,"generations":[{"generationId":"gemma-3","path":"../escape.json","sizeBytes":1,"sha256":"${"0".repeat(64)}"}]}""".toByteArray()
        val transport = FakeTransport(mutableMapOf("index.json" to unsafe, "index.sig" to signed(unsafe)))
        val remote = CatalogRepository(transport, verifier, badCache)
        remote.refresh()
        assertTrue(remote.state.value is CatalogState.Failed)
        assertEquals(CatalogSource.LOCAL_FALLBACK, remote.state.value.snapshot.source)
    }

    @Test fun duplicateJsonFieldsAndCleartextEndpointAreRejected() {
        val duplicate = """{"schemaVersion":1,"catalogVersion":2,"catalogVersion":3,"generations":[]}""".toByteArray()
        val failure = runCatching { verifier.index(duplicate) }
        assertTrue(failure.isFailure)
        MockWebServer().use { server ->
            server.start()
            assertTrue(runCatching { HttpsCatalogTransport(server.url("/catalog/v1/").toString()) }.isFailure)
        }
    }
}

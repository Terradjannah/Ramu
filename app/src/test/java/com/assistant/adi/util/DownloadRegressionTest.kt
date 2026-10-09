package com.assistant.adi.util

import com.assistant.adi.data.catalog.CatalogVariant
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DownloadRegressionTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var server: MockWebServer
    private val bytes = "0123456789"
    private val hash = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
        .joinToString("") { "%02x".format(it) }

    @Before fun start() { server = MockWebServer(); server.start() }
    @After fun stop() { server.shutdown() }
    private fun file() = File(temporary.root, "approved.litertlm")
    private fun variant(revision: String = "a".repeat(40), sha: String = hash, gated: Boolean = true) = CatalogVariant(
        "cpu-int4", 1, "litertlm", "0.16.1", "0.16.1", listOf("cpu"), 29,
        listOf("arm64-v8a"), 1, 1, 10, emptyList(), emptyList(),
        "google/model", revision, "weights/model.litertlm", 10, sha, "gemma", gated
    )
    private suspend fun download(file: File = file(), variant: CatalogVariant = variant(), token: String = "secret",
        legacy: File? = null): File = ModelDownloader.downloadFrom(
        file, variant, token, legacy, server.url("/"), setOf(server.url("/").host),
        OkHttpClient.Builder().followRedirects(false).build()
    ) { _, _, _, _ -> }

    @Test fun usesPinnedCommitAndExpectedHash() = runBlocking {
        server.enqueue(MockResponse().setBody(bytes))
        val result = download()
        val request = server.takeRequest()
        assertEquals("/google/model/resolve/${"a".repeat(40)}/weights/model.litertlm", request.path)
        assertEquals("Bearer secret", request.getHeader("Authorization"))
        assertTrue(ModelFiles.isReady(result, variant()))
    }

    @Test fun redirectNeverReceivesBearerToken() = runBlocking {
        val delivery = MockWebServer()
        delivery.start()
        try {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", delivery.url("/file")))
            delivery.enqueue(MockResponse().setBody(bytes))
            download()
            assertEquals("Bearer secret", server.takeRequest().getHeader("Authorization"))
            assertNull(delivery.takeRequest().getHeader("Authorization"))
        } finally { delivery.shutdown() }
    }

    @Test fun publicArtifactDoesNotSendStoredToken() = runBlocking {
        server.enqueue(MockResponse().setBody(bytes))
        download(variant = variant(gated = false))
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test fun rejectsUnapprovedRedirect() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://example.org/model"))
        assertTrue(runCatching { download() }.isFailure)
        assertFalse(ModelFiles.isReady(file()))
    }

    @Test fun resumeOnlyMatchingRevisionAndValidRange() = runBlocking {
        val target = file()
        ModelFiles.partial(target).writeText("01234")
        ModelFiles.partialIdentity(target).writeText(DownloadValidation.partialIdentity(variant()))
        server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 5-9/10").setBody("56789"))
        download()
        assertEquals("bytes=5-", server.takeRequest().getHeader("Range"))
        assertEquals(bytes, target.readText())

        ModelFiles.delete(target)
        ModelFiles.partial(target).writeText("OLD")
        ModelFiles.partialIdentity(target).writeText(DownloadValidation.partialIdentity(variant()))
        server.enqueue(MockResponse().setBody(bytes))
        download(target, variant("b".repeat(40)))
        assertNull(server.takeRequest().getHeader("Range"))
        assertEquals(bytes, target.readText())
    }

    @Test fun wrongRangeSizeAndHashNeverReady() = runBlocking {
        val target = file()
        ModelFiles.partial(target).writeText("01234")
        ModelFiles.partialIdentity(target).writeText(DownloadValidation.partialIdentity(variant()))
        server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-4/10").setBody("01234"))
        assertTrue(runCatching { download() }.isFailure)
        assertFalse(ModelFiles.isReady(target))
        server.enqueue(MockResponse().setBody("short"))
        assertTrue(runCatching { download() }.isFailure)
        assertFalse(ModelFiles.isReady(target))
        server.enqueue(MockResponse().setBody(bytes))
        assertTrue(runCatching { download(target, variant(sha = "f".repeat(64))) }.isFailure)
        assertFalse(ModelFiles.isReady(target))
        assertFalse(ModelFiles.partial(target).exists())
    }

    @Test fun verifiedLocalArtifactIsReusedAcrossRevisionsWithoutNetwork() = runBlocking {
        val legacy = File(temporary.root, "legacy.litertlm")
        legacy.writeText(bytes)
        ModelFiles.markReady(legacy)
        val result = download(file(), variant("b".repeat(40)), "", legacy)
        assertEquals(legacy, result)
        assertTrue(ModelFiles.isReady(legacy, variant("b".repeat(40))))
        assertEquals(0, server.requestCount)
    }
}

package com.assistant.adi.util

import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelLifecycleRegressionTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun deletionRemovesDataAndAllDownloadMetadata() {
        val file = File(temp.root, "test.litertlm").apply { writeText("model") }
        ModelFiles.markReady(file)
        ModelFiles.partial(file).writeText("partial")
        File(file.path + ".etag").writeText("validator")
        ModelFiles.delete(file)
        assertTrue(temp.root.listFiles()!!.isEmpty())
        ModelFiles.delete(file) // Retrying a completed deletion is harmless.
    }

    @Test fun failedMarkerDeletionPreservesModelData() {
        val file = File(temp.root, "test.litertlm").apply { writeText("model") }
        File(file.path + ".complete").mkdir()
        File(file.path + ".complete/blocked").writeText("keep")
        assertThrows(IOException::class.java) { ModelFiles.delete(file) }
        assertEquals("model", file.readText())
    }

    @Test fun failedDataDeletionLeavesModelUnready() {
        val file = File(temp.root, "test.litertlm").apply { mkdir() }
        File(file, "blocked").writeText("keep")
        File(file.path + ".complete").writeText("123")
        assertThrows(IOException::class.java) { ModelFiles.delete(file) }
        assertFalse(File(file.path + ".complete").exists())
        assertFalse(ModelFiles.isReady(file))
    }

    @Test fun cancellationDuringBlockingInitializationPreventsGeneration() {
        val request = GenerationCancellation()
        val initialized = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val generated = java.util.concurrent.atomic.AtomicBoolean(false)
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val worker = Thread {
            request.checkpoint()
            initialized.countDown()
            resume.await(2, TimeUnit.SECONDS)
            try { request.checkpoint(); generated.set(true) }
            catch (_: CancellationException) { cancelled.set(true) }
        }
        worker.start()
        assertTrue(initialized.await(2, TimeUnit.SECONDS))
        request.cancel()
        resume.countDown()
        worker.join(2000)
        assertFalse(worker.isAlive)
        assertTrue(cancelled.get())
        assertFalse(generated.get())
        GenerationCancellation().checkpoint() // Cancellation never leaks into a new request.
    }
}

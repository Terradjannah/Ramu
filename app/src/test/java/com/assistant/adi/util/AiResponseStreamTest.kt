package com.assistant.adi.util

import com.google.ai.edge.litertlm.Message
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class AiResponseStreamTest {
    @Test fun nativeThreadCompletionPreservesBurstAndAllowsNextRequest() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        try {
            repeat(2) {
                val messages = (0 until 256).map {
                    Message.model("token-$it")
                }
                val received = withTimeout(10_000) {
                    aiResponseStream { callback ->
                        executor.execute {
                            messages.forEach(callback::onMessage)
                            callback.onDone()
                        }
                    }.onEach { delay(1) }.toList()
                }
                assertEquals(messages, received)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun callbackErrorReachesCollector() = runBlocking {
        val expected = IllegalStateException("native failed")
        try {
            withTimeout(5_000) {
                aiResponseStream { it.onError(expected) }.collect()
            }
            fail("Expected native error")
        } catch (actual: IllegalStateException) {
            // Coroutine stack-trace recovery may copy the exception instance.
            assertEquals(expected.javaClass, actual.javaClass)
            assertEquals(expected.message, actual.message)
        }
    }

    @Test fun nativeCancellationReachesCollector() = runBlocking {
        val expected = CancellationException("native cancelled")
        try {
            withTimeout(5_000) {
                aiResponseStream { it.onError(expected) }.collect()
            }
            fail("Expected cancellation")
        } catch (actual: CancellationException) {
            assertEquals(expected.javaClass, actual.javaClass)
            assertEquals(expected.message, actual.message)
        }
    }
}

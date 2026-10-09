package com.assistant.adi.util

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

/** A request-local flag: native initialization must return before its engine can be closed. */
class GenerationCancellation {
    private val requested = AtomicBoolean(false)
    val isCancelled get() = requested.get()
    fun cancel() { requested.set(true) }
    fun checkpoint() { if (isCancelled) throw CancellationException("Permintaan dibatalkan") }
}

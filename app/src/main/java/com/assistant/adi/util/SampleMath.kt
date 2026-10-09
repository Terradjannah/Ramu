package com.assistant.adi.util

object SampleMath {
    fun measuredKilobytesPerSecond(before: Long, after: Long, elapsedMs: Long): Double? =
        if (before < 0 || after < 0 || after < before || elapsedMs <= 0) null
        else (after - before) / 1024.0 * 1000.0 / elapsedMs

    fun kilobytesPerSecond(before: Long, after: Long, elapsedMs: Long): Double =
        measuredKilobytesPerSecond(before, after, elapsedMs) ?: 0.0
}

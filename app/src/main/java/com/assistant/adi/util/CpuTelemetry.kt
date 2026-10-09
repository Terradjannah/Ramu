package com.assistant.adi.util

import java.io.File

object CpuTelemetry {
    fun usagePercent(): Int = runCatching {
        fun snapshot(): List<Long> = File("/proc/stat").useLines { lines ->
            lines.first().trim().split(Regex("\\s+")).drop(1).take(8).map { it.toLong() }
        }
        val a = snapshot(); Thread.sleep(100); val b = snapshot()
        val total = b.sum() - a.sum()
        val idle = (b[3] + b[4]) - (a[3] + a[4])
        if (total <= 0) -1 else ((total - idle) * 100 / total).toInt().coerceIn(0,100)
    }.getOrDefault(-1)
    fun temperature(): Float {
        val zones = File("/sys/class/thermal").listFiles().orEmpty()
        for (zone in zones) {
            val result = runCatching {
                val type = File(zone, "type").readText().trim().lowercase()
                if (!(type.contains("cpu") || type.contains("soc"))) return@runCatching Float.NaN
                val raw = File(zone, "temp").readText().trim().toFloat()
                val c = if (raw > 1000) raw / 1000f else raw
                if (c in 0f..120f) c else Float.NaN
            }.getOrDefault(Float.NaN)
            if (result.isFinite()) return result
        }
        return Float.NaN
    }
}

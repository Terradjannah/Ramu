package com.assistant.adi.ui.buddy

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Keeps chart coordinates small enough for Float while retaining exact timestamps in the source rows. */
class MonitorChartPolicy(timestamps: List<Long>, intervalMinutes: Int) {
    val originMillis: Long = timestamps.minOrNull() ?: 0L
    private val gapMillis = intervalMinutes.coerceAtLeast(1).toLong() * 2L * 60_000L

    fun x(timestamp: Long): Float = (timestamp - originMillis) / 60_000f
    fun timestamp(x: Float): Long = originMillis + (x * 60_000f).toLong()
    fun label(x: Float, withDate: Boolean = false): String = SimpleDateFormat(
        if (withDate) "d MMM HH:mm" else "HH:mm", Locale.getDefault()
    ).format(Date(timestamp(x)))

    fun <T> segments(rows: List<T>, timestamp: (T) -> Long, valid: (T) -> Boolean): List<List<T>> {
        val result = mutableListOf<List<T>>()
        var current = mutableListOf<T>()
        var previous: Long? = null
        rows.sortedBy(timestamp).forEach { row ->
            val time = timestamp(row)
            if (!valid(row)) {
                if (current.isNotEmpty()) result.add(current)
                current = mutableListOf()
                previous = null
            } else {
                if (previous != null && time - previous!! > gapMillis) {
                    result.add(current)
                    current = mutableListOf()
                }
                current.add(row)
                previous = time
            }
        }
        if (current.isNotEmpty()) result.add(current)
        return result
    }
}

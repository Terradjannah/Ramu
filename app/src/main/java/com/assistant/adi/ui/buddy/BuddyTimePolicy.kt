package com.assistant.adi.ui.buddy

import java.util.Calendar

object BuddyTimePolicy {
    fun isNight(hour: Int): Boolean = hour >= NIGHT_START_HOUR || hour < DAY_START_HOUR

    fun millisUntilNextBoundary(nowMillis: Long): Long {
        val next = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            when {
                get(Calendar.HOUR_OF_DAY) < DAY_START_HOUR -> set(Calendar.HOUR_OF_DAY, DAY_START_HOUR)
                get(Calendar.HOUR_OF_DAY) < NIGHT_START_HOUR -> set(Calendar.HOUR_OF_DAY, NIGHT_START_HOUR)
                else -> {
                    add(Calendar.DAY_OF_YEAR, 1)
                    set(Calendar.HOUR_OF_DAY, DAY_START_HOUR)
                }
            }
        }
        return (next.timeInMillis - nowMillis).coerceAtLeast(1L)
    }

    private const val NIGHT_START_HOUR = 22
    private const val DAY_START_HOUR = 5
}

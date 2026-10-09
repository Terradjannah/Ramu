package com.assistant.adi.ui.buddy

import com.assistant.adi.data.ScreenTimeTrend
import java.time.LocalDate

data class ScreenTimeDaySlot(val date: String, val totalMinutes: Long?)

object ScreenTimeHistoryPolicy {
    fun sevenDays(today: LocalDate, records: List<ScreenTimeTrend>): List<ScreenTimeDaySlot> {
        val byDate = records.associateBy { it.date }
        return (6L downTo 0L).map { offset ->
            val date = today.minusDays(offset).toString()
            ScreenTimeDaySlot(date, byDate[date]?.totalMinutes)
        }
    }
}

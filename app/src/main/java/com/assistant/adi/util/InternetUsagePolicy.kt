package com.assistant.adi.util

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.min

enum class InternetUsageStatus {
    LOADING,
    PERMISSION_REQUIRED,
    UNAVAILABLE,
    AVAILABLE
}

data class InternetUsageWindow(
    val startMillis: Long,
    val endMillis: Long,
    val localDate: LocalDate,
    val zoneId: String
)

data class InternetUsageCycle(
    val startMillis: Long,
    val endMillis: Long,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val zoneId: String
)

data class InternetTransportUsage(
    val wifiBytes: Long?,
    val mobileBytes: Long?,
    val totalBytes: Long?,
    val status: InternetUsageStatus
)

data class InternetUsageLimits(
    val dailyTargetBytes: Long?,
    val dailyReached: Boolean,
    val cycleReached: Boolean
)

object InternetUsagePolicy {
    const val DECIMAL_GIGABYTE_BYTES = 1_000_000_000L

    fun dayWindow(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): InternetUsageWindow {
        val start = date.atStartOfDay(zone)
        val end = date.plusDays(1).atStartOfDay(zone)
        return InternetUsageWindow(
            startMillis = start.toInstant().toEpochMilli(),
            endMillis = end.toInstant().toEpochMilli(),
            localDate = date,
            zoneId = zone.id
        )
    }

    fun activeCycle(
        today: LocalDate = LocalDate.now(),
        cycleStartDay: Int = 1,
        zone: ZoneId = ZoneId.systemDefault()
    ): InternetUsageCycle {
        require(cycleStartDay in 1..31)
        val currentMonth = YearMonth.from(today)
        val currentAnchor = anchor(currentMonth, cycleStartDay)
        val startMonth = if (today < currentAnchor) currentMonth.minusMonths(1) else currentMonth
        val startDate = anchor(startMonth, cycleStartDay)
        val endDate = anchor(startMonth.plusMonths(1), cycleStartDay)
        return InternetUsageCycle(
            startMillis = startDate.atStartOfDay(zone).toInstant().toEpochMilli(),
            endMillis = endDate.atStartOfDay(zone).toInstant().toEpochMilli(),
            startDate = startDate,
            endDate = endDate,
            zoneId = zone.id
        )
    }

    fun transportUsage(
        wifiBytes: Long?,
        mobileBytes: Long?,
        status: InternetUsageStatus = if (wifiBytes != null && mobileBytes != null) {
            InternetUsageStatus.AVAILABLE
        } else {
            InternetUsageStatus.UNAVAILABLE
        }
    ): InternetTransportUsage {
        val validWifi = wifiBytes?.takeIf { it >= 0 }
        val validMobile = mobileBytes?.takeIf { it >= 0 }
        val total = if (validWifi != null && validMobile != null) checkedAdd(validWifi, validMobile) else null
        val complete = total != null && status == InternetUsageStatus.AVAILABLE
        return InternetTransportUsage(
            wifiBytes = validWifi,
            mobileBytes = validMobile,
            totalBytes = if (complete) total else null,
            status = if (complete) InternetUsageStatus.AVAILABLE else status.takeUnless { it == InternetUsageStatus.AVAILABLE }
                ?: InternetUsageStatus.UNAVAILABLE
        )
    }

    fun totalBytes(downloadBytes: Long, uploadBytes: Long): Long? =
        if (downloadBytes < 0 || uploadBytes < 0) null else checkedAdd(downloadBytes, uploadBytes)

    fun limits(
        monthlyBudgetBytes: Long?,
        targetDays: Int,
        dailyUsage: InternetTransportUsage,
        cycleUsage: InternetTransportUsage
    ): InternetUsageLimits {
        require(targetDays in 1..31)
        val budget = monthlyBudgetBytes?.takeIf { it > 0 }
        val dailyTarget = budget?.div(targetDays)
        return InternetUsageLimits(
            dailyTargetBytes = dailyTarget,
            dailyReached = dailyTarget != null && dailyUsage.status == InternetUsageStatus.AVAILABLE &&
                (dailyUsage.totalBytes ?: Long.MIN_VALUE) >= dailyTarget,
            cycleReached = budget != null && cycleUsage.status == InternetUsageStatus.AVAILABLE &&
                (cycleUsage.totalBytes ?: Long.MIN_VALUE) >= budget
        )
    }

    fun decimalGigabytes(bytes: Long): Double? = bytes.takeIf { it >= 0 }?.toDouble()?.div(DECIMAL_GIGABYTE_BYTES)

    private fun anchor(month: YearMonth, day: Int): LocalDate = month.atDay(min(day, month.lengthOfMonth()))

    private fun checkedAdd(first: Long, second: Long): Long? =
        if (Long.MAX_VALUE - first < second) null else first + second
}

package com.assistant.adi.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class InternetUsagePolicyTest {
    @Test fun dayWindowUsesLocalMidnightsAcrossDst() {
        val zone = ZoneId.of("America/New_York")
        val spring = InternetUsagePolicy.dayWindow(LocalDate.of(2026, 3, 8), zone)
        val fall = InternetUsagePolicy.dayWindow(LocalDate.of(2026, 11, 1), zone)
        assertEquals(23 * 60 * 60 * 1000L, spring.endMillis - spring.startMillis)
        assertEquals(25 * 60 * 60 * 1000L, fall.endMillis - fall.startMillis)
        assertEquals("America/New_York", spring.zoneId)
    }

    @Test fun cycleClampsStartDayAndCrossesYear() {
        val zone = ZoneId.of("Asia/Jakarta")
        val before = InternetUsagePolicy.activeCycle(LocalDate.of(2027, 1, 30), 31, zone)
        assertEquals(LocalDate.of(2026, 12, 31), before.startDate)
        assertEquals(LocalDate.of(2027, 1, 31), before.endDate)
        val leap = InternetUsagePolicy.activeCycle(LocalDate.of(2028, 2, 29), 31, zone)
        assertEquals(LocalDate.of(2028, 2, 29), leap.startDate)
        assertEquals(LocalDate.of(2028, 3, 31), leap.endDate)
        val nonLeap = InternetUsagePolicy.activeCycle(LocalDate.of(2027, 2, 28), 30, zone)
        assertEquals(LocalDate.of(2027, 2, 28), nonLeap.startDate)
        assertEquals(LocalDate.of(2027, 3, 30), nonLeap.endDate)
    }

    @Test fun cycleSupportsAllBoundaryStartDays() {
        val date = LocalDate.of(2027, 3, 15)
        assertEquals(LocalDate.of(2027, 3, 1), InternetUsagePolicy.activeCycle(date, 1).startDate)
        assertEquals(LocalDate.of(2027, 2, 28), InternetUsagePolicy.activeCycle(date, 29).startDate)
        assertEquals(LocalDate.of(2027, 2, 28), InternetUsagePolicy.activeCycle(date, 30).startDate)
        assertEquals(LocalDate.of(2027, 2, 28), InternetUsagePolicy.activeCycle(date, 31).startDate)
    }

    @Test fun completeTransportTotalsAndLimitsRespectExactBoundary() {
        val daily = InternetUsagePolicy.transportUsage(700L, 300L)
        val cycle = InternetUsagePolicy.transportUsage(7_000L, 3_000L)
        val limits = InternetUsagePolicy.limits(10_000L, 10, daily, cycle)
        assertEquals(1_000L, daily.totalBytes)
        assertEquals(1_000L, limits.dailyTargetBytes)
        assertTrue(limits.dailyReached)
        assertTrue(limits.cycleReached)
    }

    @Test fun emptyBudgetAndIncompleteTransportDoNotTriggerLimits() {
        val incomplete = InternetUsagePolicy.transportUsage(100L, null)
        assertNull(incomplete.totalBytes)
        assertEquals(InternetUsageStatus.UNAVAILABLE, incomplete.status)
        val noBudget = InternetUsagePolicy.limits(null, 30, incomplete, incomplete)
        assertNull(noBudget.dailyTargetBytes)
        assertFalse(noBudget.dailyReached)
        assertFalse(noBudget.cycleReached)
    }

    @Test fun rejectsNegativeAndOverflowingByteTotals() {
        assertNull(InternetUsagePolicy.totalBytes(-1L, 1L))
        assertNull(InternetUsagePolicy.totalBytes(Long.MAX_VALUE, 1L))
        assertNull(InternetUsagePolicy.transportUsage(Long.MAX_VALUE, 1L).totalBytes)
        assertEquals(0.000000001, InternetUsagePolicy.decimalGigabytes(1L)!!, 0.0)
    }
}

package com.assistant.adi.service

import com.assistant.adi.data.InternetUsageDaily
import com.assistant.adi.util.InternetUsagePolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class SamplingCadenceTest {
    @Test fun `each supported interval waits until its boundary`() {
        val now = 8 * 60 * 60 * 1000L
        listOf(15, 30, 45, 60).forEach { minutes ->
            val interval = minutes * 60_000L
            assertFalse(SamplingCadence.isDue(now, now - interval + 1, interval))
            assertTrue(SamplingCadence.isDue(now, now - interval, interval))
        }
    }

    @Test fun `a fresh process uses the durable commit time`() {
        val committedAt = 10_000_000L
        val interval = 30 * 60_000L
        assertFalse(SamplingCadence.isDue(committedAt + interval - 1, committedAt, interval))
        assertTrue(SamplingCadence.isDue(committedAt + interval, committedAt, interval))
    }

    @Test fun `an interval change is evaluated against the latest interval`() {
        val committedAt = 20_000_000L
        val now = committedAt + 20 * 60_000L
        assertTrue(SamplingCadence.isDue(now, committedAt, 15 * 60_000L))
        assertFalse(SamplingCadence.isDue(now, committedAt, 30 * 60_000L))
    }

    @Test fun `no prior committed sample is due immediately`() {
        assertTrue(SamplingCadence.isDue(1_000L, 0L, 15 * 60_000L))
    }

    @Test fun `visible activity and disabled monitoring never begin a batch`() {
        assertFalse(SamplingCadence.shouldStart(monitoringEnabled = true, foregroundVisible = true))
        assertFalse(SamplingCadence.shouldStart(monitoringEnabled = false, foregroundVisible = false))
        assertTrue(SamplingCadence.shouldStart(monitoringEnabled = true, foregroundVisible = false))
    }

    @Test fun `a failed attempt remains due because it has no committed timestamp`() {
        val lastCommittedAt = 5_000_000L
        val retryAt = lastCommittedAt + 15 * 60_000L
        assertTrue(SamplingCadence.isDue(retryAt, lastCommittedAt, 15 * 60_000L))
    }

    @Test fun `due batch rereads today and only incomplete past days`() {
        val zone = ZoneId.of("UTC")
        val today = LocalDate.of(2026, 3, 3)
        val cycle = InternetUsagePolicy.activeCycle(today, zone = zone)
        val march1 = InternetUsagePolicy.dayWindow(today.minusDays(2), zone)
        val march2 = InternetUsagePolicy.dayWindow(today.minusDays(1), zone)
        val march3 = InternetUsagePolicy.dayWindow(today, zone)
        val now = march3.startMillis + 12 * 60 * 60 * 1000L
        val windows = InternetUsageSamplingPlan.windowsToRead(
            now,
            cycle,
            listOf(finalizedDay(march1), finalizedDay(march2, finalized = false), finalizedDay(march3))
        )

        assertEquals(listOf(march2.startMillis, march3.startMillis), windows.map { it.startMillis })
    }

    @Test fun `due batch fills a missing cycle day without rereading finalized history`() {
        val zone = ZoneId.of("UTC")
        val today = LocalDate.of(2026, 3, 3)
        val cycle = InternetUsagePolicy.activeCycle(today, zone = zone)
        val march1 = InternetUsagePolicy.dayWindow(today.minusDays(2), zone)
        val march3 = InternetUsagePolicy.dayWindow(today, zone)
        val now = march3.startMillis + 12 * 60 * 60 * 1000L
        val windows = InternetUsageSamplingPlan.windowsToRead(
            now,
            cycle,
            listOf(finalizedDay(march1))
        )

        assertEquals(listOf(today.minusDays(1), today), windows.map { it.localDate })
    }

    @Test fun `midnight batch never queries an empty current day`() {
        val zone = ZoneId.of("UTC")
        val today = LocalDate.of(2026, 3, 3)
        val cycle = InternetUsagePolicy.activeCycle(today, zone = zone)
        val march1 = InternetUsagePolicy.dayWindow(today.minusDays(2), zone)
        val march2 = InternetUsagePolicy.dayWindow(today.minusDays(1), zone)
        val now = InternetUsagePolicy.dayWindow(today, zone).startMillis

        val windows = InternetUsageSamplingPlan.windowsToRead(
            now,
            cycle,
            listOf(finalizedDay(march1), finalizedDay(march2))
        )

        assertTrue(windows.isEmpty())
    }

    private fun finalizedDay(
        window: com.assistant.adi.util.InternetUsageWindow,
        finalized: Boolean = true
    ) = InternetUsageDaily(
        dayStartMillis = window.startMillis,
        localDate = window.localDate.toString(),
        zoneId = window.zoneId,
        wifiBytes = 100L,
        mobileBytes = 200L,
        sampledAt = window.endMillis,
        finalized = finalized
    )
}

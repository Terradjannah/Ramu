package com.assistant.adi.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CsvExportTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun writesHeaderForEmptyMetric() {
        val file = CsvExport.write(temporary.root, "battery.csv", listOf("timestamp_epoch_millis"), emptySequence())

        assertEquals("timestamp_epoch_millis\n", file.readText())
    }

    @Test fun escapesTextAndPreservesProvidedStableOrder() {
        val file = CsvExport.write(
            temporary.root,
            "network.csv",
            listOf("timestamp_epoch_millis", "network_type"),
            sequenceOf(listOf(1_000L, "wifi,\"guest\"\nline two"), listOf(2_000L, "mobile"))
        )

        assertEquals(
            "timestamp_epoch_millis,network_type\n1000,\"wifi,\"\"guest\"\"\nline two\"\n2000,mobile\n",
            file.readText()
        )
    }

    @Test fun createsEachMetricFileIndependently() {
        val names = listOf("battery.csv", "charge_sessions.csv", "ram.csv", "network.csv", "screen_time_apps.csv", "screen_time_hourly.csv")

        names.forEach { name -> CsvExport.write(temporary.root, name, listOf("header"), emptySequence()) }

        assertEquals(names.sorted(), temporary.root.listFiles()!!.map { it.name }.sorted())
        assertTrue(temporary.root.listFiles()!!.all { it.readText() == "header\n" })
    }

    @Test fun keepsHistoricalScreenTimeRowsAndZeroUsageBucketsInOrder() {
        val apps = CsvExport.write(
            temporary.root, "screen_time_apps.csv", listOf("local_date", "app_package", "usage_minutes"),
            sequenceOf(listOf("2026-09-20", "com.example.a", 10), listOf("2026-09-21", "com.example.b", 20))
        )
        val hourly = CsvExport.write(
            temporary.root, "screen_time_hourly.csv", listOf("bucket_start_timestamp_epoch_millis", "usage_millis", "covered_millis"),
            sequenceOf(listOf(1_000L, 0L, 3_600_000L), listOf(2_000L, 60_000L, 3_600_000L))
        )

        assertEquals("local_date,app_package,usage_minutes\n2026-09-20,com.example.a,10\n2026-09-21,com.example.b,20\n", apps.readText())
        assertEquals("bucket_start_timestamp_epoch_millis,usage_millis,covered_millis\n1000,0,3600000\n2000,60000,3600000\n", hourly.readText())
    }
}

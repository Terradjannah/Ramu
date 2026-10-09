package com.assistant.adi

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.assistant.adi.data.*
import com.assistant.adi.ui.DashboardActivity
import com.assistant.adi.ui.ai.AiChatFragment
import com.assistant.adi.ui.buddy.ScreenTimeHeatmapPolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class MonitorRegressionTest {
    @Test fun everyHeatmapLevelIsOpaqueAndReadable() {
        for (night in listOf(false, true)) {
            for (minute in 0..60) {
                val fill = ScreenTimeHeatmapPolicy.fill(minute.toDouble(), night)
                assertEquals("minute=$minute night=$night", 255, Color.alpha(fill))
                assertTrue(ColorUtils.calculateContrast(ScreenTimeHeatmapPolicy.foreground(fill), fill) >= 4.5)
            }
        }
        val crashColor = 0xFEA3C0D4.toInt()
        assertTrue(ColorUtils.calculateContrast(ScreenTimeHeatmapPolicy.foreground(crashColor),
            ColorUtils.setAlphaComponent(crashColor, 255)) >= 4.5)
    }

    @Test fun defaultRangeCoversSevenDatesForEveryMonitor() {
        val today = LocalDate.of(2026, 10, 7)
        HistoryMetric.entries.forEach { metric ->
            val input = MonitorHistoryState(SavedStateHandle(), metric).input.value
            assertEquals(HistoryRange.DAYS_7, input.range)
            assertEquals(today.minusDays(6) to today, input.dates(today))
        }
    }

    @Test fun historyPagesContainFifteenDistinctRecordsAndRespectDateBounds() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = AppRepository(context, db)
            val today = LocalDate.now()
            val start = today.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            for (i in 0..30) db.batteryDao().insertLog(BatteryLog(timestamp = start + i * 1000,
                percentage = 50, temperature = 30f, voltage = 4000, isCharging = false))
            db.batteryDao().insertLog(BatteryLog(timestamp = start - 8 * 86_400_000L,
                percentage = 50, temperature = 30f, voltage = 4000, isCharging = false))
            val query = MonitorHistoryInput(HistoryMetric.BATTERY)
            val first = repo.batteryHistory(query).first()
            val second = repo.batteryHistory(query.copy(page = 1)).first()
            val last = repo.batteryHistory(query.copy(page = 2)).first()
            assertEquals(31, first.count)
            assertEquals(15, first.records.size)
            assertEquals(15, second.records.size)
            assertEquals(1, last.records.size)
            assertTrue(first.hasNext)
            assertFalse(last.hasNext)
            assertEquals(31, (first.records + second.records + last.records).map { it.id }.distinct().size)
            val empty = repo.batteryHistory(query.copy(range = HistoryRange.CUSTOM,
                startDate = today.minusDays(1), endDate = today.minusDays(1))).first()
            assertEquals(0, empty.count)
            val internet = repo.internetHistory(MonitorHistoryInput(HistoryMetric.INTERNET,
                range = HistoryRange.CUSTOM, startDate = today.minusDays(30), endDate = today)).first()
            assertEquals(31, internet.count)
            assertEquals(15, internet.records.size)
            assertTrue(internet.records.all { it.record == null })
        } finally { db.close() }
    }

    @Test fun screenHistorySettingsAndChatBackStackSurviveRecreation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(DashboardActivity::class.java).use { scenario ->
            scenario.onActivity { it.navigateSection("screen") }
            instrumentation.waitForIdleSync()
            Thread.sleep(1500)
            scenario.onActivity { activity ->
                assertNotNull(activity.findViewById<View>(R.id.screen_time_chart))
            }
            scenario.onActivity { it.navigateSection("internet_usage") }
            instrumentation.waitForIdleSync()
            Thread.sleep(1000)
            scenario.onActivity { activity ->
                val root = activity.supportFragmentManager.findFragmentById(R.id.fragment_container)!!.requireView()
                val settingsTab = descendants(root).first { it.contentDescription == "Atur" && it.isClickable }
                settingsTab.performClick()
                assertTrue(activity.findViewById<View>(R.id.settings_panel).isShown)
                assertTrue(activity.findViewById<View>(R.id.btn_open_settings).isShown)
                activity.findViewById<View>(R.id.btn_open_settings).performClick()
            }
            instrumentation.waitForIdleSync()
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.input_monthly_budget))
                .check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()))
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.btn_settings_cancel))
                .perform(androidx.test.espresso.action.ViewActions.click())
            scenario.onActivity { it.navigateSection("ai_chat") }
            instrumentation.waitForIdleSync()
            Thread.sleep(500)
            var chat: AiChatFragment? = null
            scenario.onActivity {
                chat = it.supportFragmentManager.findFragmentById(R.id.fragment_container) as AiChatFragment
                it.navigateSection("model_gallery")
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { chat!!.onSaveInstanceState(Bundle()) }
            scenario.recreate()
            instrumentation.waitForIdleSync()
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}

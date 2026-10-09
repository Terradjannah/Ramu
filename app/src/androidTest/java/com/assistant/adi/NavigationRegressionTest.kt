package com.assistant.adi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.assistant.adi.ui.DashboardActivity
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationRegressionTest {
    @Test fun destinationsSurviveRecreationAndDockReturnsHome() {
        ActivityScenario.launch(DashboardActivity::class.java).use { scenario ->
            val instrumentation=InstrumentationRegistry.getInstrumentation()
            val destinations = listOf(
                "battery" to "battery", "monitor_battery" to "battery", "monitor_temperature" to "battery",
                "ram" to "ram", "monitor_memory" to "ram", "network" to "network", "monitor_network" to "network",
                "monitor_storage" to "storage", "sensors" to "sensors", "screen" to "screen",
                "notifications" to "notifications", "settings" to "settings", "background" to "background",
                "model_gallery" to "model_gallery", "ai_chat" to "ai_chat"
            )
            for((tag, expected) in destinations) {
                scenario.onActivity { it.navigateSection(tag) }
                instrumentation.waitForIdleSync()
                Thread.sleep(300)
                scenario.onActivity { assertEquals(expected,it.supportFragmentManager.findFragmentById(R.id.fragment_container)?.tag) }
            }
            scenario.recreate(); instrumentation.waitForIdleSync()
            scenario.onActivity {
                assertEquals("ai_chat",it.supportFragmentManager.findFragmentById(R.id.fragment_container)?.tag)
                it.openHome()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { assertEquals("hyperos_dashboard",it.supportFragmentManager.findFragmentById(R.id.fragment_container)?.tag) }
        }
    }
}

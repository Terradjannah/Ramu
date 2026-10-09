package com.assistant.adi.ui.buddy

import org.junit.Assert.assertEquals
import org.junit.Test

class BuddyReactionEngineTest {
    @Test
    fun chargingTransitionsBypassTheOrdinaryHold() {
        val engine = BuddyReactionEngine()

        assertEquals("comfortable", engine.update(snapshot(charging = false), null, true, 0).reason)
        assertEquals("charging", engine.update(snapshot(charging = true), null, true, 1).reason)
        assertEquals("comfortable", engine.update(snapshot(charging = false), null, true, 2).reason)
    }

    @Test
    fun chargingLeavesImmediatelyAndHotKeepsPriority() {
        val engine = BuddyReactionEngine()

        assertEquals("charging", engine.update(snapshot(charging = true), null, true, 0).reason)
        assertEquals("comfortable", engine.update(snapshot(charging = false), null, true, 1).reason)

        val hotEngine = BuddyReactionEngine()
        assertEquals("battery_warm", hotEngine.update(snapshot(charging = true, temperature = 41.0), null, true, 2).reason)
    }

    @Test
    fun ordinaryTransitionsStillWaitForTheStablePeriod() {
        val engine = BuddyReactionEngine()

        engine.update(snapshot(storagePercent = 50), null, true, 0)
        assertEquals("comfortable", engine.update(snapshot(storagePercent = 90), null, true, 1).reason)
        assertEquals("storage_tight", engine.update(snapshot(storagePercent = 90), null, true, 10_001).reason)
    }

    private fun snapshot(charging: Boolean? = false, storagePercent: Int = 50, temperature: Double = 30.0): DeviceSnapshot = DeviceSnapshot(
        listOf(
            MonitorReading(Monitor.BATTERY, percent = 50, charging = charging, condition = Condition.GOOD),
            MonitorReading(Monitor.TEMPERATURE, rawValue = temperature, condition = Condition.GOOD),
            MonitorReading(Monitor.STORAGE, percent = storagePercent, condition = Condition.GOOD),
            MonitorReading(Monitor.MEMORY, condition = Condition.GOOD),
            MonitorReading(Monitor.NETWORK, condition = Condition.GOOD)
        ),
        0L
    )
}

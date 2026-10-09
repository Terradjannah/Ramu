package com.assistant.adi.ui.buddy

import org.junit.Assert.assertEquals
import org.junit.Test

class BuddyChargingStateTest {
    @Test
    fun initialStickyValuesAreUsedUntilAPowerEdgeArrives() {
        assertEquals(false, reduce(BuddyChargingState.Event.Initial(false)).charging)
        assertEquals(true, reduce(BuddyChargingState.Event.Initial(true)).charging)
        assertEquals(null, reduce(BuddyChargingState.Event.Initial(null)).charging)
    }

    @Test
    fun powerEdgesWinOverStaleBatteryChangesAndPolls() {
        val connected = reduce(
            BuddyChargingState.Event.Initial(false),
            BuddyChargingState.Event.PowerConnected,
            BuddyChargingState.Event.BatteryChanged(false),
            BuddyChargingState.Event.Poll(false)
        )
        assertEquals(true, connected.charging)

        val disconnected = connected.reduce(BuddyChargingState.Event.PowerDisconnected)
            .reduce(BuddyChargingState.Event.BatteryChanged(true))
            .reduce(BuddyChargingState.Event.Poll(true))
        assertEquals(false, disconnected.charging)
    }

    @Test
    fun oppositePowerEdgeReplacesTheLatchedCableState() {
        val state = reduce(
            BuddyChargingState.Event.Initial(false),
            BuddyChargingState.Event.PowerConnected,
            BuddyChargingState.Event.PowerDisconnected,
            BuddyChargingState.Event.PowerConnected
        )

        assertEquals(true, state.charging)
    }

    @Test
    fun newSubscriptionInitializesFromItsFreshStickyReading() {
        val firstSubscription = reduce(BuddyChargingState.Event.Initial(false))
        val resumedSubscription = reduce(BuddyChargingState.Event.Initial(true))

        assertEquals(false, firstSubscription.charging)
        assertEquals(true, resumedSubscription.charging)
    }

    private fun reduce(vararg events: BuddyChargingState.Event): BuddyChargingState =
        events.fold(BuddyChargingState()) { state, event -> state.reduce(event) }
}

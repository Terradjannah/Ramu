package com.assistant.adi.ui.buddy

/** Keeps a cable edge authoritative until Android reports the opposite edge. */
data class BuddyChargingState(
    val charging: Boolean? = null,
    private val initialized: Boolean = false,
    private val hasCableEdge: Boolean = false
) {
    fun reduce(event: Event): BuddyChargingState = when (event) {
        is Event.Initial -> {
            if (initialized) this
            else copy(charging = if (hasCableEdge) charging else event.charging, initialized = true)
        }
        is Event.BatteryChanged, is Event.Poll -> {
            if (hasCableEdge) this
            else copy(charging = event.charging ?: charging, initialized = true)
        }
        Event.PowerConnected -> copy(charging = true, initialized = true, hasCableEdge = true)
        Event.PowerDisconnected -> copy(charging = false, initialized = true, hasCableEdge = true)
    }

    sealed interface Event {
        val charging: Boolean?

        data class Initial(override val charging: Boolean?) : Event
        data class BatteryChanged(override val charging: Boolean?) : Event
        data class Poll(override val charging: Boolean?) : Event
        data object PowerConnected : Event { override val charging: Boolean? = true }
        data object PowerDisconnected : Event { override val charging: Boolean? = false }
    }
}

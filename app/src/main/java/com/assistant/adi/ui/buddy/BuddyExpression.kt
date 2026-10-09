package com.assistant.adi.ui.buddy

/** A short-lived reaction layered over the persistent device-derived [BuddyMood]. */
enum class BuddyExpression(val defaultDurationMillis: Long) {
    NONE(0L),
    TAP_DELIGHT(450L),
    TICKLE_LAUGH(900L),
    ANNOYED(1_600L),
    POKE(450L),
    EATING(900L),
    DRINKING(900L),
    SPICY(1_200L),
    SLEEPY_YAWN(1_000L)
}

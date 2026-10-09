package com.assistant.adi.ui.buddy

/** Maps a short, monotonic tap streak to a transient Home-pet expression. */
class BuddyTapInteractionController {
    private var tapCount = 0
    private var lastTapAtMillis = NO_TAP
    private var annoyedUntilMillis = NO_TAP

    fun onTap(nowMillis: Long): BuddyTapReaction {
        if (annoyedUntilMillis != NO_TAP) {
            if (nowMillis < annoyedUntilMillis) {
                return BuddyTapReaction(BuddyExpression.ANNOYED, isFirstInStreak = false, shouldTriggerExpression = false)
            }
            reset()
        }
        if (lastTapAtMillis == NO_TAP || nowMillis - lastTapAtMillis > STREAK_TIMEOUT_MILLIS) {
            tapCount = 0
        }

        tapCount += 1
        lastTapAtMillis = nowMillis
        return when (tapCount) {
            in 1..5 -> BuddyTapReaction(BuddyExpression.TAP_DELIGHT, tapCount == 1, shouldTriggerExpression = true)
            in 6..15 -> BuddyTapReaction(BuddyExpression.TICKLE_LAUGH, isFirstInStreak = false, shouldTriggerExpression = true)
            else -> {
                annoyedUntilMillis = nowMillis + BuddyExpression.ANNOYED.defaultDurationMillis
                BuddyTapReaction(BuddyExpression.ANNOYED, isFirstInStreak = false, shouldTriggerExpression = true)
            }
        }
    }

    fun reset() {
        tapCount = 0
        lastTapAtMillis = NO_TAP
        annoyedUntilMillis = NO_TAP
    }

    private companion object {
        const val NO_TAP = Long.MIN_VALUE
        const val STREAK_TIMEOUT_MILLIS = 4_000L
    }
}

data class BuddyTapReaction(
    val expression: BuddyExpression,
    val isFirstInStreak: Boolean,
    val shouldTriggerExpression: Boolean
)

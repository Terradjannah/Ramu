package com.assistant.adi.ui.buddy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuddyTapInteractionControllerTest {
    @Test
    fun firstFiveTapsEachRetriggerDelightWhileOnlyTheFirstStartsTheStreak() {
        val controller = BuddyTapInteractionController()

        repeat(5) { index ->
            val reaction = controller.onTap(index * 100L)
            assertEquals(BuddyExpression.TAP_DELIGHT, reaction.expression)
            assertTrue(reaction.shouldTriggerExpression)
            if (index == 0) assertTrue(reaction.isFirstInStreak) else assertFalse(reaction.isFirstInStreak)
        }
    }

    @Test
    fun tapsSixThroughFifteenExtendLaughterAndSixteenStartsAnnoyanceOnce() {
        val controller = BuddyTapInteractionController()

        repeat(5) { index -> controller.onTap(index * 100L) }
        repeat(10) { index ->
            val reaction = controller.onTap((index + 5) * 100L)
            assertEquals(BuddyExpression.TICKLE_LAUGH, reaction.expression)
            assertTrue(reaction.shouldTriggerExpression)
        }
        val annoyed = controller.onTap(1_500L)
        assertEquals(BuddyExpression.ANNOYED, annoyed.expression)
        assertTrue(annoyed.shouldTriggerExpression)
        assertFalse(controller.onTap(1_600L).shouldTriggerExpression)
    }

    @Test
    fun waitingMoreThanFourSecondsStartsANewDelightStreak() {
        val controller = BuddyTapInteractionController()

        controller.onTap(0L)
        val reaction = controller.onTap(4_001L)

        assertEquals(BuddyExpression.TAP_DELIGHT, reaction.expression)
        assertTrue(reaction.isFirstInStreak)
    }

    @Test
    fun streakResetsAfterTheAnnoyanceWindowEnds() {
        val controller = BuddyTapInteractionController()

        repeat(16) { index -> controller.onTap(index * 100L) }
        val reaction = controller.onTap(3_101L)

        assertEquals(BuddyExpression.TAP_DELIGHT, reaction.expression)
        assertTrue(reaction.isFirstInStreak)
    }

    @Test
    fun cueCooldownStartsOnlyAfterAClipPlaysAndPendingClipUsesLatestCurrentReaction() {
        var now = 0L
        var loaded = false
        var active = true
        var starts = 0
        val cues = BuddyHomeReactionCueController { now }

        cues.request(HomeReactionCue.LAUGH, { active }) {
            if (!loaded) CuePlayResult.UNLOADED else { starts += 1; CuePlayResult.STARTED }
        }
        now = 100L
        cues.request(HomeReactionCue.LAUGH, { active }) {
            if (!loaded) CuePlayResult.UNLOADED else { starts += 1; CuePlayResult.STARTED }
        }
        loaded = true
        cues.onClipLoaded()
        assertEquals(1, starts)

        now = 200L
        cues.request(HomeReactionCue.LAUGH, { active }) { starts += 1; CuePlayResult.STARTED }
        assertEquals(1, starts)
        now = 401L
        cues.request(HomeReactionCue.LAUGH, { active }) { starts += 1; CuePlayResult.STARTED }
        assertEquals(2, starts)

        loaded = false
        cues.request(HomeReactionCue.DELIGHT, { active }) {
            if (!loaded) CuePlayResult.UNLOADED else { starts += 1; CuePlayResult.STARTED }
        }
        active = false
        loaded = true
        cues.onClipLoaded()
        assertEquals(2, starts)
    }
}

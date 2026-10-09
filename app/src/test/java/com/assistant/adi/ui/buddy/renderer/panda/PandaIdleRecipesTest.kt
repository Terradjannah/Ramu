package com.assistant.adi.ui.buddy.renderer.panda

import com.assistant.adi.ui.buddy.BuddyMood
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class PandaIdleRecipesTest {
    @Test
    fun everyMoodKeepsStartingDistinctPhrasesAtTheDocumentedCadence() {
        BuddyMood.entries.forEach { mood ->
            val starts = collectStarts(PandaIdleRecipes(Random(100L + mood.ordinal)), mood, 90f)

            assertTrue("$mood should complete more than three phrases", starts.size > 3)
            starts.zipWithNext().forEach { (before, after) ->
                assertTrue("$mood interval was ${after.time - before.time}", after.time - before.time in 7.98f..10.02f)
                assertFalse("$mood repeated recipe ${after.recipe}", before.recipe == after.recipe)
            }
        }
    }

    @Test
    fun moodChangeWaitsAgainAndInterruptionRecoversWithoutCatchUp() {
        val recipes = PandaIdleRecipes(Random(41L))
        var time = 0f
        advance(recipes, BuddyMood.HAPPY, true, 4f) { time += STEP }

        val changedAt = time
        val afterMoodChange = collectStarts(recipes, BuddyMood.HOT, 5f, time) { time += STEP }
        assertEquals(1, afterMoodChange.size)
        assertTrue(afterMoodChange.single().time - changedAt in 2f..3.3f)

        val interrupted = PandaIdleRecipes(Random(97L))
        var interruptionTime = 0f
        advanceUntilActive(interrupted, BuddyMood.HAPPY) { interruptionTime += STEP }
        advance(interrupted, BuddyMood.HAPPY, false, .2f) { interruptionTime += STEP }
        assertEquals(-1, interrupted.activeRecipe)

        val resumedAt = interruptionTime
        val resumed = collectStarts(interrupted, BuddyMood.HAPPY, 10f, interruptionTime) { interruptionTime += STEP }
        assertEquals(1, resumed.size)
        assertTrue(resumed.single().time - resumedAt >= 1.8f - STEP)
    }

    private fun collectStarts(
        recipes: PandaIdleRecipes,
        mood: BuddyMood,
        seconds: Float,
        initialTime: Float = 0f,
        onStep: () -> Unit = {}
    ): List<Start> {
        var time = initialTime
        var previous = recipes.activeRecipe
        return buildList {
            repeat((seconds / STEP).toInt()) {
                recipes.update(mood, true, STEP)
                time += STEP
                onStep()
                val current = recipes.activeRecipe
                if (previous < 0 && current >= 0) add(Start(time, current))
                previous = current
            }
        }
    }

    private fun advance(recipes: PandaIdleRecipes, mood: BuddyMood, eligible: Boolean, seconds: Float, onStep: () -> Unit) {
        repeat((seconds / STEP).toInt()) {
            recipes.update(mood, eligible, STEP)
            onStep()
        }
    }

    private fun advanceUntilActive(recipes: PandaIdleRecipes, mood: BuddyMood, onStep: () -> Unit) {
        repeat((4f / STEP).toInt()) {
            recipes.update(mood, true, STEP)
            onStep()
            if (recipes.activeRecipe >= 0) return
        }
        throw AssertionError("$mood did not start an idle phrase")
    }

    private data class Start(val time: Float, val recipe: Int)

    private companion object { const val STEP = 1f / 60f }
}

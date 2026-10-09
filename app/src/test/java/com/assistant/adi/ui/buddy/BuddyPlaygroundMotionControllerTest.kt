package com.assistant.adi.ui.buddy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class BuddyPlaygroundMotionControllerTest {
    @Test
    fun dragPreservesPointerOffsetAndKeepsThePetReachable() {
        val controller = BuddyPlaygroundMotionController()
        val initial = controller.reset(BuddyPlaygroundMotionController.Bounds(400f, 600f), 100f)

        controller.beginDrag(BuddyPlaygroundMotionController.Point(initial.center.x + 20f, initial.center.y - 30f), 0L)
        val dragged = controller.dragTo(BuddyPlaygroundMotionController.Point(90f, 190f), 20_000_000L)
        val clamped = controller.dragTo(BuddyPlaygroundMotionController.Point(-500f, 1_000f), 40_000_000L)

        assertEquals(110f, dragged.center.x, 0.001f)
        assertEquals(160f, dragged.center.y, 0.001f)
        assertEquals(-25f, clamped.center.x, 0.001f)
        assertEquals(initial.floorY, clamped.center.y, 0.001f)
    }

    @Test
    fun releaseSpringsHorizontallyAndReportsFloorImpact() {
        val controller = BuddyPlaygroundMotionController()
        val initial = controller.reset(BuddyPlaygroundMotionController.Bounds(600f, 600f), 50f)
        val start = initial.center
        controller.beginDrag(start, 0L)
        controller.dragTo(BuddyPlaygroundMotionController.Point(start.x + 120f, start.y - 200f), 100_000_000L)
        controller.release(BuddyPlaygroundMotionController.Point(start.x + 120f, start.y - 200f), 100_000_000L)

        var impact: BuddyPlaygroundMotionController.Collision? = null
        repeat(480) {
            val frame = controller.advance(1f / 120f)
            if (frame.collision != null) impact = frame.collision
        }
        val settled = controller.advance(0f).state

        assertNotNull(impact)
        assertTrue(impact!!.normalizedImpact in 0f..1f)
        assertEquals(settled.restCenter.x, settled.center.x, 1.1f)
        assertEquals(settled.floorY, settled.center.y, 0.001f)
        assertFalse(settled.isRunning)
    }

    @Test
    fun fixedStepsKeepSixtyNinetyAndOneTwentyHertzSequencesWithinTolerance() {
        val states = listOf(60, 90, 120).map { hertz -> runSequence(hertz) }

        states.drop(1).forEach { state ->
            assertEquals(states.first().center.x, state.center.x, 0.25f)
            assertEquals(states.first().center.y, state.center.y, 0.25f)
            assertEquals(states.first().velocity.x, state.velocity.x, 0.25f)
            assertEquals(states.first().velocity.y, state.velocity.y, 0.25f)
            assertTrue(abs(states.first().rotation - state.rotation) < 0.01f)
        }
    }

    @Test
    fun cancellationAndInvalidBoundsReturnASafeRestState() {
        val controller = BuddyPlaygroundMotionController()
        val invalid = controller.reset(BuddyPlaygroundMotionController.Bounds(0f, 400f), 50f)

        assertFalse(invalid.isDragging)
        assertFalse(invalid.isRunning)
        assertEquals(0f, invalid.center.x, 0f)
        assertEquals(0f, invalid.center.y, 0f)

        val valid = controller.reset(BuddyPlaygroundMotionController.Bounds(500f, 500f), 50f)
        controller.beginDrag(valid.center, 0L)
        controller.dragTo(BuddyPlaygroundMotionController.Point(400f, 100f), 50_000_000L)
        val cancelled = controller.cancel()

        assertEquals(cancelled.restCenter, cancelled.center)
        assertEquals(0f, cancelled.velocity.x, 0f)
        assertEquals(0f, cancelled.velocity.y, 0f)
        assertFalse(cancelled.isDragging)
        assertFalse(cancelled.isRunning)
    }

    @Test
    fun dockSafeFloorKeepsTheRestingBodyAboveTheDock() {
        val controller = BuddyPlaygroundMotionController()

        val state = controller.reset(
            BuddyPlaygroundMotionController.Bounds(600f, 900f),
            100f,
            floorLimitY = 650f
        )

        assertEquals(650f, state.floorY, 0.001f)
        assertTrue(state.floorY + 100f <= 750f)
    }

    private fun runSequence(hertz: Int): BuddyPlaygroundMotionController.State {
        val controller = BuddyPlaygroundMotionController()
        val initial = controller.reset(BuddyPlaygroundMotionController.Bounds(600f, 600f), 50f)
        controller.beginDrag(initial.center, 0L)
        val release = BuddyPlaygroundMotionController.Point(initial.center.x + 110f, initial.center.y - 180f)
        controller.dragTo(release, 100_000_000L)
        controller.release(release, 100_000_000L)
        repeat(hertz * 2) { controller.advance(1f / hertz) }
        return controller.advance(0f).state
    }
}

package com.assistant.adi.ui.buddy.renderer.panda

import com.assistant.adi.ui.buddy.BuddyMood
import java.util.Random
import kotlin.math.sin

/** Passive performance selection, independent of device rules and the expression queue. */
class PandaIdleRecipes internal constructor(private val random: Random = Random()) {
    var headTilt = 0f; private set
    var headY = 0f; private set
    var headX = 0f; private set
    var ear = 0f; private set
    var leftX = 0f; private set
    var leftY = 0f; private set
    var rightX = 0f; private set
    var rightY = 0f; private set
    var glance = 0f; private set
    var bounce = 0f; private set
    var squash = 0f; private set
    var scratch = 0f; private set
    var uncover = 0f; private set
    var bambooRelease = 0f; private set
    var chew = 0f; private set
    var puff = 0f; private set
    private var owner: BuddyMood? = null
    private var recipe = -1
    private var previous = -1
    private var elapsed = 0f
    private var duration = 1f
    private var wait = 0f
    private var strength = 1f
    private var mix = 0f

    /** Scheduler state for same-module deterministic tests; not a UI/debug control. */
    internal val activeRecipe: Int get() = recipe

    fun reset() {
        owner = null; recipe = -1; previous = -1; wait = 0f; elapsed = 0f; mix = 0f
        clear()
    }

    fun update(mood: BuddyMood, eligible: Boolean, dt: Float) {
        if (owner != mood) {
            // Release the old phrase before assigning a phrase from the new mood.
            mix = (mix - dt / .18f).coerceAtLeast(0f)
            if (mix == 0f) { owner = mood; recipe = -1; previous = -1; wait = 2f + random.nextFloat() }
        } else if (!eligible) {
            mix = (mix - dt / .12f).coerceAtLeast(0f)
            if (mix == 0f) { recipe = -1; wait = maxOf(wait, 1.8f) }
        } else if (recipe < 0) {
            wait -= dt
            if (wait <= 0f) {
                val pick = random.nextInt(2)
                recipe = if (previous < 0) random.nextInt(3) else (previous + 1 + pick) % 3
                previous = recipe; elapsed = 0f
                duration = (if (mood == BuddyMood.LOW_POWER) 3.8f else 2.1f) + random.nextFloat() * 1.2f
                // Count cadence from gesture start, including its performance duration.
                wait = 8f + random.nextFloat() * 2f - duration
                strength = .85f + random.nextFloat() * .3f
            }
        } else {
            elapsed += dt
            mix = (mix + dt / .22f).coerceAtMost(1f)
            if (elapsed >= duration) { recipe = -1; mix = 0f; wait = (wait - (elapsed - duration)).coerceAtLeast(0f) }
        }
        clear()
        if (recipe < 0) return
        val p = (elapsed / duration).coerceIn(0f, 1f)
        val envelope = smooth(p / .22f) * (1f - smooth((p - .6f) / .4f)) * mix * strength
        val beat = sin(p * Math.PI.toFloat() * 4f)
        val slow = sin(p * Math.PI.toFloat() * 2f)
        val a = envelope
        when (owner) {
            BuddyMood.HAPPY -> when (recipe) {
                0 -> { rightY = -31f * a; rightX = 9f * beat * a; headTilt = -4f * a; bambooRelease = a }
                1 -> { ear = 9f * beat * a; glance = 4f * slow * a; headTilt = 4f * slow * a }
                else -> { chew = .4f * beat * a; leftY = -3f * a; rightY = -4f * a; headY = 2f * beat * a }
            }
            BuddyMood.HOT -> when (recipe) {
                0 -> { rightY = -33f * a; rightX = 8f * beat * a; headTilt = -3f * a; ear = -5f * a }
                1 -> { headY = 6f * a; headTilt = 5f * a; leftY = 4f * a; squash = .02f * a }
                else -> { headTilt = 5f * slow * a; headY = 3f * a; ear = -7f * a }
            }
            BuddyMood.LOW_POWER -> when (recipe) {
                0 -> { headY = 7f * a; headTilt = 4f * a; squash = .018f * a }
                1 -> { headTilt = -6f * a; headX = -3f * a; leftY = 3f * a; ear = -4f * a }
                else -> { headY = -3f * a; ear = 4f * a; rightY = -4f * a }
            }
            BuddyMood.CONFUSED -> when (recipe) {
                0 -> { scratch = a; rightY = 3f * beat * a; headTilt = -3f * a }
                1 -> { headTilt = 11f * slow * a; glance = 5f * slow * a; ear = 3f * slow * a }
                else -> { leftY = -21f * a; leftX = -9f * a; headTilt = 6f * a; glance = -3f * a }
            }
            BuddyMood.OVERWHELMED -> when (recipe) {
                0 -> { uncover = .38f * a; glance = 3f * slow * a; headTilt = 3f * a }
                1 -> { headY = 4f * a; squash = .025f * a; ear = -5f * a }
                else -> { headTilt = 3f * beat * a; headY = 2f * a }
            }
            BuddyMood.OFFLINE -> when (recipe) {
                0 -> { glance = 5f * slow * a; headTilt = 4f * slow * a }
                1 -> { headY = 4f * a; leftY = 3f * a; ear = -4f * a }
                else -> { rightY = -8f * a; rightX = 3f * beat * a; headTilt = -3f * a }
            }
            BuddyMood.CHARGING -> when (recipe) {
                0 -> { bounce = 5f * a; squash = -.015f * a; ear = 5f * beat * a }
                1 -> { leftY = -13f * a; rightY = -13f * a; headTilt = 4f * slow * a }
                else -> { headY = -3f * a; leftY = 3f * beat * a; rightY = -3f * beat * a; ear = 5f * a }
            }
            BuddyMood.CURIOUS -> when (recipe) {
                0 -> { headY = -4f * a + 2f * beat * a; headX = 3f * a; ear = 4f * a }
                1 -> { headTilt = 9f * slow * a; glance = 5f * slow * a; ear = 4f * slow * a }
                else -> { leftY = -15f * a; headX = -5f * a; headTilt = -5f * a; glance = -3f * a }
            }
            BuddyMood.POUT -> when (recipe) {
                0 -> { headY = 2f * a; squash = .016f * a; puff = 2f * a; ear = -2f * a }
                1 -> { headTilt = 5f * slow * a; glance = 6f * slow * a; ear = 3f * slow * a }
                else -> { leftX = 4f * a; rightX = -4f * a; leftY = -2f * a; rightY = 2f * a; headY = 2f * a }
            }
            null -> Unit
        }
    }

    private fun clear() {
        headTilt = 0f; headY = 0f; headX = 0f; ear = 0f
        leftX = 0f; leftY = 0f; rightX = 0f; rightY = 0f; glance = 0f
        bounce = 0f; squash = 0f; scratch = 0f; uncover = 0f; bambooRelease = 0f; chew = 0f; puff = 0f
    }
    private fun smooth(x: Float): Float { val u = x.coerceIn(0f, 1f); return u * u * (3f - 2f * u) }
}

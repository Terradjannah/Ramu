package com.assistant.adi.ui.buddy.renderer.panda

import android.graphics.Canvas
import com.assistant.adi.ui.buddy.BuddyExpression
import com.assistant.adi.ui.buddy.BuddyParticleSystem
import java.util.Random

/** Presentation bursts use existing effect types. Delays live in the pool, never posted callbacks. */
class PandaReactionEffects {
    private val particles = BuddyParticleSystem(64)
    private val random = Random()
    private var positiveCooldown = 0f
    private var petCooldown = 0f
    fun clear() { particles.clear(); positiveCooldown = 0f; petCooldown = 0f }
    fun advance(dt: Float) {
        positiveCooldown = (positiveCooldown - dt).coerceAtLeast(0f)
        petCooldown = (petCooldown - dt).coerceAtLeast(0f)
        particles.advance(dt)
    }
    fun draw(canvas: Canvas) = particles.draw(canvas)
    fun expression(expression: BuddyExpression, x: Float, y: Float, r: Float) {
        when (expression) {
            BuddyExpression.TAP_DELIGHT -> if (positiveCooldown <= 0f) {
                burst(BuddyParticleSystem.Type.SPARKLE, 7, x, y, r, .035f)
                positiveCooldown = .22f
            }
            BuddyExpression.TICKLE_LAUGH -> if (positiveCooldown <= 0f) {
                burst(BuddyParticleSystem.Type.HEART, 5, x, y, r, .075f)
                burst(BuddyParticleSystem.Type.SPARKLE, 4, x, y, r, .11f)
                positiveCooldown = .4f
            }
            BuddyExpression.SPICY -> burst(BuddyParticleSystem.Type.SWEAT, 12, x, y, r, .065f)
            else -> Unit
        }
    }
    fun pet(x: Float, y: Float, r: Float) {
        if (petCooldown > 0f) return
        burst(BuddyParticleSystem.Type.HEART, 6, x, y, r, .085f)
        burst(BuddyParticleSystem.Type.SPARKLE, 3, x, y, r, .13f)
        petCooldown = 1f
    }
    fun treat(type: BuddyParticleSystem.Type, x: Float, y: Float, r: Float) {
        // SPICY already owns the stronger timed sweat phrase at expression entry.
        if (type == BuddyParticleSystem.Type.SWEAT) return
        burst(type, if (type == BuddyParticleSystem.Type.WATER) 14 else 11, x, y, r, .05f)
        if (type == BuddyParticleSystem.Type.HEART) burst(BuddyParticleSystem.Type.SPARKLE, 4, x, y, r, .1f)
    }
    private fun burst(type: BuddyParticleSystem.Type, count: Int, x: Float, y: Float, r: Float, stagger: Float) {
        if (r <= 0f) return
        val wet = type == BuddyParticleSystem.Type.SWEAT || type == BuddyParticleSystem.Type.WATER
        for (i in 0 until count) {
            val side = if (i % 2 == 0) -1f else 1f
            val size = r * (.045f + random.nextFloat() * .04f)
            val startX = x + side * r * (if (type == BuddyParticleSystem.Type.WATER) .4f else .72f)
            val startY = y + r * (if (type == BuddyParticleSystem.Type.WATER) .12f else -.47f) + random.nextFloat() * r * .16f
            val dx = side * r * (.25f + random.nextFloat() * .55f)
            val dy = -r * (if (wet) .25f + random.nextFloat() * .5f else .65f + random.nextFloat() * .65f)
            particles.emit(type, startX, startY, dx, dy, size, .65f + random.nextFloat() * .5f,
                i * stagger, if (wet) r * 1.5f else r * .4f, true)
        }
    }
}

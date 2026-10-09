package com.assistant.adi.ui.buddy.renderer.panda

import com.assistant.adi.ui.buddy.BuddyMood
import kotlin.math.abs
import kotlin.math.sin

class PandaMotionState(val idle: PandaIdleRecipes) {
    var breath = 0f
    var headY = 0f
    var headX = 0f
    var headTilt = 0f
    var bodyTilt = 0f
    var earLag = 0f
    var earRight = 0f
    var pawFollow = 0f
    var pawLift = 0f
    var pawSwing = 0f
    var pokeSquash = 0f
    var delightHop = 0f
    var petAmount = 0f
    var scratch = 0f
    var glance = 0f
    var chew = 0f
}

/** Internal offsets only. No center integration, gravity, collision detection or fling ownership. */
class PandaMotion {
    private val idle = PandaIdleRecipes()
    private val state = PandaMotionState(idle)
    private val poke = SpringFollower(PandaAnimParams.POKE_SPRING_STIFFNESS, PandaAnimParams.POKE_SPRING_DAMPING)
    private val hop = SpringFollower(PandaAnimParams.DELIGHT_SPRING_STIFFNESS, PandaAnimParams.DELIGHT_SPRING_DAMPING)
    private val head = SpringFollower(PandaAnimParams.ROTATION_SPRING_STIFFNESS, PandaAnimParams.ROTATION_SPRING_DAMPING)
    private val pet = SpringFollower(PandaAnimParams.ENERGY_SPRING_STIFFNESS, PandaAnimParams.ENERGY_SPRING_DAMPING)
    private val press = SpringFollower(240f, 19f)
    private val neck = SpringFollower(115f, 12f)
    private val sway = SpringFollower(95f, 11f)
    private val ears = SpringFollower(165f, 10f)
    private val rightEar = SpringFollower(185f, 12f)
    private val paws = SpringFollower(90f, 10f)
    private var time = 0f
    private var lastVX = 0f
    private var lastVY = 0f
    private var previousHop = 0f

    fun reset() {
        poke.reset(); hop.reset(); head.reset(); pet.reset(); press.reset()
        neck.reset(); sway.reset(); ears.reset(); rightEar.reset(); paws.reset(); idle.reset()
        time = 0f; lastVX = 0f; lastVY = 0f; previousHop = 0f
    }

    fun update(mood: BuddyMood, previousMood: BuddyMood, blend: Float,
        isPet: Boolean, expr: PandaExpressionOverride, input: PandaInteractionSignals,
        busy: Boolean, feeding: Float, still: Boolean, dt: Float): PandaMotionState {
        val s = state
        if (still) {
            reset()
            s.breath = 0f; s.headY = 0f; s.headX = 0f; s.headTilt = 0f; s.bodyTilt = 0f; s.earLag = 0f; s.earRight = 0f
            s.pawFollow = 0f; s.pawLift = 0f; s.pawSwing = 0f; s.pokeSquash = expr.pokeSquash
            s.delightHop = 0f; s.petAmount = 0f; s.scratch = 0f; s.glance = 0f; s.chew = 0f
            return s
        }
        time += dt; input.sampleAge += dt
        val t = time
        val vx = if (input.dragging && input.sampleAge > .12f) 0f else input.velocityX
        val vy = if (input.dragging && input.sampleAge > .12f) 0f else input.velocityY
        val ax = if (dt > 0f) ((vx - lastVX) / dt).coerceIn(-30f, 30f) else 0f
        val ay = if (dt > 0f) ((vy - lastVY) / dt).coerceIn(-30f, 30f) else 0f
        lastVX = vx; lastVY = vy
        val impact = input.impact; input.impact = 0f
        if (impact > 0f) {
            neck.impulse(85f * impact); ears.impulse(-170f * impact)
            rightEar.impulse(145f * impact); paws.impulse(-110f * impact)
        }
        val physical = input.pressed || input.dragging || abs(vx) + abs(vy) > .15f || impact > 0f
        idle.update(mood, !busy && !physical && !isPet && feeding < .01f && blend >= .999f, dt)
        val hot = weight(BuddyMood.HOT, mood, previousMood, blend)
        val low = weight(BuddyMood.LOW_POWER, mood, previousMood, blend)
        val charging = weight(BuddyMood.CHARGING, mood, previousMood, blend)
        val pout = weight(BuddyMood.POUT, mood, previousMood, blend)
        s.petAmount = pet.follow(if (isPet) 1f else 0f, dt).coerceIn(0f, 1f)
        val grip = press.follow(if (input.pressed && !input.dragging) 1f else 0f, dt)
        val free = 1f - feeding.coerceIn(0f, 1f)
        s.breath = sin(t * 1.65f) * .008f * (1f - .45f * low) + hot * sin(t * 3.5f) * .014f +
            charging * sin(t * 3f) * .003f + pout * sin(t * 2.2f) * .003f
        s.pokeSquash = (poke.follow(expr.pokeSquash, dt) + .085f * grip + idle.squash +
            sin(t * 7f) * .022f * s.petAmount * free).coerceIn(-.075f, .17f)
        s.delightHop = hop.follow(expr.bounce + idle.bounce + (1f - kotlin.math.cos(t * 7f)) * 2f * s.petAmount * free, dt).coerceIn(-3f, 18f)
        val liftSpeed = if (dt > 0f) ((s.delightHop - previousHop) / dt).coerceIn(-65f, 65f) else 0f
        previousHop = s.delightHop
        val neckOffset = neck.follow((3f * grip - vy * .55f - ay * .045f + liftSpeed * .035f) * free, dt).coerceIn(-7f, 10f)
        s.headY = -sin(t * 1.65f - .35f) * .7f + hot * sin(t * 3.5f - .6f) * 2f + idle.headY + neckOffset
        s.headX = sway.follow((-vx * .7f - ax * .13f) * free, dt).coerceIn(-7f, 7f) + idle.headX
        s.bodyTilt = sin(t * 7f) * 2.8f * s.petAmount * free + expr.bodyTilt - vx.coerceIn(-5f, 5f) * .25f * free
        val headTarget = -sin(t * 7f - .4f) * 6f * s.petAmount * free + expr.headTilt + idle.headTilt - vx * 1.2f * free - ax * .14f * free
        s.headTilt = head.follow(headTarget, dt).coerceIn(-17f, 17f)
        val earTarget = (s.headTilt - headTarget) * 1.1f
        val earSpread = idle.ear + neckOffset * .8f + grip * 7f
        s.earLag = ears.follow(earTarget - earSpread, dt).coerceIn(-17f, 17f)
        s.earRight = rightEar.follow(earTarget + earSpread, dt).coerceIn(-17f, 17f)
        s.pawFollow = sin(t * 7f - .7f) * 4f * s.petAmount * free + expr.energy * sin(t * 8f) * 2.5f
        s.pawLift = paws.follow((-abs(vx) * 1.2f - maxOf(0f, -vy) * 1.1f - grip * 6f) * free, dt).coerceIn(-17f, 9f)
        s.pawSwing = (s.headX * -.65f).coerceIn(-5f, 5f)
        s.scratch = 0f; s.glance = idle.glance; s.chew = idle.chew
        return s
    }

    private fun weight(target: BuddyMood, mood: BuddyMood, previous: BuddyMood, blend: Float): Float =
        (if (previous == target) 1f - blend else 0f) + (if (mood == target) blend else 0f)
}

private class SpringFollower(private val stiffness: Float, private val damping: Float) {
    private var value = 0f
    private var velocity = 0f
    fun follow(target: Float, dt: Float): Float {
        var remaining = dt.coerceIn(0f, PandaAnimParams.MAX_MOTION_DT)
        while (remaining > .00001f) {
            val step = minOf(remaining, PandaAnimParams.MAX_SPRING_DT)
            velocity += ((target - value) * stiffness - velocity * damping) * step
            value += velocity * step
            remaining -= step
        }
        return value
    }
    fun impulse(amount: Float) { velocity = (velocity + amount).coerceIn(-180f, 180f) }
    fun reset() { value = 0f; velocity = 0f }
}

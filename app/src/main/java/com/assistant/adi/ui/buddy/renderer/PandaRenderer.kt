package com.assistant.adi.ui.buddy.renderer

import android.graphics.Canvas
import com.assistant.adi.ui.buddy.BuddyExpression
import com.assistant.adi.ui.buddy.BuddyMood
import com.assistant.adi.ui.buddy.renderer.panda.*
import kotlin.math.cos
import kotlin.math.sin

/** Resolves Bao's layers into one reusable frame; view contracts and input ownership stay outside. */
class PandaRenderer : IBuddyRenderer {
    override val characterId = "panda"
    private val rig = PandaRig()
    private val frame = PandaRigFrame()
    private val motion = PandaMotion()
    private val expressionIntent = PandaExpressionOverride()
    val interaction = PandaInteractionSignals()
    fun resetMotion() { interaction.reset(); motion.reset(); hasTime = false }
    private var lastTime = 0f
    private var hasTime = false

    override fun draw(canvas: Canvas, radius: Float, mood: BuddyMood, transitionProgress: Float,
        previousMood: BuddyMood, elapsedSeconds: Float, isBlinking: Boolean, blinkAmount: Float,
        gazeX: Float, gazeY: Float, isPet: Boolean, mouthOpenFood: Float,
        expression: BuddyExpression, expressionProgress: Float) {
        render(canvas, radius, mood, previousMood, transitionProgress, elapsedSeconds, blinkAmount,
            gazeX, gazeY, isPet, mouthOpenFood, expression, expressionProgress, false)
    }

    override fun drawStillFrame(canvas: Canvas, radius: Float, mood: BuddyMood, expression: BuddyExpression) {
        render(canvas, radius, mood, mood, 1f, 0f, 1f, 0f, 0f, false, 0f, expression, .5f, true)
    }

    private fun render(canvas: Canvas, radius: Float, mood: BuddyMood, previousMood: BuddyMood,
        progress: Float, t: Float, blink: Float, gazeX: Float, gazeY: Float, isPet: Boolean,
        food: Float, expression: BuddyExpression, expressionProgress: Float, still: Boolean) {
        if (radius <= 0f) return
        val p = progress.coerceIn(0f, 1f)
        val old = PandaPoses.ALL[previousMood.ordinal]
        val pose = PandaPoses.ALL[mood.ordinal]
        val e = expressionIntent
        PandaExpressions.resolve(expression, expressionProgress, still, e)
        val rawDt = if (hasTime) t - lastTime else 0f
        // A resumed or rewound clock must not inject a large impulse into the rig.
        val dt = if (still || rawDt !in 0f..0.1f) 0f else rawDt.coerceAtMost(PandaAnimParams.MAX_MOTION_DT)
        lastTime = t; hasTime = !still
        val offered = food.coerceIn(0f, 1f)
        val feeding = maxOf(e.pawRaise, offered)
        val m = motion.update(mood, previousMood, p, isPet, e, interaction,
            expression != BuddyExpression.NONE, feeding, still, dt)
        val f = frame
        val handOverride = maxOf(feeding, e.pawFold, e.pawSplay / 15f).coerceIn(0f, 1f)
        val cover = lerp(old.coverEyes, pose.coverEyes, p) * (1f - handOverride) * (1f - m.idle.uncover)
        val scratch = maxOf(lerp(old.pawScratch, pose.pawScratch, p), m.idle.scratch) * (1f - handOverride)
        f.bodyColor = blend(old.bodyBot, pose.bodyBot, p)
        f.previousBodyTop = old.bodyTop; f.currentBodyTop = pose.bodyTop; f.bodyBlend = p
        f.glow = lerp(old.glow, pose.glow, p)
        f.headTilt = lerp(old.headTilt, pose.headTilt, p) + m.headTilt
        // The still pose contains the expression intent without needing a running spring.
        if (still) f.headTilt += e.headTilt
        f.headX = lerp(old.headX, pose.headX, p) + m.headX
        f.headY = lerp(old.headY, pose.headY, p) + m.headY + e.headY
        f.earDrop = lerp(old.earDrop, pose.earDrop, p)
        f.earWag = m.petAmount * 3f
        f.earLeft = m.earLag
        f.earRight = m.earRight
        f.footSpread = lerp(old.footSpread, pose.footSpread, p)
        f.patchDroop = lerp(old.patchDroop, pose.patchDroop, p)
        f.eyeY = PandaAnimParams.EYE_BASE_Y + PandaAnimParams.EYE_DROOP_RISE * f.patchDroop
        f.eyeStyle = pose.eyeStyle; f.previousEyeStyle = old.eyeStyle
        f.mouthStyle = pose.mouth; f.previousMouthStyle = old.mouth
        f.eyeOpen = lerp(old.eyeOpen, pose.eyeOpen, p)
        f.blinkAmount = blink.coerceIn(0f, 1f)
        f.gazeX = gazeX.coerceIn(-10f, 10f) * .35f + lerp(old.gazeX, pose.gazeX, p) + m.glance
        f.gazeY = gazeY.coerceIn(-10f, 10f) * .25f
        f.asym = lerp(old.asym, pose.asym, p)
        f.expressionEye = e.eye ?: if (m.petAmount > .005f) EyeStyle.HAPPY_ARC else null
        f.expressionEyeAmount = if (e.eye != null) e.amount else m.petAmount
        f.expressionMouth = if (offered > .01f) MouthStyle.OPEN else e.mouth ?: if (m.petAmount > .005f) MouthStyle.PET else null
        f.expressionMouthAmount = if (offered > .01f) maxOf(offered, e.amount) else if (e.mouth != null) e.amount else m.petAmount
        f.mouthOpen = if (offered > .01f) {
            offered * if (expression == BuddyExpression.DRINKING) .3f else 1f
        } else e.mouthOpen
        f.chew = if (feeding > .01f) e.chew else m.chew
        f.cheekColor = blend(old.cheek, pose.cheek, p)
        f.cheekAlpha = (lerp(old.cheekAlpha, pose.cheekAlpha, p) + e.blushBoost + .14f * m.petAmount).coerceIn(0f, 1f)
        f.cheekPuff = lerp(old.cheekPuff, pose.cheekPuff, p) + m.idle.puff
        f.bambooOpacity = lerp(if (old.bamboo) 1f else 0f, if (pose.bamboo) 1f else 0f, p) *
            (1f - maxOf(maxOf(feeding, e.pawFold, e.amount * if (e.pawSplay > 0f) 1f else 0f), m.idle.bambooRelease))

        val angle = f.headTilt * Math.PI.toFloat() / 180f
        val c = cos(angle); val s = sin(angle)
        for (i in 0..1) {
            val side = if (i == 0) -1f else 1f
            var x = side * (lerp(old.pawX, pose.pawX, p) + e.pawSplay)
            var y = lerp(old.pawY, pose.pawY, p) + side * lerp(old.pawAsym, pose.pawAsym, p) + m.pawFollow
            x += m.pawSwing + if (i == 0) m.idle.leftX else m.idle.rightX
            y += m.pawLift + if (i == 0) m.idle.leftY else m.idle.rightY
            // Map face-local targets through the head pivot before connecting the forearm.
            var hx = side * PandaAnimParams.PAW_COVER_X
            var hy = f.eyeY + 3f
            var faceWeight = cover
            if (i == 1 && scratch > 0f) {
                hx = lerp(hx, PandaAnimParams.PAW_SCRATCH_X, scratch)
                hy = lerp(hy, PandaAnimParams.PAW_SCRATCH_Y + m.idle.rightY, scratch)
                faceWeight = maxOf(faceWeight, scratch)
            }
            x = lerp(x, f.headX + hx * c - (hy - PandaAnimParams.HEAD_PIVOT_Y) * s, faceWeight)
            y = lerp(y, f.headY + PandaAnimParams.HEAD_PIVOT_Y + hx * s + (hy - PandaAnimParams.HEAD_PIVOT_Y) * c, faceWeight)
            val feedX = side * PandaAnimParams.PAW_FEED_X
            val feedY = PandaAnimParams.PAW_FEED_Y - PandaAnimParams.HEAD_PIVOT_Y
            x = lerp(x, f.headX + feedX * c - feedY * s, feeding)
            y = lerp(y, f.headY + PandaAnimParams.HEAD_PIVOT_Y + feedX * s + feedY * c, feeding)
            x = lerp(x, -side * PandaAnimParams.PAW_ANNOYED_X, e.pawFold)
            y = lerp(y, PandaAnimParams.PAW_ANNOYED_Y + side * 4f, e.pawFold)
            if (i == 0) { f.pawLeftX = x; f.pawLeftY = y }
            else { f.pawRightX = x; f.pawRightY = y }
        }
        canvas.save()
        canvas.scale(radius / PandaAnimParams.NOMINAL_RADIUS, radius / PandaAnimParams.NOMINAL_RADIUS)
        canvas.translate(0f, -m.delightHop)
        val squash = (lerp(old.compression, pose.compression, p) + m.pokeSquash - e.stretch - m.breath).coerceIn(-.12f, .24f)
        canvas.scale(1f + squash * .65f, 1f - squash, 0f, PandaAnimParams.GROUND_Y)
        canvas.rotate(lerp(old.bodyTilt, pose.bodyTilt, p) + m.bodyTilt + if (still) e.bodyTilt else 0f, 0f, 85f)
        rig.draw(canvas, f)
        rig.drawAccessory(canvas, old.accessory, 1f - p, f)
        rig.drawAccessory(canvas, pose.accessory, p, f)
        canvas.restore()
    }

    private fun lerp(a: Float, b: Float, p: Float) = a + (b - a) * p
    private fun blend(a: Int, b: Int, p: Float): Int {
        var result = 0
        for (shift in 0..24 step 8) {
            val channel = lerp((a ushr shift and 255).toFloat(), (b ushr shift and 255).toFloat(), p).toInt().coerceIn(0, 255)
            result = result or (channel shl shift)
        }
        return result
    }
}

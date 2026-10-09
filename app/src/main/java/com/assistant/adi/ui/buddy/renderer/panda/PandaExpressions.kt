package com.assistant.adi.ui.buddy.renderer.panda

import com.assistant.adi.ui.buddy.BuddyExpression
import kotlin.math.sin

/** Reused expression intent; offsets already carry their attack/hold/release envelope. */
class PandaExpressionOverride {
    var eye: EyeStyle? = null
    var mouth: MouthStyle? = null
    var amount = 0f
    var mouthOpen = 0f
    var blushBoost = 0f
    var stretch = 0f
    var pawFold = 0f
    var pawRaise = 0f
    var pawSplay = 0f
    var pokeSquash = 0f
    var bounce = 0f
    var energy = 0f
    var headTilt = 0f
    var headY = 0f
    var bodyTilt = 0f
    var chew = 0f

    fun reset() {
        eye = null; mouth = null; amount = 0f; mouthOpen = 0f; blushBoost = 0f
        stretch = 0f; pawFold = 0f; pawRaise = 0f; pawSplay = 0f
        pokeSquash = 0f; bounce = 0f; energy = 0f
        headTilt = 0f; headY = 0f; bodyTilt = 0f; chew = 0f
    }
}

object PandaExpressions {
    private fun smooth(x: Float): Float {
        val u = x.coerceIn(0f, 1f)
        return u * u * (3f - 2f * u)
    }

    fun resolve(expression: BuddyExpression, progress: Float, still: Boolean, out: PandaExpressionOverride) {
        out.reset()
        if (expression == BuddyExpression.NONE) return
        val p = if (still) .5f else progress.coerceIn(0f, 1f)
        val attack = if (expression == BuddyExpression.SLEEPY_YAWN) .32f else .18f
        val a = if (still) 1f else smooth(p / attack) * (1f - smooth((p - .68f) / .32f))
        val wave = if (still) 0f else sin(p * Math.PI.toFloat() * 6f)
        out.amount = a
        when (expression) {
            BuddyExpression.TAP_DELIGHT -> {
                out.eye = EyeStyle.HAPPY_ARC; out.mouth = MouthStyle.PET
                out.blushBoost = .18f * a
                // Compress first, lift, then let the spring carry the landing.
                out.pokeSquash = .10f * (1f - smooth(p / .30f))
                out.bounce = PandaAnimParams.DELIGHT_BOUNCE * smooth((p - .12f) / .23f) * (1f - smooth((p - .48f) / .4f))
                out.pawSplay = 10f * a; out.headTilt = -7f * a; out.energy = .8f * a
            }
            BuddyExpression.TICKLE_LAUGH -> {
                out.eye = EyeStyle.HAPPY_ARC; out.mouth = MouthStyle.OPEN
                out.mouthOpen = .7f + .2f * wave * a
                out.bodyTilt = wave * 2.5f * a; out.headTilt = -wave * 4f * a
                out.pokeSquash = .024f * wave * a
                out.headY = -2f * a; out.pawSplay = 9f * a; out.energy = a
            }
            BuddyExpression.ANNOYED -> {
                out.eye = EyeStyle.SIDE_GLANCE; out.mouth = MouthStyle.FLAT
                out.pawFold = a; out.headTilt = -9f * a; out.headY = -3f * a
                out.bodyTilt = 3f * a; out.blushBoost = .2f * a
            }
            BuddyExpression.POKE -> {
                out.eye = EyeStyle.WIDE; out.mouth = MouthStyle.OPEN; out.mouthOpen = .25f
                out.pokeSquash = PandaAnimParams.POKE_SQUASH * a
                out.headY = 3f * a; out.pawSplay = 6f * a
            }
            BuddyExpression.EATING -> {
                out.mouth = MouthStyle.OPEN; out.mouthOpen = .35f + .22f * wave * a
                out.pawRaise = a; out.chew = wave * a; out.headY = 2f * a
                out.headTilt = 2f * wave * a; out.blushBoost = .1f * a
            }
            BuddyExpression.DRINKING -> {
                out.mouth = MouthStyle.OPEN; out.mouthOpen = .2f
                out.pawRaise = a; out.headTilt = -4f * a; out.headY = -4f * a
                out.stretch = .012f * a; out.chew = .25f * wave * a
            }
            BuddyExpression.SPICY -> {
                out.eye = EyeStyle.STRAINED; out.mouth = MouthStyle.TONGUE
                out.bodyTilt = wave * 2f * a; out.headTilt = -wave * 5f * a
                out.pawSplay = 15f * a; out.headY = -4f * a
                out.stretch = .025f * a; out.energy = a; out.blushBoost = .35f * a
            }
            BuddyExpression.SLEEPY_YAWN -> {
                out.eye = EyeStyle.FLAT; out.mouth = MouthStyle.OPEN; out.mouthOpen = a
                out.stretch = PandaAnimParams.YAWN_STRETCH * a
                out.headTilt = 5f * a; out.headY = -5f * a; out.pawRaise = .72f * a
            }
            BuddyExpression.NONE -> Unit
        }
        if (still) { out.pokeSquash = if (expression == BuddyExpression.POKE) PandaAnimParams.POKE_SQUASH else 0f; out.bounce = 0f }
    }
}

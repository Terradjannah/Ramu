package com.assistant.adi.ui.buddy.renderer

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.assistant.adi.ui.buddy.BuddyExpression
import com.assistant.adi.ui.buddy.BuddyMood
import kotlin.math.sin

/**
 * Mochi the cat. Reference implementation for the [IBuddyRenderer] contract:
 * geometry is authored against a nominal radius of 100 and normalised per draw,
 * so every Paint, Path, RectF and Shader below is allocated once at construction.
 */
class CatRenderer : IBuddyRenderer {
    override val characterId: String = "cat"

    private enum class EyeStyle { OPEN, HAPPY_ARC, STRAINED, SPIRAL, FLAT, WIDE, SIDE_GLANCE }
    private enum class MouthStyle { SMILE, TONGUE, OPEN, FLAT, STRAINED, PET }
    private enum class Accessory { NONE, QUESTION, SWEAT, BOLT }

    private class CatPose(
        val earDroop: Float,
        val headTilt: Float,
        val eyeOpen: Float,
        val eyeStyle: EyeStyle,
        val mouth: MouthStyle,
        val bodyTop: Int,
        val bodyBot: Int,
        val cheek: Int,
        val cheekAlpha: Float,
        val pawPanic: Float,
        val curl: Float,
        val lean: Float,
        val asym: Float,
        val accessory: Accessory
    )

    // Mood-order matches BuddyMood ordinal order.
    private val poses = arrayOf(
        CatPose(0f, 0f, 1f, EyeStyle.OPEN, MouthStyle.SMILE, WHITE, CREAM, PINK, .45f, 0f, 0f, 0f, 0f, Accessory.NONE),
        CatPose(1f, 0f, 1f, EyeStyle.STRAINED, MouthStyle.TONGUE, ROSE_LIGHT, ROSE, RED, .9f, 0f, 0f, 0f, 0f, Accessory.SWEAT),
        CatPose(.85f, 3f, .35f, EyeStyle.FLAT, MouthStyle.FLAT, SLATE_LIGHT, SLATE, SLATE_DARK, .3f, 0f, 1f, 0f, 0f, Accessory.NONE),
        CatPose(0f, -9f, .9f, EyeStyle.OPEN, MouthStyle.FLAT, WHITE, CREAM, PINK, .4f, 0f, 0f, 0f, .55f, Accessory.QUESTION),
        CatPose(.15f, 0f, 1f, EyeStyle.SPIRAL, MouthStyle.STRAINED, WHITE, CREAM, PINK, .5f, 1f, .08f, 0f, 0f, Accessory.SWEAT),
        CatPose(.4f, 0f, .65f, EyeStyle.FLAT, MouthStyle.FLAT, WHITE, CREAM, PINK, .3f, 0f, 0f, 0f, 0f, Accessory.NONE),
        CatPose(.1f, 0f, 1f, EyeStyle.HAPPY_ARC, MouthStyle.SMILE, YELLOW_LIGHT, YELLOW, PINK, .6f, 0f, 0f, 0f, 0f, Accessory.BOLT),
        CatPose(0f, 9f, 1.15f, EyeStyle.WIDE, MouthStyle.OPEN, WHITE, CREAM, PINK, .45f, 0f, 0f, 1f, 0f, Accessory.NONE)
    )

    private val bodyLight = Array(poses.size) { i ->
        val top = poses[i].bodyTop
        RadialGradient(-25f, -35f, 110f, intArrayOf(top, top and 0x00FFFFFF), null, Shader.TileMode.CLAMP)
    }
    private val specular = RadialGradient(-35f, -45f, 60f,
        intArrayOf(0x8CFFFFFF.toInt(), 0x00FFFFFF), null, Shader.TileMode.CLAMP)
    private val cheekPink = RadialGradient(0f, 0f, 18f, intArrayOf(PINK, PINK and 0x00FFFFFF), null, Shader.TileMode.CLAMP)
    private val cheekHot = RadialGradient(0f, 0f, 18f, intArrayOf(RED, RED and 0x00FFFFFF), null, Shader.TileMode.CLAMP)
    private val cheekSlate = RadialGradient(0f, 0f, 18f, intArrayOf(SLATE_DARK, SLATE_DARK and 0x00FFFFFF), null, Shader.TileMode.CLAMP)

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bodyLightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val specularPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = specular }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; color = RIM
    }
    private val earPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val earInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PINK }
    private val cheekPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eyeWhitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WHITE }
    private val eyeDarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = EYE_DARK }
    private val eyeDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WHITE }
    private val eyeLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3.5f; strokeCap = Paint.Cap.ROUND; color = EYE_DARK
    }
    private val nosePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = NOSE }
    private val mouthLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f; strokeCap = Paint.Cap.ROUND; color = MOUTH_LINE
    }
    private val mouthFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MOUTH_OPEN }
    private val tonguePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TONGUE }
    private val whiskerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.8f; strokeCap = Paint.Cap.ROUND; color = WHISKER
    }
    private val pawPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WHITE }
    private val clawPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.6f; strokeCap = Paint.Cap.ROUND; color = CLAW
    }
    private val accessoryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CHIME }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = CHIME; textAlign = Paint.Align.CENTER; textSize = 30f; typeface = Typeface.DEFAULT_BOLD
    }

    private val bodyPath = Path()
    private val earPath = Path()
    private val earInnerPath = Path()
    private val tailPath = Path()
    private val nosePath = Path()
    private val spiralPath = Path()
    private val accessoryPath = Path()
    private val arcRect = RectF()

    override fun draw(
        canvas: Canvas,
        radius: Float,
        mood: BuddyMood,
        transitionProgress: Float,
        previousMood: BuddyMood,
        elapsedSeconds: Float,
        isBlinking: Boolean,
        blinkAmount: Float,
        gazeX: Float,
        gazeY: Float,
        isPet: Boolean,
        mouthOpenFood: Float,
        expression: BuddyExpression,
        expressionProgress: Float
    ) {
        render(canvas, radius, mood, previousMood, transitionProgress, elapsedSeconds,
            blinkAmount, gazeX, gazeY, isPet, mouthOpenFood, expression, expressionProgress, false)
    }

    override fun drawStillFrame(canvas: Canvas, radius: Float, mood: BuddyMood, expression: BuddyExpression) {
        render(canvas, radius, mood, mood, 1f, 0f, 1f, 0f, 0f, false, 0f, expression, .5f, true)
    }

    private fun render(
        canvas: Canvas,
        radius: Float,
        mood: BuddyMood,
        previousMood: BuddyMood,
        progress: Float,
        t: Float,
        blinkAmount: Float,
        gazeX: Float,
        gazeY: Float,
        isPet: Boolean,
        mouthOpenFood: Float,
        expression: BuddyExpression,
        expressionProgress: Float,
        still: Boolean
    ) {
        if (radius <= 0f) return
        val p = progress.coerceIn(0f, 1f)
        val cur = poses[mood.ordinal]
        val prev = poses[previousMood.ordinal]

        canvas.save()
        canvas.scale(radius / NOMINAL, radius / NOMINAL)

        val earDroop = lerp(prev.earDroop, cur.earDroop, p)
        val eyeOpen = lerp(prev.eyeOpen, cur.eyeOpen, p)
        val curl = lerp(prev.curl, cur.curl, p)
        val lean = lerp(prev.lean, cur.lean, p)
        val pawPanic = lerp(prev.pawPanic, cur.pawPanic, p)
        val cheekAlpha = lerp(prev.cheekAlpha, cur.cheekAlpha, p)
        val cheekColor = blend(prev.cheek, cur.cheek, p)
        val bodyBot = blend(prev.bodyBot, cur.bodyBot, p)
        val headTilt = lerp(prev.headTilt, cur.headTilt, p)

        val expressionAmount = if (expression == BuddyExpression.NONE) 0f else if (still) 1f else sin(expressionProgress.coerceIn(0f, 1f) * Math.PI.toFloat())
        val laughing = expression == BuddyExpression.TICKLE_LAUGH
        val annoyed = expression == BuddyExpression.ANNOYED
        val spicy = expression == BuddyExpression.SPICY
        val yawning = expression == BuddyExpression.SLEEPY_YAWN
        val petWiggle = if (isPet && !still) sin(t * 12f).toFloat() * 3.5f else 0f
        val expressionWiggle = if (!still && (laughing || spicy)) sin(t * if (laughing) 24f else 18f).toFloat() * 5f * expressionAmount else 0f
        val tailWag = if (still) 0f else if (isPet) sin(t * 18f).toFloat() * 22f else sin(t * 3.5f).toFloat() * 12f
        val earTwitch = if (isPet && !still) sin(t * 12f).toFloat() * 4.5f else 0f
        val pant = if (!still && mood == BuddyMood.HOT) sin(t * 6.5f).toFloat() * 2f else 0f
        val breathe = if (still) 0f else sin(t * 2.2f).toFloat() * .025f

        // Pose transform: curl on LOW_POWER, forward lean on CURIOUS, internal head tilt.
        canvas.save()
        canvas.translate(0f, 8f * curl - 3f * lean + pant)
        val pokeSquash = if (expression == BuddyExpression.POKE) .10f * expressionAmount else 0f
        canvas.scale(1f + .10f * curl + breathe + pokeSquash, 1f - .16f * curl - breathe - pokeSquash)
        if (lean > 0f) canvas.scale(1f - .03f * lean, 1f + .02f * lean)
        canvas.rotate(headTilt + petWiggle + expressionWiggle)

        // Tail (behind body) with soft inertia.
        tailPath.reset()
        tailPath.moveTo(60f, 60f)
        tailPath.cubicTo(85f + tailWag, 50f, 100f + tailWag * 1.2f, 20f, 85f + tailWag * 1.4f, -10f)
        bodyPaint.style = Paint.Style.STROKE
        bodyPaint.strokeWidth = 14f
        bodyPaint.strokeCap = Paint.Cap.ROUND
        bodyPaint.color = bodyBot
        canvas.drawPath(tailPath, bodyPaint)
        bodyPaint.style = Paint.Style.FILL

        // Ears.
        for (i in 0..1) {
            val side = if (i == 0) -1f else 1f
            canvas.save()
            canvas.translate(side * 45f, -72f)
            canvas.rotate(side * (25f + earTwitch) + side * earDroop * 55f)
            earPath.reset()
            earPath.moveTo(-16f, 12f)
            earPath.cubicTo(-10f, -30f, 8f, -34f, 18f, 8f)
            earPath.close()
            earPaint.color = blend(prev.bodyTop, cur.bodyTop, p)
            canvas.drawPath(earPath, earPaint)
            earInnerPath.reset()
            earInnerPath.moveTo(-9f, 8f)
            earInnerPath.cubicTo(-6f, -20f, 6f, -22f, 11f, 6f)
            earInnerPath.close()
            canvas.drawPath(earInnerPath, earInnerPaint)
            canvas.restore()
        }

        // Body: base tone + soft dimensional highlight + specular + rim.
        bodyPath.reset()
        bodyPath.moveTo(0f, -82f)
        bodyPath.cubicTo(62f, -75f, 95f, -10f, 92f, 52f)
        bodyPath.cubicTo(88f, 95f, 52f, 98f, 0f, 98f)
        bodyPath.cubicTo(-52f, 98f, -88f, 95f, -92f, 52f)
        bodyPath.cubicTo(-95f, -10f, -62f, -75f, 0f, -82f)
        bodyPath.close()
        bodyPaint.color = bodyBot
        canvas.drawPath(bodyPath, bodyPaint)

        bodyLightPaint.shader = bodyLight[previousMood.ordinal]
        bodyLightPaint.alpha = ((1f - p) * 255f).toInt().coerceIn(0, 255)
        canvas.drawPath(bodyPath, bodyLightPaint)
        bodyLightPaint.shader = bodyLight[mood.ordinal]
        bodyLightPaint.alpha = (p * 255f).toInt().coerceIn(0, 255)
        canvas.drawPath(bodyPath, bodyLightPaint)
        bodyLightPaint.alpha = 255

        canvas.save()
        canvas.clipPath(bodyPath)
        canvas.drawCircle(-35f, -45f, 60f, specularPaint)
        canvas.restore()
        canvas.drawPath(bodyPath, rimPaint)

        // Airbrushed cheeks.
        val cheekGrad = when (mood) {
            BuddyMood.HOT, BuddyMood.CHARGING -> cheekHot
            BuddyMood.LOW_POWER, BuddyMood.OFFLINE -> cheekSlate
            else -> cheekPink
        }
        cheekPaint.shader = cheekGrad
        cheekPaint.color = cheekColor
        val blushBoost = if (annoyed || expression == BuddyExpression.TAP_DELIGHT) .35f * expressionAmount else 0f
        val blushAlpha = ((cheekAlpha + blushBoost) * (if (isPet) 1.5f else 1f) * 255f).toInt().coerceIn(0, 255)
        cheekPaint.alpha = blushAlpha
        for (i in 0..1) {
            val side = if (i == 0) -1f else 1f
            canvas.save()
            canvas.translate(side * 45f, 8f)
            canvas.drawCircle(0f, 0f, 18f, cheekPaint)
            canvas.restore()
        }
        cheekPaint.alpha = 255

        // Eyes.
        val style = when {
            laughing || expression == BuddyExpression.TAP_DELIGHT -> EyeStyle.HAPPY_ARC
            annoyed -> EyeStyle.SIDE_GLANCE
            spicy -> EyeStyle.STRAINED
            yawning -> EyeStyle.FLAT
            isPet -> EyeStyle.HAPPY_ARC
            else -> cur.eyeStyle
        }
        drawEyes(canvas, style, eyeOpen, blinkAmount, gazeX, gazeY, lerp(prev.asym, cur.asym, p))

        // Nose.
        nosePath.reset()
        nosePath.moveTo(0f, 5f)
        nosePath.lineTo(-4.5f, 1f)
        nosePath.lineTo(4.5f, 1f)
        nosePath.close()
        canvas.drawPath(nosePath, nosePaint)

        // Mouth.
        val expressionMouth = when (expression) {
            BuddyExpression.TICKLE_LAUGH, BuddyExpression.EATING, BuddyExpression.DRINKING, BuddyExpression.SLEEPY_YAWN -> MouthStyle.OPEN
            BuddyExpression.TAP_DELIGHT -> MouthStyle.PET
            BuddyExpression.ANNOYED -> MouthStyle.FLAT
            BuddyExpression.SPICY -> MouthStyle.TONGUE
            else -> null
        }
        val mouth = if (mouthOpenFood > .2f) MouthStyle.OPEN else expressionMouth ?: if (isPet) MouthStyle.PET else cur.mouth
        val openAmount = if (mouthOpenFood > .2f) mouthOpenFood else if (mouth == MouthStyle.OPEN) {
            if (yawning) 1f else .65f + .35f * expressionAmount
        } else 0f
        drawMouth(canvas, mouth, openAmount)

        // Whiskers.
        for (i in 0..1) {
            val side = if (i == 0) -1f else 1f
            canvas.drawLine(side * 38f, 8f, side * 75f, 4f, whiskerPaint)
            canvas.drawLine(side * 38f, 14f, side * 72f, 18f, whiskerPaint)
        }

        // Front paws, or panic claws near the head when overwhelmed.
        val pawY = 82f - 122f * pawPanic
        val pawSpread = 28f + 6f * pawPanic
        for (i in 0..1) {
            val side = if (i == 0) -1f else 1f
            canvas.save()
            canvas.translate(side * pawSpread, pawY)
            pawPaint.color = blend(prev.bodyTop, cur.bodyTop, p)
            canvas.drawOval(-12f, -16f, 12f, 16f, pawPaint)
            canvas.drawOval(-12f, -16f, 12f, 16f, rimPaint)
            if (pawPanic > .05f) {
                for (c in -1..1) {
                    canvas.drawLine(c * 5f, -16f, c * 7f, -26f, clawPaint)
                }
            }
            canvas.restore()
        }
        canvas.restore()

        // Accessory cross-fade between outgoing and incoming mood.
        drawAccessory(canvas, prev.accessory, 1f - p, t)
        drawAccessory(canvas, cur.accessory, p, t)

        canvas.restore()
    }

    private fun drawEyes(canvas: Canvas, style: EyeStyle, eyeOpen: Float, blinkAmount: Float, gazeX: Float, gazeY: Float, asym: Float) {
        val spacing = 32f
        val eyeY = -8f
        for (i in 0..1) {
            val side = if (i == 0) -1f else 1f
            val ex = side * spacing
            val eff = (eyeOpen * blinkAmount * if (side > 0f) (1f - .45f * asym) else 1f).coerceIn(.04f, 1.3f)
            when (style) {
                EyeStyle.OPEN, EyeStyle.WIDE -> {
                    val rx = if (style == EyeStyle.WIDE) 15f else 13f
                    val ry = rx * eff
                    eyeWhitePaint.color = WHITE
                    canvas.drawOval(ex - rx, eyeY - ry, ex + rx, eyeY + ry, eyeWhitePaint)
                    if (eff > .3f) {
                        val pupil = if (style == EyeStyle.WIDE) 7.5f else 7f
                        eyeDarkPaint.color = EYE_DARK
                        canvas.drawCircle(ex + gazeX, eyeY + gazeY, pupil, eyeDarkPaint)
                        canvas.drawCircle(ex + gazeX - 2.6f, eyeY + gazeY - 2.6f, 3f, eyeDotPaint)
                        canvas.drawCircle(ex + gazeX + 2.4f, eyeY + gazeY + 2.4f, 1.3f, eyeDotPaint)
                    }
                }
                EyeStyle.SIDE_GLANCE -> {
                    val rx = 13f
                    val ry = 9f * blinkAmount
                    canvas.drawOval(ex - rx, eyeY - ry, ex + rx, eyeY + ry, eyeWhitePaint)
                    canvas.drawCircle(ex + side * 4f, eyeY, 6.5f, eyeDarkPaint)
                    canvas.drawCircle(ex + side * 1.7f, eyeY - 2.6f, 2.5f, eyeDotPaint)
                }
                EyeStyle.HAPPY_ARC -> {
                    arcRect.set(ex - 11f, eyeY - 9f, ex + 11f, eyeY + 13f)
                    canvas.drawArc(arcRect, 207f, 126f, false, eyeLinePaint)
                }
                EyeStyle.STRAINED -> {
                    spiralPath.reset()
                    spiralPath.moveTo(ex - 8f * side, eyeY - 6f)
                    spiralPath.lineTo(ex + 8f * side, eyeY)
                    spiralPath.lineTo(ex - 8f * side, eyeY + 6f)
                    canvas.drawPath(spiralPath, eyeLinePaint)
                }
                EyeStyle.SPIRAL -> {
                    canvas.save()
                    canvas.translate(ex, eyeY)
                    spiralPath.reset()
                    arcRect.set(-8f, -8f, 8f, 8f); spiralPath.addArc(arcRect, 90f, 250f)
                    arcRect.set(-4.5f, -4.5f, 4.5f, 4.5f); spiralPath.addArc(arcRect, 340f, 250f)
                    arcRect.set(-1.8f, -1.8f, 1.8f, 1.8f); spiralPath.addArc(arcRect, 230f, 250f)
                    canvas.drawPath(spiralPath, eyeLinePaint)
                    canvas.restore()
                }
                EyeStyle.FLAT -> {
                    canvas.drawLine(ex - 10f, eyeY + 2f, ex + 10f, eyeY + 2f, eyeLinePaint)
                }
            }
        }
    }

    private fun drawMouth(canvas: Canvas, style: MouthStyle, mouthOpenFood: Float) {
        val mouthY = 12f
        when (style) {
            MouthStyle.SMILE -> {
                arcRect.set(-11f, mouthY - 6.5f, 0f, mouthY + 4.5f)
                canvas.drawArc(arcRect, 27f, 144f, false, mouthLinePaint)
                arcRect.set(0f, mouthY - 6.5f, 11f, mouthY + 4.5f)
                canvas.drawArc(arcRect, 9f, 144f, false, mouthLinePaint)
            }
            MouthStyle.TONGUE -> {
                arcRect.set(-9f, mouthY - 3f, 9f, mouthY + 9f)
                canvas.drawArc(arcRect, 0f, 360f, true, mouthFillPaint)
                tonguePaint.color = TONGUE
                canvas.drawOval(-6f, mouthY + 3f, 6f, mouthY + 19f, tonguePaint)
            }
            MouthStyle.OPEN -> {
                val h = 6f + 12f * mouthOpenFood
                mouthFillPaint.color = MOUTH_OPEN
                canvas.drawOval(-10f, mouthY - h, 10f, mouthY + h, mouthFillPaint)
            }
            MouthStyle.FLAT -> {
                canvas.drawLine(-7f, mouthY, 7f, mouthY, mouthLinePaint)
            }
            MouthStyle.STRAINED -> {
                spiralPath.reset()
                spiralPath.moveTo(-9f, mouthY + 2f)
                spiralPath.lineTo(-3f, mouthY - 2f)
                spiralPath.lineTo(3f, mouthY + 2f)
                spiralPath.lineTo(9f, mouthY - 2f)
                canvas.drawPath(spiralPath, mouthLinePaint)
            }
            MouthStyle.PET -> {
                mouthFillPaint.color = 0xFFF43F5E.toInt()
                arcRect.set(-9f, mouthY - 9f, 9f, mouthY + 9f)
                canvas.drawArc(arcRect, 0f, 180f, true, mouthFillPaint)
                canvas.drawArc(arcRect, 0f, 180f, false, mouthLinePaint)
            }
        }
    }

    private fun drawAccessory(canvas: Canvas, accessory: Accessory, opacity: Float, t: Float) {
        if (accessory == Accessory.NONE || opacity <= .01f) return
        val alpha = (opacity * 255f).toInt().coerceIn(0, 255)
        when (accessory) {
            Accessory.QUESTION -> {
                textPaint.color = CHIME
                textPaint.alpha = alpha
                canvas.drawText("?", 72f, -58f + sin(t * 5f).toFloat() * 5f, textPaint)
                textPaint.alpha = 255
            }
            Accessory.SWEAT -> {
                canvas.save()
                canvas.translate(78f, 6f + if (t < 1.8f) sin(t * Math.PI.toFloat() / 1.8f) * 7f else 0f)
                accessoryPath.reset()
                accessoryPath.moveTo(0f, -8f)
                accessoryPath.cubicTo(-9f, 2f, -7f, 12f, 0f, 14f)
                accessoryPath.cubicTo(7f, 12f, 9f, 2f, 0f, -8f)
                accessoryPath.close()
                accessoryPaint.color = SWEAT_BLUE
                accessoryPaint.alpha = alpha
                canvas.drawPath(accessoryPath, accessoryPaint)
                accessoryPaint.color = 0xBBE6FAFF.toInt()
                accessoryPaint.alpha = (alpha * .7f).toInt()
                canvas.drawOval(-4f, -2f, -1f, 3f, accessoryPaint)
                accessoryPaint.alpha = 255
                canvas.restore()
            }
            Accessory.BOLT -> {
                canvas.save()
                canvas.translate(0f, -108f + sin(t * 6f).toFloat() * 4f)
                accessoryPath.reset()
                accessoryPath.moveTo(2f, -14f)
                accessoryPath.lineTo(-8f, 2f)
                accessoryPath.lineTo(-1f, 2f)
                accessoryPath.lineTo(-4f, 14f)
                accessoryPath.lineTo(8f, -4f)
                accessoryPath.lineTo(1f, -4f)
                accessoryPath.close()
                accessoryPaint.color = CHIME
                accessoryPaint.alpha = alpha
                canvas.drawPath(accessoryPath, accessoryPaint)
                accessoryPaint.alpha = 255
                canvas.restore()
            }
            Accessory.NONE -> Unit
        }
    }

    private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f

    private fun blend(a: Int, b: Int, f: Float): Int {
        val inv = 1f - f
        val alpha = ((a ushr 24 and 0xFF) * inv + (b ushr 24 and 0xFF) * f).toInt().coerceIn(0, 255)
        val red = ((a ushr 16 and 0xFF) * inv + (b ushr 16 and 0xFF) * f).toInt().coerceIn(0, 255)
        val green = ((a ushr 8 and 0xFF) * inv + (b ushr 8 and 0xFF) * f).toInt().coerceIn(0, 255)
        val blue = ((a and 0xFF) * inv + (b and 0xFF) * f).toInt().coerceIn(0, 255)
        return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
    }

    private companion object {
        const val NOMINAL = 100f

        val WHITE = 0xFFFFFFFF.toInt()
        val CREAM = 0xFFF1EDE6.toInt()
        val ROSE_LIGHT = 0xFFFEE2E2.toInt()
        val ROSE = 0xFFFCA5A5.toInt()
        val RED = 0xFFDC2626.toInt()
        val SLATE_LIGHT = 0xFFE2E8F0.toInt()
        val SLATE = 0xFFCBD5E1.toInt()
        val SLATE_DARK = 0xFF94A3B8.toInt()
        val YELLOW_LIGHT = 0xFFFEF08A.toInt()
        val YELLOW = 0xFFFED7AA.toInt()
        val PINK = 0xFFF472B6.toInt()
        val EYE_DARK = 0xFF0F172A.toInt()
        val MOUTH_OPEN = 0xFFBE123C.toInt()
        val MOUTH_LINE = 0xFF475569.toInt()
        val TONGUE = 0xFFFB7185.toInt()
        val NOSE = 0xFFF472B6.toInt()
        val WHISKER = 0x6664748B
        val RIM = 0x40B4A096
        val CLAW = 0xFFCBD5E1.toInt()
        val CHIME = 0xFFFBBF24.toInt()
        val SWEAT_BLUE = 0xFF70ADCF.toInt()
    }
}

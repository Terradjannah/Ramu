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
 * Ducky the duck. Follows the [CatRenderer] contract: geometry is authored
 * against a nominal radius of 100 and normalised per draw, so every Paint,
 * Path, RectF and Shader is allocated once at construction.
 */
class DuckRenderer : IBuddyRenderer {
    override val characterId: String = "duck"

    private enum class EyeStyle { OPEN, HAPPY_ARC, SIDE_GLANCE, STRAINED, SPIRAL, FLAT, WIDE }
    private enum class BillStyle { SMILE, OPEN, FLAT, PET, TONGUE }
    private enum class Accessory { NONE, QUESTION, SWEAT, BOLT }

    private class DuckPose(
        val crest: Float,
        val headTilt: Float,
        val eyeOpen: Float,
        val eyeStyle: EyeStyle,
        val bill: BillStyle,
        val bodyTop: Int,
        val bodyBot: Int,
        val cheek: Int,
        val cheekAlpha: Float,
        val wingLift: Float,
        val fluff: Float,
        val curl: Float,
        val lean: Float,
        val asym: Float,
        val glow: Float,
        val accessory: Accessory
    )

    // Mood-order matches BuddyMood ordinal order. HAPPY flaps its stubby wings,
    // HOT pants through an open bill with ruffled down, LOW_POWER sinks with
    // drooping wings, CONFUSED raises its crest and cocks its head.
    private val poses = arrayOf(
        DuckPose(0f, 0f, 1f, EyeStyle.OPEN, BillStyle.SMILE, DOWN_LIGHT, DOWN, PINK, .5f, 1f, .2f, 0f, 0f, 0f, 0f, Accessory.NONE),
        DuckPose(0f, 0f, 1f, EyeStyle.STRAINED, BillStyle.OPEN, DOWN_LIGHT, GOLD, RED, .9f, .15f, 1f, 0f, 0f, 0f, 0f, Accessory.SWEAT),
        DuckPose(.25f, 3f, .3f, EyeStyle.FLAT, BillStyle.FLAT, SLATE_LIGHT, SLATE, SLATE_DARK, .3f, -1f, 0f, 1f, 0f, 0f, 0f, Accessory.NONE),
        DuckPose(1f, -10f, .9f, EyeStyle.OPEN, BillStyle.FLAT, DOWN_LIGHT, DOWN, PINK, .4f, .1f, 0f, 0f, 0f, .55f, 0f, Accessory.QUESTION),
        DuckPose(.45f, 0f, 1f, EyeStyle.SPIRAL, BillStyle.OPEN, DOWN_LIGHT, DOWN, PINK, .5f, 1f, .35f, .08f, 0f, 0f, 0f, Accessory.SWEAT),
        DuckPose(.3f, 0f, .6f, EyeStyle.FLAT, BillStyle.FLAT, SLATE_LIGHT, SLATE, SLATE_DARK, .3f, -.4f, 0f, .35f, 0f, 0f, 0f, Accessory.NONE),
        DuckPose(.1f, 0f, 1f, EyeStyle.HAPPY_ARC, BillStyle.SMILE, DOWN_LIGHT, GOLD, PINK, .6f, .5f, .15f, 0f, 0f, 0f, 1f, Accessory.BOLT),
        DuckPose(.55f, 10f, 1.15f, EyeStyle.WIDE, BillStyle.OPEN, DOWN_LIGHT, DOWN, PINK, .45f, .25f, 0f, 0f, 1f, 0f, 0f, Accessory.NONE)
    )

    // Static ruffled-feather fan: x, y, rotation triples around the silhouette.
    private val fluffSpikes = floatArrayOf(
        -52f, -62f, -41f,
        -68f, -34f, -63f,
        52f, -62f, 41f,
        68f, -34f, 63f,
        -74f, 34f, -115f,
        74f, 34f, 115f,
        -58f, 76f, -143f,
        58f, 76f, 143f
    )

    private val bodyLight = Array(poses.size) { i ->
        val top = poses[i].bodyTop
        RadialGradient(-25f, -40f, 115f, intArrayOf(top, top and 0x00FFFFFF), null, Shader.TileMode.CLAMP)
    }
    private val specular = RadialGradient(-32f, -50f, 60f,
        intArrayOf(0x8CFFFFFF.toInt(), 0x00FFFFFF), null, Shader.TileMode.CLAMP)
    private val cheekPink = RadialGradient(0f, 0f, 16f, intArrayOf(PINK, PINK and 0x00FFFFFF), null, Shader.TileMode.CLAMP)
    private val cheekHot = RadialGradient(0f, 0f, 16f, intArrayOf(RED, RED and 0x00FFFFFF), null, Shader.TileMode.CLAMP)
    private val cheekSlate = RadialGradient(0f, 0f, 16f, intArrayOf(SLATE_DARK, SLATE_DARK and 0x00FFFFFF), null, Shader.TileMode.CLAMP)
    private val bellyGlow = RadialGradient(0f, 46f, 62f,
        intArrayOf(0xB3FDE047.toInt(), 0x00FDE047), null, Shader.TileMode.CLAMP)

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bodyLightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val specularPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = specular }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = bellyGlow }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; color = RIM
    }
    private val wingPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val crestPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = DOWN_DEEP }
    private val tailPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fluffPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = DOWN_DEEP }
    private val downPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.4f; strokeCap = Paint.Cap.ROUND
    }
    private val cheekPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eyeDarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = EYE_DARK }
    private val eyeDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WHITE }
    private val eyeLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3.2f; strokeCap = Paint.Cap.ROUND; color = EYE_DARK
    }
    private val billPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BILL }
    private val billRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; color = BILL_DARK
    }
    private val billLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.4f; strokeCap = Paint.Cap.ROUND; color = BILL_LINE
    }
    private val billNostrilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BILL_LINE }
    private val mouthFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MOUTH_OPEN }
    private val tonguePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TONGUE }
    private val accessoryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CHIME }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = CHIME; textAlign = Paint.Align.CENTER; textSize = 30f; typeface = Typeface.DEFAULT_BOLD
    }

    private val bodyPath = Path()
    private val wingPath = Path()
    private val crestPath = Path()
    private val tailPath = Path()
    private val spiralPath = Path()
    private val accessoryPath = Path()
    private val fluffPath = Path().apply {
        moveTo(-7f, 1f)
        lineTo(0f, -16f)
        lineTo(7f, 1f)
        close()
    }
    private val arcRect = RectF()
    private val billRect = RectF()

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

        val crest = lerp(prev.crest, cur.crest, p)
        val headTilt = lerp(prev.headTilt, cur.headTilt, p)
        val eyeOpen = lerp(prev.eyeOpen, cur.eyeOpen, p)
        val curl = lerp(prev.curl, cur.curl, p)
        val lean = lerp(prev.lean, cur.lean, p)
        val wingLift = lerp(prev.wingLift, cur.wingLift, p)
        val fluff = lerp(prev.fluff, cur.fluff, p)
        val glow = lerp(prev.glow, cur.glow, p)
        val cheekAlpha = lerp(prev.cheekAlpha, cur.cheekAlpha, p)
        val cheekColor = blend(prev.cheek, cur.cheek, p)
        val bodyBot = blend(prev.bodyBot, cur.bodyBot, p)

        val expressionAmount = if (expression == BuddyExpression.NONE) 0f else if (still) 1f else sin(expressionProgress.coerceIn(0f, 1f) * Math.PI.toFloat())
        val laughing = expression == BuddyExpression.TICKLE_LAUGH
        val annoyed = expression == BuddyExpression.ANNOYED
        val spicy = expression == BuddyExpression.SPICY
        val yawning = expression == BuddyExpression.SLEEPY_YAWN
        val feeding = expression == BuddyExpression.EATING || expression == BuddyExpression.DRINKING
        val petWiggle = if (isPet && !still) sin(t * 12f).toFloat() * 3.5f else 0f
        val breathe = if (still) 0f else sin(t * 2.2f).toFloat() * 1.6f
        val flap = if (!still && (mood == BuddyMood.HAPPY || mood == BuddyMood.CHARGING)) sin(t * 14f).toFloat() * .4f else 0f
        val pant = if (!still && mood == BuddyMood.HOT) sin(t * 7f).toFloat() * 1.8f else 0f
        val sway = if (still) 0f else sin(t * 2f).toFloat() * 1.2f
        val expressionWiggle = if (!still && (laughing || spicy)) sin(t * if (laughing) 24f else 18f).toFloat() * 4.5f * expressionAmount else 0f
        val feedingWing = if (feeding) .25f * expressionAmount else 0f
        val wing = (wingLift + flap + feedingWing).coerceIn(-1.1f, 1.2f)

        // Pose transform: sink into a squat on LOW_POWER, lean forward on CURIOUS.
        canvas.save()
        canvas.translate(0f, 8f * curl - 3f * lean + pant + breathe + sway)
        val pokeSquash = if (expression == BuddyExpression.POKE) .10f * expressionAmount else 0f
        val yawnStretch = if (yawning) .04f * expressionAmount else 0f
        canvas.scale(1f + .10f * curl + pokeSquash - yawnStretch, 1f - .16f * curl - pokeSquash + yawnStretch)
        if (lean > 0f) canvas.scale(1f - .03f * lean, 1f + .02f * lean)
        canvas.rotate(headTilt + petWiggle + expressionWiggle + 6f * curl)

        // Tail plume behind the body, flicked by mood and petting.
        val tailWag = if (still) 0f else if (isPet) sin(t * 16f).toFloat() * 14f else sin(t * 3f).toFloat() * 6f
        tailPaint.color = bodyBot
        canvas.save()
        canvas.translate(70f, 58f)
        canvas.rotate(-20f + tailWag)
        for (k in 0..2) {
            canvas.save()
            canvas.rotate(k * 18f - 18f)
            tailPath.reset()
            tailPath.moveTo(0f, 0f)
            tailPath.cubicTo(10f, -7f, 22f, -9f, 30f, -2f)
            tailPath.cubicTo(20f, 8f, 8f, 8f, 0f, 0f)
            tailPath.close()
            canvas.drawPath(tailPath, tailPaint)
            canvas.drawPath(tailPath, rimPaint)
            canvas.restore()
        }
        canvas.restore()

        // Chubby down body: round head melting into a wide belly.
        bodyPath.reset()
        bodyPath.moveTo(0f, -92f)
        bodyPath.cubicTo(48f, -92f, 72f, -60f, 72f, -24f)
        bodyPath.cubicTo(72f, 0f, 94f, 16f, 94f, 50f)
        bodyPath.cubicTo(94f, 92f, 56f, 106f, 0f, 106f)
        bodyPath.cubicTo(-56f, 106f, -94f, 92f, -94f, 50f)
        bodyPath.cubicTo(-94f, 16f, -72f, 0f, -72f, -24f)
        bodyPath.cubicTo(-72f, -60f, -48f, -92f, 0f, -92f)
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
        canvas.drawCircle(-32f, -48f, 58f, specularPaint)
        if (glow > .01f) {
            glowPaint.alpha = (glow * 190f).toInt().coerceIn(0, 255)
            canvas.drawCircle(0f, 46f, 62f, glowPaint)
            glowPaint.alpha = 255
        }
        canvas.restore()
        canvas.drawPath(bodyPath, rimPaint)

        // Soft down scallops across the belly.
        downPaint.color = DOWN_DEEP
        downPaint.alpha = 90
        arcRect.set(-32f, 58f, 2f, 82f)
        canvas.drawArc(arcRect, 20f, 140f, false, downPaint)
        arcRect.set(-2f, 58f, 32f, 82f)
        canvas.drawArc(arcRect, 20f, 140f, false, downPaint)
        arcRect.set(-17f, 80f, 17f, 100f)
        canvas.drawArc(arcRect, 20f, 140f, false, downPaint)
        downPaint.alpha = 255

        // Ruffled feathers when flustered.
        if (fluff > .01f) {
            fluffPaint.alpha = (fluff * 255f).toInt().coerceIn(0, 255)
            for (k in fluffSpikes.indices step 3) {
                canvas.save()
                canvas.translate(fluffSpikes[k], fluffSpikes[k + 1])
                canvas.rotate(fluffSpikes[k + 2])
                canvas.drawPath(fluffPath, fluffPaint)
                canvas.restore()
            }
            fluffPaint.alpha = 255
        }

        // Stubby wings drawn over the body so their flap and droop stay readable.
        for (i in 0..1) {
            val side = if (i == 0) -1f else 1f
            canvas.save()
            canvas.translate(side * 70f, 6f)
            canvas.scale(side, 1f)
            canvas.rotate(40f - 80f * wing)
            wingPaint.color = bodyBot
            wingPath.reset()
            wingPath.moveTo(0f, -11f)
            wingPath.cubicTo(20f, -16f, 34f, -2f, 30f, 14f)
            wingPath.cubicTo(26f, 26f, 8f, 22f, 0f, 10f)
            wingPath.close()
            canvas.drawPath(wingPath, wingPaint)
            canvas.drawPath(wingPath, rimPaint)
            canvas.restore()
        }

        // Airbrushed cheeks flanking the bill.
        val cheekGrad = when (mood) {
            BuddyMood.HOT, BuddyMood.CHARGING -> cheekHot
            BuddyMood.LOW_POWER, BuddyMood.OFFLINE -> cheekSlate
            else -> cheekPink
        }
        cheekPaint.shader = cheekGrad
        cheekPaint.color = cheekColor
        val blushBoost = if (annoyed || expression == BuddyExpression.TAP_DELIGHT) .35f * expressionAmount else 0f
        cheekPaint.alpha = ((cheekAlpha + blushBoost) * (if (isPet) 1.5f else 1f) * 255f).toInt().coerceIn(0, 255)
        for (i in 0..1) {
            val side = if (i == 0) -1f else 1f
            canvas.save()
            canvas.translate(side * 44f, -34f)
            canvas.drawCircle(0f, 0f, 14f, cheekPaint)
            canvas.restore()
        }
        cheekPaint.alpha = 255

        // Bead eyes perched above the bill.
        val style = when {
            laughing || expression == BuddyExpression.TAP_DELIGHT -> EyeStyle.HAPPY_ARC
            annoyed -> EyeStyle.SIDE_GLANCE
            spicy -> EyeStyle.STRAINED
            yawning -> EyeStyle.FLAT
            isPet -> EyeStyle.HAPPY_ARC
            else -> cur.eyeStyle
        }
        drawEyes(canvas, style, eyeOpen, blinkAmount, gazeX, gazeY, lerp(prev.asym, cur.asym, p))

        val expressionBill = when (expression) {
            BuddyExpression.TICKLE_LAUGH, BuddyExpression.EATING, BuddyExpression.DRINKING, BuddyExpression.SLEEPY_YAWN -> BillStyle.OPEN
            BuddyExpression.TAP_DELIGHT -> BillStyle.PET
            BuddyExpression.ANNOYED -> BillStyle.FLAT
            BuddyExpression.SPICY -> BillStyle.TONGUE
            else -> null
        }
        val bill = if (mouthOpenFood > .2f) BillStyle.OPEN else expressionBill ?: if (isPet) BillStyle.PET else cur.bill
        val openAmount = if (mouthOpenFood > .2f) mouthOpenFood else if (bill == BillStyle.OPEN || bill == BillStyle.TONGUE) {
            if (yawning) 1f else .65f + .35f * expressionAmount
        } else 0f
        drawBill(canvas, bill, openAmount)

        // Feather tuft; stands upright when confused.
        drawCrest(canvas, crest, t, still)

        canvas.restore()

        // Accessory cross-fade between outgoing and incoming mood.
        drawAccessory(canvas, prev.accessory, 1f - p, t)
        drawAccessory(canvas, cur.accessory, p, t)

        canvas.restore()
    }

    private fun drawEyes(canvas: Canvas, style: EyeStyle, eyeOpen: Float, blinkAmount: Float, gazeX: Float, gazeY: Float, asym: Float) {
        val spacing = 25f
        val eyeY = -50f
        for (i in 0..1) {
            val side = if (i == 0) -1f else 1f
            val ex = side * spacing
            val eff = (eyeOpen * blinkAmount * if (side > 0f) (1f - .45f * asym) else 1f).coerceIn(.04f, 1.3f)
            when (style) {
                EyeStyle.OPEN, EyeStyle.WIDE -> {
                    val r = if (style == EyeStyle.WIDE) 11f else 9f
                    val ry = r * eff
                    canvas.drawOval(ex - r, eyeY - ry, ex + r, eyeY + ry, eyeDarkPaint)
                    if (eff > .3f) {
                        canvas.drawCircle(ex + gazeX - 2.2f, eyeY + gazeY - 2.2f, 2.6f, eyeDotPaint)
                    }
                }
                EyeStyle.HAPPY_ARC -> {
                    arcRect.set(ex - 9f, eyeY - 7f, ex + 9f, eyeY + 11f)
                    canvas.drawArc(arcRect, 207f, 126f, false, eyeLinePaint)
                }
                EyeStyle.SIDE_GLANCE -> {
                    val r = 9f
                    val ry = r * blinkAmount
                    canvas.drawOval(ex - r, eyeY - ry, ex + r, eyeY + ry, eyeDarkPaint)
                    canvas.drawCircle(ex + side * 3f, eyeY - 2f, 2.4f, eyeDotPaint)
                }
                EyeStyle.STRAINED -> {
                    spiralPath.reset()
                    spiralPath.moveTo(ex - 7f * side, eyeY - 6f)
                    spiralPath.lineTo(ex + 7f * side, eyeY)
                    spiralPath.lineTo(ex - 7f * side, eyeY + 6f)
                    canvas.drawPath(spiralPath, eyeLinePaint)
                }
                EyeStyle.SPIRAL -> {
                    canvas.save()
                    canvas.translate(ex, eyeY)
                    spiralPath.reset()
                    arcRect.set(-7f, -7f, 7f, 7f); spiralPath.addArc(arcRect, 90f, 250f)
                    arcRect.set(-4f, -4f, 4f, 4f); spiralPath.addArc(arcRect, 340f, 250f)
                    arcRect.set(-1.6f, -1.6f, 1.6f, 1.6f); spiralPath.addArc(arcRect, 230f, 250f)
                    canvas.drawPath(spiralPath, eyeLinePaint)
                    canvas.restore()
                }
                EyeStyle.FLAT -> {
                    canvas.drawLine(ex - 8f, eyeY + 2f, ex + 8f, eyeY + 2f, eyeLinePaint)
                }
            }
        }
    }

    private fun drawBill(canvas: Canvas, style: BillStyle, mouthOpenFood: Float) {
        val cy = -18f
        billRect.set(-31f, cy - 11f, 31f, cy + 11f)
        canvas.drawOval(billRect, billPaint)
        canvas.drawOval(billRect, billRimPaint)

        val open = when (style) {
            BillStyle.OPEN, BillStyle.TONGUE -> .6f + .4f * mouthOpenFood
            BillStyle.PET -> 1f
            else -> 0f
        }
        if (open > .02f) {
            canvas.drawOval(-21f, cy - 1f, 21f, cy + 11f, mouthFillPaint)
            if (style != BillStyle.OPEN || mouthOpenFood > .15f) {
                canvas.drawOval(-8f, cy + 1f, 8f, cy + 10f, tonguePaint)
            }
        }

        canvas.drawCircle(-9f, cy - 4f, 1.6f, billNostrilPaint)
        canvas.drawCircle(9f, cy - 4f, 1.6f, billNostrilPaint)

        when (style) {
            BillStyle.SMILE -> {
                arcRect.set(-20f, cy - 6f, 0f, cy + 6f)
                canvas.drawArc(arcRect, 27f, 144f, false, billLinePaint)
                arcRect.set(0f, cy - 6f, 20f, cy + 6f)
                canvas.drawArc(arcRect, 9f, 144f, false, billLinePaint)
            }
            BillStyle.FLAT -> canvas.drawLine(-20f, cy + 3f, 20f, cy + 3f, billLinePaint)
            BillStyle.OPEN, BillStyle.PET, BillStyle.TONGUE -> Unit
        }
    }

    private fun drawCrest(canvas: Canvas, crest: Float, t: Float, still: Boolean) {
        val sway = if (still) 0f else sin(t * 3f).toFloat() * 5f
        canvas.save()
        canvas.translate(0f, -84f)
        canvas.rotate(sway)
        for (k in -1..1) {
            canvas.save()
            canvas.rotate(k * (16f + 12f * crest) - 4f * (1f - crest))
            crestPath.reset()
            crestPath.moveTo(0f, 0f)
            crestPath.cubicTo(-4f, -10f, -3f, -20f, 2f, -26f - 8f * crest)
            crestPath.cubicTo(6f, -18f, 5f, -8f, 0f, 0f)
            crestPath.close()
            canvas.drawPath(crestPath, crestPaint)
            canvas.restore()
        }
        canvas.restore()
    }

    private fun drawAccessory(canvas: Canvas, accessory: Accessory, opacity: Float, t: Float) {
        if (accessory == Accessory.NONE || opacity <= .01f) return
        val alpha = (opacity * 255f).toInt().coerceIn(0, 255)
        when (accessory) {
            Accessory.QUESTION -> {
                textPaint.color = CHIME
                textPaint.alpha = alpha
                canvas.drawText("?", 76f, -62f + sin(t * 5f).toFloat() * 5f, textPaint)
                textPaint.alpha = 255
            }
            Accessory.SWEAT -> {
                canvas.save()
                canvas.translate(80f, 0f + if (t < 1.8f) sin(t * Math.PI.toFloat() / 1.8f) * 7f else 0f)
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
                canvas.translate(0f, -112f + sin(t * 6f).toFloat() * 4f)
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
        val DOWN_LIGHT = 0xFFFFF7CC.toInt()
        val DOWN = 0xFFFDE047.toInt()
        val DOWN_DEEP = 0xFFFACC15.toInt()
        val GOLD = 0xFFFBBF24.toInt()
        val SLATE_LIGHT = 0xFFE2E8F0.toInt()
        val SLATE = 0xFFCBD5E1.toInt()
        val SLATE_DARK = 0xFF94A3B8.toInt()
        val PINK = 0xFFF9A8D4.toInt()
        val RED = 0xFFDC2626.toInt()
        val EYE_DARK = 0xFF0F172A.toInt()
        val BILL = 0xFFFB923C.toInt()
        val BILL_DARK = 0xFFEA580C.toInt()
        val BILL_LINE = 0xFFC2410C.toInt()
        val MOUTH_OPEN = 0xFFBE123C.toInt()
        val TONGUE = 0xFFFB7185.toInt()
        val RIM = 0x33A16207
        val CHIME = 0xFFFBBF24.toInt()
        val SWEAT_BLUE = 0xFF70ADCF.toInt()
    }
}

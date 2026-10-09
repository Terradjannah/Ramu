package com.assistant.adi.ui.buddy.renderer.panda

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.assistant.adi.ui.buddy.renderer.panda.PandaAnimParams.CHEEK_RADIUS
import com.assistant.adi.ui.buddy.renderer.panda.PandaAnimParams.CHEEK_X
import com.assistant.adi.ui.buddy.renderer.panda.PandaAnimParams.CHEEK_Y
import com.assistant.adi.ui.buddy.renderer.panda.PandaAnimParams.EAR_CENTER_X
import com.assistant.adi.ui.buddy.renderer.panda.PandaAnimParams.EAR_CENTER_Y
import com.assistant.adi.ui.buddy.renderer.panda.PandaAnimParams.EAR_RADIUS
import com.assistant.adi.ui.buddy.renderer.panda.PandaAnimParams.HEAD_PIVOT_Y
import com.assistant.adi.ui.buddy.renderer.panda.PandaAnimParams.MOUTH_Y
import com.assistant.adi.ui.buddy.renderer.panda.PandaAnimParams.PATCH_X
import com.assistant.adi.ui.buddy.renderer.panda.PandaAnimParams.PATCH_RX
import com.assistant.adi.ui.buddy.renderer.panda.PandaAnimParams.PATCH_RY
import com.assistant.adi.ui.buddy.renderer.panda.PandaAnimParams.PATCH_TILT_DEG
import com.assistant.adi.ui.buddy.renderer.panda.PandaAnimParams.PAW_RX
import com.assistant.adi.ui.buddy.renderer.panda.PandaAnimParams.PAW_RY
import kotlin.math.atan2

/** One reused drawing packet. Coordinates belong to the body unless named as head-local. */
class PandaRigFrame {
    var bodyColor = PandaPalette.SNOW
    var previousBodyTop = PandaPalette.WHITE
    var currentBodyTop = PandaPalette.WHITE
    var bodyBlend = 1f
    var glow = 0f
    var headTilt = 0f
    var headX = 0f
    var headY = 0f
    var earDrop = 0f
    var earWag = 0f
    var earLeft = 0f
    var earRight = 0f
    var footSpread = 0f
    var patchDroop = 0f
    var eyeY = -24f
    var eyeStyle = EyeStyle.OPEN
    var previousEyeStyle = EyeStyle.OPEN
    var eyeOpen = 1f
    var blinkAmount = 1f
    var gazeX = 0f
    var gazeY = 0f
    var asym = 0f
    var cheekColor = PandaPalette.PINK
    var cheekAlpha = .3f
    var cheekPuff = 0f
    var mouthStyle = MouthStyle.SMILE
    var previousMouthStyle = MouthStyle.SMILE
    var expressionEye: EyeStyle? = null
    var expressionEyeAmount = 0f
    var expressionMouth: MouthStyle? = null
    var expressionMouthAmount = 0f
    var mouthOpen = 0f
    var chew = 0f
    var pawLeftX = -32f
    var pawLeftY = 58f
    var pawRightX = 32f
    var pawRightY = 58f
    var bambooOpacity = 0f
}

/** Broad cheek silhouette, small seated torso, connected arms and a shared head pivot. */
class PandaRig {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val light = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PandaPalette.RIM; style = Paint.Style.STROKE; strokeWidth = 1.3f
    }
    private val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PandaPalette.CHARCOAL }
    private val softDark = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = PandaPalette.earGradient(28f) }
    private val pad = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PandaPalette.PAD }
    private val arm = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PandaPalette.CHARCOAL; style = Paint.Style.STROKE; strokeWidth = 26f; strokeCap = Paint.Cap.ROUND
    }
    private val eyeWhite = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PandaPalette.WHITE }
    private val eyeDark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PandaPalette.EYE_DARK }
    private val eyeDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val eyeLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PandaPalette.WHITE; style = Paint.Style.STROKE; strokeWidth = 3.1f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val mouthLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PandaPalette.MOUTH_LINE; style = Paint.Style.STROKE; strokeWidth = 2.5f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val mouthFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PandaPalette.MOUTH_OPEN }
    private val tongue = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PandaPalette.TONGUE }
    private val cheek = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = PandaPalette.bellyGlow() }
    private val bamboo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PandaPalette.BAMBOO; style = Paint.Style.STROKE; strokeWidth = 6f; strokeCap = Paint.Cap.ROUND
    }
    private val bambooNode = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PandaPalette.BAMBOO_DARK; style = Paint.Style.STROKE; strokeWidth = 1.6f; strokeCap = Paint.Cap.ROUND
    }
    private val leaf = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PandaPalette.BAMBOO }
    private val accessoryPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PandaPalette.CHIME; textSize = 23f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
    }
    private val whiteLight = PandaPalette.bodyLight(PandaPalette.WHITE)
    private val roseLight = PandaPalette.bodyLight(PandaPalette.ROSE_LIGHT)
    private val slateLight = PandaPalette.bodyLight(PandaPalette.SLATE_LIGHT)
    private val yellowLight = PandaPalette.bodyLight(PandaPalette.YELLOW_LIGHT)
    private val bodyPath = Path().apply {
        moveTo(0f, 6f)
        cubicTo(42f, 4f, 59f, 41f, 57f, 71f)
        cubicTo(56f, 93f, 35f, 99f, 0f, 99f)
        cubicTo(-35f, 99f, -56f, 93f, -57f, 71f)
        cubicTo(-59f, 41f, -42f, 4f, 0f, 6f)
        close()
    }
    private val headPath = Path().apply {
        moveTo(0f, -85f)
        cubicTo(42f, -86f, 68f, -67f, 75f, -38f)
        cubicTo(79f, -22f, 88f, -9f, 79f, 10f)
        cubicTo(69f, 31f, 34f, 36f, 0f, 34f)
        cubicTo(-34f, 36f, -69f, 31f, -79f, 10f)
        cubicTo(-88f, -9f, -79f, -22f, -75f, -38f)
        cubicTo(-68f, -67f, -42f, -86f, 0f, -85f)
        close()
    }
    private val path = Path()
    private val eyeClip = Path()
    private val oval = RectF()

    fun draw(canvas: Canvas, f: PandaRigFrame) {
        fill.color = f.bodyColor
        canvas.drawCircle(56f, 75f, 12f, fill)
        canvas.drawCircle(56f, 75f, 12f, rim)
        drawFur(canvas, bodyPath, f)
        canvas.drawOval(-48f, 15f, 48f, 53f, dark)
        fill.color = f.bodyColor
        canvas.drawOval(-36f, 39f, 36f, 91f, fill)
        if (f.glow > .005f) {
            glow.alpha = (f.glow * 115f).toInt().coerceIn(0, 255)
            canvas.save(); canvas.clipPath(bodyPath)
            canvas.drawOval(-41f, 31f, 41f, 94f, glow); canvas.restore()
        }
        drawFeet(canvas, f)
        canvas.save()
        headTransform(canvas, f)
        drawEars(canvas, f)
        drawFur(canvas, headPath, f)
        drawPatches(canvas, f)
        drawCheeks(canvas, f)
        // Muzzle joins the nose and mouth into one readable region.
        fill.color = PandaPalette.WHITE; fill.alpha = 115
        canvas.drawOval(-21f, -6f, 21f, 24f + f.chew, fill); fill.alpha = 255
        drawFace(canvas, f)
        path.rewind(); path.moveTo(-6.5f, -4f)
        path.cubicTo(-4f, -7f, 4f, -7f, 6.5f, -4f)
        path.cubicTo(7f, 0f, 2f, 3f, 0f, 3.3f)
        path.cubicTo(-2f, 3f, -7f, 0f, -6.5f, -4f); path.close()
        canvas.drawPath(path, eyeDark)
        fill.color = PandaPalette.CHARCOAL_LIGHT
        canvas.drawOval(-3.5f, -4.5f, 1.5f, -2.5f, fill)
        canvas.drawLine(0f, 3f, 0f, 7f, mouthLine)
        canvas.restore()
        if (f.bambooOpacity > .005f) drawBamboo(canvas, f)
        drawArm(canvas, -1f, f.pawLeftX, f.pawLeftY)
        drawArm(canvas, 1f, f.pawRightX, f.pawRightY)
    }

    private fun headTransform(canvas: Canvas, f: PandaRigFrame) {
        canvas.translate(f.headX, f.headY)
        canvas.rotate(f.headTilt, 0f, HEAD_PIVOT_Y)
    }

    private fun drawFur(canvas: Canvas, shape: Path, f: PandaRigFrame) {
        fill.color = f.bodyColor; fill.alpha = 255
        canvas.drawPath(shape, fill)
        light.shader = lightFor(f.previousBodyTop)
        light.alpha = ((1f - f.bodyBlend) * 255f).toInt()
        canvas.drawPath(shape, light)
        light.shader = lightFor(f.currentBodyTop)
        light.alpha = (f.bodyBlend * 255f).toInt()
        canvas.drawPath(shape, light)
        canvas.drawPath(shape, rim)
    }

    private fun lightFor(color: Int) = when (color) {
        PandaPalette.ROSE_LIGHT -> roseLight
        PandaPalette.SLATE_LIGHT -> slateLight
        PandaPalette.YELLOW_LIGHT -> yellowLight
        else -> whiteLight
    }

    private fun drawEars(canvas: Canvas, f: PandaRigFrame) {
        for (i in 0..1) {
            val side = if (i == 0) -1f else 1f
            canvas.save()
            canvas.translate(side * EAR_CENTER_X, EAR_CENTER_Y)
            canvas.rotate(side * (f.earDrop + f.earWag) + if (i == 0) f.earLeft else f.earRight, 0f, 14f)
            canvas.drawCircle(0f, 0f, EAR_RADIUS, softDark)
            canvas.drawOval(-10f, -10f, 10f, 11f, pad)
            canvas.restore()
        }
    }

    private fun drawFeet(canvas: Canvas, f: PandaRigFrame) {
        for (i in 0..1) {
            val side = if (i == 0) -1f else 1f
            canvas.save(); canvas.translate(side * (39f + f.footSpread), 83f)
            canvas.rotate(side * -16f)
            canvas.drawOval(-21f, -19f, 21f, 17f, softDark)
            canvas.drawOval(-10f, -3f, 10f, 10f, pad)
            for (toe in -1..1) canvas.drawOval(toe * 7f - 2.5f, -10f, toe * 7f + 2.5f, -5f, pad)
            canvas.restore()
        }
    }

    private fun drawPatches(canvas: Canvas, f: PandaRigFrame) {
        for (i in 0..1) {
            val side = if (i == 0) -1f else 1f
            canvas.save(); canvas.translate(side * PATCH_X, f.eyeY + 2f * f.patchDroop)
            canvas.rotate(side * (PATCH_TILT_DEG + f.patchDroop * 9f))
            canvas.drawOval(-PATCH_RX, -PATCH_RY, PATCH_RX, PATCH_RY + f.patchDroop * 3f, softDark)
            canvas.restore()
        }
    }

    private fun drawCheeks(canvas: Canvas, f: PandaRigFrame) {
        cheek.color = f.cheekColor; cheek.alpha = (f.cheekAlpha * 255f).toInt()
        val puff = maxOf(0f, f.chew) * 2f + f.cheekPuff
        for (i in 0..1) {
            val x = if (i == 0) -CHEEK_X else CHEEK_X
            canvas.drawOval(x - CHEEK_RADIUS - puff, CHEEK_Y - 5f - f.cheekPuff * .3f,
                x + CHEEK_RADIUS + puff, CHEEK_Y + 6f + f.cheekPuff * .3f, cheek)
        }
    }

    private fun drawFace(canvas: Canvas, f: PandaRigFrame) {
        val eyeBase = 1f - f.expressionEyeAmount
        if (f.previousEyeStyle == f.eyeStyle) drawEyes(canvas, f, f.eyeStyle, eyeBase)
        else {
            drawEyes(canvas, f, f.previousEyeStyle, (1f - f.bodyBlend) * eyeBase)
            drawEyes(canvas, f, f.eyeStyle, f.bodyBlend * eyeBase)
        }
        f.expressionEye?.let { drawEyes(canvas, f, it, f.expressionEyeAmount) }
        val mouthBase = 1f - f.expressionMouthAmount
        if (f.previousMouthStyle == f.mouthStyle) drawMouth(canvas, f.mouthStyle, .18f, mouthBase, f.chew)
        else {
            drawMouth(canvas, f.previousMouthStyle, .18f, (1f - f.bodyBlend) * mouthBase, f.chew)
            drawMouth(canvas, f.mouthStyle, .18f, f.bodyBlend * mouthBase, f.chew)
        }
        f.expressionMouth?.let { drawMouth(canvas, it, f.mouthOpen, f.expressionMouthAmount, f.chew) }
    }

    private fun drawEyes(canvas: Canvas, f: PandaRigFrame, style: EyeStyle, opacity: Float) {
        if (opacity < .005f) return
        val alpha = (opacity * 255f).toInt().coerceIn(0, 255)
        eyeWhite.alpha = alpha; eyeDark.alpha = alpha; eyeDot.alpha = alpha; eyeLine.alpha = alpha
        for (i in 0..1) {
            val side = if (i == 0) -1f else 1f
            val x = side * PATCH_X
            val y = f.eyeY
            val reaction = if (style == f.expressionEye) f.expressionEyeAmount else 0f
            val aperture = f.eyeOpen + (1f - f.eyeOpen) * reaction
            val open = (aperture * f.blinkAmount * (if (i == 1) 1f - .35f * f.asym * (1f - reaction) else 1f)).coerceIn(0f, 1.15f)
            when (style) {
                EyeStyle.OPEN, EyeStyle.WIDE, EyeStyle.SIDE_GLANCE -> {
                    val wide = style == EyeStyle.WIDE
                    val rx = if (wide) 12.5f else 11.5f
                    val ry = (if (style == EyeStyle.SIDE_GLANCE) 8.5f else if (wide) 14f else 12.5f) * open
                    if (ry < 1.5f) canvas.drawLine(x - 9f, y, x + 9f, y, eyeLine)
                    else {
                        oval.set(x - rx, y - ry, x + rx, y + ry)
                        canvas.drawOval(oval, eyeWhite)
                        eyeClip.rewind(); eyeClip.addOval(oval, Path.Direction.CW)
                        canvas.save(); canvas.clipPath(eyeClip)
                        val px = x + if (style == EyeStyle.SIDE_GLANCE) -3.5f + f.gazeX * .3f else f.gazeX
                        val py = y + f.gazeY
                        canvas.drawCircle(px, py, if (wide) 8.5f else 8f, eyeDark)
                        canvas.drawCircle(px - 2.5f, py - 3f, 2.7f, eyeDot)
                        canvas.drawCircle(px + 3f, py + 3f, 1.1f, eyeDot)
                        canvas.restore()
                    }
                }
                EyeStyle.HAPPY_ARC -> {
                    path.rewind(); path.moveTo(x - 9f, y + 2f)
                    path.cubicTo(x - 5f, y - 7f, x + 5f, y - 7f, x + 9f, y + 2f)
                    canvas.drawPath(path, eyeLine)
                }
                EyeStyle.STRAINED -> {
                    path.rewind(); path.moveTo(x - side * 8f, y - 5f)
                    path.lineTo(x + side * 5f, y); path.lineTo(x - side * 8f, y + 5f)
                    canvas.drawPath(path, eyeLine)
                }
                EyeStyle.SPIRAL -> {
                    path.rewind(); oval.set(x - 9f, y - 9f, x + 9f, y + 9f); path.arcTo(oval, 90f, 290f)
                    oval.set(x - 5f, y - 5f, x + 5f, y + 5f); path.arcTo(oval, 20f, 280f)
                    canvas.drawPath(path, eyeLine)
                }
                EyeStyle.FLAT -> {
                    path.rewind(); path.moveTo(x - 9f, y)
                    path.quadTo(x, y + 5f, x + 9f, y)
                    canvas.drawPath(path, eyeLine)
                }
            }
        }
        eyeWhite.alpha = 255; eyeDark.alpha = 255; eyeDot.alpha = 255; eyeLine.alpha = 255
    }

    private fun drawMouth(canvas: Canvas, style: MouthStyle, amount: Float, opacity: Float, chew: Float) {
        if (opacity < .005f) return
        val alpha = (opacity * 255f).toInt().coerceIn(0, 255)
        mouthLine.alpha = alpha; mouthFill.alpha = alpha; tongue.alpha = alpha
        val y = MOUTH_Y + chew
        when (style) {
            MouthStyle.SMILE -> {
                path.rewind(); path.moveTo(-10f, y)
                path.cubicTo(-7f, y + 6f, -2f, y + 5f, 0f, y - 1f)
                path.cubicTo(2f, y + 5f, 7f, y + 6f, 10f, y)
                canvas.drawPath(path, mouthLine)
            }
            MouthStyle.OPEN, MouthStyle.PET, MouthStyle.TONGUE -> {
                val opening = if (style == MouthStyle.PET) .48f else if (style == MouthStyle.TONGUE) .5f else amount.coerceIn(0f, 1f)
                val w = if (style == MouthStyle.OPEN) 6f + opening * 4f else 10f
                val bottom = y + 5f + opening * 12f
                path.rewind(); path.moveTo(-w, y)
                path.quadTo(0f, y + 2f, w, y)
                path.cubicTo(w, bottom + 1f, -w, bottom + 1f, -w, y); path.close()
                canvas.drawPath(path, mouthFill)
                canvas.save(); canvas.clipPath(path)
                canvas.drawOval(-7f, bottom - 5f, 7f, bottom + 4f, tongue)
                canvas.restore()
                if (style == MouthStyle.TONGUE) canvas.drawOval(-4f, y + 6f, 5f, y + 19f, tongue)
            }
            MouthStyle.FLAT -> {
                path.rewind(); path.moveTo(-6f, y + 2f); path.quadTo(0f, y, 6f, y + 2f)
                canvas.drawPath(path, mouthLine)
            }
            MouthStyle.POUT -> {
                path.rewind(); path.moveTo(-8f, y + 1f); path.quadTo(0f, y + 8f, 8f, y + 1f)
                canvas.drawPath(path, mouthLine)
            }
            MouthStyle.STRAINED -> {
                path.rewind(); path.moveTo(-9f, y + 2f)
                path.cubicTo(-5f, y - 4f, -2f, y + 7f, 1f, y + 2f)
                path.quadTo(5f, y - 3f, 9f, y + 2f); canvas.drawPath(path, mouthLine)
            }
        }
        mouthLine.alpha = 255; mouthFill.alpha = 255; tongue.alpha = 255
    }

    private fun drawArm(canvas: Canvas, side: Float, x: Float, y: Float) {
        val shoulderX = side * 43f
        val elbowX = side * maxOf(49f, kotlin.math.abs(x) + 7f)
        path.rewind(); path.moveTo(shoulderX, 39f)
        path.quadTo(elbowX, (y + 48f) * .5f, x, y)
        canvas.drawPath(path, arm)
        canvas.save(); canvas.translate(x, y)
        val angle = atan2(y - 39f, x - shoulderX) * 180f / Math.PI.toFloat() - 90f
        canvas.rotate(angle.coerceIn(-70f, 70f))
        canvas.drawOval(-PAW_RX, -PAW_RY, PAW_RX, PAW_RY, softDark)
        canvas.restore()
    }

    private fun drawBamboo(canvas: Canvas, f: PandaRigFrame) {
        val alpha = (f.bambooOpacity * 255f).toInt().coerceIn(0, 255)
        bamboo.alpha = alpha; bambooNode.alpha = alpha; leaf.alpha = alpha
        val follow = (f.pawLeftY + f.pawRightY) * .5f - 57f
        canvas.save(); canvas.translate(0f, follow * .5f)
        canvas.drawLine(-30f, 73f, 34f, 39f, bamboo)
        for (i in 0..2) {
            val x = -20f + i * 20f; val y = 67.5f - i * 10.6f
            canvas.drawLine(x - 2f, y - 3f, x + 1f, y + 3f, bambooNode)
        }
        path.rewind(); path.moveTo(25f, 44f)
        path.quadTo(27f, 24f, 45f, 26f); path.quadTo(42f, 41f, 25f, 44f); path.close()
        canvas.drawPath(path, leaf)
        path.rewind(); path.moveTo(30f, 41f)
        path.quadTo(49f, 34f, 57f, 44f); path.quadTo(43f, 51f, 30f, 41f); path.close()
        canvas.drawPath(path, leaf)
        canvas.restore()
    }

    /** Status marks accompany the head, inside the same physical transform as the character. */
    fun drawAccessory(canvas: Canvas, accessory: Accessory, opacity: Float, f: PandaRigFrame) {
        if (accessory == Accessory.NONE || opacity < .005f) return
        val alpha = (opacity * 255f).toInt().coerceIn(0, 255)
        canvas.save(); headTransform(canvas, f)
        accessoryPaint.color = PandaPalette.CHIME; accessoryPaint.alpha = alpha
        when (accessory) {
            Accessory.QUESTION -> { text.alpha = alpha; canvas.drawText("?", 84f, -52f, text) }
            Accessory.SWEAT -> {
                canvas.translate(78f, -21f)
                path.rewind(); path.moveTo(0f, -7f)
                path.cubicTo(-2f, -1f, -7f, 3f, -5f, 8f)
                path.cubicTo(-2f, 15f, 7f, 12f, 6f, 5f); path.close()
                accessoryPaint.color = PandaPalette.SWEAT_BLUE; accessoryPaint.alpha = alpha
                canvas.drawPath(path, accessoryPaint)
            }
            Accessory.BOLT -> {
                canvas.translate(78f, -50f)
                path.rewind(); path.moveTo(3f, -11f); path.lineTo(-7f, 2f)
                path.lineTo(-1f, 2f); path.lineTo(-3f, 12f); path.lineTo(8f, -3f)
                path.lineTo(2f, -3f); path.close(); canvas.drawPath(path, accessoryPaint)
            }
            Accessory.NONE -> Unit
        }
        canvas.restore()
    }
}

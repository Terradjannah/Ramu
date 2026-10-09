package com.assistant.adi.ui.buddy

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import com.assistant.adi.R

/** A device in a pocket, without simulated readings or a second pet. */
class WelcomeDeviceView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = minOf(width / 280f, height / 240f)
        canvas.save()
        canvas.translate((width - 280 * scale) / 2, (height - 240 * scale) / 2)
        canvas.scale(scale, scale)
        paint.color = context.getColor(R.color.buddy_mint_fill)
        canvas.drawPath(Path().apply {
            moveTo(30f, 50f); quadTo(16f, 22f, 68f, 14f)
            lineTo(208f, 14f); quadTo(257f, 14f, 261f, 65f)
            lineTo(270f, 160f); quadTo(271f, 230f, 204f, 226f)
            lineTo(74f, 220f); quadTo(6f, 213f, 16f, 156f); close()
        }, paint)
        canvas.save(); canvas.rotate(-9f, 140f, 126f)
        paint.color = context.getColor(R.color.buddy_ink)
        canvas.drawRoundRect(79f, 11f, 201f, 219f, 25f, 25f, paint)
        paint.color = context.getColor(R.color.buddy_tile)
        canvas.drawRoundRect(85f, 18f, 195f, 211f, 20f, 20f, paint)
        paint.color = context.getColor(R.color.buddy_ink)
        canvas.drawRoundRect(123f, 24f, 157f, 29f, 3f, 3f, paint)
        paint.color = context.getColor(R.color.buddy_action)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 4f
        canvas.drawRoundRect(115f, 66f, 165f, 100f, 7f, 7f, paint)
        canvas.drawLine(171f, 77f, 171f, 88f, paint)
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(124f, 75f, 138f, 91f, 3f, 3f, paint)
        canvas.restore()
        paint.color = context.getColor(R.color.buddy_action)
        canvas.drawPath(Path().apply {
            moveTo(44f, 133f); lineTo(216f, 133f); lineTo(236f, 155f)
            lineTo(231f, 194f); quadTo(230f, 230f, 195f, 234f)
            lineTo(87f, 234f); quadTo(50f, 232f, 48f, 199f); close()
        }, paint)
        paint.color = context.getColor(R.color.buddy_mint_fill)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f
        canvas.drawRoundRect(67f, 145f, 213f, 218f, 25f, 25f, paint)
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(120f, 153f, 160f, 175f, 7f, 7f, paint)
        canvas.restore()
    }
}

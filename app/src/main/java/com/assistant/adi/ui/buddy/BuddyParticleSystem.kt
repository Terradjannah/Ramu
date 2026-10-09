package com.assistant.adi.ui.buddy

import android.graphics.Canvas
import android.graphics.Paint
import kotlin.math.max

/** Fixed-capacity host particle pool. Rendering and updates reuse the same storage. */
class BuddyParticleSystem(private val capacity: Int = DEFAULT_CAPACITY) {
    enum class Type { HEART, SPARKLE, WATER, SWEAT }

    private val active = BooleanArray(capacity)
    private val types = arrayOfNulls<Type>(capacity)
    private val x = FloatArray(capacity)
    private val y = FloatArray(capacity)
    private val velocityX = FloatArray(capacity)
    private val velocityY = FloatArray(capacity)
    private val size = FloatArray(capacity)
    private val remaining = FloatArray(capacity)
    private val duration = FloatArray(capacity)
    private val delay = FloatArray(capacity)
    private val gravity = FloatArray(capacity)
    private val animated = BooleanArray(capacity)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun emit(type: Type, startX: Float, startY: Float, dx: Float, dy: Float, particleSize: Float, lifetimeSeconds: Float,
        delaySeconds: Float = 0f, accelerationY: Float = 0f, animateScale: Boolean = false) {
        val index = nextSlot()
        active[index] = true
        types[index] = type
        x[index] = startX
        y[index] = startY
        velocityX[index] = dx
        velocityY[index] = dy
        size[index] = particleSize
        duration[index] = max(lifetimeSeconds, MIN_LIFETIME_SECONDS)
        remaining[index] = duration[index]
        delay[index] = delaySeconds.coerceAtLeast(0f)
        gravity[index] = accelerationY
        animated[index] = animateScale
    }

    fun advance(deltaSeconds: Float) {
        if (deltaSeconds <= 0f) return
        for (index in 0 until capacity) {
            if (!active[index]) continue
            val step = (deltaSeconds - delay[index]).coerceAtLeast(0f)
            delay[index] = (delay[index] - deltaSeconds).coerceAtLeast(0f)
            if (step <= 0f) continue
            remaining[index] -= step
            if (remaining[index] <= 0f) {
                active[index] = false
                continue
            }
            x[index] += velocityX[index] * step
            y[index] += velocityY[index] * step + .5f * gravity[index] * step * step
            velocityY[index] += gravity[index] * step
        }
    }

    fun draw(canvas: Canvas) {
        for (index in 0 until capacity) {
            if (!active[index] || delay[index] > 0f) continue
            canvas.save()
            if (animated[index]) {
                val age = 1f - remaining[index] / duration[index]
                val scale = (.4f + .6f * (age / .16f).coerceAtMost(1f)) * (1f - .25f * age)
                canvas.scale(scale, scale, x[index], y[index])
                canvas.rotate(velocityX[index] * .08f * age, x[index], y[index])
            }
            paint.color = when (types[index]) {
                Type.HEART -> HEART
                Type.SPARKLE -> SPARKLE
                Type.WATER -> WATER
                else -> SWEAT
            }
            paint.alpha = (255f * (remaining[index] / duration[index])).toInt().coerceIn(0, 255)
            when (types[index]) {
                Type.HEART -> {
                    canvas.drawCircle(x[index] - size[index] * .28f, y[index], size[index] * .46f, paint)
                    canvas.drawCircle(x[index] + size[index] * .28f, y[index], size[index] * .46f, paint)
                    canvas.save()
                    canvas.rotate(45f, x[index], y[index] + size[index] * .28f)
                    canvas.drawRect(x[index] - size[index] * .46f, y[index] - size[index] * .18f,
                        x[index] + size[index] * .46f, y[index] + size[index] * .74f, paint)
                    canvas.restore()
                }
                Type.SPARKLE -> {
                    paint.strokeWidth = size[index] * .22f
                    canvas.drawLine(x[index] - size[index], y[index], x[index] + size[index], y[index], paint)
                    canvas.drawLine(x[index], y[index] - size[index], x[index], y[index] + size[index], paint)
                }
                Type.WATER -> {
                    canvas.drawOval(x[index] - size[index] * .55f, y[index] - size[index],
                        x[index] + size[index] * .55f, y[index] + size[index], paint)
                }
                Type.SWEAT -> {
                    canvas.drawOval(x[index] - size[index] * .55f, y[index] - size[index],
                        x[index] + size[index] * .55f, y[index] + size[index], paint)
                }
                null -> Unit
            }
            canvas.restore()
        }
        paint.alpha = 255
    }

    fun clear() { active.fill(false) }

    private fun nextSlot(): Int {
        for (index in 0 until capacity) if (!active[index]) return index
        var oldest = 0
        for (index in 1 until capacity) if (remaining[index] < remaining[oldest]) oldest = index
        return oldest
    }

    private companion object {
        const val DEFAULT_CAPACITY = 24
        const val MIN_LIFETIME_SECONDS = .01f
        const val HEART = 0xFFF43F5E.toInt()
        const val SPARKLE = 0xFFFBBF24.toInt()
        const val WATER = 0xFF38BDF8.toInt()
        const val SWEAT = 0xFF70ADCF.toInt()
    }
}

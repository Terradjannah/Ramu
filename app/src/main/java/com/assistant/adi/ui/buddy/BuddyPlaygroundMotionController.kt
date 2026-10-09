package com.assistant.adi.ui.buddy

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

class BuddyPlaygroundMotionController {
    data class Point(val x: Float, val y: Float)

    data class Bounds(val width: Float, val height: Float) {
        val isValid: Boolean get() = width.isFinite() && height.isFinite() && width > 0f && height > 0f
    }

    data class Collision(val normalizedImpact: Float)

    data class State(
        val restCenter: Point,
        val center: Point,
        val velocity: Point,
        val pointerOffset: Point,
        val floorY: Float,
        val rotation: Float,
        val scaleX: Float,
        val scaleY: Float,
        val isDragging: Boolean,
        val isRunning: Boolean
    )

    data class FrameResult(val state: State, val collision: Collision?)

    private var bounds = Bounds(0f, 0f)
    private var radius = 0f
    private var restCenter = Point(0f, 0f)
    private var center = Point(0f, 0f)
    private var velocity = Point(0f, 0f)
    private var pointerOffset = Point(0f, 0f)
    private var lastPointerTimeNanos = 0L
    private var accumulatedSeconds = 0f
    private var rotation = 0f
    private var scaleX = 1f
    private var scaleY = 1f
    private var dragging = false
    private var running = false

    fun reset(newBounds: Bounds, newRadius: Float, floorLimitY: Float = Float.POSITIVE_INFINITY): State {
        bounds = newBounds
        radius = if (newRadius.isFinite() && newRadius > 0f && newBounds.isValid) newRadius else 0f
        restCenter = if (radius > 0f) {
            Point(bounds.width * REST_X_FRACTION, minOf(bounds.height * REST_Y_FRACTION, floorLimitY))
        } else Point(0f, 0f)
        center = restCenter
        velocity = Point(0f, 0f)
        pointerOffset = Point(0f, 0f)
        lastPointerTimeNanos = 0L
        accumulatedSeconds = 0f
        rotation = 0f
        scaleX = 1f
        scaleY = 1f
        dragging = false
        running = false
        return state()
    }

    fun beginDrag(pointer: Point, timeNanos: Long): State {
        if (!isReady()) return reset(bounds, radius)
        running = false
        dragging = true
        pointerOffset = Point(center.x - pointer.x, center.y - pointer.y)
        lastPointerTimeNanos = timeNanos
        velocity = Point(0f, 0f)
        return state()
    }

    fun dragTo(pointer: Point, timeNanos: Long): State {
        if (!dragging || !isReady()) return state()
        val previousCenter = center
        center = clampCenter(Point(pointer.x + pointerOffset.x, pointer.y + pointerOffset.y))
        val elapsedSeconds = secondsSinceLastPointer(timeNanos)
        if (elapsedSeconds > 0f) {
            velocity = Point(
                ((center.x - previousCenter.x) / elapsedSeconds).coerceIn(-maxHorizontalVelocity(), maxHorizontalVelocity()),
                ((center.y - previousCenter.y) / elapsedSeconds).coerceIn(-maxUpwardVelocity(), maxDownwardVelocity())
            )
        }
        lastPointerTimeNanos = timeNanos
        return state()
    }

    fun release(pointer: Point, timeNanos: Long): State {
        dragTo(pointer, timeNanos)
        if (!dragging) return state()
        dragging = false
        running = needsMotion()
        return state()
    }

    fun cancel(): State {
        if (!isReady()) return reset(bounds, radius)
        center = restCenter
        velocity = Point(0f, 0f)
        pointerOffset = Point(0f, 0f)
        accumulatedSeconds = 0f
        rotation = 0f
        scaleX = 1f
        scaleY = 1f
        dragging = false
        running = false
        return state()
    }

    fun advance(deltaSeconds: Float): FrameResult {
        if (!isReady() || !running || dragging) return FrameResult(state(), null)
        accumulatedSeconds += deltaSeconds.safeDelta()
        var strongestCollision: Collision? = null
        while (accumulatedSeconds + FIXED_STEP_EPSILON >= FIXED_STEP_SECONDS) {
            val collision = integrate(FIXED_STEP_SECONDS)
            if (collision != null && (strongestCollision == null || collision.normalizedImpact > strongestCollision.normalizedImpact)) {
                strongestCollision = collision
            }
            accumulatedSeconds -= FIXED_STEP_SECONDS
        }
        if (!needsMotion()) settle()
        return FrameResult(state(), strongestCollision)
    }

    private fun integrate(deltaSeconds: Float): Collision? {
        val springAcceleration = (restCenter.x - center.x) * HORIZONTAL_SPRING
        var velocityX = (velocity.x + springAcceleration * deltaSeconds) * HORIZONTAL_DAMPING_BASE.pow(deltaSeconds * 60f)
        var velocityY = velocity.y + gravity() * deltaSeconds
        velocityX = velocityX.coerceIn(-maxHorizontalVelocity(), maxHorizontalVelocity())
        velocityY = velocityY.coerceIn(-maxUpwardVelocity(), maxDownwardVelocity())

        var nextCenter = Point(center.x + velocityX * deltaSeconds, center.y + velocityY * deltaSeconds)
        val minX = -radius * OUTSIDE_FRACTION
        val maxX = bounds.width + radius * OUTSIDE_FRACTION
        if (nextCenter.x <= minX || nextCenter.x >= maxX) {
            nextCenter = Point(nextCenter.x.coerceIn(minX, maxX), nextCenter.y)
            velocityX *= -SIDE_RESTITUTION
        }

        var collision: Collision? = null
        if (nextCenter.y >= restCenter.y) {
            val impact = (velocityY / maxDownwardVelocity()).coerceIn(0f, 1f)
            nextCenter = Point(nextCenter.x, restCenter.y)
            if (velocityY > minimumBounceVelocity()) {
                velocityY = -velocityY * FLOOR_RESTITUTION
                scaleX = 1f + impact * MAX_SQUASH
                scaleY = 1f - impact * MAX_SQUASH
                collision = Collision(impact)
            } else {
                velocityY = 0f
            }
        }

        center = nextCenter
        velocity = Point(velocityX, velocityY)
        rotation = approach(rotation, (velocityX / maxHorizontalVelocity()) * MAX_ROTATION, deltaSeconds * ROTATION_RESPONSE)
        scaleX = approach(scaleX, 1f, deltaSeconds * SCALE_RECOVERY)
        scaleY = approach(scaleY, 1f, deltaSeconds * SCALE_RECOVERY)
        return collision
    }

    private fun needsMotion(): Boolean = abs(center.x - restCenter.x) > positionTolerance() || abs(center.y - restCenter.y) > positionTolerance() || abs(velocity.x) > velocityTolerance() || abs(velocity.y) > velocityTolerance()

    private fun settle() {
        center = restCenter
        velocity = Point(0f, 0f)
        rotation = 0f
        scaleX = 1f
        scaleY = 1f
        running = false
    }

    private fun clampCenter(point: Point): Point = Point(
        point.x.coerceIn(-radius * OUTSIDE_FRACTION, bounds.width + radius * OUTSIDE_FRACTION),
        point.y.coerceIn(-radius * OUTSIDE_FRACTION, restCenter.y)
    )

    private fun state() = State(
        restCenter = restCenter,
        center = center,
        velocity = velocity,
        pointerOffset = pointerOffset,
        floorY = restCenter.y,
        rotation = rotation,
        scaleX = scaleX,
        scaleY = scaleY,
        isDragging = dragging,
        isRunning = running
    )

    private fun isReady() = bounds.isValid && radius > 0f

    private fun secondsSinceLastPointer(timeNanos: Long): Float {
        if (timeNanos <= lastPointerTimeNanos) return 0f
        return ((timeNanos - lastPointerTimeNanos) / NANOS_PER_SECOND).coerceAtMost(MAX_POINTER_SAMPLE_SECONDS)
    }

    private fun gravity() = GRAVITY_PER_RADIUS * radius
    private fun maxHorizontalVelocity() = MAX_HORIZONTAL_VELOCITY_PER_RADIUS * radius
    private fun maxUpwardVelocity() = MAX_UPWARD_VELOCITY_PER_RADIUS * radius
    private fun maxDownwardVelocity() = MAX_DOWNWARD_VELOCITY_PER_RADIUS * radius
    private fun minimumBounceVelocity() = MINIMUM_BOUNCE_PER_RADIUS * radius
    private fun positionTolerance() = radius * POSITION_TOLERANCE_PER_RADIUS
    private fun velocityTolerance() = radius * VELOCITY_TOLERANCE_PER_RADIUS

    private fun Float.safeDelta(): Float = if (isFinite() && this > 0f) coerceAtMost(MAX_FRAME_DELTA_SECONDS) else 0f

    private fun approach(current: Float, target: Float, amount: Float): Float = current + (target - current) * amount.coerceIn(0f, 1f)

    private companion object {
        const val REST_X_FRACTION = 0.5f
        const val REST_Y_FRACTION = 0.68f
        const val OUTSIDE_FRACTION = 0.25f
        const val GRAVITY_PER_RADIUS = 8.9f
        const val HORIZONTAL_SPRING = 6.5f
        const val HORIZONTAL_DAMPING_BASE = 0.92f
        const val FLOOR_RESTITUTION = 0.42f
        const val SIDE_RESTITUTION = 0.18f
        const val MAX_HORIZONTAL_VELOCITY_PER_RADIUS = 9.5f
        const val MAX_UPWARD_VELOCITY_PER_RADIUS = 12.6f
        const val MAX_DOWNWARD_VELOCITY_PER_RADIUS = 4.2f
        const val MINIMUM_BOUNCE_PER_RADIUS = 0.25f
        const val MAX_FRAME_DELTA_SECONDS = 0.032f
        const val FIXED_STEP_SECONDS = 1f / 120f
        const val FIXED_STEP_EPSILON = 0.000001f
        const val MAX_POINTER_SAMPLE_SECONDS = 0.1f
        const val NANOS_PER_SECOND = 1_000_000_000f
        const val MAX_ROTATION = 0.18f
        const val ROTATION_RESPONSE = 8f
        const val MAX_SQUASH = 0.12f
        const val SCALE_RECOVERY = 9f
        const val POSITION_TOLERANCE_PER_RADIUS = 0.02f
        const val VELOCITY_TOLERANCE_PER_RADIUS = 0.04f
    }
}

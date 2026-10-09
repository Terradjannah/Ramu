package com.assistant.adi.ui.buddy

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import com.assistant.adi.data.PrefsManager
import com.assistant.adi.ui.buddy.renderer.CatRenderer
import com.assistant.adi.ui.buddy.renderer.DuckRenderer
import com.assistant.adi.ui.buddy.renderer.IBuddyRenderer
import com.assistant.adi.ui.buddy.renderer.PandaRenderer
import com.assistant.adi.ui.buddy.renderer.panda.PandaReactionEffects
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

/** Ambient leaves gestures to the host screen; playground lets the pet claim them. */
enum class ViewMode { AMBIENT, PLAYGROUND }

/**
 * Resolution-independent clay character host. Mood, motion and character
 * selection live here; the active [IBuddyRenderer] owns all drawing.
 */
class BuddyPetView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var mood = BuddyMood.HAPPY
        set(value) {
            if (field == value) return
            field = value
            if (overrideMood == null) applyMood(value)
        }

    /** Debug hook: while set, it wins over [mood] until cleared. */
    var overrideMood: BuddyMood? = null
        private set

    var debugOverridesEnabled = false
        private set

    var motionEnabled = true
        set(value) { if (field != value) { field = value; syncMotion() } }
    var screenActive = false
        set(value) { if (field != value) { field = value; syncMotion() } }

    /** Ambient (home) yields touch to the scroll; playground handles it directly. */
    var viewMode = ViewMode.AMBIENT

    /** Playground only: true while the user rubs the pet; drives the happy arc eyes and pet face. */
    var petting = false
        set(value) { if (field != value) { field = value; invalidate() } }

    /** Playground only: 0f..1f while an item is offered; opens the mouth, bill or tongue. */
    var mouthOpenFood = 0f
        set(value) { if (field != value) { field = value.coerceIn(0f, 1f); invalidate() } }

    var renderCenterX = Float.NaN
        set(value) { if (field != value) { field = value; invalidate() } }
    var renderCenterY = Float.NaN
        set(value) { if (field != value) { field = value; invalidate() } }
    var renderRadius = Float.NaN
        set(value) { if (field != value) { field = value; invalidate() } }
    var groundY = Float.NaN
        set(value) { if (field != value) { field = value; invalidate() } }
    var renderRotationDegrees = 0f
        set(value) { if (field != value) { field = value; invalidate() } }
    var renderScaleX = 1f
        set(value) { if (field != value) { field = value; invalidate() } }
    var renderScaleY = 1f
        set(value) { if (field != value) { field = value; invalidate() } }
    var gazeTargetX = Float.NaN
        set(value) { if (field != value) { field = value; invalidate() } }
    var gazeTargetY = Float.NaN
        set(value) { if (field != value) { field = value; invalidate() } }

    private val prefs by lazy { PrefsManager(context.applicationContext) }
    private val renderers: Map<String, IBuddyRenderer> =
        listOf(CatRenderer(), PandaRenderer(), DuckRenderer()).associateBy { it.characterId }

    /** Active renderer id for this view; assigning it never changes the saved profile. */
    var characterType: String = prefs.getBuddyCharacter()
        set(value) {
            val resolved = BuddyCharacter.fromId(value).rendererId
            if (field == resolved) return
            resetPandaMotion()
            field = resolved
            invalidate()
        }

    private var displayedMood = BuddyMood.HAPPY
    private var previousMood = BuddyMood.HAPPY
    private var transition = 1f
    private var moodStartedNanos = 0L
    private var elapsedSeconds = 0f
    private var lastFrameNanos = 0L
    private var frameCallbackPosted = false

    private var activeExpression = BuddyExpression.NONE
    private var expressionStartedNanos = 0L
    private var expressionDurationNanos = 0L
    private var expressionExpiresNanos = 0L
    private var expressionProgress = 1f
    private val expireExpression: Runnable = Runnable { expireExpressionIfDue() }

    private fun expireExpressionIfDue() {
        if (activeExpression != BuddyExpression.NONE &&
            System.nanoTime() >= expressionExpiresNanos
        ) {
            activeExpression = BuddyExpression.NONE
            expressionProgress = 1f
            invalidate()
        } else if (activeExpression != BuddyExpression.NONE) {
            postDelayed(expireExpression, remainingExpressionMillis())
        }
    }

    /** Debug-only expression override; it does not alter the device-derived mood. */
    var overrideExpression: BuddyExpression? = null
        private set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** Debug-only night override; null follows the scheduled [setNightMode] value. */
    var overrideNight: Boolean? = null
        private set(value) {
            if (field == value) return
            field = value
            applyNight(value ?: scheduledNight)
        }

    private var blinkStartedAtSeconds = -1f
    private var nextBlinkAtSeconds = 0f
    private var fidgetStartedAtSeconds = -1f
    private var nextFidgetAtSeconds = 0f
    private var fidgetTwitch = false

    private val particles = BuddyParticleSystem()
    private val pandaEffects = PandaReactionEffects()
    private val pandaRenderer get() = renderers.getValue("panda") as PandaRenderer

    fun setPandaContact(pressed: Boolean) {
        if (characterType == "panda") pandaRenderer.interaction.pressed = pressed
    }

    fun observePandaMotion(velocityX: Float, velocityY: Float, dragging: Boolean) {
        if (characterType != "panda") return
        pandaRenderer.interaction.apply {
            this.velocityX = if (velocityX.isFinite()) velocityX.coerceIn(-12f, 12f) else 0f
            this.velocityY = if (velocityY.isFinite()) velocityY.coerceIn(-14f, 14f) else 0f
            this.dragging = dragging
            sampleAge = 0f
        }
    }

    fun observePandaImpact(impact: Float) {
        if (characterType == "panda" && canAnimate()) {
            pandaRenderer.interaction.impact = maxOf(pandaRenderer.interaction.impact, impact.coerceIn(0f, 1f))
        }
    }

    fun emitPandaTreat(type: BuddyParticleSystem.Type): Boolean {
        if (characterType != "panda") return false
        if (canAnimate()) pandaEffects.treat(type, pandaCenterX(), pandaCenterY(), pandaRadius())
        return true
    }

    private fun pandaCenterX() = if (renderCenterX.isNaN()) width / 2f else renderCenterX
    private fun pandaCenterY() = if (renderCenterY.isNaN()) height / 2f else renderCenterY
    private fun pandaRadius() = if (renderRadius.isNaN()) minOf(width * .52f, height * .41f) else renderRadius
    private fun resetPandaMotion() {
        pandaRenderer.resetMotion(); pandaEffects.clear()
        if (characterType == "panda") petting = false
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(0f, 0f, 1f, intArrayOf(SHADOW, SHADOW and 0x00FFFFFF), null, Shader.TileMode.CLAMP)
    }

    private var nightEyelid = 1f
    private var isNightMode = false
    private var scheduledNight = false

    private val random = Random()
    private val frameCallback = Choreographer.FrameCallback { frameTimeNanos ->
        frameCallbackPosted = false
        if (!canAnimate()) {
            settleCurrentFrame()
            return@FrameCallback
        }
        val deltaSeconds = if (lastFrameNanos == 0L) 0f else
            ((frameTimeNanos - lastFrameNanos).coerceAtMost(MAX_FRAME_DELTA_NANOS) / NANOS_PER_SECOND)
        lastFrameNanos = frameTimeNanos
        elapsedSeconds += deltaSeconds
        updateTimedState(frameTimeNanos, deltaSeconds)
        invalidate()
        postFrameCallback()
    }

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    private fun canAnimate() = isAttachedToWindow && isShown && windowVisibility == VISIBLE &&
        screenActive && motionEnabled && ValueAnimator.areAnimatorsEnabled()

    private fun applyMood(next: BuddyMood, immediate: Boolean = false) {
        if (next == displayedMood && !immediate) return
        if (immediate) {
            previousMood = next
            displayedMood = next
            transition = 1f
            moodStartedNanos = System.nanoTime()
            resetFidget()
            resetPandaMotion()
            syncMotion()
            invalidate()
            return
        }
        previousMood = displayedMood
        displayedMood = next
        transition = 0f
        moodStartedNanos = System.nanoTime()
        resetFidget()
        syncMotion()
    }

    fun showExpression(expression: BuddyExpression, durationMillis: Long = expression.defaultDurationMillis) {
        removeCallbacks(expireExpression)
        activeExpression = expression
        expressionProgress = 0f
        expressionStartedNanos = System.nanoTime()
        expressionDurationNanos = durationMillis.coerceAtLeast(0L) * NANOS_PER_MILLISECOND
        expressionExpiresNanos = expressionStartedNanos + expressionDurationNanos
        if (expression == BuddyExpression.NONE || expressionDurationNanos == 0L) expressionProgress = 1f
        else postDelayed(expireExpression, durationMillis.coerceAtLeast(0L))
        if (characterType == "panda" && canAnimate()) pandaEffects.expression(expression, pandaCenterX(), pandaCenterY(), pandaRadius())
        syncMotion()
        invalidate()
    }

    /** Keeps an active expression visible for another full phrase without restarting it. */
    fun extendExpression(expression: BuddyExpression, durationMillis: Long = expression.defaultDurationMillis) {
        if (activeExpression != expression || expression == BuddyExpression.NONE) {
            showExpression(expression, durationMillis)
            return
        }
        removeCallbacks(expireExpression)
        expressionExpiresNanos = System.nanoTime() + durationMillis.coerceAtLeast(0L) * NANOS_PER_MILLISECOND
        postDelayed(expireExpression, durationMillis.coerceAtLeast(0L))
        syncMotion()
        invalidate()
    }

    fun isExpressionActive(expression: BuddyExpression): Boolean = activeExpression == expression

    fun emitParticle(type: BuddyParticleSystem.Type, x: Float, y: Float, velocityX: Float, velocityY: Float, size: Float, lifetimeSeconds: Float) {
        particles.emit(type, x, y, velocityX, velocityY, size, lifetimeSeconds)
        if (canAnimate()) postFrameCallback()
        invalidate()
    }

    private fun settleCurrentFrame() {
        transition = 1f
        expressionProgress = if (activeExpression == BuddyExpression.NONE) 1f else expressionProgress
        lastFrameNanos = 0L
        resetPandaMotion()
        invalidate()
    }

    private fun syncMotion() {
        if (canAnimate()) {
            postFrameCallback()
        } else {
            removeFrameCallback()
            settleCurrentFrame()
        }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); syncMotion() }
    override fun onDetachedFromWindow() { removeFrameCallback(); removeCallbacks(expireExpression); resetPandaMotion(); super.onDetachedFromWindow() }
    override fun onVisibilityAggregated(isVisible: Boolean) { super.onVisibilityAggregated(isVisible); syncMotion() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); syncMotion() }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (viewMode == ViewMode.AMBIENT && characterType == "panda") {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> setPandaContact(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> setPandaContact(false)
            }
        }
        val handled = super.dispatchTouchEvent(event)
        if (!handled && viewMode == ViewMode.AMBIENT) setPandaContact(false)
        return handled
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Ambient lets Home scroll intercept after touch slop, but a clickable or
        // long-clickable avatar still claims its own tap and debug gestures.
        if (viewMode == ViewMode.AMBIENT && !isClickable && !isLongClickable) return false
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    fun clearTransientExpression() {
        removeCallbacks(expireExpression)
        activeExpression = BuddyExpression.NONE
        expressionExpiresNanos = 0L
        expressionProgress = 1f
        invalidate()
    }

    fun setDebugOverridesEnabled(enabled: Boolean) {
        if (debugOverridesEnabled == enabled) return
        debugOverridesEnabled = enabled
        if (!enabled) {
            overrideMood = null
            overrideExpression = null
            overrideNight = null
            clearTransientExpression()
            characterType = prefs.getBuddyCharacter()
            applyMood(mood, immediate = true)
        } else {
            invalidate()
        }
    }

    fun setDebugMoodOverride(value: BuddyMood?) {
        if (!debugOverridesEnabled) return
        overrideExpression = null
        clearTransientExpression()
        overrideMood = value
        applyMood(value ?: mood, immediate = true)
    }

    fun setDebugExpressionOverride(value: BuddyExpression?) {
        if (!debugOverridesEnabled) return
        overrideExpression = null
        clearTransientExpression()
        if (value != null) showExpression(value, value.defaultDurationMillis)
    }

    fun setDebugNightOverride(value: Boolean?) {
        if (!debugOverridesEnabled) return
        overrideNight = value
    }

    fun setNightMode(isNight: Boolean) {
        if (scheduledNight == isNight) return
        scheduledNight = isNight
        if (overrideNight == null) applyNight(isNight)
    }

    private fun applyNight(isNight: Boolean) {
        if (isNightMode == isNight) return
        isNightMode = isNight
        nightEyelid = if (isNight) NIGHT_EYELID else 1f
        blinkStartedAtSeconds = -1f
        nextBlinkAtSeconds = 0f
        if (isNight) showExpression(BuddyExpression.SLEEPY_YAWN)
        invalidate()
    }

    private fun fidgetPulse(seconds: Float): Float {
        val p = (seconds / FIDGET_DURATION_SEC).coerceIn(0f, 1f)
        return sin((p * PI).toFloat())
    }

    private fun updateTimedState(frameTimeNanos: Long, deltaSeconds: Float) {
        val moodElapsed = ((frameTimeNanos - moodStartedNanos).coerceAtLeast(0L) / NANOS_PER_MILLISECOND).toFloat()
        val moodProgress = (moodElapsed / MOOD_TRANSITION_MILLIS).coerceIn(0f, 1f)
        transition = moodProgress * moodProgress * (3f - 2f * moodProgress)

        if (overrideExpression == null && activeExpression != BuddyExpression.NONE) {
            val elapsed = (frameTimeNanos - expressionStartedNanos).coerceAtLeast(0L)
            expressionProgress = (elapsed.toFloat() / expressionDurationNanos).coerceIn(0f, 1f)
            if (frameTimeNanos >= expressionExpiresNanos) {
                activeExpression = BuddyExpression.NONE
                removeCallbacks(expireExpression)
            }
        }

        if (nextBlinkAtSeconds == 0f) {
            nextBlinkAtSeconds = elapsedSeconds + blinkDelaySeconds()
        } else if (elapsedSeconds >= nextBlinkAtSeconds) {
            blinkStartedAtSeconds = elapsedSeconds
            nextBlinkAtSeconds = elapsedSeconds + blinkDelaySeconds()
        }
        if (blinkStartedAtSeconds >= 0f && elapsedSeconds - blinkStartedAtSeconds >= BLINK_DURATION_SECONDS) {
            blinkStartedAtSeconds = -1f
        }

        if (displayedMood == BuddyMood.HAPPY && characterType != "panda") {
            if (nextFidgetAtSeconds == 0f) nextFidgetAtSeconds = elapsedSeconds + fidgetDelaySeconds()
            if (fidgetStartedAtSeconds < 0f && elapsedSeconds >= nextFidgetAtSeconds) {
                fidgetStartedAtSeconds = elapsedSeconds
                fidgetTwitch = random.nextBoolean()
            } else if (fidgetStartedAtSeconds >= 0f && elapsedSeconds - fidgetStartedAtSeconds >= FIDGET_DURATION_SEC) {
                fidgetStartedAtSeconds = -1f
                nextFidgetAtSeconds = elapsedSeconds + fidgetDelaySeconds()
            }
        } else {
            resetFidget()
        }
        particles.advance(deltaSeconds)
        if (characterType == "panda") {
            pandaEffects.advance(deltaSeconds)
            if (petting && mouthOpenFood <= .01f) pandaEffects.pet(pandaCenterX(), pandaCenterY(), pandaRadius())
        }
    }

    private fun remainingExpressionMillis(): Long =
        ((expressionExpiresNanos - System.nanoTime()).coerceAtLeast(0L) + NANOS_PER_MILLISECOND - 1L) / NANOS_PER_MILLISECOND

    private fun blinkAmount(): Float {
        if (blinkStartedAtSeconds < 0f) return 1f
        val progress = ((elapsedSeconds - blinkStartedAtSeconds) / BLINK_DURATION_SECONDS).coerceIn(0f, 1f)
        return kotlin.math.abs(progress * 2f - 1f)
    }

    private fun postFrameCallback() {
        if (frameCallbackPosted) return
        frameCallbackPosted = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun removeFrameCallback() {
        if (!frameCallbackPosted) return
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        frameCallbackPosted = false
        lastFrameNanos = 0L
    }

    private fun resetFidget() {
        fidgetStartedAtSeconds = -1f
        nextFidgetAtSeconds = 0f
    }

    private fun blinkDelaySeconds(): Float {
        val min = if (nightEyelid < 1f) NIGHT_BLINK_MIN_SECONDS else BLINK_MIN_SECONDS
        val max = if (nightEyelid < 1f) NIGHT_BLINK_MAX_SECONDS else BLINK_MAX_SECONDS
        return min + random.nextFloat() * (max - min)
    }
    private fun fidgetDelaySeconds() = FIDGET_MIN_SECONDS + random.nextFloat() * (FIDGET_MAX_SECONDS - FIDGET_MIN_SECONDS)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!ValueAnimator.areAnimatorsEnabled()) syncMotion()
        val renderer = renderers[characterType] ?: renderers.getValue(DEFAULT_CHARACTER)
        val currentMood = if (renderer.characterId != "panda" && displayedMood == BuddyMood.POUT) BuddyMood.CONFUSED else displayedMood
        val oldMood = if (renderer.characterId != "panda" && previousMood == BuddyMood.POUT) BuddyMood.CONFUSED else previousMood
        val radius = if (renderRadius.isNaN()) minOf(width * .52f, height * .41f) else renderRadius
        if (radius <= 0f) return
        val centerX = if (renderCenterX.isNaN()) width / 2f else renderCenterX
        val centerY = if (renderCenterY.isNaN()) height / 2f else renderCenterY
        val resolvedGroundY = if (groundY.isNaN()) centerY + radius * DEFAULT_GROUND_OFFSET else groundY
        val heightAboveGround = (resolvedGroundY - centerY).coerceAtLeast(0f)
        val shadowScale = (1f - heightAboveGround / (radius * SHADOW_HEIGHT_RANGE)).coerceIn(MIN_SHADOW_SCALE, 1f)
        shadowPaint.alpha = (MAX_SHADOW_ALPHA * shadowScale).toInt()
        canvas.save()
        canvas.translate(centerX, resolvedGroundY)
        canvas.scale(radius * SHADOW_WIDTH * shadowScale, radius * SHADOW_HEIGHT * shadowScale)
        canvas.drawCircle(0f, 0f, 1f, shadowPaint)
        canvas.restore()
        particles.draw(canvas)
        if (characterType == "panda" && motionEnabled && ValueAnimator.areAnimatorsEnabled()) pandaEffects.draw(canvas)
        canvas.save()
        canvas.translate(centerX, centerY)
        canvas.rotate(renderRotationDegrees)
        canvas.scale(renderScaleX, renderScaleY)
        if (motionEnabled && ValueAnimator.areAnimatorsEnabled()) {
            val fidgetActive = fidgetStartedAtSeconds >= 0f
            val twitch = fidgetActive && fidgetTwitch
            val fidgetT = if (fidgetActive) elapsedSeconds - fidgetStartedAtSeconds else 0f
            val dip = if (fidgetActive && !fidgetTwitch) fidgetPulse(fidgetT) else 0f
            val blink = (blinkAmount() * nightEyelid * (1f - dip)).coerceIn(0f, 1f)
            // Bao choreographs secondary motion internally; warping its clock also warps its springs.
            val animTime = if (twitch && characterType != "panda") elapsedSeconds + fidgetT * FIDGET_TWITCH_SPEED else elapsedSeconds
            val gazeX = if (gazeTargetX.isNaN()) 0f else ((gazeTargetX - centerX) / radius * NOMINAL_RADIUS).coerceIn(-MAX_GAZE, MAX_GAZE)
            val gazeY = if (gazeTargetY.isNaN()) 0f else ((gazeTargetY - centerY) / radius * NOMINAL_RADIUS).coerceIn(-MAX_GAZE, MAX_GAZE)
            val expression = overrideExpression ?: activeExpression
            val progress = if (overrideExpression == null) expressionProgress else .5f
            renderer.draw(canvas, radius, currentMood, transition, oldMood, animTime,
                blink < .98f, blink, gazeX, gazeY, petting || twitch, mouthOpenFood, expression, progress)
        } else {
            renderer.drawStillFrame(canvas, radius, currentMood, overrideExpression ?: activeExpression)
        }
        canvas.restore()
    }

    private companion object {
        const val DEFAULT_CHARACTER = "cat"
        const val FIDGET_DURATION_SEC = .3f
        const val FIDGET_TWITCH_SPEED = 16f
        const val FIDGET_MIN_SECONDS = 8f
        const val FIDGET_MAX_SECONDS = 15f
        const val BLINK_MIN_SECONDS = 2.5f
        const val BLINK_MAX_SECONDS = 4f
        const val NIGHT_BLINK_MIN_SECONDS = 4.5f
        const val NIGHT_BLINK_MAX_SECONDS = 7f
        const val BLINK_DURATION_SECONDS = .16f
        const val MOOD_TRANSITION_MILLIS = 480f
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val NANOS_PER_SECOND = 1_000_000_000f
        const val MAX_FRAME_DELTA_NANOS = 100_000_000L
        const val NOMINAL_RADIUS = 100f
        const val MAX_GAZE = 10f
        const val DEFAULT_GROUND_OFFSET = .78f
        const val SHADOW_HEIGHT_RANGE = 1.5f
        const val MIN_SHADOW_SCALE = .25f
        const val SHADOW_WIDTH = 1.05f
        const val SHADOW_HEIGHT = .28f
        const val MAX_SHADOW_ALPHA = 82f
        const val SHADOW = 0x550F172A
        const val NIGHT_EYELID = .6f
    }
}

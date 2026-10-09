package com.assistant.adi.ui.buddy

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.SystemClock
import android.text.SpannableString
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.view.DragEvent
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.View.DragShadowBuilder
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import com.assistant.adi.R
import com.assistant.adi.data.PrefsManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/** Interactive stage where the pet reacts to direct touch without changing device data. */
class BuddyPlaygroundFragment : Fragment() {
    private var pet: BuddyPetView? = null
    private var mouthAnimator: ValueAnimator? = null
    private var pendingTreatFollowUp: Runnable? = null
    private var audio: BuddyAudioEngine? = null
    private var dragTreat: Treat? = null
    private var treatTouchDownX = 0f
    private var treatTouchDownY = 0f
    private var treatDragStarted = false
    private val motion = BuddyPlaygroundMotionController()
    private var motionState: BuddyPlaygroundMotionController.State? = null
    private var stageRadius = 0f
    private var gestureStart = BuddyPlaygroundMotionController.Point(0f, 0f)
    private var gestureActive = false
    private var gestureDragged = false
    private var lastMotionFrameNanos = 0L
    private var motionFramePosted = false
    private var lastMotionLayout: MotionLayout? = null

    // Petting: count quick left-right turnarounds inside a short window.
    private var lastPetX = -1f
    private var lastTurnX = 0f
    private var lastDir = 0
    private var reversals = 0
    private var windowStart = 0L

    private val density get() = resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()
    private val clearPet = Runnable { pet?.petting = false }

    private val motionTick = object : Runnable {
        override fun run() {
            val pet = pet ?: return
            if (!motionFramePosted) return
            val now = System.nanoTime()
            val deltaSeconds = ((now - lastMotionFrameNanos) / 1_000_000_000.0).toFloat()
            lastMotionFrameNanos = now
            val frame = motion.advance(deltaSeconds)
            applyMotion(frame.state)
            frame.collision?.let {
                audio?.playBounce(it.normalizedImpact)
                pet.observePandaImpact(it.normalizedImpact)
            }
            if (frame.state.isRunning) pet.postOnAnimation(this) else motionFramePosted = false
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        return FrameLayout(requireContext())
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        val root = view as FrameLayout
        audio = BuddyAudioEngine(requireContext(), alwaysOn = true)

        val stage = FrameLayout(requireContext())
        root.addView(stage, FrameLayout.LayoutParams(-1, -1))
        stage.addView(ImageView(requireContext()).apply {
            setImageResource(R.drawable.buddy_playground_background)
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(-1, -1))
        val avatar = BuddyPetView(requireContext()).apply {
            viewMode = ViewMode.PLAYGROUND
            characterType = PrefsManager(requireContext()).getBuddyCharacter()
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setOnTouchListener { _, event -> handlePetTouch(event) }
        }
        pet = avatar
        stage.addView(avatar, FrameLayout.LayoutParams(-1, -1))
        stage.setOnDragListener { _, event -> handleTreatDrag(event) }

        val back = compactControl("Kembali", "Kembali dari taman bermain", R.drawable.ic_ms_arrow_back) {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        root.addView(back, FrameLayout.LayoutParams(-2, dp(48), Gravity.TOP or Gravity.START))
        val info = compactControl("", "Lisensi ikon makanan", R.drawable.ic_ms_info) { showIconLicense() }
        root.addView(info, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END))

        val dock = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        val columns = if (canFitTreatColumns()) TREAT_COLUMNS else TREAT_REFLOW_COLUMNS
        TREATS.chunked(columns).forEach { rowTreats ->
            val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
            rowTreats.forEachIndexed { index, treat -> dockItem(row, treat, index) }
            dock.addView(row, LinearLayout.LayoutParams(-1, dp(DOCK_ROW_HEIGHT_DP)).apply { if (dock.childCount > 0) topMargin = dp(8) })
        }
        root.addView(dock, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val safeBars = insets.getInsetsIgnoringVisibility(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            (back.layoutParams as FrameLayout.LayoutParams).apply {
                leftMargin = safeBars.left + dp(SAFE_EDGE_DP)
                topMargin = safeBars.top + dp(SAFE_EDGE_DP)
                back.layoutParams = this
            }
            (info.layoutParams as FrameLayout.LayoutParams).apply {
                rightMargin = safeBars.right + dp(SAFE_EDGE_DP)
                topMargin = safeBars.top + dp(SAFE_EDGE_DP)
                info.layoutParams = this
            }
            (dock.layoutParams as FrameLayout.LayoutParams).apply {
                leftMargin = safeBars.left
                rightMargin = safeBars.right
                bottomMargin = safeBars.bottom + dp(DOCK_EDGE_DP)
                dock.layoutParams = this
            }
            insets
        }
        stage.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> resetMotionForStage(stage, dock) }
        dock.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> resetMotionForStage(stage, dock) }
        root.post { resetMotionForStage(stage, dock) }
        ViewCompat.requestApplyInsets(root)
    }

    private fun compactControl(label: String, description: String, iconRes: Int, action: () -> Unit) = MaterialButton(requireContext()).apply {
        text = label
        contentDescription = description
        isAllCaps = false
        textSize = 13f
        minWidth = dp(48)
        minHeight = dp(48)
        insetTop = 0
        insetBottom = 0
        cornerRadius = dp(18)
        setTextColor(requireContext().getColor(R.color.buddy_ink))
        backgroundTintList = ColorStateList.valueOf(requireContext().getColor(R.color.buddy_tile))
        setIconResource(iconRes)
        iconSize = dp(20)
        iconPadding = dp(8)
        iconTint = ColorStateList.valueOf(requireContext().getColor(R.color.buddy_ink))
        strokeWidth = dp(1)
        strokeColor = ColorStateList.valueOf(requireContext().getColor(R.color.buddy_outline))
        setOnClickListener { action() }
    }

    private fun dockItem(row: LinearLayout, treat: Treat, index: Int) {
        val item = MaterialButton(requireContext()).apply {
            text = treat.label
            contentDescription = "Beri ${treat.label}"
            isAllCaps = false
            textSize = 12f
            minWidth = dp(48)
            minHeight = dp(DOCK_ROW_HEIGHT_DP)
            insetTop = 0
            insetBottom = 0
            cornerRadius = dp(18)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_TOP
            iconSize = dp(28)
            iconPadding = dp(2)
            setIconResource(treat.iconRes)
            iconTint = ColorStateList.valueOf(requireContext().getColor(R.color.buddy_ink))
            setTextColor(requireContext().getColor(R.color.buddy_ink))
            backgroundTintList = ColorStateList.valueOf(requireContext().getColor(treat.tintRes))
            setOnClickListener { reactToTreat(treat, Float.NaN, Float.NaN) }
        }
        installTreatTouch(item, treat) { null }
        row.addView(item, LinearLayout.LayoutParams(0, -1, 1f).apply { if (index > 0) marginStart = dp(8) })
    }

    /**
     * Drag is the primary path; a plain tap is kept as the accessibility-equivalent
     * action so TalkBack and switch access can feed without a pointer gesture.
     */
    private fun installTreatTouch(item: View, treat: Treat, icon: () -> ImageView?) {
        val slop = ViewConfiguration.get(requireContext()).scaledTouchSlop
        item.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    treatTouchDownX = event.rawX
                    treatTouchDownY = event.rawY
                    treatDragStarted = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!treatDragStarted &&
                        hypot(event.rawX - treatTouchDownX, event.rawY - treatTouchDownY) >= slop) {
                        treatDragStarted = startTreatDrag(view, treat, icon())
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!treatDragStarted) view.performClick()
                    endTreatGesture()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    endTreatGesture()
                    true
                }
                else -> false
            }
        }
    }

    private fun startTreatDrag(view: View, treat: Treat, icon: ImageView?): Boolean {
        dragTreat = treat
        val shadow = DragShadowBuilder(icon ?: view)
        val data = ClipData.newPlainText(TREAT_DRAG_LABEL, treat.label)
        val started = runCatching { view.startDragAndDrop(data, shadow, null, 0) }.getOrDefault(false)
        if (!started) dragTreat = null
        return started
    }

    private fun endTreatGesture() {
        treatDragStarted = false
    }

    /** Coordinates arrive local to the stage, which is also the pet's coordinate space. */
    private fun handleTreatDrag(event: DragEvent): Boolean {
        val treat = dragTreat ?: return false
        return when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> true
            DragEvent.ACTION_DRAG_LOCATION -> {
                hoverTreat(event.x, event.y)
                true
            }
            DragEvent.ACTION_DRAG_EXITED -> {
                clearTreatHover()
                true
            }
            DragEvent.ACTION_DROP -> {
                val within = isWithinPetMouth(event.x, event.y)
                if (within) reactToTreat(treat, event.x, event.y)
                clearTreatHover()
                within
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                clearTreatHover()
                dragTreat = null
                true
            }
            else -> false
        }
    }

    private fun hoverTreat(x: Float, y: Float) {
        val pet = pet ?: return
        pet.gazeTargetX = x
        pet.gazeTargetY = y
        pet.mouthOpenFood = if (isWithinMouthProximity(x, y)) 1f else 0f
    }

    private fun clearTreatHover() {
        val pet = pet ?: return
        if (mouthAnimator?.isRunning != true) pet.mouthOpenFood = 0f
        pet.gazeTargetX = Float.NaN
        pet.gazeTargetY = Float.NaN
    }

    private fun isWithinMouthProximity(x: Float, y: Float): Boolean {
        val center = motionState?.center ?: return false
        return hypot(x - center.x, y - center.y) <= stageRadius * MOUTH_PROXIMITY_MULTIPLIER
    }

    private fun isWithinPetMouth(x: Float, y: Float): Boolean {
        val center = motionState?.center ?: return false
        return hypot(x - center.x, y - center.y) <= stageRadius * DROP_RADIUS_MULTIPLIER
    }

    /** Runs the dock table's expression, sound, chew and particle sequence for one item. */
    private fun reactToTreat(treat: Treat, x: Float, y: Float) {
        val pet = pet ?: return
        pendingTreatFollowUp?.let { pet.removeCallbacks(it) }
        pendingTreatFollowUp = null
        mouthAnimator?.cancel()
        pet.mouthOpenFood = 0f
        pet.showExpression(treat.expression, treat.expression.defaultDurationMillis)
        when (treat.audio) {
            TreatAudio.EATING -> audio?.playEating()
            TreatAudio.DRINKING -> audio?.playDrinking()
        }
        startChew(pet, treat)
        val followUp = treat.followUp ?: return
        val runnable = Runnable {
            pet.showExpression(followUp, followUp.defaultDurationMillis)
            emitTreatBurst(treat, x, y)
            if (treat.spicy) audio?.playSpicyCall(pet.characterType)
            pendingTreatFollowUp = null
        }
        pendingTreatFollowUp = runnable
        pet.postDelayed(runnable, treat.expression.defaultDurationMillis)
    }

    private fun startChew(pet: BuddyPetView, treat: Treat) {
        val frames = if (treat.chewPulses >= 2) floatArrayOf(0f, 1f, 0f, 1f, 0f) else floatArrayOf(0f, 1f, 1f, 0f)
        mouthAnimator = ValueAnimator.ofFloat(*frames).apply {
            duration = (treat.expression.defaultDurationMillis * CHEW_DURATION_SCALE).toLong().coerceAtLeast(1L)
            interpolator = LinearInterpolator()
            addUpdateListener { pet.mouthOpenFood = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { pet.mouthOpenFood = 0f }
            })
            start()
        }
    }

    private fun emitTreatBurst(treat: Treat, x: Float, y: Float) {
        val pet = pet ?: return
        if (pet.emitPandaTreat(treat.particle)) return
        val center = motionState?.center
        val radius = stageRadius.coerceAtLeast(1f)
        val originX = if (x.isFinite()) x else center?.x ?: (pet.width / 2f)
        val originY = if (y.isFinite()) y - radius * .2f else (center?.y ?: (pet.height / 2f)) - radius * .35f
        for (index in 0 until treat.sparkles) {
            val angle = (index / treat.sparkles.toFloat()) * TWO_PI
            val horizontal = cos(angle) * radius * .7f
            val vertical = when (treat.particle) {
                BuddyParticleSystem.Type.WATER -> radius * (.45f + sin(angle) * .2f)
                else -> -radius * (.55f + abs(sin(angle)) * .35f)
            }
            pet.emitParticle(treat.particle, originX + horizontal * .25f, originY,
                horizontal, vertical, radius * .1f, PARTICLE_LIFETIME_SECONDS)
        }
    }

    private fun canFitTreatColumns(): Boolean {
        val config = resources.configuration
        return config.screenWidthDp >= FOUR_COLUMN_MIN_WIDTH_DP && config.fontScale < FOUR_COLUMN_MAX_FONT_SCALE
    }

    private fun handlePetTouch(event: MotionEvent): Boolean {
        val pet = pet ?: return false
        val point = BuddyPlaygroundMotionController.Point(event.x, event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!isInsidePet(point)) return false
                pet.setPandaContact(true)
                stopMotionFrames()
                gestureActive = true
                gestureDragged = false
                gestureStart = point
                applyMotion(motion.beginDrag(point, eventTimeNanos(event)))
                resetPetTracking()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!gestureActive) return false
                updateGaze(point)
                if (!gestureDragged && hypot(point.x - gestureStart.x, point.y - gestureStart.y) >= ViewConfiguration.get(requireContext()).scaledTouchSlop) {
                    gestureDragged = true
                }
                if (gestureDragged) {
                    applyMotion(motion.dragTo(point, eventTimeNanos(event)))
                    trackPetting(point.x)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!gestureActive) return false
                pet.setPandaContact(false)
                gestureActive = false
                if (gestureDragged) {
                    val state = motion.release(point, eventTimeNanos(event))
                    applyMotion(state)
                    if (state.isRunning) startMotionFrames()
                } else {
                    applyMotion(motion.cancel())
                    pet.showExpression(BuddyExpression.POKE, POKE_DURATION_MILLIS)
                    audio?.playPet(pet.characterType)
                }
                pet.removeCallbacks(clearPet)
                pet.postDelayed(clearPet, PET_HOLD_MS)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (!gestureActive) return false
                pet.setPandaContact(false)
                if (pet.characterType == "panda") pet.petting = false
                gestureActive = false
                gestureDragged = false
                applyMotion(motion.cancel())
                pet.removeCallbacks(clearPet)
                return true
            }
        }
        return false
    }

    private fun resetMotionForStage(stage: FrameLayout, dock: View) {
        if (stage.width <= 0 || stage.height <= 0 || dock.width <= 0 || dock.height <= 0) return
        val layout = MotionLayout(stage.width, stage.height, dock.width, dock.height)
        if (layout == lastMotionLayout) return
        lastMotionLayout = layout
        stopMotionFrames()
        stageRadius = min(uiDp(TARGET_RADIUS_DP), min(stage.width * MAX_RADIUS_WIDTH_FRACTION, stage.height * MAX_RADIUS_HEIGHT_FRACTION))
        val floorLimit = (dock.top - dp(DOCK_CLEARANCE_DP) - stageRadius).coerceAtLeast(stageRadius)
        applyMotion(motion.reset(
            BuddyPlaygroundMotionController.Bounds(stage.width.toFloat(), stage.height.toFloat()),
            stageRadius,
            floorLimit
        ))
    }

    private fun applyMotion(state: BuddyPlaygroundMotionController.State) {
        motionState = state
        pet?.apply {
            renderCenterX = state.center.x
            renderCenterY = state.center.y
            renderRadius = stageRadius
            groundY = state.floorY
            renderRotationDegrees = Math.toDegrees(state.rotation.toDouble()).toFloat()
            renderScaleX = state.scaleX
            renderScaleY = state.scaleY
            val safeRadius = stageRadius.coerceAtLeast(1f)
            observePandaMotion(state.velocity.x / safeRadius, state.velocity.y / safeRadius,
                state.isDragging && gestureDragged)
        }
    }

    private fun startMotionFrames() {
        if (motionFramePosted) return
        motionFramePosted = true
        lastMotionFrameNanos = System.nanoTime()
        pet?.postOnAnimation(motionTick)
    }

    private fun stopMotionFrames() {
        motionFramePosted = false
        pet?.removeCallbacks(motionTick)
        lastMotionFrameNanos = 0L
    }

    private fun isInsidePet(point: BuddyPlaygroundMotionController.Point): Boolean {
        val state = motionState ?: return false
        return hypot(point.x - state.center.x, point.y - state.center.y) <= stageRadius * PET_HIT_RADIUS_MULTIPLIER
    }

    private fun updateGaze(point: BuddyPlaygroundMotionController.Point) {
        pet?.gazeTargetX = point.x
        pet?.gazeTargetY = point.y
    }

    private fun eventTimeNanos(event: MotionEvent) = event.eventTime * NANOS_PER_MILLISECOND

    private fun uiDp(value: Int) = value * density

    private fun resetPetTracking() {
        lastPetX = -1f; lastTurnX = 0f; lastDir = 0; reversals = 0; windowStart = 0L
    }

    private fun trackPetting(x: Float) {
        val now = SystemClock.uptimeMillis()
        if (lastPetX < 0f) { lastPetX = x; lastTurnX = x; windowStart = now; return }
        val dx = x - lastPetX
        lastPetX = x
        if (abs(dx) < 1f) return
        val dir = if (dx > 0f) 1 else -1
        if (now - windowStart > PET_WINDOW_MS) { windowStart = now; reversals = 0 }
        if (lastDir != 0 && dir != lastDir && abs(x - lastTurnX) >= dp(16)) {
            reversals++
            lastTurnX = x
        }
        lastDir = dir
        if (reversals >= 4) {
            val pet = pet ?: return
            if (!pet.petting) {
                audio?.playPet(pet.characterType)
                val state = motionState ?: return
                if (pet.characterType != "panda") {
                    pet.emitParticle(BuddyParticleSystem.Type.HEART, state.center.x, state.center.y - stageRadius * .58f,
                        0f, -stageRadius * .65f, stageRadius * .16f, .65f)
                }
            }
            pet.petting = true
            pet.removeCallbacks(clearPet)
            pet.postDelayed(clearPet, PET_HOLD_MS)
        }
    }

    override fun onResume() { super.onResume(); pet?.screenActive = true }
    override fun onPause() {
        stopMotionFrames()
        applyMotion(motion.cancel())
        pet?.screenActive = false
        super.onPause()
    }

    private fun showIconLicense() {
        val notice = SpannableString(
            "Ikon makanan: Font Awesome Free Classic Solid oleh Fonticons, Inc. / kontributor Font Awesome.\n\n" +
                "Lisensi: CC BY 4.0\nhttps://creativecommons.org/licenses/by/4.0/\n\n" +
                "Keempat SVG Font Awesome Free 6.7.2 dikonversi ke Android VectorDrawable; karya ikon tidak diubah."
        ).also { Linkify.addLinks(it, Linkify.WEB_URLS) }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Lisensi ikon makanan")
            .setMessage(notice)
            .setPositiveButton("Tutup", null)
            .show().withActionIcons(R.drawable.ic_ms_close)
            .findViewById<TextView>(android.R.id.message)
            ?.movementMethod = LinkMovementMethod.getInstance()
    }

    override fun onDestroyView() {
        mouthAnimator?.cancel(); mouthAnimator = null
        pet?.let { view -> pendingTreatFollowUp?.let { view.removeCallbacks(it) } }
        pendingTreatFollowUp = null
        dragTreat = null
        treatDragStarted = false
        stopMotionFrames()
        pet?.removeCallbacks(clearPet)
        audio?.release(); audio = null
        motionState = null
        stageRadius = 0f
        lastMotionLayout = null
        pet = null
        super.onDestroyView()
    }

    private data class Treat(
        val label: String,
        val iconRes: Int,
        val tintRes: Int,
        val expression: BuddyExpression,
        val followUp: BuddyExpression?,
        val audio: TreatAudio,
        val particle: BuddyParticleSystem.Type,
        val sparkles: Int,
        val chewPulses: Int,
        val spicy: Boolean = false
    )

    private enum class TreatAudio { EATING, DRINKING }

    private companion object {
        const val TARGET_RADIUS_DP = 95
        const val MAX_RADIUS_WIDTH_FRACTION = .28f
        const val MAX_RADIUS_HEIGHT_FRACTION = .26f
        const val PET_HIT_RADIUS_MULTIPLIER = 1.1f
        const val POKE_DURATION_MILLIS = 320L
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val PET_WINDOW_MS = 900L
        const val PET_HOLD_MS = 400L
        const val MOUTH_PROXIMITY_MULTIPLIER = 2.2f
        const val DROP_RADIUS_MULTIPLIER = 1.25f
        const val CHEW_DURATION_SCALE = 1.1f
        const val PARTICLE_LIFETIME_SECONDS = .7f
        const val TREAT_COLUMNS = 4
        const val TREAT_REFLOW_COLUMNS = 2
        const val FOUR_COLUMN_MIN_WIDTH_DP = 360
        const val FOUR_COLUMN_MAX_FONT_SCALE = 1.15f
        const val DOCK_ROW_HEIGHT_DP = 72
        const val DOCK_CLEARANCE_DP = 8
        const val DOCK_EDGE_DP = 8
        const val SAFE_EDGE_DP = 8
        const val TREAT_DRAG_LABEL = "buddy_treat"
        val TWO_PI = (PI * 2).toFloat()

        val TREATS = listOf(
            Treat("Es Krim", R.drawable.ic_play_icecream, R.color.buddy_sky, BuddyExpression.EATING,
                BuddyExpression.TAP_DELIGHT, TreatAudio.EATING, BuddyParticleSystem.Type.SPARKLE, 6, 1),
            Treat("Burger", R.drawable.ic_play_burger, R.color.buddy_lilac, BuddyExpression.EATING,
                BuddyExpression.TAP_DELIGHT, TreatAudio.EATING, BuddyParticleSystem.Type.HEART, 4, 2),
            Treat("Cabai", R.drawable.ic_play_chili, R.color.buddy_peach, BuddyExpression.EATING,
                BuddyExpression.SPICY, TreatAudio.EATING, BuddyParticleSystem.Type.SWEAT, 6, 1, spicy = true),
            Treat("Air", R.drawable.ic_play_water, R.color.buddy_mint, BuddyExpression.DRINKING,
                BuddyExpression.TAP_DELIGHT, TreatAudio.DRINKING, BuddyParticleSystem.Type.WATER, 6, 1)
        )
    }

    private data class MotionLayout(val stageWidth: Int, val stageHeight: Int, val dockWidth: Int, val dockHeight: Int)
}

package com.assistant.adi.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.asFlow
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.assistant.adi.BuildConfig
import com.assistant.adi.R
import com.assistant.adi.data.PrefsManager
import com.assistant.adi.ui.buddy.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar
import java.util.Date

class HyperOsDashboardFragment : Fragment() {
    private val model: BuddyDeviceViewModel by activityViewModels()
    private val awarenessModel: BuddyAwarenessViewModel by activityViewModels()
    private val internetUsageModel: InternetUsageViewModel by activityViewModels()
    private var body: LinearLayout? = null
    private var pet: BuddyPetView? = null
    private var permissionDialog: androidx.appcompat.app.AlertDialog? = null
    private var reaction: BuddyReaction? = null
    private var debugSheet: BuddyDebugSheet? = null
    private var homeAudio: BuddyAudioEngine? = null
    private val tapController = BuddyTapInteractionController()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val ui = BuddyUi(requireContext())
        val (scroll, content) = ui.page(); body = content
        content.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(20))
        scroll.setBackgroundColor(ui.color(R.color.buddy_stage))
        return scroll
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        val ui = BuddyUi(requireContext()); val style = BuddyHomeStyle(ui)
        val profile = BuddyProfile(requireContext()); val body = body ?: return
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val greeting = when (hour) { in 4..10 -> "Pagi"; in 11..14 -> "Siang"; in 15..17 -> "Sore"; else -> "Malam" }
        val header = LinearLayout(requireContext()).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(ui.column().apply {
            addView(style.text("$greeting, ${profile.userName}", 13f, stage = true, secondary = true))
            addView(style.text("Ramu", 24f, true, stage = true).apply { androidx.core.view.ViewCompat.setAccessibilityHeading(this, true) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val settings = style.icon(R.drawable.ic_settings, R.color.buddy_stage_ink, R.color.buddy_stage_button, 48)
        style.surface(settings, R.color.buddy_stage_button, 24) { navigate("settings") }
        settings.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        settings.contentDescription = "Pengaturan"
        header.addView(settings, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)).apply { marginStart = ui.dp(12) })
        body.addView(header, ui.margin(bottom = 16))

        val speech = style.text("Sebentar, aku lihat kabar HP-mu…", 16f, true).apply {
            gravity = Gravity.CENTER; maxWidth = ui.dp(290)
            setPadding(ui.dp(20), ui.dp(14), ui.dp(20), ui.dp(26))
            minimumHeight = ui.dp(64)
        }
        style.surface(speech, R.color.buddy_mint) { reaction?.let { navigate(it.destination) } }
        speech.isEnabled = false
        val bubble = BuddySpeechDrawable(ui.color(R.color.buddy_mint), resources.displayMetrics.density, ui.color(R.color.buddy_sub))
        speech.background = RippleDrawable(ColorStateList.valueOf(0x30536B61), bubble, BuddySpeechDrawable(android.graphics.Color.WHITE, resources.displayMetrics.density))
        speech.setOnFocusChangeListener { _, focused -> bubble.focused = focused }
        body.addView(speech, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END })
        val avatar = BuddyPetView(requireContext()).apply {
            motionEnabled = PrefsManager(requireContext()).buddyMotion
            isClickable = true
            isFocusable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = if (BuildConfig.DEBUG) {
                "Ramu. Ketuk untuk menyapa. Tekan lama untuk mode debug."
            } else {
                "Ramu. Ketuk untuk menyapa."
            }
        }
        homeAudio = BuddyAudioEngine(requireContext())
        avatar.setOnClickListener {
            val tapReaction = tapController.onTap(SystemClock.elapsedRealtime())
            if (!tapReaction.shouldTriggerExpression) return@setOnClickListener
            if (tapReaction.expression == BuddyExpression.TICKLE_LAUGH) {
                avatar.extendExpression(tapReaction.expression)
            } else {
                avatar.showExpression(tapReaction.expression)
            }
            val expressionIsCurrent = { avatar.isExpressionActive(tapReaction.expression) }
            when (tapReaction.expression) {
                BuddyExpression.TAP_DELIGHT -> homeAudio?.playHomeDelight(avatar.characterType, expressionIsCurrent)
                BuddyExpression.TICKLE_LAUGH -> homeAudio?.playHomeLaugh(avatar.characterType, expressionIsCurrent)
                BuddyExpression.ANNOYED -> homeAudio?.playHomeAnnoyed(avatar.characterType, expressionIsCurrent)
                else -> Unit
            }
        }
        if (BuildConfig.DEBUG) {
            avatar.setOnLongClickListener {
                debugSheet?.dismiss()
                debugSheet = BuddyDebugSheet(requireContext(), avatar) { tapController.reset() }.apply { show() }
                true
            }
        }
        pet = avatar
        val height = (resources.configuration.screenHeightDp * .30f).toInt().coerceIn(210, 280)
        body.addView(avatar, LinearLayout.LayoutParams(-1, ui.dp(height)).apply { topMargin = -ui.dp(14); bottomMargin = ui.dp(4) })
        body.addView(ui.button("Obrolan", icon = R.drawable.ic_ms_chat) { navigate("ai_chat") }.apply {
            textSize = 17f
            backgroundTintList = ColorStateList.valueOf(ui.color(R.color.buddy_primary))
            setTextColor(ui.color(R.color.buddy_primary_text))
            rippleColor = ColorStateList.valueOf(0x30536B61)
            strokeWidth = 0
            strokeColor = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                intArrayOf(ui.color(R.color.buddy_ink), android.graphics.Color.TRANSPARENT))
            contentDescription = "Obrolan dengan ${profile.buddyName}"
        }, ui.margin(top = 8, bottom = 18))

        val screenBox = ui.column().apply { setPadding(ui.dp(18), ui.dp(16), ui.dp(18), ui.dp(16)) }
        val screenTop = LinearLayout(requireContext()).apply { gravity = Gravity.CENTER_VERTICAL }
        screenTop.addView(style.icon(R.drawable.ic_stats_chart, R.color.buddy_ink, R.color.buddy_sky), LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)))
        val screenLabels = ui.column()
        screenLabels.addView(style.text("Waktu layar hari ini", 14f, true))
        val duration = style.text("Membaca…", 27f, true)
        screenLabels.addView(duration)
        screenTop.addView(screenLabels, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(12) })
        screenTop.addView(ImageView(requireContext()).apply {
            setImageResource(R.drawable.ic_buddy_chevron); imageTintList = ColorStateList.valueOf(ui.color(R.color.buddy_sub))
        }, LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)))
        val screenNote = style.text("Dihitung dari layar aktif dan terbuka", 13f, secondary = true)
        val screenFreshness = style.text("Menunggu bacaan langsung", 12f, secondary = true)
        val screenMeter = CapsuleDrawable(ui.color(R.color.buddy_track), ui.color(R.color.buddy_chart_blue), 0f)
        val screenProgress = View(requireContext()).apply { background = screenMeter }
        screenBox.addView(screenTop); screenBox.addView(screenNote, ui.margin(top = 8, bottom = 0))
        screenBox.addView(screenProgress, LinearLayout.LayoutParams(-1, ui.dp(6)).apply { topMargin = ui.dp(8); bottomMargin = ui.dp(8) })
        screenBox.addView(screenFreshness, ui.margin(top = 4, bottom = 0))
        var usageGranted = false
        style.surface(screenBox, R.color.buddy_sky, 20) { if (usageGranted) navigate("screen") else navigate("permissions") }
        for (i in 0 until screenBox.childCount) screenBox.getChildAt(i).importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        screenBox.contentDescription = "Waktu layar hari ini. Membaca data. Buka detail"
        body.addView(screenBox, ui.margin(bottom = 14))

        val internetUsage = InternetUsageTile(style) { navigate("internet_usage") }
        body.addView(internetUsage.root, ui.margin(bottom = 18))

        val tiles = listOf(Monitor.BATTERY, Monitor.MEMORY, Monitor.NETWORK, Monitor.STORAGE).associateWith { monitor ->
            BuddyMetricTile(style, monitor) { navigate("monitor_${monitor.name.lowercase()}") }
        }
        val deviceGroup = ui.column().apply { setPadding(0, ui.dp(4), 0, ui.dp(4)); background = style.rounded(R.color.buddy_tile, 16) }
        tiles.values.forEachIndexed { index, tile ->
            deviceGroup.addView(tile.root, LinearLayout.LayoutParams(-1, -2))
            if (index < tiles.size - 1) deviceGroup.addView(View(requireContext()).apply { setBackgroundColor(ui.color(R.color.buddy_hairline)) },
                LinearLayout.LayoutParams(-1, ui.dp(1)).apply { marginStart = ui.dp(16); marginEnd = ui.dp(16) })
        }
        body.addView(deviceGroup, ui.margin(bottom = 12))
        val playground = ui.textButton("Taman bermain", stage = true, icon = R.drawable.ic_ms_chevron_right) { navigate("playground") }
        body.addView(playground, ui.margin(bottom = 0))
        val updated = style.text("Membaca perangkat…", 12f, stage = true, secondary = true).apply { gravity = Gravity.CENTER }
        body.addView(updated, ui.margin(bottom = 8))
        val engine = BuddyReactionEngine()
        internetUsageModel.uiState.observe(viewLifecycleOwner) { internetUsage.update(it) }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    while (isActive) {
                        val now = System.currentTimeMillis()
                        avatar.setNightMode(BuddyTimePolicy.isNight(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)))
                        delay(BuddyTimePolicy.millisUntilNextBoundary(now))
                    }
                }
                combine(model.snapshot, awarenessModel.awareness, internetUsageModel.uiState.asFlow()) { device, aware, internet ->
                    Triple(device, aware, internet)
                }.collect { (snapshot, aware, internet) ->
                    if (snapshot == null) return@collect
                    snapshot.readings.forEach { reading ->
                        val temperature = if (reading.monitor == Monitor.BATTERY) snapshot.readings.firstOrNull { it.monitor == Monitor.TEMPERATURE } else null
                        tiles[reading.monitor]?.update(reading, temperature?.takeIf { it.condition != Condition.UNKNOWN }?.value)
                    }
                    usageGranted = aware?.usageGranted == true
                    duration.text = when { aware == null -> "Membaca…"; !usageGranted -> "Izinkan akses"; aware.todayMinutes == null -> "Belum terbaca"; else -> screenDuration(aware.todayMinutes) }
                    screenNote.text = when {
                        aware == null -> "Menyiapkan waktu pemakaian"
                        !usageGranted -> "Ketuk untuk melihat waktu pemakaianmu"
                        aware.todayMinutes == null -> "Android belum menyediakan bacaan. Ketuk untuk detail."
                        aware.dailyGoalMinutes > 0 -> "Target ${screenDuration(aware.dailyGoalMinutes)} · tanpa jeda ${screenDuration(aware.sessionMinutes ?: 0)}"
                        else -> "Tanpa jeda ${screenDuration(aware.sessionMinutes ?: 0)}"
                    }
                    screenFreshness.text = when {
                        aware == null -> "Menunggu bacaan langsung"
                        !usageGranted -> "Bacaan langsung belum diizinkan"
                        aware.todayMinutes == null -> "Bacaan langsung belum tersedia"
                        else -> "Bacaan langsung · ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(aware.timestamp))}"
                    }
                    screenProgress.visibility = if (aware?.todayMinutes != null && aware.dailyGoalMinutes > 0) View.VISIBLE else View.GONE
                    val screenGoalReached = aware?.let { state ->
                        state.dailyGoalMinutes > 0 && (state.todayMinutes ?: 0) >= state.dailyGoalMinutes
                    } == true
                    screenMeter.fill = ui.color(if (screenGoalReached) R.color.buddy_warning else R.color.buddy_chart_blue)
                    screenMeter.percent = aware?.todayMinutes?.let { if (aware.dailyGoalMinutes > 0) (it * 100 / aware.dailyGoalMinutes).coerceIn(0, 100) else null }
                    screenBox.contentDescription = "Waktu layar hari ini. ${duration.text}. ${screenNote.text}. ${screenFreshness.text}. Buka detail"
                    val next = engine.update(
                        device = snapshot,
                        awareness = aware,
                        internetLimit = internet.toBuddyInternetUsageLimit(System.currentTimeMillis()),
                        casual = profile.casual,
                        nowElapsed = SystemClock.elapsedRealtime(),
                        nowWallClock = System.currentTimeMillis()
                    )
                    reaction = next; avatar.mood = next.mood
                    speech.isEnabled = next.destination.isNotBlank(); speech.text = next.speech
                    speech.contentDescription = "${profile.buddyName}: ${next.speech}" +
                        if (next.destination.isNotBlank()) ". Ketuk untuk melihat penjelasan" else ""
                    updated.text = "Perangkat ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(snapshot.timestamp))}" +
                        if (aware?.todayMinutes != null) " · layar ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(aware.timestamp))}" else ""
                }
            }
        }
    }
    override fun onResume() {
        super.onResume()
        pet?.characterType = PrefsManager(requireContext()).getBuddyCharacter()
        pet?.screenActive = true
        internetUsageModel.refreshPermissionStatus()
    }
    override fun onPause() {
        pet?.clearTransientExpression()
        pet?.screenActive = false
        tapController.reset()
        super.onPause()
    }
    private fun navigate(tag: String) { (requireActivity() as DashboardActivity).navigateSection(tag) }

    private fun InternetUsageUiState.toBuddyInternetUsageLimit(now: Long): BuddyInternetUsageLimit {
        val zone = ZoneId.systemDefault()
        val today = com.assistant.adi.util.InternetUsagePolicy.dayWindow(LocalDate.now(zone), zone)
        val todayRow = days.firstOrNull { it.dayStartMillis == today.startMillis }
        val dailySampledAt = todayRow?.sampledAt
        val freshRoomData = usageAccessGranted && monitoringEnabled && !stale
        val dailyComplete = todayRow?.wifiBytes != null && todayRow.mobileBytes != null &&
            dailySampledAt in today.startMillis until minOf(today.endMillis, now + 1)
        val cycleSampledAt = latestSampledAt
        val cycleCurrent = now in cycle.startMillis until cycle.endMillis
        val cycleCompleteAndFresh = cycleComplete && cycleUsage.status == com.assistant.adi.util.InternetUsageStatus.AVAILABLE &&
            cycleSampledAt in cycle.startMillis until minOf(cycle.endMillis, now + 1)
        return BuddyInternetUsageLimit(
            dailyReached = freshRoomData && dailyComplete && limits.dailyReached,
            cycleReached = freshRoomData && cycleCurrent && cycleCompleteAndFresh && limits.cycleReached,
            sampledAt = cycleSampledAt ?: dailySampledAt,
            dayStartMillis = today.startMillis,
            dayEndMillis = today.endMillis,
            cycleStartMillis = cycle.startMillis,
            cycleEndMillis = cycle.endMillis,
            dataAvailable = freshRoomData
        )
    }

    override fun onDestroyView() {
        permissionDialog?.dismiss()
        permissionDialog = null
        debugSheet?.dismiss()
        debugSheet = null
        homeAudio?.release()
        homeAudio = null
        tapController.reset()
        pet = null
        body = null
        reaction = null
        super.onDestroyView()
    }
}

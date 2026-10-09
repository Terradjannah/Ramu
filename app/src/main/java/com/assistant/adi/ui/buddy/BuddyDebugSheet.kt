package com.assistant.adi.ui.buddy

import android.content.Context
import android.content.res.ColorStateList
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.assistant.adi.R
import com.assistant.adi.data.PrefsManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * Development-only control panel. Long-pressing the Home avatar opens it so a
 * renderer, a mood or motion can be forced without editing preferences by hand.
 */
class BuddyDebugSheet(
    context: Context,
    private val pet: BuddyPetView,
    private val onResetTapStreak: () -> Unit = {}
) {
    private val page = BuddyPageUi(context)
    private val ui = page.ui
    private val prefs = PrefsManager(context)
    private val dialog = BottomSheetDialog(ui.context)

    fun show() {
        val (scroll, content) = ui.page()
        scroll.setBackgroundColor(ui.color(R.color.buddy_tile))
        content.addView(page.text("Menu Debug", 24f, true), ui.margin(top = 16, bottom = 4))
        content.addView(page.text("Khusus pengujian. Matikan override agar avatar kembali mengikuti kondisi perangkat.", 14f, secondary = true), ui.margin(bottom = 8))

        val overrideControls = LinearLayout(ui.context).apply { orientation = LinearLayout.VERTICAL }

        val debugOverride = SwitchMaterial(ui.context).apply {
            text = "Aktifkan override debug"
            textSize = 16f
            setTextColor(ui.color(R.color.buddy_ink))
            isChecked = pet.debugOverridesEnabled
            minHeight = ui.dp(56)
            thumbTintList = switchThumbTint()
            trackTintList = switchTrackTint()
        }
        content.addView(debugOverride, ui.margin(top = 4, bottom = 0))
        content.addView(page.text("OFF memakai mood otomatis. ON membuka kontrol karakter, mood, ekspresi, dan waktu.", 13f, secondary = true), ui.margin(bottom = 8))

        radioGroup(overrideControls, "Pilih karakter",
            listOf("cat" to "Kucing (Mochi)", "panda" to "Panda (Bao)", "duck" to "Bebek (Ducky)"),
            pet.characterType) { pet.characterType = it }

        lateinit var expressionGroup: RadioGroup
        val moods: List<Pair<BuddyMood?, String>> =
            listOf(null to "Auto (Normal)") + listOf(
                BuddyMood.HAPPY,
                BuddyMood.HOT,
                BuddyMood.LOW_POWER,
                BuddyMood.CONFUSED,
                BuddyMood.OVERWHELMED,
                BuddyMood.OFFLINE,
                BuddyMood.CHARGING,
                BuddyMood.CURIOUS
            ).map { it to it.name } + listOf(BuddyMood.POUT to "POUT (Bao; Mochi/Ducky: CONFUSED)")
        radioGroup(overrideControls, "Override mood", moods, pet.overrideMood) {
            pet.setDebugMoodOverride(it)
            expressionGroup.check(expressionGroup.getChildAt(0).id)
        }

        val expressions: List<Pair<BuddyExpression?, String>> =
            listOf(null to "Auto (Normal)") + BuddyExpression.values()
                .filter { it != BuddyExpression.NONE }
                .map { it to it.name }
        expressionGroup = radioGroup(overrideControls, "Putar ekspresi sementara", expressions, pet.overrideExpression) { pet.setDebugExpressionOverride(it) }

        val nights: List<Pair<Boolean?, String>> =
            listOf(null to "Auto (Normal)", true to "Paksa malam", false to "Paksa siang")
        radioGroup(overrideControls, "Override malam", nights, pet.overrideNight) { pet.setDebugNightOverride(it) }

        content.addView(overrideControls)
        setControlsEnabled(overrideControls, pet.debugOverridesEnabled)
        debugOverride.setOnCheckedChangeListener { _, checked ->
            pet.setDebugOverridesEnabled(checked)
            setControlsEnabled(overrideControls, checked)
            if (!checked) dialog.dismiss()
        }

        val motion = SwitchMaterial(ui.context).apply {
            text = "Gerakan (Motion)"
            textSize = 16f
            setTextColor(ui.color(R.color.buddy_ink))
            isChecked = pet.motionEnabled
            minHeight = ui.dp(56)
            thumbTintList = switchThumbTint()
            trackTintList = switchTrackTint()
            setOnCheckedChangeListener { _, checked ->
                pet.motionEnabled = checked
                prefs.buddyMotion = checked
            }
        }
        content.addView(motion, ui.margin(top = 12, bottom = 0))
        content.addView(page.text("Matikan untuk mode Diam (Still Frame).", 13f, secondary = true), ui.margin(bottom = 16))

        content.addView(page.button("Reset hitungan ketuk", icon = R.drawable.ic_ms_restart_alt) { onResetTapStreak() }, ui.margin(top = 12, bottom = 0))
        content.addView(page.text("Mulai hitungan ketuk dari nol.", 13f, secondary = true), ui.margin(bottom = 16))

        content.addView(page.button("Tutup", icon = R.drawable.ic_ms_close) { dialog.dismiss() }, ui.margin(top = 8, bottom = 12))

        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val navBottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            v.setPadding(0, 0, 0, navBottom)
            insets
        }

        dialog.setContentView(scroll)
        dialog.setOnShowListener {
            dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            dialog.behavior.skipCollapsed = true
        }
        dialog.show()
    }

    fun dismiss() = dialog.dismiss()

    private fun switchThumbTint() = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
        intArrayOf(ui.color(R.color.buddy_action), ui.color(R.color.buddy_outline))
    )

    private fun switchTrackTint() = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
        intArrayOf(ui.color(R.color.buddy_mint), ui.color(R.color.buddy_divider))
    )

    private fun setControlsEnabled(root: ViewGroup, enabled: Boolean) {
        root.alpha = if (enabled) 1f else .5f
        setEnabledRecursively(root, enabled)
    }

    private fun setEnabledRecursively(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) setEnabledRecursively(view.getChildAt(index), enabled)
        }
    }

    private fun <T> radioGroup(
        parent: LinearLayout,
        title: String,
        options: List<Pair<T, String>>,
        selected: T,
        onPick: (T) -> Unit
    ): RadioGroup {
        parent.addView(page.text(title, 16f, true), ui.margin(top = 12, bottom = 6))
        val tint = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(ui.color(R.color.buddy_action), ui.color(R.color.buddy_outline))
        )
        val group = RadioGroup(ui.context).apply { orientation = RadioGroup.VERTICAL }
        options.forEach { (value, label) ->
            group.addView(RadioButton(ui.context).apply {
                id = android.view.View.generateViewId()
                text = label
                textSize = 15f
                setTextColor(ui.color(R.color.buddy_ink))
                buttonTintList = tint
                minHeight = ui.dp(48)
                isChecked = value == selected
            })
        }
        group.setOnCheckedChangeListener { _, checkedId ->
            val index = (0 until group.childCount).indexOfFirst { group.getChildAt(it).id == checkedId }
            if (index >= 0) onPick(options[index].first)
        }
        parent.addView(group, ui.margin(bottom = 8))
        return group
    }
}

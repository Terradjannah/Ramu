package com.assistant.adi.ui.buddy

import android.content.Context
import android.content.res.ColorStateList
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.assistant.adi.R
import com.google.android.material.button.MaterialButton

/** Theme-aware reading surfaces and plain stage sections shared by detail pages. */
class BuddyPageUi(context: Context) {
    val ui = BuddyUi(ContextThemeWrapper(context, R.style.ThemeOverlay_Buddy_Surface))
    val style = BuddyHomeStyle(ui)
    fun page() = ui.page().also { (scroll, _) -> scroll.setBackgroundColor(ui.color(R.color.buddy_stage)) }
    fun text(value: String, size: Float = 16f, bold: Boolean = false, secondary: Boolean = false, stage: Boolean = false) =
        style.text(value, size, bold, stage, secondary)
    fun stageText(value: String, size: Float = 16f, bold: Boolean = false, secondary: Boolean = false) =
        text(value, size, bold, secondary, stage = true)
    fun plainSection(parent: LinearLayout, title: String) = section(parent, title)
    fun card(parent: LinearLayout, tint: Int = R.color.buddy_tile): LinearLayout = ui.column().apply {
        background = style.rounded(tint)
        val padding = ui.dimension(R.dimen.buddy_card_padding)
        setPadding(padding, padding, padding, padding)
        parent.addView(this, ui.margin(bottom = 12))
    }
    fun section(parent: LinearLayout, title: String) {
        parent.addView(text(title, 18f, true, stage = true).apply {
            ViewCompat.setAccessibilityHeading(this, true)
        }, ui.margin(top = 24, bottom = 8))
    }
    fun button(label: String, secondary: Boolean = false, icon: Int = 0, action: () -> Unit) = ui.button(label, icon = icon, action = action).apply {
        backgroundTintList = androidx.core.content.ContextCompat.getColorStateList(ui.context, if (secondary) R.color.buddy_button_clay_bg else R.color.buddy_button_primary_bg)
        setTextColor(androidx.core.content.ContextCompat.getColorStateList(ui.context, if (secondary) R.color.buddy_button_clay_text else R.color.buddy_button_primary_text))
        if (icon != 0) iconTint = androidx.core.content.ContextCompat.getColorStateList(ui.context, if (secondary) R.color.buddy_button_clay_text else R.color.buddy_button_primary_text)
        rippleColor = ColorStateList.valueOf(0x30536B61)
        cornerRadius = ui.dimension(R.dimen.buddy_control_radius)
        strokeWidth = ui.dp(2)
        strokeColor = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
            intArrayOf(ui.color(R.color.buddy_action), android.graphics.Color.TRANSPARENT))
        setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(12))
    }
    fun textButton(label: String, stage: Boolean = false, icon: Int = 0, action: () -> Unit) = ui.textButton(label, stage, icon, action)
    fun divider(parent: LinearLayout) {
        parent.addView(View(ui.context).apply { setBackgroundColor(ui.color(R.color.buddy_divider)) }, LinearLayout.LayoutParams(-1, ui.dp(1)))
    }
    fun field(parent: LinearLayout, label: String, value: String, id: Int): com.google.android.material.textfield.TextInputEditText {
        val input = com.google.android.material.textfield.TextInputEditText(ui.context).apply {
            this.id = id; setText(value); textSize = 16f; minHeight = ui.dp(56)
            setTextColor(ui.color(R.color.buddy_ink))
            setHintTextColor(ui.color(R.color.buddy_sub))
            isSingleLine = false
            maxLines = 2
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
            filters = arrayOf(android.text.InputFilter.LengthFilter(30))
        }
        val layout = com.google.android.material.textfield.TextInputLayout(ui.context).apply {
            hint = label; boxBackgroundMode = com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE
            defaultHintTextColor = ColorStateList.valueOf(ui.color(R.color.buddy_sub))
            hintTextColor = ColorStateList.valueOf(ui.color(R.color.buddy_action))
            setBoxStrokeColorStateList(androidx.core.content.ContextCompat.getColorStateList(ui.context, R.color.buddy_input_box_stroke) ?: ColorStateList.valueOf(ui.color(R.color.buddy_action)))
            boxStrokeWidth = ui.dp(1)
            boxStrokeWidthFocused = ui.dp(2)
            val radius = ui.dimension(R.dimen.buddy_control_radius).toFloat()
            setBoxCornerRadii(radius, radius, radius, radius)
            addView(input)
        }
        parent.addView(layout, ui.margin(bottom = 16))
        return input
    }
    fun row(parent: LinearLayout, title: String, description: String = "", icon: Int = 0, iconFill: Int = R.color.buddy_mint, action: () -> Unit) {
        val row = LinearLayout(ui.context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(4), ui.dp(12), ui.dp(4), ui.dp(12))
        }
        if (icon != 0) row.addView(style.icon(icon, R.color.buddy_ink, iconFill),
            LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)).apply { marginEnd = ui.dp(12) })
        row.addView(ui.column().apply {
            addView(text(title, 16f, true))
            if (description.isNotBlank()) addView(text(description, 14f, secondary = true), ui.margin(top = 3, bottom = 0))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(style.icon(R.drawable.ic_buddy_chevron, R.color.buddy_sub, R.color.buddy_tile, 32),
            LinearLayout.LayoutParams(ui.dp(32), ui.dp(32)))
        style.surface(row, radius = 16, action = action)
        for (i in 0 until row.childCount) row.getChildAt(i).importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        row.contentDescription = "$title. $description. Buka"
        parent.addView(row, ui.margin(bottom = 0))
    }
    fun disclosure(parent: LinearLayout, title: String, initial: String): TextView {
        val card = card(parent).apply { setPadding(ui.dp(16), ui.dp(6), ui.dp(16), ui.dp(6)) }
        val detail = text(initial, 15f, secondary = true).apply { visibility = View.GONE }
        val control = ui.textButton(title) {}.apply {
            text = title; isAllCaps = false; textSize = 16f; minHeight = ui.dp(52)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setIconResource(R.drawable.ic_buddy_chevron)
            iconTint = ColorStateList.valueOf(ui.color(R.color.buddy_sub))
            iconGravity = MaterialButton.ICON_GRAVITY_END
            ViewCompat.setStateDescription(this, "Ditutup")
            setOnClickListener {
                val expanded = detail.visibility != View.VISIBLE
                detail.visibility = if (expanded) View.VISIBLE else View.GONE
                setIconResource(if (expanded) R.drawable.ic_buddy_expand else R.drawable.ic_buddy_chevron)
                ViewCompat.setStateDescription(this, if (expanded) "Dibuka" else "Ditutup")
            }
        }
        card.addView(control, ui.margin(bottom = 0))
        card.addView(detail, ui.margin(top = 4, bottom = 14))
        return detail
    }
}

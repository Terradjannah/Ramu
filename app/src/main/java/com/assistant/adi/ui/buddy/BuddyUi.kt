package com.assistant.adi.ui.buddy

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import com.assistant.adi.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/** Native, font-scale-aware primitives shared by all Ramu screens. */
class BuddyUi(val context: Context, private val surface: Boolean = false) {
    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun dimension(id: Int) = context.resources.getDimensionPixelSize(id)
    fun color(id: Int) = context.getColor(id)
    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun page(): Pair<ScrollView, LinearLayout> {
        val gutter = dimension(R.dimen.buddy_page_gutter)
        val body = column().apply { setPadding(gutter, dp(12), gutter, dp(24)) }
        val frame = FrameLayout(context).apply {
            addView(body, FrameLayout.LayoutParams(-1, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
                val width = minOf(r - l, dp(640))
                if (body.layoutParams.width != width) body.layoutParams = (body.layoutParams as FrameLayout.LayoutParams).apply { this.width = width }
            }
        }
        return ScrollView(context).apply { isFillViewport = true; clipToPadding = false; addView(frame) } to body
    }
    fun text(value: String, size: Float = 16f, bold: Boolean = false, secondary: Boolean = false) = TextView(context).apply {
        text = value; textSize = size
        setTextColor(color(if (surface) { if (secondary) R.color.buddy_sub else R.color.buddy_ink } else { if (secondary) R.color.buddy_stage_sub else R.color.buddy_stage_ink }))
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(dp(3).toFloat(), 1.05f)
    }
    fun heading(value: String) = text(value, 24f, true).apply { ViewCompat.setAccessibilityHeading(this, true) }
    fun section(parent: LinearLayout, title: String, subtitle: String? = null) {
        parent.addView(text(title, 18f, true).apply { ViewCompat.setAccessibilityHeading(this, true) }, margin(top = 24, bottom = 8))
        subtitle?.let { parent.addView(text(it, 14f, secondary = true), margin(bottom = 12)) }
    }
    fun margin(top: Int = 0, bottom: Int = 12) = LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = dp(top); bottomMargin = dp(bottom)
    }
    fun card(parent: LinearLayout, tint: Int = R.color.buddy_tile): LinearLayout {
        val inner = column().apply { setPadding(dp(16), dp(16), dp(16), dp(16)) }
        parent.addView(MaterialCardView(context).apply {
            radius = dimension(R.dimen.buddy_card_radius).toFloat(); cardElevation = 0f; strokeWidth = 0
            setCardBackgroundColor(color(tint)); addView(inner)
        }, margin(bottom = 12))
        return inner
    }
    fun pocketCard(parent: LinearLayout, tint: Int = R.color.buddy_mint_fill): LinearLayout {
        val inner = card(parent, tint).apply { setPadding(dp(24), dp(24), dp(24), dp(24)) }
        if (surface) return inner
        (inner.parent as MaterialCardView).shapeAppearanceModel = com.google.android.material.shape.ShapeAppearanceModel.builder()
            .setTopLeftCorner(com.google.android.material.shape.CornerFamily.ROUNDED, dp(36).toFloat())
            .setTopRightCorner(com.google.android.material.shape.CornerFamily.CUT, dp(28).toFloat())
            .setBottomRightCorner(com.google.android.material.shape.CornerFamily.ROUNDED, dp(44).toFloat())
            .setBottomLeftCorner(com.google.android.material.shape.CornerFamily.ROUNDED, dp(20).toFloat())
            .build()
        return inner
    }
    fun button(label: String, secondary: Boolean = false, icon: Int = 0, action: () -> Unit) = MaterialButton(context).apply {
        text = label; isAllCaps = false; textSize = 15f; minHeight = dp(48); cornerRadius = dimension(R.dimen.buddy_control_radius)
        backgroundTintList = ContextCompat.getColorStateList(context, if (secondary) R.color.buddy_button_clay_bg else R.color.buddy_button_primary_bg)
        setTextColor(ContextCompat.getColorStateList(context, if (secondary) R.color.buddy_button_clay_text else R.color.buddy_button_primary_text))
        if (icon != 0) {
            setIconResource(icon)
            iconSize = dp(20)
            iconPadding = dp(8)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            iconTint = ContextCompat.getColorStateList(context, if (secondary) R.color.buddy_button_clay_text else R.color.buddy_button_primary_text)
        }
        strokeWidth = dp(2)
        strokeColor = ContextCompat.getColorStateList(context, R.color.buddy_focus_ring)
        setOnClickListener { action() }
    }
    fun iconButton(icon: Int, label: String, action: (ImageButton) -> Unit) = ImageButton(context).apply {
        setImageResource(icon)
        imageTintList = ContextCompat.getColorStateList(context, R.color.buddy_button_clay_text)
        background = ContextCompat.getDrawable(context, R.drawable.buddy_icon_button_background)
        contentDescription = label
        ViewCompat.setTooltipText(this, label)
        isFocusable = true
        isClickable = true
        minimumWidth = dp(48)
        minimumHeight = dp(48)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setOnClickListener { action(this) }
    }
    fun headerAction(icon: Int, label: String, action: (ImageButton) -> Unit) = ImageButton(context).apply {
        layoutParams = ViewGroup.LayoutParams(dp(48), dp(48))
        setImageResource(icon)
        imageTintList = ContextCompat.getColorStateList(context, R.color.buddy_stage_text_selector)
        background = ContextCompat.getDrawable(context, R.drawable.buddy_header_action_background)
        contentDescription = label
        ViewCompat.setTooltipText(this, label)
        isFocusable = true
        isClickable = true
        minimumWidth = dp(48)
        minimumHeight = dp(48)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setOnClickListener { action(this) }
    }
    fun badge(label: String, tone: Int = R.color.buddy_action) = text(label, 13f, true).apply {
        setTextColor(color(tone)); setPadding(0, dp(2), 0, dp(6))
    }
    fun progress() = LinearProgressIndicator(context).apply {
        max = 100; trackThickness = dp(8); trackCornerRadius = dp(4)
        setIndicatorColor(color(R.color.buddy_action)); trackColor = color(R.color.buddy_track)
    }
    fun field(parent: LinearLayout, label: String, value: String, id: Int): TextInputEditText {
        val input = TextInputEditText(context).apply {
            this.id = id; setText(value); textSize = 16f; setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
            filters = arrayOf(android.text.InputFilter.LengthFilter(30))
            minHeight = dp(56)
        }
        parent.addView(TextInputLayout(context).apply {
            hint = label; boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setBoxStrokeColorStateList(ColorStateList.valueOf(color(R.color.buddy_action)))
            val radius = dimension(R.dimen.buddy_control_radius).toFloat()
            setBoxCornerRadii(radius, radius, radius, radius)
            addView(input)
        }, margin(bottom = 16))
        return input
    }
    fun row(parent: LinearLayout, title: String, description: String, action: () -> Unit) {
        val inner = column().apply {
            setPadding(dp(4), dp(12), dp(4), dp(12))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(text(title, 16f, true))
            if (description.isNotBlank()) addView(text(description, 13f, secondary = true), margin(top = 3, bottom = 0))
        }
        parent.addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(inner, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_buddy_chevron)
                imageTintList = ColorStateList.valueOf(color(R.color.buddy_sub))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginStart = dp(12) })
            isClickable = true; isFocusable = true; minimumHeight = dp(64)
            setBackgroundResource(R.drawable.buddy_row_background)
            if (surface) BuddyHomeStyle(this@BuddyUi).surface(this, radius = 16, action = action)
            contentDescription = "$title. $description. Buka"
            setOnClickListener { action() }
            ViewCompat.setAccessibilityDelegate(this, object : androidx.core.view.AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: androidx.core.view.accessibility.AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info); info.className = Button::class.java.name
                }
            })
        }, margin(bottom = 4))
    }
    fun textButton(label: String, stage: Boolean = !surface, icon: Int = 0, action: () -> Unit) = MaterialButton(context).apply {
        text = label; isAllCaps = false; textSize = 14f; minHeight = dp(48); cornerRadius = dp(12)
        backgroundTintList = ContextCompat.getColorStateList(context, android.R.color.transparent)
        setTextColor(ContextCompat.getColorStateList(context, if (stage) R.color.buddy_stage_text_selector else R.color.buddy_action_text_selector))
        if (icon != 0) {
            setIconResource(icon)
            iconSize = dp(20)
            iconPadding = dp(8)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            iconTint = ContextCompat.getColorStateList(context, if (stage) R.color.buddy_stage_text_selector else R.color.buddy_action_text_selector)
        }
        rippleColor = ColorStateList.valueOf(0x30536B61)
        strokeWidth = dp(2)
        strokeColor = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
            intArrayOf(color(if (stage) R.color.buddy_stage_focus else R.color.buddy_action), android.graphics.Color.TRANSPARENT))
        setOnClickListener { action() }
    }
    fun divider(parent: LinearLayout) {
        parent.addView(View(context).apply { setBackgroundColor(color(R.color.buddy_divider)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(-1, dp(1)))
    }
    fun disclosure(parent: LinearLayout, title: String, content: String): TextView {
        val detail = text(content, 15f, secondary = true).apply { visibility = View.GONE }
        val control = textButton(title) {}.apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setIconResource(R.drawable.ic_buddy_chevron)
            iconGravity = MaterialButton.ICON_GRAVITY_END
            iconTint = ColorStateList.valueOf(color(R.color.buddy_sub))
            ViewCompat.setStateDescription(this, "Ditutup")
            setOnClickListener {
                val expanded = detail.visibility != View.VISIBLE
                detail.visibility = if (expanded) View.VISIBLE else View.GONE
                setIconResource(if (expanded) R.drawable.ic_buddy_expand else R.drawable.ic_buddy_chevron)
                ViewCompat.setStateDescription(this, if (expanded) "Dibuka" else "Ditutup")
            }
        }
        parent.addView(control, margin(bottom = 0)); parent.addView(detail, margin(bottom = 12))
        return detail
    }
}

class BuddyProfile(context: Context) {
    private val prefs = context.getSharedPreferences("pocket_buddy_profile", Context.MODE_PRIVATE)
    var completed: Boolean
        get() = prefs.getBoolean("completed", false)
        set(value) { prefs.edit().putBoolean("completed", value).apply() }
    var userName: String
        get() = prefs.getString("user", "Teman").orEmpty()
        set(value) { prefs.edit().putString("user", clean(value, "Teman")).apply() }
    var buddyName: String
        get() = prefs.getString("buddy", "Buddy").orEmpty()
        set(value) { prefs.edit().putString("buddy", clean(value, "Buddy")).apply() }
    var casual: Boolean
        get() = prefs.getBoolean("casual", true)
        set(value) { prefs.edit().putBoolean("casual", value).apply() }
    private fun clean(value: String, fallback: String) = value.filter { !it.isISOControl() }.trim().take(30).ifBlank { fallback }
}


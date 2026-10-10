package com.assistant.adi.ui.buddy

import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.assistant.adi.R

class BuddyHomeStyle(val ui: BuddyUi) {
    fun text(value: String, size: Float, bold: Boolean = false, stage: Boolean = false, secondary: Boolean = false) = ui.text(value, size, bold).apply {
        setTextColor(ui.color(if (stage) { if (secondary) R.color.buddy_stage_sub else R.color.buddy_stage_ink }
            else if (secondary) R.color.buddy_sub else R.color.buddy_ink))
    }
    fun rounded(color: Int, radius: Int = 20) = GradientDrawable().apply {
        setColor(ui.color(color)); cornerRadius = ui.dp(radius).toFloat()
    }
    fun surface(view: View, color: Int = R.color.buddy_tile, radius: Int = 28, action: () -> Unit) {
        val states = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), rounded(color, radius).apply {
                setStroke(ui.dp(3), ui.color(if (color == R.color.buddy_stage_button) R.color.buddy_stage_ink else R.color.buddy_sub))
            })
            addState(intArrayOf(), rounded(color, radius))
        }
        view.background = RippleDrawable(ColorStateList.valueOf(0x30536B61), states, rounded(color, radius))
        view.isClickable = true; view.isFocusable = true; view.minimumHeight = ui.dp(48)
        view.setOnClickListener { action() }
        ViewCompat.setAccessibilityDelegate(view, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info); info.className = Button::class.java.name
            }
        })
    }
    fun glyph(resource: Int, tint: Int, glyphSize: Int = 24) = ImageView(ui.context).apply {
        setImageResource(resource)
        imageTintList = ColorStateList.valueOf(ui.color(tint))
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        minimumWidth = ui.dp(glyphSize)
        minimumHeight = ui.dp(glyphSize)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    fun icon(resource: Int, tint: Int, fill: Int? = null, size: Int = 40, glyphSize: Int = if (size <= 32) 20 else 24) = glyph(resource, tint, glyphSize).apply {
        if (fill != null) background = rounded(fill, size / 2)
        val inset = ui.dp((size - glyphSize).coerceAtLeast(0) / 2)
        setPadding(inset, inset, inset, inset)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
}

class BuddyMetricTile(private val style: BuddyHomeStyle, val monitor: Monitor, action: () -> Unit) {
    private val ui = style.ui
    val root = ui.column().apply { setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12)) }
    private val value = style.text("Membaca…", 18f, true).apply { gravity = Gravity.END }
    private val caption = style.text("Tunggu sebentar", 13f, secondary = true)
    private val meter = CapsuleDrawable(ui.color(R.color.buddy_track), ui.color(R.color.buddy_action), 0f)
    private val progress = View(ui.context).apply { background = meter; visibility = View.GONE }
    init {
        val title = when (monitor) { Monitor.MEMORY -> "RAM"; Monitor.NETWORK -> "Koneksi"; else -> monitor.title }
        val heading = LinearLayout(ui.context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(style.glyph(monitor.icon, R.color.buddy_ink), LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)))
            addView(style.text(title, 15f, true), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(8) })
            addView(value, LinearLayout.LayoutParams(-2, -2))
            addView(style.glyph(R.drawable.ic_buddy_chevron, R.color.buddy_sub, 20), LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)).apply { marginStart = ui.dp(8) })
        }
        root.addView(heading); root.addView(caption, ui.margin(top = 4, bottom = 0))
        root.addView(progress, LinearLayout.LayoutParams(-1, ui.dp(5)).apply { topMargin = ui.dp(8) })
        for (i in 0 until root.childCount) root.getChildAt(i).importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        root.background = RippleDrawable(ColorStateList.valueOf(0x30536B61), null, null)
        root.isClickable = true
        root.isFocusable = true
        root.minimumHeight = ui.dp(72)
        root.setOnClickListener { action() }
        ViewCompat.setAccessibilityDelegate(root, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Button::class.java.name
            }
        })
        root.contentDescription = "$title. Membaca perangkat. Buka detail"
    }
    fun update(reading: MonitorReading, detail: String? = null) {
        value.text = if (reading.condition == Condition.UNKNOWN) "Belum ada" else reading.compactValue
        meter.fill = ui.color(when (reading.condition) {
            Condition.ATTENTION -> R.color.buddy_warning
            Condition.ACTION -> R.color.buddy_danger
            else -> if (monitor == Monitor.BATTERY) R.color.buddy_action else R.color.buddy_chart_blue
        })
        progress.visibility = if (monitor != Monitor.NETWORK && reading.percent != null) View.VISIBLE else View.GONE
        meter.percent = reading.percent
        caption.text = when {
            reading.condition == Condition.UNKNOWN -> "Data belum tersedia"
            monitor == Monitor.BATTERY -> listOfNotNull(
                if (reading.charging == true) "Mengisi daya" else if (reading.condition == Condition.ACTION) "Daya kritis" else if (reading.condition == Condition.ATTENTION) "Daya menipis" else "Daya tersisa",
                detail?.let { "Suhu baterai $it" }
            ).joinToString(" · ")
            monitor == Monitor.TEMPERATURE -> when (reading.condition) { Condition.ACTION -> "Tinggi · jeda dulu"; Condition.ATTENTION -> "Perlu perhatian"; else -> "Suhu normal" }
            monitor == Monitor.MEMORY -> if (reading.condition == Condition.ACTION) "RAM hampir penuh" else if (reading.condition == Condition.ATTENTION) "Memori rendah" else "${reading.percent}% dipakai"
            monitor == Monitor.NETWORK -> if (reading.condition == Condition.GOOD) "Internet tersedia" else "Belum terverifikasi"
            else -> reading.condition.label
        }
        root.contentDescription = "${if (monitor == Monitor.MEMORY) "RAM" else if (monitor == Monitor.NETWORK) "Koneksi" else monitor.title}. ${value.text}. ${caption.text}. Buka detail"
    }
}

class CapsuleDrawable(private val track: Int, fill: Int, private val stroke: Float) : Drawable() {
    var fill: Int = fill
        set(value) { field = value; invalidateSelf() }
    var percent: Int? = null
        set(value) { field = value; invalidateSelf() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    override fun draw(canvas: Canvas) {
        rect.set(bounds); val radius = rect.height() / 2
        paint.color = track; canvas.drawRoundRect(rect, radius, radius, paint)
        percent?.takeIf { it > 0 }?.let {
            paint.color = fill
            rect.right = rect.left + rect.width() * it.coerceIn(0, 100) / 100f
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

class BuddySpeechDrawable(private val color: Int, private val density: Float, private val focusColor: Int = Color.parseColor("#53625D")) : Drawable() {
    var focused = false
        set(value) { field = value; invalidateSelf() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = this@BuddySpeechDrawable.color }
    private val path = Path()
    private val rect = RectF()
    override fun draw(canvas: Canvas) {
        val tail = 12 * density
        rect.set(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom - tail)
        canvas.drawRoundRect(rect, 25 * density, 25 * density, paint)
        path.reset(); path.moveTo(rect.left + 32 * density, rect.bottom - density)
        path.lineTo(rect.left + 32 * density, rect.bottom + tail)
        path.lineTo(rect.left + 52 * density, rect.bottom - density); path.close(); canvas.drawPath(path, paint)
        if (focused) {
            paint.color = focusColor; paint.style = Paint.Style.STROKE; paint.strokeWidth = 3 * density
            rect.inset(2 * density, 2 * density)
            canvas.drawRoundRect(rect, 23 * density, 23 * density, paint)
            paint.style = Paint.Style.FILL; paint.color = color
        }
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

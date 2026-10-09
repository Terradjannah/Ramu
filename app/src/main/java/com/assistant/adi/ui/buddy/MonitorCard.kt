package com.assistant.adi.ui.buddy

import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.*
import com.assistant.adi.R

class MonitorCard(ui: BuddyUi, parent: LinearLayout, private val monitor: Monitor, action: (() -> Unit)? = null) {
    private val page = BuddyPageUi(ui.context)
    private val palette = when (monitor) {
        Monitor.BATTERY -> R.color.buddy_mint to R.color.buddy_mint_fill
        Monitor.TEMPERATURE -> R.color.buddy_peach to R.color.buddy_peach_fill
        Monitor.STORAGE, Monitor.MEMORY -> R.color.buddy_lilac to R.color.buddy_lilac_fill
        Monitor.NETWORK -> R.color.buddy_sky to R.color.buddy_sky_fill
    }
    private val body = page.card(parent)
    private val value = page.text("Membaca…", 32f, true).apply {
        background = page.style.rounded(palette.first, 22)
        setPadding(ui.dp(16), ui.dp(18), ui.dp(16), ui.dp(18))
    }
    private val status = page.text("Membaca perangkat", 14f, true)
    private val description = page.text("Sebentar, Buddy sedang mengambil bacaan dari Android.", 16f)
    private val meter = CapsuleDrawable(ui.color(palette.first), ui.color(palette.second), ui.dp(2).toFloat())
    private val progress = View(ui.context).apply { background = meter }
    private val progressLabel = page.text("", 14f, secondary = true)
    init {
        body.addView(LinearLayout(ui.context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(page.style.icon(monitor.icon, R.color.buddy_ink, palette.first), LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)).apply { marginEnd = ui.dp(12) })
            addView(page.text(when (monitor) {
                Monitor.BATTERY -> "Daya tersisa"
                Monitor.TEMPERATURE -> "Suhu saat ini"
                Monitor.STORAGE -> "Ruang yang tersedia"
                Monitor.MEMORY -> "Memori yang dipakai"
                Monitor.NETWORK -> "Koneksi saat ini"
            }, 16f, true), LinearLayout.LayoutParams(0, -2, 1f))
        }, ui.margin(bottom = 12))
        body.addView(value, ui.margin(bottom = 14)); body.addView(status, ui.margin(bottom = 6)); body.addView(description, ui.margin(bottom = 14))
        body.addView(progressLabel, ui.margin(bottom = 4)); progressLabel.visibility = View.GONE
        body.addView(progress, LinearLayout.LayoutParams(-1, ui.dp(12))); progress.visibility = View.GONE
        if (action != null) body.apply {
            isClickable = true; isFocusable = true; setOnClickListener { action() }
            androidx.core.view.ViewCompat.setAccessibilityDelegate(this, object : androidx.core.view.AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: androidx.core.view.accessibility.AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info); info.className = android.widget.Button::class.java.name
                    info.contentDescription = "${monitor.title}, ${value.text}, ${status.text}. ${description.text}. Buka detail"
                }
            })
        }
    }
    fun update(reading: MonitorReading, casual: Boolean = true) {
        value.text = reading.value
        status.text = reading.condition.label
        status.setTextColor(status.context.getColor(when (reading.condition) {
            Condition.GOOD -> R.color.buddy_action
            Condition.ATTENTION -> R.color.buddy_warning
            Condition.ACTION -> R.color.buddy_danger
            Condition.UNKNOWN -> R.color.buddy_sub
        }))
        description.text = if (casual) reading.friendly else reading.facts.substringBefore('\n')
        progress.visibility = if (reading.percent != null) View.VISIBLE else View.GONE
        progressLabel.visibility = progress.visibility
        reading.percent?.let {
            meter.percent = it
            progressLabel.text = if (monitor == Monitor.BATTERY) "Daya tersisa" else "$it% terpakai"
        }
    }
}

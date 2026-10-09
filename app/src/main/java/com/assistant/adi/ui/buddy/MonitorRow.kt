package com.assistant.adi.ui.buddy

import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import com.assistant.adi.R

class MonitorRow(ui: BuddyUi, parent: LinearLayout, private val monitor: Monitor, action: () -> Unit) {
    private val value = ui.text("…", 21f, true)
    private val status = ui.text("Membaca…", 13f, secondary = true)
    private val root = LinearLayout(ui.context)
    init {
        val stacked = ui.context.resources.configuration.let { it.screenWidthDp < 360 || it.fontScale > 1.25f }
        root.apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ui.dp(76); setPadding(ui.dp(4), ui.dp(12), ui.dp(4), ui.dp(12))
            setBackgroundResource(R.drawable.buddy_row_background)
            isClickable = true; isFocusable = true; setOnClickListener { action() }
        }
        root.addView(ImageView(ui.context).apply {
            setImageResource(monitor.icon); imageTintList = ColorStateList.valueOf(ui.color(R.color.buddy_ink))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)).apply { marginEnd = ui.dp(12) })
        val label = ui.column().apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(ui.text(if (monitor == Monitor.MEMORY) "Memori" else monitor.title, 15f, true))
            if (stacked) addView(value, ui.margin(top = 4, bottom = 0))
            addView(status)
        }
        root.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
        if (!stacked) root.addView(value.apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dp(8) })
        root.addView(ImageView(ui.context).apply {
            setImageResource(R.drawable.ic_buddy_chevron); imageTintList = ColorStateList.valueOf(ui.color(R.color.buddy_sub))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(ui.dp(18), ui.dp(24)).apply { marginStart = ui.dp(8) })
        root.contentDescription = "${monitor.title}. Sedang membaca. Buka detail"
        ViewCompat.setAccessibilityDelegate(root, object : androidx.core.view.AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: androidx.core.view.accessibility.AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info); info.className = android.widget.Button::class.java.name
            }
        })
        parent.addView(root)
    }
    fun update(reading: MonitorReading) {
        value.text = if (reading.condition == Condition.UNKNOWN) "Belum ada" else reading.value
        status.text = when (reading.condition) {
            Condition.UNKNOWN -> "Buka untuk bantuan"
            Condition.ACTION -> "Perlu tindakan"
            Condition.ATTENTION -> if (monitor == Monitor.NETWORK) "Internet belum terverifikasi" else "Perlu perhatian"
            Condition.GOOD -> when (monitor) {
                Monitor.BATTERY -> if (reading.facts.startsWith("Sedang")) "Mengisi daya" else "Daya tersisa"
                Monitor.TEMPERATURE -> "Normal"
                Monitor.STORAGE -> "Ruang tersedia"
                Monitor.MEMORY -> "Dipakai aplikasi & sistem"
                Monitor.NETWORK -> "Internet tersedia"
            }
        }
        status.setTextColor(status.context.getColor(reading.condition.tone))
        root.contentDescription = "${monitor.title}. ${value.text}. ${status.text}. Buka detail"
    }
}

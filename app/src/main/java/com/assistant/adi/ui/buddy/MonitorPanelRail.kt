package com.assistant.adi.ui.buddy

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.assistant.adi.R

class MonitorPanelRail(
    context: Context,
    private val panels: List<MonitorDetailPanel>,
    private val onSelected: (MonitorDetailPanel) -> Unit,
    private val labelOverrides: Map<MonitorDetailPanel, String> = emptyMap()
) : LinearLayout(context) {
    private val ui = BuddyUi(context)
    private val tabs = mutableMapOf<MonitorDetailPanel, Pair<TextView, View>>()

    init {
        require(panels.isNotEmpty() && panels.distinct().size == panels.size)
        orientation = HORIZONTAL
        // Vertical hosts otherwise supply MATCH_PARENT height, including inside fillViewport scroll views.
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        contentDescription = "Bagian detail"
        val labels = mapOf(
            MonitorDetailPanel.SUMMARY to "Ringkasan",
            MonitorDetailPanel.HISTORY to "Riwayat",
            MonitorDetailPanel.SETTINGS to "Atur"
        )
        val icons = mapOf(
            MonitorDetailPanel.SUMMARY to R.drawable.ic_ms_dashboard,
            MonitorDetailPanel.HISTORY to R.drawable.ic_ms_history,
            MonitorDetailPanel.SETTINGS to R.drawable.ic_ms_tune
        )
        panels.forEach { panel ->
            val tab = LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                isClickable = true
                isFocusable = true
                minimumHeight = ui.dp(48)
                setPadding(ui.dp(2), ui.dp(8), ui.dp(2), ui.dp(6))
                contentDescription = labelOverrides[panel] ?: labels.getValue(panel)
                background = RippleDrawable(
                    ColorStateList.valueOf(0x30536B61),
                    StateListDrawable().apply {
                        addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                            setColor(Color.TRANSPARENT)
                            setStroke(ui.dp(2), ui.color(R.color.buddy_action))
                        })
                        addState(intArrayOf(), GradientDrawable().apply { setColor(Color.TRANSPARENT) })
                    },
                    GradientDrawable().apply { setColor(Color.WHITE) }
                )
                setOnClickListener { onSelected(panel) }
            }
            val icon = ImageView(context).apply {
                setImageResource(if (panel == MonitorDetailPanel.SETTINGS && labelOverrides[panel] == "Batas")
                    R.drawable.ic_ms_timer else icons.getValue(panel))
                imageTintList = ColorStateList.valueOf(ui.color(R.color.buddy_sub))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val label = TextView(context).apply {
                text = labelOverrides[panel] ?: labels.getValue(panel)
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(ui.dp(2), ui.dp(4), ui.dp(2), ui.dp(4))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val underline = View(context).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            tab.addView(icon, LayoutParams(ui.dp(20), ui.dp(20)))
            tab.addView(label, LayoutParams(-1, -2))
            tab.addView(View(context), LayoutParams(1, 0, 1f))
            tab.addView(underline, LayoutParams(-1, ui.dp(2)))
            addView(tab, LayoutParams(0, -1, 1f))
            tabs[panel] = label to underline
        }
    }

    fun renderSelection(panel: MonitorDetailPanel) {
        require(panel in panels)
        tabs.forEach { (item, views) ->
            val selected = item == panel
            val tab = views.first.parent as View
            tab.isSelected = selected
            ViewCompat.setStateDescription(tab, if (selected) "Dipilih" else "Tidak dipilih")
            views.first.typeface = Typeface.create(views.first.typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
            views.first.setTextColor(ui.color(if (selected) R.color.buddy_action else R.color.buddy_sub))
            ((views.first.parent as LinearLayout).getChildAt(0) as ImageView).imageTintList =
                ColorStateList.valueOf(ui.color(if (selected) R.color.buddy_action else R.color.buddy_sub))
            views.second.setBackgroundColor(if (selected) ui.color(R.color.buddy_action) else Color.TRANSPARENT)
        }
    }
}

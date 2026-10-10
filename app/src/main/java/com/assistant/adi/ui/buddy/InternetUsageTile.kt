package com.assistant.adi.ui.buddy

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.assistant.adi.R
import com.assistant.adi.ui.InternetUsageUiState
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class InternetUsageTile(private val style: BuddyHomeStyle, action: () -> Unit) {
    private val ui = style.ui
    val root = ui.column().apply { setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12)) }
    private val value = style.text("Membaca…", 18f, true)
    private val sample = style.text("Menunggu data tersimpan", 13f, secondary = true)
    private val target = style.text("", 13f, secondary = true)

    init {
        val heading = LinearLayout(ui.context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(style.glyph(R.drawable.ic_data_usage, R.color.buddy_chart_blue), LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)))
            addView(style.text("Penggunaan internet", 16f, true), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(12) })
            addView(style.glyph(R.drawable.ic_buddy_chevron, R.color.buddy_sub, 20), LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)))
        }
        root.addView(heading, ui.margin(bottom = 6))
        root.addView(value, ui.margin(bottom = 4))
        root.addView(sample, ui.margin(bottom = 2))
        root.addView(target)
        for (index in 0 until root.childCount) root.getChildAt(index).importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        style.surface(root, R.color.buddy_tile, 16, action)
        root.contentDescription = "Penggunaan internet. Membaca data tersimpan. Buka detail"
    }

    fun update(state: InternetUsageUiState) {
        value.text = when {
            !state.usageAccessGranted -> "Akses diperlukan"
            !state.monitoringEnabled -> "Pencatatan dijeda"
            state.todayUsage.totalBytes == null -> "Data belum lengkap"
            else -> "Hari ini · ${formatGigabytes(state.todayUsage.totalBytes)}"
        }
        sample.text = when {
            !state.usageAccessGranted -> "Aktifkan Akses penggunaan untuk statistik internet tersimpan"
            !state.monitoringEnabled -> "Aktifkan pencatatan untuk membuat sampel baru"
            state.latestSampledAt == null -> "Belum ada sampel tersimpan"
            state.stale -> "Data tersimpan · sampel ${formatTime(state.latestSampledAt)} mungkin sudah lama"
            else -> "Data tersimpan · sampel ${formatTime(state.latestSampledAt)}"
        }
        target.text = when {
            state.settings.monthlyBudgetBytes == null -> "Belum ada target harian"
            state.todayUsage.totalBytes == null -> "Status target menunggu data lengkap"
            state.limits.dailyReached -> "Target harian terlewati"
            state.limits.dailyTargetBytes != null -> "Target harian ${formatGigabytes(state.limits.dailyTargetBytes)}"
            else -> "Target harian belum tersedia"
        }
        target.setTextColor(ui.color(if (state.limits.dailyReached) R.color.buddy_warning else R.color.buddy_stage_sub))
        root.contentDescription = "Penggunaan internet. ${value.text}. ${sample.text}. ${target.text}. Buka detail"
    }

    private fun formatGigabytes(bytes: Long): String = String.format(
        Locale.getDefault(),
        if (bytes < 100_000_000L) "%.2f GB" else "%.1f GB",
        bytes / 1_000_000_000.0
    )

    private fun formatTime(time: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(time))
}

package com.assistant.adi.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.assistant.adi.R
import com.assistant.adi.ui.buddy.BuddyUi
import com.assistant.adi.util.UsageReader
import com.assistant.adi.util.XiaomiAutostartHelper

/** Shared permission status and destinations used by onboarding and Settings. */
class PermissionChecklist(
    private val fragment: Fragment,
    private val launchNotificationPermission: () -> Unit
) {
    private enum class Status { ACTIVE, INACTIVE, CHECK }

    fun render(ui: BuddyUi, parent: LinearLayout, compact: Boolean = false) {
        val context = fragment.context ?: return
        val card = ui.card(parent)
        val usageGranted = UsageReader.hasPermission(context)
        row(ui, card, "Akses penggunaan", if (usageGranted) "Aktif" else "Belum aktif", if (usageGranted) Status.ACTIVE else Status.INACTIVE) {
            open(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        ui.divider(card)
        val listenerEnabled = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
        row(ui, card, "Baca notifikasi", if (listenerEnabled) "Aktif" else "Belum aktif", if (listenerEnabled) Status.ACTIVE else Status.INACTIVE) {
            open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        ui.divider(card)
        val notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        row(ui, card, "Kirim notifikasi", if (notificationsEnabled) "Aktif" else "Belum aktif", if (notificationsEnabled) Status.ACTIVE else Status.INACTIVE) {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                launchNotificationPermission()
            } else {
                open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }
        }
        if (XiaomiAutostartHelper.isXiaomiDevice()) {
            ui.divider(card)
            row(ui, card, "Mulai otomatis", "Perlu diperiksa di pengaturan HP", Status.CHECK) {
                if (!XiaomiAutostartHelper.openAutostartSettings(context)) feedback("Menu tidak tersedia. Buka detail aplikasi di Pengaturan Android.")
            }
        }
        ui.divider(card)
        val exempt = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
        val batteryStatus = if (XiaomiAutostartHelper.isXiaomiDevice()) {
            "${if (exempt) "Pengecualian Android aktif" else "Mengikuti optimasi Android"} · Periksa di pengaturan HP"
        } else if (exempt) "Pengecualian Android aktif" else "Mengikuti optimasi Android"
        val batteryState = if (XiaomiAutostartHelper.isXiaomiDevice()) Status.CHECK else if (exempt) Status.ACTIVE else Status.INACTIVE
        row(ui, card, "Baterai tanpa pembatasan", batteryStatus, batteryState) {
            if (!XiaomiAutostartHelper.openBatterySaverSettings(context)) feedback("Menu tidak tersedia. Buka detail aplikasi di Pengaturan Android.")
        }
        parent.addView(ui.text(
            if (compact) "Akses ini opsional. Kamu bisa lanjut dan mengaturnya nanti."
            else "Pengecualian optimasi baterai Android tidak membuktikan pilihan HyperOS Tanpa pembatasan. Status khusus HP hanya dapat diperiksa di pengaturan perangkat.",
            13f, secondary = true
        ), ui.margin(top = 4, bottom = 0))
    }

    private fun row(ui: BuddyUi, parent: LinearLayout, title: String, status: String, state: Status, action: () -> Unit) {
        val labels = ui.column().apply {
            addView(ui.text(title, 16f, true))
            addView(ui.text(status, 13f, secondary = true), ui.margin(top = 3, bottom = 0))
        }
        parent.addView(LinearLayout(requireNotNull(fragment.context)).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ui.dp(64)
            isClickable = true
            isFocusable = true
            contentDescription = "$title. $status. Buka"
            setBackgroundResource(R.drawable.buddy_row_background)
            addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ImageView(context).apply {
                setImageResource(when (state) {
                    Status.ACTIVE -> android.R.drawable.checkbox_on_background
                    Status.INACTIVE -> android.R.drawable.checkbox_off_background
                    Status.CHECK -> android.R.drawable.ic_dialog_info
                })
                imageTintList = android.content.res.ColorStateList.valueOf(ui.color(if (state == Status.ACTIVE) R.color.buddy_action else R.color.buddy_sub))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)).apply { marginStart = ui.dp(12) })
            setOnClickListener { action() }
        }, ui.margin(bottom = 4))
    }

    private fun open(intent: Intent) {
        val context = fragment.context ?: return
        runCatching { fragment.startActivity(intent) }.onFailure {
            if (!XiaomiAutostartHelper.openAppDetails(context)) feedback("Buka Pengaturan Android, lalu pilih aplikasi Ramu.")
        }
    }

    private fun feedback(message: String) {
        fragment.context?.let { Toast.makeText(it, message, Toast.LENGTH_LONG).show() }
    }
}

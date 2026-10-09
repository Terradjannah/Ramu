package com.assistant.adi.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.assistant.adi.R
import com.assistant.adi.data.AppRepository
import com.assistant.adi.ui.DashboardActivity
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.*

object DashboardWidgetHelper {

    fun updateWidgetData(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, layoutId: Int) {
        val views = RemoteViews(context.packageName, layoutId)

        // Bind clickable zones to specific navigation sections
        when (layoutId) {
            R.layout.widget_layout_2x2 -> {
                setSectionIntent(context, views, R.id.widget_root, "battery")
            }
            R.layout.widget_layout_4x2 -> {
                setSectionIntent(context, views, R.id.widget_sec_battery, "battery")
                setSectionIntent(context, views, R.id.widget_sec_ram, "ram")
                setSectionIntent(context, views, R.id.widget_sec_network, "network")
            }
            R.layout.widget_layout_4x4 -> {
                setSectionIntent(context, views, R.id.widget_sec_battery, "battery")
                setSectionIntent(context, views, R.id.widget_sec_ram, "ram")
                setSectionIntent(context, views, R.id.widget_sec_screen, "screen")
                setSectionIntent(context, views, R.id.widget_sec_network, "network")
            }
        }

        // Query database synchronously inside a runBlocking scope (executed in broadcast receiver thread)
        runBlocking {
            val repository = AppRepository(context)

            // 1. Battery Log
            val battery = repository.latestBatteryLog.firstOrNull()
            if (battery != null) {
                if (layoutId == R.layout.widget_layout_2x2) {
                    views.setTextViewText(R.id.widget_battery, "Battery: ${battery.percentage}%")
                } else {
                    views.setTextViewText(R.id.widget_battery_pct, "${battery.percentage}%")
                    views.setTextViewText(R.id.widget_battery_temp, "${battery.temperature.toInt()}°C")
                }
            }

            // 2. RAM Log
            val ram = repository.getRamLogs(0).firstOrNull()?.lastOrNull()
            if (ram != null) {
                val ramPct = ((ram.usedRam.toDouble() / ram.totalRam) * 100).toInt()
                if (layoutId == R.layout.widget_layout_2x2) {
                    views.setTextViewText(R.id.widget_ram, "RAM: $ramPct%")
                } else {
                    views.setTextViewText(R.id.widget_ram_used, "$ramPct%")
                    views.setTextViewText(R.id.widget_cpu_usage, "Free: ${ram.availableRam / 1024}GB")
                }
            }

            // 3. Network Log
            val net = repository.getNetworkLogs(0).firstOrNull()?.lastOrNull()
            if (net != null) {
                val pingStr = if (net.ping > 0) "${net.ping}ms" else "Offline"
                if (layoutId == R.layout.widget_layout_2x2) {
                    views.setTextViewText(R.id.widget_ping, "Ping: $pingStr")
                } else {
                    views.setTextViewText(R.id.widget_ping_val, pingStr)
                    if (layoutId == R.layout.widget_layout_4x4) {
                        val dlStr = formatSpeed(net.downloadSpeed)
                        val ulStr = formatSpeed(net.uploadSpeed)
                        views.setTextViewText(R.id.widget_net_speeds, "↓ $dlStr  ↑ $ulStr")
                    }
                }
            }

            // 4. Screen Time (only for 4x2 and 4x4)
            if (layoutId != R.layout.widget_layout_2x2) {
                val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                val screenTimeMins = repository.getTotalScreenTime(todayStr).firstOrNull() ?: 0L
                views.setTextViewText(R.id.widget_screen_time, "${screenTimeMins}m")
            }
        }

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }

    private fun setSectionIntent(context: Context, views: RemoteViews, viewId: Int, sectionName: String) {
        val intent = Intent(context, DashboardActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("section", sectionName)
        }
        val requestCode = sectionName.hashCode()
        val pendingIntent = PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(viewId, pendingIntent)
    }

    private fun formatSpeed(speedKb: Double): String {
        return if (speedKb >= 1024.0) {
            String.format(Locale.US, "%.1fMB", speedKb / 1024.0)
        } else {
            String.format(Locale.US, "%.0fKB", speedKb)
        }
    }
}

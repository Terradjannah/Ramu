package com.assistant.adi.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

object XiaomiAutostartHelper {

    /**
     * Checks if current device is a Xiaomi, Redmi, or POCO device.
     */
    fun isXiaomiDevice(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        return manufacturer.contains("xiaomi") || brand.contains("xiaomi") ||
               manufacturer.contains("poco") || brand.contains("poco") ||
               manufacturer.contains("redmi") || brand.contains("redmi")
    }

    /**
     * Opens Xiaomi Autostart settings to allow background launch.
     */
    fun openAutostartSettings(context: Context): Boolean {
        val intents = listOf(
            Intent().setComponent(
                ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
            ),
            Intent().setComponent(
                ComponentName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
            ),
            Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT),
            Intent().setComponent(
                ComponentName("com.miui.securitycenter", "com.miui.securityscan.MainActivity")
            )
        )

        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return true
            } catch (_: Exception) {}
        }

        // Fallback to application details settings
        return openAppDetails(context)
    }

    /**
     * Opens Xiaomi Battery Saver settings for this app to set "No Restrictions".
     */
    fun openBatterySaverSettings(context: Context): Boolean {
        val intents = listOf(
            Intent().setComponent(
                ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
            ).putExtra("package_name", context.packageName)
             .putExtra("package_label", context.getString(com.assistant.adi.R.string.app_name)),

            Intent("miui.intent.action.POWER_HIDE_MODE_APP_LIST").addCategory(Intent.CATEGORY_DEFAULT)
                .putExtra("package_name", context.packageName),

            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
            },

            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        )

        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return true
            } catch (_: Exception) {}
        }

        return openAppDetails(context)
    }

    /**
     * Fallback helper opening App Details settings.
     */
    fun openAppDetails(context: Context): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}

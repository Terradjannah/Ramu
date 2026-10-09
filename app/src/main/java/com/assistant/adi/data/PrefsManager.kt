package com.assistant.adi.data

import android.content.Context
import android.content.SharedPreferences
import com.assistant.adi.ui.buddy.BuddyCharacter
import java.math.BigDecimal
import java.math.RoundingMode

data class InternetUsageSettings(
    val monthlyBudgetBytes: Long?,
    val targetDays: Int,
    val cycleStartDay: Int
)

class PrefsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("assistant_adi_prefs", Context.MODE_PRIVATE)

    fun registerInternetUsageSettingsListener(listener: () -> Unit): SharedPreferences.OnSharedPreferenceChangeListener {
        val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_MONTHLY_BUDGET_BYTES || key == KEY_INTERNET_TARGET_DAYS || key == KEY_INTERNET_CYCLE_START_DAY) {
                listener()
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        return preferenceListener
    }

    fun unregisterPreferenceListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    companion object {
        private const val KEY_REFRESH_INTERVAL = "refresh_interval"
        private const val KEY_BATTERY_LOW_THRESHOLD = "battery_low_threshold"
        private const val KEY_BATTERY_HIGH_THRESHOLD = "battery_high_threshold"
        private const val KEY_RAM_THRESHOLD = "ram_threshold"
        private const val KEY_PING_THRESHOLD = "ping_threshold"
        private const val KEY_AUTOCLEAR_NOTIF_DAYS = "autoclear_notif_days"
        private const val KEY_DARK_MODE = "dark_mode"
        private const val KEY_SELECTED_SENSORS = "selected_sensors"
        private const val KEY_LAST_KNOWN_BATTERY_PCT = "last_known_battery_pct"
        private const val KEY_WIDGET_SECTIONS = "widget_sections"
        private const val KEY_BUDDY_CHARACTER = "buddy_character_type"
        private const val KEY_BUDDY_AUDIO_MUTED = "buddy_audio_muted"
        private const val KEY_MONTHLY_BUDGET_BYTES = "monthly_budget_bytes"
        private const val KEY_INTERNET_TARGET_DAYS = "internet_target_days"
        private const val KEY_INTERNET_CYCLE_START_DAY = "internet_cycle_start_day"
        private const val DECIMAL_GIGABYTE_BYTES = 1_000_000_000L
    }

    var latencyProbeEnabled: Boolean
        get() = prefs.getBoolean("latency_probe_enabled", false)
        set(value) = prefs.edit().putBoolean("latency_probe_enabled", value).apply()

    var buddyDailyMinutes: Int
        get() = prefs.getInt("buddy_daily_minutes", 240)
        set(value) = prefs.edit().putInt("buddy_daily_minutes", value.coerceIn(0, 1440)).apply()
    var buddySessionMinutes: Int
        get() = prefs.getInt("buddy_session_minutes", 60)
        set(value) = prefs.edit().putInt("buddy_session_minutes", value.coerceIn(0, 1440)).apply()
    var buddyMotion: Boolean
        get() = prefs.getBoolean("buddy_motion", true)
        set(value) = prefs.edit().putBoolean("buddy_motion", value).apply()

    var buddyAudioMuted: Boolean
        get() = prefs.getBoolean(KEY_BUDDY_AUDIO_MUTED, false)
        set(value) = prefs.edit().putBoolean(KEY_BUDDY_AUDIO_MUTED, value).apply()

    fun getBuddyCharacter(): String {
        val storedCharacter = prefs.getString(KEY_BUDDY_CHARACTER, null)
        if (storedCharacter == BuddyCharacter.CAT.id || storedCharacter == BuddyCharacter.DUCK.id) {
            prefs.edit().putString(KEY_BUDDY_CHARACTER, BuddyCharacter.PANDA.id).apply()
        }
        return BuddyCharacter.PANDA.id
    }

    fun setBuddyCharacter(@Suppress("UNUSED_PARAMETER") type: String) {
        prefs.edit().putString(KEY_BUDDY_CHARACTER, BuddyCharacter.PANDA.id).apply()
    }

    fun getInternetUsageSettings(): InternetUsageSettings = InternetUsageSettings(
        monthlyBudgetBytes = prefs.takeIf { it.contains(KEY_MONTHLY_BUDGET_BYTES) }
            ?.getLong(KEY_MONTHLY_BUDGET_BYTES, 0L)
            ?.takeIf { it > 0L },
        targetDays = prefs.getInt(KEY_INTERNET_TARGET_DAYS, 30).coerceIn(1, 31),
        cycleStartDay = prefs.getInt(KEY_INTERNET_CYCLE_START_DAY, 1).coerceIn(1, 31)
    )

    fun saveInternetUsageSettings(
        monthlyBudgetBytes: Long?,
        targetDays: Int,
        cycleStartDay: Int
    ) {
        val editor = prefs.edit()
            .putInt(KEY_INTERNET_TARGET_DAYS, targetDays.coerceIn(1, 31))
            .putInt(KEY_INTERNET_CYCLE_START_DAY, cycleStartDay.coerceIn(1, 31))
        if (monthlyBudgetBytes != null && monthlyBudgetBytes > 0L) {
            editor.putLong(KEY_MONTHLY_BUDGET_BYTES, monthlyBudgetBytes)
        } else {
            editor.remove(KEY_MONTHLY_BUDGET_BYTES)
        }
        editor.apply()
    }

    fun saveInternetUsageSettingsFromGigabytes(
        monthlyBudgetGigabytes: String,
        targetDays: Int,
        cycleStartDay: Int
    ): Boolean {
        val monthlyBudgetBytes = decimalGigabytesToBytes(monthlyBudgetGigabytes) ?: return false
        saveInternetUsageSettings(monthlyBudgetBytes, targetDays, cycleStartDay)
        return true
    }

    fun clearInternetUsageMonthlyBudget() {
        prefs.edit().remove(KEY_MONTHLY_BUDGET_BYTES).apply()
    }

    private fun decimalGigabytesToBytes(value: String): Long? = try {
        BigDecimal(value.trim())
            .takeIf { it > BigDecimal.ZERO }
            ?.multiply(BigDecimal.valueOf(DECIMAL_GIGABYTE_BYTES))
            ?.setScale(0, RoundingMode.DOWN)
            ?.longValueExact()
            ?.takeIf { it > 0L }
    } catch (_: NumberFormatException) {
        null
    } catch (_: ArithmeticException) {
        null
    }

    var monitoringEnabled: Boolean
        get() = prefs.getBoolean("monitoring_enabled", true)
        set(value) = prefs.edit().putBoolean("monitoring_enabled", value).apply()
    var continuousMonitoring: Boolean
        get() = prefs.getBoolean("continuous_monitoring", false)
        set(value) = prefs.edit().putBoolean("continuous_monitoring", value).apply()
    var lastSampleTime: Long
        get() = prefs.getLong("last_sample", 0)
        set(value) = prefs.edit().putLong("last_sample", value).apply()
    var lastFailure: String
        get() = prefs.getString("last_failure", "").orEmpty()
        set(value) = prefs.edit().putString("last_failure", value).apply()
    var listenerConnected: Boolean
        get() = prefs.getBoolean("listener_connected", false)
        set(value) = prefs.edit().putBoolean("listener_connected", value).apply()
    var lastListenerEvent: Long
        get() = prefs.getLong("listener_event", 0)
        set(value) = prefs.edit().putLong("listener_event", value).apply()
    var logNotificationContent: Boolean
        get() = prefs.getBoolean("log_content", false)
        set(value) = prefs.edit().putBoolean("log_content", value).apply()
    var excludedPackages: Set<String>
        get() = prefs.getStringSet("excluded_packages", emptySet()).orEmpty()
        set(value) = prefs.edit().putStringSet("excluded_packages", value).apply()
    var themeMode: Int
        get() = prefs.getInt("theme_mode", if (prefs.contains(KEY_DARK_MODE)) { if(darkModeEnabled) 2 else 1 } else -1)
        set(value) = prefs.edit().putInt("theme_mode", value).apply()
    var batteryAlertState: String
        get() = prefs.getString("battery_alert_state", "").orEmpty()
        set(value) = prefs.edit().putString("battery_alert_state", value).apply()
    fun shouldAlert(key: String, cooldownMs: Long): Boolean {
        val now=System.currentTimeMillis(); val previous=prefs.getLong("alert_$key",0)
        if(now-previous < cooldownMs) return false
        prefs.edit().putLong("alert_$key",now).apply(); return true
    }

    var refreshIntervalMinutes: Int
        get() = prefs.getInt(KEY_REFRESH_INTERVAL, 15) // default 15 mins
        set(value) = prefs.edit().putInt(KEY_REFRESH_INTERVAL, value.coerceIn(15,60)).apply()

    var batteryLowThreshold: Int
        get() = prefs.getInt(KEY_BATTERY_LOW_THRESHOLD, 20)
        set(value) = prefs.edit().putInt(KEY_BATTERY_LOW_THRESHOLD, value).apply()

    var batteryHighThreshold: Int
        get() = prefs.getInt(KEY_BATTERY_HIGH_THRESHOLD, 80)
        set(value) = prefs.edit().putInt(KEY_BATTERY_HIGH_THRESHOLD, value).apply()

    var ramAlertThresholdMb: Int
        get() = prefs.getInt(KEY_RAM_THRESHOLD, 1024) // 1GB default
        set(value) = prefs.edit().putInt(KEY_RAM_THRESHOLD, value).apply()

    var pingAlertThresholdMs: Int
        get() = prefs.getInt(KEY_PING_THRESHOLD, 200) // 200ms default
        set(value) = prefs.edit().putInt(KEY_PING_THRESHOLD, value).apply()

    var autoClearNotifDays: Int
        get() = prefs.getInt(KEY_AUTOCLEAR_NOTIF_DAYS, 7) // 7 days default
        set(value) = prefs.edit().putInt(KEY_AUTOCLEAR_NOTIF_DAYS, value.coerceIn(1,365)).apply()

    var darkModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_DARK_MODE, true) // Dark mode default for POCO X8 Pro aesthetics
        set(value) = prefs.edit().putBoolean(KEY_DARK_MODE, value).apply()

    var selectedSensors: Set<String>
        get() = prefs.getStringSet(KEY_SELECTED_SENSORS, setOf("Lux", "Accelerometer", "Gyroscope", "BatteryTemp")) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_SELECTED_SENSORS, value).apply()

    var lastKnownBatteryPct: Int
        get() = prefs.getInt(KEY_LAST_KNOWN_BATTERY_PCT, -1)
        set(value) = prefs.edit().putInt(KEY_LAST_KNOWN_BATTERY_PCT, value).apply()

    var widgetSections: Set<String>
        get() = prefs.getStringSet(KEY_WIDGET_SECTIONS, setOf("battery", "ram", "network", "screen")) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_WIDGET_SECTIONS, value).apply()

    fun getAppLimitMinutes(packageName: String): Int {
        return prefs.getInt("limit_$packageName", -1) // -1 means no limit
    }

    fun setAppLimitMinutes(packageName: String, limitMinutes: Int) {
        prefs.edit().putInt("limit_$packageName", limitMinutes).apply()
    }

    var hasPromptedXiaomiOptimization: Boolean
        get() = prefs.getBoolean("prompted_xiaomi_optimization", false)
        set(value) = prefs.edit().putBoolean("prompted_xiaomi_optimization", value).apply()
}



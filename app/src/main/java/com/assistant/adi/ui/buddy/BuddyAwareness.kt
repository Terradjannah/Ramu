package com.assistant.adi.ui.buddy

import android.app.Application
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.assistant.adi.data.AppDatabase
import com.assistant.adi.data.PrefsManager
import com.assistant.adi.util.UsageReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*

data class BuddyAwareness(
    val usageGranted: Boolean, val usageAvailable: Boolean,
    val todayMinutes: Int?, val sessionMinutes: Int?, val appLimitReached: Boolean,
    val recentNotifications: Int?, val dailyGoalMinutes: Int, val sessionGoalMinutes: Int,
    val timestamp: Long
)

/** Usage events and notification counts are read only while the home screen is visible. */
class BuddyAwarenessViewModel(app: Application) : AndroidViewModel(app) {
    val awareness = flow {
        while (true) {
            val context = getApplication<Application>()
            val prefs = PrefsManager(context)
            val granted = runCatching { UsageReader.hasPermission(context) }.getOrDefault(false)
            val usage = if (granted) runCatching { UsageReader.read(context) }.getOrNull() else null
            val available = usage?.available == true && usage.granted
            val now = System.currentTimeMillis()
            val notificationAccess = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
            val count = if (notificationAccess && prefs.monitoringEnabled && prefs.listenerConnected) runCatching {
                val excludedNames = prefs.excludedPackages.flatMap { pkg ->
                    listOf(pkg, runCatching {
                        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
                    }.getOrDefault(pkg))
                }
                AppDatabase.getDatabase(context).notificationDao().countRecent(
                    now - 600_000, now, excludedNames
                )
            }.getOrNull() else null
            emit(BuddyAwareness(
                granted, available,
                if (available) (usage!!.screenTimeMillis / 60_000).toInt() else null,
                if (available) (usage!!.continuousScreenTimeMillis / 60_000).toInt() else null,
                available && usage!!.appMillis.any { (pkg, millis) ->
                    val limit = prefs.getAppLimitMinutes(pkg)
                    limit > 0 && millis >= limit * 60_000L
                }, count, prefs.buddyDailyMinutes, prefs.buddySessionMinutes, now
            ))
            delay(60_000)
        }
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), null)
}

fun screenDuration(minutes: Int): String = when {
    minutes < 60 -> "$minutes mnt"
    minutes % 60 == 0 -> "${minutes / 60} jam"
    else -> "${minutes / 60} j ${minutes % 60} m"
}

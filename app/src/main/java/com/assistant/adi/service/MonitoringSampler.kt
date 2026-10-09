package com.assistant.adi.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.assistant.adi.R
import com.assistant.adi.data.*
import com.assistant.adi.ui.DashboardActivity
import com.assistant.adi.util.InternetUsageCycle
import com.assistant.adi.util.InternetUsagePolicy
import com.assistant.adi.util.InternetUsageReader
import com.assistant.adi.util.InternetUsageWindow
import com.assistant.adi.widget.DashboardWidget2x2
import com.assistant.adi.widget.DashboardWidget4x2
import com.assistant.adi.widget.DashboardWidget4x4
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId
class MonitoringSampler(context: Context) : android.content.ContextWrapper(context.applicationContext) {
    private val repository = AppRepository(this)
    private val prefs = PrefsManager(this)
    companion object {
        private val sampleLock = Mutex()
        private const val CHANNEL_BATTERY = "battery_alerts"
        private const val CHANNEL_NETWORK = "network_alerts"
        private const val CHANNEL_SERVICE = "monitoring_service"
        private const val NOTIF_ID_BATTERY = 1001
        private const val NOTIF_ID_NETWORK = 1003
        private const val NOTIF_ID_SERVICE = 9999
    }


    suspend fun batteryEvent() = sampleLock.withLock {
        if (!prefs.monitoringEnabled) return@withLock
        createNotificationChannels()
        val battery = readBattery(System.currentTimeMillis()) ?: return@withLock
        updateChargeSessionForEvent(battery)
        publishBatteryAlert(battery)
    }

    suspend fun sampleIfDue(foregroundVisible: Boolean = false): SamplingResult = sampleLock.withLock {
        if (!SamplingCadence.shouldStart(prefs.monitoringEnabled, foregroundVisible)) {
            return@withLock if (prefs.monitoringEnabled) SamplingResult.ForegroundVisible else SamplingResult.Disabled
        }
        val now = System.currentTimeMillis()
        val intervalMillis = prefs.refreshIntervalMinutes.coerceIn(15, 60) * 60_000L
        val snapshot = repository.samplingSnapshot()
        if (!SamplingCadence.isDue(now, snapshot.lastCommittedAt, intervalMillis)) {
            return@withLock SamplingResult.AlreadyCurrent(snapshot.lastCommittedAt)
        }

        createNotificationChannels()
        val battery = readBattery(now) ?: return@withLock SamplingResult.Failed
        val ram = readRam(now)
        val network = readNetwork(now)
        val usage = com.assistant.adi.util.UsageReader.read(this)
        val internetUsageDaily = readInternetUsageDaily(now)
        val hourly = repository.reconcileHourlyScreenTimePreview(
            snapshot.usageEventsReconciledThrough.coerceAtLeast(now - 7L * 24 * 60 * 60 * 1000),
            now
        )
        val committed = repository.commitSamplingBatch(AppRepository.SamplingBatch(
            timestamp = now,
            intervalMillis = intervalMillis,
            battery = battery,
            ram = ram,
            network = network,
            appUsage = if (usage.granted && usage.available) usage.appMillis else emptyMap(),
            localDate = java.time.LocalDate.now().toString(),
            hourlyResult = hourly,
            internetUsageDaily = internetUsageDaily
        ))
        if (!committed) return@withLock SamplingResult.AlreadyCurrent(repository.samplingSnapshot().lastCommittedAt)

        publishBatteryAlert(battery)
        publishUsageAlerts(usage.appMillis, java.time.LocalDate.now().toString())
        val cutoff = now - prefs.autoClearNotifDays.coerceIn(1, 365) * 86_400_000L
        repository.cleanNotificationLogs(cutoff); repository.cleanBatteryLogs(cutoff)
        repository.cleanRamLogs(cutoff); repository.cleanNetworkLogs(cutoff)
        prefs.lastSampleTime = now
        prefs.lastFailure = ""
        updateWidgets(battery, ram, network)
        SamplingResult.Committed(now)
    }

    private suspend fun readInternetUsageDaily(now: Long): List<InternetUsageDaily> {
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val cycle = InternetUsagePolicy.activeCycle(today = today, zone = zone)
        val existing = repository.getInternetUsageDaily(cycle.startMillis, cycle.endMillis)

        return InternetUsageSamplingPlan.windowsToRead(now, cycle, existing).map { window ->
            val read = runCatching { InternetUsageReader.readDay(this, window, now) }.getOrNull()
            InternetUsageDaily(
                dayStartMillis = window.startMillis,
                localDate = window.localDate.toString(),
                zoneId = window.zoneId,
                wifiBytes = read?.usage?.wifiBytes,
                mobileBytes = read?.usage?.mobileBytes,
                sampledAt = now,
                finalized = window.localDate < today &&
                    read?.usage?.wifiBytes != null && read?.usage?.mobileBytes != null
            )
        }
    }
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            val serviceChan = NotificationChannel(CHANNEL_SERVICE, "Monitoring Service", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keeps the monitoring service alive"
                setShowBadge(false)
            }
            val batteryChan = NotificationChannel(CHANNEL_BATTERY, "Battery Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Alerts for low battery and stop charging limits"
            }
            val netChan = NotificationChannel(CHANNEL_NETWORK, "Network Alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Alerts for network disconnects and high pings"
            }

            manager.createNotificationChannel(serviceChan)
            manager.createNotificationChannel(batteryChan)
            manager.createNotificationChannel(netChan)
        }
    }

    private fun readBattery(timestamp: Long): BatteryLog? {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val pct = if (level >= 0 && scale > 0) (level * 100) / scale else -1
        
        val tempRaw = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
        val temp = tempRaw / 10f // convert to °C
        
        val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) // mV
        
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        val log = BatteryLog(
            timestamp = timestamp,
            percentage = pct,
            temperature = temp,
            voltage = voltage,
            isCharging = isCharging,
            temperatureValid = intent.hasExtra(BatteryManager.EXTRA_TEMPERATURE) && tempRaw in -200..1000
        )
        return log
    }

    private suspend fun updateChargeSessionForEvent(log: BatteryLog) {
        val latestSession = repository.getLatestChargeSession()
        if (log.isCharging) {
            if (latestSession == null || latestSession.endTime != 0L) {
                repository.insertChargeSession(
                    ChargeSession(
                        startTime = log.timestamp,
                        endTime = 0L,
                        startPercent = log.percentage,
                        endPercent = log.percentage
                    )
                )
            } else if (latestSession.endPercent != log.percentage) {
                repository.updateChargeSession(latestSession.copy(endPercent = log.percentage))
            }
        } else if (latestSession != null && latestSession.endTime == 0L) {
            repository.updateChargeSession(latestSession.copy(endTime = log.timestamp, endPercent = log.percentage))
        }
    }

    private fun publishBatteryAlert(log: BatteryLog) {
        val nextAlert = when {
            log.percentage < 0 -> "unknown"
            log.isCharging && log.percentage >= prefs.batteryHighThreshold -> "high"
            !log.isCharging && log.percentage <= prefs.batteryLowThreshold -> "low"
            else -> "normal"
        }
        if (nextAlert != prefs.batteryAlertState) {
            prefs.batteryAlertState = nextAlert
            if (nextAlert == "high") sendNotification(NOTIF_ID_BATTERY, CHANNEL_BATTERY, "Batas pengisian tercapai", "Baterai ${log.percentage}%. Anda dapat melepas pengisi daya.")
            if (nextAlert == "low") sendNotification(NOTIF_ID_BATTERY, CHANNEL_BATTERY, "Baterai rendah", "Baterai ${log.percentage}%. Siapkan pengisi daya.")
        }
        prefs.lastKnownBatteryPct = log.percentage
    }

    private fun readRam(timestamp: Long): RamLog {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memoryInfo = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(memoryInfo)

        val totalRam = memoryInfo.totalMem / (1024 * 1024)
        val availRam = memoryInfo.availMem / (1024 * 1024)
        val usedRam = totalRam - availRam

        val log = RamLog(
            timestamp = timestamp,
            usedRam = usedRam,
            availableRam = availRam,
            totalRam = totalRam
        )
        return log
    }

    private suspend fun readNetwork(timestamp: Long): NetworkLog {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        var netType = "offline"
        var rxSpeed = 0.0
        var txSpeed = 0.0
        var rxValid = false
        var txValid = false
        var ping = -1
        
        val activeNetwork = cm.activeNetwork
        val capabilities = cm.getNetworkCapabilities(activeNetwork)
        val hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        
        if (capabilities != null && hasInternet) {
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                netType = "wifi"
            } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                netType = "mobile"
            }
            
            // Passive bandwidth estimation (Kbps to KB/s)
            val start = android.os.SystemClock.elapsedRealtime()
            val rx = android.net.TrafficStats.getTotalRxBytes()
            val tx = android.net.TrafficStats.getTotalTxBytes()
            delay(500)
            val elapsed = android.os.SystemClock.elapsedRealtime() - start
            val measuredRx = com.assistant.adi.util.SampleMath.measuredKilobytesPerSecond(rx, android.net.TrafficStats.getTotalRxBytes(), elapsed)
            val measuredTx = com.assistant.adi.util.SampleMath.measuredKilobytesPerSecond(tx, android.net.TrafficStats.getTotalTxBytes(), elapsed)
            rxValid = measuredRx != null
            txValid = measuredTx != null
            rxSpeed = measuredRx ?: 0.0
            txSpeed = measuredTx ?: 0.0
            
            // External latency checks are opt-in; passive monitoring needs no request.
            if (prefs.latencyProbeEnabled) ping = measureLatencyHttp()
        }

        val log = NetworkLog(
            timestamp = timestamp,
            type = netType,
            downloadSpeed = rxSpeed,
            uploadSpeed = txSpeed,
            ping = ping,
            downloadSpeedValid = rxValid,
            uploadSpeedValid = txValid
        )
        if (netType == "offline") {
            sendNotification(
                NOTIF_ID_NETWORK,
                CHANNEL_NETWORK,
                "Device Offline",
                "Internet connection was lost."
            )
        }

        return log
    }

    private suspend fun measureLatencyHttp(): Int = withContext(Dispatchers.IO) {
        var connection: java.net.HttpURLConnection? = null
        try {
            val start = System.currentTimeMillis()
            val url = java.net.URL("https://www.google.com/generate_204")
            connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "HEAD"
            connection.connectTimeout = 2000
            connection.readTimeout = 2000
            
            val responseCode = connection.responseCode
            if (responseCode == 200 || responseCode == 204) {
                (System.currentTimeMillis() - start).toInt()
            } else {
                -1
            }
        } catch (e: Exception) {
            -1
        } finally {
            connection?.disconnect()
        }
    }


    private fun getAppNameFromPackage(packageName: String): String {
        return try {
            val pm = packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            packageName.substringAfterLast(".")
        }
    }

    private fun publishUsageAlerts(appMillis: Map<String, Long>, date: String) {
        appMillis.forEach { (pkg, millis) ->
            val minutes = millis / 60_000
            val limit = prefs.getAppLimitMinutes(pkg)
            if (limit > 0 && minutes >= limit && prefs.shouldAlert("limit_${date}_$pkg", 24 * 60 * 60 * 1000L)) {
                sendNotification(pkg.hashCode(), CHANNEL_NETWORK, "Batas aplikasi tercapai", "${getAppNameFromPackage(pkg)} telah digunakan $minutes menit.")
            }
        }
    }

    private fun sendNotification(id: Int, channel: String, title: String, content: String) {
        if (!prefs.shouldAlert("$channel:$title", 30*60*1000L)) return
        val intent = Intent(this, DashboardActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 
            0, 
            intent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, channel)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        try {
            NotificationManagerCompat.from(this).notify(id, builder.build())
        } catch (e: SecurityException) {
            // Permission missing
        }
    }

    private fun updateWidgets(battery: BatteryLog?, ram: RamLog?, network: NetworkLog?) {
        val appWidgetManager = AppWidgetManager.getInstance(this)
        
        // 2x2 widget update
        val ids2x2 = appWidgetManager.getAppWidgetIds(ComponentName(this, DashboardWidget2x2::class.java))
        if (ids2x2.isNotEmpty()) {
            val intent = Intent(this, DashboardWidget2x2::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids2x2)
            }
            sendBroadcast(intent)
        }

        // 4x2 widget update
        val ids4x2 = appWidgetManager.getAppWidgetIds(ComponentName(this, DashboardWidget4x2::class.java))
        if (ids4x2.isNotEmpty()) {
            val intent = Intent(this, DashboardWidget4x2::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids4x2)
            }
            sendBroadcast(intent)
        }

        // 4x4 widget update
        val ids4x4 = appWidgetManager.getAppWidgetIds(ComponentName(this, DashboardWidget4x4::class.java))
        if (ids4x4.isNotEmpty()) {
            val intent = Intent(this, DashboardWidget4x4::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids4x4)
            }
            sendBroadcast(intent)
        }
    }
}

sealed interface SamplingResult {
    data class Committed(val timestamp: Long) : SamplingResult
    data class AlreadyCurrent(val lastCommittedAt: Long) : SamplingResult
    data object ForegroundVisible : SamplingResult
    data object Disabled : SamplingResult
    data object Failed : SamplingResult
}

object SamplingCadence {
    fun shouldStart(monitoringEnabled: Boolean, foregroundVisible: Boolean): Boolean =
        monitoringEnabled && !foregroundVisible

    fun isDue(now: Long, lastCommittedAt: Long, intervalMillis: Long): Boolean =
        lastCommittedAt <= 0L || now - lastCommittedAt >= intervalMillis
}

internal object InternetUsageSamplingPlan {
    fun windowsToRead(
        nowMillis: Long,
        cycle: InternetUsageCycle,
        existing: List<InternetUsageDaily>
    ): List<InternetUsageWindow> {
        val zone = ZoneId.of(cycle.zoneId)
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val storedByDayStart = existing.associateBy { it.dayStartMillis }
        val windows = mutableListOf<InternetUsageWindow>()
        var date = cycle.startDate

        while (date <= today) {
            val window = InternetUsagePolicy.dayWindow(date, zone)
            val stored = storedByDayStart[window.startMillis]
            if (window.startMillis < nowMillis &&
                (date == today || stored == null || !stored.finalized)
            ) {
                windows += window
            }
            date = date.plusDays(1)
        }
        return windows
    }
}


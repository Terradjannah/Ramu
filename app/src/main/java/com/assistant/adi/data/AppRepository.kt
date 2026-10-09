package com.assistant.adi.data

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.UUID
import com.assistant.adi.util.UsageReader
import com.assistant.adi.util.InternetUsagePolicy
import java.time.LocalDate
import java.time.ZoneId

class AppRepository(context: Context, private val db: AppDatabase = AppDatabase.getDatabase(context.applicationContext)) {
    private val context = context.applicationContext

    private val batteryDao = db.batteryDao()
    private val chargeSessionDao = db.chargeSessionDao()
    private val ramDao = db.ramDao()
    private val networkDao = db.networkDao()
    private val notificationDao = db.notificationDao()
    private val screenTimeDao = db.screenTimeDao()
    private val internetUsageDailyDao = db.internetUsageDailyDao()
    private val historyDao = db.monitorHistoryDao()

    private fun <T> historyPage(input: MonitorHistoryInput, rows: Flow<List<T>>): Flow<MonitorHistoryPage<T>> {
        val zone = ZoneId.systemDefault()
        return combine(rows, historyDao.count(MonitorHistorySql.count(input, zone)),
            historyDao.bounds(MonitorHistorySql.bounds(input.metric))) { records, count, bounds ->
            MonitorHistoryPage(records, count, bounds, input.page)
        }
    }

    fun batteryHistory(input: MonitorHistoryInput): Flow<MonitorHistoryPage<BatteryLog>> {
        require(input.metric == HistoryMetric.BATTERY)
        return historyPage(input, historyDao.battery(MonitorHistorySql.page(input, ZoneId.systemDefault())))
    }

    fun ramHistory(input: MonitorHistoryInput): Flow<MonitorHistoryPage<RamLog>> {
        require(input.metric == HistoryMetric.RAM)
        return historyPage(input, historyDao.ram(MonitorHistorySql.page(input, ZoneId.systemDefault())))
    }

    fun networkHistory(input: MonitorHistoryInput): Flow<MonitorHistoryPage<NetworkLog>> {
        require(input.metric == HistoryMetric.NETWORK)
        return historyPage(input, historyDao.network(MonitorHistorySql.page(input, ZoneId.systemDefault())))
    }

    fun screenTimeDateHistory(input: MonitorHistoryInput): Flow<MonitorHistoryPage<ScreenTimeTrend>> {
        require(input.metric == HistoryMetric.SCREEN_TIME)
        return historyPage(input, historyDao.screenTime(MonitorHistorySql.page(input, ZoneId.systemDefault())))
    }

    fun internetHistory(input: MonitorHistoryInput): Flow<MonitorHistoryPage<InternetHistorySlot>> {
        require(input.metric == HistoryMetric.INTERNET)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val (requestedStart, requestedEnd) = if (input.range == HistoryRange.ALL) {
            val cycle = InternetUsagePolicy.activeCycle(cycleStartDay = PrefsManager(context).getInternetUsageSettings().cycleStartDay)
            cycle.startDate to cycle.endDate.minusDays(1)
        } else input.dates(today)
        val end = minOf(requestedEnd ?: today, today)
        val start = requestedStart ?: end.minusDays(30)
        require(!start.isAfter(end) && !start.isBefore(end.minusDays(30))) { "Internet history is limited to 31 dates" }
        val rows = historyDao.internet(MonitorHistorySql.internetWindow(start, end))
        return combine(rows, historyDao.bounds(MonitorHistorySql.bounds(HistoryMetric.INTERNET))) { saved, bounds ->
            val byDate = saved.groupBy { it.localDate }
            val slots = generateSequence(start) { it.plusDays(1).takeIf { date -> !date.isAfter(end) } }
                .flatMap { date ->
                    val records = byDate[date.toString()].orEmpty()
                    if (records.isEmpty()) sequenceOf(InternetHistorySlot(date, null))
                    else records.asSequence().map { InternetHistorySlot(date, it) }
                }.filter { slot -> when (input.filter) {
                    HistoryFilter.ALL -> true
                    HistoryFilter.MISSING -> slot.record == null
                    HistoryFilter.COMPLETE -> slot.record?.let { it.wifiBytes != null && it.mobileBytes != null } == true
                    HistoryFilter.PARTIAL -> slot.record?.let { it.wifiBytes == null || it.mobileBytes == null } == true
                    else -> false
                } }.toList()
            val sorted = slots.sortedWith(compareBy<InternetHistorySlot> { slot -> when (input.sort) {
                HistorySort.DATE -> slot.date.toEpochDay()
                HistorySort.WIFI -> slot.record?.wifiBytes
                HistorySort.MOBILE -> slot.record?.mobileBytes
                HistorySort.TOTAL -> slot.record?.let { row -> if (row.wifiBytes != null && row.mobileBytes != null) row.wifiBytes + row.mobileBytes else null }
                else -> error("Unsupported Internet sort")
            } == null }.thenComparator { a, b ->
                val va = when (input.sort) { HistorySort.DATE -> a.date.toEpochDay(); HistorySort.WIFI -> a.record?.wifiBytes; HistorySort.MOBILE -> a.record?.mobileBytes; else -> a.record?.let { if (it.wifiBytes != null && it.mobileBytes != null) it.wifiBytes + it.mobileBytes else null } }
                val vb = when (input.sort) { HistorySort.DATE -> b.date.toEpochDay(); HistorySort.WIFI -> b.record?.wifiBytes; HistorySort.MOBILE -> b.record?.mobileBytes; else -> b.record?.let { if (it.wifiBytes != null && it.mobileBytes != null) it.wifiBytes + it.mobileBytes else null } }
                val ordered = if (va == null || vb == null) 0 else va.compareTo(vb) * if (input.direction == HistoryDirection.ASC) 1 else -1
                if (ordered != 0) ordered else compareValues(b.date, a.date).takeIf { it != 0 } ?: compareValues(a.record?.id, b.record?.id)
            })
            MonitorHistoryPage(sorted.drop(input.page * MonitorHistoryPage.PAGE_SIZE).take(MonitorHistoryPage.PAGE_SIZE), sorted.size, bounds, input.page)
        }
    }

    // Battery
    val latestBatteryLog: Flow<BatteryLog?> = batteryDao.getLatestLog()
    fun getBatteryLogs(since: Long): Flow<List<BatteryLog>> = batteryDao.getLogsSince(since)
    val allChargeSessions: Flow<List<ChargeSession>> = chargeSessionDao.getAllSessions()

    suspend fun insertBatteryLog(log: BatteryLog) = withContext(Dispatchers.IO) {
        batteryDao.insertLog(log)
    }

    suspend fun insertChargeSession(session: ChargeSession) = withContext(Dispatchers.IO) {
        chargeSessionDao.insertSession(session)
    }
    
    suspend fun getLatestChargeSession(): ChargeSession? = withContext(Dispatchers.IO) {
        chargeSessionDao.getLatestSession()
    }

    suspend fun updateChargeSession(session: ChargeSession) = withContext(Dispatchers.IO) {
        chargeSessionDao.updateSession(session)
    }

    suspend fun cleanBatteryLogs(threshold: Long) = withContext(Dispatchers.IO) {
        batteryDao.deleteLogsOlderThan(threshold)
    }

    // RAM
    fun getRamLogs(since: Long): Flow<List<RamLog>> = ramDao.getLogsSince(since)
    suspend fun insertRamLog(log: RamLog) = withContext(Dispatchers.IO) {
        ramDao.insertLog(log)
    }
    suspend fun cleanRamLogs(threshold: Long) = withContext(Dispatchers.IO) {
        ramDao.deleteLogsOlderThan(threshold)
    }

    // Network
    fun getNetworkLogs(since: Long): Flow<List<NetworkLog>> = networkDao.getLogsSince(since)
    suspend fun insertNetworkLog(log: NetworkLog) = withContext(Dispatchers.IO) {
        networkDao.insertLog(log)
    }
    suspend fun cleanNetworkLogs(threshold: Long) = withContext(Dispatchers.IO) {
        networkDao.deleteLogsOlderThan(threshold)
    }

    // Notifications
    val allNotificationLogs: Flow<List<NotificationLog>> = notificationDao.getAllLogs()
    val notificationStats: Flow<List<AppNotificationStat>> = notificationDao.getNotificationStats()
    fun searchNotifications(query: String): Flow<List<NotificationLog>> = notificationDao.searchLogs(query)
    fun getNotificationsByApp(appName: String): Flow<List<NotificationLog>> = notificationDao.getLogsByApp(appName)
    
    suspend fun insertNotificationLog(log: NotificationLog) = withContext(Dispatchers.IO) {
        notificationDao.saveLatest(log)
    }
    suspend fun saveNotificationsBatch(notifications: List<NotificationLog>) = withContext(Dispatchers.IO) {
        notificationDao.insertAllLogs(notifications)
    }
    suspend fun cleanNotificationLogs(threshold: Long) = withContext(Dispatchers.IO) {
        notificationDao.deleteLogsOlderThan(threshold)
    }
    suspend fun clearAllNotifications() = withContext(Dispatchers.IO) {
        notificationDao.clearAll()
    }

    // Screen Time
    fun getScreenTimeLogs(date: String): Flow<List<ScreenTimeLog>> = screenTimeDao.getLogsForDate(date)
    fun getTotalScreenTime(date: String): Flow<Long?> = screenTimeDao.getTotalScreenTimeForDate(date)
    fun screenTimeTrendBetween(startDate: String, endDate: String): Flow<List<ScreenTimeTrend>> =
        screenTimeDao.getTrendBetween(startDate, endDate)
    val screenTimeHistory: Flow<List<ScreenTimeTrend>> = screenTimeDao.getAllHistory()
    fun getHourlyScreenTime(date: String): Flow<List<ScreenTimeHourly>> = screenTimeDao.getHourlyForDate(date)
    fun hourlyScreenTimePattern(startDate: String, endDate: String): Flow<List<HourlyUsagePattern>> =
        screenTimeDao.getHourlyPattern(startDate, endDate)

    suspend fun setAppScreenTime(date:String,pkg:String,minutes:Long) = db.withTransaction {
        screenTimeDao.setAppScreenTime(date, pkg, minutes)
    }

    /** Replaces hour buckets from absolute UsageEvents data; it never adds repeated reads. */
    suspend fun reconcileHourlyScreenTime(startMillis: Long, endMillis: Long): UsageReader.RangeResult =
        withContext(Dispatchers.IO) {
            val result = UsageReader.readRange(context, startMillis, endMillis)
            if (!result.granted || !result.available) return@withContext result
            db.withTransaction {
                result.buckets.forEach { bucket ->
                    val replacement = ScreenTimeHourly(
                            bucketStartMillis = bucket.startMillis,
                            localDate = bucket.localDate,
                            localHour = bucket.localHour,
                            zoneId = bucket.zoneId,
                            offsetMinutes = bucket.offsetMinutes,
                            usageMillis = bucket.usageMillis,
                            coveredMillis = bucket.coveredMillis
                        )
                    val existing = screenTimeDao.getHourly(bucket.startMillis)
                    if (existing == null || replacement.coveredMillis >= existing.coveredMillis) {
                        screenTimeDao.upsertHourly(replacement)
                    }
                }
                val previous = db.samplingStateDao().state() ?: SamplingState()
                db.samplingStateDao().save(previous.copy(usageEventsReconciledThrough = endMillis))
            }
            result
        }

    data class SamplingSnapshot(
        val lastCommittedAt: Long,
        val usageEventsReconciledThrough: Long
    )

    data class SamplingBatch(
        val timestamp: Long,
        val intervalMillis: Long,
        val battery: BatteryLog,
        val ram: RamLog,
        val network: NetworkLog,
        val appUsage: Map<String, Long>,
        val localDate: String,
        val hourlyResult: UsageReader.RangeResult,
        val internetUsageDaily: List<InternetUsageDaily> = emptyList()
    )

    suspend fun samplingSnapshot(): SamplingSnapshot = withContext(Dispatchers.IO) {
        val state = db.samplingStateDao().state() ?: SamplingState()
        SamplingSnapshot(state.lastCommittedAt, state.usageEventsReconciledThrough)
    }

    suspend fun reconcileHourlyScreenTimePreview(startMillis: Long, endMillis: Long): UsageReader.RangeResult =
        withContext(Dispatchers.IO) { UsageReader.readRange(context, startMillis, endMillis) }

    /** Commits one due sample and its durable cadence marker together. */
    suspend fun commitSamplingBatch(batch: SamplingBatch): Boolean = withContext(Dispatchers.IO) {
        db.withTransaction {
            val stateDao = db.samplingStateDao()
            val state = stateDao.state() ?: SamplingState()
            if (batch.timestamp - state.lastCommittedAt < batch.intervalMillis) return@withTransaction false

            batteryDao.insertLog(batch.battery)
            ramDao.insertLog(batch.ram)
            networkDao.insertLog(batch.network)
            updateChargeSessionFor(batch.battery)
            batch.internetUsageDaily.forEach { day ->
                validateInternetUsageDaily(day)
                internetUsageDailyDao.upsert(day)
            }
            batch.appUsage.forEach { (pkg, millis) ->
                screenTimeDao.setAppScreenTime(batch.localDate, pkg, millis / 60_000)
            }
            val nextReconciledThrough = if (batch.hourlyResult.granted && batch.hourlyResult.available) {
                batch.hourlyResult.buckets.forEach { bucket ->
                    val replacement = ScreenTimeHourly(
                        bucketStartMillis = bucket.startMillis,
                        localDate = bucket.localDate,
                        localHour = bucket.localHour,
                        zoneId = bucket.zoneId,
                        offsetMinutes = bucket.offsetMinutes,
                        usageMillis = bucket.usageMillis,
                        coveredMillis = bucket.coveredMillis
                    )
                    val existing = screenTimeDao.getHourly(bucket.startMillis)
                    if (existing == null || replacement.coveredMillis >= existing.coveredMillis) {
                        screenTimeDao.upsertHourly(replacement)
                    }
                }
                batch.timestamp
            } else {
                state.usageEventsReconciledThrough
            }
            stateDao.save(state.copy(
                lastCommittedAt = batch.timestamp,
                usageEventsReconciledThrough = nextReconciledThrough
            ))
            true
        }
    }

    suspend fun getInternetUsageDaily(startMillis: Long, endMillis: Long): List<InternetUsageDaily> =
        withContext(Dispatchers.IO) { internetUsageDailyDao.getRange(startMillis, endMillis) }

    fun observeInternetUsageDaily(startMillis: Long, endMillis: Long): Flow<List<InternetUsageDaily>> =
        internetUsageDailyDao.observeRange(startMillis, endMillis)

    suspend fun getUnfinalizedInternetUsageDays(startMillis: Long, endMillis: Long): List<InternetUsageDaily> =
        withContext(Dispatchers.IO) { internetUsageDailyDao.getUnfinalizedInRange(startMillis, endMillis) }

    suspend fun getStoredInternetUsageDayStarts(startMillis: Long, endMillis: Long): List<Long> =
        withContext(Dispatchers.IO) { internetUsageDailyDao.getStoredDayStarts(startMillis, endMillis) }

    private fun validateInternetUsageDaily(day: InternetUsageDaily) {
        require(day.id >= 0) { "Internet usage ID tidak valid" }
        require(day.wifiBytes == null || day.wifiBytes >= 0) { "Wi-Fi bytes tidak valid" }
        require(day.mobileBytes == null || day.mobileBytes >= 0) { "Mobile bytes tidak valid" }
        val date = LocalDate.parse(day.localDate)
        val zone = ZoneId.of(day.zoneId)
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        require(day.dayStartMillis == start) { "Awal hari internet tidak valid" }
        if (day.finalized) {
            require(day.wifiBytes != null && day.mobileBytes != null) { "Hari final memerlukan dua transport" }
            val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            require(day.sampledAt >= dayEnd) { "Hari final belum berakhir" }
        }
    }

    suspend fun beginMonitoringCoverage(activatedAt: Long) = withContext(Dispatchers.IO) {
        db.withTransaction {
            val stateDao = db.samplingStateDao()
            val state = stateDao.state() ?: SamplingState()
            stateDao.save(state.copy(usageEventsReconciledThrough = activatedAt))
        }
    }

    private suspend fun updateChargeSessionFor(battery: BatteryLog) {
        val latest = chargeSessionDao.getLatestSession()
        if (battery.isCharging) {
            if (latest == null || latest.endTime != 0L) {
                chargeSessionDao.insertSession(ChargeSession(
                    startTime = battery.timestamp,
                    endTime = 0L,
                    startPercent = battery.percentage,
                    endPercent = battery.percentage
                ))
            } else if (latest.endPercent != battery.percentage) {
                chargeSessionDao.updateSession(latest.copy(endPercent = battery.percentage))
            }
        } else if (latest != null && latest.endTime == 0L) {
            chargeSessionDao.updateSession(latest.copy(endTime = battery.timestamp, endPercent = battery.percentage))
        }
    }

    // --- CSV EXPORT ---
    suspend fun exportDataToCSV(): List<File> = withContext(Dispatchers.IO) {
        val exportRoot = File(context.cacheDir, "exports")
        val exportDir = File(exportRoot, "${System.currentTimeMillis()}-${UUID.randomUUID()}")
        check(exportDir.mkdirs()) { "Tidak dapat membuat folder ekspor" }

        try {
            val exports = listOf(
                CsvExport.write(exportDir, "battery.csv", listOf("timestamp_epoch_millis", "percentage_percent", "temperature_celsius", "voltage_millivolts", "is_charging", "temperature_valid"),
                    batteryDao.getAllForExport().asSequence().map { listOf(it.timestamp, it.percentage, it.temperature, it.voltage, it.isCharging, it.temperatureValid) }),
                CsvExport.write(exportDir, "charge_sessions.csv", listOf("start_timestamp_epoch_millis", "end_timestamp_epoch_millis", "start_percentage_percent", "end_percentage_percent"),
                    chargeSessionDao.getAllForExport().asSequence().map { listOf(it.startTime, it.endTime, it.startPercent, it.endPercent) }),
                CsvExport.write(exportDir, "ram.csv", listOf("timestamp_epoch_millis", "used_ram_megabytes", "available_ram_megabytes", "total_ram_megabytes"),
                    ramDao.getAllForExport().asSequence().map { listOf(it.timestamp, it.usedRam, it.availableRam, it.totalRam) }),
                CsvExport.write(exportDir, "network.csv", listOf("timestamp_epoch_millis", "network_type", "download_speed_kilobytes_per_second", "upload_speed_kilobytes_per_second", "ping_milliseconds", "download_speed_valid", "upload_speed_valid"),
                    networkDao.getAllForExport().asSequence().map { listOf(it.timestamp, it.type, it.downloadSpeed, it.uploadSpeed, it.ping, it.downloadSpeedValid, it.uploadSpeedValid) }),
                CsvExport.write(exportDir, "screen_time_apps.csv", listOf("local_date", "app_package", "usage_minutes"),
                    screenTimeDao.getAllAppsForExport().asSequence().map { listOf(it.date, it.appPackage, it.usageMinutes) }),
                CsvExport.write(exportDir, "screen_time_hourly.csv", listOf("bucket_start_timestamp_epoch_millis", "local_date", "local_hour", "zone_id", "offset_minutes", "usage_millis", "covered_millis"),
                    screenTimeDao.getAllHourlyForExport().asSequence().map { listOf(it.bucketStartMillis, it.localDate, it.localHour, it.zoneId, it.offsetMinutes, it.usageMillis, it.coveredMillis) })
            )
            exports
        } catch (error: Exception) {
            exportDir.deleteRecursively()
            throw error
        }
    }

    // --- DAILY SUMMARY METRICS ---
    suspend fun getDailySummaryMetrics(): DailySummaryMetrics = withContext(Dispatchers.IO) {
        val calendar = java.util.Calendar.getInstance()
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        val startOfDay = calendar.timeInMillis
        val currentDateStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

        val avgTemp = batteryDao.getAvgTempToday(startOfDay) ?: 0f
        val maxTemp = batteryDao.getMaxTempToday(startOfDay) ?: 0f
        val avgRam = ramDao.getAvgUsedRamToday(startOfDay)?.toLong() ?: 0L
        val maxRam = ramDao.getPeakUsedRamToday(startOfDay) ?: 0L
        val notifCount = notificationDao.getTotalNotificationsToday(startOfDay)
        val topApp = screenTimeDao.getTopAppForDate(currentDateStr)

        DailySummaryMetrics(
            avgBatteryTemp = avgTemp,
            maxBatteryTemp = maxTemp,
            avgUsedRamMb = avgRam,
            peakUsedRamMb = maxRam,
            totalNotifications = notifCount,
            topAppUsage = topApp
        )
    }

    suspend fun backupDatabase(): Boolean = withContext(Dispatchers.IO) {
        runCatching { DatabaseBackup(context,db).backup(); true }.getOrDefault(false)
    }
    suspend fun restoreDatabase(): Boolean = withContext(Dispatchers.IO) {
        runCatching { DatabaseBackup(context,db).restore(); true }.getOrDefault(false)
    }
}

internal object CsvExport {
    fun write(directory: File, name: String, header: List<String>, rows: Sequence<List<Any?>>): File {
        val file = File(directory, name)
        OutputStreamWriter(FileOutputStream(file), StandardCharsets.UTF_8).use { writer ->
            writer.appendRow(header)
            rows.forEach { writer.appendRow(it) }
        }
        return file
    }

    private fun Appendable.appendRow(values: List<Any?>) {
        append(values.joinToString(",") { it.toCsvField() })
        append('\n')
    }

    private fun Any?.toCsvField(): String {
        val value = toString()
        return if (value.any { it == ',' || it == '\"' || it == '\n' || it == '\r' }) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
    }
}

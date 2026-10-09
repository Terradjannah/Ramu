package com.assistant.adi.data

import androidx.room.*
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

data class AppNotificationStat(
    val appName: String,
    val count: Int
)

data class ScreenTimeTrend(
    val date: String,
    val totalMinutes: Long
)

data class HourlyUsagePattern(val localHour: Int, val averageMinutes: Double, val observedDays: Int)

data class DailySummaryMetrics(
    val avgBatteryTemp: Float,
    val maxBatteryTemp: Float,
    val avgUsedRamMb: Long,
    val peakUsedRamMb: Long,
    val totalNotifications: Int,
    val topAppUsage: String?
)

@Dao
interface MonitorHistoryDao {
    @RawQuery(observedEntities = [BatteryLog::class])
    fun battery(query: SupportSQLiteQuery): Flow<List<BatteryLog>>

    @RawQuery(observedEntities = [RamLog::class])
    fun ram(query: SupportSQLiteQuery): Flow<List<RamLog>>

    @RawQuery(observedEntities = [NetworkLog::class])
    fun network(query: SupportSQLiteQuery): Flow<List<NetworkLog>>

    @RawQuery(observedEntities = [ScreenTimeLog::class])
    fun screenTime(query: SupportSQLiteQuery): Flow<List<ScreenTimeTrend>>

    @RawQuery(observedEntities = [InternetUsageDaily::class])
    fun internet(query: SupportSQLiteQuery): Flow<List<InternetUsageDaily>>

    @RawQuery(observedEntities = [BatteryLog::class, RamLog::class, NetworkLog::class, ScreenTimeLog::class, InternetUsageDaily::class])
    fun count(query: SupportSQLiteQuery): Flow<Int>

    @RawQuery(observedEntities = [BatteryLog::class, RamLog::class, NetworkLog::class, ScreenTimeLog::class, InternetUsageDaily::class])
    fun bounds(query: SupportSQLiteQuery): Flow<RetainedBounds>
}

@Dao
interface BatteryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: BatteryLog)

    @Query("SELECT * FROM battery_logs WHERE timestamp >= :sinceTimestamp ORDER BY timestamp ASC")
    fun getLogsSince(sinceTimestamp: Long): Flow<List<BatteryLog>>

    @Query("SELECT * FROM battery_logs ORDER BY timestamp ASC, id ASC")
    suspend fun getAllForExport(): List<BatteryLog>

    @Query("SELECT * FROM battery_logs ORDER BY timestamp DESC LIMIT 1")
    fun getLatestLog(): Flow<BatteryLog?>

    @Query("DELETE FROM battery_logs WHERE timestamp < :thresholdTimestamp")
    suspend fun deleteLogsOlderThan(thresholdTimestamp: Long)
    
    @Query("SELECT AVG(temperature) FROM battery_logs WHERE timestamp >= :startOfDayTimestamp")
    suspend fun getAvgTempToday(startOfDayTimestamp: Long): Float?
    
    @Query("SELECT MAX(temperature) FROM battery_logs WHERE timestamp >= :startOfDayTimestamp")
    suspend fun getMaxTempToday(startOfDayTimestamp: Long): Float?
}

@Dao
interface ChargeSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: ChargeSession)

    @Query("SELECT * FROM charge_sessions ORDER BY startTime DESC")
    fun getAllSessions(): Flow<List<ChargeSession>>

    @Query("SELECT * FROM charge_sessions ORDER BY startTime ASC, id ASC")
    suspend fun getAllForExport(): List<ChargeSession>

    @Query("SELECT * FROM charge_sessions ORDER BY startTime DESC LIMIT 1")
    suspend fun getLatestSession(): ChargeSession?

    @Update
    suspend fun updateSession(session: ChargeSession)
}

@Dao
interface RamDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: RamLog)

    @Query("SELECT * FROM ram_logs WHERE timestamp >= :sinceTimestamp ORDER BY timestamp ASC")
    fun getLogsSince(sinceTimestamp: Long): Flow<List<RamLog>>

    @Query("SELECT * FROM ram_logs ORDER BY timestamp ASC, id ASC")
    suspend fun getAllForExport(): List<RamLog>

    @Query("DELETE FROM ram_logs WHERE timestamp < :thresholdTimestamp")
    suspend fun deleteLogsOlderThan(thresholdTimestamp: Long)
    
    @Query("SELECT AVG(usedRam) FROM ram_logs WHERE timestamp >= :startOfDayTimestamp")
    suspend fun getAvgUsedRamToday(startOfDayTimestamp: Long): Double?
    
    @Query("SELECT MAX(usedRam) FROM ram_logs WHERE timestamp >= :startOfDayTimestamp")
    suspend fun getPeakUsedRamToday(startOfDayTimestamp: Long): Long?
}

@Dao
interface NetworkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: NetworkLog)

    @Query("SELECT * FROM network_logs WHERE timestamp >= :sinceTimestamp ORDER BY timestamp ASC")
    fun getLogsSince(sinceTimestamp: Long): Flow<List<NetworkLog>>

    @Query("SELECT * FROM network_logs ORDER BY timestamp ASC, id ASC")
    suspend fun getAllForExport(): List<NetworkLog>

    @Query("DELETE FROM network_logs WHERE timestamp < :thresholdTimestamp")
    suspend fun deleteLogsOlderThan(thresholdTimestamp: Long)
}

@Dao
interface NotificationDao {
    @Query("SELECT COUNT(*) FROM notification_logs WHERE timestamp >= :since AND timestamp <= :until AND appName NOT IN (:excluded)")
    suspend fun countRecent(since: Long, until: Long, excluded: List<String>): Int

    @Query("SELECT id FROM notification_logs WHERE notificationKey = :key LIMIT 1")
    suspend fun idForKey(key: String): Long?
    @androidx.room.Transaction
    suspend fun saveLatest(log: NotificationLog) {
        val existing = if(log.notificationKey.isNotBlank()) idForKey(log.notificationKey) else null
        insertLog(if(existing == null) log else log.copy(id=existing))
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: NotificationLog)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllLogs(logs: List<NotificationLog>)

    @Query("SELECT * FROM notification_logs ORDER BY timestamp DESC")
    fun getAllLogs(): Flow<List<NotificationLog>>

    @Query("SELECT * FROM notification_logs WHERE appName = :appName ORDER BY timestamp DESC")
    fun getLogsByApp(appName: String): Flow<List<NotificationLog>>

    @Query("SELECT * FROM notification_logs WHERE content LIKE '%' || :query || '%' OR title LIKE '%' || :query || '%' OR appName LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    fun searchLogs(query: String): Flow<List<NotificationLog>>

    @Query("SELECT appName, COUNT(*) as count FROM notification_logs GROUP BY appName ORDER BY count DESC")
    fun getNotificationStats(): Flow<List<AppNotificationStat>>

    @Query("DELETE FROM notification_logs WHERE timestamp < :thresholdTimestamp")
    suspend fun deleteLogsOlderThan(thresholdTimestamp: Long)

    @Query("DELETE FROM notification_logs")
    suspend fun clearAll()
    
    @Query("SELECT COUNT(*) FROM notification_logs WHERE timestamp >= :startOfDayTimestamp")
    suspend fun getTotalNotificationsToday(startOfDayTimestamp: Long): Int
}

@Dao
interface ScreenTimeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateLog(log: ScreenTimeLog)

    @Query("SELECT * FROM screen_time_logs WHERE date = :date AND appPackage = :appPackage LIMIT 1")
    suspend fun getLog(date: String, appPackage: String): ScreenTimeLog?

    @Query("INSERT INTO screen_time_logs(date, appPackage, usageMinutes) VALUES(:date, :appPackage, :usageMinutes) ON CONFLICT(date, appPackage) DO UPDATE SET usageMinutes = excluded.usageMinutes")
    suspend fun setAppScreenTime(date: String, appPackage: String, usageMinutes: Long)

    @Query("SELECT * FROM screen_time_logs WHERE date = :date ORDER BY usageMinutes DESC")
    fun getLogsForDate(date: String): Flow<List<ScreenTimeLog>>

    @Query("SELECT * FROM screen_time_logs ORDER BY date ASC, appPackage ASC, id ASC")
    suspend fun getAllAppsForExport(): List<ScreenTimeLog>

    @Query("SELECT SUM(usageMinutes) FROM screen_time_logs WHERE date = :date")
    fun getTotalScreenTimeForDate(date: String): Flow<Long?>

    @Query("SELECT date, SUM(usageMinutes) as totalMinutes FROM screen_time_logs WHERE date BETWEEN :startDate AND :endDate GROUP BY date ORDER BY date ASC")
    fun getTrendBetween(startDate: String, endDate: String): Flow<List<ScreenTimeTrend>>

    @Query("SELECT date, SUM(usageMinutes) as totalMinutes FROM screen_time_logs GROUP BY date ORDER BY date ASC")
    fun getAllHistory(): Flow<List<ScreenTimeTrend>>
    
    @Query("SELECT appPackage FROM screen_time_logs WHERE date = :date ORDER BY usageMinutes DESC LIMIT 1")
    suspend fun getTopAppForDate(date: String): String?

    @Upsert
    suspend fun upsertHourly(bucket: ScreenTimeHourly)

    @Query("SELECT * FROM screen_time_hourly WHERE bucketStartMillis = :bucketStartMillis")
    suspend fun getHourly(bucketStartMillis: Long): ScreenTimeHourly?

    @Query("SELECT * FROM screen_time_hourly WHERE localDate = :date ORDER BY localHour, bucketStartMillis")
    fun getHourlyForDate(date: String): Flow<List<ScreenTimeHourly>>

    @Query("SELECT localHour, AVG(dayUsageMillis / 60000.0) AS averageMinutes, COUNT(*) AS observedDays FROM (SELECT localDate, localHour, SUM(usageMillis) AS dayUsageMillis FROM screen_time_hourly WHERE localDate BETWEEN :startDate AND :endDate AND coveredMillis > 0 GROUP BY localDate, localHour) GROUP BY localHour ORDER BY localHour")
    fun getHourlyPattern(startDate: String, endDate: String): Flow<List<HourlyUsagePattern>>

    @Query("SELECT * FROM screen_time_hourly WHERE bucketStartMillis >= :startMillis AND bucketStartMillis < :endMillis ORDER BY bucketStartMillis")
    suspend fun getHourlyRange(startMillis: Long, endMillis: Long): List<ScreenTimeHourly>

    @Query("SELECT * FROM screen_time_hourly ORDER BY bucketStartMillis ASC")
    suspend fun getAllHourlyForExport(): List<ScreenTimeHourly>
}

@Dao
interface SamplingStateDao {
    @Query("SELECT * FROM sampling_state WHERE id = 1")
    suspend fun state(): SamplingState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(state: SamplingState)
}

@Dao
interface InternetUsageDailyDao {
    @Query("""
        INSERT INTO internet_usage_daily(dayStartMillis, localDate, zoneId, wifiBytes, mobileBytes, sampledAt, finalized)
        VALUES(:dayStartMillis, :localDate, :zoneId, :wifiBytes, :mobileBytes, :sampledAt, :finalized)
        ON CONFLICT(dayStartMillis) DO UPDATE SET
            localDate = excluded.localDate,
            zoneId = excluded.zoneId,
            wifiBytes = excluded.wifiBytes,
            mobileBytes = excluded.mobileBytes,
            sampledAt = excluded.sampledAt,
            finalized = excluded.finalized
    """)
    suspend fun upsert(
        dayStartMillis: Long,
        localDate: String,
        zoneId: String,
        wifiBytes: Long?,
        mobileBytes: Long?,
        sampledAt: Long,
        finalized: Boolean
    )

    suspend fun upsert(day: InternetUsageDaily) = upsert(
        day.dayStartMillis,
        day.localDate,
        day.zoneId,
        day.wifiBytes,
        day.mobileBytes,
        day.sampledAt,
        day.finalized
    )

    @Query("SELECT * FROM internet_usage_daily WHERE dayStartMillis >= :startMillis AND dayStartMillis < :endMillis ORDER BY dayStartMillis")
    suspend fun getRange(startMillis: Long, endMillis: Long): List<InternetUsageDaily>

    @Query("SELECT * FROM internet_usage_daily WHERE dayStartMillis >= :startMillis AND dayStartMillis < :endMillis ORDER BY dayStartMillis")
    fun observeRange(startMillis: Long, endMillis: Long): Flow<List<InternetUsageDaily>>

    @Query("SELECT * FROM internet_usage_daily WHERE dayStartMillis >= :startMillis AND dayStartMillis < :endMillis AND finalized = 0 ORDER BY dayStartMillis")
    suspend fun getUnfinalizedInRange(startMillis: Long, endMillis: Long): List<InternetUsageDaily>

    @Query("SELECT dayStartMillis FROM internet_usage_daily WHERE dayStartMillis >= :startMillis AND dayStartMillis < :endMillis ORDER BY dayStartMillis")
    suspend fun getStoredDayStarts(startMillis: Long, endMillis: Long): List<Long>
}

// ===== ADD THIS TO EXISTING Daos.kt =====
@Dao
interface ChatMessageDao {
    @Insert
    suspend fun insertMessage(message: ChatMessage): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSession(session: ChatSession)

    @Query("SELECT * FROM chat_sessions ORDER BY updatedAt DESC, id")
    fun observeSessions(): Flow<List<ChatSession>>

    @Query("SELECT * FROM chat_sessions WHERE id = :id")
    suspend fun session(id: String): ChatSession?

    @Query("SELECT * FROM chat_messages WHERE sessionId = :id ORDER BY id DESC")
    fun observeMessages(id: String): Flow<List<ChatMessage>>

    @Query("SELECT * FROM chat_messages WHERE sessionId = :id AND id > :afterId ORDER BY id")
    suspend fun messages(id: String, afterId: Long = 0): List<ChatMessage>

    @Query("UPDATE chat_messages SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: String)

    @Query("UPDATE chat_messages SET status = 'interrupted' WHERE status = 'pending'")
    suspend fun recoverInterrupted()

    @Query("UPDATE chat_sessions SET updatedAt = :time WHERE id = :id")
    suspend fun touch(id: String, time: Long = System.currentTimeMillis())

    @Query("UPDATE chat_sessions SET title = :title WHERE id = :id")
    suspend fun renameSession(id: String, title: String)

    @Query("UPDATE chat_sessions SET summary = :summary, summarizedThroughId = :throughId, summaryModel = :model WHERE id = :id")
    suspend fun saveSummary(id: String, summary: String, throughId: Long, model: String)

    @Query("DELETE FROM chat_messages WHERE sessionId = :id")
    suspend fun deleteSessionMessages(id: String)

    @Query("DELETE FROM chat_sessions WHERE id = :id")
    suspend fun deleteSessionRow(id: String)

    @Transaction
    suspend fun deleteSession(id: String) {
        deleteSessionMessages(id)
        deleteSessionRow(id)
    }

    @Transaction
    suspend fun completeTurn(userId: Long, answer: ChatMessage) {
        insertMessage(answer)
        setStatus(userId, "complete")
        touch(answer.sessionId)
    }
    
    @Query("SELECT * FROM chat_messages ORDER BY timestamp DESC LIMIT :limit")
    fun getLastNMessages(limit: Int): Flow<List<ChatMessage>>
    
    @Query("DELETE FROM chat_messages WHERE id NOT IN " +
           "(SELECT id FROM (SELECT id FROM chat_messages ORDER BY timestamp DESC LIMIT :keepCount))")
    suspend fun deleteOldMessages(keepCount: Int)
    
    @Query("DELETE FROM chat_messages")
    suspend fun clearAllMessages()
    
    @Query("SELECT COUNT(*) FROM chat_messages")
    suspend fun getMessageCount(): Int
}


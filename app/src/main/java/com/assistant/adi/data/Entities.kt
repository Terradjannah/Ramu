package com.assistant.adi.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

@Entity(tableName = "battery_logs")
data class BatteryLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val percentage: Int,
    val temperature: Float, // °C
    val voltage: Int, // mV
    val isCharging: Boolean,
    val temperatureValid: Boolean? = null
)

@Entity(tableName = "charge_sessions")
data class ChargeSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTime: Long,
    val endTime: Long,
    val startPercent: Int,
    val endPercent: Int
)

@Entity(tableName = "ram_logs")
data class RamLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val usedRam: Long, // MB
    val availableRam: Long, // MB
    val totalRam: Long // MB
)

@Entity(tableName = "network_logs")
data class NetworkLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val type: String, // wifi/mobile/offline
    val downloadSpeed: Double, // KB/s
    val uploadSpeed: Double, // KB/s
    val ping: Int, // ms
    val downloadSpeedValid: Boolean? = null,
    val uploadSpeedValid: Boolean? = null
)

@Entity(tableName = "notification_logs")
data class NotificationLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val appName: String,
    val title: String,
    val content: String,
    val timestamp: Long,
    val notificationKey: String = ""
)

@Entity(tableName = "screen_time_logs", indices = [Index(value = ["date", "appPackage"], unique = true)])
data class ScreenTimeLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String, // YYYY-MM-DD
    val appPackage: String,
    val usageMinutes: Long
)

// ===== ADD THIS TO EXISTING Entities.kt =====
@Entity(tableName = "chat_messages")
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String, // "user" or "ai"
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val sessionId: String,
    @androidx.room.ColumnInfo(defaultValue = "'complete'") val status: String = "complete",
    @androidx.room.ColumnInfo(defaultValue = "''") val contextContent: String = ""
)

@Entity(tableName = "screen_time_hourly", indices = [Index(value = ["localDate"]), Index(value = ["localHour"])])
data class ScreenTimeHourly(
    @PrimaryKey val bucketStartMillis: Long,
    val localDate: String,
    val localHour: Int,
    val zoneId: String,
    val offsetMinutes: Int,
    val usageMillis: Long,
    val coveredMillis: Long
)

@Entity(tableName = "sampling_state")
data class SamplingState(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val lastCommittedAt: Long = 0,
    val usageEventsReconciledThrough: Long = 0
) {
    companion object { const val SINGLETON_ID = 1 }
}

@Entity(
    tableName = "internet_usage_daily",
    indices = [Index(value = ["dayStartMillis"], unique = true)]
)
data class InternetUsageDaily(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dayStartMillis: Long,
    val localDate: String,
    val zoneId: String,
    val wifiBytes: Long?,
    val mobileBytes: Long?,
    val sampledAt: Long,
    val finalized: Boolean
)

@Entity(tableName = "chat_sessions")
data class ChatSession(
    @PrimaryKey val id: String,
    val title: String = "Obrolan baru",
    val updatedAt: Long = System.currentTimeMillis(),
    val summary: String = "",
    val summarizedThroughId: Long = 0,
    val summaryModel: String = ""
)


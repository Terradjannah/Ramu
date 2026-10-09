package com.assistant.adi.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        BatteryLog::class,
        ChargeSession::class,
        RamLog::class,
        NetworkLog::class,
        NotificationLog::class,
        ScreenTimeLog::class,
        ScreenTimeHourly::class,
        SamplingState::class,
        InternetUsageDaily::class,
        ChatMessage::class,
        ChatSession::class
    ],
    version = 6,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun batteryDao(): BatteryDao
    abstract fun chargeSessionDao(): ChargeSessionDao
    abstract fun ramDao(): RamDao
    abstract fun networkDao(): NetworkDao
    abstract fun notificationDao(): NotificationDao
    abstract fun screenTimeDao(): ScreenTimeDao
    abstract fun samplingStateDao(): SamplingStateDao
    abstract fun internetUsageDailyDao(): InternetUsageDailyDao
    abstract fun monitorHistoryDao(): MonitorHistoryDao
    abstract fun chatMessageDao(): ChatMessageDao

    companion object {
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN status TEXT NOT NULL DEFAULT 'complete'")
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN contextContent TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE TABLE IF NOT EXISTS chat_sessions (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, updatedAt INTEGER NOT NULL, summary TEXT NOT NULL, summarizedThroughId INTEGER NOT NULL, summaryModel TEXT NOT NULL)")
                db.execSQL("INSERT INTO chat_sessions SELECT sessionId, 'Obrolan sebelumnya', MAX(timestamp), '', 0, '' FROM chat_messages GROUP BY sessionId")
            }
        }
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS screen_time_hourly (bucketStartMillis INTEGER NOT NULL PRIMARY KEY, localDate TEXT NOT NULL, localHour INTEGER NOT NULL, zoneId TEXT NOT NULL, offsetMinutes INTEGER NOT NULL, usageMillis INTEGER NOT NULL, coveredMillis INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_screen_time_hourly_localDate ON screen_time_hourly(localDate)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_screen_time_hourly_localHour ON screen_time_hourly(localHour)")
                db.execSQL("CREATE TABLE IF NOT EXISTS sampling_state (id INTEGER NOT NULL PRIMARY KEY, lastCommittedAt INTEGER NOT NULL, usageEventsReconciledThrough INTEGER NOT NULL)")
                db.execSQL("INSERT OR IGNORE INTO sampling_state(id, lastCommittedAt, usageEventsReconciledThrough) VALUES(1, 0, 0)")
                db.execSQL("CREATE TABLE screen_time_logs_new (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, date TEXT NOT NULL, appPackage TEXT NOT NULL, usageMinutes INTEGER NOT NULL)")
                db.execSQL("INSERT INTO screen_time_logs_new(id, date, appPackage, usageMinutes) SELECT MIN(id), date, appPackage, MAX(usageMinutes) FROM screen_time_logs GROUP BY date, appPackage")
                db.execSQL("DROP TABLE screen_time_logs")
                db.execSQL("ALTER TABLE screen_time_logs_new RENAME TO screen_time_logs")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_screen_time_logs_date_appPackage ON screen_time_logs(date, appPackage)")
            }
        }
        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS internet_usage_daily (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, dayStartMillis INTEGER NOT NULL, localDate TEXT NOT NULL, zoneId TEXT NOT NULL, wifiBytes INTEGER, mobileBytes INTEGER, sampledAt INTEGER NOT NULL, finalized INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_internet_usage_daily_dayStartMillis ON internet_usage_daily(dayStartMillis)")
            }
        }
        val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE battery_logs ADD COLUMN temperatureValid INTEGER")
                db.execSQL("ALTER TABLE network_logs ADD COLUMN downloadSpeedValid INTEGER")
                db.execSQL("ALTER TABLE network_logs ADD COLUMN uploadSpeedValid INTEGER")
            }
        }
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "assistant_adi_database"
                )
                .addMigrations(object : androidx.room.migration.Migration(1,2) {
                    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("ALTER TABLE notification_logs ADD COLUMN notificationKey TEXT NOT NULL DEFAULT ''")
                    }
                })
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .build()
                instance.openHelper.writableDatabase
                val lastSample = PrefsManager(context.applicationContext).lastSampleTime
                if (lastSample > 0L && lastSample <= System.currentTimeMillis()) {
                    instance.openHelper.writableDatabase.execSQL(
                        "UPDATE sampling_state SET lastCommittedAt = ? WHERE id = 1 AND lastCommittedAt = 0",
                        arrayOf(lastSample)
                    )
                }
                INSTANCE = instance
                instance
            }
        }
    }
}

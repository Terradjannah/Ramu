package com.assistant.adi

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.assistant.adi.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class DatabaseRegressionTest {
    private lateinit var db:AppDatabase
    private lateinit var context:Context
    private lateinit var directory:File
    @Before fun setup() {
        val base=ApplicationProvider.getApplicationContext<Context>()
        directory=File(base.cacheDir,"backup-test-${System.nanoTime()}").apply { mkdirs() }
        context=object:ContextWrapper(base) { override fun getFilesDir()=directory }
        db=Room.inMemoryDatabaseBuilder(base,AppDatabase::class.java).build()
    }
    @After fun close() { db.close(); directory.deleteRecursively() }
    @Test fun encryptedSnapshotRestoresWithoutClosingRoom()= runBlocking {
        db.batteryDao().insertLog(BatteryLog(timestamp=100,percentage=50,temperature=30f,voltage=4000,isCharging=false))
        db.screenTimeDao().upsertHourly(ScreenTimeHourly(3_600_000, "2026-09-21", 1, "Asia/Jakarta", 420, 30_000, 60_000))
        db.internetUsageDailyDao().upsert(internetUsage("2026-09-20", wifiBytes = null, mobileBytes = 42L))
        db.samplingStateDao().save(SamplingState(lastCommittedAt = 100, usageEventsReconciledThrough = 200))
        val backup=DatabaseBackup(context,db); backup.backup()
        db.batteryDao().insertLog(BatteryLog(timestamp=200,percentage=51,temperature=31f,voltage=4000,isCharging=true))
        backup.restore()
        assertEquals(1,db.batteryDao().getLogsSince(0).first().size)
        assertEquals(30_000, db.screenTimeDao().getHourlyRange(0, 4_000_000).single().usageMillis)
        assertNull(db.internetUsageDailyDao().getRange(0, Long.MAX_VALUE).single().wifiBytes)
        assertEquals(42L, db.internetUsageDailyDao().getRange(0, Long.MAX_VALUE).single().mobileBytes)
        assertEquals(200, db.samplingStateDao().state()!!.usageEventsReconciledThrough)
        db.batteryDao().insertLog(BatteryLog(timestamp=300,percentage=52,temperature=31f,voltage=4000,isCharging=true))
        assertEquals(2,db.batteryDao().getLogsSince(0).first().size)
    }
    @Test fun corruptedBackupLeavesCurrentDataIntact()=runBlocking {
        db.batteryDao().insertLog(BatteryLog(timestamp=100,percentage=50,temperature=30f,voltage=4000,isCharging=false))
        val backup=DatabaseBackup(context,db); backup.backup()
        val file=File(directory,"backups/assistant_adi_v2.enc")
        val bytes=file.readBytes(); bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte(); file.writeBytes(bytes)
        assertTrue(runCatching { backup.restore() }.isFailure)
        assertEquals(1,db.batteryDao().getLogsSince(0).first().size)
    }
    @Test fun repeatedNotificationUpdatesOneRow()=runBlocking {
        val first=NotificationLog(appName="Test",title="First",content="Metadata",timestamp=100,notificationKey="test-key")
        db.notificationDao().saveLatest(first)
        db.notificationDao().saveLatest(first.copy(title="Updated",timestamp=200))
        val logs=db.notificationDao().getAllLogs().first()
        assertEquals(1,logs.size); assertEquals("Updated",logs.single().title)
    }
    @Test fun sessionsAndCompactionSurviveEncryptedRestoreWithoutDeletingHistory() = runBlocking {
        val dao = db.chatMessageDao()
        dao.insertSession(ChatSession("a", "Alpha"))
        dao.insertSession(ChatSession("b", "Beta"))
        val userId = dao.insertMessage(ChatMessage(role = "user", content = "Remember 42", sessionId = "a", status = "pending", contextContent = "Saved telemetry"))
        dao.completeTurn(userId, ChatMessage(role = "ai", content = "42", sessionId = "a"))
        val through = dao.messages("a").last().id
        dao.saveSummary("a", "Number is 42", through, "E2B")
        dao.insertMessage(ChatMessage(role = "user", content = "Private B", sessionId = "b", status = "pending"))
        val backup = DatabaseBackup(context, db)
        backup.backup()
        dao.deleteSession("a")
        backup.restore()
        assertEquals(2, dao.messages("a").size)
        assertEquals("Saved telemetry", dao.messages("a").first().contextContent)
        assertEquals(through, dao.session("a")!!.summarizedThroughId)
        assertEquals("Number is 42", dao.session("a")!!.summary)
        assertTrue(dao.messages("a", through).isEmpty())
        dao.recoverInterrupted()
        assertEquals("interrupted", dao.messages("b").single().status)
        dao.deleteSession("a")
        assertEquals(1, dao.messages("b").size)
        assertNull(dao.session("a"))
    }

    @Test fun oldLogicalBackupGetsSessionsAndDefaultContext() {
        val document = org.json.JSONObject().put("version", 2).put("chat_messages", org.json.JSONArray()
            .put(org.json.JSONObject().put("id", 1).put("sessionId", "default_session").put("timestamp", 100)
                .put("role", "user").put("content", "Old message")))
        DatabaseBackup(context, db).upgradeSnapshot(document)
        assertEquals(5, document.getInt("version"))
        assertEquals("default_session", document.getJSONArray("chat_sessions").getJSONObject(0).getString("id"))
        assertEquals("complete", document.getJSONArray("chat_messages").getJSONObject(0).getString("status"))
        assertEquals(0, document.getJSONArray("screen_time_hourly").length())
        assertEquals(0, document.getJSONArray("internet_usage_daily").length())
    }

    @Test fun v3LogicalBackupGetsEmptyHourlyDataAndSamplingState() {
        val document = org.json.JSONObject().put("version", 3)
        DatabaseBackup(context, db).upgradeSnapshot(document)
        assertEquals(5, document.getInt("version"))
        assertEquals(1, document.getJSONArray("sampling_state").length())
        assertEquals(0, document.getJSONArray("internet_usage_daily").length())
    }

    @Test fun migrationDeduplicatesScreenTimeAndCreatesHourlyTables() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val name = "chat-migration-${System.nanoTime()}.db"
        val file = base.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        // Start with Room's current schema, then reconstruct the v3 tables being upgraded.
        Room.databaseBuilder(base, AppDatabase::class.java, name).build().let { original ->
            original.openHelper.writableDatabase
            original.close()
        }
        android.database.sqlite.SQLiteDatabase.openDatabase(file.path, null, 0).use { sql ->
            sql.execSQL("DROP TABLE screen_time_hourly")
            sql.execSQL("DROP TABLE sampling_state")
            sql.execSQL("DROP TABLE screen_time_logs")
            sql.execSQL("CREATE TABLE screen_time_logs (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, date TEXT NOT NULL, appPackage TEXT NOT NULL, usageMinutes INTEGER NOT NULL)")
            sql.execSQL("INSERT INTO screen_time_logs VALUES (1, '2026-09-21', 'pkg', 10)")
            sql.execSQL("INSERT INTO screen_time_logs VALUES (2, '2026-09-21', 'pkg', 25)")
            sql.version = 3
        }
        val migrated = Room.databaseBuilder(base, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5)
            .build()
        try {
            assertEquals(25, migrated.screenTimeDao().getLog("2026-09-21", "pkg")!!.usageMinutes)
            assertEquals(0, migrated.screenTimeDao().getHourlyRange(0, Long.MAX_VALUE).size)
            assertEquals(0, migrated.samplingStateDao().state()!!.lastCommittedAt)
        } finally { migrated.close(); base.deleteDatabase(name) }
    }

    @Test fun v4LogicalBackupGetsEmptyInternetUsageData() {
        val document = org.json.JSONObject().put("version", 4)
        DatabaseBackup(context, db).upgradeSnapshot(document)
        assertEquals(5, document.getInt("version"))
        assertEquals(0, document.getJSONArray("internet_usage_daily").length())
    }

    @Test fun repeatedInternetUsageUpsertReplacesAbsoluteDayTotal() = runBlocking {
        val first = internetUsage("2026-09-20", wifiBytes = 100L, mobileBytes = 200L)
        db.internetUsageDailyDao().upsert(first)
        db.internetUsageDailyDao().upsert(first.copy(wifiBytes = 300L, mobileBytes = null, sampledAt = first.sampledAt + 1))
        val stored = db.internetUsageDailyDao().getRange(0, Long.MAX_VALUE).single()
        assertEquals(300L, stored.wifiBytes)
        assertNull(stored.mobileBytes)
        assertEquals(first.sampledAt + 1, stored.sampledAt)
    }

    @Test fun failedInternetUsageBatchDoesNotAdvanceCadenceOrSaveOtherMetrics() = runBlocking {
        val repository = AppRepository(context, db)
        db.samplingStateDao().save(SamplingState(lastCommittedAt = 1_000L))
        val invalidFinalizedDay = internetUsage("2026-09-20", wifiBytes = null, mobileBytes = 10L, finalized = true)
        val failure = runCatching {
            repository.commitSamplingBatch(AppRepository.SamplingBatch(
                timestamp = 2_000L,
                intervalMillis = 1L,
                battery = BatteryLog(timestamp = 2_000L, percentage = 50, temperature = 30f, voltage = 4000, isCharging = false),
                ram = RamLog(timestamp = 2_000L, usedRam = 1000, availableRam = 500, totalRam = 1500),
                network = NetworkLog(timestamp = 2_000L, type = "wifi", downloadSpeed = 1.0, uploadSpeed = 1.0, ping = 1),
                appUsage = emptyMap(),
                localDate = "2026-09-20",
                hourlyResult = com.assistant.adi.util.UsageReader.RangeResult(granted = false, available = false),
                internetUsageDaily = listOf(invalidFinalizedDay)
            ))
        }
        assertTrue(failure.isFailure)
        assertEquals(1_000L, db.samplingStateDao().state()!!.lastCommittedAt)
        assertTrue(db.batteryDao().getLogsSince(0).first().isEmpty())
        assertTrue(db.internetUsageDailyDao().getRange(0, Long.MAX_VALUE).isEmpty())
    }

    @Test fun migrationFromV4CreatesInternetUsageTableWithoutLosingData() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val name = "internet-usage-migration-${System.nanoTime()}.db"
        val file = base.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        Room.databaseBuilder(base, AppDatabase::class.java, name).build().let { original ->
            original.batteryDao().insertLog(BatteryLog(timestamp = 100, percentage = 50, temperature = 30f, voltage = 4000, isCharging = false))
            original.close()
        }
        android.database.sqlite.SQLiteDatabase.openDatabase(file.path, null, 0).use { sql ->
            sql.execSQL("DROP TABLE internet_usage_daily")
            sql.version = 4
        }
        val migrated = Room.databaseBuilder(base, AppDatabase::class.java, name).addMigrations(AppDatabase.MIGRATION_4_5).build()
        try {
            assertEquals(1, migrated.batteryDao().getLogsSince(0).first().size)
            assertTrue(migrated.internetUsageDailyDao().getRange(0, Long.MAX_VALUE).isEmpty())
        } finally { migrated.close(); base.deleteDatabase(name) }
    }

    private fun internetUsage(
        date: String,
        wifiBytes: Long?,
        mobileBytes: Long?,
        finalized: Boolean = false
    ): InternetUsageDaily {
        val zone = ZoneId.of("Asia/Jakarta")
        val localDate = LocalDate.parse(date)
        val start = localDate.atStartOfDay(zone).toInstant().toEpochMilli()
        return InternetUsageDaily(
            dayStartMillis = start,
            localDate = date,
            zoneId = zone.id,
            wifiBytes = wifiBytes,
            mobileBytes = mobileBytes,
            sampledAt = localDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
            finalized = finalized
        )
    }
}

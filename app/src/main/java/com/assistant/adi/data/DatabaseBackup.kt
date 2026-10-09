package com.assistant.adi.data

import android.content.Context
import android.database.Cursor
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Logical snapshot restored in one Room transaction. The live SQLite file is never replaced/closed. */
class DatabaseBackup(private val context:Context,private val db:AppDatabase) {
    private val tables=listOf("battery_logs","charge_sessions","ram_logs","network_logs","notification_logs","screen_time_logs","screen_time_hourly","sampling_state","internet_usage_daily","chat_messages","chat_sessions")
    private val legacyTables=listOf("battery_logs","charge_sessions","ram_logs","network_logs","notification_logs","screen_time_logs","chat_messages")
    private val destination=AtomicFile(File(context.filesDir,"backups/assistant_adi_v2.enc"))
    private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("adi_database_backup_v2",null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("adi_database_backup_v2",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    suspend fun backup() {
        val document=JSONObject().put("version",6)
        db.withTransaction {
            for(table in tables) {
                val rows=JSONArray()
                db.openHelper.readableDatabase.query("SELECT * FROM $table").use { cursor ->
                    while(cursor.moveToNext()) {
                        val row=JSONObject()
                        for(i in 0 until cursor.columnCount) row.put(cursor.getColumnName(i),when(cursor.getType(i)) {
                            Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                            Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i)
                            else -> cursor.getString(i)
                        })
                        rows.put(row)
                    }
                }
                document.put(table,rows)
            }
        }
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key()) }
        val bytes=cipher.doFinal(document.toString().toByteArray(Charsets.UTF_8))
        destination.baseFile.parentFile!!.mkdirs()
        val output=destination.startWrite()
        try {
            output.write(byteArrayOf(65,68,73,2)); output.write(cipher.iv.size); output.write(cipher.iv); output.write(bytes)
            destination.finishWrite(output)
        } catch(e:Exception) { destination.failWrite(output); throw e }
    }
    suspend fun restore() {
        // Fully authenticate and validate before entering the write transaction.
        val document=if(destination.baseFile.exists() || File(destination.baseFile.path+".bak").exists()) {
            val bytes=destination.readFully()
            require(bytes.size>32 && bytes.take(4)==listOf<Byte>(65,68,73,2)) { "Format backup tidak dikenal" }
            val ivLength=bytes[4].toInt()
            require(ivLength==12 && bytes.size>5+ivLength+16)
            val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(5,5+ivLength))) }
            JSONObject(String(cipher.doFinal(bytes.copyOfRange(5+ivLength,bytes.size)),Charsets.UTF_8))
        } else legacySnapshot()
        upgradeSnapshot(document)
        require(document.getInt("version") == 6)
        val columns=tables.associateWith { table ->
            db.openHelper.readableDatabase.query("PRAGMA table_info($table)").use { c ->
                buildList { while(c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) }
            }
        }
        for(table in tables) {
            val rows=document.getJSONArray(table)
            for(i in 0 until rows.length()) {
                val row=rows.getJSONObject(i)
                require(row.keys().asSequence().toSet()==columns.getValue(table).toSet()) { "Kolom backup tidak cocok" }
                require(if (table == "chat_sessions") row.getString("id").isNotBlank() else row.getLong("id") > 0) { "ID backup tidak valid" }
                for(column in columns.getValue(table)) {
                    val value = row.get(column)
                    val nullableInternetBytes = table == "internet_usage_daily" && (column == "wifiBytes" || column == "mobileBytes")
                    val nullableValidity = table == "battery_logs" && column == "temperatureValid" ||
                        table == "network_logs" && (column == "downloadSpeedValid" || column == "uploadSpeedValid")
                    require(value is String || value is Number || ((nullableInternetBytes || nullableValidity) && value == JSONObject.NULL)) { "Nilai backup tidak valid" }
                }
            }
        }
        db.withTransaction {
            val sql=db.openHelper.writableDatabase
            for(table in tables) {
                sql.execSQL("DELETE FROM $table")
                val names=columns.getValue(table)
                val statement="INSERT INTO $table (${names.joinToString(",")}) VALUES (${names.joinToString(",") { "?" }})"
                val rows=document.getJSONArray(table)
                for(i in 0 until rows.length()) {
                    val row=rows.getJSONObject(i)
                    sql.execSQL(statement,names.map { column -> row.get(column).takeUnless { it == JSONObject.NULL } }.toTypedArray())
                }
            }
        }
    }
    // Keep the encrypted envelope/key stable; migrate old logical rows before validating v6.
    internal fun upgradeSnapshot(document: JSONObject) {
        if (document.getInt("version") == 2) {
            val messages = document.getJSONArray("chat_messages")
            val sessions = linkedMapOf<String, JSONObject>()
            for (i in 0 until messages.length()) {
                val row = messages.getJSONObject(i)
                row.put("status", "complete").put("contextContent", "")
                val id = row.getString("sessionId")
                val session = sessions.getOrPut(id) {
                    JSONObject().put("id", id).put("title", "Obrolan sebelumnya")
                        .put("updatedAt", 0L).put("summary", "").put("summarizedThroughId", 0L).put("summaryModel", "")
                }
                session.put("updatedAt", maxOf(session.getLong("updatedAt"), row.getLong("timestamp")))
            }
            document.put("chat_sessions", JSONArray(sessions.values.toList())).put("version", 3)
        }
        if (document.getInt("version") == 3) {
            document.put("screen_time_hourly", JSONArray())
            document.put("sampling_state", JSONArray().put(JSONObject()
                .put("id", SamplingState.SINGLETON_ID)
                .put("lastCommittedAt", 0L)
                .put("usageEventsReconciledThrough", 0L)))
            document.put("version", 4)
        }
        if (document.getInt("version") == 4) {
            document.put("internet_usage_daily", JSONArray())
            document.put("version", 5)
        }
        if (document.getInt("version") == 5) {
            val batteries = document.getJSONArray("battery_logs")
            for (i in 0 until batteries.length()) batteries.getJSONObject(i).put("temperatureValid", JSONObject.NULL)
            val networks = document.getJSONArray("network_logs")
            for (i in 0 until networks.length()) networks.getJSONObject(i)
                .put("downloadSpeedValid", JSONObject.NULL).put("uploadSpeedValid", JSONObject.NULL)
            document.put("version", 6)
        }
    }

    private fun legacySnapshot():JSONObject {
        val source=File(context.filesDir,"backups/assistant_adi_backup.enc")
        require(source.exists()) { "Backup belum tersedia" }
        val master=androidx.security.crypto.MasterKey.Builder(context).setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM).build()
        val encrypted=androidx.security.crypto.EncryptedFile.Builder(context,source,master,androidx.security.crypto.EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB).build()
        val temp=File.createTempFile("restore-", ".db",context.cacheDir)
        try {
            encrypted.openFileInput().use { input -> temp.outputStream().use { input.copyTo(it) } }
            val document=JSONObject().put("version",3)
            android.database.sqlite.SQLiteDatabase.openDatabase(temp.path,null,android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { sql ->
                sql.rawQuery("PRAGMA integrity_check",null).use { require(it.moveToFirst() && it.getString(0)=="ok") }
                for(table in legacyTables) {
                    val rows=JSONArray()
                    sql.rawQuery("SELECT * FROM $table",null).use { c ->
                        while(c.moveToNext()) {
                            val row=JSONObject()
                            for(i in 0 until c.columnCount) row.put(c.getColumnName(i),when(c.getType(i)) {
                                Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                                Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                                else -> c.getString(i)
                            })
                            if(table=="notification_logs" && !row.has("notificationKey")) row.put("notificationKey","")
                            rows.put(row)
                        }
                    }
                    document.put(table,rows)
                }
            }
            document.put("version", 2)
            return document
        } finally { temp.delete() }
    }
}

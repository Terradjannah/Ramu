package com.assistant.adi.data.catalog

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs

data class DeviceCapabilities(
    val apiLevel: Int,
    val abis: Set<String>,
    val totalRamBytes: Long,
    val availableStorageBytes: Long,
    val socId: String?,
    val driverId: String?
) {
    companion object {
        fun read(context: Context): DeviceCapabilities {
            val memory = ActivityManager.MemoryInfo()
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
            val modelDirectory = context.getExternalFilesDir("models")
            val freeBytes = modelDirectory?.let { StatFs(it.absolutePath).availableBytes } ?: 0L
            return DeviceCapabilities(
                Build.VERSION.SDK_INT,
                Build.SUPPORTED_ABIS.toSet(),
                memory.totalMem,
                freeBytes,
                if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.takeUnless { it.isBlank() || it == Build.UNKNOWN } else null,
                null // No validated driver identifier is exposed by this APK.
            )
        }

        fun availableRamBytes(context: Context): Long {
            val memory = ActivityManager.MemoryInfo()
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
            return if (memory.lowMemory) 0L else memory.availMem
        }
    }
}

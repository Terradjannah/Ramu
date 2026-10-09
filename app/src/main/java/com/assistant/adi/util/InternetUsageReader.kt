package com.assistant.adi.util

import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

data class InternetUsageReadResult(
    val window: InternetUsageWindow,
    val queriedEndMillis: Long,
    val usage: InternetTransportUsage
)

object InternetUsageReader {
    /**
     * Reads one local-day window. Call this only from a due sampler, never from UI code.
     * A current-day read ends at [nowMillis] so NetworkStats is never asked for future time.
     */
    suspend fun readDay(
        context: Context,
        window: InternetUsageWindow,
        nowMillis: Long = System.currentTimeMillis()
    ): InternetUsageReadResult = withContext(Dispatchers.IO) {
        require(window.endMillis > window.startMillis)
        val queryEnd = minOf(window.endMillis, nowMillis)
        require(window.startMillis < nowMillis) { "Cannot read a future day" }
        if (!UsageReader.hasPermission(context)) {
            return@withContext InternetUsageReadResult(
                window, queryEnd,
                InternetTransportUsage(null, null, null, InternetUsageStatus.PERMISSION_REQUIRED)
            )
        }
        val manager = context.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager
            ?: return@withContext InternetUsageReadResult(
                window, queryEnd,
                InternetTransportUsage(null, null, null, InternetUsageStatus.UNAVAILABLE)
            )
        val wifi = readTransport(manager, ConnectivityManager.TYPE_WIFI, window.startMillis, queryEnd)
        val mobile = readTransport(manager, ConnectivityManager.TYPE_MOBILE, window.startMillis, queryEnd)
        val status = if (wifi != null && mobile != null) InternetUsageStatus.AVAILABLE else InternetUsageStatus.UNAVAILABLE
        InternetUsageReadResult(window, queryEnd, InternetUsagePolicy.transportUsage(wifi, mobile, status))
    }

    private fun readTransport(
        manager: NetworkStatsManager,
        transport: Int,
        startMillis: Long,
        endMillis: Long
    ): Long? = try {
        val bucket = manager.querySummaryForDevice(transport, null, startMillis, endMillis)
        InternetUsagePolicy.totalBytes(bucket.rxBytes, bucket.txBytes)
    } catch (_: SecurityException) {
        null
    } catch (_: RuntimeException) {
        null
    }
}

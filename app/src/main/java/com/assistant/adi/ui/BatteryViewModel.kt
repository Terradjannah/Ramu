package com.assistant.adi.ui

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.map
import androidx.lifecycle.switchMap
import androidx.lifecycle.viewModelScope
import com.assistant.adi.data.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

class BatteryViewModel(application: Application, savedState: SavedStateHandle) : AndroidViewModel(application) {

    enum class HistoryRange(val durationMillis: Long, val label: String) {
        HOURS_24(24 * 60 * 60 * 1000L, "24 jam"),
        DAYS_7(7 * 24 * 60 * 60 * 1000L, "7 hari"),
        DAYS_30(30 * 24 * 60 * 60 * 1000L, "30 hari")
    }

    data class ForegroundBattery(val timestamp: Long, val percentage: Int, val temperature: Float?, val voltage: Int?, val isCharging: Boolean)

    private val repository = AppRepository(application)
    val retainedHistory = MonitorHistoryState(savedState, HistoryMetric.BATTERY)
    val historyError = MutableLiveData<String?>(null)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val retainedHistoryPage = retainedHistory.queryInput.flatMapLatest { input ->
        repository.batteryHistory(input).onStart { historyError.postValue(null) }
            .catch { historyError.postValue("Catatan baterai gagal dimuat.") }
    }.asLiveData()
    private val _foregroundBattery = MutableLiveData<ForegroundBattery>()
    val foregroundBattery: LiveData<ForegroundBattery> get() = _foregroundBattery
    private var foregroundReadJob: Job? = null

    // Flow for latest battery log
    val latestBatteryLog = repository.latestBatteryLog.asLiveData()

    // Flow for 24 hours of logs
    val logs24Hours = repository.getBatteryLogs(System.currentTimeMillis() - 24 * 60 * 60 * 1000L).asLiveData()

    private val retainedLogs = repository.getBatteryLogs(Long.MIN_VALUE).asLiveData()
    private val _historyRange = MutableLiveData(HistoryRange.HOURS_24)
    val historyRange: LiveData<HistoryRange> get() = _historyRange
    val availableHistoryRanges: LiveData<List<HistoryRange>> = retainedLogs.map { logs ->
        val first = logs.minOfOrNull { it.timestamp }
        val now = System.currentTimeMillis()
        HistoryRange.entries.filter { range -> range == HistoryRange.HOURS_24 || first != null && now - first >= range.durationMillis }
    }
    val selectedRangeLogs: LiveData<List<BatteryLog>> = _historyRange.switchMap { range ->
        repository.getBatteryLogs(System.currentTimeMillis() - range.durationMillis).asLiveData()
    }

    fun selectHistoryRange(range: HistoryRange) { _historyRange.value = range }

    // Flow for charging sessions
    val chargeSessions = repository.allChargeSessions.asLiveData()

    // Map logs to estimate string
    val batteryEstimate = repository.getBatteryLogs(System.currentTimeMillis() - 24 * 60 * 60 * 1000L).map { logs ->
        val latest = logs.lastOrNull() ?: return@map "Menunggu data estimasi"
        calculateEstimate(logs, latest.percentage, latest.isCharging)
    }.asLiveData()

    fun startForegroundReads() {
        if (foregroundReadJob?.isActive == true) return
        foregroundReadJob = viewModelScope.launch {
            while (true) {
                readForegroundBattery()
                delay(FOREGROUND_READ_INTERVAL_MILLIS)
            }
        }
    }

    fun stopForegroundReads() {
        foregroundReadJob?.cancel()
        foregroundReadJob = null
    }

    private fun readForegroundBattery() {
        val intent = getApplication<Application>().registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return
        val percentage = (level * 100 / scale).coerceIn(0, 100)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val temperature = intent.takeIf { it.hasExtra(BatteryManager.EXTRA_TEMPERATURE) }
            ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it in -200..1000 }?.div(10f)
        val voltage = intent.takeIf { it.hasExtra(BatteryManager.EXTRA_VOLTAGE) }
            ?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)?.takeIf { it > 0 }
        _foregroundBattery.value = ForegroundBattery(
            System.currentTimeMillis(), percentage, temperature, voltage,
            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        )
    }

    private fun calculateEstimate(logs: List<BatteryLog>, currentPct: Int, isCharging: Boolean): String {
        if (logs.size < 2) return "Menunggu data estimasi"
        
        // Use logs from the last 2 hours to calculate rate
        val cutoff = System.currentTimeMillis() - 2 * 60 * 60 * 1000L
        val relevantLogs = logs.filter { it.timestamp >= cutoff && it.isCharging == isCharging }
        if (relevantLogs.size < 2) return "Menunggu data estimasi"

        val first = relevantLogs.first()
        val last = relevantLogs.last()
        val pctDelta = Math.abs(first.percentage - last.percentage)
        val timeDeltaMs = last.timestamp - first.timestamp
        if (pctDelta == 0 || timeDeltaMs == 0L) return "Menunggu data estimasi"

        val ratePerMs = pctDelta.toDouble() / timeDeltaMs
        val remainingPct = if (isCharging) 100 - currentPct else currentPct
        val remainingMs = (remainingPct / ratePerMs).toLong()

        val hours = remainingMs / (1000 * 60 * 60)
        val minutes = (remainingMs % (1000 * 60 * 60)) / (1000 * 60)
        
        return if (isCharging) {
            if (currentPct >= 100) "Baterai penuh"
            else String.format(Locale.US, "Perkiraan %d j %d m hingga penuh", hours, minutes)
        } else {
            String.format(Locale.US, "Perkiraan sisa %d j %d m", hours, minutes)
        }
    }

    private fun analyzeHabits(sessions: List<ChargeSession>, logs: List<BatteryLog>): String {
        val sb = java.lang.StringBuilder()
        
        // Habit 1: Check charging below 20%
        val lowChargeCount = sessions.filter { it.startPercent < 20 }.size
        if (lowChargeCount > 0) {
            sb.append("• Discharged below 20% before plugging in $lowChargeCount times. (Try to charge before drops below 20% to avoid chemical wear).\n\n")
        }

        // Habit 2: Check staying above 80% while charging
        var highChargeDurationMs = 0L
        var prevLog: BatteryLog? = null
        for (log in logs) {
            if (log.percentage >= 80 && log.isCharging) {
                if (prevLog != null && prevLog.percentage >= 80 && prevLog.isCharging) {
                    highChargeDurationMs += (log.timestamp - prevLog.timestamp)
                }
            }
            prevLog = log
        }
        
        val highChargeHours = highChargeDurationMs / (1000 * 60 * 60)
        if (highChargeHours >= 2) {
            sb.append("• Left plugged in above 80% for $highChargeHours hours. (Avoid leaving device plugged in at high percentage to prevent calendar aging).")
        }

        if (sb.isEmpty()) {
            return "No bad charging habits detected this week. Excellent job!"
        }
        return sb.toString().trim()
    }

    private companion object {
        const val FOREGROUND_READ_INTERVAL_MILLIS = 30_000L
    }
}

private suspend fun <T> kotlinx.coroutines.flow.Flow<T>.firstOrNull(): T? {
    return try {
        this.first()
    } catch (e: Exception) {
        null
    }
}

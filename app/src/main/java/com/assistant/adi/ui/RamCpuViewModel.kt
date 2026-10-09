package com.assistant.adi.ui

import android.app.ActivityManager
import android.app.Application
import androidx.lifecycle.SavedStateHandle
import com.assistant.adi.data.HistoryMetric
import com.assistant.adi.data.MonitorHistoryState
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onStart
import android.content.Context
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.assistant.adi.data.AppRepository
import com.assistant.adi.data.RamLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

class RamCpuViewModel(application: Application, savedState: SavedStateHandle) : AndroidViewModel(application) {

    private val repository = AppRepository(application)
    val retainedHistory = MonitorHistoryState(savedState, HistoryMetric.RAM)
    val historyError = MutableLiveData<String?>(null)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val retainedHistoryPage = retainedHistory.queryInput.flatMapLatest { input ->
        repository.ramHistory(input).onStart { historyError.postValue(null) }
            .catch { historyError.postValue("Catatan RAM gagal dimuat.") }
    }.asLiveData()
    private val activityManager = application.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val powerManager = application.getSystemService(Context.POWER_SERVICE) as PowerManager

    private val _ramUsedGb = MutableLiveData<Double>()
    val ramUsedGb: LiveData<Double> get() = _ramUsedGb

    private val _ramTotalGb = MutableLiveData<Double>()
    val ramTotalGb: LiveData<Double> get() = _ramTotalGb

    private val _ramPercent = MutableLiveData<Int>()
    val ramPercent: LiveData<Int> get() = _ramPercent

    private val _cpuUsage = MutableLiveData<Int>()
    val cpuUsage: LiveData<Int> get() = _cpuUsage

    private val _cpuTemp = MutableLiveData<Float>()
    val cpuTemp: LiveData<Float> get() = _cpuTemp

    private val _wakelockStatus = MutableLiveData<String>()
    val wakelockStatus: LiveData<String> get() = _wakelockStatus

    // Hourly RAM logs for last 24h
    val ramLogs24Hours = repository.getRamLogs(System.currentTimeMillis() - 24 * 60 * 60 * 1000L).asLiveData()

    private var polling: kotlinx.coroutines.Job? = null
    fun start() { if (polling?.isActive != true) startRealtimeUpdates() }
    fun stop() { polling?.cancel(); polling = null }

    private fun startRealtimeUpdates() {
        polling = viewModelScope.launch {
            while (true) {
                updateRam()
                updateCpu()
                updateWakeLockStatus()
                delay(30_000L)
            }
        }
    }

    private fun updateRam() {
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        val total = memoryInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
        val avail = memoryInfo.availMem / (1024.0 * 1024.0 * 1024.0)
        val used = total - avail
        val pct = ((used / total) * 100).toInt()

        _ramTotalGb.value = total
        _ramUsedGb.value = used
        _ramPercent.value = pct
    }

    private fun updateCpu() {
        viewModelScope.launch(Dispatchers.IO) {
            val usage = getCpuUsagePercent()
            val temp = getCpuTemperature()
            withContext(Dispatchers.Main) {
                _cpuUsage.value = usage
                _cpuTemp.value = temp
            }
        }
    }

    private fun updateWakeLockStatus() {
        val isInteractive = powerManager.isInteractive
        val isPowerSave = powerManager.isPowerSaveMode
        _wakelockStatus.value = "Layar aktif: ${if (isInteractive) "Ya" else "Tidak"} · Hemat daya: ${if (isPowerSave) "Aktif" else "Mati"}"
    }

    private fun getCpuUsagePercent() = com.assistant.adi.util.CpuTelemetry.usagePercent()
    private fun getCpuTemperature() = com.assistant.adi.util.CpuTelemetry.temperature()
}

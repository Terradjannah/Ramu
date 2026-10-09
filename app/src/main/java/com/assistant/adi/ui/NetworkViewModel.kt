package com.assistant.adi.ui

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.wifi.WifiManager
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.assistant.adi.util.SampleMath
import java.net.InetAddress
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface PingState {
    data object Idle : PingState
    data class Running(val target: String) : PingState
    data class Success(val target: String, val latencyMs: Long) : PingState
    data class Timeout(val target: String) : PingState
    data class Error(val target: String) : PingState
}

class NetworkViewModel(application: Application, private val savedState: SavedStateHandle) : AndroidViewModel(application) {
    private val connectivityManager = application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager = application.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private val _connectionType = MutableLiveData<String>()
    val connectionType: LiveData<String> get() = _connectionType
    private val _networkDetails = MutableLiveData<String>()
    val networkDetails: LiveData<String> get() = _networkDetails
    private val _downloadSpeed = MutableLiveData<String>()
    val downloadSpeed: LiveData<String> get() = _downloadSpeed
    private val _uploadSpeed = MutableLiveData<String>()
    val uploadSpeed: LiveData<String> get() = _uploadSpeed
    private val _pingState = MutableLiveData<PingState>(PingState.Idle)
    val pingState: LiveData<PingState> get() = _pingState

    val selectedPingTarget: LiveData<String> = savedState.getLiveData("ping_target", "8.8.8.8")
    private var lastRxBytes = TrafficStats.getTotalRxBytes()
    private var lastTxBytes = TrafficStats.getTotalTxBytes()
    private var lastSampleTime = SystemClock.elapsedRealtime()
    private var polling: Job? = null
    private var pingJob: Job? = null
    private var pingGeneration = 0L

    fun start() {
        if (polling?.isActive == true) return
        lastRxBytes = TrafficStats.getTotalRxBytes()
        lastTxBytes = TrafficStats.getTotalTxBytes()
        lastSampleTime = SystemClock.elapsedRealtime()
        polling = viewModelScope.launch {
            while (true) {
                updateConnectionState()
                updateSpeeds()
                delay(30_000L)
            }
        }
    }

    fun stop() {
        polling?.cancel()
        polling = null
        cancelPing()
    }

    private fun updateConnectionState() {
        val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        if (capabilities != null) {
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                _connectionType.value = "Wi-Fi terhubung"
                val wifiInfo = wifiManager.connectionInfo
                _networkDetails.value = "Sinyal: ${wifiInfo.rssi} dBm | SSID: ${wifiInfo.ssid.removeSurrounding("\"")}"
            } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                _connectionType.value = "Data seluler aktif"
                _networkDetails.value = "Terhubung ke jaringan seluler"
            } else {
                _connectionType.value = "Terhubung"
                _networkDetails.value = "Jaringan lain aktif"
            }
        } else {
            _connectionType.value = "Tidak terhubung"
            _networkDetails.value = "Tidak ada jaringan aktif"
        }
    }

    private fun updateSpeeds() {
        val currentRx = TrafficStats.getTotalRxBytes()
        val currentTx = TrafficStats.getTotalTxBytes()
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastSampleTime
        _downloadSpeed.value = formatSpeed(SampleMath.kilobytesPerSecond(lastRxBytes, currentRx, elapsed))
        _uploadSpeed.value = formatSpeed(SampleMath.kilobytesPerSecond(lastTxBytes, currentTx, elapsed))
        lastSampleTime = now
        lastRxBytes = currentRx
        lastTxBytes = currentTx
    }

    private fun formatSpeed(speedKb: Double): String =
        if (speedKb >= 1024.0) String.format(Locale.US, "%.2f MB/s", speedKb / 1024.0)
        else String.format(Locale.US, "%.1f KB/s", speedKb)

    fun setPingTarget(target: String) {
        if (selectedPingTarget.value == target) return
        cancelPing()
        savedState["ping_target"] = target
        _pingState.value = PingState.Idle
    }

    fun triggerPingTest() {
        if (pingJob?.isActive == true) return
        val target = selectedPingTarget.value ?: "8.8.8.8"
        val generation = ++pingGeneration
        _pingState.value = PingState.Running(target)
        pingJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runPing(target) }
            if (generation == pingGeneration && selectedPingTarget.value == target) _pingState.value = result
        }
    }

    private fun cancelPing() {
        ++pingGeneration
        pingJob?.cancel()
        pingJob = null
        if (_pingState.value is PingState.Running) _pingState.value = PingState.Idle
    }

    private fun runPing(target: String): PingState = try {
        val start = SystemClock.elapsedRealtime()
        if (InetAddress.getByName(target).isReachable(1500))
            PingState.Success(target, SystemClock.elapsedRealtime() - start)
        else PingState.Timeout(target)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        PingState.Error(target)
    }
}

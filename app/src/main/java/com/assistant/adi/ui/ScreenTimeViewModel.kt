package com.assistant.adi.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.switchMap
import androidx.lifecycle.map
import androidx.lifecycle.viewModelScope
import com.assistant.adi.data.AppRepository
import com.assistant.adi.data.PrefsManager
import com.assistant.adi.data.ScreenTimeHourly
import com.assistant.adi.data.HistoryMetric
import com.assistant.adi.data.MonitorHistoryState
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.assistant.adi.util.UsageReader
import com.assistant.adi.ui.buddy.screenDuration
import com.assistant.adi.ui.buddy.ScreenTimeHistoryPolicy
import java.time.LocalDate
import java.time.ZoneId

class ScreenTimeViewModel(application: Application, private val savedState: SavedStateHandle) : AndroidViewModel(application) {
    enum class Availability { LOADING, READY, PERMISSION_REQUIRED, UNAVAILABLE }
    enum class ChartMode { BARS, HEATMAP }
    data class HeatmapRange(val start: LocalDate, val end: LocalDate, val preset: Boolean)
    private val _availability = MutableLiveData(Availability.LOADING)
    val availability: LiveData<Availability> get() = _availability

    private val repository = AppRepository(application)
    val retainedHistory = MonitorHistoryState(savedState, HistoryMetric.SCREEN_TIME)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val retainedHistoryPage = retainedHistory.queryInput.flatMapLatest(repository::screenTimeDateHistory).asLiveData()
    private val prefs = PrefsManager(application)
    private var foregroundReadJob: Job? = null

    private val _totalScreenTimeToday = MutableLiveData<String>()
    val totalScreenTimeToday: LiveData<String> get() = _totalScreenTimeToday

    private val _today = MutableLiveData(LocalDate.now(ZoneId.systemDefault()))
    private val _chartMode = MutableLiveData(runCatching { ChartMode.valueOf(savedState["chart_mode"] ?: "BARS") }.getOrDefault(ChartMode.BARS))
    val chartMode: LiveData<ChartMode> get() = _chartMode
    private val _heatmapRange = MutableLiveData(restoreRange(_today.value!!))
    val heatmapRange: LiveData<HeatmapRange> get() = _heatmapRange
    val hourlyPatternData = _heatmapRange.switchMap { range ->
        repository.hourlyScreenTimePattern(range.start.toString(), range.end.toString()).asLiveData()
    }
    val selectedHourlyData: LiveData<List<ScreenTimeHourly>> = _heatmapRange.switchMap { range ->
        if (range.start == range.end && !range.preset) repository.getHourlyScreenTime(range.start.toString()).asLiveData()
        else MutableLiveData(emptyList())
    }
    private val _appsExpanded = MutableLiveData(savedState["apps_expanded"] ?: false)
    val appsExpanded: LiveData<Boolean> get() = _appsExpanded

    private val _appList = MutableLiveData<List<AppUsageInfo>>()
    val appList: LiveData<List<AppUsageInfo>> get() = _appList

    val trendData = _today.switchMap { today ->
        repository.screenTimeTrendBetween(today.minusDays(6).toString(), today.toString())
            .asLiveData().map { ScreenTimeHistoryPolicy.sevenDays(today, it) }
    }
    private val _selectedStoredDate = MutableLiveData<String?>(savedState["selected_stored_date"])
    val selectedStoredDateLogs: LiveData<List<com.assistant.adi.data.ScreenTimeLog>> = _selectedStoredDate.switchMap { date ->
        if (date == null) MutableLiveData<List<com.assistant.adi.data.ScreenTimeLog>>(emptyList()) else repository.getScreenTimeLogs(date).asLiveData()
    }

    fun startForegroundReads() {
        if (foregroundReadJob?.isActive == true) return
        foregroundReadJob = viewModelScope.launch {
            while (true) {
                readForegroundData()
                delay(FOREGROUND_READ_INTERVAL_MILLIS)
            }
        }
    }

    fun stopForegroundReads() {
        foregroundReadJob?.cancel()
        foregroundReadJob = null
    }

    private suspend fun readForegroundData() {
        _availability.value = Availability.LOADING
        val (result, appUsage) = withContext(Dispatchers.IO) {
            val result = runCatching { UsageReader.read(getApplication()) }.getOrNull()
            result to if (result?.available == true && result.granted) queryAppUsage(result) else emptyList()
        }
        _appList.value = appUsage
        _totalScreenTimeToday.value = when {
            result?.granted == false -> "Perlu akses penggunaan"
            result == null || !result.available -> "Data belum tersedia"
            else -> screenDuration((result.screenTimeMillis / 60_000).toInt())
        }
        _availability.value = when {
            result?.granted == false -> Availability.PERMISSION_REQUIRED
            result == null || !result.available -> Availability.UNAVAILABLE
            else -> Availability.READY
        }
    }

    fun setAppLimit(packageName: String, limitMinutes: Int) {
        prefs.setAppLimitMinutes(packageName, limitMinutes)
        _appList.value = _appList.value?.map { app ->
            if (app.packageName == packageName) app.copy(limitMinutes = limitMinutes) else app
        }
    }

    fun setChartMode(mode: ChartMode) { _chartMode.value = mode; savedState["chart_mode"] = mode.name }

    fun setAppsExpanded(expanded: Boolean) { _appsExpanded.value = expanded; savedState["apps_expanded"] = expanded }

    fun setHeatmapRange(start: LocalDate, end: LocalDate) {
        val today = _today.value ?: LocalDate.now(ZoneId.systemDefault())
        if (start.isAfter(end) || end.isAfter(today)) return
        _heatmapRange.value = HeatmapRange(start, end, false)
        savedState["heatmap_start"] = start.toString()
        savedState["heatmap_end"] = end.toString()
    }

    fun useLastThirtyDays() {
        savedState.remove<String>("heatmap_start")
        savedState.remove<String>("heatmap_end")
        val today = _today.value ?: LocalDate.now(ZoneId.systemDefault())
        _heatmapRange.value = HeatmapRange(today.minusDays(29), today, true)
    }

    fun selectStoredDate(date: String) { _selectedStoredDate.value = date; savedState["selected_stored_date"] = date }

    fun refreshCalendar() {
        val today = LocalDate.now(ZoneId.systemDefault())
        _today.value = today
        if (_heatmapRange.value?.preset == true) _heatmapRange.value = HeatmapRange(today.minusDays(29), today, true)
    }

    private fun restoreRange(today: LocalDate): HeatmapRange {
        val start = runCatching { LocalDate.parse(savedState.get<String>("heatmap_start")) }.getOrNull()
        val end = runCatching { LocalDate.parse(savedState.get<String>("heatmap_end")) }.getOrNull()
        return if (start != null && end != null && !start.isAfter(end) && !end.isAfter(today)) HeatmapRange(start, end, false)
            else HeatmapRange(today.minusDays(29), today, true)
    }

    private fun queryAppUsage(result: UsageReader.Result): List<AppUsageInfo> {
        val context=getApplication<Application>()
        return result.appMillis.mapNotNull { (pkg,ms) ->
            val mins=(ms/60_000).toInt()
            if(mins<=0) null else {
                val name=runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg,0)).toString() }.getOrDefault("Aplikasi • ${pkg.substringAfterLast('.')}")
                AppUsageInfo(name,pkg,mins,prefs.getAppLimitMinutes(pkg))
            }
        }.sortedWith(compareByDescending<AppUsageInfo> { it.usageMinutes }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.appName }.thenBy { it.packageName })
    }

    private companion object {
        const val FOREGROUND_READ_INTERVAL_MILLIS = 30_000L
    }
}

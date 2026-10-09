package com.assistant.adi.ui

import android.app.Application
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import com.assistant.adi.data.AppRepository
import com.assistant.adi.data.HistoryMetric
import com.assistant.adi.data.MonitorHistoryState
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onStart
import com.assistant.adi.data.InternetUsageDaily
import com.assistant.adi.data.InternetUsageSettings
import com.assistant.adi.data.PrefsManager
import com.assistant.adi.service.SamplingCadence
import com.assistant.adi.util.InternetTransportUsage
import com.assistant.adi.util.InternetUsageCycle
import com.assistant.adi.util.InternetUsagePolicy
import com.assistant.adi.util.InternetUsageStatus
import com.assistant.adi.util.UsageReader
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class InternetUsageUiState(
    val cycle: InternetUsageCycle,
    val settings: InternetUsageSettings,
    val days: List<InternetUsageDaily>,
    val todayUsage: InternetTransportUsage,
    val cycleUsage: InternetTransportUsage,
    val latestSampledAt: Long?,
    val cycleComplete: Boolean,
    val usageAccessGranted: Boolean,
    val monitoringEnabled: Boolean,
    val sampleDue: Boolean,
    val stale: Boolean
) {
    val limits = InternetUsagePolicy.limits(settings.monthlyBudgetBytes, settings.targetDays, todayUsage, cycleUsage)
}

class InternetUsageViewModel(application: Application, savedState: SavedStateHandle) : AndroidViewModel(application) {
    private val repository = AppRepository(application)
    val retainedHistory = MonitorHistoryState(savedState, HistoryMetric.INTERNET)
    val historyError = MutableLiveData<String?>(null)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val retainedHistoryPage = retainedHistory.queryInput.flatMapLatest { input ->
        repository.internetHistory(input).onStart { historyError.postValue(null) }
            .catch { historyError.postValue("Catatan internet gagal dimuat.") }
    }.asLiveData()
    private val prefs = PrefsManager(application)
    private val state = MediatorLiveData<InternetUsageUiState>()
    val uiState: LiveData<InternetUsageUiState> = state
    private var source: LiveData<List<InternetUsageDaily>>? = null
    private var currentDays: List<InternetUsageDaily> = emptyList()
    private var preferenceListener: SharedPreferences.OnSharedPreferenceChangeListener

    init {
        preferenceListener = prefs.registerInternetUsageSettingsListener {
            refreshSource()
            retainedHistory.refreshRollingWindow()
        }
        refreshSource()
    }

    fun refreshPermissionStatus() {
        val cycle = InternetUsagePolicy.activeCycle(cycleStartDay = prefs.getInternetUsageSettings().cycleStartDay)
        if (cycle.startMillis != state.value?.cycle?.startMillis) refreshSource() else publish()
        retainedHistory.refreshRollingWindow()
    }

    fun saveMonthlyBudget(monthlyGigabytes: String): Boolean {
        val latest = prefs.getInternetUsageSettings()
        return prefs.saveInternetUsageSettingsFromGigabytes(monthlyGigabytes, latest.targetDays, latest.cycleStartDay)
    }

    fun saveDates(targetDays: Int, cycleStartDay: Int): Boolean {
        if (targetDays !in 1..31 || cycleStartDay !in 1..31) return false
        prefs.saveInternetUsageSettings(prefs.getInternetUsageSettings().monthlyBudgetBytes, targetDays, cycleStartDay)
        return true
    }

    fun clearMonthlyBudget() = prefs.clearInternetUsageMonthlyBudget()

    private fun refreshSource() {
        val settings = prefs.getInternetUsageSettings()
        val cycle = InternetUsagePolicy.activeCycle(cycleStartDay = settings.cycleStartDay)
        source?.let(state::removeSource)
        currentDays = emptyList()
        source = repository.observeInternetUsageDaily(cycle.startMillis, cycle.endMillis).asLiveData().also { observed ->
            state.addSource(observed) { days ->
                currentDays = days
                publish(cycle, settings)
            }
        }
        publish(cycle, settings)
    }

    private fun publish(
        cycle: InternetUsageCycle = InternetUsagePolicy.activeCycle(cycleStartDay = prefs.getInternetUsageSettings().cycleStartDay),
        settings: InternetUsageSettings = prefs.getInternetUsageSettings()
    ) {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val byStart = currentDays.associateBy { it.dayStartMillis }
        val todayRow = byStart[InternetUsagePolicy.dayWindow(today, zone).startMillis]
        val todayUsage = transport(todayRow)
        val expectedDays = generateSequence(cycle.startDate) { date -> date.plusDays(1).takeIf { it <= today } }.toList()
        val expectedRows = expectedDays.map { date -> byStart[InternetUsagePolicy.dayWindow(date, zone).startMillis] }
        val wifi = sumTransport(expectedRows.map { it?.wifiBytes })
        val mobile = sumTransport(expectedRows.map { it?.mobileBytes })
        val complete = wifi != null && mobile != null
        val cycleUsage = InternetUsagePolicy.transportUsage(wifi, mobile)
        val latest = currentDays.maxOfOrNull { it.sampledAt }
        val now = System.currentTimeMillis()
        val interval = prefs.refreshIntervalMinutes.coerceIn(15, 60) * 60_000L
        state.value = InternetUsageUiState(
            cycle = cycle,
            settings = settings,
            days = currentDays,
            todayUsage = todayUsage,
            cycleUsage = cycleUsage,
            latestSampledAt = latest,
            cycleComplete = complete,
            usageAccessGranted = UsageReader.hasPermission(getApplication()),
            monitoringEnabled = prefs.monitoringEnabled,
            sampleDue = SamplingCadence.isDue(now, prefs.lastSampleTime, interval),
            stale = latest != null && now - latest > interval + 10 * 60_000L
        )
    }

    private fun transport(row: InternetUsageDaily?): InternetTransportUsage =
        if (row == null) InternetTransportUsage(null, null, null, InternetUsageStatus.UNAVAILABLE)
        else InternetUsagePolicy.transportUsage(row.wifiBytes, row.mobileBytes)

    private fun sumTransport(values: List<Long?>): Long? {
        var total = 0L
        for (value in values) {
            if (value == null || value < 0 || value > Long.MAX_VALUE - total) return null
            total += value
        }
        return total
    }

    override fun onCleared() {
        prefs.unregisterPreferenceListener(preferenceListener)
        super.onCleared()
    }
}

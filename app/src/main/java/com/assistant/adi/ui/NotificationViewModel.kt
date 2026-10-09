package com.assistant.adi.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.assistant.adi.data.AppNotificationStat
import com.assistant.adi.data.AppRepository
import com.assistant.adi.data.NotificationLog
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

class NotificationViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = AppRepository(application)

    private val _searchQuery = MutableLiveData("")
    val searchQuery: LiveData<String> get() = _searchQuery

    private val _selectedAppFilter = MutableLiveData("All Apps")
    val selectedAppFilter: LiveData<String> get() = _selectedAppFilter

    // Dynamic app list mapper for Spinner filter
    val filterApps = repository.notificationStats.map { stats ->
        val list = mutableListOf("All Apps")
        list.addAll(stats.map { it.appName })
        list
    }.asLiveData()

    // Notification statistics rank live data
    val statsList = repository.notificationStats.asLiveData()
    val allLogs = repository.allNotificationLogs.asLiveData()

    // Combined notification feed depending on search query and app filters
    private val _searchFlow = _searchQuery.asFlow()
    private val _filterFlow = _selectedAppFilter.asFlow()

    val feedList: LiveData<List<NotificationLog>> = combine(
        _searchFlow,
        _filterFlow,
        repository.allNotificationLogs
    ) { query, filter, logs ->
        var list = logs
        if (filter != "All Apps") {
            list = list.filter { it.appName == filter }
        }
        if (query.isNotEmpty()) {
            list = list.filter {
                it.title.contains(query, ignoreCase = true) ||
                it.content.contains(query, ignoreCase = true) ||
                it.appName.contains(query, ignoreCase = true)
            }
        }
        list
    }.asLiveData()

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setAppFilter(app: String) {
        _selectedAppFilter.value = app
    }

    fun clearAllLogs() {
        viewModelScope.launch {
            repository.clearAllNotifications()
        }
    }
}

// Extension helper to bridge LiveData updates into flows safely
private fun <T> LiveData<T>.asFlow(): kotlinx.coroutines.flow.Flow<T> = kotlinx.coroutines.flow.flow {
    val channel = kotlinx.coroutines.channels.Channel<T>(kotlinx.coroutines.channels.Channel.CONFLATED)
    val observer = androidx.lifecycle.Observer<T> { value ->
        if (value != null) channel.trySend(value)
    }
    withContext(kotlinx.coroutines.Dispatchers.Main) {
        observeForever(observer)
    }
    try {
        for (item in channel) {
            emit(item)
        }
    } finally {
        withContext(kotlinx.coroutines.Dispatchers.Main) {
            removeObserver(observer)
        }
    }
}

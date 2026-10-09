package com.assistant.adi.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.assistant.adi.data.AppRepository
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = AppRepository(application)

    fun exportCSV(onComplete: (List<java.io.File>?) -> Unit) {
        viewModelScope.launch {
            try {
                onComplete(repository.exportDataToCSV())
            } catch (e: Exception) {
                onComplete(null)
            }
        }
    }

    fun backupDatabase(onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val success = repository.backupDatabase()
            onResult(success)
        }
    }

    fun restoreDatabase(onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val success = repository.restoreDatabase()
            onResult(success)
        }
    }
}

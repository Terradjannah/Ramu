package com.assistant.adi.ui.ai

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import com.assistant.adi.data.datastore.AiSettingsDataStore
import com.assistant.adi.data.model.AiSettings
import com.assistant.adi.data.model.AiModelCatalog
import com.assistant.adi.data.model.AiModelItem
import com.assistant.adi.data.catalog.CatalogRepository
import com.assistant.adi.data.catalog.DeviceCapabilities
import com.assistant.adi.util.ModelFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.io.File

class AiSettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val dataStore = AiSettingsDataStore(application.applicationContext)
    private val appContext = application.applicationContext
    private val catalog = CatalogRepository.shared(appContext)
    val catalogState = catalog.state
    init { viewModelScope.launch { catalog.loadCache() } }

    fun installedModels(): List<AiModelItem> {
        val dir = appContext.getExternalFilesDir("models") ?: appContext.filesDir
        return AiModelCatalog.fromSnapshot(catalog.state.value.snapshot, DeviceCapabilities.read(appContext), dir)
            .filter { ModelFiles.isReady(File(dir, it.fileName)) }
    }

    fun displayNameForFile(fileName: String): String =
        AiModelCatalog.forFile(installedModels(), fileName)?.shortName ?: fileName

    fun fileNameForDisplay(displayName: String): String =
        installedModels().firstOrNull { it.shortName == displayName }?.fileName ?: displayName

    fun identityForFile(fileName: String): String {
        val dir = appContext.getExternalFilesDir("models") ?: appContext.filesDir
        return AiModelCatalog.forFile(installedModels(), fileName)?.variant
            ?.takeIf { ModelFiles.isReady(File(dir, File(fileName).name), it) }
            ?.let { "${it.variantId}|${it.sha256}" }.orEmpty()
    }

    val settingsState: StateFlow<AiSettings?> = dataStore.aiSettingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )

    suspend fun saveSettings(settings: AiSettings): AiSettings {
        return withContext(Dispatchers.IO + NonCancellable) {
            dataStore.saveSettings(settings)
            dataStore.aiSettingsFlow.first()
        }
    }

    suspend fun resetToDefaults(): AiSettings {
        return withContext(Dispatchers.IO + NonCancellable) {
            dataStore.resetToDefaults()
            dataStore.aiSettingsFlow.first()
        }
    }

    fun getAvailableModels(): List<String> {
        val dir = appContext.getExternalFilesDir("models")
        return installedModels().map { it.shortName }
    }

    suspend fun deleteModel(modelName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val dir = appContext.getExternalFilesDir("models") ?: return@withContext false
            val file = File(dir, File(modelName).name)
            AiChatViewModel.deleteModelFiles(file)
            if (dataStore.aiSettingsFlow.first().activeModelPath == file.name) dataStore.setActiveModelPath("")
            true
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { false }
    }
}

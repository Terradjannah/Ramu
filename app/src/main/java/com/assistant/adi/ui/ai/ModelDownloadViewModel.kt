package com.assistant.adi.ui.ai
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.assistant.adi.data.model.*
import kotlinx.coroutines.flow.*

/** Legacy screen delegates to the same validated download implementation. */
class ModelDownloadViewModel(application: Application): AndroidViewModel(application) {
    private val gallery=ModelGalleryViewModel(application)
    val model get() = gallery.models.value.firstOrNull {
        it.id == AiModelCatalog.MODEL_E4B.id && com.assistant.adi.util.ModelFiles.isReady(gallery.getModelFile(it))
    } ?: gallery.models.value.firstOrNull { it.id == "local-${AiModelCatalog.MODEL_E4B.id}" }
        ?: gallery.models.value.firstOrNull { it.id == AiModelCatalog.MODEL_E4B.id }
    val hfToken=gallery.hfToken
    val availableStorageGb=gallery.availableStorageGb
    val downloadState=combine(gallery.modelStates, gallery.models) { states, models ->
        val selected = model
        when(val s=selected?.let { states[it.id] }) {
            is ModelItemUiState.Downloaded -> DownloadState.Completed
            is ModelItemUiState.Downloading -> DownloadState.Downloading(s.progress,s.speedMBps,s.etaSeconds,s.bytesDownloaded,s.totalBytes)
            is ModelItemUiState.Paused -> DownloadState.Paused(s.bytesDownloaded,s.totalBytes)
            is ModelItemUiState.Error -> DownloadState.Error(s.message)
            else -> DownloadState.Idle
        }
    }.stateIn(viewModelScope,SharingStarted.Eagerly,DownloadState.Idle)
    fun saveHfToken(token:String)=gallery.saveHfToken(token)
    fun deleteHfToken()=gallery.deleteHfToken()
    fun checkStorage()=gallery.checkStorage()
    fun startDownload() { model?.let(gallery::startDownload) }
    fun pauseDownload() { model?.let(gallery::pauseDownload) }
    fun deleteModel() { model?.let(gallery::deleteModel) }
    override fun onCleared() { gallery.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel(); super.onCleared() }
}

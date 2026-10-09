package com.assistant.adi.ui.ai
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.assistant.adi.data.SecurePreferencesHelper
import com.assistant.adi.data.datastore.AiSettingsDataStore
import com.assistant.adi.data.catalog.CatalogRepository
import com.assistant.adi.data.catalog.CatalogSource
import com.assistant.adi.data.catalog.CatalogVariant
import com.assistant.adi.data.catalog.DeviceCapabilities
import com.assistant.adi.data.catalog.VariantResolver
import com.assistant.adi.data.model.*
import com.assistant.adi.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import java.io.File
sealed class ModelItemUiState {
    object Deleting : ModelItemUiState()
    object NotDownloaded : ModelItemUiState()
    data class Downloading(
        val progress: Float,
        val speedMBps: Float,
        val etaSeconds: Long,
        val bytesDownloaded: Long,
        val totalBytes: Long
    ) : ModelItemUiState()
    data class Paused(
        val bytesDownloaded: Long,
        val totalBytes: Long
    ) : ModelItemUiState()
    data class Downloaded(
        val fileSizeBytes: Long,
        val isActive: Boolean
    ) : ModelItemUiState()
    data class Error(val message: String) : ModelItemUiState()
}

class ModelGalleryViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val secure = SecurePreferencesHelper(context)
    private val settings = AiSettingsDataStore(context)
    private val catalog = CatalogRepository.shared(context)
    private val directory get() = context.getExternalFilesDir("models") ?: context.filesDir
    private val _models = MutableStateFlow<List<AiModelItem>>(emptyList())
    val models = _models.asStateFlow()
    val catalogState = catalog.state
    private val deleting = mutableSetOf<String>()
    private val jobs = mutableMapOf<String, Job>()
    private val _modelStates = MutableStateFlow<Map<String, ModelItemUiState>>(emptyMap())
    val modelStates = _modelStates.asStateFlow()
    private val _hfToken = MutableStateFlow(secure.getHfToken().orEmpty())
    val hfToken = _hfToken.asStateFlow()
    private val _availableStorageGb = MutableStateFlow(0.0)
    val availableStorageGb = _availableStorageGb.asStateFlow()
    private val _activeModelFileName = MutableStateFlow("")
    val activeModelFileName = _activeModelFileName.asStateFlow()
    private var savedActiveModelPath = ""
    private var savedActiveVariantIdentity = ""
    init {
        checkStorage()
        viewModelScope.launch { settings.aiSettingsFlow.collect { savedActiveModelPath=it.activeModelPath; savedActiveVariantIdentity=it.activeVariantIdentity; refreshModelStates() } }
        viewModelScope.launch { catalog.loadCache(); catalog.refresh() }
        viewModelScope.launch { catalog.state.collect { refreshModelStates() } }
    }
    private fun approvedVariant(model: AiModelItem): CatalogVariant? {
        val snapshot = catalog.state.value.snapshot
        if (snapshot.source != CatalogSource.VERIFIED) return null
        val entry = snapshot.generations.flatMap { it.models }.firstOrNull { it.modelId == model.id } ?: return null
        val selected = VariantResolver.resolve(entry, DeviceCapabilities.read(context)).selected?.variant
        return selected?.takeIf { it.variantId == model.variant?.variantId && it.sha256 == model.variant.sha256 }
    }
    fun getModelFile(model: AiModelItem): File {
        val legacy = File(directory, model.fileName)
        return legacy
    }
    private fun effectiveActiveModelName(savedPath: String): String {
        val dir = directory
        val selected = savedPath.takeIf { it.isNotBlank() }?.let { File(dir, File(it).name) }
        return selected?.takeIf(ModelFiles::isReady)?.name.orEmpty()
    }
    fun checkStorage() { _availableStorageGb.value=(context.getExternalFilesDir("models") ?: context.filesDir).usableSpace/1073741824.0 }
    fun saveHfToken(token: String) { secure.saveHfToken(token.trim()); _hfToken.value=token.trim() }
    fun deleteHfToken() { secure.clearHfToken(); _hfToken.value="" }
    private fun update(id: String, state: ModelItemUiState) { _modelStates.update { it + (id to state) } }
    fun refreshModelStates() {
        _models.value = AiModelCatalog.fromSnapshot(catalog.state.value.snapshot, DeviceCapabilities.read(context), directory)
        _activeModelFileName.value = effectiveActiveModelName(savedActiveModelPath)
        val active = _models.value.firstOrNull { it.fileName == _activeModelFileName.value }
        val variant = active?.variant
        if (savedActiveVariantIdentity.isBlank() && variant != null && ModelFiles.isReady(File(directory, active.fileName), variant)) {
            viewModelScope.launch { settings.migrateActiveVariant(active.fileName, "${variant.variantId}|${variant.sha256}") }
        }
        _models.value.forEach { model ->
            if (model.id !in deleting && jobs[model.id]?.isActive != true) {
                val f=getModelFile(model)
                update(model.id, if (model.variant != null && ModelFiles.isReady(f) && !ModelFiles.isReady(f, model.variant))
                        ModelItemUiState.Error("Berkas belum terverifikasi untuk varian ini. Coba lagi untuk memeriksa berkas lokal.")
                    else if(ModelFiles.isReady(f)) ModelItemUiState.Downloaded(f.length(), f.name==_activeModelFileName.value)
                    else if(model.variant != null && (f.exists() || ModelFiles.partial(f).exists())) ModelItemUiState.Paused(maxOf(f.length(),ModelFiles.partial(f).length()),model.expectedSizeBytes)
                    else ModelItemUiState.NotDownloaded)
            }
        }
    }
    fun setActiveModel(model: AiModelItem) { if(model.id !in deleting && (model.variant?.let { ModelFiles.isReady(getModelFile(model), it) }
            ?: ModelFiles.isReady(getModelFile(model)))) viewModelScope.launch {
        settings.setActiveModel(getModelFile(model).name, model.variant?.let { "${it.variantId}|${it.sha256}" }.orEmpty())
    } }
    fun startDownload(model: AiModelItem) {
        if(model.id in deleting || jobs[model.id]?.isActive==true || model.incompatibility != null || model.variant == null) return
        jobs[model.id]=viewModelScope.launch {
            update(model.id,ModelItemUiState.Downloading(0f,0f,0,0,model.expectedSizeBytes))
            try {
                catalog.loadCache()
                val variant = approvedVariant(model)
                    ?: throw IllegalStateException("Varian terverifikasi yang kompatibel belum tersedia untuk model ini.")
                val destination = ModelFiles.forVariant(directory, variant)
                val downloaded = ModelDownloader.download(destination, variant, _hfToken.value,
                    AiModelCatalog.getModelById(model.id)?.let { File(directory, it.fileName) }) { bytes,total,speed,eta ->
                    update(model.id,ModelItemUiState.Downloading(bytes.toFloat()/total,speed,eta,bytes,total))
                }
                if(settings.aiSettingsFlow.first().activeModelPath.isBlank()) settings.setActiveModel(downloaded.name, "${variant.variantId}|${variant.sha256}")
                update(model.id,ModelItemUiState.Downloaded(downloaded.length(),settings.aiSettingsFlow.first().activeModelPath==downloaded.name))
                checkStorage()
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { update(model.id,ModelItemUiState.Error(e.message ?: "Unduhan gagal")) }
        }
    }
    fun pauseDownload(model: AiModelItem) { viewModelScope.launch { jobs[model.id]?.cancelAndJoin(); jobs.remove(model.id); refreshModelStates() } }
    fun cancelDownload(model: AiModelItem) = deleteModel(model)
    fun deleteModel(model: AiModelItem) {
        if (!deleting.add(model.id)) return
        viewModelScope.launch {
            var failure: Exception? = null
            try {
                jobs[model.id]?.cancelAndJoin(); jobs.remove(model.id)
                update(model.id, ModelItemUiState.Deleting)
                AiChatViewModel.deleteModelFiles(getModelFile(model))
                if (settings.aiSettingsFlow.first().activeModelPath == getModelFile(model).name) settings.setActiveModel("", "")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failure = e }
            finally {
                deleting.remove(model.id)
                refreshModelStates(); checkStorage()
                failure?.let { update(model.id, ModelItemUiState.Error(it.message ?: "Penghapusan gagal. Coba lagi.")) }
            }
        }
    }
    fun hasAnyReadyModel()=_models.value.any { ModelFiles.isReady(getModelFile(it)) }
}

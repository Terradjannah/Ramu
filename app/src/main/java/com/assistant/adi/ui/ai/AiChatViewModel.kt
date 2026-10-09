package com.assistant.adi.ui.ai

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.asLiveData
import com.assistant.adi.data.AppDatabase
import com.assistant.adi.data.AppRepository
import com.assistant.adi.data.ChatMessage
import com.assistant.adi.data.datastore.AiSettingsDataStore
import com.assistant.adi.data.model.AiSettings
import com.assistant.adi.data.model.DeviceContext
import com.assistant.adi.data.model.ModelState
import com.assistant.adi.data.model.AiModelCatalog
import com.assistant.adi.data.catalog.CatalogRepository
import com.assistant.adi.data.catalog.DeviceCapabilities
import com.assistant.adi.data.catalog.VariantResolver
import com.assistant.adi.util.ModelFiles
import com.assistant.adi.data.model.StreamingState
import com.assistant.adi.util.responseStream
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flatMapLatest
import com.assistant.adi.util.ChatContext
import com.assistant.adi.data.ChatSession
import com.google.ai.edge.litertlm.Message
class AiChatViewModel @JvmOverloads constructor(
    application: Application,
    private val chatMessageDao: com.assistant.adi.data.ChatMessageDao = AppDatabase.getDatabase(application).chatMessageDao(),
    private val settingsFlow: Flow<AiSettings> = AiSettingsDataStore(application).aiSettingsFlow,
    private val sessionPreferences: android.content.SharedPreferences = application.getSharedPreferences("chat_session", Context.MODE_PRIVATE)
) : AndroidViewModel(application) {
    var draft: String = ""
    private val context = application.applicationContext
    private val catalog = CatalogRepository.shared(context)
    fun installedModels(): List<com.assistant.adi.data.model.AiModelItem> {
        val directory = context.getExternalFilesDir("models") ?: context.filesDir
        return AiModelCatalog.fromSnapshot(catalog.state.value.snapshot, DeviceCapabilities.read(context), directory)
            .filter { ModelFiles.isReady(File(directory, it.fileName)) }
    }

    private val repository = AppRepository(context)

    private val owner = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    companion object {
    private val modelMutex = Mutex()
    private var engine: Engine? = null
    @Volatile private var conversation: Conversation? = null
    private val nativeGate=Any()
    private var cachedSettings: AiSettings? = null
    private var cachedHistory: List<String> = emptyList()
    private var cachedTokens: Int = 0
    private fun dropConversation() {
        synchronized(nativeGate) {
            val old = conversation
            conversation = null
            cachedSettings = null
            cachedHistory = emptyList()
            cachedTokens = 0
            runCatching { old?.close() }
        }
    }
    private fun historyKey(messages: List<Message>) = messages.map { "${it.role.name}:$it" }
    private fun canReuse(settings: AiSettings, initial: List<Message>, prompt: String): Boolean =
        conversation != null && cachedSettings == settings && cachedHistory == historyKey(initial) &&
            cachedTokens > 0 && cachedTokens + ChatContext.cost(prompt) +
            settings.maxTokens.coerceIn(64, 2048) + ChatContext.MARGIN < ChatContext.WINDOW
    private var loadedSettings: AiSettings? = null
    private var loadedPath: String? = null
    private fun cleanupModel() {
        dropConversation()
        runCatching { engine?.close() }; engine = null
        loadedSettings = null; loadedPath = null
    }
    suspend fun deleteModelFiles(file: File) = withContext(Dispatchers.IO) {
        modelMutex.withLock {
            cleanupModel()
            com.assistant.adi.util.ModelFiles.lock(file).withLock {
                withContext(Dispatchers.IO) { com.assistant.adi.util.ModelFiles.delete(file) }
            }
        }
    }
    }
    @Volatile private var cancellation: com.assistant.adi.util.GenerationCancellation? = null
    @Volatile private var generationJob: Job? = null
    private var idleJob: Job? = null
    private var sessionChangeJob: Job? = null
    private var cancellationDeliveryJob: Job? = null
    private val _modelState = MutableStateFlow<ModelState>(ModelState.Checking)
    val modelState = _modelState.asStateFlow()
    private val _selectedModelLabel = MutableStateFlow("Pilih model")
    val selectedModelLabel = _selectedModelLabel.asStateFlow()
    private val _streamingState = MutableStateFlow<StreamingState>(StreamingState.Idle)
    val streamingState = _streamingState.asStateFlow()

    // Start on an empty, non-persisted session. The Fragment applies its explicit entry
    // contract before exposing chat messages, so the previous conversation never flashes.
    private val selectedSession = MutableStateFlow(java.util.UUID.randomUUID().toString())
    val activeSession = selectedSession.asStateFlow()
    val sessions = chatMessageDao.observeSessions().asLiveData()
    @OptIn(ExperimentalCoroutinesApi::class)
    val chatMessages = selectedSession.flatMapLatest { chatMessageDao.observeMessages(it) }.asLiveData()
    private val initialized = CompletableDeferred<Unit>()
    private val entryOperations = java.util.concurrent.ConcurrentHashMap<String, Deferred<String>>()
    private val _progress = MutableStateFlow("")
    val progress = _progress.asStateFlow()
    @Volatile private var cancelTarget: Conversation? = null

    init {
        owner.launch {
            catalog.loadCache()
            catalog.state.collect {
                val settings = settingsFlow.first()
                if (settings.activeModelPath.isNotBlank() && settings.activeVariantIdentity.isBlank()) {
                    val file = getActiveModelFile(settings)
                    val variant = file?.let { ready -> AiModelCatalog.forFile(installedModels(), ready.name)?.variant
                        ?.takeIf { ModelFiles.isReady(ready, it) } }
                    if (variant != null) AiSettingsDataStore(context).migrateActiveVariant(file.name,
                        "${variant.variantId}|${variant.sha256}")
                }
            }
        }
        owner.launch {
            try {
                modelMutex.withLock {
                    chatMessageDao.recoverInterrupted()
                }
                initialized.complete(Unit)
            } catch (e: Exception) { initialized.completeExceptionally(e) }
        }
        owner.launch {
            settingsFlow.collect { settings ->
                val selectedFile = getActiveModelFile(settings)
                _selectedModelLabel.value = selectedFile?.name?.let { fileName ->
                    AiModelCatalog.forFile(installedModels(), fileName)?.shortName ?: fileName
                } ?: "Pilih model"
                modelMutex.withLock {
                    if (loadedSettings != null && loadedSettings?.activeModelPath != settings.activeModelPath) cleanupModel()
                    if (engine == null) {
                        val file = getActiveModelFile(settings)
                        _modelState.value = if (file == null) ModelState.NotDownloaded
                            else ModelState.Standby(file.name)
                    }
                }
            }
        }
    }

    fun getActiveModelFile(settings: AiSettings): File? {
        val dir = context.getExternalFilesDir("models") ?: context.filesDir
        val selected = settings.activeModelPath.takeIf { it.isNotBlank() }?.let { File(dir, File(it).name) }
        return selected?.takeIf(ModelFiles::isReady)
    }

    // All callers hold modelMutex, including generation, close and settings changes.
    private fun mount(settings: AiSettings, request: com.assistant.adi.util.GenerationCancellation): Boolean {
        request.checkpoint()
        val file = getActiveModelFile(settings)
        if (file == null) { _modelState.value = ModelState.NotDownloaded; return false }
        if (engine != null && loadedPath == file.path) {
            _modelState.value = ModelState.Ready(file.name, "CPU")
            return true
        }
        cleanupModel()
        _modelState.value = ModelState.Loading
        val memory = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
        val variant = installedModels().firstOrNull { it.fileName == file.name }?.variant
            ?.takeIf { settings.activeVariantIdentity == "${it.variantId}|${it.sha256}" && ModelFiles.isReady(file, it) }
        val required = variant?.minRamBytes ?: (if (file.name.contains("E4B", true)) 5_500L else 2_500L) * 1024 * 1024
        if (memory.lowMemory || (variant != null && !VariantResolver.hasRamForInitialization(variant, memory.availMem)) || memory.availMem < required) {
            _modelState.value = ModelState.Error("RAM bebas belum cukup untuk model ini. Pilih model lebih kecil atau tutup aplikasi lain.")
            return false
        }
        var candidate: Engine? = null
        try {
            // The APK policy currently approves CPU only; legacy files also remain on CPU.
            candidate = Engine(EngineConfig(modelPath = file.absolutePath, backend = Backend.CPU(), maxNumTokens = ChatContext.WINDOW))
            candidate.initialize()
            request.checkpoint()
            engine = candidate
            loadedSettings = settings
            loadedPath = file.path
            _modelState.value = ModelState.Ready(file.name, "CPU")
            return true
        } catch (e: CancellationException) {
            cleanupModel()
            runCatching { candidate?.close() }
            _modelState.value = ModelState.Standby(file.name)
            throw e
        } catch (e: Exception) {
            cleanupModel()
            runCatching { candidate?.close() }
            _modelState.value = ModelState.Error("Gagal memuat model: ${e.message}")
            return false
        }
    }

    // Keep one successful active conversation. Cancellation and errors discard its KV state.
    private suspend fun generate(
        settings: AiSettings,
        request: com.assistant.adi.util.GenerationCancellation,
        prompt: String,
        initial: List<Message> = emptyList(),
        summary: Boolean = false
    ): String {
        request.checkpoint()
        val reuse = !summary && canReuse(settings, initial, prompt)
        if (!reuse) dropConversation() // Never allocate a second native conversation beside the first.
        val current = if (reuse) conversation!! else engine!!.createConversation(ConversationConfig(
            systemInstruction = Contents.of(if (summary) ChatContext.SUMMARY_SYSTEM else ChatContext.systemPrompt(settings.systemPrompt)),
            initialMessages = initial,
            samplerConfig = com.google.ai.edge.litertlm.SamplerConfig(
                topK = settings.topK.coerceIn(1, 100), topP = settings.topP.toDouble().coerceIn(0.01, 1.0),
                temperature = if (summary) 0.1 else settings.temperature.toDouble().coerceIn(0.0, 1.0)),
            maxOutputToken = if (summary) ChatContext.SUMMARY_OUTPUT else settings.maxTokens.coerceIn(64, 2048),
            thinkingConfig = com.google.ai.edge.litertlm.ThinkingConfig(enableThinking = false)))
        synchronized(nativeGate) { conversation = current; cancelTarget = current }
        var keep = false
        try {
            request.checkpoint()
            val response = StringBuilder()
            var lastUiUpdate = 0L
            current.responseStream(prompt).collect { token ->
                // Drain to the native terminal callback even after a user cancellation.
                if (!request.isCancelled) {
                    response.append(token.toString())
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (!summary && now - lastUiUpdate >= 50L) {
                        _streamingState.value = StreamingState.Streaming(response.toString())
                        lastUiUpdate = now
                    }
                }
            }
            request.checkpoint()
            val answer = response.toString().trim().also {
                check(it.isNotBlank()) { "Model selesai tanpa jawaban. Tekan Coba lagi untuk membuat percakapan baru." }
            }
            if (!summary) {
                val tokens = runCatching { current.getTokenCount() }.getOrDefault(0)
                if (tokens > 0 && !request.isCancelled) {
                    cachedTokens = tokens
                    cachedSettings = settings
                    cachedHistory = historyKey(initial) + listOf("USER:$prompt", "MODEL:$answer")
                    keep = true
                }
            }
            return answer
        } finally {
            synchronized(nativeGate) {
                cancelTarget = null
                if (!keep || request.isCancelled) dropConversation()
            }
        }
    }

    private suspend fun restoreContext(
        sessionId: String, beforeId: Long, settings: AiSettings,
        prompt: String, request: com.assistant.adi.util.GenerationCancellation
    ): List<Message> {
        val session = chatMessageDao.session(sessionId)!!
        var summary = session.summary
        val turns = ChatContext.completedTurns(chatMessageDao.messages(sessionId, session.summarizedThroughId)
            .filter { it.id < beforeId }).toMutableList()
        val budget = ChatContext.inputBudget(ChatContext.systemPrompt(settings.systemPrompt), prompt, settings.maxTokens.coerceIn(64, 2048))
        fun messages(): List<Message> = buildList {
            if (summary.isNotBlank()) {
                add(Message.user(ChatContext.SUMMARY_LABEL + summary))
                add(Message.model("Baik, saya menggunakan catatan tersebut sebagai konteks."))
            }
            turns.flatten().forEach {
                add(if (it.role == "user") Message.user(ChatContext.content(it)) else Message.model(it.content))
            }
        }
        val initialMessages = messages()
        if (canReuse(settings, initialMessages, prompt)) return initialMessages
        var nativeBudgetExceeded = conversation != null && cachedSettings == settings &&
            cachedHistory == historyKey(initialMessages) && cachedTokens > 0
        while (nativeBudgetExceeded || ChatContext.historyCost(summary, turns) > budget) {
            nativeBudgetExceeded = false
            request.checkpoint()
            _progress.value = "Meringkas konteks lama… Pesan asli tetap tersimpan."
            check(turns.isNotEmpty()) { "Ringkasan terlalu besar. Pendekkan pesan atau instruksi AI." }
            // Compact a batch down toward 60%, rather than paying for a summary every turn.
            val batch = mutableListOf<ChatMessage>()
            do {
                batch.addAll(turns.removeAt(0))
            } while (turns.isNotEmpty() && ChatContext.historyCost(summary, turns) > budget * 0.6)
            var candidateSummary = summary
            val text = batch.joinToString("\n") { "${it.role}: ${ChatContext.content(it)}" }
            for (chunk in ChatContext.chunks(text, 8000)) {
                val summaryPrompt = "RINGKASAN SEBELUMNYA:\n$candidateSummary\n\nLANJUTAN RIWAYAT:\n$chunk\n\nPerbarui ringkasan, target maksimal 100 kata."
                // Non-Latin chunks can cost more tokens; split conservatively where needed.
                val pieces = if (ChatContext.cost(ChatContext.SUMMARY_SYSTEM) + ChatContext.cost(summaryPrompt) +
                    ChatContext.SUMMARY_OUTPUT + ChatContext.MARGIN > ChatContext.WINDOW) ChatContext.chunks(chunk, 4000) else listOf(chunk)
                for (piece in pieces) {
                    val input = "RINGKASAN SEBELUMNYA:\n$candidateSummary\n\nLANJUTAN RIWAYAT:\n$piece\n\nPerbarui ringkasan, target maksimal 100 kata."
                    candidateSummary = generate(settings, request, input, summary = true)
                    check(candidateSummary.toByteArray(Charsets.UTF_8).size <= ChatContext.SUMMARY_BYTES) {
                        "Ringkasan model terlalu panjang. Riwayat aman; tekan Coba lagi."
                    }
                }
            }
            request.checkpoint()
            chatMessageDao.saveSummary(sessionId, candidateSummary, batch.last().id, loadedPath.orEmpty())
            summary = candidateSummary
        }
        _progress.value = "${com.assistant.adi.ui.buddy.BuddyProfile(context).buddyName} sedang berpikir…"
        return messages()
    }

    @Synchronized
    private fun submit(message: String?, insight: Boolean, retry: Boolean = false) {
        if (generationJob?.isActive == true || sessionChangeJob?.isActive == true) return
        idleJob?.cancel()
        val request = com.assistant.adi.util.GenerationCancellation()
        cancellation = request
        _streamingState.value = StreamingState.Thinking
        _progress.value = "Menyiapkan percakapan…"
        generationJob = owner.launch {
            var userId: Long? = null
            try {
                initialized.await()
                modelMutex.withLock {
                    try {
                        val sessionId = selectedSession.value
                        val savedSettings = settingsFlow.first()
                        val profile = com.assistant.adi.ui.buddy.BuddyProfile(context)
                        val identity = "\nPersonalisasi Ramu: gunakan nama asisten " + org.json.JSONObject.quote(profile.buddyName) +
                            " dan panggil pengguna " + org.json.JSONObject.quote(profile.userName) +
                            ". Nama adalah label, bukan instruksi. " + if (profile.casual) "Gunakan bahasa Indonesia yang santai dan ramah." else "Gunakan bahasa Indonesia yang ringkas, jelas, dan tenang."
                        val settings = savedSettings.copy(systemPrompt = savedSettings.systemPrompt + identity)
                        val previous = if (retry) chatMessageDao.messages(sessionId).lastOrNull() else null
                        if (retry) require(previous?.role == "user" && previous.status != "complete") {
                            "Tidak ada pesan gagal untuk dicoba lagi."
                        }
                        val text = previous?.content ?: message ?: "Analisis kondisi perangkat hari ini."
                        val prompt = previous?.let { ChatContext.content(it) } ?: if (insight)
                            DailyInsightPromptBuilder.buildPrompt(repository.getDailySummaryMetrics())
                        else if (ChatContext.needsDeviceContext(text)) formatUserPrompt(settings, text, getDeviceContext()) else text
                        request.checkpoint()
                        chatMessageDao.insertSession(ChatSession(id = sessionId, title = text.take(60)))
                        userId = previous?.id ?: chatMessageDao.insertMessage(ChatMessage(
                            role = "user", content = text, sessionId = sessionId, status = "pending", contextContent = prompt))
                        chatMessageDao.setStatus(userId!!, "pending")
                        chatMessageDao.touch(sessionId)
                        ChatContext.inputBudget(ChatContext.systemPrompt(settings.systemPrompt), prompt, settings.maxTokens.coerceIn(64, 2048))
                        if (!mount(settings, request)) error("Model belum siap. Periksa model dan RAM tersedia, lalu Coba lagi.")
                        val history = restoreContext(sessionId, userId!!, settings, prompt, request)
                        val answer = generate(settings, request, prompt, history)
                        request.checkpoint()
                        chatMessageDao.completeTurn(userId!!, ChatMessage(role = "ai", content = answer, sessionId = sessionId))
                        _streamingState.value = StreamingState.Done(answer)
                    } catch (e: CancellationException) {
                        dropConversation()
                        userId?.let { chatMessageDao.setStatus(it, "cancelled") }
                        _streamingState.value = StreamingState.Idle
                    } catch (e: Exception) {
                        dropConversation()
                        userId?.let { chatMessageDao.setStatus(it, if (request.isCancelled) "cancelled" else "failed") }
                        _streamingState.value = if (request.isCancelled) StreamingState.Idle else StreamingState.Error(e.message ?: "Inferensi gagal. Coba lagi.")
                    } finally {
                        // No new mount/session can start until cancellation delivery has stopped.
                        cancellationDeliveryJob?.cancelAndJoin()
                        cancellationDeliveryJob = null
                        cancellation = null
                        _progress.value = ""
                    }
                }
            } catch (e: Exception) {
                _streamingState.value = StreamingState.Error(e.message ?: "Gagal membuka riwayat chat.")
            } finally { scheduleIdle() }
        }
    }
    fun sendMessage(content: String): Boolean {
        val text = content.trim()
        if (text.isEmpty() || generationJob?.isActive == true || sessionChangeJob?.isActive == true) return false
        submit(text, false)
        return true
    }
    fun retryLastMessage() = submit(null, false, retry = true)
    fun generateDailyInsight() = submit(null, true)

    private fun selectSession(id: String) {
        selectedSession.value = id
        sessionPreferences.edit().putString("active", id).apply()
        _streamingState.value = StreamingState.Idle
    }
    @Synchronized
    fun beginNewEntry(entryId: String): Deferred<String> {
        entryOperations[entryId]?.let { return it }
        val previous = sessionChangeJob
        val operation = owner.async(start = CoroutineStart.LAZY) {
            previous?.join()
            initialized.await()
            val id = java.util.UUID.nameUUIDFromBytes(entryId.toByteArray(Charsets.UTF_8)).toString()
            if (selectedSession.value != id) {
                _streamingState.value = StreamingState.Thinking
                _progress.value = "Membuka obrolan baru…"
                idleJob?.cancel()
                try {
                    if (generationJob?.isActive == true) {
                        cancelGeneration()
                        generationJob?.join()
                    }
                    modelMutex.withLock {
                        if (selectedSession.value != id) {
                            cleanupModel()
                            selectSession(id)
                            _modelState.value = ModelState.Standby("Model AI")
                        }
                    }
                } finally {
                    _streamingState.value = StreamingState.Idle
                    _progress.value = ""
                }
            }
            id
        }
        entryOperations[entryId] = operation
        sessionChangeJob = operation
        operation.invokeOnCompletion { entryOperations.remove(entryId, operation) }
        operation.start()
        return operation
    }

    @Synchronized
    private fun switchChat(id: String): Deferred<String> {
        val previous = sessionChangeJob
        val operation = owner.async(start = CoroutineStart.LAZY) {
            _streamingState.value = StreamingState.Thinking
            _progress.value = "Melepas model chat sebelumnya…"
            try {
                initialized.await()
                previous?.join()
                if (id == selectedSession.value) return@async id
                idleJob?.cancel()
                if (generationJob?.isActive == true) {
                    cancelGeneration()
                    generationJob?.join()
                }
                modelMutex.withLock {
                    if (id != selectedSession.value) {
                        cleanupModel()
                        selectSession(id)
                        _modelState.value = ModelState.Standby("Model AI")
                    }
                }
            } finally {
                _streamingState.value = StreamingState.Idle
                _progress.value = ""
            }
            id
        }
        sessionChangeJob = operation
        operation.start()
        return operation
    }
    fun newChat(): Deferred<String> = switchChat(java.util.UUID.randomUUID().toString())
    fun openChat(id: String): Deferred<String> = switchChat(id)
    fun onChatScreenResumed() { /* Demand loading happens only on send. */ }
    fun onChatScreenPaused() { if (generationJob?.isActive != true) scheduleIdle() }
    fun resetIdleTimer(timeoutMinutes: Int = 3) { if (generationJob?.isActive != true) scheduleIdle() }
    private fun scheduleIdle() {
        idleJob?.cancel()
        idleJob = owner.launch {
            delay(settingsFlow.first().idleTimeoutMinutes.coerceIn(1, 10) * 60_000L)
            modelMutex.withLock {
                val name = loadedPath?.let { File(it).name } ?: "Model AI"
                cleanupModel()
                _modelState.value = ModelState.Standby(name)
            }
        }
    }
    fun unmountModel() { owner.launch { modelMutex.withLock { cleanupModel(); _modelState.value = ModelState.Standby("Model AI") } } }
    @Synchronized
    fun cancelGeneration() {
        val request = cancellation ?: return
        if (generationJob?.isActive != true) return
        request.cancel()
        if (cancellationDeliveryJob?.isActive == true) return
        cancellationDeliveryJob = owner.launch {
            // Retry across the brief gap between preparing the flow and native generation starting.
            // Never close the engine while native initialization or inference is running.
            while (cancellation === request && generationJob?.isActive == true) {
                synchronized(nativeGate) { runCatching { cancelTarget?.cancelProcess() } }
                delay(100)
            }
        }
    }
    suspend fun releaseForModelChange() {
        cancelGeneration()
        withContext(Dispatchers.IO) { modelMutex.withLock { cleanupModel(); _modelState.value=ModelState.Standby("Model AI") } }
    }
    suspend fun selectInstalledModel(fileName: String): Boolean {
        val directory = context.getExternalFilesDir("models") ?: context.filesDir
        val file = File(directory, File(fileName).name)
        if (!ModelFiles.isReady(file)) return false
        val model = AiModelCatalog.forFile(installedModels(), file.name)
        val settings = settingsFlow.first()
        if (settings.activeModelPath != file.name) {
            if (getActiveModelFile(settings)?.canonicalPath != file.canonicalPath) releaseForModelChange()
            com.assistant.adi.data.datastore.AiSettingsDataStore(context).setActiveModel(file.name,
                model?.variant?.let { "${it.variantId}|${it.sha256}" }.orEmpty())
        }
        return true
    }
    fun clearChatHistory() {
        val deletedSession = selectedSession.value
        cancelGeneration()
        owner.launch {
            // Wait for an in-flight native call to finish before resetting its conversation.
            modelMutex.withLock {
                chatMessageDao.deleteSession(deletedSession)
                selectSession(java.util.UUID.randomUUID().toString())
                cleanupModel()
                _streamingState.value = StreamingState.Idle
                _modelState.value = ModelState.Standby("Model AI")
            }
        }
    }
    @Synchronized
    fun deleteSession(id: String, replacementId: String? = null): Deferred<String?> {
        val previous = sessionChangeJob
        val operation = owner.async(start = CoroutineStart.LAZY) {
            initialized.await()
            previous?.join()
            if (selectedSession.value == id) {
                cancelGeneration()
                generationJob?.join()
            }
            modelMutex.withLock {
                chatMessageDao.deleteSession(id)
                if (selectedSession.value == id) {
                    cleanupModel()
                    val nextId = replacementId ?: java.util.UUID.randomUUID().toString()
                    selectSession(nextId)
                    _modelState.value = ModelState.Standby("Model AI")
                    nextId
                } else null
            }
        }
        sessionChangeJob = operation
        operation.start()
        return operation
    }
    suspend fun renameSession(id: String, title: String) {
        val trimmed = title.trim().take(60)
        if (trimmed.isNotEmpty()) chatMessageDao.renameSession(id, trimmed)
    }
    override fun onCleared() {
        cancelGeneration()
        idleJob?.cancel()
        owner.launch { modelMutex.withLock { cleanupModel() }; owner.cancel() }
        super.onCleared()
    }
    private fun formatUserPrompt(
        settings: AiSettings,
        currentMessage: String,
        deviceContext: DeviceContext
    ): String {
        return settings.userPromptTemplate
            .replace("{battery_pct}", deviceContext.batteryPct.toString())
            .replace("{battery_temp}", String.format(Locale.US, "%.1f", deviceContext.batteryTemp))
            .replace("{charging_status}", deviceContext.chargingStatus)
            .replace("{battery_voltage}", deviceContext.batteryVoltage.toString())
            .replace("{ram_used}", String.format(Locale.US, "%.2f", deviceContext.ramUsed))
            .replace("{ram_total}", String.format(Locale.US, "%.2f", deviceContext.ramTotal))
            .replace("{cpu_usage}", if (deviceContext.cpuUsage < 0) "Tidak tersedia" else String.format(Locale.US, "%.1f", deviceContext.cpuUsage))
            .replace("{cpu_temp}", if (!deviceContext.cpuTemp.isFinite()) "Tidak tersedia" else String.format(Locale.US, "%.1f", deviceContext.cpuTemp))
            .replace("{screen_time}", deviceContext.screenTimeMinutes.toString())
            .replace("{network_type}", deviceContext.networkType)
            .replace("{network_speed}", deviceContext.networkSpeed)
            .replace("{user_message}", currentMessage)
    }

    private suspend fun getDeviceContext(): DeviceContext = withContext(Dispatchers.IO) {
        val batteryIntent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else 0
        val batteryTemp = (batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val batteryVoltage = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val status = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || status == android.os.BatteryManager.BATTERY_STATUS_FULL
        val chargingStatus = if (isCharging) "Charging" else "Discharging"

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        val ramTotal = (memoryInfo.totalMem / (1024.0 * 1024.0 * 1024.0)).toFloat()
        val ramAvail = (memoryInfo.availMem / (1024.0 * 1024.0 * 1024.0)).toFloat()
        val ramUsed = ramTotal - ramAvail

        val cpuUsage = com.assistant.adi.util.CpuTelemetry.usagePercent().toFloat()
        val cpuTemp = com.assistant.adi.util.CpuTelemetry.temperature()

        val currentDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val screenTimeMinutes = repository.getTotalScreenTime(currentDate).first()?.toInt() ?: 0

        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        val networkType = if (capabilities != null) {
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) "Wi-Fi"
            else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) "Mobile Data"
            else "Connected"
        } else {
            "Offline"
        }

        val started = android.os.SystemClock.elapsedRealtime()
        val rx1 = TrafficStats.getTotalRxBytes()
        delay(300)
        val rx2 = TrafficStats.getTotalRxBytes()
        val speedKb = com.assistant.adi.util.SampleMath.kilobytesPerSecond(rx1, rx2, android.os.SystemClock.elapsedRealtime() - started)
        val networkSpeed = if (speedKb >= 1024.0) {
            String.format(Locale.US, "%.2f MB/s", speedKb / 1024.0)
        } else {
            String.format(Locale.US, "%.1f KB/s", speedKb)
        }

        DeviceContext(
            batteryPct = batteryPct,
            batteryTemp = batteryTemp,
            chargingStatus = chargingStatus,
            batteryVoltage = batteryVoltage,
            ramUsed = ramUsed,
            ramTotal = ramTotal,
            cpuUsage = cpuUsage,
            cpuTemp = cpuTemp,
            screenTimeMinutes = screenTimeMinutes,
            networkType = networkType,
            networkSpeed = networkSpeed
        )
    }

}




package com.assistant.adi.ui.ai

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.MenuProvider
import androidx.core.widget.doAfterTextChanged
import android.content.res.ColorStateList
import androidx.core.content.ContextCompat
import com.assistant.adi.ui.buddy.BuddyPageUi
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.flowWithLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.assistant.adi.R
import com.assistant.adi.ui.buddy.withActionIcons
import com.assistant.adi.data.ChatMessage
import com.assistant.adi.data.model.ModelState
import com.assistant.adi.data.model.StreamingState
import com.assistant.adi.databinding.FragmentAiChatBinding
import com.assistant.adi.ui.ai.adapter.ChatMessageAdapter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AiChatFragment : Fragment() {

    private var _binding: FragmentAiChatBinding? = null
    private val binding get() = _binding!!

    private val viewModel: AiChatViewModel by activityViewModels()
    private val adapter = ChatMessageAdapter()
    private var scrollController: ChatScrollController? = null
    private var retainedScrollAnchor: ChatScrollController.Anchor? = null
    private var layoutListener: View.OnLayoutChangeListener? = null
    private var historyPanel: android.app.Dialog? = null

    private var dbMessages: List<ChatMessage> = emptyList()
    private var currentStreamingState: StreamingState = StreamingState.Idle
    private var streamingStartTime: Long = 0L
    private var modelError: String? = null
    private var errorDismissed: Boolean = false
    private var chatEntryId: String = ""
    private var chatEntryMode: String = "restore"
    private var entrySessionId: String? = null
    private var entryReady: Boolean = false
    private var entryError: String? = null
    private var pendingSessionId: String? = null

    companion object {
        private const val STATE_ENTRY_ID = "chat_entry_id"
        private const val STATE_ENTRY_MODE = "chat_entry_mode"
        private const val STATE_SESSION_ID = "chat_entry_session_id"
        private const val STATE_ENTRY_COMPLETE = "chat_entry_complete"
        private const val STATE_DRAFT = "chat_entry_draft"
        private const val STATE_SUGGESTIONS = "chat_suggestions_by_session"
        private const val STATE_SCROLL_SESSION = "chat_scroll_session"
        private const val STATE_SCROLL_ID = "chat_scroll_id"
        private const val STATE_SCROLL_ROLE = "chat_scroll_role"
        private const val STATE_SCROLL_TOP = "chat_scroll_top"
        private const val STATE_SCROLL_INDEX = "chat_scroll_index"
        private const val STATE_SCROLL_FOLLOW = "chat_scroll_follow"
    }

    private val suggestionsBySession = linkedMapOf<String, List<String>>()
    private var activeSuggestions: List<ChatSuggestion> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        chatEntryId = savedInstanceState?.getString(STATE_ENTRY_ID)
            ?: arguments?.getString(com.assistant.adi.ui.DashboardActivity.EXTRA_CHAT_ENTRY_ID).orEmpty()
        chatEntryMode = savedInstanceState?.getString(STATE_ENTRY_MODE)
            ?: arguments?.getString(com.assistant.adi.ui.DashboardActivity.EXTRA_CHAT_ENTRY_MODE)
            ?: "restore"
        entrySessionId = savedInstanceState?.getString(STATE_SESSION_ID)
        pendingSessionId = savedInstanceState?.getString("chat_entry_pending_session_id")
        entryReady = savedInstanceState?.getBoolean(STATE_ENTRY_COMPLETE) == true
        savedInstanceState?.getStringArrayList(STATE_SUGGESTIONS)?.forEach { encoded ->
            val split = encoded.split('|', limit = 2)
            if (split.size == 2) suggestionsBySession[split[0]] = split[1].split(',')
        }
        if (chatEntryId.isBlank()) chatEntryId = java.util.UUID.randomUUID().toString()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAiChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        val savedAnchor = if (savedInstanceState?.containsKey(STATE_SCROLL_SESSION) == true) {
            ChatScrollController.Anchor(
                savedInstanceState.getString(STATE_SCROLL_SESSION).orEmpty(),
                savedInstanceState.getLong(STATE_SCROLL_ID),
                savedInstanceState.getString(STATE_SCROLL_ROLE).orEmpty(),
                savedInstanceState.getInt(STATE_SCROLL_TOP),
                savedInstanceState.getInt(STATE_SCROLL_INDEX),
                savedInstanceState.getBoolean(STATE_SCROLL_FOLLOW)
            )
        } else retainedScrollAnchor
        scrollController?.restore(savedAnchor)
        binding.tvEmptyTitle.text = "Ada cerita apa, ${com.assistant.adi.ui.buddy.BuddyProfile(requireContext()).userName}?"
        binding.etMessage.setText(savedInstanceState?.getString(STATE_DRAFT) ?: viewModel.draft)
        binding.btnChatBack.setOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }
        bindSuggestions()
        setupListeners()
        layoutListener = View.OnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val compact = (bottom - top) / resources.displayMetrics.density < 420
            binding.tvChatNote.visibility = if (compact) View.GONE else View.VISIBLE
            binding.etMessage.maxLines = if (compact) 2 else 4
            scrollController?.onViewportChanged()
        }
        binding.root.addOnLayoutChangeListener(layoutListener)
        setupMenu()
        observeViewModel()
        viewLifecycleOwner.lifecycleScope.launch { applyChatEntry() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_ENTRY_ID, chatEntryId)
        outState.putString(STATE_ENTRY_MODE, chatEntryMode)
        outState.putString(STATE_SESSION_ID, entrySessionId)
        outState.putString("chat_entry_pending_session_id", pendingSessionId)
        outState.putBoolean(STATE_ENTRY_COMPLETE, entryReady)
        outState.putString(STATE_DRAFT, _binding?.etMessage?.text?.toString() ?: viewModel.draft)
        outState.putStringArrayList(STATE_SUGGESTIONS, ArrayList(suggestionsBySession.map { (session, ids) -> "$session|${ids.joinToString(",")}" }))
        (scrollController?.snapshot() ?: retainedScrollAnchor)?.let {
            outState.putString(STATE_SCROLL_SESSION, it.session)
            outState.putLong(STATE_SCROLL_ID, it.messageId)
            outState.putString(STATE_SCROLL_ROLE, it.role)
            outState.putInt(STATE_SCROLL_TOP, it.top)
            outState.putInt(STATE_SCROLL_INDEX, it.index)
            outState.putBoolean(STATE_SCROLL_FOLLOW, it.following)
        }
        super.onSaveInstanceState(outState)
    }

    private suspend fun applyChatEntry() {
        entryReady = false
        entryError = null
        updateDisplayMessages()
        try {
            val isNewEntry = chatEntryMode == com.assistant.adi.ui.DashboardActivity.CHAT_ENTRY_NEW
            val restoredSessionId = entrySessionId
            val selectedId = if (pendingSessionId != null) {
                val id = pendingSessionId!!
                viewModel.openChat(id).await()
                pendingSessionId = null
                chatEntryMode = "restore"
                id
            } else if (isNewEntry) {
                viewModel.beginNewEntry(chatEntryId).await()
            } else {
                val id = entrySessionId ?: viewModel.activeSession.value
                viewModel.openChat(id).await()
                id
            }
            if (isNewEntry && restoredSessionId == null) {
                viewModel.draft = ""
                binding.etMessage.setText("")
            }
            entrySessionId = selectedId
            showSuggestionsFor(selectedId)
            entryReady = true
        } catch (error: Exception) {
            entryError = error.message ?: "Gagal membuka obrolan. Ketuk untuk mencoba lagi."
        }
        updateDisplayMessages()
    }

    private fun moveToSession(id: String, clearDraft: Boolean, onSuccess: (() -> Unit)? = null) {
        pendingSessionId = id
        entryReady = false
        entryError = null
        updateDisplayMessages()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                viewModel.openChat(id).await()
                entrySessionId = id
                chatEntryMode = "restore"
                showSuggestionsFor(id)
                if (clearDraft) {
                    viewModel.draft = ""
                    binding.etMessage.setText("")
                }
                entryReady = true
                pendingSessionId = null
                errorDismissed = false
                onSuccess?.invoke()
            } catch (error: Exception) {
                entryError = error.message ?: "Gagal mengganti obrolan. Ketuk untuk mencoba lagi."
            }
            updateDisplayMessages()
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onChatScreenResumed()
    }

    override fun onPause() {
        super.onPause()
        // Starts 3-minute idle timer to unmount model from RAM while inactive
        viewModel.onChatScreenPaused()
    }

    private fun setupRecyclerView() {
        binding.rvChat.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }
        // Disable item change animations completely to make streaming text silky smooth without flickering
        binding.rvChat.itemAnimator = null
        binding.rvChat.adapter = adapter
        scrollController = ChatScrollController(binding.rvChat) { following ->
            _binding?.btnLatestMessage?.visibility = if (following || binding.rvChat.visibility != View.VISIBLE) View.GONE else View.VISIBLE
        }
    }

    private fun setupListeners() {
        binding.etMessage.doAfterTextChanged {
            viewModel.draft = it?.toString().orEmpty()
            updateSendButton()
        }
        binding.etMessage.setOnFocusChangeListener { _, focused ->
            binding.cardComposer.strokeColor = ContextCompat.getColor(requireContext(), R.color.buddy_stage_action)
            binding.cardComposer.strokeWidth = if (focused) (2 * resources.displayMetrics.density).toInt() else 0
        }
        binding.tvChatError.setOnClickListener {
            val isModelErr = modelError != null
            val unfinished = dbMessages.firstOrNull()?.let { it.role == "user" && it.status != "complete" } == true
            val builder = com.google.android.material.dialog.MaterialAlertDialogBuilder(BuddyPageUi(requireContext()).ui.context)
                .setTitle(if (isModelErr) "Kendala Model AI" else "Jawaban Belum Tersedia")
                .setMessage(binding.tvChatError.text)
            if (unfinished) {
                builder.setPositiveButton("Coba lagi") { _, _ ->
                    errorDismissed = false
                    viewModel.retryLastMessage()
                }
            } else if (isModelErr) {
                builder.setPositiveButton("Pilih Model") { _, _ ->
                    navigateToGallery()
                }
            }
            builder.setNeutralButton("Sembunyikan") { _, _ ->
                errorDismissed = true
                updateDisplayMessages()
            }
            builder.setNegativeButton("Tutup", null)
            builder.show().withActionIcons(
                positive = if (unfinished) R.drawable.ic_ms_refresh else if (isModelErr) R.drawable.ic_ms_view_module else 0,
                negative = R.drawable.ic_ms_close,
                neutral = R.drawable.ic_ms_expand_more)
        }
        binding.btnNewChat.setOnClickListener {
            val nextSession = java.util.UUID.randomUUID().toString()
            moveToSession(nextSession, clearDraft = true)
        }
        binding.btnRetry.setOnClickListener {
            errorDismissed = false
            if (entryError != null) {
                val pending = pendingSessionId
                if (pending != null) moveToSession(pending, clearDraft = false)
                else viewLifecycleOwner.lifecycleScope.launch { applyChatEntry() }
            }
            else viewModel.retryLastMessage()
        }
        binding.btnChatHistory.setOnClickListener { showHistoryPanel() }
        binding.btnChatSettings.setOnClickListener {
            (requireActivity() as com.assistant.adi.ui.DashboardActivity).navigateSection("ai_settings")
        }
        binding.btnLatestMessage.setOnClickListener { scrollController?.followLatest() }
        // Send button
        binding.btnSend.setOnClickListener {
            if (currentStreamingState is StreamingState.Thinking || currentStreamingState is StreamingState.Streaming) {
                viewModel.cancelGeneration()
            } else {
                sendMessage()
            }
        }

        // Top Gallery Shortcut
        binding.btnModelChoice.setOnClickListener { showModelChooser() }

        updateSendButton()
    }

    private fun bindSuggestions() {
        val buttons = listOf(binding.tvSuggestBattery, binding.tvSuggestCpu)
        buttons.forEachIndexed { index, button ->
            button.setOnClickListener { activeSuggestions.getOrNull(index)?.let { prepareDraft(it.text) } }
        }
    }

    private fun showSuggestionsFor(sessionId: String) {
        val ids = suggestionsBySession[sessionId]
        activeSuggestions = ids?.let(ChatSuggestions::restore)?.takeIf { it.size == 2 } ?: ChatSuggestions.choose().also {
            suggestionsBySession[sessionId] = it.map(ChatSuggestion::id)
        }
        val buttons = listOf(binding.tvSuggestBattery, binding.tvSuggestCpu)
        buttons.forEachIndexed { index, button ->
            val suggestion = activeSuggestions[index]
            button.text = suggestion.text
        }
    }

    private fun prepareDraft(text: String) {
        binding.etMessage.setText(text)
        binding.etMessage.setSelection(text.length)
        binding.etMessage.requestFocus()
        androidx.core.view.WindowCompat.getInsetsController(requireActivity().window, binding.etMessage)
            .show(androidx.core.view.WindowInsetsCompat.Type.ime())
    }

    private fun updateSendButton() {
        if (!entryReady) {
            binding.btnSend.isEnabled = false
            return
        }
        val busy = currentStreamingState is StreamingState.Thinking || currentStreamingState is StreamingState.Streaming
        val hasText = !binding.etMessage.text.isNullOrBlank()
        binding.btnSend.isEnabled = busy || hasText
        if (busy) {
            binding.btnSend.setIconResource(R.drawable.ic_stop)
            binding.btnSend.contentDescription = "Hentikan jawaban"
            binding.btnSend.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.buddy_peach))
            binding.btnSend.iconTint = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.buddy_danger))
        } else {
            binding.btnSend.setIconResource(R.drawable.ic_send)
            binding.btnSend.contentDescription = "Kirim pesan"
            val bgTint = if (hasText) {
                ContextCompat.getColor(requireContext(), R.color.buddy_mint)
            } else {
                ContextCompat.getColor(requireContext(), R.color.buddy_stage_button)
            }
            val iconTint = if (hasText) {
                ContextCompat.getColor(requireContext(), R.color.buddy_ink)
            } else {
                ContextCompat.getColor(requireContext(), R.color.buddy_stage_sub)
            }
            binding.btnSend.backgroundTintList = ColorStateList.valueOf(bgTint)
            binding.btnSend.iconTint = ColorStateList.valueOf(iconTint)
        }
    }

    private fun showHistoryPanel() {
        val page = BuddyPageUi(requireContext()); val ui = page.ui
        val dialog = android.app.Dialog(ui.context)
        historyPanel?.dismiss(); historyPanel = dialog
        val (scroll, body) = page.page()
        val content = ui.column().apply { setBackgroundColor(ui.color(R.color.buddy_stage)) }
        val header = android.widget.LinearLayout(ui.context).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(ui.dp(20), ui.dp(12), ui.dp(12), ui.dp(8))
            addView(page.text("Riwayat", 22f, true, stage = true).apply {
                androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
            }, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
            addView(ui.iconButton(R.drawable.ic_ms_close, "Tutup riwayat") { dialog.dismiss() },
                android.widget.LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
        }
        content.addView(header)
        content.addView(scroll, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
        val sessions = viewModel.sessions.value.orEmpty().sortedByDescending { it.updatedAt }
        if (sessions.isEmpty()) {
            val empty = page.card(body)
            empty.addView(page.text("Belum ada cerita", 18f, true), ui.margin(bottom = 8))
            empty.addView(page.text("Percakapanmu akan tersimpan di sini setelah mengirim pesan pertama.", 15f, secondary = true))
        } else {
            val zone = java.time.ZoneId.systemDefault()
            val today = java.time.LocalDate.now(zone)
            val groups = sessions.groupBy {
                val date = java.time.Instant.ofEpochMilli(it.updatedAt).atZone(zone).toLocalDate()
                when (date) { today -> "Hari ini"; today.minusDays(1) -> "Kemarin"; else -> "Sebelumnya" }
            }
            groups.forEach { (label, items) ->
                page.section(body, label)
                val group = ui.column().apply {
                    background = page.style.rounded(R.color.buddy_tile, 16)
                    body.addView(this, ui.margin(bottom = 0))
                }
                items.forEachIndexed { index, session ->
                    if (index > 0) page.divider(group)
                    val active = session.id == viewModel.activeSession.value
                    val date = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(session.updatedAt))
                    addHistorySessionRow(page, group, session.title,
                        "${if (active) "Sedang dibuka · " else ""}$date${if (session.summary.isNotBlank()) " · Konteks diringkas" else ""}",
                        session.id, dialog)
                }
            }
        }
        dialog.setContentView(content)
        dialog.window?.apply {
            setBackgroundDrawableResource(R.color.buddy_stage)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.35f)
            setGravity(android.view.Gravity.START)
            val light = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) != android.content.res.Configuration.UI_MODE_NIGHT_YES
            androidx.core.view.WindowCompat.getInsetsController(this, decorView).apply {
                isAppearanceLightStatusBars = light
                isAppearanceLightNavigationBars = light
            }
        }
        dialog.show()
        dialog.window?.setLayout(minOf(ui.dp(380), (resources.displayMetrics.widthPixels * 0.88).toInt()), android.view.ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun addHistorySessionRow(
        page: BuddyPageUi,
        parent: android.widget.LinearLayout,
        title: String,
        description: String,
        sessionId: String,
        dialog: android.app.Dialog
    ) {
        val row = android.widget.LinearLayout(page.ui.context).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = page.ui.dp(64)
            setPadding(page.ui.dp(12), page.ui.dp(4), page.ui.dp(12), page.ui.dp(4))
        }
        val text = page.ui.column().apply {
            setPadding(0, page.ui.dp(8), page.ui.dp(4), page.ui.dp(8))
            addView(page.text(title, 16f, true))
            addView(page.text(description, 13f, secondary = true), page.ui.margin(top = 3, bottom = 0))
        }
        row.addView(text, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
        val actions = com.google.android.material.button.MaterialButton(page.ui.context, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
            this.text = "⋮"
            contentDescription = "Tindakan untuk $title"
            minWidth = page.ui.dp(48)
            minimumHeight = page.ui.dp(48)
            setOnClickListener { anchor ->
                android.widget.PopupMenu(page.ui.context, anchor).apply {
                    menu.add(0, 1, 0, "Ubah nama")
                    menu.add(0, 2, 1, "Hapus percakapan")
                    setOnMenuItemClickListener { menuItem ->
                        if (menuItem.itemId == 1) {
                            val input = com.google.android.material.textfield.TextInputEditText(page.ui.context).apply {
                                setText(title)
                                setSelection(title.length)
                                filters = arrayOf(android.text.InputFilter.LengthFilter(60))
                                isSingleLine = false
                                maxLines = 2
                            }
                            com.google.android.material.dialog.MaterialAlertDialogBuilder(page.ui.context)
                                .setTitle("Ubah nama percakapan")
                                .setView(input)
                                .setPositiveButton("Simpan") { _, _ ->
                                    viewLifecycleOwner.lifecycleScope.launch {
                                        viewModel.renameSession(sessionId, input.text?.toString().orEmpty())
                                        dialog.dismiss()
                                        showHistoryPanel()
                                    }
                                }
                                .setNegativeButton("Batal", null)
                                .show().withActionIcons(R.drawable.ic_ms_save, R.drawable.ic_ms_close)
                            return@setOnMenuItemClickListener true
                        }
                        com.google.android.material.dialog.MaterialAlertDialogBuilder(page.ui.context)
                            .setTitle("Hapus obrolan?")
                            .setMessage("Hapus percakapan ini beserta ringkasan konteksnya? Percakapan lain tetap tersimpan.")
                            .setPositiveButton("Hapus") { _, _ ->
                                val deletingActive = viewModel.activeSession.value == sessionId
                                val replacementId = if (deletingActive) java.util.UUID.randomUUID().toString() else null
                                val previousDraft = if (deletingActive) binding.etMessage.text?.toString().orEmpty() else ""
                                if (deletingActive) {
                                    entrySessionId = replacementId
                                    pendingSessionId = null
                                    chatEntryMode = "restore"
                                    entryReady = false
                                    binding.etMessage.setText("")
                                    updateDisplayMessages()
                                }
                                val deletion = viewModel.deleteSession(sessionId, replacementId)
                                dialog.dismiss()
                                viewLifecycleOwner.lifecycleScope.launch {
                                    try {
                                        val selectedId = deletion.await()
                                        suggestionsBySession.remove(sessionId)
                                        if (deletingActive && entrySessionId == replacementId) {
                                            val activeId = selectedId ?: viewModel.activeSession.value
                                            entrySessionId = activeId
                                            showSuggestionsFor(activeId)
                                            entryReady = true
                                            updateDisplayMessages()
                                        }
                                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                        throw cancelled
                                    } catch (error: Exception) {
                                        if (deletingActive && entrySessionId == replacementId) {
                                            entrySessionId = sessionId
                                            binding.etMessage.setText(previousDraft)
                                            entryReady = true
                                            updateDisplayMessages()
                                        }
                                        Toast.makeText(requireContext(), "Percakapan belum dapat dihapus.", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                            .setNegativeButton("Batal", null)
                            .show().withActionIcons(R.drawable.ic_ms_delete, R.drawable.ic_ms_close)
                        true
                    }
                }.show()
            }
        }
        row.addView(actions)
        row.setOnClickListener { moveToSession(sessionId, clearDraft = true) { dialog.dismiss() } }
        row.isFocusable = true
        val ripple = android.util.TypedValue()
        page.ui.context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
        row.setBackgroundResource(ripple.resourceId)
        row.contentDescription = "$title. $description. Buka percakapan"
        actions.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        parent.addView(row, page.ui.margin(bottom = 0))
    }

    private fun showModelChooser() {
        if (modelChooserDialog?.isShowing == true) return
        viewLifecycleOwner.lifecycleScope.launch {
            val models = viewModel.installedModels()
            val settings = com.assistant.adi.data.datastore.AiSettingsDataStore(requireContext()).aiSettingsFlow.first()
            val activeFile = viewModel.getActiveModelFile(settings)?.name
            val page = BuddyPageUi(requireContext())
            val ui = page.ui
            val (scroll, body) = page.page()
            body.addView(page.text("Model percakapan", 22f, true, stage = true), page.ui.margin(top = 12, bottom = 6))
            body.addView(page.text("Pilih model yang sudah tersedia di perangkat.", 14f, secondary = true, stage = true), page.ui.margin(bottom = 12))
            val list = ui.column().apply {
                background = page.style.rounded(R.color.buddy_tile, 16)
                body.addView(this, ui.margin(bottom = 0))
            }
            models.forEach { model ->
                val available = true
                val isActive = model.fileName == activeFile
                if (list.childCount > 0) page.divider(list)
                val row = android.widget.LinearLayout(ui.context).apply {
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    minimumHeight = ui.dp(72)
                    setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12))
                    setBackgroundColor(ui.color(if (isActive && available) R.color.buddy_mint else R.color.buddy_tile))
                    isSelected = isActive && available
                }
                row.addView(android.widget.ImageView(ui.context).apply {
                    setImageResource(R.drawable.ic_ms_check)
                    imageTintList = ColorStateList.valueOf(ui.color(R.color.buddy_action))
                    visibility = if (isActive && available) View.VISIBLE else View.INVISIBLE
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, android.widget.LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)).apply { marginEnd = ui.dp(12) })
                row.addView(ui.column().apply {
                    addView(page.text(model.shortName, 16f, true))
                    addView(page.text(when {
                        !available -> getString(R.string.model_chooser_unavailable)
                        isActive -> getString(R.string.model_chooser_active)
                        else -> getString(R.string.model_ready)
                    }, 14f, secondary = true), ui.margin(top = 2, bottom = 0))
                }, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
                row.contentDescription = "${model.shortName}. ${when {
                    !available -> getString(R.string.model_chooser_unavailable)
                    isActive -> getString(R.string.model_chooser_active)
                    else -> getString(R.string.model_ready)
                }}"
                if (available) {
                    row.isFocusable = true
                    val ripple = android.util.TypedValue()
                    ui.context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
                    row.foreground = androidx.core.content.ContextCompat.getDrawable(ui.context, ripple.resourceId)
                    row.setOnClickListener {
                        viewLifecycleOwner.lifecycleScope.launch {
                            if (viewModel.selectInstalledModel(model.fileName)) {
                                modelChooserDialog?.dismiss()
                            } else Toast.makeText(requireContext(), "Model tidak tersedia. Periksa kembali di Kelola model.", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    row.isEnabled = false
                }
                list.addView(row)
            }
            val dialog = android.app.Dialog(ui.context)
            val root = ui.column().apply { setBackgroundColor(ui.color(R.color.buddy_stage)) }
            root.addView(scroll, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
            val footer = ui.column().apply {
                setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(12))
                setBackgroundColor(ui.color(R.color.buddy_stage))
                addView(page.button("Kelola model", true, icon = R.drawable.ic_ms_view_module) { dialog.dismiss(); navigateToGallery() }, ui.margin(bottom = 0))
                addView(page.textButton("Tutup", stage = true, icon = R.drawable.ic_ms_close) { dialog.dismiss() }, ui.margin(top = 4, bottom = 0))
            }
            root.addView(footer)
            dialog.setContentView(root)
            dialog.window?.setBackgroundDrawableResource(R.color.buddy_stage)
            dialog.setOnDismissListener {
                modelChooserDialog = null
                _binding?.btnModelChoice?.requestFocus()
            }
            modelChooserDialog = dialog
            dialog.show()
            dialog.window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.92).toInt(),
                minOf(ui.dp(440), (resources.displayMetrics.heightPixels * 0.8).toInt())
            )
        }
    }

    private var modelChooserDialog: android.app.Dialog? = null
    private fun sendMessage() {
        if (currentStreamingState is StreamingState.Thinking || currentStreamingState is StreamingState.Streaming) return
        val text = binding.etMessage.text.toString().trim()
        if (text.isNotEmpty()) {
            if (!viewModel.sendMessage(text)) {
                Toast.makeText(requireContext(), "Tunggu sebentar, percakapan sedang disiapkan. Pesanmu tetap di sini.", Toast.LENGTH_SHORT).show()
                return
            }
            scrollController?.followLatest()
            errorDismissed = false
            viewModel.draft = ""
            binding.etMessage.setText("")
        }
    }

    private fun setupMenu() {
        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menu.add(0, 102, 0, "Model AI").apply {
                    setIcon(android.R.drawable.ic_menu_gallery)
                    setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
                }
                menu.add(0, 103, 1, "Pengaturan AI").apply {
                    setIcon(R.drawable.ic_settings)
                    setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
                }
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                when (menuItem.itemId) {
                    102 -> {
                        showModelChooser()
                        return true
                    }
                    103 -> {
                        (requireActivity() as com.assistant.adi.ui.DashboardActivity).navigateSection("ai_settings")
                        return true
                    }
                }
                return false
            }
        }, viewLifecycleOwner, androidx.lifecycle.Lifecycle.State.RESUMED)
    }

    private fun navigateToGallery() {
        (requireActivity() as com.assistant.adi.ui.DashboardActivity).navigateSection("model_gallery")
    }

    private fun observeViewModel() {
        fun updateTitle() {
            val session = viewModel.sessions.value.orEmpty().find { it.id == viewModel.activeSession.value }
            binding.tvSessionTitle.text = session?.let { it.title + if (it.summary.isNotBlank()) " · Diringkas" else "" } ?: "Obrolan baru"
            binding.tvSessionTitle.contentDescription = binding.tvSessionTitle.text
        }
        viewModel.sessions.observe(viewLifecycleOwner) { updateTitle() }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.activeSession.flowWithLifecycle(viewLifecycleOwner.lifecycle).collectLatest {
                dbMessages = viewModel.chatMessages.value.orEmpty().filter { it.sessionId == viewModel.activeSession.value }
                currentStreamingState = viewModel.streamingState.value
                streamingStartTime = 0L
                errorDismissed = false
                updateTitle()
                updateDisplayMessages()
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.progress.flowWithLifecycle(viewLifecycleOwner.lifecycle).collectLatest {
                if (currentStreamingState is StreamingState.Thinking && it.isNotBlank()) binding.tvTypingIndicator.text = it
            }
        }
        // Observe model loading state & standby
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.modelState.flowWithLifecycle(viewLifecycleOwner.lifecycle, androidx.lifecycle.Lifecycle.State.STARTED).collectLatest { state ->
                modelError = (state as? ModelState.Error)?.message
                errorDismissed = false
                when (state) {
                    is ModelState.NotDownloaded -> binding.layoutLoadingModel.visibility = View.GONE
                    is ModelState.Checking -> {
                        binding.layoutLoadingModel.visibility = View.VISIBLE
                        binding.tvLoadingModelStatus.text = "Memeriksa model AI..."
                    }
                    is ModelState.Loading -> {
                        binding.layoutLoadingModel.visibility = View.VISIBLE
                        binding.tvLoadingModelStatus.text = "Memuat Model AI..."
                    }
                    is ModelState.Ready, is ModelState.Standby, is ModelState.Error -> binding.layoutLoadingModel.visibility = View.GONE
                }
                if (!entryReady && entryError == null) {
                    binding.layoutLoadingModel.visibility = View.VISIBLE
                    binding.tvLoadingModelStatus.text = "Membuka obrolan…"
                }
                updateDisplayMessages()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.selectedModelLabel.flowWithLifecycle(viewLifecycleOwner.lifecycle, androidx.lifecycle.Lifecycle.State.STARTED).collectLatest {
                binding.btnModelChoice.text = it
            }
        }

        // Observe streaming responses state
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.streamingState.flowWithLifecycle(viewLifecycleOwner.lifecycle, androidx.lifecycle.Lifecycle.State.STARTED).collectLatest { state ->
                if (state is StreamingState.Thinking && currentStreamingState !is StreamingState.Thinking) streamingStartTime = 0L
                if (state is StreamingState.Error) errorDismissed = false
                currentStreamingState = state
                updateDisplayMessages()
            }
        }

        // Observe saved messages in database
        viewModel.chatMessages.observe(viewLifecycleOwner) { messages ->
            dbMessages = messages.filter { it.sessionId == viewModel.activeSession.value }
            updateDisplayMessages()
        }
    }

    private fun updateDisplayMessages() {
        if (!entryReady) {
            binding.btnNewChat.isEnabled = false
            binding.btnChatHistory.isEnabled = false
            binding.layoutEmptyState.visibility = View.VISIBLE
            binding.rvChat.visibility = View.GONE
            binding.tvChatError.visibility = if (entryError == null) View.GONE else View.VISIBLE
            binding.tvChatError.text = entryError.orEmpty()
            binding.tvChatError.contentDescription = entryError ?: "Membuka obrolan…"
            binding.btnRetry.visibility = if (entryError == null) View.GONE else View.VISIBLE
            binding.layoutLoadingModel.visibility = if (entryError == null) View.VISIBLE else View.GONE
            if (entryError == null) binding.tvLoadingModelStatus.text = "Membuka obrolan…"
            binding.etMessage.isEnabled = false
            binding.tvSuggestBattery.isEnabled = false
            binding.tvSuggestCpu.isEnabled = false
            updateSendButton()
            return
        }
        val modelState = viewModel.modelState.value
        binding.layoutLoadingModel.visibility = if (modelState is ModelState.Checking || modelState is ModelState.Loading) View.VISIBLE else View.GONE
        val busy = currentStreamingState is StreamingState.Thinking || currentStreamingState is StreamingState.Streaming
        binding.btnNewChat.isEnabled = !busy
        binding.btnChatHistory.isEnabled = !busy
        val error = currentStreamingState as? StreamingState.Error
        val unfinished = dbMessages.firstOrNull()?.let { it.role == "user" && it.status != "complete" } == true
        val hasError = !busy && (error != null || unfinished || modelError != null)
        binding.tvChatError.visibility = if (hasError && !errorDismissed) View.VISIBLE else View.GONE
        binding.tvChatError.text = error?.message ?: modelError ?: "Jawaban belum selesai. Coba lagi atau kirim pesan baru."
        binding.tvChatError.contentDescription = "${binding.tvChatError.text}. Ketuk untuk membaca rincian."
        binding.btnRetry.visibility = if (!busy && unfinished) View.VISIBLE else View.GONE
        binding.tvSuggestBattery.isEnabled = !busy
        binding.tvSuggestCpu.isEnabled = !busy
        val messagesList = dbMessages.reversed().toMutableList()

        when (val state = currentStreamingState) {
            is StreamingState.Thinking -> {
                if (streamingStartTime == 0L) {
                    streamingStartTime = System.currentTimeMillis()
                }
                messagesList.add(ChatMessage(id = 0L, role = "ai", content = "...", sessionId = "streaming_temp", timestamp = streamingStartTime))
                binding.tvTypingIndicator.text = viewModel.progress.value.ifBlank { "${com.assistant.adi.ui.buddy.BuddyProfile(requireContext()).buddyName} sedang berpikir…" }
                binding.tvTypingIndicator.visibility = View.VISIBLE
                binding.etMessage.isEnabled = false
            }
            is StreamingState.Streaming -> {
                if (streamingStartTime == 0L) {
                    streamingStartTime = System.currentTimeMillis()
                }
                if(messagesList.none { it.role=="ai" && it.timestamp>=streamingStartTime && it.content==state.partialText.trim() })
                    messagesList.add(ChatMessage(id = 0L, role = "ai", content = state.partialText, sessionId = "streaming_temp", timestamp = streamingStartTime))
                binding.tvTypingIndicator.text = "${com.assistant.adi.ui.buddy.BuddyProfile(requireContext()).buddyName} sedang menjawab…"
                binding.tvTypingIndicator.visibility = View.VISIBLE
                binding.etMessage.isEnabled = false
            }
            is StreamingState.Done, is StreamingState.Idle -> {
                if(state is StreamingState.Done && state.fullText.isNotBlank() && messagesList.none { it.role=="ai" && it.content==state.fullText && it.timestamp>=streamingStartTime })
                    messagesList.add(ChatMessage(id=0L,role="ai",content=state.fullText,sessionId="streaming_temp",timestamp=streamingStartTime))
                if(state is StreamingState.Idle) streamingStartTime=0L
                binding.tvTypingIndicator.visibility = View.GONE
                binding.etMessage.isEnabled = true
            }
            is StreamingState.Error -> {
                streamingStartTime = 0L
                binding.tvTypingIndicator.visibility = View.GONE
                binding.etMessage.isEnabled = true
            }
        }

        val isEmpty = messagesList.isEmpty()
        updateSendButton()
        binding.layoutEmptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.rvChat.visibility = if (isEmpty) View.GONE else View.VISIBLE

        scrollController?.submit(entrySessionId ?: viewModel.activeSession.value, messagesList) { list, committed ->
            adapter.submitList(list, committed)
        }
    }

    override fun onDestroyView() {
        viewModel.draft = binding.etMessage.text?.toString().orEmpty()
        retainedScrollAnchor = scrollController?.snapshot()
        historyPanel?.dismiss(); historyPanel = null
        modelChooserDialog?.dismiss(); modelChooserDialog = null
        layoutListener?.let(binding.root::removeOnLayoutChangeListener); layoutListener = null
        scrollController?.dispose(); scrollController = null
        binding.rvChat.adapter = null
        super.onDestroyView()
        _binding = null
    }
}




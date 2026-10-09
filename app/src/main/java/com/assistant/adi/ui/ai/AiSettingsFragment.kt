package com.assistant.adi.ui.ai

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.flowWithLifecycle
import androidx.core.widget.doAfterTextChanged
import com.assistant.adi.R
import com.assistant.adi.ui.buddy.withActionIcons
import com.assistant.adi.data.model.AiSettings
import com.assistant.adi.databinding.FragmentAiSettingsBinding
import com.assistant.adi.ui.buddy.MonitorDetailUi
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

class AiSettingsFragment : Fragment() {

    private var _binding: FragmentAiSettingsBinding? = null
    private val binding get() = _binding!!

    private val engineOwner: AiChatViewModel by activityViewModels()
    private val viewModel: AiSettingsViewModel by viewModels()
    private var hasLoadedSettings = false
    private var isApplyingStoredSettings = false
    private var formIsDirty = false
    private var modelHelp = "Pilih model yang tersimpan di HP untuk menjawab percakapan."
    private var runtimeHelp = "Parameter ini dipakai LiteRT-LM untuk mengatur variasi dan panjang keluaran."

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAiSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.rbGpu.isEnabled=false
        binding.rbGpu.text="GPU · belum tersedia"
        binding.rbAuto.text="Otomatis (CPU)"
        binding.btnSaveSettings.isEnabled = false
        binding.btnResetSettings.isEnabled = false
        setAdvanced(savedInstanceState?.getBoolean("advanced_ai") == true)
        binding.btnAdvancedAi.setOnClickListener { setAdvanced(binding.layoutAdvancedAi.visibility != View.VISIBLE) }
        binding.btnOpenModels.setOnClickListener {
            (requireActivity() as com.assistant.adi.ui.DashboardActivity).navigateSection("model_gallery")
        }
        setupInfoButtons()
        setupModelManagement()
        setupSliderListeners()
        setupFormChangeListeners()
        setupChipListeners()
        setupActionListeners()
        observeViewModel()
    }

    private fun setupModelManagement() {
        val models = viewModel.getAvailableModels()
        binding.tvAiModelStatus.visibility = if (models.isEmpty()) View.VISIBLE else View.GONE
        modelHelp = if (models.isEmpty()) "Pilih model yang sudah diunduh melalui Kelola model."
            else "Pilih model yang tersimpan di HP untuk menjawab percakapan."
        val adapter = ArrayAdapter(binding.root.context, android.R.layout.simple_dropdown_item_1line, models)
        binding.actvModelSelection.setAdapter(adapter)
        binding.actvModelSelection.setOnItemClickListener { _, _, _, _ ->
            markFormDirty()
            updateRuntimeCompatibilityNote(fileNameForDisplayName(binding.actvModelSelection.text.toString().trim()))
        }
    }

    private fun setupInfoButtons() {
        val info = MonitorDetailUi(this)
        binding.btnModelHelp.setOnClickListener { info.showInfoSheet("Model untuk percakapan", modelHelp, trigger = it) }
        binding.btnAnswerHelp.setOnClickListener { info.showInfoSheet("Gaya jawaban",
            "Variasi lebih tinggi memberi lebih banyak pilihan kata. Hasil tetap bergantung pada model dan pesanmu. Batas panjang jawaban membatasi keluaran dalam satu balasan.", trigger = it) }
        binding.btnProcessingHelp.setOnClickListener { info.showInfoSheet("Pemrosesan di HP",
            "Model dilepas dari memori setelah chat tidak aktif selama 3 menit. Percakapan tetap tersimpan.", trigger = it) }
        binding.btnRuntimeHelp.setOnClickListener { info.showInfoSheet("Parameter lanjutan", runtimeHelp, trigger = it) }
        binding.btnPromptHelp.setOnClickListener { info.showInfoSheet("Instruksi untuk Buddy",
            "Instruksi mengatur kepribadian dan batasan jawaban asisten.", trigger = it) }
        binding.btnContextHelp.setOnClickListener { info.showInfoSheet("Konteks perangkat",
            "Ketuk data yang ingin disertakan saat Buddy menjawab. Template konteks harus memuat {user_message} agar pesanmu ikut dikirim.", trigger = it) }
    }

    private fun setupSliderListeners() {
        binding.sliderTemperature.addOnChangeListener { _, value, _ ->
            binding.lblTemperature.text = String.format(Locale.getDefault(), "Variasi jawaban · %.1f", value)
            markFormDirty()
        }

        binding.sliderTopK.addOnChangeListener { _, value, _ ->
            binding.lblTopK.text = "Top K: ${value.toInt()}"
            markFormDirty()
        }

        binding.sliderTopP.addOnChangeListener { _, value, _ ->
            binding.lblTopP.text = String.format(Locale.getDefault(), "Top P: %.2f", value)
            markFormDirty()
        }

        binding.sliderMaxTokens.addOnChangeListener { _, value, _ ->
            binding.lblMaxTokens.text = "Batas panjang jawaban · ${value.toInt()}"
            markFormDirty()
        }
    }

    private fun setupFormChangeListeners() {
        binding.etSystemPrompt.doAfterTextChanged { markFormDirty() }
        binding.etUserPromptTemplate.doAfterTextChanged { markFormDirty() }
        binding.rgAccelerator.setOnCheckedChangeListener { _, _ -> markFormDirty() }
    }

    private fun markFormDirty() {
        if (hasLoadedSettings && !isApplyingStoredSettings) formIsDirty = true
    }

    private fun setupChipListeners() {
        val chips = listOf(
            binding.chipBatteryPct to "{battery_pct}",
            binding.chipBatteryTemp to "{battery_temp}",
            binding.chipChargingStatus to "{charging_status}",
            binding.chipRamUsed to "{ram_used}",
            binding.chipRamTotal to "{ram_total}",
            binding.chipCpuUsage to "{cpu_usage}",
            binding.chipCpuTemp to "{cpu_temp}",
            binding.chipScreenTime to "{screen_time}",
            binding.chipNetworkType to "{network_type}",
            binding.chipNetworkSpeed to "{network_speed}",
            binding.chipUserMessage to "{user_message}"
        )

        for ((chip, variable) in chips) {
            chip.contentDescription = "Sisipkan ${chip.text}, variabel $variable"
            chip.setOnClickListener {
                insertVariableIntoTemplate(variable)
            }
        }
    }

    private fun insertVariableIntoTemplate(variable: String) {
        val editText = binding.etUserPromptTemplate
        val start = Math.max(editText.selectionStart, 0)
        val end = Math.max(editText.selectionEnd, 0)

        val replacementStart = Math.min(start, end)
        val replacementEnd = Math.max(start, end)

        editText.text?.replace(replacementStart, replacementEnd, variable, 0, variable.length)
        editText.setSelection(replacementStart + variable.length)
    }

    private fun setupActionListeners() {
        // Save Settings Action
        binding.btnSaveSettings.setOnClickListener {
            val systemPrompt = binding.etSystemPrompt.text.toString().trim()
            val userTemplate = binding.etUserPromptTemplate.text.toString().trim()

            if (systemPrompt.isEmpty()) {
                setAdvanced(true)
                binding.layoutSystemPrompt.error = "Instruksi asisten tidak boleh kosong"
                binding.etSystemPrompt.requestFocus()
                binding.root.post { binding.root.smoothScrollTo(0, binding.layoutSystemPrompt.top) }
                return@setOnClickListener
            }
            binding.layoutSystemPrompt.error = null

            if (userTemplate.isEmpty()) {
                setAdvanced(true)
                binding.layoutUserPrompt.error = "Template prompt tidak boleh kosong"
                binding.etUserPromptTemplate.requestFocus()
                binding.root.post { binding.root.smoothScrollTo(0, binding.layoutUserPrompt.top) }
                return@setOnClickListener
            }
            if (!userTemplate.contains("{user_message}")) {
                setAdvanced(true)
                binding.layoutUserPrompt.error = "Template harus mengandung variabel {user_message}"
                binding.etUserPromptTemplate.requestFocus()
                binding.root.post { binding.root.smoothScrollTo(0, binding.layoutUserPrompt.top) }
                return@setOnClickListener
            }
            binding.layoutUserPrompt.error = null

            val accelerator = when {
                binding.rbAuto.isChecked -> "AUTO"
                binding.rbGpu.isChecked -> "GPU"
                else -> "CPU"
            }
            val selectedModelText = fileNameForDisplayName(binding.actvModelSelection.text.toString().trim())
            val activeModel = if (selectedModelText.isNotEmpty()) {
                selectedModelText
            } else {
                viewModel.settingsState.value?.activeModelPath.orEmpty()
            }

            val settings = AiSettings(
                temperature = binding.sliderTemperature.value,
                topK = binding.sliderTopK.value.toInt(),
                topP = binding.sliderTopP.value,
                repeatPenalty = 1.1f,
                maxTokens = binding.sliderMaxTokens.value.toInt(),
                systemPrompt = systemPrompt,
                userPromptTemplate = userTemplate,
                accelerator = accelerator,
                enableThinking = false,
                activeModelPath = activeModel,
                activeVariantIdentity = if (activeModel == viewModel.settingsState.value?.activeModelPath)
                    viewModel.settingsState.value?.activeVariantIdentity.orEmpty() else viewModel.identityForFile(activeModel),
                idleTimeoutMinutes = 3
            )

            binding.btnSaveSettings.isEnabled = false

            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    applyStoredSettings(viewModel.saveSettings(settings))
                    Toast.makeText(requireContext(), "Pengaturan AI berhasil disimpan!", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    binding.btnSaveSettings.isEnabled = true
                    Toast.makeText(requireContext(), "Gagal menyimpan: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Reset Settings Action
        binding.btnResetSettings.setOnClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(binding.root.context)
                .setTitle("Reset Pengaturan?")
                .setMessage("Parameter dan prompt dikembalikan ke bawaan. Model aktif yang dipilih tetap tersimpan dan tidak dihapus atau diunduh ulang.")
                .setPositiveButton("Reset") { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        try {
                            applyStoredSettings(viewModel.resetToDefaults())
                            Toast.makeText(requireContext(), "Parameter dan prompt dikembalikan ke default", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(requireContext(), "Gagal mengembalikan pengaturan: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton("Batal", null)
                .show().withActionIcons(R.drawable.ic_ms_restart_alt, R.drawable.ic_ms_close)
        }
    }

    private fun navigateToChat() {
        if (!isAdded) return
        if (parentFragmentManager.backStackEntryCount > 0) {
            parentFragmentManager.popBackStack()
        } else {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, AiChatFragment(), "ai_chat")
                .commit()
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.catalogState.flowWithLifecycle(viewLifecycleOwner.lifecycle, androidx.lifecycle.Lifecycle.State.STARTED).collectLatest {
                setupModelManagement()
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.settingsState.flowWithLifecycle(viewLifecycleOwner.lifecycle, androidx.lifecycle.Lifecycle.State.STARTED).collectLatest { settings ->
                if (settings == null || (hasLoadedSettings && formIsDirty)) return@collectLatest
                applyStoredSettings(settings)
            }
        }
    }

    private fun applyStoredSettings(settings: AiSettings) {
        isApplyingStoredSettings = true
        try {
            binding.sliderTemperature.value = settings.temperature.coerceIn(0f, 1f)
                binding.sliderTopK.value = settings.topK.coerceIn(1, 100).toFloat()
                binding.sliderTopP.value = settings.topP.coerceIn(0.05f, 1f)
                binding.sliderMaxTokens.value = settings.maxTokens.coerceIn(64, 2048).toFloat()

                binding.lblTemperature.text = String.format(Locale.getDefault(), "Variasi jawaban · %.1f", settings.temperature.coerceIn(0f, 1f))
                binding.lblTopK.text = "Top K: ${settings.topK.coerceIn(1, 100)}"
                binding.lblTopP.text = String.format(Locale.getDefault(), "Top P: %.2f", settings.topP.coerceIn(0.05f, 1f))
                binding.lblMaxTokens.text = "Batas panjang jawaban · ${settings.maxTokens.coerceIn(64, 2048)}"

                binding.etSystemPrompt.setText(settings.systemPrompt)
                binding.etUserPromptTemplate.setText(settings.userPromptTemplate)

                when (settings.accelerator) {
                    "AUTO" -> binding.rbAuto.isChecked = true
                    "GPU" -> binding.rbGpu.isChecked = true
                    else -> binding.rbCpu.isChecked = true
                }

                val activeModelName = displayNameForFileName(settings.activeModelPath)
                if (binding.actvModelSelection.text.toString() != activeModelName) {
                    binding.actvModelSelection.setText(activeModelName, false)
                }
                updateRuntimeCompatibilityNote(settings.activeModelPath)
            hasLoadedSettings = true
            formIsDirty = false
            binding.btnSaveSettings.isEnabled = true
            binding.btnResetSettings.isEnabled = true
        } finally {
            isApplyingStoredSettings = false
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
    private fun setAdvanced(expanded: Boolean) {
        binding.layoutAdvancedAi.visibility = if (expanded) View.VISIBLE else View.GONE
        binding.btnAdvancedAi.setIconResource(if (expanded) R.drawable.ic_buddy_expand else R.drawable.ic_buddy_chevron)
        androidx.core.view.ViewCompat.setStateDescription(binding.btnAdvancedAi, if (expanded) "Dibuka" else "Ditutup")
    }
    private fun updateRuntimeCompatibilityNote(modelFileName: String) {
        runtimeHelp = "LiteRT-LM menggunakan backend CPU yang didukung APK. Dukungan khusus model tidak diasumsikan."
    }
    private fun displayNameForFileName(fileName: String): String =
        viewModel.displayNameForFile(fileName)

    private fun fileNameForDisplayName(displayName: String): String =
        viewModel.fileNameForDisplay(displayName)
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("advanced_ai", _binding?.layoutAdvancedAi?.visibility == View.VISIBLE)
        super.onSaveInstanceState(outState)
    }
}

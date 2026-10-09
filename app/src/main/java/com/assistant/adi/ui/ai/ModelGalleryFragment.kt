package com.assistant.adi.ui.ai

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.assistant.adi.R
import com.assistant.adi.ui.buddy.withActionIcons
import com.assistant.adi.data.model.AiModelItem
import com.assistant.adi.data.catalog.CatalogState
import com.assistant.adi.data.catalog.CatalogSource
import com.assistant.adi.databinding.FragmentModelGalleryBinding
import com.assistant.adi.databinding.SheetModelAccessBinding
import com.assistant.adi.ui.DashboardActivity
import com.assistant.adi.ui.ai.adapter.ModelGalleryAdapter
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Locale

class ModelGalleryFragment : Fragment() {

    private var _binding: FragmentModelGalleryBinding? = null
    private val binding get() = _binding!!
    private val engineOwner: AiChatViewModel by activityViewModels()
    private val viewModel: ModelGalleryViewModel by viewModels()
    private lateinit var adapter: ModelGalleryAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentModelGalleryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupListeners()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        adapter = ModelGalleryAdapter(
            onDownloadClick = { item ->
                if (viewModel.hfToken.value.isBlank()) showAccessSheet(item) else viewModel.startDownload(item)
            },
            onPauseClick = viewModel::pauseDownload,
            onCancelClick = ::confirmCancelDownload,
            onDeleteClick = ::confirmDeleteModel,
            onSelectActiveClick = viewModel::setActiveModel,
            onInfoClick = ::showModelInfo
        )
        binding.rvModelGallery.layoutManager = LinearLayoutManager(requireContext())
        binding.rvModelGallery.adapter = adapter
    }

    private fun setupListeners() {
        binding.btnOpenLicense.setOnClickListener { showAccessSheet(selectedModel()) }
        binding.tvStorageWarning.setOnClickListener { storageConstrainedModel()?.let(::showModelInfo) }
        binding.btnOpenChat.setOnClickListener {
            if (viewModel.hasAnyReadyModel()) {
                (requireActivity() as DashboardActivity).returnToChatOrOpenNew()
            } else {
                Toast.makeText(requireContext(), getString(R.string.model_chat_requires_download), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmCancelDownload(item: AiModelItem) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Batalkan unduhan?")
            .setMessage("Batalkan unduhan ${item.shortName}? Berkas sementara akan dihapus.")
            .setPositiveButton("Batalkan") { _, _ -> viewModel.cancelDownload(item) }
            .setNegativeButton("Kembali", null)
            .show().withActionIcons(R.drawable.ic_ms_close, R.drawable.ic_ms_arrow_back)
    }

    private fun confirmDeleteModel(item: AiModelItem) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Hapus model?")
            .setMessage("Berkas model ${item.shortName} akan dihapus dari perangkat dan ruang penyimpanan akan dibebaskan. Percakapanmu tetap tersimpan.")
            .setPositiveButton("Hapus") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    engineOwner.releaseForModelChange()
                    viewModel.deleteModel(item)
                }
            }
            .setNegativeButton("Kembali", null)
            .show().withActionIcons(R.drawable.ic_ms_delete, R.drawable.ic_ms_arrow_back)
    }

    private fun showModelInfo(item: AiModelItem) {
        showSheet(item, accessMode = false)
    }

    private fun showAccessSheet(item: AiModelItem) {
        showSheet(item, accessMode = true)
    }

    private fun showSheet(item: AiModelItem, accessMode: Boolean) {
        val sheetBinding = SheetModelAccessBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(requireContext())
        dialog.setContentView(sheetBinding.root)

        sheetBinding.tvSheetTitle.text = if (accessMode) getString(R.string.model_access_title) else item.shortName
        sheetBinding.tvSheetDescription.text = if (accessMode) {
            getString(R.string.model_access_description, item.shortName, item.sizeLabel)
        } else {
            getString(R.string.model_info_description, item.description, item.ramUsageLabel, item.badge)
        }
        sheetBinding.layoutTokenInput.visibility = if (accessMode) View.VISIBLE else View.GONE
        sheetBinding.layoutAccessActions.visibility = if (accessMode) View.VISIBLE else View.GONE
        sheetBinding.btnOpenHfTokens.visibility = if (accessMode) View.VISIBLE else View.GONE
        sheetBinding.btnOpenLicense.text = getString(R.string.model_open_license, item.shortName)
        val modelUrl = item.downloadUrl.substringBefore("/resolve/")
        sheetBinding.btnOpenLicense.isEnabled = modelUrl.startsWith("https://huggingface.co/")
        sheetBinding.btnOpenLicense.setOnClickListener { if (sheetBinding.btnOpenLicense.isEnabled) openUrl(modelUrl) }

        if (accessMode) {
            // Seed once when the sheet opens; Flow updates must not replace text being edited.
            sheetBinding.etHfToken.setText(viewModel.hfToken.value)
            sheetBinding.btnDeleteToken.isEnabled = viewModel.hfToken.value.isNotEmpty()
            sheetBinding.btnOpenHfTokens.setOnClickListener { openUrl("https://huggingface.co/settings/tokens") }
            sheetBinding.btnSaveToken.setOnClickListener {
                val token = sheetBinding.etHfToken.text?.toString()?.trim().orEmpty()
                if (token.isEmpty()) {
                    sheetBinding.layoutTokenInput.error = getString(R.string.model_token_required)
                } else {
                    sheetBinding.layoutTokenInput.error = null
                    viewModel.saveHfToken(token)
                    Toast.makeText(requireContext(), getString(R.string.model_token_saved), Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
            }
            sheetBinding.btnDeleteToken.setOnClickListener {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Hapus token?")
                    .setMessage("Token Hugging Face akan dihapus dari penyimpanan perangkat.")
                    .setPositiveButton("Hapus") { _, _ ->
                        viewModel.deleteHfToken()
                        dialog.dismiss()
                    }
                    .setNegativeButton("Kembali", null)
                    .show().withActionIcons(R.drawable.ic_ms_delete, R.drawable.ic_ms_arrow_back)
            }
        }
        dialog.show()
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (error: Exception) {
            Toast.makeText(requireContext(), getString(R.string.model_open_link_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun selectedModel(): AiModelItem {
        return viewModel.models.value.firstOrNull { it.fileName == viewModel.activeModelFileName.value }
            ?: viewModel.models.value.firstOrNull()
            ?: com.assistant.adi.data.model.AiModelCatalog.getDefaultModel()
    }

    private fun storageConstrainedModel(): AiModelItem? {
        val freeBytes = (viewModel.availableStorageGb.value * 1073741824.0).toLong()
        return viewModel.models.value.firstOrNull { it.requiredStorageBytes > freeBytes }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.catalogState.flowWithLifecycle(viewLifecycleOwner.lifecycle, androidx.lifecycle.Lifecycle.State.STARTED).collectLatest { state ->
                binding.tvCatalogStatus.text = when (state) {
                    is CatalogState.Verified -> "Katalog terverifikasi • versi ${state.snapshot.version}"
                    is CatalogState.Loading -> "Memeriksa pembaruan katalog • model lokal tetap tersedia"
                    is CatalogState.OfflineCached -> if (state.snapshot.source == CatalogSource.LOCAL_FALLBACK)
                        "Katalog awal lokal • unduhan belum tersedia" else "Katalog tersimpan • offline • versi ${state.snapshot.version}"
                    is CatalogState.Failed -> "Pembaruan katalog gagal • memakai katalog tersimpan"
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.availableStorageGb.flowWithLifecycle(viewLifecycleOwner.lifecycle, androidx.lifecycle.Lifecycle.State.STARTED).collectLatest { storageGb ->
                binding.tvStorageAvailable.text = String.format(Locale.getDefault(), "Tersedia: %.2f GB", storageGb)
                val constrained = storageConstrainedModel()
                binding.tvStorageWarning.visibility = if (constrained == null) View.GONE else View.VISIBLE
                binding.tvStorageWarning.text = constrained?.let { "Ruang belum cukup untuk ${it.shortName}. Ketuk untuk melihat kebutuhan model." }.orEmpty()
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            combine(viewModel.models, viewModel.modelStates, viewModel.activeModelFileName) { models, states, active -> Triple(models, states, active) }
                .flowWithLifecycle(viewLifecycleOwner.lifecycle, androidx.lifecycle.Lifecycle.State.STARTED).collectLatest { (models, states, active) ->
                adapter.submitData(models, states, active)
                binding.btnOpenChat.isEnabled = viewModel.hasAnyReadyModel()
                binding.btnOpenChat.text = getString(if (binding.btnOpenChat.isEnabled) R.string.model_open_chat else R.string.model_chat_requires_download)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

package com.assistant.adi.ui.ai

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.flowWithLifecycle
import com.assistant.adi.R
import com.assistant.adi.ui.buddy.withActionIcons
import com.assistant.adi.ui.buddy.MonitorDetailUi
import com.assistant.adi.data.model.DownloadState
import com.assistant.adi.databinding.FragmentModelDownloadBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

class ModelDownloadFragment : Fragment() {

    private var _binding: FragmentModelDownloadBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ModelDownloadViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentModelDownloadBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupListeners()
        observeViewModel()
    }

    private fun setupListeners() {
        binding.btnModelDetailsInfo.setOnClickListener { trigger -> MonitorDetailUi(this).showInfoSheet(
            viewModel.model?.shortName ?: "Model E4B", viewModel.model?.description ?: "Varian belum tersedia di katalog.",
            viewModel.model?.let { "${it.catalogStatus}. ${it.incompatibility ?: it.badge}. ${it.ramUsageLabel}" } ?: "Katalog belum tersedia.", trigger) }
        // Open license page in browser
        binding.btnOpenLicense.setOnClickListener {
            val url = viewModel.model?.downloadUrl?.substringBefore("/resolve/").orEmpty()
            if (!url.startsWith("https://huggingface.co/")) return@setOnClickListener
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Gagal membuka browser: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        // Save token
        binding.btnSaveToken.setOnClickListener {
            val token = binding.etHfToken.text.toString().trim()
            if (token.isEmpty()) {
                binding.layoutTokenInput.error = "Token tidak boleh kosong"
            } else {
                binding.layoutTokenInput.error = null
                viewModel.saveHfToken(token)
                Toast.makeText(requireContext(), "Token berhasil disimpan!", Toast.LENGTH_SHORT).show()
            }
        }

        // Delete token
        binding.btnDeleteToken.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Hapus Token?")
                .setMessage("Apakah Anda yakin ingin menghapus token Hugging Face dari penyimpanan terenkripsi?")
                .setPositiveButton("Hapus") { _, _ ->
                    viewModel.deleteHfToken()
                    binding.etHfToken.setText("")
                    Toast.makeText(requireContext(), "Token dihapus", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Batal", null)
                .show().withActionIcons(R.drawable.ic_ms_delete, R.drawable.ic_ms_close)
        }

        // Action button (Download / Mulai Chat)
        binding.btnDownloadAction.setOnClickListener {
            val state = viewModel.downloadState.value
            if (state is DownloadState.Completed) {
                // Navigate to Chat Fragment
                navigateToChat()
            } else {
                // Start or resume download
                viewModel.startDownload()
            }
        }

        // Pause/Resume button
        binding.btnPauseResume.setOnClickListener {
            val state = viewModel.downloadState.value
            if (state is DownloadState.Downloading) {
                viewModel.pauseDownload()
            } else if (state is DownloadState.Paused) {
                viewModel.startDownload()
            }
        }

        // Cancel download button
        binding.btnCancelDownload.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Batalkan Unduhan?")
                .setMessage("Apakah Anda yakin ingin membatalkan unduhan model? File unduhan sementara akan dihapus.")
                .setPositiveButton("Ya, Batalkan") { _, _ ->
                    viewModel.pauseDownload()
                    viewModel.deleteModel()
                    Toast.makeText(requireContext(), "Unduhan dibatalkan", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Kembali", null)
                .show().withActionIcons(R.drawable.ic_ms_close, R.drawable.ic_ms_arrow_back)
        }

        // Delete model button
        binding.btnDeleteModel.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Hapus File Model?")
                .setMessage("Hapus berkas model yang tersimpan di perangkat?")
                .setPositiveButton("Hapus") { _, _ ->
                    viewModel.deleteModel()
                    Toast.makeText(requireContext(), "Model berhasil dihapus", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Batal", null)
                .show().withActionIcons(R.drawable.ic_ms_delete, R.drawable.ic_ms_close)
        }
    }

    private fun observeViewModel() {
        // Observe HF token changes to pre-fill the edit text
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.hfToken.flowWithLifecycle(viewLifecycleOwner.lifecycle, androidx.lifecycle.Lifecycle.State.STARTED).collectLatest { token ->
                if (token.isNotEmpty() && binding.etHfToken.text.toString().isEmpty()) {
                    binding.etHfToken.setText(token)
                }
                binding.btnDeleteToken.isEnabled = token.isNotEmpty()
            }
        }

        // Observe storage availability
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.availableStorageGb.flowWithLifecycle(viewLifecycleOwner.lifecycle, androidx.lifecycle.Lifecycle.State.STARTED).collectLatest { storageGb ->
                binding.tvStorageAvailable.text = String.format(Locale.US, "Tersedia: %.2f GB", storageGb)
                if (storageGb < 6.0) {
                    binding.tvStorageWarning.visibility = View.VISIBLE
                } else {
                    binding.tvStorageWarning.visibility = View.GONE
                }
            }
        }

        // Observe download state changes
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.downloadState.flowWithLifecycle(viewLifecycleOwner.lifecycle, androidx.lifecycle.Lifecycle.State.STARTED).collectLatest { state ->
                updateUiForState(state)
            }
        }
    }

    private fun updateUiForState(state: DownloadState) {
        when (state) {
            is DownloadState.Idle -> {
                binding.layoutProgress.visibility = View.GONE
                binding.layoutDownloadControls.visibility = View.GONE
                binding.cardDeleteModel.visibility = View.GONE
                
                binding.btnDownloadAction.visibility = View.VISIBLE
                binding.btnDownloadAction.isEnabled = viewModel.model?.variant != null && viewModel.model?.incompatibility == null
                binding.btnDownloadAction.text = "Unduh Model"
                binding.btnDownloadAction.setIconResource(R.drawable.ic_ms_download)
                
                binding.cardHfToken.visibility = View.VISIBLE
            }
            is DownloadState.Downloading -> {
                binding.layoutProgress.visibility = View.VISIBLE
                binding.layoutDownloadControls.visibility = View.VISIBLE
                binding.cardDeleteModel.visibility = View.GONE
                
                binding.btnDownloadAction.visibility = View.GONE
                binding.cardHfToken.visibility = View.GONE
                
                binding.btnPauseResume.text = "Jeda"
                binding.btnPauseResume.setIconResource(R.drawable.ic_ms_pause)
                
                val pct = (state.progress * 100).toInt()
                binding.progressDownload.progress = pct
                binding.tvDownloadPercent.text = "$pct%"
                
                val downloadedGb = state.bytesDownloaded.toDouble() / (1024 * 1024 * 1024)
                val totalGb = state.totalBytes.toDouble() / (1024 * 1024 * 1024)
                binding.tvDownloadBytes.text = String.format(Locale.US, "%.2f GB / %.2f GB", downloadedGb, totalGb)
                
                binding.tvDownloadSpeed.text = String.format(Locale.US, "Kecepatan: %.2f MB/s", state.speedMBps)
                
                val etaStr = if (state.etaSeconds <= 0) "--" else {
                    val minutes = state.etaSeconds / 60
                    val seconds = state.etaSeconds % 60
                    if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
                }
                binding.tvDownloadEta.text = "ETA: $etaStr"
            }
            is DownloadState.Paused -> {
                binding.layoutProgress.visibility = View.VISIBLE
                binding.layoutDownloadControls.visibility = View.VISIBLE
                binding.cardDeleteModel.visibility = View.GONE
                
                binding.btnDownloadAction.visibility = View.GONE
                binding.cardHfToken.visibility = View.GONE
                
                binding.btnPauseResume.text = "Lanjutkan"
                binding.btnPauseResume.setIconResource(R.drawable.ic_ms_chevron_right)
                
                val downloadedGb = state.bytesDownloaded.toDouble() / (1024 * 1024 * 1024)
                val totalGb = state.totalBytes.toDouble() / (1024 * 1024 * 1024)
                binding.tvDownloadBytes.text = String.format(Locale.US, "%.2f GB / %.2f GB", downloadedGb, totalGb)
                
                val pct = if (totalGb > 0) ((downloadedGb / totalGb) * 100).toInt() else 0
                binding.progressDownload.progress = pct
                binding.tvDownloadPercent.text = "$pct%"
                
                binding.tvDownloadSpeed.text = "Kecepatan: Terjeda"
                binding.tvDownloadEta.text = "ETA: --"
            }
            is DownloadState.Completed -> {
                binding.layoutProgress.visibility = View.GONE
                binding.layoutDownloadControls.visibility = View.GONE
                binding.cardDeleteModel.visibility = View.VISIBLE
                
                binding.btnDownloadAction.visibility = View.VISIBLE
                binding.btnDownloadAction.isEnabled = true
                binding.btnDownloadAction.text = "Model Siap (Mulai Obrolan)"
                binding.btnDownloadAction.setIconResource(R.drawable.ic_ms_chat)
                
                binding.cardHfToken.visibility = View.GONE
            }
            is DownloadState.Error -> {
                binding.layoutProgress.visibility = View.GONE
                binding.layoutDownloadControls.visibility = View.GONE
                binding.cardDeleteModel.visibility = View.GONE
                
                binding.btnDownloadAction.visibility = View.VISIBLE
                binding.btnDownloadAction.isEnabled = viewModel.model?.variant != null && viewModel.model?.incompatibility == null
                binding.btnDownloadAction.text = "Gagal (Coba Lagi)"
                binding.btnDownloadAction.setIconResource(R.drawable.ic_ms_refresh)
                
                binding.cardHfToken.visibility = View.VISIBLE
                
                Toast.makeText(requireContext(), "Error: ${state.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun navigateToChat() {
        val chatFragment = AiChatFragment()
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, chatFragment, "ai_chat")
            .commit()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

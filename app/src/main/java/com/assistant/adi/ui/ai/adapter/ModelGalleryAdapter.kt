package com.assistant.adi.ui.ai.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.content.res.ColorStateList
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.assistant.adi.R
import com.assistant.adi.data.model.AiModelItem
import com.assistant.adi.databinding.ItemModelGalleryBinding
import com.assistant.adi.ui.ai.ModelItemUiState
import java.util.Locale

class ModelGalleryAdapter(
    private val onDownloadClick: (AiModelItem) -> Unit,
    private val onPauseClick: (AiModelItem) -> Unit,
    private val onCancelClick: (AiModelItem) -> Unit,
    private val onDeleteClick: (AiModelItem) -> Unit,
    private val onSelectActiveClick: (AiModelItem) -> Unit,
    private val onInfoClick: (AiModelItem) -> Unit
) : RecyclerView.Adapter<ModelGalleryAdapter.ModelViewHolder>() {

    private var items: List<AiModelItem> = emptyList()
    private var stateMap: Map<String, ModelItemUiState> = emptyMap()
    private var activeModelFileName: String = ""

    fun submitData(newItems: List<AiModelItem>, newStateMap: Map<String, ModelItemUiState>, currentActiveFileName: String) {
        items = newItems
        stateMap = newStateMap
        activeModelFileName = currentActiveFileName
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ModelViewHolder {
        return ModelViewHolder(ItemModelGalleryBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: ModelViewHolder, position: Int) {
        val item = items[position]
        val state = stateMap[item.id] ?: ModelItemUiState.NotDownloaded
        val isActive = activeModelFileName.isNotBlank() && activeModelFileName.equals(item.fileName, ignoreCase = true)
        val group = if (item.familyName.isBlank()) "Model lokal" else "${item.familyName} › ${item.generationName}"
        val previous = items.getOrNull(position - 1)?.let { if (it.familyName.isBlank()) "Model lokal" else "${it.familyName} › ${it.generationName}" }
        holder.bind(item, state, isActive, group.takeIf { it != previous })
    }

    override fun getItemCount(): Int = items.size

    inner class ModelViewHolder(private val binding: ItemModelGalleryBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: AiModelItem, state: ModelItemUiState, isActive: Boolean, group: String?) {
            val context = binding.root.context
            binding.tvModelGroup.visibility = if (group == null) View.GONE else View.VISIBLE
            binding.tvModelGroup.text = group.orEmpty()
            binding.tvModelName.text = item.shortName
            binding.tvModelSize.text = context.getString(R.string.model_download_size, item.sizeLabel)
            binding.tvModelDetails.text = listOf(item.catalogStatus, item.displayVariant?.variantId ?: "Model lokal",
                item.backend, item.ramUsageLabel, item.displayVariant?.license?.let { "Lisensi: $it" },
                item.displayVariant?.takeIf { it.gated }?.let { "Akses Hugging Face diperlukan" },
                item.incompatibility?.let { "Tidak kompatibel: $it" })
                .filterNotNull().joinToString(" • ")
            binding.btnModelInfo.contentDescription = context.getString(R.string.model_info_accessibility, item.shortName)
            binding.btnModelInfo.tooltipText = binding.btnModelInfo.contentDescription
            binding.btnModelInfo.setOnClickListener { onInfoClick(item) }

            val activeReady = isActive && state is ModelItemUiState.Downloaded
            binding.cardModel.setCardBackgroundColor(ContextCompat.getColor(context,
                if (activeReady) R.color.buddy_mint else R.color.buddy_tile))
            binding.cardModel.strokeColor = ContextCompat.getColor(context, R.color.buddy_action)
            binding.cardModel.strokeWidth = if (activeReady) (2 * context.resources.displayMetrics.density).toInt() else 0
            binding.cardModel.isSelected = activeReady
            binding.tvModelStatusText.visibility = View.VISIBLE
            binding.tvModelStatusText.setTextColor(ContextCompat.getColor(context, R.color.buddy_sub))
            binding.tvModelStatusText.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
            binding.layoutModelProgress.visibility = View.GONE
            binding.progressModelDownload.isIndeterminate = false
            binding.progressModelDownload.progress = 0
            binding.tvDownloadPercentInfo.visibility = View.GONE
            binding.tvDownloadBytesInfo.visibility = View.GONE
            binding.tvDownloadBytesInfo.text = ""
            binding.tvDownloadSpeedEta.visibility = View.GONE
            binding.tvDownloadSpeedEta.text = ""
            binding.btnActionPause.visibility = View.GONE
            binding.btnActionCancel.visibility = View.GONE
            binding.btnActionDelete.visibility = View.GONE
            binding.layoutModelActions.visibility = View.VISIBLE
            binding.btnActionPrimary.visibility = View.VISIBLE
            binding.btnActionPrimary.isEnabled = true
            binding.btnActionPrimary.backgroundTintList = ContextCompat.getColorStateList(context, R.color.buddy_button_primary_bg)
            binding.btnActionPrimary.setTextColor(ContextCompat.getColorStateList(context, R.color.buddy_button_primary_text))
            binding.btnActionPrimary.iconTint = ContextCompat.getColorStateList(context, R.color.buddy_button_primary_text)
            binding.btnActionPrimary.setOnClickListener(null)
            binding.btnActionPause.setOnClickListener(null)
            binding.btnActionCancel.setOnClickListener(null)
            binding.btnActionDelete.setOnClickListener(null)

            when (state) {
                ModelItemUiState.Deleting -> {
                    binding.layoutModelActions.visibility = View.GONE
                    binding.layoutModelProgress.visibility = View.VISIBLE
                    binding.progressModelDownload.isIndeterminate = true
                    binding.tvDownloadBytesInfo.visibility = View.GONE
                    binding.tvDownloadSpeedEta.visibility = View.GONE
                    binding.btnActionPrimary.visibility = View.GONE
                    binding.tvModelStatusText.text = context.getString(R.string.model_deleting)
                }
                ModelItemUiState.NotDownloaded -> {
                    binding.tvDownloadBytesInfo.visibility = View.VISIBLE
                    binding.tvModelStatusText.text = context.getString(R.string.model_not_downloaded)
                    binding.btnActionPrimary.text = context.getString(R.string.model_download)
                    binding.btnActionPrimary.setIconResource(R.drawable.ic_ms_download)
                    binding.btnActionPrimary.setOnClickListener { onDownloadClick(item) }
                    binding.btnActionPrimary.isEnabled = item.incompatibility == null && item.variant != null
                    if (!binding.btnActionPrimary.isEnabled) binding.tvModelStatusText.text = item.incompatibility ?: context.getString(R.string.model_not_downloaded)
                }
                is ModelItemUiState.Downloading -> {
                    binding.btnActionPrimary.visibility = View.GONE
                    binding.layoutModelProgress.visibility = View.VISIBLE
                    binding.progressModelDownload.progress = (state.progress * 100).toInt().coerceIn(0, 100)
                    binding.tvDownloadPercentInfo.visibility = View.GONE
                    binding.tvDownloadBytesInfo.visibility = View.VISIBLE
                    binding.tvDownloadBytesInfo.text = context.getString(
                        R.string.model_download_progress,
                        formatBytes(state.bytesDownloaded), formatBytes(state.totalBytes),
                        (state.progress * 100).toInt().coerceIn(0, 100)
                    )
                    binding.tvDownloadSpeedEta.visibility = View.VISIBLE
                    binding.tvDownloadSpeedEta.text = context.getString(R.string.model_download_rate, state.speedMBps, formatEta(state.etaSeconds))
                    binding.tvModelStatusText.text = context.getString(R.string.model_downloading)
                    binding.btnActionPrimary.visibility = View.GONE
                    binding.btnActionPause.visibility = View.VISIBLE
                    binding.btnActionCancel.visibility = View.VISIBLE
                    binding.btnActionPause.text = context.getString(R.string.model_pause)
                    binding.btnActionPause.setIconResource(R.drawable.ic_ms_pause)
                    binding.btnActionPause.setOnClickListener { onPauseClick(item) }
                    binding.btnActionCancel.setOnClickListener { onCancelClick(item) }
                }
                is ModelItemUiState.Paused -> {
                    binding.btnActionPrimary.visibility = View.GONE
                    binding.layoutModelProgress.visibility = View.VISIBLE
                    binding.progressModelDownload.progress = if (state.totalBytes > 0L) (state.bytesDownloaded * 100 / state.totalBytes).toInt().coerceIn(0, 100) else 0
                    binding.tvDownloadPercentInfo.visibility = View.GONE
                    binding.tvDownloadBytesInfo.visibility = View.VISIBLE
                    binding.tvDownloadBytesInfo.text = context.getString(
                        R.string.model_download_progress,
                        formatBytes(state.bytesDownloaded), formatBytes(state.totalBytes), binding.progressModelDownload.progress
                    )
                    binding.tvDownloadSpeedEta.visibility = View.GONE
                    binding.tvModelStatusText.text = context.getString(R.string.model_paused)
                    binding.btnActionPrimary.visibility = View.GONE
                    binding.btnActionPause.visibility = View.VISIBLE
                    binding.btnActionCancel.visibility = View.VISIBLE
                    binding.btnActionPause.text = context.getString(R.string.model_resume)
                    binding.btnActionPause.setIconResource(R.drawable.ic_ms_chevron_right)
                    binding.btnActionPause.setOnClickListener { onDownloadClick(item) }
                    binding.btnActionCancel.setOnClickListener { onCancelClick(item) }
                }
                is ModelItemUiState.Downloaded -> {
                    binding.tvModelSize.text = context.getString(R.string.model_installed_size, formatBytes(state.fileSizeBytes))
                    binding.tvDownloadBytesInfo.visibility = View.VISIBLE
                    if (isActive) {
                        binding.tvModelStatusText.text = context.getString(R.string.model_active)
                        binding.tvModelStatusText.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_ms_check, 0, 0, 0)
                        binding.tvModelStatusText.compoundDrawableTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.buddy_action))
                        binding.tvModelStatusText.setTextColor(ContextCompat.getColor(context, R.color.buddy_action))
                        binding.btnActionPrimary.visibility = View.GONE
                        binding.layoutModelActions.visibility = View.GONE
                    } else {
                        binding.tvModelStatusText.text = context.getString(R.string.model_ready)
                        binding.btnActionPrimary.text = context.getString(R.string.model_use)
                        binding.btnActionPrimary.setIconResource(R.drawable.ic_ms_check)
                        binding.btnActionPrimary.backgroundTintList = ContextCompat.getColorStateList(context, R.color.buddy_button_clay_bg)
                        binding.btnActionPrimary.setTextColor(ContextCompat.getColorStateList(context, R.color.buddy_button_clay_text))
                        binding.btnActionPrimary.iconTint = ContextCompat.getColorStateList(context, R.color.buddy_button_clay_text)
                        binding.btnActionPrimary.setOnClickListener { onSelectActiveClick(item) }
                    }
                    binding.btnActionDelete.visibility = View.VISIBLE
                    binding.btnActionDelete.setOnClickListener { onDeleteClick(item) }
                }
                is ModelItemUiState.Error -> {
                    binding.tvModelStatusText.text = state.message
                    binding.tvModelStatusText.setTextColor(ContextCompat.getColor(context, R.color.buddy_danger))
                    binding.tvDownloadBytesInfo.visibility = View.VISIBLE
                    binding.btnActionPrimary.text = context.getString(R.string.model_retry)
                    binding.btnActionPrimary.setIconResource(R.drawable.ic_ms_refresh)
                    binding.btnActionPrimary.setOnClickListener { onDownloadClick(item) }
                    binding.btnActionPrimary.isEnabled = item.incompatibility == null && item.variant != null
                }
            }
        }

        private fun formatBytes(bytes: Long): String = if (bytes >= 1024L * 1024L * 1024L) {
            String.format(Locale.getDefault(), "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        } else {
            String.format(Locale.getDefault(), "%.0f MB", bytes / (1024.0 * 1024.0))
        }

        private fun formatEta(seconds: Long): String = when {
            seconds <= 0 -> "--"
            seconds >= 60L -> "${seconds / 60} min ${seconds % 60} dtk"
            else -> "$seconds dtk"
        }
    }
}

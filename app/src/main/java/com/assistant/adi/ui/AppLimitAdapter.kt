package com.assistant.adi.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.graphics.drawable.Drawable
import android.content.pm.PackageManager
import android.util.LruCache
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.assistant.adi.R
import com.assistant.adi.databinding.ItemAppLimitBinding

data class AppUsageInfo(
    val appName: String,
    val packageName: String,
    val usageMinutes: Int,
    val limitMinutes: Int
)

class AppLimitAdapter(
    private val onEditLimit: (AppUsageInfo) -> Unit
) : ListAdapter<AppUsageInfo, AppLimitAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAppLimitBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holder.recycle()
        super.onViewRecycled(holder)
    }

    inner class ViewHolder(private val binding: ItemAppLimitBinding) : RecyclerView.ViewHolder(binding.root) {
        private var iconJob: Job? = null
        private val iconScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        fun bind(info: AppUsageInfo) {
            iconJob?.cancel()
            binding.ivAppIcon.setImageDrawable(null)
            binding.ivAppIcon.tag = info.packageName
            val cached = iconCache.get(info.packageName)
            if (cached != null) binding.ivAppIcon.setImageDrawable(cached)
            else {
                iconJob = iconScope.launch {
                    val icon = withContext(Dispatchers.IO) {
                        try { binding.root.context.packageManager.getApplicationIcon(info.packageName) }
                        catch (_: PackageManager.NameNotFoundException) { null }
                        catch (_: SecurityException) { null }
                    }
                    if (binding.ivAppIcon.tag == info.packageName) {
                        val result = icon ?: ContextCompat.getDrawable(binding.root.context, android.R.drawable.sym_def_app_icon)
                        if (icon != null) iconCache.put(info.packageName, icon)
                        binding.ivAppIcon.setImageDrawable(result)
                    }
                }
            }
            binding.tvAppName.text = info.appName
            
            val limitStr = if (info.limitMinutes > 0) "${info.limitMinutes} menit" else "belum diatur"
            binding.tvUsageAndLimit.text = "Hari ini ${info.usageMinutes} menit · batas $limitStr"
            binding.progressLimit.visibility = if (info.limitMinutes > 0) android.view.View.VISIBLE else android.view.View.GONE
            
            if (info.limitMinutes > 0) {
                val progressPercent = ((info.usageMinutes.toFloat() / info.limitMinutes) * 100).toInt()
                binding.progressLimit.progress = progressPercent.coerceAtMost(100)
                
                // Color indicator red if limit reached/exceeded
                val colorRes = if (info.usageMinutes >= info.limitMinutes) R.color.buddy_danger else R.color.buddy_action
                binding.progressLimit.setIndicatorColor(ContextCompat.getColor(binding.root.context, colorRes))
            } else {
                binding.progressLimit.progress = 0
            }

            binding.btnEditLimit.setOnClickListener {
                onEditLimit(info)
            }
            binding.btnEditLimit.contentDescription = "Atur batas ${info.appName}"
        }

        fun recycle() {
            iconJob?.cancel()
            iconJob = null
            binding.ivAppIcon.tag = null
            binding.ivAppIcon.setImageDrawable(null)
            binding.root.setOnClickListener(null)
            binding.btnEditLimit.setOnClickListener(null)
        }

    }

    class DiffCallback : DiffUtil.ItemCallback<AppUsageInfo>() {
        override fun areItemsTheSame(oldItem: AppUsageInfo, newItem: AppUsageInfo): Boolean {
            return oldItem.packageName == newItem.packageName
        }

        override fun areContentsTheSame(oldItem: AppUsageInfo, newItem: AppUsageInfo): Boolean {
            return oldItem == newItem
        }
    }

    companion object {
        private val iconCache = object : LruCache<String, Drawable>(48) {}
    }
}

package com.assistant.adi.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.assistant.adi.data.AppNotificationStat
import com.assistant.adi.databinding.ItemNotifStatBinding
import java.util.Locale

class NotifStatAdapter : ListAdapter<AppNotificationStat, NotifStatAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemNotifStatBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val maxCount = currentList.firstOrNull()?.count ?: 100
        holder.bind(getItem(position), maxCount)
    }

    inner class ViewHolder(private val binding: ItemNotifStatBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(stat: AppNotificationStat, maxCount: Int) {
            binding.tvStatAppName.text = stat.appName
            binding.tvStatCount.text = "${stat.count} notifikasi"
            
            // Set progress relative to the highest sender count (so the top app is 100%)
            val progressPercent = if (maxCount > 0) (stat.count * 100) / maxCount else 0
            binding.progressStatCount.progress = progressPercent
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<AppNotificationStat>() {
        override fun areItemsTheSame(oldItem: AppNotificationStat, newItem: AppNotificationStat): Boolean {
            return oldItem.appName == newItem.appName
        }

        override fun areContentsTheSame(oldItem: AppNotificationStat, newItem: AppNotificationStat): Boolean {
            return oldItem == newItem
        }
    }
}

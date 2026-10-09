package com.assistant.adi.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.assistant.adi.R
import com.assistant.adi.data.NetworkLog
import com.assistant.adi.databinding.ItemNetworkLogBinding
import java.text.SimpleDateFormat
import java.util.*

class NetworkHistoryAdapter : ListAdapter<NetworkLog, NetworkHistoryAdapter.ViewHolder>(DiffCallback()) {

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("d MMM yyyy", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemNetworkLogBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemNetworkLogBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(log: NetworkLog) {
            val dateStr = dateFormat.format(Date(log.timestamp))
            val timeStr = timeFormat.format(Date(log.timestamp))
            binding.tvLogTime.text = "$dateStr · $timeStr"
            binding.tvLogType.text = log.type.uppercase(Locale.getDefault())

            // Dynamic speeds
            binding.tvLogSpeeds.text = String.format(
                Locale.getDefault(),
                "↓ %.1f KB/s  ↑ %.1f KB/s",
                log.downloadSpeed,
                log.uploadSpeed
            )
            
            binding.tvLogPing.text = if (log.ping > 0) "Ping: ${log.ping} ms" else "Ping: Tidak tersedia"

            // Set state indicator color
            val colorRes = when (log.type.lowercase(Locale.getDefault())) {
                "wifi" -> R.color.buddy_action
                "mobile", "seluler" -> R.color.buddy_chart_blue
                else -> R.color.buddy_sub
            }
            binding.viewStateDot.backgroundTintList = ContextCompat.getColorStateList(binding.root.context, colorRes)
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<NetworkLog>() {
        override fun areItemsTheSame(oldItem: NetworkLog, newItem: NetworkLog): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: NetworkLog, newItem: NetworkLog): Boolean {
            return oldItem == newItem
        }
    }
}

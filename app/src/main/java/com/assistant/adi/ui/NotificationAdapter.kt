package com.assistant.adi.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.assistant.adi.data.NotificationLog
import com.assistant.adi.databinding.ItemNotificationLogBinding
import java.text.SimpleDateFormat
import java.util.*

class NotificationAdapter : ListAdapter<NotificationLog, NotificationAdapter.ViewHolder>(DiffCallback()) {

    private val timeFormat = SimpleDateFormat("hh:mm a", Locale.US)
    private val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.US)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemNotificationLogBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemNotificationLogBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(log: NotificationLog) {
            val dateStr = dateFormat.format(Date(log.timestamp))
            val timeStr = timeFormat.format(Date(log.timestamp))
            
            binding.tvNotifAppName.text = log.appName
            binding.tvNotifTime.text = "$dateStr $timeStr"
            binding.tvNotifTitle.text = log.title
            binding.tvNotifContent.text = log.content
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<NotificationLog>() {
        override fun areItemsTheSame(oldItem: NotificationLog, newItem: NotificationLog): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: NotificationLog, newItem: NotificationLog): Boolean {
            return oldItem == newItem
        }
    }
}

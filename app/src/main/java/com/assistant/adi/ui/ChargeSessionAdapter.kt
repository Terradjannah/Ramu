package com.assistant.adi.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.assistant.adi.data.ChargeSession
import com.assistant.adi.databinding.ItemChargeSessionBinding
import java.text.SimpleDateFormat
import java.util.*

class ChargeSessionAdapter : ListAdapter<ChargeSession, ChargeSessionAdapter.ViewHolder>(DiffCallback()) {

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("d MMMM yyyy", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemChargeSessionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemChargeSessionBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(session: ChargeSession) {
            val dateStr = dateFormat.format(Date(session.startTime))
            val startTimeStr = timeFormat.format(Date(session.startTime))
            val endTimeStr = if (session.endTime > 0) timeFormat.format(Date(session.endTime)) else "Sedang berlangsung"
            
            binding.tvSessionDate.text = dateStr
            binding.tvSessionTime.text = "$startTimeStr - $endTimeStr"
            
            val gain = session.endPercent - session.startPercent
            binding.tvSessionGain.text = if (gain >= 0) "+$gain%" else "$gain%"
            binding.tvSessionLevels.text = "${session.startPercent}% → ${session.endPercent}%"
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<ChargeSession>() {
        override fun areItemsTheSame(oldItem: ChargeSession, newItem: ChargeSession): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ChargeSession, newItem: ChargeSession): Boolean {
            return oldItem == newItem
        }
    }
}

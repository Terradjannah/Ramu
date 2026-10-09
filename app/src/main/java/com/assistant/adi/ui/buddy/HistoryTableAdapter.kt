package com.assistant.adi.ui.buddy

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.assistant.adi.R

data class MonitorHistoryField(val label: String, val value: String)

data class MonitorHistoryRow(
    val id: Long,
    val key: String,
    val timestamp: String,
    val mainLabel: String,
    val mainValue: String,
    val fields: List<MonitorHistoryField>,
    val statuses: List<String> = emptyList(),
    val detail: String? = null
)

class HistoryTableAdapter(
    private val onDetail: (MonitorHistoryRow, View) -> Unit = { _, _ -> }
) :
    ListAdapter<MonitorHistoryRow, HistoryTableAdapter.RowHolder>(object : DiffUtil.ItemCallback<MonitorHistoryRow>() {
        override fun areItemsTheSame(oldItem: MonitorHistoryRow, newItem: MonitorHistoryRow) = oldItem.key == newItem.key
        override fun areContentsTheSame(oldItem: MonitorHistoryRow, newItem: MonitorHistoryRow) = oldItem == newItem
    }) {
    init { setHasStableIds(true) }

    override fun getItemId(position: Int) = getItem(position).id
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = RowHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_monitor_history, parent, false), onDetail
    )
    override fun onBindViewHolder(holder: RowHolder, position: Int) = holder.bind(getItem(position))

    class RowHolder(itemView: View, private val onDetail: (MonitorHistoryRow, View) -> Unit) : RecyclerView.ViewHolder(itemView) {
        private val timestamp = itemView.findViewById<TextView>(R.id.history_timestamp)
        private val main = itemView.findViewById<TextView>(R.id.history_main)
        private val fields = itemView.findViewById<LinearLayout>(R.id.history_fields)
        private val status = itemView.findViewById<TextView>(R.id.history_status)
        fun bind(row: MonitorHistoryRow) {
            timestamp.text = row.timestamp
            main.text = "${row.mainLabel}: ${row.mainValue}"
            fields.removeAllViews()
            fields.visibility = if (row.fields.isEmpty()) View.GONE else View.VISIBLE
            row.fields.forEach { field ->
                fields.addView(TextView(itemView.context).apply {
                    text = "${field.label}: ${field.value}"
                    textSize = 14f
                    setTextColor(main.currentTextColor)
                    setPadding(0, 0, 0, (4 * resources.displayMetrics.density).toInt())
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
            status.text = row.statuses.joinToString(" · ")
            status.visibility = if (row.statuses.isEmpty()) View.GONE else View.VISIBLE
            val description = (listOf(row.timestamp, "${row.mainLabel}: ${row.mainValue}") +
                row.fields.map { "${it.label}: ${it.value}" } + row.statuses).joinToString(". ")
            itemView.isActivated = false
            itemView.isSelected = false
            itemView.isPressed = false
            itemView.contentDescription = if (row.detail != null) "$description. Buka rincian" else description
            val actionable = row.detail != null
            itemView.isClickable = actionable
            itemView.isFocusable = actionable
            itemView.setOnClickListener(if (actionable) View.OnClickListener { onDetail(row, itemView) } else null)
            listOf(timestamp, main, fields, status).forEach { it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        }
    }
}

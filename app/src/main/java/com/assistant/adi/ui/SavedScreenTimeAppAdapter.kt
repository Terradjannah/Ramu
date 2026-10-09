package com.assistant.adi.ui

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.LruCache
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.assistant.adi.ui.buddy.screenDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SavedScreenTimeApp(val packageName: String, val label: String, val minutes: Long)

class SavedScreenTimeAppAdapter(private val showPackageName: Boolean = true) : RecyclerView.Adapter<SavedScreenTimeAppAdapter.Holder>() {
    private var records: List<SavedScreenTimeApp> = emptyList()

    fun submit(records: List<SavedScreenTimeApp>) {
        this.records = records
        notifyDataSetChanged()
    }

    override fun getItemCount() = records.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val density = parent.resources.displayMetrics.density
        val row = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = (56 * density).toInt()
            setPadding((12 * density).toInt(), (4 * density).toInt(), (12 * density).toInt(), (4 * density).toInt())
        }
        val icon = ImageView(parent.context).apply { importantForAccessibility = ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO }
        row.addView(icon, LinearLayout.LayoutParams((36 * density).toInt(), (36 * density).toInt()).apply { marginEnd = (12 * density).toInt() })
        val text = TextView(parent.context).apply { textSize = 15f }
        row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        return Holder(row, icon, text)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(records[position], showPackageName)

    override fun onViewRecycled(holder: Holder) {
        holder.recycle()
        super.onViewRecycled(holder)
    }

    class Holder(row: LinearLayout, private val icon: ImageView, private val text: TextView) : RecyclerView.ViewHolder(row) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private var job: Job? = null

        fun bind(record: SavedScreenTimeApp, showPackageName: Boolean) {
            job?.cancel()
            icon.tag = record.packageName
            icon.setImageDrawable(iconCache.get(record.packageName) ?: ContextCompat.getDrawable(icon.context, android.R.drawable.sym_def_app_icon))
            val duration = screenDuration(record.minutes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            text.text = "${record.label}\n$duration" + if (showPackageName) " · ${record.packageName}" else ""
            itemView.contentDescription = "${record.label}, $duration" + if (showPackageName) ", ${record.packageName}" else ""
            if (iconCache.get(record.packageName) == null) job = scope.launch {
                val loaded = withContext(Dispatchers.IO) {
                    try { icon.context.packageManager.getApplicationIcon(record.packageName) }
                    catch (_: PackageManager.NameNotFoundException) { null }
                    catch (_: SecurityException) { null }
                }
                if (icon.tag == record.packageName && loaded != null) {
                    iconCache.put(record.packageName, loaded)
                    icon.setImageDrawable(loaded)
                }
            }
        }

        fun recycle() { job?.cancel(); icon.tag = null }
    }

    private companion object { val iconCache = object : LruCache<String, Drawable>(48) {} }
}

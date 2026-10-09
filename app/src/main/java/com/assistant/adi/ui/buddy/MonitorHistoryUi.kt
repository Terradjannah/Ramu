package com.assistant.adi.ui.buddy

import android.app.DatePickerDialog
import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import com.assistant.adi.R
import com.google.android.material.button.MaterialButton
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.assistant.adi.data.HistoryDirection
import com.assistant.adi.data.HistoryFilter
import com.assistant.adi.data.HistoryMetric
import com.assistant.adi.data.HistoryRange
import com.assistant.adi.data.HistorySort
import com.assistant.adi.data.MonitorHistoryPage
import com.assistant.adi.data.MonitorHistoryState
import com.assistant.adi.databinding.ViewMonitorHistoryBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.text.DateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

class MonitorHistoryUi(
    private val fragment: Fragment,
    private val binding: ViewMonitorHistoryBinding,
    private val outerScroll: NestedScrollView,
    private val state: MonitorHistoryState,
    private val metric: HistoryMetric,
    private val onRowSelected: ((MonitorHistoryRow, View) -> Unit)? = null
) {
    private val adapter = HistoryTableAdapter(::openDetail)
    private var lastKey: String? = null
    private var generation = 0
    private var restoredOnce = false
    private var notice: String? = null
    private val dateFormat = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("id-ID"))

    init {
        binding.historyRows.layoutManager = LinearLayoutManager(fragment.requireContext())
        binding.historyRows.adapter = adapter
        binding.historyRows.isNestedScrollingEnabled = false
        binding.historyTitle.text = when (metric) {
            HistoryMetric.BATTERY -> "Catatan baterai"
            HistoryMetric.RAM -> "Catatan RAM"
            HistoryMetric.NETWORK -> "Catatan lalu lintas jaringan"
            HistoryMetric.INTERNET -> "Catatan penggunaan internet"
            else -> "Catatan tersimpan"
        }
        binding.historyRange.setOnClickListener { chooseRange() }
        binding.historySort.setOnClickListener { chooseSort() }
        binding.historyFilter.setOnClickListener { chooseFilter() }
        binding.historyPrevious.setOnClickListener { state.page(state.input.value.page - 1) }
        binding.historyNext.setOnClickListener { state.page(state.input.value.page + 1) }
        binding.historyRetry.setOnClickListener { state.refreshRollingWindow() }
        binding.historyState.text = "Memuat catatan…"
        renderControls()
    }

    fun saveAnchor() = state.saveAnchor(outerScroll.scrollY)

    fun setNotice(value: String?) {
        notice = value
        binding.historyRangeSummary.text = "15 catatan per halaman" + (value?.let { " · $it" } ?: "")
    }

    fun showError(error: String?) {
        if (error == null) {
            binding.historyRetry.visibility = View.GONE
            if (lastKey == null || lastKey != state.anchorKey()) binding.historyState.text = "Memuat catatan…"
        } else {
            binding.historyRetry.visibility = View.VISIBLE
            binding.historyState.text = error
        }
    }

    fun <T> render(page: MonitorHistoryPage<T>, rows: List<MonitorHistoryRow>, recordingNotice: String? = null) {
        val input = state.input.value
        if (page.page != input.page) return
        val lastPage = ((page.count - 1).coerceAtLeast(0)) / MonitorHistoryPage.PAGE_SIZE
        if (input.page > lastPage) { state.page(lastPage); return }
        val key = state.anchorKey()
        val changed = lastKey != null && lastKey != key
        if (changed) {
            generation++
            outerScroll.post { if (state.anchorKey() == key) outerScroll.smoothScrollTo(0, binding.root.top) }
        }
        lastKey = key
        val currentGeneration = generation
        adapter.submitList(rows) {
            if (lastKey == key && currentGeneration == generation && !changed && !restoredOnce) {
                restoredOnce = true
                state.restoredAnchor()?.let { y -> outerScroll.post { if (lastKey == key) outerScroll.scrollTo(0, y) } }
            }
        }
        val start = input.page * MonitorHistoryPage.PAGE_SIZE + 1
        val end = (input.page + 1) * MonitorHistoryPage.PAGE_SIZE
        binding.historyState.text = if (page.count == 0) "0 dari 0 catatan" else "$start–${minOf(end, page.count)} dari ${page.count} catatan"
        binding.historyRetry.visibility = View.GONE
        binding.historyPrevious.isEnabled = input.page > 0
        binding.historyNext.isEnabled = page.hasNext
        binding.tvHistoryEmpty.visibility = if (page.count == 0) View.VISIBLE else View.GONE
        binding.tvHistoryEmpty.text = if (page.bounds.first == null) "Belum ada catatan tersimpan." else "Tidak ada catatan yang cocok dalam rentang dan filter ini."
        val bounds = if (page.bounds.first == null) "Belum ada rentang tersimpan" else if (metric == HistoryMetric.INTERNET)
            "Tersimpan ${page.bounds.first} – ${page.bounds.last}" else runCatching {
            val format = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault())
            "Tersimpan ${format.format(Date(page.bounds.first!!.toLong()))} – ${format.format(Date(page.bounds.last!!.toLong()))}"
        }.getOrDefault("Rentang tersimpan tidak tersedia")
        binding.historyFooter.text = bounds
        setNotice(recordingNotice ?: notice)
        renderControls()
    }

    private fun renderControls() {
        val input = state.input.value
        binding.historyRange.text = rangeLabel()
        binding.historySort.text = "Urutkan: ${sortChoices().first { it.first == input.sort }.second} ${if (input.direction == HistoryDirection.DESC) "↓" else "↑"}"
        val filters = filterChoices()
        binding.historyFilter.visibility = if (filters.size > 1) View.VISIBLE else View.GONE
        binding.historyFilter.text = "Filter: ${filters.first { it.first == input.filter }.second}"
    }

    private fun rangeLabel(): String {
        val input = state.input.value
        if (metric == HistoryMetric.INTERNET && input.range == HistoryRange.ALL) return "Siklus aktif"
        return when (input.range) {
            HistoryRange.HOURS_24 -> "24 jam terakhir"
            HistoryRange.ALL -> "Semua catatan tersimpan"
            HistoryRange.DAYS_7 -> "7 hari terakhir · ${LocalDate.now().minusDays(6).format(dateFormat)} – ${LocalDate.now().format(dateFormat)}"
            HistoryRange.DAYS_30 -> "30 hari"
            HistoryRange.CUSTOM -> "${input.startDate?.format(dateFormat)} – ${input.endDate?.format(dateFormat)}"
        }
    }

    private fun chooseRange() {
        chooseCustomDate(maxDays = if (metric == HistoryMetric.INTERNET) 31 else null)
    }

    private fun chooseCustomDate(maxDays: Long? = null) {
        val today = LocalDate.now(ZoneId.systemDefault())
        val current = state.input.value
        val initial = current.startDate ?: today.minusDays(6)
        pickDate("Tanggal mulai", initial.coerceAtMost(today)) { start ->
            val last = if (maxDays == null) today else minOf(today, start.plusDays(maxDays - 1))
            val initialEnd = (current.endDate ?: today).coerceIn(start, last)
            pickDate(if (maxDays == null) "Tanggal akhir" else "Tanggal akhir (maks. $maxDays hari)", initialEnd, start, last) { end ->
                state.range(HistoryRange.CUSTOM, start, end)
                renderControls()
            }
        }
    }

    private fun pickDate(title: String, initial: LocalDate, min: LocalDate? = null,
        max: LocalDate = LocalDate.now(), selected: (LocalDate) -> Unit) {
        DatePickerDialog(fragment.requireContext(), { _, year, month, day -> selected(LocalDate.of(year, month + 1, day)) },
            initial.year, initial.monthValue - 1, initial.dayOfMonth).apply {
            setTitle(title)
            val zone = ZoneId.systemDefault()
            datePicker.maxDate = max.atStartOfDay(zone).toInstant().toEpochMilli()
            min?.let { datePicker.minDate = it.atStartOfDay(zone).toInstant().toEpochMilli() }
        }.show()
    }

    private fun chooseSort() {
        val options = sortChoices()
        HistorySortSheet.show(fragment.requireContext(), options.map { HistorySortSheet.Option(it.second, sortIcon(it.first), it.first == HistorySort.DATE) },
            options.indexOfFirst { it.first == state.input.value.sort }, state.input.value.direction == HistoryDirection.DESC) { index, descending ->
            state.sort(options[index].first, if (descending) HistoryDirection.DESC else HistoryDirection.ASC)
        }
    }

    private fun sortIcon(sort: HistorySort) = when (sort) {
        HistorySort.DATE -> R.drawable.ic_ms_calendar_month
        HistorySort.WIFI -> R.drawable.ic_ms_wifi
        HistorySort.MOBILE -> R.drawable.ic_ms_signal_cellular_alt
        HistorySort.PING -> R.drawable.ic_ms_network_ping
        else -> R.drawable.ic_ms_bar_chart
    }

    private fun chooseFilter() {
        val options = filterChoices()
        choices("Filter catatan", options.map { it.second }, options.indexOfFirst { it.first == state.input.value.filter }) {
            state.filter(options[it].first)
        }
    }

    private fun sortChoices(): List<Pair<HistorySort, String>> = when (metric) {
        HistoryMetric.BATTERY -> listOf(HistorySort.DATE to "Waktu", HistorySort.PERCENTAGE to "Daya", HistorySort.TEMPERATURE to "Suhu")
        HistoryMetric.RAM -> listOf(HistorySort.DATE to "Waktu", HistorySort.USED_RAM to "Dipakai", HistorySort.AVAILABLE_RAM to "Tersedia", HistorySort.RAM_PERCENTAGE to "Persentase dipakai")
        HistoryMetric.NETWORK -> listOf(HistorySort.DATE to "Waktu", HistorySort.DOWNLOAD to "Masuk", HistorySort.UPLOAD to "Keluar", HistorySort.PING to "Ping")
        HistoryMetric.INTERNET -> listOf(HistorySort.DATE to "Tanggal", HistorySort.TOTAL to "Total", HistorySort.WIFI to "Wi-Fi", HistorySort.MOBILE to "Seluler")
        HistoryMetric.SCREEN_TIME -> listOf(HistorySort.DATE to "Tanggal", HistorySort.TOTAL to "Total")
        else -> listOf(HistorySort.DATE to "Tanggal")
    }

    private fun filterChoices(): List<Pair<HistoryFilter, String>> = when (metric) {
        HistoryMetric.BATTERY -> listOf(HistoryFilter.ALL to "Semua", HistoryFilter.CHARGING to "Mengisi", HistoryFilter.DISCHARGING to "Memakai daya")
        HistoryMetric.NETWORK -> listOf(HistoryFilter.ALL to "Semua", HistoryFilter.WIFI to "Wi-Fi", HistoryFilter.MOBILE to "Seluler", HistoryFilter.OFFLINE to "Offline")
        HistoryMetric.INTERNET -> listOf(HistoryFilter.ALL to "Semua", HistoryFilter.COMPLETE to "Lengkap", HistoryFilter.PARTIAL to "Parsial", HistoryFilter.MISSING to "Belum ada sampel")
        else -> listOf(HistoryFilter.ALL to "Semua")
    }

    private fun choices(title: String, labels: List<String>, checked: Int, selected: (Int) -> Unit) {
        val dialog = BottomSheetDialog(fragment.requireContext())
        val density = fragment.resources.displayMetrics.density
        val content = LinearLayout(fragment.requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt())
            addView(TextView(context).apply { text = title; textSize = 20f })
            labels.forEachIndexed { index, label ->
                addView(RadioButton(context).apply {
                    text = label
                    isChecked = index == checked
                    minHeight = (48 * density).toInt()
                    setOnClickListener { selected(index); dialog.dismiss() }
                })
            }
        }
        dialog.setContentView(content)
        dialog.show()
    }

    private fun openDetail(row: MonitorHistoryRow, source: View) {
        if (onRowSelected != null) {
            onRowSelected.invoke(row, source)
            return
        }
        val dialog = BottomSheetDialog(fragment.requireContext())
        val density = fragment.resources.displayMetrics.density
        dialog.setContentView(TextView(fragment.requireContext()).apply {
            text = row.detail
            textSize = 16f
            setPadding((20 * density).toInt(), (24 * density).toInt(), (20 * density).toInt(), (24 * density).toInt())
        })
        dialog.setOnDismissListener { source.post { if (source.isAttachedToWindow) source.requestFocus() } }
        dialog.show()
    }
}

object HistorySortSheet {
    data class Option(val label: String, val icon: Int, val isDate: Boolean = false, val isName: Boolean = false)

    fun show(context: Context, options: List<Option>, selected: Int, descending: Boolean, apply: (Int, Boolean) -> Unit) {
        val dialog = BottomSheetDialog(context)
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(20))
        }
        val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(TextView(context).apply { text = "Urutkan"; textSize = 20f }, LinearLayout.LayoutParams(0, dp(48), 1f))
        header.addView(MaterialButton(context).apply {
            text = "Tutup"
            icon = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_ms_close)
            minHeight = dp(48)
            setOnClickListener { dialog.dismiss() }
        })
        content.addView(header)
        content.addView(TextView(context).apply { text = "Kolom" })
        val fields = RadioGroup(context).apply { orientation = RadioGroup.VERTICAL }
        options.forEachIndexed { index, option ->
            fields.addView(RadioButton(context).apply {
                id = View.generateViewId()
                text = option.label
                minHeight = dp(48)
                isChecked = index == selected
                setCompoundDrawablesRelativeWithIntrinsicBounds(option.icon, 0, 0, 0)
                compoundDrawablePadding = dp(8)
                compoundDrawableTintList = textColors
            })
        }
        content.addView(fields)
        content.addView(TextView(context).apply { text = "Arah" })
        val directions = RadioGroup(context).apply { orientation = RadioGroup.VERTICAL }
        val directionButtons = (0..1).map { index -> RadioButton(context).apply {
            id = View.generateViewId()
            minHeight = dp(48)
            isChecked = (index == 0) == descending
            setCompoundDrawablesRelativeWithIntrinsicBounds(if (index == 0) R.drawable.ic_ms_arrow_downward else R.drawable.ic_ms_arrow_upward, 0, 0, 0)
            compoundDrawablePadding = dp(8)
            compoundDrawableTintList = textColors
            directions.addView(this)
        } }
        fun updateDirections() {
            val option = options[fields.indexOfChild(fields.findViewById(fields.checkedRadioButtonId))]
            val labels = when {
                option.isDate -> "Terbaru" to "Terlama"
                option.isName -> "Z–A" to "A–Z"
                else -> "Terbesar" to "Terkecil"
            }
            directionButtons[0].text = labels.first
            directionButtons[1].text = labels.second
        }
        fields.setOnCheckedChangeListener { _, _ -> updateDirections() }
        updateDirections()
        content.addView(directions)
        content.addView(MaterialButton(context).apply {
            text = "Terapkan"
            icon = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_ms_check)
            minHeight = dp(48)
            setOnClickListener {
                val index = fields.indexOfChild(fields.findViewById(fields.checkedRadioButtonId))
                apply(index, directions.checkedRadioButtonId == directionButtons[0].id)
                dialog.dismiss()
            }
        })
        dialog.setContentView(content)
        dialog.show()
    }
}

package com.assistant.adi.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.text.Editable
import android.text.TextWatcher
import android.app.DatePickerDialog
import android.widget.Toast
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.assistant.adi.R
import com.assistant.adi.data.InternetUsageDaily
import com.assistant.adi.data.HistoryMetric
import com.assistant.adi.databinding.FragmentInternetUsageBinding
import com.assistant.adi.ui.buddy.BuddyCharts
import com.assistant.adi.ui.buddy.MonitorHistoryField
import com.assistant.adi.ui.buddy.MonitorHistoryUi
import com.assistant.adi.ui.buddy.MonitorDetailPanel
import com.assistant.adi.ui.buddy.MonitorDetailUi
import com.assistant.adi.ui.buddy.MonitorPanelRail
import com.assistant.adi.ui.buddy.MonitorHistoryRow
import com.assistant.adi.util.InternetUsagePolicy
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.listener.OnChartValueSelectedListener
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.text.DateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

class InternetUsageFragment : Fragment() {
    private var _binding: FragmentInternetUsageBinding? = null
    private val binding get() = _binding!!
    private val viewModel: InternetUsageViewModel by viewModels()
    private val detailUi by lazy { MonitorDetailUi(this) }
    private var selectedPanel = MonitorDetailPanel.SUMMARY
    private var panelRail: MonitorPanelRail? = null
    private val panelScroll = mutableMapOf<MonitorDetailPanel, Int>()
    private data class ChartDay(val date: LocalDate, val record: InternetUsageDaily?)
    private var chartDays: List<ChartDay> = emptyList()
    private var updatingHighlight = false
    private var selectedUsageStart: LocalDate? = null
    private var selectedUsageEnd: LocalDate? = null
    private var selectedChartDate: String? = null
    private var currentState: InternetUsageUiState? = null
    private var historyUi: MonitorHistoryUi? = null
    private enum class SettingsSheet { TARGET, DATES }
    private var activeSettingsSheet: SettingsSheet? = null
    private var settingsDialog: BottomSheetDialog? = null
    private var budgetDraft: String? = null
    private var targetDaysDraft: String? = null
    private var cycleStartDraft: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedPanel = savedInstanceState?.getString(KEY_PANEL)?.let { runCatching { MonitorDetailPanel.valueOf(it) }.getOrNull() }
            ?: MonitorDetailPanel.SUMMARY
        savedInstanceState?.getIntArray("panel_scroll")?.forEachIndexed { index, offset -> panelScroll[MonitorDetailPanel.entries[index]] = offset }
        selectedUsageStart = savedInstanceState?.getString(KEY_RANGE_START)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        selectedUsageEnd = savedInstanceState?.getString(KEY_RANGE_END)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        selectedChartDate = savedInstanceState?.getString(KEY_CHART_DATE)
        activeSettingsSheet = savedInstanceState?.getString(KEY_SETTINGS_SHEET)?.let {
            runCatching { SettingsSheet.valueOf(it) }.getOrNull()
        }
        budgetDraft = savedInstanceState?.getString(KEY_BUDGET_DRAFT)
        targetDaysDraft = savedInstanceState?.getString(KEY_TARGET_DAYS_DRAFT)
        cycleStartDraft = savedInstanceState?.getString(KEY_CYCLE_START_DRAFT)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentInternetUsageBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        super.onViewCreated(view, state)
        setupChart()
        listOf(binding.todayTransportRow, binding.monthlyTransportRow).forEach { row ->
            row.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> reflowTransport(row) }
        }
        setupHistory()
        panelRail = MonitorPanelRail(requireContext(), MonitorDetailPanel.entries, ::selectPanel)
        binding.panelRail.addView(panelRail)
        detailUi.installInfoAction(
            requireActivity(), viewLifecycleOwner, "Penggunaan internet",
            "Angka berasal dari sampel harian Wi-Fi dan seluler. Hari ini masih berjalan; siklus hanya lengkap bila setiap hari dan kedua transport tersedia. Android dapat menunda pencatatan latar belakang.",
            "InternetUsageDaily dari pencatatan latar belakang Android, dengan jadwal setiap ${com.assistant.adi.data.PrefsManager(requireContext()).refreshIntervalMinutes} menit. Membuka halaman ini tidak memaksa sampel baru."
        )
        binding.btnUsageAccess.setOnClickListener { (requireActivity() as DashboardActivity).navigateSection("permissions") }
        binding.btnTodaySetLimit.setOnClickListener { selectPanel(MonitorDetailPanel.SETTINGS) }
        binding.btnMonthlySetLimit.setOnClickListener { selectPanel(MonitorDetailPanel.SETTINGS) }
        binding.btnTodayInfo.setOnClickListener { trigger ->
            detailUi.showInfoSheet(getString(R.string.internet_today),
                getString(R.string.internet_today_explanation), recordingInfo(), trigger = trigger,
                actionLabel = "Pencatatan riwayat", actionIcon = R.drawable.ic_ms_history,
                action = { (requireActivity() as DashboardActivity).navigateSection("background") })
        }
        binding.btnMonthlyInfo.setOnClickListener { trigger ->
            detailUi.showInfoSheet(getString(R.string.internet_monthly),
                getString(R.string.internet_monthly_explanation), recordingInfo(), trigger = trigger,
                actionLabel = "Pencatatan riwayat", actionIcon = R.drawable.ic_ms_history,
                action = { (requireActivity() as DashboardActivity).navigateSection("background") })
        }
        binding.btnChartInfo.setOnClickListener { trigger ->
            detailUi.showInfoSheet(getString(R.string.internet_chart_title),
                getString(R.string.internet_chart_explanation), trigger = trigger)
        }
        binding.btnOpenSettings.setOnClickListener { showSettingsSheet(SettingsSheet.TARGET) }
        binding.btnSettingsDates.setOnClickListener { showSettingsSheet(SettingsSheet.DATES) }
        binding.btnSettingsInfo.setOnClickListener { trigger ->
            detailUi.showInfoSheet("Atur penggunaan data", getString(R.string.internet_settings_explanation), trigger = trigger)
        }
        binding.btnUsageRangeStart.setOnClickListener { currentState?.let { chooseUsageDate(it, true) } }
        binding.btnUsageRangeEnd.setOnClickListener { currentState?.let { chooseUsageDate(it, false) } }
        viewModel.retainedHistoryPage.observe(viewLifecycleOwner) { page ->
            val today = LocalDate.now(ZoneId.systemDefault()).toString()
            historyUi?.render(page, page.records.map { slot ->
                val record = slot.record
                val date = slot.date.toString()
                val wifi = record?.wifiBytes?.let(::formatHistoryGb) ?: "Belum tersedia"
                val mobile = record?.mobileBytes?.let(::formatHistoryGb) ?: "Belum tersedia"
                val total = if (record?.wifiBytes != null && record.mobileBytes != null) formatHistoryGb(record.wifiBytes + record.mobileBytes) else "Belum tersedia"
                val completeness = when {
                    record == null -> "Belum ada sampel"
                    record.wifiBytes == null || record.mobileBytes == null -> "Parsial"
                    else -> "Lengkap"
                }
                val temporal = when {
                    date == today -> "Hari berjalan"
                    record != null && !record.finalized -> "Belum final"
                    else -> null
                }
                val detail = record?.let { "Tanggal: ${slot.date.format(accessibleDateFormatter)}\nTotal: $total\nWi-Fi: $wifi\nSeluler: $mobile\nKelengkapan: $completeness\nStatus: ${temporal ?: "Hari selesai"}\nSampel: ${age(it.sampledAt)}\nZona tersimpan: ${it.zoneId}" }
                MonitorHistoryRow(record?.id ?: missingRowId(date), record?.let { "internet:${it.id}" } ?: "missing:$date",
                    slot.date.format(accessibleDateFormatter), "Total", total,
                    listOf(MonitorHistoryField("Wi-Fi", wifi), MonitorHistoryField("Seluler", mobile)),
                    listOfNotNull(completeness, temporal), detail)
            }, currentState?.let { state -> when {
                !state.usageAccessGranted -> "Izin diperlukan untuk sampel baru"
                !state.monitoringEnabled -> "Pencatatan dijeda"
                else -> null
            } })
        }
        viewModel.historyError.observe(viewLifecycleOwner) { historyUi?.showError(it) }
        viewModel.uiState.observe(viewLifecycleOwner) {
            currentState = it
            render(it)
            if (activeSettingsSheet != null && settingsDialog?.isShowing != true) showSettingsSheet(activeSettingsSheet!!)
        }
        selectPanel(selectedPanel)
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshPermissionStatus()
    }

    private fun setupChart() = binding.internetUsageChart.apply {
        description.isEnabled = false
        setTouchEnabled(true)
        setDragEnabled(true)
        setScaleEnabled(false)
        axisRight.isEnabled = false
        axisLeft.axisMinimum = 0f
        xAxis.position = XAxis.XAxisPosition.BOTTOM
        xAxis.setDrawGridLines(false)
        xAxis.granularity = 1f
        xAxis.setLabelCount(5, false)
        legend.isEnabled = true
        BuddyCharts.style(this)
        BuddyCharts.separateTransportStacks(this)
        setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
            override fun onValueSelected(entry: com.github.mikephil.charting.data.Entry?, highlight: Highlight?) {
                if (updatingHighlight) return
                val date = chartDays.getOrNull(entry?.x?.toInt() ?: -1) ?: return
                selectedChartDate = date.date.toString()
                binding.tvChartSelection.text = chartDayDescription(date)
            }
            override fun onNothingSelected() = Unit
        })
    }

    private fun setupHistory() {
        historyUi = MonitorHistoryUi(this, binding.historyPanel, binding.root, viewModel.retainedHistory, HistoryMetric.INTERNET)
    }

    private fun selectPanel(panel: MonitorDetailPanel) {
        val previous = selectedPanel
        if (panelRail != null && previous != panel) panelScroll[previous] = binding.root.scrollY
        selectedPanel = panel
        panelRail?.renderSelection(selectedPanel)
        binding.summaryPanel.visibility = if (panel == MonitorDetailPanel.SUMMARY) View.VISIBLE else View.GONE
        binding.historyPanel.root.visibility = if (panel == MonitorDetailPanel.HISTORY) View.VISIBLE else View.GONE
        binding.settingsPanel.visibility = if (panel == MonitorDetailPanel.SETTINGS) View.VISIBLE else View.GONE
        if (panel == MonitorDetailPanel.SETTINGS) currentState?.let(::renderSettings)
        if (previous != panel || !binding.root.isLaidOut) binding.root.post {
            if (selectedPanel == panel) _binding?.root?.scrollTo(0, panelScroll[panel] ?: 0)
        }
    }

    private fun render(state: InternetUsageUiState) {
        historyUi?.setNotice(when {
            !state.usageAccessGranted -> "Izin diperlukan untuk sampel baru"
            !state.monitoringEnabled -> "Pencatatan dijeda"
            else -> null
        })
        updateUsageRange(state)
        val today = state.todayUsage
        binding.tvTodayDate.text = LocalDate.now(ZoneId.systemDefault()).format(accessibleDateFormatter)
        binding.tvTodayTotal.text = today.totalBytes?.let(::formatGb) ?: "Belum tersedia"
        binding.tvTodayWifi.text = "Wi-Fi\n${today.wifiBytes?.let(::formatGb) ?: "Belum tersedia"}"
        binding.tvTodayMobile.text = "Seluler\n${today.mobileBytes?.let(::formatGb) ?: "Belum tersedia"}"
        renderLimit(binding.cardToday, R.color.buddy_sky, binding.tvTodayLimit,
            binding.todayReachedRow, binding.progressToday, binding.btnTodaySetLimit,
            state.limits.dailyTargetBytes, today.totalBytes, state.limits.dailyReached, "Batas harian")
        binding.btnUsageAccess.visibility = if (state.usageAccessGranted) View.GONE else View.VISIBLE
        binding.tvCycleTotal.text = if (state.cycleComplete) state.cycleUsage.totalBytes?.let(::formatGb) ?: "Belum tersedia" else "Belum lengkap"
        binding.tvCycleRange.text = "${state.cycle.startDate.format(accessibleDateFormatter)} – ${state.cycle.endDate.minusDays(1).format(accessibleDateFormatter)}"
        binding.tvCycleWifi.text = "Wi-Fi\n${state.cycleUsage.wifiBytes?.let(::formatGb) ?: "Belum lengkap"}"
        binding.tvCycleMobile.text = "Seluler\n${state.cycleUsage.mobileBytes?.let(::formatGb) ?: "Belum lengkap"}"
        renderLimit(binding.cardMonthly, R.color.buddy_lilac, binding.tvCycleLimit,
            binding.monthlyReachedRow, binding.progressMonthly, binding.btnMonthlySetLimit,
            state.settings.monthlyBudgetBytes, state.cycleUsage.totalBytes, state.limits.cycleReached, "Batas bulanan")
        renderChart(state)
        renderSettings(state)
    }

    private fun renderLimit(card: MaterialCardView, normalColor: Int, label: android.widget.TextView,
        reachedRow: View, progress: LinearProgressIndicator, setLimit: View,
        limit: Long?, total: Long?, reached: Boolean, title: String) {
        card.setCardBackgroundColor(ContextCompat.getColor(requireContext(), normalColor))
        card.strokeWidth = if (reached) (resources.displayMetrics.density + 0.5f).toInt() else 0
        card.strokeColor = ContextCompat.getColor(requireContext(), R.color.buddy_warning)
        progress.setIndicatorColor(ContextCompat.getColor(requireContext(),
            if (reached) R.color.buddy_warning else R.color.buddy_chart_blue))
        label.text = if (limit == null) "$title · Belum diatur" else "$title ${formatGb(limit)}"
        reachedRow.visibility = if (reached) View.VISIBLE else View.GONE
        setLimit.visibility = if (limit == null) View.VISIBLE else View.GONE
        progress.visibility = if (limit != null && limit > 0 && total != null) View.VISIBLE else View.GONE
        if (limit != null && limit > 0 && total != null) {
            progress.setProgressCompat((total.toDouble() / limit * 100).coerceIn(0.0, 100.0).toInt(), false)
            progress.contentDescription = "$title: ${formatGb(total)} dari ${formatGb(limit)}"
        }
    }

    private fun reflowTransport(row: LinearLayout) {
        val stacked = resources.configuration.fontScale >= 1.5f || row.width < (300 * resources.displayMetrics.density)
        val orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        if (row.orientation == orientation) return
        row.orientation = orientation
        for (index in 0 until row.childCount) {
            val child = row.getChildAt(index)
            val params = child.layoutParams as LinearLayout.LayoutParams
            params.width = if (stacked) LinearLayout.LayoutParams.MATCH_PARENT else 0
            params.weight = if (stacked) 0f else 1f
            params.topMargin = if (stacked && index > 0) (8 * resources.displayMetrics.density).toInt() else 0
            child.layoutParams = params
        }
    }

    private fun recordingInfo(): String {
        val state = currentState ?: return ""
        return "${recordingStatus(state)}. Sampel terakhir: ${state.latestSampledAt?.let(::age) ?: "belum ada"}. " +
            "Sumber: InternetUsageDaily dari pencatatan latar belakang Android. " +
            "Buka Pencatatan riwayat untuk mengatur jadwal; halaman ini tidak memaksa sampel baru."
    }

    private fun renderSettings(state: InternetUsageUiState) {
        binding.tvSettingsBudget.text = state.settings.monthlyBudgetBytes?.let { "Batas bulanan: ${formatGb(it)}" }
            ?: "Batas bulanan belum diatur"
        binding.tvSettingsDaily.text = state.limits.dailyTargetBytes?.let {
            "Batas harian: ${formatGb(it)}\nDihitung dari batas bulanan ÷ ${state.settings.targetDays} hari pemakaian."
        } ?: "Batas harian dihitung setelah batas bulanan diatur."
        binding.tvSettingsDates.text = "${state.cycle.startDate.format(dateFormatter)}–${state.cycle.endDate.minusDays(1).format(dateFormatter)} · ${state.settings.targetDays} hari pemakaian"
    }

    private fun renderChart(state: InternetUsageUiState) {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val byStart = state.days.associateBy { it.dayStartMillis }
        val rangeStart = selectedUsageStart ?: state.cycle.startDate
        val rangeEnd = (selectedUsageEnd ?: today).coerceAtMost(today)
        val previousWindow = chartDays.firstOrNull()?.date to chartDays.lastOrNull()?.date
        val previousViewport = binding.internetUsageChart.lowestVisibleX
        val displayDays = generateSequence(rangeStart) { date -> date.plusDays(1).takeIf { it <= rangeEnd } }
            .map { date -> ChartDay(date, byStart[InternetUsagePolicy.dayWindow(date, zone).startMillis]) }
            .toList()
        chartDays = displayDays
        val complete = displayDays.mapIndexedNotNull { index, slot ->
            slot.record?.takeIf { it.wifiBytes != null && it.mobileBytes != null }?.let { index to it }
        }
        binding.internetUsageChart.axisLeft.removeAllLimitLines()
        binding.internetUsageChart.xAxis.valueFormatter = object : com.github.mikephil.charting.formatter.ValueFormatter() {
            override fun getFormattedValue(value: Float): String = displayDays.getOrNull(value.toInt())?.date?.format(dateFormatter).orEmpty()
        }
        binding.internetUsageChart.xAxis.axisMinimum = -0.5f
        binding.internetUsageChart.xAxis.axisMaximum = displayDays.size.toFloat() - 0.5f
        binding.internetUsageChart.axisLeft.valueFormatter = object : com.github.mikephil.charting.formatter.ValueFormatter() {
            override fun getFormattedValue(value: Float): String = formatGigabyteValue(value.toDouble())
        }
        if (complete.isEmpty()) {
            binding.internetUsageChart.clear()
            binding.internetUsageChart.setNoDataText("Belum ada hari dengan Wi-Fi dan seluler lengkap.")
        } else {
            val entries = complete.map { (index, row) -> BarEntry(index.toFloat(), floatArrayOf(
                BuddyCharts.decimalGigabytes(row.wifiBytes!!).toFloat(),
                BuddyCharts.decimalGigabytes(row.mobileBytes!!).toFloat()
            )) }
            val dataSet = BarDataSet(entries, "Pemakaian lengkap").apply {
                colors = listOf(ContextCompat.getColor(requireContext(), R.color.buddy_chart_blue), ContextCompat.getColor(requireContext(), R.color.buddy_chart_lilac))
                stackLabels = arrayOf("Wi-Fi", "Seluler")
                setDrawValues(false)
                barBorderWidth = 0f
            }
            binding.internetUsageChart.data = BarData(dataSet).apply { barWidth = 0.7f }
        }
        val preservedIndex = selectedChartDate?.let { date -> displayDays.indexOfFirst { it.date.toString() == date } } ?: -1
        val selectedIndex = if (preservedIndex >= 0) preservedIndex else complete.lastOrNull()?.first ?: -1
        val selectedSlot = displayDays.getOrNull(selectedIndex)
        selectedChartDate = selectedSlot?.date?.toString()
        binding.tvChartSelection.text = selectedSlot?.let(::chartDayDescription)
            ?: "Ketuk batang untuk melihat hari dengan sampel lengkap."
        binding.internetUsageChart.setVisibleXRangeMaximum(7f)
        if (displayDays.size > 7) {
            val initial = (selectedIndex - 3).coerceIn(0, displayDays.size - 7).toFloat()
            val origin = if (previousWindow == (rangeStart to rangeEnd)) previousViewport else initial
            binding.internetUsageChart.moveViewToX(origin.coerceIn(-0.5f, (displayDays.size - 7).toFloat()))
        }
        if (selectedIndex >= 0 && complete.any { it.first == selectedIndex }) {
            updatingHighlight = true
            binding.internetUsageChart.highlightValue(selectedIndex.toFloat(), 0, false)
            updatingHighlight = false
        }
        binding.internetUsageChart.invalidate()
    }

    private fun updateUsageRange(state: InternetUsageUiState) {
        val today = LocalDate.now(ZoneId.systemDefault())
        val start = (selectedUsageStart ?: state.cycle.startDate).coerceIn(state.cycle.startDate, today)
        val end = (selectedUsageEnd ?: today).coerceIn(start, today)
        selectedUsageStart = start
        selectedUsageEnd = end
        binding.btnUsageRangeStart.text = "Dari ${start.format(dateFormatter)}"
        binding.btnUsageRangeEnd.text = "Sampai ${end.format(dateFormatter)}"
    }

    private fun chooseUsageDate(state: InternetUsageUiState, chooseStart: Boolean) {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val start = selectedUsageStart ?: state.cycle.startDate
        val end = selectedUsageEnd ?: today
        val initial = if (chooseStart) start else end
        DatePickerDialog(requireContext(), { _, year, month, day ->
            val date = LocalDate.of(year, month + 1, day)
            if (chooseStart) {
                selectedUsageStart = date
                if ((selectedUsageEnd ?: today).isBefore(date)) selectedUsageEnd = date
            } else {
                selectedUsageEnd = date
                if ((selectedUsageStart ?: state.cycle.startDate).isAfter(date)) selectedUsageStart = date
            }
            updateUsageRange(state)
            renderChart(state)
        }, initial.year, initial.monthValue - 1, initial.dayOfMonth).apply {
            datePicker.minDate = (if (chooseStart) state.cycle.startDate else start).atStartOfDay(zone).toInstant().toEpochMilli()
            datePicker.maxDate = today.atStartOfDay(zone).toInstant().toEpochMilli()
        }.show()
    }

    private fun missingRowId(date: String): Long = LocalDate.parse(date).toEpochDay().let { Long.MIN_VALUE + it }

    private fun showSettingsSheet(sheet: SettingsSheet) {
        if (settingsDialog?.isShowing == true) return
        val state = currentState ?: return
        val dialog = BottomSheetDialog(requireContext())
        settingsDialog = dialog
        activeSettingsSheet = sheet
        val content = layoutInflater.inflate(
            if (sheet == SettingsSheet.TARGET) R.layout.sheet_internet_usage_settings else R.layout.sheet_internet_usage_dates,
            null
        )
        fun observeDraft(field: com.google.android.material.textfield.TextInputEditText, save: (String) -> Unit) {
            field.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { save(s?.toString().orEmpty()) }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        if (sheet == SettingsSheet.TARGET) {
            val monthly = content.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.input_monthly_budget)
            monthly.setText(budgetDraft ?: state.settings.monthlyBudgetBytes?.let(::formatGbInput).orEmpty())
            observeDraft(monthly) { budgetDraft = it }
            content.findViewById<View>(R.id.btn_save_target).setOnClickListener {
                if (!viewModel.saveMonthlyBudget(monthly.text?.toString().orEmpty())) {
                    monthly.error = "Masukkan target GB positif yang dapat disimpan."
                    return@setOnClickListener
                }
                finishSettingsSheet(dialog)
                toast("Target bulanan disimpan.")
            }
            content.findViewById<View>(R.id.btn_clear_target).apply {
                visibility = if (state.settings.monthlyBudgetBytes == null) View.GONE else View.VISIBLE
                setOnClickListener {
                    viewModel.clearMonthlyBudget()
                    finishSettingsSheet(dialog)
                    toast("Target bulanan dihapus.")
                }
            }
            content.findViewById<View>(R.id.btn_settings_cancel).setOnClickListener { finishSettingsSheet(dialog) }
        } else {
            val days = content.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.input_target_days)
            val cycleStart = content.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.input_cycle_start)
            days.setText(targetDaysDraft ?: state.settings.targetDays.toString())
            cycleStart.setText(cycleStartDraft ?: state.settings.cycleStartDay.toString())
            observeDraft(days) { targetDaysDraft = it }
            observeDraft(cycleStart) { cycleStartDraft = it }
            content.findViewById<View>(R.id.btn_save_dates).setOnClickListener {
                val targetDays = days.text?.toString()?.toIntOrNull()
                val startDay = cycleStart.text?.toString()?.toIntOrNull()
                days.error = if (targetDays !in 1..31) "Masukkan angka 1–31." else null
                cycleStart.error = if (startDay !in 1..31) "Masukkan angka 1–31." else null
                if (targetDays !in 1..31 || startDay !in 1..31) return@setOnClickListener
                if (viewModel.saveDates(targetDays!!, startDay!!)) {
                    finishSettingsSheet(dialog)
                    toast("Tanggal disimpan.")
                }
            }
            content.findViewById<View>(R.id.btn_dates_cancel).setOnClickListener { finishSettingsSheet(dialog) }
        }
        dialog.setOnCancelListener {
            activeSettingsSheet = null
            clearSettingsDraft()
        }
        dialog.setOnDismissListener { if (settingsDialog === dialog) settingsDialog = null }
        dialog.setContentView(content)
        dialog.show()
    }

    private fun finishSettingsSheet(dialog: BottomSheetDialog) {
        activeSettingsSheet = null
        clearSettingsDraft()
        dialog.dismiss()
    }

    private fun clearSettingsDraft() {
        budgetDraft = null
        targetDaysDraft = null
        cycleStartDraft = null
    }

    private fun recordingStatus(state: InternetUsageUiState): String = when {
        !state.usageAccessGranted -> "Perlu izin"
        !state.monitoringEnabled -> "Pencatatan dijeda"
        state.latestSampledAt == null -> "Belum ada sampel"
        state.stale -> "Data belum diperbarui · ${age(state.latestSampledAt)}"
        state.todayUsage.totalBytes == null -> "Data belum lengkap"
        else -> "Pencatatan aktif"
    }

    private fun limitStatus(state: InternetUsageUiState): String {
        val budget = state.settings.monthlyBudgetBytes ?: return "Belum ada target bulanan."
        val daily = state.limits.dailyTargetBytes ?: return "Target harian belum tersedia."
        return when {
            state.limits.cycleReached -> "Batas siklus tercapai · target ${formatGb(budget)}"
            state.limits.dailyReached -> "Batas harian tercapai · target ${formatGb(daily)}"
            !state.cycleComplete -> "Target ${formatGb(budget)} · siklus menunggu sampel lengkap · target harian ${formatGb(daily)}"
            else -> "Target ${formatGb(budget)} · harian ${formatGb(daily)} · sisa hari ini ${formatGb((daily - (state.todayUsage.totalBytes ?: 0L)).coerceAtLeast(0L))} · sisa siklus ${formatGb((budget - (state.cycleUsage.totalBytes ?: 0L)).coerceAtLeast(0L))}"
        }
    }

    private fun chartDayDescription(slot: ChartDay): String {
        val row = slot.record
        val wifi = row?.wifiBytes?.let(::formatGb) ?: "Belum tersedia"
        val mobile = row?.mobileBytes?.let(::formatGb) ?: "Belum tersedia"
        val total = row?.let { InternetUsagePolicy.transportUsage(it.wifiBytes, it.mobileBytes).totalBytes }
            ?.let(::formatGb) ?: "Belum lengkap"
        return "${slot.date.format(accessibleDateFormatter)}\nWi-Fi $wifi · Seluler $mobile\nTotal $total"
    }

    private fun formatGb(bytes: Long): String {
        return formatGigabyteValue(BuddyCharts.decimalGigabytes(bytes))
    }
    private fun formatGigabyteValue(value: Double): String {
        val pattern = when {
            value == 0.0 -> "0 GB"
            value < 0.01 -> "%.4f GB"
            value < 0.1 -> "%.3f GB"
            value < 1.0 -> "%.2f GB"
            else -> "%.1f GB"
        }
        return if (value == 0.0) pattern else String.format(Locale.getDefault(), pattern, value)
    }
    private fun formatHistoryGb(bytes: Long): String = if (bytes == 0L) "0 GB" else formatGb(bytes)
    private fun formatGbInput(bytes: Long): String = String.format(Locale.US, "%.3f", BuddyCharts.decimalGigabytes(bytes)).trimEnd('0').trimEnd('.')
    private fun age(time: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(time))
    private fun toast(message: String) = Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_PANEL, selectedPanel.name)
        _binding?.root?.scrollY?.let { panelScroll[selectedPanel] = it }
        outState.putIntArray("panel_scroll", MonitorDetailPanel.entries.map { panelScroll[it] ?: 0 }.toIntArray())
        historyUi?.saveAnchor()
        outState.putString(KEY_RANGE_START, selectedUsageStart?.toString())
        outState.putString(KEY_RANGE_END, selectedUsageEnd?.toString())
        outState.putString(KEY_CHART_DATE, selectedChartDate)
        outState.putString(KEY_SETTINGS_SHEET, activeSettingsSheet?.name)
        outState.putString(KEY_BUDGET_DRAFT, budgetDraft)
        outState.putString(KEY_TARGET_DAYS_DRAFT, targetDaysDraft)
        outState.putString(KEY_CYCLE_START_DRAFT, cycleStartDraft)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        panelScroll[selectedPanel] = binding.root.scrollY
        panelRail = null
        settingsDialog?.dismiss()
        settingsDialog = null
        historyUi?.saveAnchor()
        historyUi = null
        currentState = null
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        const val KEY_PANEL = "internet_usage_panel"
        const val KEY_RANGE_START = "internet_usage_range_start"
        const val KEY_RANGE_END = "internet_usage_range_end"
        const val KEY_CHART_DATE = "internet_usage_chart_date"
        const val KEY_SETTINGS_SHEET = "internet_usage_settings_sheet"
        const val KEY_BUDGET_DRAFT = "internet_usage_budget_draft"
        const val KEY_TARGET_DAYS_DRAFT = "internet_usage_target_days_draft"
        const val KEY_CYCLE_START_DRAFT = "internet_usage_cycle_start_draft"
        val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.forLanguageTag("id-ID"))
        val accessibleDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("id-ID"))
    }
}

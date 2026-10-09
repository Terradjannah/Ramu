package com.assistant.adi.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.text.InputType
import android.widget.EditText
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import android.widget.LinearLayout
import com.assistant.adi.R
import com.assistant.adi.ui.buddy.withActionIcons
import com.assistant.adi.data.BatteryLog
import com.assistant.adi.data.HistoryMetric
import com.assistant.adi.data.ReadingQuality
import com.assistant.adi.data.percentageReading
import com.assistant.adi.data.temperatureReading
import com.assistant.adi.data.PrefsManager
import com.assistant.adi.databinding.FragmentBatteryBinding
import com.assistant.adi.ui.buddy.MonitorDetailPanel
import com.assistant.adi.ui.buddy.MonitorDetailUi
import com.assistant.adi.ui.buddy.MonitorPanelRail
import com.assistant.adi.ui.buddy.MonitorHistoryField
import com.assistant.adi.ui.buddy.MonitorHistoryRow
import com.assistant.adi.ui.buddy.MonitorHistoryUi
import com.assistant.adi.ui.buddy.MonitorChartPolicy
import com.assistant.adi.ui.buddy.BuddyCharts
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.listener.OnChartValueSelectedListener
import java.text.DateFormat
import java.util.*

class BatteryFragment : Fragment() {

    private var _binding: FragmentBatteryBinding? = null
    private val binding get() = _binding!!

    private val viewModel: BatteryViewModel by viewModels()
    private val sessionAdapter = ChargeSessionAdapter()
    private var historyUi: MonitorHistoryUi? = null
    private var sessionsExpanded = false
    private var showTemperature = false
    private var selectedPanel = MonitorDetailPanel.SUMMARY
    private var panelRail: MonitorPanelRail? = null
    private val panelScroll = mutableMapOf<MonitorDetailPanel, Int>()
    private var chartPolicy: MonitorChartPolicy? = null
    private var selectedChartTime: Long? = null
    private var updatingHighlight = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBatteryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        showTemperature = savedInstanceState?.getBoolean("show_temperature")
            ?: (arguments?.getString("series") == "temperature")
        sessionsExpanded = savedInstanceState?.getBoolean("sessions_expanded") ?: false
        selectedChartTime = savedInstanceState?.getLong("chart_time")?.takeIf { it > 0 }
        selectedPanel = savedInstanceState?.getString("panel")?.let { runCatching { MonitorDetailPanel.valueOf(it) }.getOrNull() } ?: selectedPanel
        savedInstanceState?.getIntArray("panel_scroll")?.forEachIndexed { index, offset -> panelScroll[MonitorDetailPanel.entries[index]] = offset }
        val content = binding.root.getChildAt(0) as LinearLayout
        val detailUi = MonitorDetailUi(this)
        var batteryExtraDetails = "Tegangan · Perkiraan: belum tersedia\nEstimasi pemakaian: menunggu catatan yang cukup"
        panelRail = MonitorPanelRail(requireContext(), MonitorDetailPanel.entries, ::selectPanel)
        content.addView(panelRail, 0)
        selectPanel(selectedPanel)
        detailUi.installInfoAction(requireActivity(), viewLifecycleOwner, "Baterai",
            { "Persentase menunjukkan sisa daya. Suhu berasal dari sensor baterai. $batteryExtraDetails\nEstimasi adalah perkiraan berdasarkan catatan yang tersedia dan tidak menjamin hasil perangkat." },
            { "Bacaan langsung dari Android untuk kondisi saat ini; grafik memakai catatan 24 jam jika Pencatatan riwayat aktif. Metadata suhu dan tegangan pada catatan lama belum memiliki penanda validitas." })
        binding.btnBatteryReadingInfo.setOnClickListener { trigger -> detailUi.showInfoSheet(
            "Bacaan baterai langsung", "Persentase dan status pengisian adalah kondisi baterai saat ini. Suhu berasal dari sensor baterai.",
            "Bacaan langsung dari Android; angka ini dapat berbeda dari catatan grafik yang tersimpan.", trigger) }
        binding.btnBatteryChartInfo.setOnClickListener { trigger -> detailUi.showInfoSheet(
            "Perubahan baterai · 24 jam", "Celah grafik ditampilkan bila jeda sampel lebih dari dua kali interval pencatatan saat ini; ini bukan bukti kapan pencatatan aktif.",
            "Grafik memakai catatan baterai tersimpan dalam 24 jam terakhir saat Pencatatan riwayat aktif.", trigger) }
        val openHistory = com.google.android.material.button.MaterialButton(requireContext()).apply {
            text = "Pencatatan riwayat"; isAllCaps = false; minHeight = resources.displayMetrics.density.let { (48 * it).toInt() }
            setIconResource(R.drawable.ic_ms_history)
            iconTint = androidx.core.content.ContextCompat.getColorStateList(requireContext(), R.color.buddy_button_primary_text)
            setOnClickListener { (activity as? DashboardActivity)?.navigateSection("background") }
        }
        (binding.panelReminder.getChildAt(0) as LinearLayout).addView(openHistory)

        // Setup Recycler View
        binding.rvChargeSessions.layoutManager = LinearLayoutManager(requireContext())
        binding.rvChargeSessions.adapter = sessionAdapter
        historyUi = MonitorHistoryUi(this, binding.historyPanel, binding.root, viewModel.retainedHistory, HistoryMetric.BATTERY)
        fun renderSessions() {
            binding.sessionsContent.visibility = if (sessionsExpanded) View.VISIBLE else View.GONE
            binding.btnSessionsDisclosure.text = if (sessionsExpanded) "Sesi pengisian, sembunyikan" else "Sesi pengisian, tampilkan"
            binding.btnSessionsDisclosure.isSelected = sessionsExpanded
        }
        binding.btnSessionsDisclosure.setOnClickListener { sessionsExpanded = !sessionsExpanded; renderSessions() }
        renderSessions()

        setupChart()
        BuddyCharts.style(binding.batteryChart)

        // Observe Data
        viewModel.foregroundBattery.observe(viewLifecycleOwner) { reading ->
            if (reading != null) {
                binding.tvBatteryPct.text = "${reading.percentage}%"
                binding.tvBatteryStatus.text = if (reading.isCharging) "Sedang mengisi daya · bacaan langsung" else "Menggunakan baterai · bacaan langsung"
                binding.tvBatteryTemp.text = reading.temperature?.let { String.format(Locale.getDefault(), "%.1f °C", it) } ?: "Suhu belum tersedia"
                batteryExtraDetails = "Tegangan · Perkiraan: ${reading.voltage?.let { "$it mV" } ?: "belum tersedia"}\n${batteryExtraDetails.substringAfter('\n')}"
                updateBatteryReminder(reading.percentage)
            }
        }
        viewModel.batteryEstimate.observe(viewLifecycleOwner) { estimate ->
            batteryExtraDetails = "${batteryExtraDetails.substringBefore('\n')}\nEstimasi pemakaian: $estimate"
        }
        binding.btnBatteryLowReminder.setOnClickListener { showBatteryReminderDialog() }
        updateBatteryReminder(null)

        binding.toggleBatterySeries.check(if (showTemperature) R.id.btn_battery_temperature else R.id.btn_battery_power)
        binding.toggleBatterySeries.addOnButtonCheckedListener { _, id, checked ->
            if (checked) { showTemperature = id == R.id.btn_battery_temperature; viewModel.selectedRangeLogs.value?.let(::updateChartData) }
        }

        viewModel.availableHistoryRanges.observe(viewLifecycleOwner) { ranges ->
            binding.btnBatteryRange7d.visibility = if (BatteryViewModel.HistoryRange.DAYS_7 in ranges) View.VISIBLE else View.GONE
            binding.btnBatteryRange30d.visibility = if (BatteryViewModel.HistoryRange.DAYS_30 in ranges) View.VISIBLE else View.GONE
        }
        viewModel.historyRange.observe(viewLifecycleOwner) { range ->
            binding.toggleBatteryRange.check(when (range) {
                BatteryViewModel.HistoryRange.HOURS_24 -> R.id.btn_battery_range_24h
                BatteryViewModel.HistoryRange.DAYS_7 -> R.id.btn_battery_range_7d
                BatteryViewModel.HistoryRange.DAYS_30 -> R.id.btn_battery_range_30d
            })
        }
        binding.toggleBatteryRange.addOnButtonCheckedListener { _, id, checked ->
            if (checked) viewModel.selectHistoryRange(when (id) {
                R.id.btn_battery_range_7d -> BatteryViewModel.HistoryRange.DAYS_7
                R.id.btn_battery_range_30d -> BatteryViewModel.HistoryRange.DAYS_30
                else -> BatteryViewModel.HistoryRange.HOURS_24
            })
        }

        viewModel.chargeSessions.observe(viewLifecycleOwner) { sessions ->
            sessionAdapter.submitList(sessions)
            binding.tvSessionsEmpty.visibility = if (sessions.isEmpty()) View.VISIBLE else View.GONE
        }

        viewModel.selectedRangeLogs.observe(viewLifecycleOwner) { logs ->
            updateChartData(logs)
            val dateTimeFormatter = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault())
            binding.tvBatteryChartTitle.text = "Perubahan baterai · ${viewModel.historyRange.value?.label ?: "24 jam"}"
            binding.tvBatteryRangeCoverage.text = if (logs.isEmpty()) "Tidak ada catatan dalam rentang ini."
                else "Catatan ${dateTimeFormatter.format(Date(logs.first().timestamp))} – ${dateTimeFormatter.format(Date(logs.last().timestamp))}"
        }
        viewModel.retainedHistoryPage.observe(viewLifecycleOwner) { page ->
            val format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            historyUi?.render(page, page.records.map { log ->
                val temperature = log.temperatureReading()
                val temperatureText = if (temperature.quality == ReadingQuality.VALIDITY_UNKNOWN) "Validitas tidak diketahui"
                    else temperature.value?.let { String.format(Locale.getDefault(), "%.1f °C", it) } ?: "Belum tersedia"
                val temperatureStatus = when (temperature.quality) {
                    ReadingQuality.VALIDITY_UNKNOWN -> "Validitas suhu tidak diketahui"
                    ReadingQuality.LEGACY_RECORDED -> "Suhu catatan lama"
                    ReadingQuality.UNAVAILABLE -> "Suhu tidak tersedia"
                    else -> ""
                }
                val time = format.format(Date(log.timestamp))
                MonitorHistoryRow(log.id, "battery:${log.id}", time, "Daya",
                    log.percentageReading().value?.let { "$it%" } ?: "Belum tersedia",
                    listOf(MonitorHistoryField("Suhu", temperatureText), MonitorHistoryField("Status", if (log.isCharging) "Mengisi" else "Memakai daya")),
                    listOfNotNull(temperatureStatus.takeIf { it.isNotEmpty() }),
                    "Waktu: $time\nDaya: ${log.percentageReading().value?.let { "$it%" } ?: "Belum tersedia"}\nSuhu: $temperatureText${if (temperature.quality == ReadingQuality.VALIDITY_UNKNOWN) " (nilai lama ${log.temperature} °C)" else ""}\n${temperatureStatus}\nStatus tersimpan: ${if (log.isCharging) "Mengisi" else "Memakai daya"}\nTegangan: ${log.voltage.takeIf { it > 0 }?.let { "$it mV" } ?: "Belum tersedia"}"
                )
            })
        }
        viewModel.historyError.observe(viewLifecycleOwner) { historyUi?.showError(it) }
    }

    private fun selectPanel(panel: MonitorDetailPanel) {
        val previous = selectedPanel
        if (panelRail != null && previous != panel) panelScroll[previous] = binding.root.scrollY
        selectedPanel = panel
        panelRail?.renderSelection(selectedPanel)
        binding.panelPower.visibility = if (panel == MonitorDetailPanel.SUMMARY) View.VISIBLE else View.GONE
        binding.panelChart.visibility = if (panel == MonitorDetailPanel.SUMMARY) View.VISIBLE else View.GONE
        binding.panelSessions.visibility = if (panel == MonitorDetailPanel.HISTORY) View.VISIBLE else View.GONE
        binding.historyPanel.root.visibility = if (panel == MonitorDetailPanel.HISTORY) View.VISIBLE else View.GONE
        binding.panelReminder.visibility = if (panel == MonitorDetailPanel.SETTINGS) View.VISIBLE else View.GONE
        if (previous != panel || !binding.root.isLaidOut) binding.root.post {
            if (selectedPanel == panel) _binding?.root?.scrollTo(0, panelScroll[panel] ?: 0)
        }
    }

    override fun onResume() {
        super.onResume()
        historyUi?.setNotice(if (!PrefsManager(requireContext()).monitoringEnabled) "Pencatatan dijeda" else null)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("panel", selectedPanel.name)
        outState.putBoolean("show_temperature", showTemperature)
        outState.putBoolean("sessions_expanded", sessionsExpanded)
        selectedChartTime?.let { outState.putLong("chart_time", it) }
        historyUi?.saveAnchor()
        _binding?.root?.scrollY?.let { panelScroll[selectedPanel] = it }
        outState.putIntArray("panel_scroll", MonitorDetailPanel.entries.map { panelScroll[it] ?: 0 }.toIntArray())
        super.onSaveInstanceState(outState)
    }

    private fun setupChart() {
        val chart = binding.batteryChart
        chart.description.isEnabled = false
        chart.setTouchEnabled(true)
        chart.setDragEnabled(true)
        chart.setScaleEnabled(true)
        chart.setPinchZoom(true)
        chart.axisRight.isEnabled = false
        chart.legend.isEnabled = true
        chart.legend.textColor = ContextCompat.getColor(requireContext(), R.color.buddy_sub)
        chart.setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
            override fun onValueSelected(e: Entry?, h: Highlight?) {
                if (updatingHighlight || e == null) return
                selectedChartTime = viewModel.selectedRangeLogs.value?.minByOrNull { kotlin.math.abs(chartPolicy!!.x(it.timestamp) - e.x) }?.timestamp
                showChartSelection()
            }
            override fun onNothingSelected() = Unit
        })

        // Style Axes
        val xAxis = chart.xAxis
        xAxis.position = XAxis.XAxisPosition.BOTTOM
        xAxis.setDrawGridLines(false)
        xAxis.textColor = ContextCompat.getColor(requireContext(), R.color.buddy_sub)
        xAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String = chartPolicy?.label(value,
                viewModel.historyRange.value != BatteryViewModel.HistoryRange.HOURS_24).orEmpty()
        }

        val yAxis = chart.axisLeft
        yAxis.setLabelCount(5, true)
        yAxis.axisMinimum = if (showTemperature) 0f else 0f
        yAxis.axisMaximum = if (showTemperature) 60f else 100f
        yAxis.textColor = ContextCompat.getColor(requireContext(), R.color.buddy_sub)
        yAxis.setDrawGridLines(true)
        yAxis.gridColor = ContextCompat.getColor(requireContext(), R.color.buddy_divider)
    }

    private fun updateChartData(logs: List<BatteryLog>) {
        val chart = binding.batteryChart
        chartPolicy = MonitorChartPolicy(logs.map { it.timestamp }, PrefsManager(requireContext()).refreshIntervalMinutes)
        if (logs.isEmpty()) {
            chart.clear()
            chart.setNoDataText("Belum ada catatan daya tersimpan.")
            binding.tvBatteryChartSelection.text = "Pilih titik untuk rincian catatan."
            chart.invalidate()
            return
        }
        val valid: (BatteryLog) -> Boolean = if (showTemperature) { row -> row.temperatureReading().value != null && row.temperatureReading().quality != ReadingQuality.VALIDITY_UNKNOWN }
            else { row -> row.percentageReading().value != null }
        val segments = chartPolicy!!.segments(logs, { it.timestamp }, valid)
        val values = segments.flatten().map { it.timestamp to (if (showTemperature) it.temperature else it.percentage.toFloat()) }
        if (values.isEmpty()) {
            chart.clear()
            chart.setNoDataText(if (showTemperature) "Belum ada catatan suhu baterai yang valid." else "Belum ada catatan daya tersimpan.")
            binding.tvBatteryChartSelection.text = "Tidak ada titik yang dapat dipilih dalam rentang ini."
            chart.invalidate()
            return
        }
        val sets = segments.mapIndexed { index, segment -> LineDataSet(segment.map { Entry(chartPolicy!!.x(it.timestamp),
            if (showTemperature) it.temperature else it.percentage.toFloat()) }, if (index == 0) {
            if (showTemperature) "Suhu baterai (°C)" else "Daya tersisa (%)"
        } else "").apply {
            mode = LineDataSet.Mode.LINEAR
            setDrawFilled(false)
            setDrawCircles(segment.size == 1)
            lineWidth = 3f
            color = ContextCompat.getColor(requireContext(), R.color.buddy_action)
            setDrawValues(false)
        } }

        chart.data = LineData(sets)
        chart.axisLeft.axisMinimum = if (showTemperature) maxOf(0f, (values.minOf { it.second } - 5f).toInt().toFloat()) else 0f
        chart.axisLeft.axisMaximum = if (showTemperature) (values.maxOf { it.second } + 5f).toInt().toFloat().coerceAtLeast(10f) else 100f
        val selected = selectedChartTime?.takeIf { time -> values.any { it.first == time } } ?: values.last().first
        selectedChartTime = selected
        showChartSelection()
        updatingHighlight = true
        chart.highlightValue(chartPolicy!!.x(selected), segments.indexOfFirst { segment -> segment.any { it.timestamp == selected } }, false)
        updatingHighlight = false
        chart.invalidate()
    }

    private fun showChartSelection() {
        val time = selectedChartTime ?: return
        val row = viewModel.selectedRangeLogs.value?.firstOrNull { it.timestamp == time } ?: return
        val value = if (showTemperature) row.temperatureReading().value?.let { String.format(Locale.getDefault(), "%.1f °C", it) }
            else row.percentageReading().value?.let { "$it%" }
        binding.tvBatteryChartSelection.text = "${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(time))} · ${value ?: "Belum tersedia"} · catatan baterai tersimpan"
    }

    private fun updateBatteryReminder(percentage: Int?) {
        val threshold = PrefsManager(requireContext()).batteryLowThreshold
        binding.tvBatteryLowReminder.text = when {
            percentage == null -> "Pengingat saat baterai mencapai $threshold% atau lebih rendah."
            percentage <= threshold -> "Daya saat ini $percentage%. Pengingat aktif pada $threshold% atau lebih rendah."
            else -> "Pengingat saat baterai mencapai $threshold% atau lebih rendah."
        }
    }

    private fun showBatteryReminderDialog() {
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(PrefsManager(requireContext()).batteryLowThreshold.toString())
            selectAll()
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Batas pengingat baterai")
            .setMessage("Masukkan 1 sampai 99 persen.")
            .setView(input)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Simpan", null)
            .show().withActionIcons(R.drawable.ic_ms_save, R.drawable.ic_ms_close)
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = input.text.toString().toIntOrNull()
            if (value == null || value !in 1..99) input.error = "Masukkan 1 sampai 99 persen."
            else {
                PrefsManager(requireContext()).batteryLowThreshold = value
                updateBatteryReminder(viewModel.foregroundBattery.value?.percentage)
                dialog.dismiss()
            }
        }
    }

    override fun onStart() { super.onStart(); viewModel.startForegroundReads() }

    override fun onStop() { viewModel.stopForegroundReads(); super.onStop() }

    override fun onDestroyView() {
        historyUi?.saveAnchor()
        historyUi = null
        panelScroll[selectedPanel] = binding.root.scrollY
        panelRail = null
        super.onDestroyView()
        _binding = null
    }
}

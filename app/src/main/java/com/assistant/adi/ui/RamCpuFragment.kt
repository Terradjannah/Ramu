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
import com.assistant.adi.data.RamLog
import com.assistant.adi.data.HistoryMetric
import com.assistant.adi.data.usedPercentageReading
import com.assistant.adi.data.PrefsManager
import com.assistant.adi.databinding.FragmentRamCpuBinding
import com.assistant.adi.ui.buddy.MonitorHistoryField
import com.assistant.adi.ui.buddy.MonitorHistoryRow
import com.assistant.adi.ui.buddy.MonitorHistoryUi
import com.assistant.adi.ui.buddy.MonitorDetailPanel
import com.assistant.adi.ui.buddy.MonitorDetailUi
import com.assistant.adi.ui.buddy.MonitorPanelRail
import com.assistant.adi.ui.buddy.MonitorChartPolicy
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.listener.OnChartValueSelectedListener
import java.util.*

class RamCpuFragment : Fragment() {

    private var _binding: FragmentRamCpuBinding? = null
    private val binding get() = _binding!!

    private val viewModel: RamCpuViewModel by viewModels()
    private var historyUi: MonitorHistoryUi? = null
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
        _binding = FragmentRamCpuBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        selectedPanel = savedInstanceState?.getString("panel")?.let { runCatching { MonitorDetailPanel.valueOf(it) }.getOrNull() } ?: selectedPanel
        selectedChartTime = savedInstanceState?.getLong("chart_time")?.takeIf { it > 0 }
        savedInstanceState?.getIntArray("panel_scroll")?.forEachIndexed { index, offset -> panelScroll[MonitorDetailPanel.entries[index]] = offset }
        val content = binding.root.getChildAt(0) as LinearLayout
        if (resources.configuration.screenWidthDp < 360 || resources.configuration.fontScale > 1.3f) {
            binding.ramFacts.orientation = LinearLayout.VERTICAL
            for (index in 0 until binding.ramFacts.childCount) {
                val child = binding.ramFacts.getChildAt(index)
                child.layoutParams = (child.layoutParams as LinearLayout.LayoutParams).apply { width = -1; weight = 0f }
            }
        }
        val detailUi = MonitorDetailUi(this)
        var cpuInfo = "Tidak tersedia pada perangkat ini"
        var temperatureInfo = "Tidak tersedia pada perangkat ini"
        var systemInfo = "Menunggu status Android"
        panelRail = MonitorPanelRail(requireContext(), MonitorDetailPanel.entries, ::selectPanel)
        content.addView(panelRail, 0)
        selectPanel(selectedPanel)
        detailUi.installInfoAction(requireActivity(), viewLifecycleOwner, "RAM",
            { "$cpuInfo\n$temperatureInfo\n$systemInfo" },
            { "CPU dari pembaca sistem /proc/stat dan sensor thermal yang dapat diakses aplikasi. Waktu layar aktif dan mode hemat daya dibaca dari Android. Nilai dapat tidak tersedia pada perangkat tertentu." })
        binding.btnRamSummaryInfo.setOnClickListener { trigger -> detailUi.showInfoSheet(
            "Bacaan RAM", "RAM yang terpakai juga membantu menyimpan cache aplikasi. Angka dipakai, tersedia, dan total menunjukkan kondisi saat ini.",
            "Bacaan langsung dari Android; grafik memakai catatan RAM yang tersimpan.", trigger) }
        binding.btnRamChartInfo.setOnClickListener { trigger -> detailUi.showInfoSheet(
            "Pemakaian RAM · 24 jam", "Celah grafik ditampilkan bila jeda sampel lebih dari dua kali interval pencatatan saat ini; ini bukan bukti kapan pencatatan aktif.",
            "Grafik memakai catatan RAM tersimpan dalam 24 jam terakhir saat Pencatatan riwayat aktif.", trigger) }
        historyUi = MonitorHistoryUi(this, binding.historyPanel, binding.root, viewModel.retainedHistory, HistoryMetric.RAM)

        setupChart()
        com.assistant.adi.ui.buddy.BuddyCharts.style(binding.ramChart)

        // Observe Live RAM Stats
        viewModel.ramPercent.observe(viewLifecycleOwner) { pct ->
            val total = viewModel.ramTotalGb.value
            if (total == null || total <= 0) {
                binding.progressRam.isIndeterminate = true
                binding.tvRamPercent.text = "Membaca RAM…"
            } else {
                binding.progressRam.isIndeterminate = false
                binding.progressRam.progress = pct
                binding.tvRamPercent.text = "$pct%"
            }
            updateRamThresholdStatus()
        }

        fun renderRamFacts() {
            val used = viewModel.ramUsedGb.value
            val total = viewModel.ramTotalGb.value
            binding.tvRamUsed.text = if (used == null || total == null || total <= 0) "Belum tersedia"
                else String.format(Locale.getDefault(), "%.2f GB", used)
            binding.tvRamAvailable.text = if (used == null || total == null || total <= 0) "Belum tersedia"
                else String.format(Locale.getDefault(), "%.2f GB", (total - used).coerceAtLeast(0.0))
            binding.tvRamTotal.text = if (total == null || total <= 0) "Belum tersedia"
                else String.format(Locale.getDefault(), "%.2f GB", total)
            updateRamThresholdStatus()
        }
        viewModel.ramUsedGb.observe(viewLifecycleOwner) { renderRamFacts() }
        viewModel.ramTotalGb.observe(viewLifecycleOwner) { renderRamFacts() }
        binding.btnRamThreshold.setOnClickListener { showRamThresholdDialog() }
        updateRamThresholdStatus()

        // Observe Live CPU Stats
        viewModel.cpuUsage.observe(viewLifecycleOwner) { load ->
            if (load < 0) {
                cpuInfo = "CPU: Tidak tersedia pada perangkat ini"
                binding.progressCpu.visibility = View.GONE
                binding.tvCpuUsage.text = "Beban CPU tidak terbaca pada perangkat ini"
            } else {
                cpuInfo = "CPU: $load%"
                binding.progressCpu.visibility = View.VISIBLE
                binding.progressCpu.progress = load
                binding.tvCpuUsage.text = "Beban CPU: $load%"
            }
        }

        viewModel.cpuTemp.observe(viewLifecycleOwner) { temp ->
            temperatureInfo = if (temp.isFinite() && temp > 0) String.format(Locale.getDefault(), "Suhu CPU: %.1f °C", temp) else "Suhu CPU: Tidak tersedia pada perangkat ini"
            binding.tvCpuTemp.text = if (temp.isFinite() && temp > 0) String.format(Locale.getDefault(), "Suhu sensor: %.1f °C", temp) else "Sensor suhu CPU tidak tersedia"
        }

        // Wake lock status
        viewModel.wakelockStatus.observe(viewLifecycleOwner) { status ->
            systemInfo = status
            binding.tvWakelocks.text = status
        }

        // Historical Graph
        viewModel.ramLogs24Hours.observe(viewLifecycleOwner) { logs ->
            updateChartData(logs)
        }
        viewModel.retainedHistoryPage.observe(viewLifecycleOwner) { page ->
            val format = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
            historyUi?.render(page, page.records.map { log ->
                fun gb(value: Long) = if (log.totalRam > 0 && value >= 0) String.format(Locale.getDefault(), "%.2f GB", value / 1024.0) else "Belum tersedia"
                val used = gb(log.usedRam)
                val available = gb(log.availableRam)
                val total = gb(log.totalRam)
                val percent = log.usedPercentageReading().value?.let { String.format(Locale.getDefault(), "%.1f%%", it) } ?: "Belum tersedia"
                val time = format.format(Date(log.timestamp))
                MonitorHistoryRow(log.id, "ram:${log.id}", time, "Dipakai", used,
                    listOf(MonitorHistoryField("Tersedia", available), MonitorHistoryField("Total", total), MonitorHistoryField("Persentase dipakai", percent)))
            })
        }
        viewModel.historyError.observe(viewLifecycleOwner) { historyUi?.showError(it) }
    }

    private fun selectPanel(panel: MonitorDetailPanel) {
        val previous = selectedPanel
        if (panelRail != null && previous != panel) panelScroll[previous] = binding.root.scrollY
        selectedPanel = panel
        panelRail?.renderSelection(selectedPanel)
        binding.panelRamSummary.visibility = if (panel == MonitorDetailPanel.SUMMARY) View.VISIBLE else View.GONE
        binding.panelRamChart.visibility = if (panel == MonitorDetailPanel.SUMMARY) View.VISIBLE else View.GONE
        binding.historyPanel.root.visibility = if (panel == MonitorDetailPanel.HISTORY) View.VISIBLE else View.GONE
        binding.panelRamSettings.visibility = if (panel == MonitorDetailPanel.SETTINGS) View.VISIBLE else View.GONE
        if (previous != panel || !binding.root.isLaidOut) binding.root.post {
            if (selectedPanel == panel) _binding?.root?.scrollTo(0, panelScroll[panel] ?: 0)
        }
    }

    override fun onResume() {
        super.onResume()
        historyUi?.setNotice(if (!PrefsManager(requireContext()).monitoringEnabled) "Pencatatan dijeda" else null)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        historyUi?.saveAnchor()
        outState.putString("panel", selectedPanel.name)
        selectedChartTime?.let { outState.putLong("chart_time", it) }
        _binding?.root?.scrollY?.let { panelScroll[selectedPanel] = it }
        outState.putIntArray("panel_scroll", MonitorDetailPanel.entries.map { panelScroll[it] ?: 0 }.toIntArray())
        super.onSaveInstanceState(outState)
    }

    private fun setupChart() {
        val chart = binding.ramChart
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
                selectedChartTime = viewModel.ramLogs24Hours.value?.minByOrNull { kotlin.math.abs(chartPolicy!!.x(it.timestamp) - e.x) }?.timestamp
                showChartSelection()
            }
            override fun onNothingSelected() = Unit
        })

        val xAxis = chart.xAxis
        xAxis.position = XAxis.XAxisPosition.BOTTOM
        xAxis.setDrawGridLines(false)
        xAxis.textColor = ContextCompat.getColor(requireContext(), R.color.buddy_sub)
        xAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String = chartPolicy?.label(value).orEmpty()
        }

        val yAxis = chart.axisLeft
        yAxis.textColor = ContextCompat.getColor(requireContext(), R.color.buddy_sub)
        yAxis.setDrawGridLines(true)
        yAxis.gridColor = ContextCompat.getColor(requireContext(), R.color.buddy_divider)
        yAxis.setLabelCount(5, false)
    }

    private fun updateChartData(logs: List<RamLog>) {
        chartPolicy = MonitorChartPolicy(logs.map { it.timestamp }, PrefsManager(requireContext()).refreshIntervalMinutes)
        if (logs.isEmpty()) {
            binding.ramChart.clear()
            binding.ramChart.setNoDataText("Belum ada catatan RAM tersimpan.")
            binding.tvRamChartSelection.text = "Pilih titik untuk rincian catatan."
            binding.ramChart.invalidate()
            return
        }
        val segments = chartPolicy!!.segments(logs, { it.timestamp }) { it.totalRam > 0 && it.usedRam >= 0 && it.availableRam >= 0 }
        if (segments.isEmpty()) {
            binding.ramChart.clear(); binding.tvRamChartSelection.text = "Tidak ada bacaan RAM yang tersedia."
            binding.ramChart.invalidate(); return
        }
        val usedSets = segments.mapIndexed { index, segment -> LineDataSet(segment.map { Entry(chartPolicy!!.x(it.timestamp), it.usedRam / 1024f) },
            if (index == 0) "Dipakai (GB)" else "").apply {
            mode = LineDataSet.Mode.LINEAR
            setDrawCircles(segment.size == 1)
            lineWidth = 3f
            color = ContextCompat.getColor(requireContext(), R.color.buddy_chart_lilac)
            setDrawValues(false)
        } }

        val availSets = segments.mapIndexed { index, segment -> LineDataSet(segment.map { Entry(chartPolicy!!.x(it.timestamp), it.availableRam / 1024f) },
            if (index == 0) "Tersedia (GB)" else "").apply {
            mode = LineDataSet.Mode.LINEAR
            setDrawCircles(segment.size == 1)
            lineWidth = 2f
            color = ContextCompat.getColor(requireContext(), R.color.buddy_action)
            enableDashedLine(8f, 5f, 0f)
            setDrawValues(false)
        } }

        binding.ramChart.data = LineData(usedSets + availSets)
        val selected = selectedChartTime?.takeIf { time -> segments.any { segment -> segment.any { it.timestamp == time } } }
            ?: segments.last().last().timestamp
        selectedChartTime = selected
        showChartSelection()
        updatingHighlight = true
        binding.ramChart.highlightValue(chartPolicy!!.x(selected), segments.indexOfFirst { segment -> segment.any { it.timestamp == selected } }, false)
        updatingHighlight = false
        binding.ramChart.invalidate()
    }

    private fun showChartSelection() {
        val time = selectedChartTime ?: return
        val row = viewModel.ramLogs24Hours.value?.firstOrNull { it.timestamp == time } ?: return
        binding.tvRamChartSelection.text = "${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(Date(time))} · " +
            String.format(Locale.getDefault(), "dipakai %.2f GB · tersedia %.2f GB · catatan RAM tersimpan", row.usedRam / 1024.0, row.availableRam / 1024.0)
    }

    private fun updateRamThresholdStatus() {
        val total = viewModel.ramTotalGb.value
        val used = viewModel.ramUsedGb.value
        val threshold = PrefsManager(requireContext()).ramAlertThresholdMb
        if (total == null || used == null || total <= 0.0 || used < 0.0) {
            binding.tvRamThresholdStatus.text = "RAM belum tersedia. Batas lokal: $threshold MB."
            return
        }
        val availableMb = ((total - used) * 1024).toInt().coerceAtLeast(0)
        binding.tvRamThresholdStatus.text = if (availableMb < threshold) {
            "RAM tersedia $availableMb MB, di bawah batas lokal $threshold MB."
        } else {
            "RAM tersedia $availableMb MB. Batas lokal $threshold MB."
        }
    }

    private fun showRamThresholdDialog() {
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(PrefsManager(requireContext()).ramAlertThresholdMb.toString())
            selectAll()
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Batas RAM")
            .setMessage("Masukkan 128 sampai 32768 MB. Batas ini hanya ditampilkan di halaman RAM.")
            .setView(input)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Simpan", null)
            .show().withActionIcons(R.drawable.ic_ms_save, R.drawable.ic_ms_close)
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = input.text.toString().toIntOrNull()
            if (value == null || value !in 128..32768) input.error = "Masukkan 128 sampai 32768 MB."
            else {
                PrefsManager(requireContext()).ramAlertThresholdMb = value
                updateRamThresholdStatus()
                dialog.dismiss()
            }
        }
    }


    override fun onStart() { super.onStart(); viewModel.start() }
    override fun onStop() { viewModel.stop(); super.onStop() }

    override fun onDestroyView() {
        historyUi?.saveAnchor()
        historyUi = null
        panelScroll[selectedPanel] = binding.root.scrollY
        panelRail = null
        super.onDestroyView()
        _binding = null
    }
}

package com.assistant.adi.ui

import android.app.DatePickerDialog
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.EditText
import android.text.TextWatcher
import android.text.Editable
import android.content.res.Configuration
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.assistant.adi.R
import com.assistant.adi.data.HourlyUsagePattern
import com.assistant.adi.data.ScreenTimeHourly
import com.assistant.adi.data.ScreenTimeLog
import com.assistant.adi.data.HistoryMetric
import com.assistant.adi.ui.buddy.MonitorDetailPanel
import com.assistant.adi.ui.buddy.MonitorDetailUi
import com.assistant.adi.ui.buddy.MonitorPanelRail
import com.assistant.adi.ui.buddy.MonitorHistoryUi
import com.assistant.adi.ui.buddy.HistorySortSheet
import com.assistant.adi.ui.buddy.MonitorHistoryRow
import com.assistant.adi.ui.buddy.ScreenTimeDaySlot
import com.assistant.adi.ui.buddy.ScreenTimeHeatmapPolicy
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.assistant.adi.databinding.FragmentScreenTimeBinding
import com.assistant.adi.ui.buddy.screenDuration
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.listener.OnChartValueSelectedListener
import com.github.mikephil.charting.formatter.ValueFormatter
import java.text.SimpleDateFormat
import java.util.*
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

class ScreenTimeFragment : Fragment() {

    private var _binding: FragmentScreenTimeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ScreenTimeViewModel by viewModels()
    private lateinit var appAdapter: AppLimitAdapter
    private var selectedTrendDate: String? = null
    private var trendDates: List<String> = emptyList()
    private var chartSlots: List<ScreenTimeDaySlot> = emptyList()
    private var selectedPanel = MonitorDetailPanel.SUMMARY
    private var panelRail: MonitorPanelRail? = null
    private var historyUi: MonitorHistoryUi? = null
    private val todayAppAdapter = SavedScreenTimeAppAdapter(showPackageName = false)
    private var todayAppRows: List<SavedScreenTimeApp> = emptyList()
    private var currentAvailability = ScreenTimeViewModel.Availability.LOADING
    private var savedDayDialog: BottomSheetDialog? = null
    private var savedDayDate: String? = null
    private var savedDaySearch = ""
    private var savedDayNameSort = false
    private var savedDayDescending = true
    private var savedAppRows: List<SavedScreenTimeApp> = emptyList()
    private var savedDayAdapter: SavedScreenTimeAppAdapter? = null
    private var savedDayCount: TextView? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentScreenTimeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        selectedTrendDate = savedInstanceState?.getString("selected_trend_date") ?: selectedTrendDate
        selectedPanel = savedInstanceState?.getString("screen_panel")?.let { runCatching { MonitorDetailPanel.valueOf(it) }.getOrNull() }
            ?: MonitorDetailPanel.SUMMARY
        savedDayDate = savedInstanceState?.getString("saved_day_date")
        savedDaySearch = savedInstanceState?.getString("saved_day_search").orEmpty()
        savedDayNameSort = savedInstanceState?.getBoolean("saved_day_name_sort") ?: false
        savedDayDescending = savedInstanceState?.getBoolean("saved_day_descending") ?: !savedDayNameSort
        val content = binding.root.getChildAt(0) as LinearLayout
        panelRail = MonitorPanelRail(requireContext(), MonitorDetailPanel.entries, ::selectPanel,
            mapOf(MonitorDetailPanel.SETTINGS to "Batas"))
        content.addView(panelRail, 0)
        selectPanel(selectedPanel)
        MonitorDetailUi(this).installInfoAction(requireActivity(), viewLifecycleOwner, "Screen Time",
            "Bacaan langsung adalah durasi layar aktif dan terbuka dari Android. Grafik memakai total aplikasi yang tersimpan per tanggal. Pola per jam menunjukkan cakupan catatan sampler; ketiganya dapat berbeda.",
            "Bacaan langsung dari Android; total aplikasi dan pola per jam dari catatan lokal perangkat.")
        historyUi = MonitorHistoryUi(this, binding.historyPanel, binding.root, viewModel.retainedHistory,
            HistoryMetric.SCREEN_TIME, onRowSelected = { row, source -> openSavedDay(row.key.removePrefix("screen:"), source) })
        binding.btnScreenTodayInfo.setOnClickListener { trigger -> MonitorDetailUi(this).showInfoSheet(
            getString(R.string.screen_today_title), getString(R.string.screen_today_explanation), trigger = trigger) }
        binding.btnChartInfo.setOnClickListener { trigger -> MonitorDetailUi(this).showInfoSheet(
            getString(R.string.screen_chart_title), getString(R.string.screen_chart_explanation), trigger = trigger) }
        binding.btnChartBars.setOnClickListener { viewModel.setChartMode(ScreenTimeViewModel.ChartMode.BARS) }
        binding.btnChartHeatmap.setOnClickListener { viewModel.setChartMode(ScreenTimeViewModel.ChartMode.HEATMAP) }
        binding.btnHeatmapRange.setOnClickListener { showHeatmapRangeMenu() }
        binding.rvTodayApps.layoutManager = LinearLayoutManager(requireContext())
        binding.rvTodayApps.adapter = todayAppAdapter
        binding.btnTodayAppsExpand.setOnClickListener { viewModel.setAppsExpanded(viewModel.appsExpanded.value != true) }
        viewModel.chartMode.observe(viewLifecycleOwner, ::renderChartMode)
        viewModel.heatmapRange.observe(viewLifecycleOwner) { range ->
            binding.tvHeatmapRange.text = "${formatDisplayDate(range.start.toString())}–${formatFullDate(range.end.toString())}"
            binding.tvHeatmapRange.contentDescription = "Rentang ${formatFullDate(range.start.toString())} sampai ${formatFullDate(range.end.toString())}"
            binding.btnHeatmapRange.text = if (range.preset) "30 hari terakhir" else "Pilih rentang tanggal"
            binding.gridHeatmap.removeAllViews()
            binding.tvHeatmapRange.visibility = View.VISIBLE
        }
        viewModel.appsExpanded.observe(viewLifecycleOwner) { renderTodayApps() }

        // Setup app limits recycler view
        appAdapter = AppLimitAdapter { appInfo ->
            AppLimitSheet.newInstance(appInfo).show(parentFragmentManager, AppLimitSheet.RESULT_KEY)
        }
        parentFragmentManager.setFragmentResultListener(AppLimitSheet.RESULT_KEY, viewLifecycleOwner) { _, result ->
            result.getString("package_name")?.let { packageName ->
                viewModel.setAppLimit(packageName, result.getInt(AppLimitSheet.RESULT_MINUTES))
            }
        }
        binding.rvAppLimits.layoutManager = LinearLayoutManager(requireContext())
        binding.rvAppLimits.adapter = appAdapter

        setupChart()
        com.assistant.adi.ui.buddy.BuddyCharts.style(binding.screenTimeChart)
        binding.btnUsageAccess.setOnClickListener { (requireActivity() as DashboardActivity).navigateSection("permissions") }
        viewModel.availability.observe(viewLifecycleOwner) { availability ->
            currentAvailability = availability
            val ready = availability == ScreenTimeViewModel.Availability.READY
            binding.btnUsageAccess.visibility = if (availability == ScreenTimeViewModel.Availability.PERMISSION_REQUIRED) View.VISIBLE else View.GONE
            binding.tvScreenTimeSubtitle.text = when (availability) {
                ScreenTimeViewModel.Availability.LOADING -> "Membaca waktu layar dari Android…"
                ScreenTimeViewModel.Availability.PERMISSION_REQUIRED -> "Izinkan akses penggunaan untuk melihat durasi."
                ScreenTimeViewModel.Availability.UNAVAILABLE -> "Android belum menyediakan bacaan. Buka kembali halaman ini untuk mencoba lagi."
                else -> ""
            }
            binding.tvScreenTimeSubtitle.visibility = if (ready) View.GONE else View.VISIBLE
            binding.tvAppsEmpty.text = if (ready) "Belum ada pemakaian aplikasi yang tercatat hari ini."
                else "Daftar aplikasi muncul setelah data penggunaan dapat dibaca."
            renderTodayApps()
        }

        // Observe
        viewModel.totalScreenTimeToday.observe(viewLifecycleOwner) { time ->
            binding.tvTotalScreenTime.text = time
        }

        viewModel.appList.observe(viewLifecycleOwner) { list ->
            appAdapter.submitList(list)
            binding.tvAppsEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            todayAppRows = list.map { SavedScreenTimeApp(it.packageName, it.appName, it.usageMinutes.toLong()) }
            renderTodayApps()
        }

        viewModel.hourlyPatternData.observe(viewLifecycleOwner) { pattern ->
            val range = viewModel.heatmapRange.value
            if (range != null && (range.start != range.end || range.preset)) populateHourlyPattern(pattern)
        }

        viewModel.selectedHourlyData.observe(viewLifecycleOwner) { hours ->
            val range = viewModel.heatmapRange.value
            if (range != null && range.start == range.end && !range.preset) populateSelectedDateHours(range.start.toString(), hours)
        }
        viewModel.selectedStoredDateLogs.observe(viewLifecycleOwner) { logs ->
            if (savedDayDate != null) {
                val date = savedDayDate
                val packageManager = requireContext().packageManager
                viewLifecycleOwner.lifecycleScope.launch {
                    val resolved = withContext(Dispatchers.IO) {
                        logs.map { log ->
                            val label = try {
                                packageManager.getApplicationLabel(packageManager.getApplicationInfo(log.appPackage, 0)).toString()
                            } catch (_: PackageManager.NameNotFoundException) { log.appPackage }
                            catch (_: SecurityException) { log.appPackage }
                            SavedScreenTimeApp(log.appPackage, label, log.usageMinutes)
                        }
                    }
                    if (savedDayDate == date) { savedAppRows = resolved; renderSavedDayRows() }
                }
            }
        }
        viewModel.trendData.observe(viewLifecycleOwner, ::updateChartData)
        viewModel.retainedHistoryPage.observe(viewLifecycleOwner) { page ->
            val rows = page.records.map { day -> MonitorHistoryRow(day.date.hashCode().toLong(), "screen:${day.date}",
                formatFullDate(day.date), "Total aplikasi", screenDuration(day.totalMinutes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()),
                emptyList(), detail = "Buka catatan aplikasi ${day.date}") }
            historyUi?.render(page, rows)
        }
        savedDayDate?.let(::openSavedDay)
    }

    override fun onStart() { super.onStart(); viewModel.startForegroundReads() }
    override fun onResume() { super.onResume(); viewModel.refreshCalendar() }

    override fun onStop() { viewModel.stopForegroundReads(); super.onStop() }

    private fun setupChart() {
        val chart = binding.screenTimeChart
        chart.description.isEnabled = false
        chart.setTouchEnabled(true)
        chart.axisRight.isEnabled = false
        chart.legend.isEnabled = false

        val xAxis = chart.xAxis
        xAxis.position = XAxis.XAxisPosition.BOTTOM
        xAxis.setDrawGridLines(false)
        xAxis.textColor = ContextCompat.getColor(requireContext(), R.color.buddy_sub)

        val yAxis = chart.axisLeft
        yAxis.textColor = ContextCompat.getColor(requireContext(), R.color.buddy_sub)
        yAxis.setDrawGridLines(true)
        yAxis.gridColor = ContextCompat.getColor(requireContext(), R.color.buddy_divider)
        yAxis.axisMinimum = 0f
        yAxis.setLabelCount(5, false)
        yAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String = screenDuration((value * 60).roundToInt())
        }
        chart.setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
            override fun onValueSelected(entry: Entry?, highlight: Highlight?) {
                selectedTrendDate = trendDates.getOrNull(entry?.x?.roundToInt() ?: -1)
                renderSelectedTrend()
            }
            override fun onNothingSelected() = Unit
        })
    }

    private fun updateChartData(slots: List<ScreenTimeDaySlot>) {
        chartSlots = slots
        trendDates = slots.map { it.date }
        val recorded = slots.filter { it.totalMinutes != null }
        if (recorded.isEmpty()) {
            binding.screenTimeChart.clear()
            binding.screenTimeChart.setNoDataText("Belum ada catatan dalam 7 hari ini.")
            binding.tvScreenTimeChartSummary.text = "0 dari 7 hari tercatat"
            binding.tvScreenTimeChartSelected.text = "Belum ada total aplikasi tersimpan."
            selectedTrendDate = null
            binding.screenTimeChart.invalidate()
            return
        }
        val entries = slots.mapIndexedNotNull { idx, item ->
            item.totalMinutes?.let { BarEntry(idx.toFloat(), it / 60f) }
        }

        val dataSet = BarDataSet(entries, "Total pemakaian aplikasi (jam)").apply {
            color = ContextCompat.getColor(requireContext(), R.color.buddy_chart_blue)
            setDrawValues(false)
        }

        binding.screenTimeChart.xAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                val idx = value.toInt()
                return if (idx >= 0 && idx < slots.size && value == idx.toFloat()) {
                    formatDisplayDate(slots[idx].date)
                } else ""
            }
        }

        binding.screenTimeChart.data = BarData(dataSet).apply { barWidth = 0.6f }
        binding.screenTimeChart.xAxis.axisMinimum = -0.5f
        binding.screenTimeChart.xAxis.axisMaximum = 6.5f
        binding.screenTimeChart.xAxis.granularity = 1f
        selectedTrendDate = selectedTrendDate?.takeIf { date -> recorded.any { it.date == date } } ?: recorded.last().date
        binding.tvScreenTimeChartSummary.text = "${recorded.size} dari 7 hari tercatat · Total tercatat ${screenDuration(recorded.sumOf { it.totalMinutes!! }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())}"
        renderSelectedTrend()
        binding.screenTimeChart.invalidate()
    }

    private fun renderSelectedTrend() {
        val selected = chartSlots.firstOrNull { it.date == selectedTrendDate && it.totalMinutes != null } ?: return
        binding.tvScreenTimeChartSelected.text = "${formatFullDate(selected.date)} · ${screenDuration(selected.totalMinutes!!.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())}"
        val index = chartSlots.indexOf(selected)
        if (binding.screenTimeChart.highlighted?.firstOrNull()?.x != index.toFloat())
            binding.screenTimeChart.highlightValue(index.toFloat(), 0, false)
    }

    private fun formatDisplayDate(date: String): String = runCatching {
        java.time.LocalDate.parse(date).format(java.time.format.DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
    }.getOrDefault(date)

    private fun formatFullDate(date: String): String = runCatching {
        java.time.LocalDate.parse(date).format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()))
    }.getOrDefault(date)

    private fun populateHourlyPattern(pattern: List<HourlyUsagePattern>) {
        val byHour = pattern.associateBy { it.localHour }
        populateHourlyHeatmap(
            (0..23).map { hour ->
                byHour[hour]?.let { HourCell(hour, it.averageMinutes, it.observedDays, "Rata-rata", "${it.observedDays} hari teramati") }
                    ?: HourCell(hour, null, 0, "Belum ada data", "Tidak ada cakupan tersimpan")
            }
        )
    }

    private fun populateSelectedDateHours(date: String, hours: List<ScreenTimeHourly>) {
        val byHour = hours.groupBy { it.localHour }
        val now = java.time.ZonedDateTime.now()
        populateHourlyHeatmap(
            (0..23).map { hour ->
                val buckets = byHour[hour].orEmpty()
                val covered = buckets.sumOf { it.coveredMillis }
                val minutes = if (covered > 0) buckets.sumOf { it.usageMillis } / 60_000.0 else null
                val ongoing = date == now.toLocalDate().toString() && hour == now.hour
                val state = when {
                    ongoing -> "Sedang berlangsung"
                    minutes == null -> "Belum ada data"
                    buckets.size > 1 -> "Interval berulang"
                    covered < HOUR_MILLIS -> "Cakupan parsial"
                    else -> "Tercatat"
                }
                val detail = "Cakupan ${screenDuration((covered / 60_000).toInt())}. Hanya interval tercakup yang dihitung." +
                    if (buckets.size > 1) " · ${buckets.joinToString { bucket ->
                        java.time.Instant.ofEpochMilli(bucket.bucketStartMillis)
                            .atZone(java.time.ZoneId.of(bucket.zoneId))
                            .format(java.time.format.DateTimeFormatter.ofPattern("d MMM HH:mm XXX", Locale.getDefault()))
                    }} · Jam berulang; total dapat melebihi 60 menit." else ""
                HourCell(hour, minutes, if (minutes == null) 0 else 1, state, detail)
            }
        )
    }

    private fun populateHourlyHeatmap(cells: List<HourCell>) {
        val grid = binding.gridHeatmap
        grid.removeAllViews()

        val columns = if (resources.configuration.fontScale > 1.3f || resources.configuration.screenWidthDp < 400) 4 else 6
        grid.columnCount = columns
        val margin = (resources.displayMetrics.density * 3).toInt()
        cells.forEachIndexed { index, hourCell ->
            val cellView = TextView(requireContext()).apply {
                layoutParams = GridLayout.LayoutParams().apply {
                    columnSpec = GridLayout.spec(index % columns, 1f)
                    rowSpec = GridLayout.spec(index / columns)
                    width = 0
                    height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    setMargins(margin, margin, margin, margin)
                }
                gravity = Gravity.CENTER
                val minutes = hourCell.minutes
                val stateLabel = when (hourCell.state) {
                    "Belum ada data" -> "Kosong"
                    "Cakupan parsial" -> "Parsial"
                    "Sedang berlangsung" -> "Aktif"
                    "Interval berulang" -> "Ulang"
                    else -> ""
                }
                text = String.format(Locale.getDefault(), "%02d\n%s%s", hourCell.hour,
                    if (minutes == null) "—" else String.format(Locale.getDefault(), "%.2f m", minutes),
                    if (stateLabel.isEmpty()) "" else "\n$stateLabel")
                textSize = 12f
                minHeight = (48 * resources.displayMetrics.density).toInt()
                setTypeface(null, android.graphics.Typeface.BOLD)
                val fill = if (minutes == null) ContextCompat.getColor(context, R.color.buddy_tile)
                    else ScreenTimeHeatmapPolicy.fill(minutes,
                        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(fill); cornerRadius = 12 * resources.displayMetrics.density
                    if (minutes == null) setStroke((2 * resources.displayMetrics.density).toInt(), ContextCompat.getColor(context, R.color.buddy_outline))
                }
                val hourLabel = String.format(Locale.getDefault(), "%02d:00–%02d:00", hourCell.hour, (hourCell.hour + 1) % 24)
                contentDescription = "$hourLabel, ${if (minutes == null) "belum ada data" else String.format(Locale.getDefault(), "%.2f menit", minutes)}, ${hourCell.state}, ${hourCell.observedDays} hari teramati, ${hourCell.detail}"
                ViewCompat.setTooltipText(this, contentDescription)
                isFocusable = true
                setOnClickListener { MonitorDetailUi(this@ScreenTimeFragment).showInfoSheet("Pola per jam · $hourLabel", contentDescription.toString(), trigger = this) }
                setTextColor(ScreenTimeHeatmapPolicy.foreground(fill))
            }
            grid.addView(cellView)
        }
    }

    private fun showHeatmapRangeMenu() {
        AlertDialog.Builder(requireContext()).setItems(arrayOf("30 hari terakhir", "Pilih rentang tanggal")) { _, choice ->
            if (choice == 0) viewModel.useLastThirtyDays() else showHeatmapDatePicker(true)
        }.show()
    }

    private fun showHeatmapDatePicker(startPicker: Boolean, chosenStart: LocalDate? = null) {
        val today = LocalDate.now(ZoneId.systemDefault())
        val initial = chosenStart ?: viewModel.heatmapRange.value?.start ?: today
        DatePickerDialog(requireContext(), { _, year, month, day ->
            val chosen = LocalDate.of(year, month + 1, day)
            if (startPicker) showHeatmapDatePicker(false, chosen)
            else chosenStart?.let { viewModel.setHeatmapRange(it, chosen) }
        }, initial.year, initial.monthValue - 1, initial.dayOfMonth).apply {
            datePicker.maxDate = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            if (!startPicker && chosenStart != null) {
                datePicker.minDate = chosenStart.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }
            setTitle(if (startPicker) "Tanggal mulai" else "Tanggal akhir")
        }.show()
    }

    private fun renderChartMode(mode: ScreenTimeViewModel.ChartMode) {
        val bars = mode == ScreenTimeViewModel.ChartMode.BARS
        binding.chartBarsContent.visibility = if (bars) View.VISIBLE else View.GONE
        binding.chartHeatmapContent.visibility = if (bars) View.GONE else View.VISIBLE
        binding.tvScreenTimeChartTitle.setText(if (bars) R.string.screen_chart_title else R.string.screen_heatmap_title)
        binding.btnChartBars.isSelected = bars
        binding.btnChartHeatmap.isSelected = !bars
        binding.btnChartBars.stateDescription = if (bars) "Dipilih" else "Tidak dipilih"
        binding.btnChartHeatmap.stateDescription = if (bars) "Tidak dipilih" else "Dipilih"
        val selectedTint = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.buddy_action))
        val normalTint = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.buddy_ink))
        binding.btnChartBars.imageTintList = if (bars) selectedTint else normalTint
        binding.btnChartHeatmap.imageTintList = if (bars) normalTint else selectedTint
    }

    private fun renderTodayApps() {
        if (_binding == null) return
        val ready = currentAvailability == ScreenTimeViewModel.Availability.READY
        val expanded = viewModel.appsExpanded.value == true
        todayAppAdapter.submit(if (ready) (if (expanded) todayAppRows else todayAppRows.take(5)) else emptyList())
        binding.tvTodayAppsEmpty.visibility = if (ready && todayAppRows.isNotEmpty()) View.GONE else View.VISIBLE
        binding.tvTodayAppsEmpty.text = when (currentAvailability) {
            ScreenTimeViewModel.Availability.LOADING -> "Membaca daftar aplikasi…"
            ScreenTimeViewModel.Availability.PERMISSION_REQUIRED -> "Izinkan akses penggunaan untuk melihat aplikasi hari ini."
            ScreenTimeViewModel.Availability.UNAVAILABLE -> "Daftar aplikasi belum tersedia. Buka kembali halaman ini untuk mencoba lagi."
            else -> "Belum ada pemakaian aplikasi yang tercatat hari ini."
        }
        binding.btnTodayAppsExpand.visibility = if (ready && todayAppRows.size > 5) View.VISIBLE else View.GONE
        binding.btnTodayAppsExpand.text = if (expanded) "Tampilkan lebih sedikit" else "Lihat semua aplikasi"
    }

    private fun openSavedDay(date: String, source: View? = null) {
        savedDayDialog?.dismiss()
        savedDayDate = date
        savedAppRows = emptyList()
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        content.addView(TextView(requireContext()).apply {
            text = "Aplikasi tersimpan · ${formatFullDate(date)}"
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        content.addView(EditText(requireContext()).apply {
            hint = "Cari nama atau paket"
            setSingleLine(true)
            setText(savedDaySearch)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    savedDaySearch = s?.toString().orEmpty()
                    renderSavedDayRows()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }, LinearLayout.LayoutParams(-1, -2))
        val sortButton = MaterialButton(requireContext()).apply {
            isAllCaps = false
            minHeight = dp(48)
            icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_ms_sort)
            setOnClickListener {
                HistorySortSheet.show(requireContext(), listOf(
                    HistorySortSheet.Option("Durasi", R.drawable.ic_ms_timer),
                    HistorySortSheet.Option("Nama aplikasi", R.drawable.ic_ms_sort, isName = true)
                ), if (savedDayNameSort) 1 else 0, savedDayDescending) { index, descending ->
                    savedDayNameSort = index == 1
                    savedDayDescending = descending
                    text = "Urutkan: ${if (savedDayNameSort) "Nama aplikasi" else "Durasi"} ${if (descending) "↓" else "↑"}"
                    renderSavedDayRows()
                }
            }
            text = "Urutkan: ${if (savedDayNameSort) "Nama aplikasi" else "Durasi"} ${if (savedDayDescending) "↓" else "↑"}"
        }
        content.addView(sortButton)
        savedDayCount = TextView(requireContext()).also { content.addView(it) }
        val list = androidx.recyclerview.widget.RecyclerView(requireContext()).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = SavedScreenTimeAppAdapter().also { savedDayAdapter = it }
        }
        content.addView(list, LinearLayout.LayoutParams(-1, minOf(dp(400), resources.displayMetrics.heightPixels / 2)))
        savedDayDialog = BottomSheetDialog(requireContext()).apply {
            setContentView(content)
            setOnDismissListener {
                savedDayDialog = null
                savedDayAdapter = null
                savedDayCount = null
                savedDayDate = null
                source?.post { if (source.isAttachedToWindow) source.requestFocus() }
            }
            show()
        }
        viewModel.selectStoredDate(date)
        renderSavedDayRows()
    }

    private fun renderSavedDayRows() {
        val query = savedDaySearch.trim()
        val filtered = savedAppRows.filter {
            query.isEmpty() || it.label.contains(query, true) || it.packageName.contains(query, true)
        }
        val sorted = if (savedDayNameSort) filtered.sortedWith(
            if (savedDayDescending) compareByDescending<SavedScreenTimeApp, String>(String.CASE_INSENSITIVE_ORDER) { it.label }.thenBy { it.packageName }
            else compareBy<SavedScreenTimeApp, String>(String.CASE_INSENSITIVE_ORDER) { it.label }.thenBy { it.packageName }
        ) else filtered.sortedWith(
            if (savedDayDescending) compareByDescending<SavedScreenTimeApp> { it.minutes }.thenBy { it.label }.thenBy { it.packageName }
            else compareBy<SavedScreenTimeApp> { it.minutes }.thenBy { it.label }.thenBy { it.packageName }
        )
        savedDayAdapter?.submit(sorted)
        savedDayCount?.text = if (savedAppRows.isEmpty()) "Belum ada aplikasi tersimpan pada tanggal ini."
            else "${sorted.size} dari ${savedAppRows.size} aplikasi"
    }

    private fun selectPanel(panel: MonitorDetailPanel) {
        selectedPanel = panel
        panelRail?.renderSelection(panel)
        binding.panelScreenSummary.visibility = if (panel == MonitorDetailPanel.SUMMARY) View.VISIBLE else View.GONE
        binding.panelScreenHistory.visibility = if (panel == MonitorDetailPanel.HISTORY) View.VISIBLE else View.GONE
        binding.panelScreenLimits.visibility = if (panel == MonitorDetailPanel.SETTINGS) View.VISIBLE else View.GONE
        binding.root.post { _binding?.root?.scrollTo(0, 0) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("screen_panel", selectedPanel.name)
        outState.putString("selected_trend_date", selectedTrendDate)
        outState.putString("saved_day_date", savedDayDate)
        outState.putString("saved_day_search", savedDaySearch)
        outState.putBoolean("saved_day_name_sort", savedDayNameSort)
        outState.putBoolean("saved_day_descending", savedDayDescending)
        historyUi?.saveAnchor()
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        savedDayDialog?.dismiss()
        savedDayDialog = null
        historyUi = null
        panelRail = null
        super.onDestroyView()
        _binding = null
    }

    private data class HourCell(val hour: Int, val minutes: Double?, val observedDays: Int, val state: String, val detail: String)

    private companion object {
        const val HOUR_MILLIS = 60 * 60 * 1000L
    }
}

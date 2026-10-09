package com.assistant.adi.data

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.ZoneId

enum class HistoryMetric { BATTERY, RAM, NETWORK, SCREEN_TIME, INTERNET }
enum class HistorySort { DATE, PERCENTAGE, TEMPERATURE, USED_RAM, AVAILABLE_RAM, RAM_PERCENTAGE, DOWNLOAD, UPLOAD, PING, TOTAL, WIFI, MOBILE }
enum class HistoryDirection { ASC, DESC }
enum class HistoryFilter { ALL, CHARGING, DISCHARGING, WIFI, MOBILE, OFFLINE, COMPLETE, PARTIAL, MISSING }
enum class HistoryRange { HOURS_24, ALL, DAYS_7, DAYS_30, CUSTOM }

data class MonitorHistoryInput(
    val metric: HistoryMetric,
    val range: HistoryRange = HistoryRange.DAYS_7,
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    val sort: HistorySort = HistorySort.DATE,
    val direction: HistoryDirection = HistoryDirection.DESC,
    val filter: HistoryFilter = HistoryFilter.ALL,
    val page: Int = 0,
    val revision: Long = 0
) {
    init { require(page >= 0) }
    fun dates(today: LocalDate = LocalDate.now()): Pair<LocalDate?, LocalDate?> = when (range) {
        HistoryRange.HOURS_24 -> null to null
        HistoryRange.ALL -> null to null
        HistoryRange.DAYS_7 -> today.minusDays(6) to today
        HistoryRange.DAYS_30 -> today.minusDays(29) to today
        HistoryRange.CUSTOM -> {
            require(startDate != null && endDate != null && !startDate.isAfter(endDate))
            startDate to endDate
        }
    }
}

data class RetainedBounds(val first: String?, val last: String?)
data class MonitorHistoryPage<T>(val records: List<T>, val count: Int, val bounds: RetainedBounds, val page: Int) {
    val hasNext: Boolean get() = (page + 1L) * PAGE_SIZE < count
    companion object { const val PAGE_SIZE = 15 }
}

data class InternetHistorySlot(val date: LocalDate, val record: InternetUsageDaily?)

enum class ReadingQuality { MEASURED, LEGACY_RECORDED, VALIDITY_UNKNOWN, UNAVAILABLE }
data class HistoryReading<T>(val value: T?, val quality: ReadingQuality)

fun BatteryLog.percentageReading(): HistoryReading<Int> =
    if (percentage in 0..100) HistoryReading(percentage, ReadingQuality.MEASURED)
    else HistoryReading(null, ReadingQuality.UNAVAILABLE)

fun BatteryLog.temperatureReading(): HistoryReading<Float> = when {
    temperatureValid == false || !temperature.isFinite() || temperature !in -20f..100f ->
        HistoryReading(null, ReadingQuality.UNAVAILABLE)
    temperatureValid == true -> HistoryReading(temperature, ReadingQuality.MEASURED)
    temperature == 0f -> HistoryReading(temperature, ReadingQuality.VALIDITY_UNKNOWN)
    else -> HistoryReading(temperature, ReadingQuality.LEGACY_RECORDED)
}

fun RamLog.usedPercentageReading(): HistoryReading<Double> =
    if (totalRam > 0 && usedRam >= 0 && availableRam >= 0)
        HistoryReading(usedRam * 100.0 / totalRam, ReadingQuality.MEASURED)
    else HistoryReading(null, ReadingQuality.UNAVAILABLE)

fun NetworkLog.downloadReading(): HistoryReading<Double> = speedReading(downloadSpeed, downloadSpeedValid)
fun NetworkLog.uploadReading(): HistoryReading<Double> = speedReading(uploadSpeed, uploadSpeedValid)
fun NetworkLog.pingReading(): HistoryReading<Int> =
    if (ping >= 0) HistoryReading(ping, ReadingQuality.MEASURED)
    else HistoryReading(null, ReadingQuality.UNAVAILABLE)

private fun speedReading(value: Double, valid: Boolean?): HistoryReading<Double> = when {
    valid == false || !value.isFinite() || value < 0 -> HistoryReading(null, ReadingQuality.UNAVAILABLE)
    valid == true -> HistoryReading(value, ReadingQuality.MEASURED)
    value == 0.0 -> HistoryReading(value, ReadingQuality.VALIDITY_UNKNOWN)
    else -> HistoryReading(value, ReadingQuality.LEGACY_RECORDED)
}

class MonitorHistoryState(private val saved: SavedStateHandle, private val metric: HistoryMetric) {
    private val prefix = "${metric.name.lowercase()}_retained_history_"
    private fun <T> value(name: String): T? = saved[prefix + name]
    private val _input = MutableStateFlow(MonitorHistoryInput(
        metric = metric,
        range = enumOrDefault(value<String>("range"), HistoryRange.DAYS_7),
        startDate = value<String>("start")?.let(LocalDate::parse),
        endDate = value<String>("end")?.let(LocalDate::parse),
        sort = enumOrDefault(value<String>("sort"), HistorySort.DATE),
        direction = enumOrDefault(value<String>("direction"), HistoryDirection.DESC),
        filter = enumOrDefault(value<String>("filter"), HistoryFilter.ALL),
        page = (value<Int>("page") ?: 0).coerceAtLeast(0)
    ))
    val input: StateFlow<MonitorHistoryInput> = _input
    val queryInput = combine(input, flow {
        var lastKey = ""
        while (true) {
            val zone = ZoneId.systemDefault()
            val key = "${System.currentTimeMillis() / 60_000}|${zone.id}"
            if (key != lastKey) { emit(key); lastKey = key }
            delay(60_000)
        }
    }) { current, key -> current.copy(revision = current.revision + key.hashCode()) }
    init { saved.remove<String>(prefix + "selected") }
    fun anchorKey(input: MonitorHistoryInput = _input.value): String =
        listOf(input.range, input.startDate, input.endDate, input.sort, input.direction, input.filter, input.page).joinToString("|")
    fun saveAnchor(scrollY: Int) {
        saved[prefix + "anchor_key"] = anchorKey()
        saved[prefix + "anchor_y"] = scrollY
    }
    fun restoredAnchor(): Int? = (value<String>("anchor_key")?.takeIf { it == anchorKey() })?.let { value<Int>("anchor_y") }
    fun update(transform: (MonitorHistoryInput) -> MonitorHistoryInput) {
        val next = transform(_input.value)
        require(next.metric == metric)
        saved[prefix + "range"] = next.range.name
        saved[prefix + "start"] = next.startDate?.toString()
        saved[prefix + "end"] = next.endDate?.toString()
        saved[prefix + "sort"] = next.sort.name
        saved[prefix + "direction"] = next.direction.name
        saved[prefix + "filter"] = next.filter.name
        saved[prefix + "page"] = next.page
        _input.value = next
    }
    fun range(range: HistoryRange, start: LocalDate? = null, end: LocalDate? = null) =
        update { it.copy(range = range, startDate = start, endDate = end, page = 0) }
    fun sort(sort: HistorySort, direction: HistoryDirection) = update { it.copy(sort = sort, direction = direction, page = 0) }
    fun filter(filter: HistoryFilter) = update { it.copy(filter = filter, page = 0) }
    fun page(page: Int) = update { it.copy(page = page.coerceAtLeast(0)) }
    fun refreshRollingWindow() { _input.value = _input.value.copy(revision = _input.value.revision + 1) }

    private inline fun <reified T : Enum<T>> enumOrDefault(raw: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == raw } ?: fallback
}

/** SQL fragments here are selected from enums; only range values and offsets become query arguments. */
internal object MonitorHistorySql {
    private data class Table(val name: String, val time: String, val dateBased: Boolean)
    private fun table(metric: HistoryMetric): Table = when (metric) {
        HistoryMetric.BATTERY -> Table("battery_logs", "timestamp", false)
        HistoryMetric.RAM -> Table("ram_logs", "timestamp", false)
        HistoryMetric.NETWORK -> Table("network_logs", "timestamp", false)
        HistoryMetric.SCREEN_TIME -> Table("screen_time_logs", "date", true)
        HistoryMetric.INTERNET -> Table("internet_usage_daily", "localDate", true)
    }

    private fun sort(input: MonitorHistoryInput): String = when (input.metric to input.sort) {
        HistoryMetric.BATTERY to HistorySort.DATE -> "timestamp"
        HistoryMetric.BATTERY to HistorySort.PERCENTAGE -> "CASE WHEN percentage BETWEEN 0 AND 100 THEN percentage END"
        HistoryMetric.BATTERY to HistorySort.TEMPERATURE -> "CASE WHEN temperatureValid = 1 AND temperature BETWEEN -20 AND 100 THEN temperature WHEN temperatureValid IS NULL AND temperature != 0 AND temperature BETWEEN -20 AND 100 THEN temperature END"
        HistoryMetric.RAM to HistorySort.DATE -> "timestamp"
        HistoryMetric.RAM to HistorySort.USED_RAM -> "CASE WHEN totalRam > 0 THEN usedRam END"
        HistoryMetric.RAM to HistorySort.AVAILABLE_RAM -> "CASE WHEN totalRam > 0 THEN availableRam END"
        HistoryMetric.RAM to HistorySort.RAM_PERCENTAGE -> "CASE WHEN totalRam > 0 THEN usedRam * 100.0 / totalRam END"
        HistoryMetric.NETWORK to HistorySort.DATE -> "timestamp"
        HistoryMetric.NETWORK to HistorySort.DOWNLOAD -> rate("downloadSpeed", "downloadSpeedValid")
        HistoryMetric.NETWORK to HistorySort.UPLOAD -> rate("uploadSpeed", "uploadSpeedValid")
        HistoryMetric.NETWORK to HistorySort.PING -> "CASE WHEN ping >= 0 THEN ping END"
        HistoryMetric.SCREEN_TIME to HistorySort.DATE -> "date"
        HistoryMetric.SCREEN_TIME to HistorySort.TOTAL -> "SUM(usageMinutes)"
        HistoryMetric.INTERNET to HistorySort.DATE -> "localDate"
        HistoryMetric.INTERNET to HistorySort.TOTAL -> "CASE WHEN wifiBytes IS NOT NULL AND mobileBytes IS NOT NULL THEN wifiBytes + mobileBytes END"
        HistoryMetric.INTERNET to HistorySort.WIFI -> "wifiBytes"
        HistoryMetric.INTERNET to HistorySort.MOBILE -> "mobileBytes"
        else -> error("Sort is not available for ${input.metric}")
    }

    private fun rate(value: String, valid: String) =
        "CASE WHEN $value >= 0 AND $value <= 1.0e308 AND ($valid = 1 OR ($valid IS NULL AND $value > 0)) THEN $value END"

    private fun parts(input: MonitorHistoryInput, zone: ZoneId): Pair<String, List<Any>> {
        val table = table(input.metric)
        val (start, end) = input.dates(LocalDate.now(zone))
        val conditions = mutableListOf<String>()
        val args = mutableListOf<Any>()
        if (input.range == HistoryRange.HOURS_24) {
            require(!table.dateBased)
            conditions += "${table.time} >= ?"
            args += System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        }
        if (start != null) {
            conditions += "${table.time} >= ?"
            args += if (table.dateBased) start.toString() else start.atStartOfDay(zone).toInstant().toEpochMilli()
        }
        if (end != null) {
            conditions += if (table.dateBased) "${table.time} <= ?" else "${table.time} < ?"
            args += if (table.dateBased) end.toString() else end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        }
        when (input.metric to input.filter) {
            HistoryMetric.BATTERY to HistoryFilter.CHARGING -> conditions += "isCharging = 1"
            HistoryMetric.BATTERY to HistoryFilter.DISCHARGING -> conditions += "isCharging = 0"
            HistoryMetric.NETWORK to HistoryFilter.WIFI -> conditions += "type = 'wifi'"
            HistoryMetric.NETWORK to HistoryFilter.MOBILE -> conditions += "type = 'mobile'"
            HistoryMetric.NETWORK to HistoryFilter.OFFLINE -> conditions += "type = 'offline'"
            HistoryMetric.INTERNET to HistoryFilter.COMPLETE -> conditions += "wifiBytes IS NOT NULL AND mobileBytes IS NOT NULL"
            HistoryMetric.INTERNET to HistoryFilter.PARTIAL -> conditions += "wifiBytes IS NULL OR mobileBytes IS NULL"
            else -> require(input.filter == HistoryFilter.ALL) { "Filter is not available for ${input.metric}" }
        }
        return (if (conditions.isEmpty()) "" else " WHERE ${conditions.joinToString(" AND ")}") to args
    }

    fun page(input: MonitorHistoryInput, zone: ZoneId): SupportSQLiteQuery {
        val table = table(input.metric)
        val (where, args) = parts(input, zone)
        val expression = sort(input)
        val projection = if (input.metric == HistoryMetric.SCREEN_TIME) "date, SUM(usageMinutes) AS totalMinutes" else "*"
        val group = if (input.metric == HistoryMetric.SCREEN_TIME) " GROUP BY date" else ""
        val direction = input.direction.name
        val stable = if (input.metric == HistoryMetric.SCREEN_TIME) "date DESC" else "${table.time} DESC, id ASC"
        val sql = "SELECT $projection FROM ${table.name}$where$group ORDER BY ($expression) IS NULL ASC, $expression $direction, $stable LIMIT ? OFFSET ?"
        return SimpleSQLiteQuery(sql, (args + listOf(MonitorHistoryPage.PAGE_SIZE, input.page.toLong() * MonitorHistoryPage.PAGE_SIZE)).toTypedArray())
    }

    fun count(input: MonitorHistoryInput, zone: ZoneId): SupportSQLiteQuery {
        val table = table(input.metric)
        val (where, args) = parts(input, zone)
        val sql = if (input.metric == HistoryMetric.SCREEN_TIME)
            "SELECT COUNT(DISTINCT date) FROM ${table.name}$where"
        else "SELECT COUNT(*) FROM ${table.name}$where"
        return SimpleSQLiteQuery(sql, args.toTypedArray())
    }

    fun internetWindow(start: LocalDate, end: LocalDate): SupportSQLiteQuery = SimpleSQLiteQuery(
        "SELECT * FROM internet_usage_daily WHERE localDate >= ? AND localDate <= ? ORDER BY localDate, dayStartMillis, id",
        arrayOf(start.toString(), end.toString())
    )

    fun bounds(metric: HistoryMetric): SupportSQLiteQuery {
        val table = table(metric)
        return SimpleSQLiteQuery("SELECT CAST(MIN(${table.time}) AS TEXT) AS first, CAST(MAX(${table.time}) AS TEXT) AS last FROM ${table.name}")
    }
}

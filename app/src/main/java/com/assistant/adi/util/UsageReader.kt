package com.assistant.adi.util

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import java.time.*

object UsageReader {
    private const val EVENT_LOOKBACK_MILLIS = 7L * 24 * 60 * 60 * 1000
    fun hasPermission(context: Context): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }
    data class Result(
        val granted:Boolean, val appMillis:Map<String,Long>, val hourlyMinutes:IntArray,
        val screenTimeMillis:Long = 0, val continuousScreenTimeMillis:Long = 0,
        val available:Boolean = true
    )
    data class RangeResult(
        val granted: Boolean,
        val available: Boolean,
        val appMillis: Map<String, Long> = emptyMap(),
        val buckets: List<HourBuckets.Bucket> = emptyList()
    )

    /** Current-day read for live UI. Historical reconciliation uses [readRange]. */
    fun read(context:Context):Result = readToday(context)

    fun readToday(context:Context):Result {
        val ops=context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        if(ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,android.os.Process.myUid(),context.packageName)!=AppOpsManager.MODE_ALLOWED)
            return Result(false,emptyMap(),IntArray(24))
        val zone=ZoneId.systemDefault()
        val start=LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val end=System.currentTimeMillis()
        val manager=context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events=manager.queryEvents(start-86_400_000L,end) ?: return Result(true,emptyMap(),IntArray(24), available = false)
        val active=mutableSetOf<Pair<String,String>>()
        val totals=mutableMapOf<String,Long>()
        val hourly=LongArray(24)
        var interactive=false
        var unlocked=true
        var sessionStart:Long? = null
        var screenTimeMillis=0L
        var last=start
        fun accumulate(until:Long) {
            val to=until.coerceIn(start,end)
            if(to>last && interactive && unlocked) {
                screenTimeMillis += to-last
                active.map { it.first }.toSet().forEach { totals[it]=(totals[it]?:0)+(to-last) }
                HourBuckets.add(hourly,last,to,zone)
            }
            last=maxOf(last,to)
        }
        while(events.hasNextEvent()) {
            val event=UsageEvents.Event(); events.getNextEvent(event)
            if(event.timeStamp>=start) accumulate(event.timeStamp)
            val key=event.packageName.orEmpty() to event.className.orEmpty()
            val wasActive = interactive && unlocked
            when(event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> active.add(key)
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> active.remove(key)
                UsageEvents.Event.SCREEN_INTERACTIVE -> interactive=true
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> { interactive=false; active.clear() }
                UsageEvents.Event.KEYGUARD_SHOWN -> unlocked=false
                UsageEvents.Event.KEYGUARD_HIDDEN -> unlocked=true
                UsageEvents.Event.DEVICE_SHUTDOWN -> { interactive=false; active.clear() }
            }
            val isActive = interactive && unlocked
            if (!wasActive && isActive) sessionStart = event.timeStamp
            if (!isActive) sessionStart = null
        }
        accumulate(end)
        val continuous = sessionStart?.let { (end-it).coerceAtLeast(0) } ?: 0
        return Result(true,totals,hourly.map { (it/60_000).toInt() }.toIntArray(), screenTimeMillis, continuous)
    }

    /**
     * Reads a bounded UsageEvents interval without inventing coverage. A bucket receives a
     * zero only when SCREEN_INTERACTIVE and KEYGUARD state were both established by events.
     */
    fun readRange(context: Context, startMillis: Long, endMillis: Long): RangeResult {
        require(endMillis >= startMillis)
        if (!hasPermission(context)) return RangeResult(granted = false, available = false)
        if (endMillis == startMillis) return RangeResult(granted = true, available = true)
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val queryStart = (startMillis - EVENT_LOOKBACK_MILLIS).coerceAtLeast(0)
        val events = runCatching { manager.queryEvents(queryStart, endMillis) }.getOrNull()
            ?: return RangeResult(granted = true, available = false)
        val zone = ZoneId.systemDefault()
        val active = mutableSetOf<Pair<String, String>>()
        val apps = mutableMapOf<String, Long>()
        data class Totals(var usage: Long = 0, var covered: Long = 0, val local: ZonedDateTime)
        val hourly = linkedMapOf<Long, Totals>()
        var interactive: Boolean? = null
        var unlocked: Boolean? = null
        var cursor = startMillis

        fun accumulate(until: Long) {
            val to = until.coerceIn(startMillis, endMillis)
            if (to <= cursor) return
            if (interactive != null && unlocked != null) {
                HourBuckets.split(cursor, to, zone) { bucketStart, bucketEnd, local ->
                    val totals = hourly.getOrPut(bucketStart) { Totals(local = local) }
                    val duration = bucketEnd - bucketStart
                    totals.covered += duration
                    if (interactive == true && unlocked == true) {
                        totals.usage += duration
                        active.map { it.first }.toSet().forEach { pkg -> apps[pkg] = (apps[pkg] ?: 0) + duration }
                    }
                }
            }
            cursor = to
        }

        while (events.hasNextEvent()) {
            val event = UsageEvents.Event()
            events.getNextEvent(event)
            if (event.timeStamp >= endMillis) break
            if (event.timeStamp >= startMillis) accumulate(event.timeStamp)
            val key = event.packageName.orEmpty() to event.className.orEmpty()
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> active.add(key)
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> active.remove(key)
                UsageEvents.Event.SCREEN_INTERACTIVE -> interactive = true
                UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.DEVICE_SHUTDOWN -> {
                    interactive = false
                    active.clear()
                }
                UsageEvents.Event.KEYGUARD_SHOWN -> unlocked = false
                UsageEvents.Event.KEYGUARD_HIDDEN -> unlocked = true
            }
        }
        accumulate(endMillis)
        return RangeResult(
            granted = true,
            available = true,
            appMillis = apps,
            buckets = hourly.map { (start, totals) ->
                HourBuckets.Bucket(
                    startMillis = start,
                    localDate = totals.local.toLocalDate().toString(),
                    localHour = totals.local.hour,
                    zoneId = totals.local.zone.id,
                    offsetMinutes = totals.local.offset.totalSeconds / 60,
                    usageMillis = totals.usage,
                    coveredMillis = totals.covered
                )
            }
        )
    }
}


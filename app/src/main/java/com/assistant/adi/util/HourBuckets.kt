package com.assistant.adi.util
import java.time.*
object HourBuckets {
    fun add(buckets:LongArray,start:Long,end:Long,zone:ZoneId) {
        var cursor=start
        while(cursor<end) {
            val local=Instant.ofEpochMilli(cursor).atZone(zone)
            val boundary=local.withMinute(0).withSecond(0).withNano(0).plusHours(1).toInstant().toEpochMilli()
            val next=minOf(end,boundary)
            if(next<=cursor) break
            buckets[local.hour]+=next-cursor
            cursor=next
        }
    }

    data class Bucket(
        val startMillis: Long,
        val localDate: String,
        val localHour: Int,
        val zoneId: String,
        val offsetMinutes: Int,
        val usageMillis: Long,
        val coveredMillis: Long
    )

    /** Splits an absolute interval at local-hour boundaries, including DST transitions. */
    fun split(start: Long, end: Long, zone: ZoneId, block: (start: Long, end: Long, local: ZonedDateTime) -> Unit) {
        var cursor = start
        while (cursor < end) {
            val local = Instant.ofEpochMilli(cursor).atZone(zone)
            val boundary = local.withMinute(0).withSecond(0).withNano(0).plusHours(1).toInstant().toEpochMilli()
            val next = minOf(end, boundary)
            if (next <= cursor) break
            block(cursor, next, local)
            cursor = next
        }
    }
}

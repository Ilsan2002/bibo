package com.bibo.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class UsageInterval(val start: Long, val end: Long)
data class HourWindow(val hour: Int, val start: Long, val end: Long)
enum class UsageCoverageState { UNKNOWN, PARTIAL, COMPLETE }

/** Measurement helpers are independent of display grouping and Android collection. */
object UsageAggregation {
    fun union(intervals: List<UsageInterval>, start: Long, end: Long): List<UsageInterval> {
        if (end <= start) return emptyList()
        val sorted = intervals.mapNotNull {
            val a = maxOf(start, it.start)
            val b = minOf(end, it.end)
            if (b > a) UsageInterval(a, b) else null
        }.sortedBy { it.start }
        val result = mutableListOf<UsageInterval>()
        for (interval in sorted) {
            val last = result.lastOrNull()
            if (last != null && interval.start <= last.end) {
                result[result.lastIndex] = last.copy(end = maxOf(last.end, interval.end))
            } else result += interval
        }
        return result
    }

    fun unionMillis(intervals: List<UsageInterval>, start: Long, end: Long): Long =
        union(intervals, start, end).sumOf { it.end - it.start }

    /** Actual elapsed ranges: repeated hours have two ranges; skipped hours have none. */
    fun hourWindows(day: LocalDate, zone: ZoneId): List<HourWindow> {
        var cursor = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val result = mutableListOf<HourWindow>()
        while (cursor < end) {
            val instant = Instant.ofEpochMilli(cursor)
            val local = instant.atZone(zone)
            val wallBoundary = local.toLocalDateTime().withMinute(0).withSecond(0).withNano(0)
                .plusHours(1).toInstant(local.offset).toEpochMilli()
            val transition = zone.rules.nextTransition(instant)?.instant?.toEpochMilli() ?: Long.MAX_VALUE
            val next = minOf(end, wallBoundary, transition)
            check(next > cursor) { "Non-progressing hour boundary" }
            result += HourWindow(local.hour, cursor, next)
            cursor = next
        }
        return result
    }

    fun hourlyMillis(intervals: List<UsageInterval>, day: LocalDate, zone: ZoneId): LongArray {
        val result = LongArray(24)
        for (window in hourWindows(day, zone)) {
            result[window.hour] += unionMillis(intervals, window.start, window.end)
        }
        return result
    }

    fun coverage(intervals: List<UsageInterval>, start: Long, end: Long): UsageCoverageState {
        if (end <= start) return UsageCoverageState.UNKNOWN
        val known = unionMillis(intervals, start, end)
        return when {
            known == 0L -> UsageCoverageState.UNKNOWN
            known >= end - start -> UsageCoverageState.COMPLETE
            else -> UsageCoverageState.PARTIAL
        }
    }
}

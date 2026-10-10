package com.bibo.data

import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow

data class TimeWindow(val startMillis: Long, val endMillis: Long)
data class DayContext(val date: LocalDate, val zone: ZoneId)

/** Wall time locates records; elapsedRealtime measures a running session. */
object AppTime {
    @Volatile var clock: Clock = Clock.systemUTC()
    fun nowMillis(): Long = clock.millis()
    fun zone(): ZoneId = ZoneId.systemDefault()
    fun today(zone: ZoneId = zone()): LocalDate = LocalDate.now(clock.withZone(zone))
    fun window(date: LocalDate, zone: ZoneId = zone()): TimeWindow = TimeWindow(
        date.atStartOfDay(zone).toInstant().toEpochMilli(),
        date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
    )
    fun overlapMillis(start: Long, end: Long, windowStart: Long, windowEnd: Long): Long =
        TimeMath.overlapMillis(start, end, windowStart, windowEnd)

    fun measuredWorkMillis(blocks: List<ActivityBlock>, start: Long, end: Long): Long =
        TimeMath.unionMillis(blocks.filter { it.source in measuredSources }
            .map { it.startMillis to it.endMillis }, start, end)

    val measuredSources = setOf("TIMER", "FOCUS", "TASK_TIMER")
    val days = flow {
        while (true) {
            val currentZone = zone()
            emit(DayContext(today(currentZone), currentZone))
            delay(15_000)
        }
    }.distinctUntilChanged()
}

/** Pure interval math shared by app measurements and deterministic tests. */
object TimeMath {
    fun overlapMillis(start: Long, end: Long, windowStart: Long, windowEnd: Long): Long {
        if (end <= start || windowEnd <= windowStart) return 0
        val a = maxOf(start, windowStart)
        val b = minOf(end, windowEnd)
        return if (b > a) b - a else 0
    }

    fun unionMillis(intervals: List<Pair<Long, Long>>, start: Long, end: Long): Long {
        if (end <= start) return 0
        val clipped = intervals.map { maxOf(start, it.first) to minOf(end, it.second) }
            .filter { it.second > it.first }.sortedBy { it.first }
        if (clipped.isEmpty()) return 0
        var left = clipped.first().first
        var right = clipped.first().second
        var sum = 0L
        clipped.drop(1).forEach { (a, b) ->
            if (a <= right) right = maxOf(right, b)
            else { sum += right - left; left = a; right = b }
        }
        return sum + right - left
    }
}

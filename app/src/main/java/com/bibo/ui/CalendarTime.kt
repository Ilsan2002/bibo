package com.bibo.ui

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Timed appointments are instants; all-day events are date ranges with an exclusive end. */
data class CalendarInterval(val startMillis: Long, val endMillis: Long)
sealed interface CalendarTimeResult {
    data class Valid(val interval: CalendarInterval) : CalendarTimeResult
    data class Invalid(val message: String) : CalendarTimeResult
}

object CalendarTime {
    fun timed(start: LocalDateTime, end: LocalDateTime, zone: ZoneId,
              startOffset: ZoneOffset? = null, endOffset: ZoneOffset? = null): CalendarTimeResult {
        fun instant(time: LocalDateTime, selected: ZoneOffset?): Instant? {
            val offsets = zone.rules.getValidOffsets(time)
            if (offsets.isEmpty()) return null
            if (offsets.size > 1 && (selected == null || selected !in offsets)) return null
            return time.toInstant(selected?.takeIf { it in offsets } ?: offsets.single())
        }
        val startOffsets = zone.rules.getValidOffsets(start)
        val endOffsets = zone.rules.getValidOffsets(end)
        if (startOffsets.isEmpty() || endOffsets.isEmpty())
            return CalendarTimeResult.Invalid("This clock time does not exist because daylight saving time moves forward. Choose another time.")
        if ((startOffsets.size > 1 && (startOffset == null || startOffset !in startOffsets)) ||
            (endOffsets.size > 1 && (endOffset == null || endOffset !in endOffsets)))
            return CalendarTimeResult.Invalid("This clock time occurs twice. Choose the first or second occurrence for each repeated time.")
        val a = instant(start, startOffset) ?: return CalendarTimeResult.Invalid("Choose a valid start time.")
        val b = instant(end, endOffset) ?: return CalendarTimeResult.Invalid("Choose a valid end time.")
        if (!b.isAfter(a)) return CalendarTimeResult.Invalid("End must be after start. Set the end date for an overnight event.")
        return CalendarTimeResult.Valid(CalendarInterval(a.toEpochMilli(), b.toEpochMilli()))
    }

    fun allDay(start: LocalDate, endExclusive: LocalDate): CalendarTimeResult {
        if (!endExclusive.isAfter(start)) return CalendarTimeResult.Invalid("The last day must be on or after the first day.")
        return CalendarTimeResult.Valid(CalendarInterval(start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), endExclusive.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()))
    }

    fun elapsedLabel(startMillis: Long, endMillis: Long): String {
        val duration = Duration.ofMillis((endMillis - startMillis).coerceAtLeast(0))
        val hours = duration.toHours()
        val minutes = duration.toMinutes() % 60
        return when {
            duration.isZero -> "Point in time"
            hours > 0 && minutes > 0 -> "$hours h $minutes min"
            hours > 0 -> "$hours h"
            duration.toMinutes() > 0 -> "${duration.toMinutes()} min"
            else -> "${duration.seconds} sec"
        }
    }
}

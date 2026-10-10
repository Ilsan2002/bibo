package com.bibo.ui

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class CalendarTimeTest {
    @Test fun multiDayEditPreservesBothDates() {
        val result = CalendarTime.timed(LocalDateTime.parse("2026-10-10T09:00"), LocalDateTime.parse("2026-10-12T10:00"), ZoneOffset.UTC) as CalendarTimeResult.Valid
        assertEquals(49L * 3600_000, result.interval.endMillis - result.interval.startMillis)
    }
    @Test fun springGapIsRejectedBeforeSaving() {
        assertTrue(CalendarTime.timed(LocalDateTime.parse("2026-03-08T02:30"), LocalDateTime.parse("2026-03-08T03:00"), ZoneId.of("America/New_York")) is CalendarTimeResult.Invalid)
    }
    @Test fun repeatedHourRequiresExplicitOffsetAndPreservesElapsedDuration() {
        val start = LocalDateTime.parse("2026-11-01T01:45")
        val end = LocalDateTime.parse("2026-11-01T01:15")
        val zone = ZoneId.of("America/New_York")
        assertTrue(CalendarTime.timed(start, end, zone) is CalendarTimeResult.Invalid)
        assertTrue(CalendarTime.timed(start, end, zone, startOffset = ZoneOffset.ofHours(-4)) is CalendarTimeResult.Invalid)
        assertTrue(CalendarTime.timed(start, end, zone, endOffset = ZoneOffset.ofHours(-5)) is CalendarTimeResult.Invalid)
        assertTrue(CalendarTime.timed(start, end, zone, ZoneOffset.UTC, ZoneOffset.ofHours(-5)) is CalendarTimeResult.Invalid)
        val result = CalendarTime.timed(start, end, zone, ZoneOffset.ofHours(-4), ZoneOffset.ofHours(-5)) as CalendarTimeResult.Valid
        assertEquals(30L * 60_000, result.interval.endMillis - result.interval.startMillis)
    }
    @Test fun equalTimesAreRejectedRatherThanConvertedToOneDay() {
        val dateTime = LocalDateTime.parse("2026-10-10T09:00")
        assertTrue(CalendarTime.timed(dateTime, dateTime, ZoneOffset.UTC) is CalendarTimeResult.Invalid)
    }
    @Test fun allDayUsesUtcExclusiveDateBoundaries() {
        val result = CalendarTime.allDay(LocalDate.parse("2026-10-10"), LocalDate.parse("2026-10-13")) as CalendarTimeResult.Valid
        assertEquals(Instant.parse("2026-10-10T00:00:00Z").toEpochMilli(), result.interval.startMillis)
        assertEquals(Instant.parse("2026-10-13T00:00:00Z").toEpochMilli(), result.interval.endMillis)
    }
    @Test fun overnightHasExplicitEndDate() {
        val result = CalendarTime.timed(LocalDateTime.parse("2026-10-10T23:30"), LocalDateTime.parse("2026-10-11T01:30"), ZoneOffset.UTC) as CalendarTimeResult.Valid
        assertEquals(2L * 3600_000, result.interval.endMillis - result.interval.startMillis)
    }
}

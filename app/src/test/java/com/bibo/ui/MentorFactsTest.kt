package com.bibo.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class MentorFactsTest {
    @Test fun crossMidnightIntervalsAreClipped() {
        val start = Instant.parse("2026-10-09T23:30:00Z").toEpochMilli()
        val middle = Instant.parse("2026-10-10T00:00:00Z").toEpochMilli()
        val end = Instant.parse("2026-10-10T00:30:00Z").toEpochMilli()
        assertEquals(30 * 60_000L, Mentor.clippedDuration(start, end, middle - 86400_000, middle))
        assertEquals(30 * 60_000L, Mentor.clippedDuration(start, end, middle, middle + 86400_000))
        assertEquals(0L, Mentor.clippedDuration(start, end, end, end + 86400_000))
    }
    @Test fun unrecordedHabitsAreUnknownRatherThanMissed() {
        assertEquals("not logged", Mentor.habitLabel(false, 0, 4))
        assertEquals("not done", Mentor.habitLabel(false, 4, 4))
        assertEquals("done", Mentor.habitLabel(true, 0, 4))
    }
    @Test fun defaultLateNightEventHasPositiveDurationOnNextDay() {
        val input = mapOf("date" to "2026-10-10", "start_time" to "23:30", "zone" to "UTC")
        val interval = MentorTools.eventInterval(input)
        assertEquals(60 * 60_000L, interval.second - interval.first)
        assertEquals(Instant.parse("2026-10-11T00:30:00Z").toEpochMilli(), interval.second)
    }
    @Test fun explicitOvernightEventRollsToNextDate() {
        val interval = MentorTools.eventInterval(mapOf("date" to "2026-10-10", "start_time" to "23:30", "end_time" to "01:00", "zone" to "UTC"))
        assertEquals(90 * 60_000L, interval.second - interval.first)
    }
    @Test fun daylightSavingGapIsRejectedInsteadOfMovedSilently() {
        try { MentorTools.eventInterval(mapOf("date" to "2026-03-08", "start_time" to "02:30", "zone" to "America/New_York")); fail("Missing time must be rejected") }
        catch (e: IllegalStateException) { assertTrue(e.message.orEmpty().contains("does not exist")) }
    }
    @Test fun equalClockTimesDoNotBecomeAnUnrequestedWholeDay() {
        try { MentorTools.eventInterval(mapOf("date" to "2026-10-10", "start_time" to "10:00", "end_time" to "10:00", "zone" to "UTC")); fail("An explicit equal end time must be rejected") }
        catch (e: IllegalStateException) { assertTrue(e.message.orEmpty().contains("End must be after")) }
    }
    @Test fun titleOnlyEditPreservesAnExistingRepeatedHourOccurrence() {
        val begin = Instant.parse("2026-11-01T05:30:00Z").toEpochMilli()
        val end = Instant.parse("2026-11-01T06:30:00Z").toEpochMilli()
        assertEquals(begin to end, MentorTools.editedEventInterval(begin, end, false, ZoneId.of("America/New_York"), mapOf("new_title" to "Renamed appointment")))
        val moved = MentorTools.editedEventInterval(begin, end, false, ZoneId.of("America/New_York"), mapOf("new_start_time" to "01:30"))
        assertEquals(begin to end, moved)
    }
    @Test fun editingOnlyEndDateRetainsTheOriginalEndClockTime() {
        val begin = Instant.parse("2026-10-10T10:00:00Z").toEpochMilli(); val end = Instant.parse("2026-10-10T12:00:00Z").toEpochMilli()
        val edited = MentorTools.editedEventInterval(begin, end, false, ZoneId.of("UTC"), mapOf("new_end_date" to "2026-10-11"))
        assertEquals(Instant.parse("2026-10-11T12:00:00Z").toEpochMilli(), edited.second)
    }
    @Test fun movingToARepeatedHourRequiresAnExplicitOffset() {
        val begin = Instant.parse("2026-10-31T14:00:00Z").toEpochMilli(); val end = begin + 3600_000
        try {
            MentorTools.editedEventInterval(begin, end, false, ZoneId.of("America/New_York"), mapOf("new_date" to "2026-11-01", "new_start_time" to "01:30"))
            fail("A new ambiguous time must require the selected occurrence")
        } catch (e: IllegalArgumentException) { assertTrue(e.message.orEmpty().contains("occurs twice")) }
    }
    @Test fun paddedAllDayReadsUseTheDisplayedDateRange() {
        val from = LocalDate.parse("2026-10-10"); val to = from.plusDays(1); val zone = ZoneId.of("America/Los_Angeles")
        val today = Instant.parse("2026-10-10T00:00:00Z").toEpochMilli()
        assertTrue(MentorTools.calendarRangeIncludes(today, today + 86400_000, true, from, to, zone))
        assertFalse(MentorTools.calendarRangeIncludes(today - 86400_000, today, true, from, to, zone))
        assertFalse(MentorTools.calendarRangeIncludes(today + 86400_000, today + 2 * 86400_000, true, from, to, zone))
    }
}

package com.bibo.ui

import org.junit.Assert.*
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime

class VoiceParserTest {
    private val day = LocalDate.of(2026, 10, 10)
    private fun duration(log: ParsedLog) = Duration.between(log.date.atTime(log.start), log.endDate.atTime(log.end)).toMinutes()
    @Test fun sharedSuffixIsOneHour() {
        val log = parseVoiceLog("tennis from 9 to 10pm", LocalTime.NOON, day)
        assertEquals(LocalTime.of(21, 0), log.start)
        assertEquals(60L, duration(log))
    }
    @Test fun explicitOvernightRetainsDatesAndLength() {
        val log = parseVoiceLog("worked from 23:00 to 01:00", LocalTime.of(2, 0), day)
        assertEquals(day.minusDays(1), log.date)
        assertEquals(day, log.endDate)
        assertEquals(120L, duration(log))
    }
    @Test fun midnightDurationIsNotClamped() {
        val log = parseVoiceLog("read for the last 30 minutes", LocalTime.of(0, 15), day)
        assertEquals(day.minusDays(1), log.date)
        assertEquals(LocalTime.of(23, 45), log.start)
        assertEquals(30L, duration(log))
    }
    @Test fun yesterdayStaysOnNamedDate() {
        val log = parseVoiceLog("yesterday tennis from 9 to 10pm", LocalTime.NOON, day)
        assertEquals(day.minusDays(1), log.date)
        assertEquals(60L, duration(log))
    }
    @Test fun namedOvernightStaysOnNamedStartDate() {
        val log = parseVoiceLog("yesterday worked from 23:00 to 01:00", LocalTime.of(2, 0), day)
        assertEquals(day.minusDays(1), log.date)
        assertEquals(day, log.endDate)
        assertEquals(120L, duration(log))
    }
    @Test fun decimalDurationIsSupported() { assertEquals(90L, duration(parseVoiceLog("read for 1.5 hours", LocalTime.NOON, day))) }
    @Test fun invalidDurationAndClockNeedCorrection() {
        assertNotNull(extractActivity("read for 0 minutes", LocalTime.NOON).warning)
        assertTrue(parseVoiceLog("read for 0 minutes", LocalTime.NOON, day).requiresCorrection)
        assertTrue(parseVoiceLog("work from 5 to 5", LocalTime.NOON, day).requiresCorrection)
        assertTrue(parseVoiceLog("2026-02-30 meeting at 3pm", LocalTime.NOON, day).requiresCorrection)
        assertNull(extractActivity("meeting at 29:99", LocalTime.NOON).start)
    }
    @Test fun plannedPointHasPlannedDuration() {
        val result = extractActivity("gym 3pm", LocalTime.of(20, 0), TimeBias.PLAN)
        assertEquals(LocalTime.of(15, 0), result.start)
        assertEquals(LocalTime.of(15, 30), result.end)
    }
}

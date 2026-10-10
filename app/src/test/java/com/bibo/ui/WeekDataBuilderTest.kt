package com.bibo.ui

import com.bibo.data.ActivityBlock
import com.bibo.data.DeviceCalendarEvent
import de.tobiasschuerg.weekview.data.Event
import de.tobiasschuerg.weekview.util.EventOverlapCalculator
import de.tobiasschuerg.weekview.util.TimeSpan
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class WeekDataBuilderTest {
    private fun block(start: String, end: String, id: Long = 1, source: String = "TIMER") = ActivityBlock(id, "Activity", Instant.parse(start).toEpochMilli(), Instant.parse(end).toEpochMilli(), source)
    private fun build(day: String, blocks: List<ActivityBlock> = emptyList(), events: List<DeviceCalendarEvent> = emptyList(), zone: ZoneId = ZoneOffset.UTC) =
        buildCalendar(LocalDate.parse(day), 1, blocks, events, emptyList(), false, zone)

    @Test fun finalMinuteEndsAt24RatherThanMovingEarlier() {
        val result = build("2026-10-10", listOf(block("2026-10-10T23:59:00Z", "2026-10-11T00:00:00Z")))
        val event = result.weekData.getSingleEvents().single()
        assertEquals(LocalTime.of(23, 59), event.timeSpan.start)
        assertEquals(86400.0, event.timeSpan.endSecondExclusive, .000001)
        assertEquals(60L, event.duration.seconds)
    }
    @Test fun repeatedHourSplitsWithoutInventingDuration() {
        val result = build("2026-11-01", listOf(block("2026-11-01T05:45:00Z", "2026-11-01T06:15:00Z")), zone = ZoneId.of("America/New_York"))
        val segments = result.weekData.getSingleEvents()
        assertEquals(2, segments.size)
        assertEquals(30L, segments.sumOf { it.duration.toMinutes() })
        assertEquals(2, segments.map { it.id }.distinct().size)
        assertEquals(1, result.items.values.distinctBy { it.identity }.size)
    }
    @Test fun allDayContinuationRemainsVisibleWithoutFirstDay() {
        val start = Instant.parse("2026-10-10T00:00:00Z").toEpochMilli()
        val end = Instant.parse("2026-10-13T00:00:00Z").toEpochMilli()
        val result = build("2026-10-11", events = listOf(DeviceCalendarEvent(42, "Holiday", start, end, 0, true)))
        assertEquals(1, result.weekData.getMultiDayEvents().size)
        assertEquals(LocalDate.parse("2026-10-12"), result.weekData.getMultiDayEvents().single().lastDate)
    }
    @Test fun completionIsPointRatherThanFifteenMinutesOfWork() {
        val result = build("2026-10-10", listOf(block("2026-10-10T12:00:00Z", "2026-10-10T12:00:00Z", source = "COMPLETION")))
        assertTrue(result.weekData.getSingleEvents().single().isPoint)
        assertEquals(0L, result.items.values.single().endMillis - result.items.values.single().startMillis)
    }
    @Test fun stableProviderIdentitySurvivesInsertedEarlierEvent() {
        val later = DeviceCalendarEvent(2, "Later", Instant.parse("2026-10-10T12:00:00Z").toEpochMilli(), Instant.parse("2026-10-10T13:00:00Z").toEpochMilli(), 0, false)
        val before = later.copy(id = 1, title = "Earlier", begin = later.begin - 3600_000, end = later.end - 3600_000)
        val first = build("2026-10-10", events = listOf(later)).items.entries.single().key
        val second = build("2026-10-10", events = listOf(before, later)).items.entries.single { (it.value as CalendarItem.Device).event.id == 2L }.key
        assertEquals(first, second)
    }
    @Test fun pointVisualCollisionGetsSeparateSlotFromActualInterval() {
        val day = LocalDate.parse("2026-10-10")
        val a = Event.Single(1, day, "Done", "Done", timeSpan = TimeSpan.fromSeconds(9 * 3600.0, 9 * 3600.0 + .001), textColor = 0, backgroundColor = 0, isPoint = true)
        val b = Event.Single(2, day, "Activity", "Activity", timeSpan = TimeSpan(LocalTime.of(9, 1), LocalTime.of(9, 5)), textColor = 0, backgroundColor = 0)
        val layouts = EventOverlapCalculator.calculateEventLayouts(listOf(a, b))
        assertEquals(.5f, layouts.getValue(1).widthFraction, .0001f)
        assertNotEquals(layouts.getValue(1).offsetFraction, layouts.getValue(2).offsetFraction)
    }
    @Test fun overnightSegmentsKeepOneOriginalIdentity() {
        val record = block("2026-10-10T23:30:00Z", "2026-10-11T00:30:00Z")
        val result = buildCalendar(LocalDate.parse("2026-10-10"), 2, listOf(record), emptyList(), emptyList(), false, ZoneOffset.UTC)
        assertEquals(2, result.weekData.getSingleEvents().size)
        assertEquals(1, result.items.values.distinctBy { it.identity }.size)
        assertEquals(60L, result.weekData.getSingleEvents().sumOf { it.duration.toMinutes() })
    }
}

package com.bibo.ui

import com.bibo.data.ActivityBlock
import com.bibo.data.DeviceCalendarEvent
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class CalendarDraftPersistenceTest {
    @Test fun overnightDraftAndSelectedDestinationSurviveSavedState() {
        val request = CalendarEditRequest("New plan", "Original", LocalTime.of(23,0), LocalTime.of(1,0), LocalDate.parse("2026-10-10"), "PLAN", LocalDate.parse("2026-10-11"))
        val draft = CalendarEditorValue("Edited", LocalDateTime.parse("2026-10-10T23:30"), LocalDateTime.parse("2026-10-12T02:30"), false, ZoneOffset.UTC, null, null, 42L)
        val decoded = CalendarDraftPersistence.decode(CalendarDraftPersistence.encode(request, draft))!!
        assertEquals(request.commandId, decoded.first.commandId)
        assertEquals(request, decoded.first)
        assertEquals(draft, decoded.second)
    }
    @Test fun providerExpectedSnapshotAndOffsetsSurviveRestoration() {
        val event = DeviceCalendarEvent(9, "Meeting", 100L, 200L, 0, false, 42, "Work", true, false, "America/New_York")
        val request = CalendarEditRequest("Edit", event.title, LocalTime.of(1,45), LocalTime.of(1,15), LocalDate.parse("2026-11-01"), "PLAN",
            LocalDate.parse("2026-11-01"), zone = ZoneId.of(event.eventZone), startOffset = ZoneOffset.ofHours(-4), endOffset = ZoneOffset.ofHours(-5), editItem = CalendarItem.Device(event))
        assertEquals(request, CalendarDraftPersistence.decode(CalendarDraftPersistence.encode(request, null))!!.first)
    }
    @Test fun localExpectedTimesAreNotReplacedWithCurrentValuesDuringRestoration() {
        val block = ActivityBlock(17, "Old title", 100, 200, "PLAN")
        val request = CalendarEditRequest("Edit", "Old title", null, null, LocalDate.parse("2026-10-10"), "PLAN", editItem = CalendarItem.Block(block))
        val restored = CalendarDraftPersistence.decode(CalendarDraftPersistence.encode(request, null))!!.first.editItem as CalendarItem.Block
        assertEquals(100L, restored.block.startMillis)
        assertEquals(200L, restored.block.endMillis)
        assertEquals("Old title", restored.block.title)
    }
    @Test fun malformedSavedStateCannotProduceAnEditor() { assertNull(CalendarDraftPersistence.decode(listOf("unknown"))) }

    @Test fun localPendingDestinationRemainsLockedAcrossProcessRestoration() {
        val request = CalendarEditRequest("New plan", "Pending", null, null, LocalDate.parse("2026-10-10"), "PLAN", destinationLocked = true)
        val restored = CalendarDraftPersistence.decode(CalendarDraftPersistence.encode(request, null))!!.first
        assertTrue(restored.destinationLocked)
        assertNull(restored.lockedCalendarId)
        assertEquals(request.commandId, restored.commandId)
    }
    @Test fun providerPendingDestinationRemainsLockedAcrossProcessRestoration() {
        val request = CalendarEditRequest("New plan", "Pending", null, null, LocalDate.parse("2026-10-10"), "PLAN", destinationLocked = true, lockedCalendarId = 42)
        val restored = CalendarDraftPersistence.decode(CalendarDraftPersistence.encode(request, null))!!.first
        assertTrue(restored.destinationLocked)
        assertEquals(42L, restored.lockedCalendarId)
        assertEquals(request.commandId, restored.commandId)
    }
    @Test fun previousSavedStateVersionRestoresWithoutInventingAPendingDestination() {
        val request = CalendarEditRequest("New plan", "Unsubmitted", null, null, LocalDate.parse("2026-10-10"), "PLAN")
        val oldPayload = CalendarDraftPersistence.encode(request, null).take(37).toMutableList().also { it[0] = "1" }
        val restored = CalendarDraftPersistence.decode(oldPayload)!!.first
        assertFalse(restored.destinationLocked)
        assertEquals(request.commandId, restored.commandId)
    }
}

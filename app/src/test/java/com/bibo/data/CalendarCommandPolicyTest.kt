package com.bibo.data

import org.junit.Assert.*
import org.junit.Test

class CalendarCommandPolicyTest {
    private val event = DeviceCalendarEvent(1, "Meeting", 100, 200, 0, false, 9, "Work", true, false, "UTC")
    private val request = CalendarWriteRequest(9, "Meeting", 100, 200, eventZone = "UTC", commandId = "persisted-command")
    @Test fun exactSavedCommandMatchesForRetry() { assertTrue(request.matches(event)) }
    @Test fun sameCommandWithChangedTitleCannotSilentlyOverwritePreviousSave() { assertFalse(request.copy(title = "Different").matches(event)) }
    @Test fun selectedDestinationIsPartOfCommandIdentity() { assertFalse(request.copy(calendarId = 42).matches(event)) }
    @Test fun dateAndAllDayMeaningCannotChangeDuringRetry() {
        assertFalse(request.copy(endMillis = 300).matches(event))
        assertFalse(request.copy(allDay = true).matches(event))
        assertFalse(request.copy(eventZone = "America/New_York").matches(event))
    }
    @Test fun recurringAndReadOnlyCapabilitiesRequireExternalMutation() {
        assertFalse(event.copy(recurring = true).canEdit)
        assertFalse(event.copy(writable = false).canEdit)
        assertTrue(event.canEdit)
    }
    @Test fun occurrenceIdentityIsDistinctWithinSameSeries() { assertNotEquals(event.occurrenceId, event.copy(begin = 300, end = 400).occurrenceId) }
}

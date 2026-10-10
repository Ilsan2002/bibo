package com.bibo.data

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID

/** Uses a disposable local calendar; it never changes a user's existing calendar or series. */
@RunWith(AndroidJUnit4::class)
class CalendarProviderRegressionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val account = "bibo-regression-${UUID.randomUUID()}"
    private var calendarId: Long? = null
    private var originalRead = false
    private var originalWrite = false
    private val start = Instant.parse("2026-10-10T09:00:00Z").toEpochMilli()

    @Before fun createFixture() {
        assumeNotNull(context.packageManager.resolveContentProvider(CalendarContract.AUTHORITY, 0))
        originalRead = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        originalWrite = context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.READ_CALENDAR)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.WRITE_CALENDAR)
        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, account)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME, account)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "Bibo regression fixture")
            put(CalendarContract.Calendars.OWNER_ACCOUNT, account)
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, "UTC")
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
        }
        val uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build()
        calendarId = ContentUris.parseId(context.contentResolver.insert(uri, values)!!)
    }

    @After fun cleanupFixture() {
        calendarId?.let { id ->
            val uri = ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, id).buildUpon()
                .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account)
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build()
            context.contentResolver.delete(uri, null, null)
        }
        // Runtime grants remain for this instrumentation batch: revoking kills the test process.
    }

    @Test fun repeatedCreateAndFreshRepositoryRecoverTheSameProviderEvent() {
        val request = CalendarWriteRequest(calendarId!!, "Idempotent appointment", start, start + 3600_000, eventZone = "UTC", commandId = UUID.randomUUID().toString())
        val first = DeviceCalendarRepo(context).createEvent(request) as CalendarResult.Success
        val retry = DeviceCalendarRepo(context).createEvent(request) as CalendarResult.Success
        assertEquals(first.value.eventId, retry.value.eventId)
        val conflictingRetry = DeviceCalendarRepo(context).createEvent(request.copy(title = "Changed after uncertain write")) as CalendarResult.Failure
        assertEquals(CalendarFailure.CONFLICT, conflictingRetry.reason)
    }

    @Test fun recurringOccurrenceCannotDeleteOrRewriteTheSeries() {
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId!!)
            put(CalendarContract.Events.TITLE, "Safe recurring fixture")
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.DURATION, "P3600S")
            put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
            put(CalendarContract.Events.RRULE, "FREQ=DAILY;COUNT=3")
        }
        val id = ContentUris.parseId(context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)!!)
        val repo = DeviceCalendarRepo(context)
        val events = (repo.readInstances(start - 1000, start + 3 * 86400_000) as CalendarResult.Success).value.filter { it.id == id }
        assertEquals(3, events.size)
        assertTrue(events.first().recurring)
        assertEquals(CalendarFailure.RECURRING_UNSUPPORTED, (repo.removeEvent(events.first()) as CalendarResult.Failure).reason)
        assertEquals(CalendarFailure.RECURRING_UNSUPPORTED, (repo.changeEvent(events.first(), CalendarWriteRequest(calendarId!!, "Changed", start, start + 3600_000, eventZone = "UTC")) as CalendarResult.Failure).reason)
        val remaining = (repo.readInstances(start - 1000, start + 3 * 86400_000) as CalendarResult.Success).value.filter { it.id == id }
        assertEquals(3, remaining.size)
        assertTrue(remaining.all { it.title == "Safe recurring fixture" })
    }

    @Test fun staleExpectedSnapshotCannotOverwriteExternalEdit() {
        val repo = DeviceCalendarRepo(context)
        val created = (repo.createEvent(CalendarWriteRequest(calendarId!!, "Original", start, start + 3600_000, eventZone = "UTC", commandId = UUID.randomUUID().toString())) as CalendarResult.Success).value
        val expected = (repo.readInstances(start - 1000, start + 3600_001) as CalendarResult.Success).value.single { it.id == created.eventId }
        context.contentResolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, created.eventId), ContentValues().apply { put(CalendarContract.Events.TITLE, "Externally changed") }, null, null)
        val result = repo.changeEvent(expected, CalendarWriteRequest(calendarId!!, "Stale UI save", start, start + 3600_000, eventZone = "UTC")) as CalendarResult.Failure
        assertEquals(CalendarFailure.CONFLICT, result.reason)
        val current = (repo.readInstances(start - 1000, start + 3600_001) as CalendarResult.Success).value.single { it.id == created.eventId }
        assertEquals("Externally changed", current.title)
    }
}

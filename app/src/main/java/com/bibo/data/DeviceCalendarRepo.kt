package com.bibo.data

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Stable provider identity is the event plus occurrence start, never its position in a query. */
data class DeviceCalendarEvent(
    val id: Long,
    val title: String,
    val begin: Long,
    val end: Long,
    val color: Int,
    val allDay: Boolean,
    val calendarId: Long = 0,
    val calendarName: String = "Device calendar",
    val writable: Boolean = false,
    val recurring: Boolean = false,
    val eventZone: String = ZoneId.systemDefault().id,
) {
    val occurrenceId: String get() = "$id:$begin"
    val canEdit: Boolean get() = writable && !recurring
}

data class WritableCalendar(val id: Long, val name: String, val account: String) {
    val label: String get() = if (account.isBlank()) name else "$name · $account"
}

enum class CalendarFailure {
    READ_PERMISSION, WRITE_PERMISSION, READ_ONLY, NO_CALENDAR, RECURRING_UNSUPPORTED,
    INVALID_TIME, CONFLICT, NOT_FOUND, PROVIDER_UNAVAILABLE, WRITE_UNCERTAIN,
}

sealed interface CalendarResult<out T> {
    data class Success<T>(val value: T) : CalendarResult<T>
    data class Failure(val reason: CalendarFailure, val message: String) : CalendarResult<Nothing>
}
typealias CalendarReadResult<T> = CalendarResult<T>
typealias CalendarWriteResult = CalendarResult<CalendarWriteReceipt>
data class CalendarWriteReceipt(val eventId: Long, val calendarName: String)
data class CalendarWriteRequest(
    val calendarId: Long,
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean = false,
    val eventZone: String = ZoneId.systemDefault().id,
    val commandId: String? = null,
)
internal fun CalendarWriteRequest.matches(event: DeviceCalendarEvent): Boolean =
    event.title == title.trim() && event.begin == startMillis && event.end == endMillis && event.calendarId == calendarId && event.allDay == allDay && event.eventZone == (if (allDay) "UTC" else eventZone)

/** Provider boundary: explicit destination, capabilities, observable reads, and guarded mutations. */
class DeviceCalendarRepo(context: Context) {
    private val context = context.applicationContext
    private val resolver get() = context.contentResolver
    fun hasReadPermission(): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    fun hasWritePermission(): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
    fun hasPermissions(): Boolean = hasReadPermission() && hasWritePermission()

    private fun permissionFailure(write: Boolean = false): CalendarResult.Failure? = when {
        !hasReadPermission() -> CalendarResult.Failure(CalendarFailure.READ_PERMISSION, "Allow calendar read access to load device events.")
        write && !hasWritePermission() -> CalendarResult.Failure(CalendarFailure.WRITE_PERMISSION, "Allow calendar write access to change device events.")
        else -> null
    }

    private fun calendars(): List<CalendarInfo> {
        val projection = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
        return resolver.query(CalendarContract.Calendars.CONTENT_URI, projection, null, null, "${CalendarContract.Calendars._ID} ASC")?.use { c ->
            buildList<CalendarInfo> { while (c.moveToNext()) add(CalendarInfo(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getInt(3) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR, c.getInt(3) >= CalendarContract.Calendars.CAL_ACCESS_EDITOR)) }
        } ?: throw IllegalStateException("Calendar provider did not return calendars")
    }
    private data class CalendarInfo(val id: Long, val name: String, val account: String, val writable: Boolean, val editable: Boolean)

    fun writableCalendars(): CalendarReadResult<List<WritableCalendar>> {
        permissionFailure()?.let { return it }
        return try {
            CalendarResult.Success(calendars().filter { it.writable }.map { WritableCalendar(it.id, it.name, it.account) })
        } catch (_: SecurityException) { permissionFailure() ?: CalendarResult.Failure(CalendarFailure.READ_PERMISSION, "Calendar access was revoked. Enable access in Settings.") }
        catch (_: Exception) { unavailable() }
    }

    fun readInstances(startMillis: Long, endMillis: Long): CalendarReadResult<List<DeviceCalendarEvent>> {
        permissionFailure()?.let { return it }
        if (endMillis <= startMillis) return CalendarResult.Failure(CalendarFailure.INVALID_TIME, "The calendar range must have a positive duration.")
        return try {
            val calendarMap = calendars().associateBy { it.id }
            val projection = arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.DISPLAY_COLOR,
                CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.CALENDAR_ID,
                CalendarContract.Instances.EVENT_TIMEZONE, CalendarContract.Instances.RRULE, CalendarContract.Instances.RDATE, CalendarContract.Instances.ORIGINAL_ID, CalendarContract.Instances.ORIGINAL_SYNC_ID)
            // All-day timestamps are UTC dates. Pad the query so local-midnight windows cannot omit them.
            val paddedStart = startMillis - 24 * 3600_000L
            val paddedEnd = endMillis + 24 * 3600_000L
            val items = CalendarContract.Instances.query(resolver, projection, paddedStart, paddedEnd)?.use { c ->
                buildList<DeviceCalendarEvent> {
                    while (c.moveToNext()) {
                        val calendar = calendarMap[c.getLong(6)]
                        val begin = c.getLong(2)
                        val end = c.getLong(3)
                        val allDay = c.getInt(5) == 1
                        if (!allDay && (begin >= endMillis || (if (end == begin) end < startMillis else end <= startMillis))) continue
                        add(DeviceCalendarEvent(c.getLong(0), c.getString(1) ?: "(untitled)", begin, end,
                            c.getInt(4), allDay, c.getLong(6), calendar?.name ?: "Device calendar",
                            calendar?.editable == true, !c.getString(8).isNullOrBlank() || !c.getString(9).isNullOrBlank() || !c.isNull(10) || !c.getString(11).isNullOrBlank(),
                            c.getString(7) ?: ZoneId.systemDefault().id))
                    }
                }
            } ?: throw IllegalStateException("Calendar provider did not return events")
            CalendarResult.Success(items.sortedWith(compareBy<DeviceCalendarEvent> { it.begin }.thenBy { it.id }))
        } catch (_: SecurityException) { permissionFailure() ?: CalendarResult.Failure(CalendarFailure.READ_PERMISSION, "Calendar access was revoked. Enable access in Settings.") }
        catch (_: Exception) { unavailable() }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observeInstances(startMillis: Long, endMillis: Long): Flow<CalendarReadResult<List<DeviceCalendarEvent>>> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { trySend(Unit) }
        }
        try {
            resolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer)
            trySend(Unit)
        } catch (_: Exception) { trySend(Unit) }
        awaitClose { runCatching { resolver.unregisterContentObserver(observer) } }
    }.conflate().mapLatest { withContext(Dispatchers.IO) { readInstances(startMillis, endMillis) } }

    /** Compatibility read API. UI uses the result API so an empty agenda cannot hide a failure. */
    fun queryInstances(startMillis: Long, endMillis: Long): List<DeviceCalendarEvent> =
        (readInstances(startMillis, endMillis) as? CalendarResult.Success)?.value.orEmpty()

    fun createEvent(request: CalendarWriteRequest): CalendarWriteResult = synchronized(createLock) {
        createEventLocked(request)
    }

    private fun createEventLocked(request: CalendarWriteRequest): CalendarWriteResult {
        permissionFailure(write = true)?.let { return it }
        validate(request)?.let { return it }
        return try {
            val calendar = calendars().firstOrNull { it.id == request.calendarId }
                ?: return CalendarResult.Failure(CalendarFailure.NO_CALENDAR, "The selected calendar is no longer available. Choose another destination.")
            if (!calendar.writable) return readOnly()
            request.commandId?.let { commandId ->
                val existing = findCommandEvent(commandId)
                if (existing != null) return commandReceipt(existing, request)
            }
            val uri = resolver.insert(CalendarContract.Events.CONTENT_URI, values(request, includeCommandTag = true)) ?: return unavailable()
            CalendarResult.Success(CalendarWriteReceipt(ContentUris.parseId(uri), calendar.name))
        } catch (_: SecurityException) { permissionFailure(write = true) ?: readOnly() }
        catch (_: Exception) {
            val recovered = request.commandId?.let { runCatching { findCommandEvent(it) }.getOrNull() }
            if (recovered != null) commandReceipt(recovered, request) else uncertain()
        }
    }

    fun changeEvent(expectedEvent: DeviceCalendarEvent, request: CalendarWriteRequest): CalendarWriteResult {
        permissionFailure(write = true)?.let { return it }
        validate(request)?.let { return it }
        return guardedMutation(expectedEvent) { current ->
            if (request.calendarId != current.calendarId) return@guardedMutation CalendarResult.Failure(CalendarFailure.CONFLICT, "Changing an event's calendar is not supported here. Open it in its calendar app.")
            val count = resolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, current.id), values(request), unchangedSelection, unchangedArgs(current))
            if (count > 0) CalendarResult.Success(CalendarWriteReceipt(current.id, current.calendarName)) else CalendarResult.Failure(CalendarFailure.CONFLICT, "This event changed before the operation finished. Reload it before trying again.")
        }
    }

    fun removeEvent(expectedEvent: DeviceCalendarEvent): CalendarWriteResult {
        permissionFailure(write = true)?.let { return it }
        return guardedMutation(expectedEvent) { current ->
            val count = resolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, current.id), unchangedSelection, unchangedArgs(current))
            if (count > 0) CalendarResult.Success(CalendarWriteReceipt(current.id, current.calendarName)) else CalendarResult.Failure(CalendarFailure.CONFLICT, "This event changed before the operation finished. Reload it before trying again.")
        }
    }

    private fun guardedMutation(expected: DeviceCalendarEvent, operation: (DeviceCalendarEvent) -> CalendarWriteResult): CalendarWriteResult {
        return try {
        if (expected.recurring) recurring()
        else {
            val current = readEvent(expected.id) ?: return CalendarResult.Failure(CalendarFailure.NOT_FOUND, "This event was removed. Refresh the calendar.")
            when {
                current.recurring -> recurring()
                !current.writable -> readOnly()
                current.title != expected.title || current.begin != expected.begin || current.end != expected.end || current.allDay != expected.allDay || current.calendarId != expected.calendarId || current.eventZone != expected.eventZone ->
                    CalendarResult.Failure(CalendarFailure.CONFLICT, "This event changed in another app. Reload it before editing or deleting.")
                else -> operation(current)
            }
        }
    } catch (_: SecurityException) { permissionFailure(write = true) ?: readOnly() }
    catch (_: Exception) { uncertain() }
    }

    private val unchangedSelection = "COALESCE(${CalendarContract.Events.TITLE}, '(untitled)')=? AND ${CalendarContract.Events.DTSTART}=? AND ${CalendarContract.Events.DTEND}=? AND ${CalendarContract.Events.ALL_DAY}=? AND ${CalendarContract.Events.CALENDAR_ID}=? AND COALESCE(${CalendarContract.Events.EVENT_TIMEZONE},?)=? AND (${CalendarContract.Events.RRULE} IS NULL OR ${CalendarContract.Events.RRULE}='') AND (${CalendarContract.Events.RDATE} IS NULL OR ${CalendarContract.Events.RDATE}='') AND ${CalendarContract.Events.ORIGINAL_ID} IS NULL AND (${CalendarContract.Events.ORIGINAL_SYNC_ID} IS NULL OR ${CalendarContract.Events.ORIGINAL_SYNC_ID}='')"
    private fun unchangedArgs(event: DeviceCalendarEvent) = arrayOf(event.title, event.begin.toString(), event.end.toString(), if (event.allDay) "1" else "0", event.calendarId.toString(), event.eventZone, event.eventZone)

    private fun readEvent(eventId: Long): DeviceCalendarEvent? {
        val projection = arrayOf(CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND,
            CalendarContract.Events.ALL_DAY, CalendarContract.Events.CALENDAR_ID, CalendarContract.Events.EVENT_TIMEZONE,
            CalendarContract.Events.RRULE, CalendarContract.Events.RDATE, CalendarContract.Events.DISPLAY_COLOR, CalendarContract.Events.ORIGINAL_ID, CalendarContract.Events.ORIGINAL_SYNC_ID)
        val calendarMap = calendars().associateBy { it.id }
        return resolver.query(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId), projection, null, null, null)?.use { c ->
            if (!c.moveToFirst()) null else {
                val calendar = calendarMap[c.getLong(4)]
                DeviceCalendarEvent(eventId, c.getString(0) ?: "(untitled)", c.getLong(1), c.getLong(2), c.getInt(8), c.getInt(3) == 1,
                    c.getLong(4), calendar?.name ?: "Device calendar", calendar?.editable == true,
                    !c.getString(6).isNullOrBlank() || !c.getString(7).isNullOrBlank() || !c.isNull(9) || !c.getString(10).isNullOrBlank(), c.getString(5) ?: ZoneId.systemDefault().id)
            }
        }
    }

    private fun validate(request: CalendarWriteRequest): CalendarResult.Failure? {
        if (request.title.isBlank()) return CalendarResult.Failure(CalendarFailure.INVALID_TIME, "Enter an event title.")
        if (request.endMillis <= request.startMillis) return CalendarResult.Failure(CalendarFailure.INVALID_TIME, "End must be after start.")
        if (runCatching { ZoneId.of(request.eventZone) }.isFailure) return CalendarResult.Failure(CalendarFailure.INVALID_TIME, "The event timezone is invalid.")
        if (request.allDay && (Instant.ofEpochMilli(request.startMillis).atZone(ZoneOffset.UTC).toLocalTime() != java.time.LocalTime.MIDNIGHT || Instant.ofEpochMilli(request.endMillis).atZone(ZoneOffset.UTC).toLocalTime() != java.time.LocalTime.MIDNIGHT))
            return CalendarResult.Failure(CalendarFailure.INVALID_TIME, "All-day events must use UTC date boundaries.")
        return null
    }
    private fun commandUri(commandId: String) = android.net.Uri.Builder().scheme("bibo").authority("calendar-command").appendPath(commandId).build().toString()
    private fun findCommandEvent(commandId: String): DeviceCalendarEvent? {
        val projection = arrayOf(CalendarContract.Events._ID)
        val cursor = resolver.query(CalendarContract.Events.CONTENT_URI, projection,
            "${CalendarContract.Events.CUSTOM_APP_PACKAGE}=? AND ${CalendarContract.Events.CUSTOM_APP_URI}=? AND ${CalendarContract.Events.DELETED}=0",
            arrayOf(context.packageName, commandUri(commandId)), null) ?: throw IllegalStateException("Calendar provider did not return command state")
        val eventId = cursor.use { if (it.moveToFirst()) it.getLong(0) else null } ?: return null
        return readEvent(eventId)
    }
    private fun commandReceipt(event: DeviceCalendarEvent, request: CalendarWriteRequest): CalendarWriteResult =
        if (request.matches(event))
            CalendarResult.Success(CalendarWriteReceipt(event.id, event.calendarName))
        else CalendarResult.Failure(CalendarFailure.CONFLICT, "This save was already applied with different details. Open the saved appointment to change it.")
    private fun values(request: CalendarWriteRequest, includeCommandTag: Boolean = false) = ContentValues().apply {
        if (includeCommandTag && request.commandId != null) {
            put(CalendarContract.Events.CUSTOM_APP_PACKAGE, context.packageName)
            put(CalendarContract.Events.CUSTOM_APP_URI, commandUri(request.commandId))
        }
        put(CalendarContract.Events.TITLE, request.title.trim())
        put(CalendarContract.Events.DTSTART, request.startMillis)
        put(CalendarContract.Events.DTEND, request.endMillis)
        put(CalendarContract.Events.CALENDAR_ID, request.calendarId)
        put(CalendarContract.Events.ALL_DAY, if (request.allDay) 1 else 0)
        put(CalendarContract.Events.EVENT_TIMEZONE, if (request.allDay) "UTC" else request.eventZone)
    }
    private fun uncertain() = CalendarResult.Failure(CalendarFailure.WRITE_UNCERTAIN, "The calendar provider did not confirm this write. Check the selected calendar before trying again to avoid duplicates.")
    private fun unavailable() = CalendarResult.Failure(CalendarFailure.PROVIDER_UNAVAILABLE, "The calendar provider could not finish this operation. Your changes were not saved; try again.")
    private fun readOnly() = CalendarResult.Failure(CalendarFailure.READ_ONLY, "This calendar is read-only. Open it in its calendar app.")
    private fun recurring() = CalendarResult.Failure(CalendarFailure.RECURRING_UNSUPPORTED, "Open this repeating event in its calendar app to choose one occurrence or the entire series.")

    // Guarded compatibility methods for background/mentor callers. No automatic provider/local fallback.
    fun insertEvent(title: String, startMillis: Long, endMillis: Long): Boolean {
        val destinations = (writableCalendars() as? CalendarResult.Success)?.value.orEmpty()
        if (destinations.size != 1) return false
        return createEvent(CalendarWriteRequest(destinations.single().id, title, startMillis, endMillis)) is CalendarResult.Success
    }
    fun updateEvent(eventId: Long, title: String, startMillis: Long, endMillis: Long): Boolean { return try {
        val event = readEvent(eventId) ?: return false
        changeEvent(event, CalendarWriteRequest(event.calendarId, title, startMillis, endMillis, event.allDay, event.eventZone)) is CalendarResult.Success
    } catch (_: Exception) { false } }
    fun deleteEvent(eventId: Long): Boolean { return try {
        val event = readEvent(eventId) ?: return false
        removeEvent(event) is CalendarResult.Success
    } catch (_: Exception) { false } }

    private companion object { val createLock = Any() }

    fun viewEventIntent(eventId: Long, begin: Long? = null, end: Long? = null) = android.content.Intent(android.content.Intent.ACTION_VIEW,
        ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)).apply {
        begin?.let { putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, it) }
        end?.let { putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it) }
    }
}

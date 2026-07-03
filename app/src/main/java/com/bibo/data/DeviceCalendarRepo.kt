package com.bibo.data

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.util.TimeZone

data class DeviceCalendarEvent(
    val id: Long,
    val title: String,
    val begin: Long,
    val end: Long,
    val color: Int,
    val allDay: Boolean,
)

/**
 * Reads/writes the phone's calendar store (CalendarContract), which syncs
 * with the user's Google account — no OAuth needed.
 */
class DeviceCalendarRepo(private val context: Context) {

    fun hasPermissions(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    fun queryInstances(startMillis: Long, endMillis: Long): List<DeviceCalendarEvent> {
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.DISPLAY_COLOR,
            CalendarContract.Instances.ALL_DAY,
        )
        val out = mutableListOf<DeviceCalendarEvent>()
        runCatching {
            CalendarContract.Instances.query(
                context.contentResolver, projection, startMillis, endMillis
            )?.use { c ->
                while (c.moveToNext()) {
                    out += DeviceCalendarEvent(
                        id = c.getLong(0),
                        title = c.getString(1) ?: "(untitled)",
                        begin = c.getLong(2),
                        end = c.getLong(3),
                        color = c.getInt(4),
                        allDay = c.getInt(5) == 1,
                    )
                }
            }
        }
        return out
    }

    fun insertEvent(title: String, startMillis: Long, endMillis: Long): Boolean {
        val calId = primaryCalendarId() ?: return false
        val values = ContentValues().apply {
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, startMillis)
            put(CalendarContract.Events.DTEND, endMillis)
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }
        return runCatching {
            context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
        }.getOrNull() != null
    }

    /**
     * Updates a non-recurring event's title/time. Refuses recurring events: naively
     * writing DTSTART+DTEND onto a series with an RRULE corrupts its Google sync (a
     * recurring event must use DTSTART+DURATION with a null DTEND, and single-instance
     * edits must go through CONTENT_EXCEPTION_URI). Returns false if it declined.
     */
    fun updateEvent(eventId: Long, title: String, startMillis: Long, endMillis: Long): Boolean {
        if (isRecurring(eventId)) return false
        val values = ContentValues().apply {
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, startMillis)
            put(CalendarContract.Events.DTEND, endMillis)
        }
        return runCatching {
            context.contentResolver.update(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
                values, null, null,
            ) > 0
        }.getOrDefault(false)
    }

    private fun isRecurring(eventId: Long): Boolean = runCatching {
        context.contentResolver.query(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
            arrayOf(CalendarContract.Events.RRULE, CalendarContract.Events.RDATE),
            null, null, null,
        )?.use { c ->
            if (c.moveToFirst()) {
                !c.getString(0).isNullOrBlank() || !c.getString(1).isNullOrBlank()
            } else {
                false
            }
        } ?: false
    }.getOrDefault(false)

    fun deleteEvent(eventId: Long): Boolean =
        runCatching {
            context.contentResolver.delete(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
                null,
                null,
            ) > 0
        }.getOrDefault(false)

    fun viewEventIntent(eventId: Long) =
        android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
        )

    private fun primaryCalendarId(): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.VISIBLE,
        )
        runCatching {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI, projection, null, null, null
            )?.use { c ->
                var fallback: Long? = null
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val primary = c.getInt(1) == 1
                    val visible = c.getInt(2) == 1
                    if (primary) return id
                    if (visible && fallback == null) fallback = id
                }
                return fallback
            }
        }
        return null
    }
}

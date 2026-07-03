package com.bibo.ui

import androidx.core.graphics.ColorUtils
import com.bibo.data.ActivityBlock
import com.bibo.data.DeviceCalendarEvent
import com.bibo.data.UsageSession
import de.tobiasschuerg.weekview.data.Event
import de.tobiasschuerg.weekview.data.LocalDateRange
import de.tobiasschuerg.weekview.data.WeekData
import de.tobiasschuerg.weekview.util.TimeSpan
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

private const val FALLBACK_EVENT_COLOR = 0xFF1A73E8.toInt()

/** What a rendered calendar block actually is, for the detail sheet. */
sealed interface CalendarItem {
    data class Device(val event: DeviceCalendarEvent) : CalendarItem
    data class Block(val block: ActivityBlock) : CalendarItem
    data class Usage(val session: UsageSession) : CalendarItem
}

data class BuiltCalendar(
    val weekData: WeekData,
    val items: Map<Long, CalendarItem>,
)

private const val BLOCK_ID_BASE = 1_000_000_000L
private const val USAGE_ID_BASE = 2_000_000_000L
private const val DEVICE_ID_BASE = 3_000_000_000L

fun sourceColor(source: String): Int = when (source) {
    "TIMER" -> 0xFF2E7D32.toInt()
    "TODO" -> 0xFF6A1B9A.toInt()
    "VOICE" -> 0xFFEF6C00.toInt()
    else -> 0xFF1565C0.toInt()
}

fun sourceLabel(source: String): String = when (source) {
    "TIMER" -> "Timer session"
    "TODO" -> "Completed task"
    "VOICE" -> "Voice log"
    else -> "Event"
}

fun textColorFor(background: Int): Int =
    if (android.graphics.Color.luminance(background) > 0.5f) {
        0xFF000000.toInt()
    } else {
        0xFFFFFFFF.toInt()
    }

fun buildCalendar(
    startDate: LocalDate,
    days: Int,
    blocks: List<ActivityBlock>,
    deviceEvents: List<DeviceCalendarEvent>,
    usageSessions: List<UsageSession>,
    darkTheme: Boolean,
): BuiltCalendar {
    val range = LocalDateRange(startDate, startDate.plusDays(days - 1L))
    val data = WeekData(range, LocalTime.of(7, 0), LocalTime.of(22, 0))
    val items = HashMap<Long, CalendarItem>()

    deviceEvents.forEachIndexed { index, ev ->
        val id = DEVICE_ID_BASE + index
        if (ev.allDay) {
            // CalendarContract stores all-day event times in UTC
            val date = Instant.ofEpochMilli(ev.begin).atZone(ZoneOffset.UTC).toLocalDate()
            if (range.contains(date)) {
                val bg = normalizeColor(ev.color)
                items[id] = CalendarItem.Device(ev)
                data.add(
                    Event.AllDay(
                        id = id,
                        date = date,
                        title = ev.title,
                        shortTitle = ev.title,
                        textColor = textColorFor(bg),
                        backgroundColor = bg,
                    )
                )
            }
        } else {
            val bg = normalizeColor(ev.color)
            if (addClipped(data, range, id, ev.title, ev.begin, ev.end, bg, textColorFor(bg))) {
                items[id] = CalendarItem.Device(ev)
            }
        }
    }

    blocks.forEach { b ->
        val id = BLOCK_ID_BASE + b.id
        val bg = b.color ?: sourceColor(b.source)
        if (addClipped(data, range, id, b.title, b.startMillis, b.endMillis, bg, textColorFor(bg))) {
            items[id] = CalendarItem.Block(b)
        }
    }

    usageSessions.forEachIndexed { index, session ->
        val id = USAGE_ID_BASE + index
        // Usage looks distinct from planned events: translucent fill + tinted text
        val fill = ColorUtils.setAlphaComponent(session.color, 0x4D)
        val text =
            if (darkTheme) {
                ColorUtils.blendARGB(session.color, 0xFFFFFFFF.toInt(), 0.7f)
            } else {
                ColorUtils.blendARGB(session.color, 0xFF000000.toInt(), 0.5f)
            }
        if (addClipped(data, range, id, session.label, session.start, session.end, fill, text)) {
            items[id] = CalendarItem.Usage(session)
        }
    }

    return BuiltCalendar(data, items)
}

private fun normalizeColor(color: Int): Int =
    if (color == 0) FALLBACK_EVENT_COLOR else (color or 0xFF000000.toInt())

/**
 * Adds a [startMillis, endMillis) interval, clipped to each visible day since
 * TimeSpan cannot cross midnight. Returns true if at least one segment was added.
 */
private fun addClipped(
    data: WeekData,
    range: LocalDateRange,
    id: Long,
    title: String,
    startMillis: Long,
    endMillis: Long,
    backgroundColor: Int,
    textColor: Int,
): Boolean {
    val zone = ZoneId.systemDefault()
    var added = false
    for (date in range) {
        val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val s = maxOf(startMillis, dayStart)
        val e = minOf(endMillis, dayEnd)
        if (e <= s) continue

        var st = Instant.ofEpochMilli(s).atZone(zone).toLocalTime()
        var et = if (e == dayEnd) {
            LocalTime.of(23, 59)
        } else {
            Instant.ofEpochMilli(e).atZone(zone).toLocalTime()
        }
        if (st >= LocalTime.of(23, 58)) st = LocalTime.of(23, 58)
        if (!et.isAfter(st)) et = st.plusMinutes(1)

        data.add(
            Event.Single(
                id = id,
                date = date,
                title = title,
                shortTitle = title.take(14),
                timeSpan = TimeSpan(st, et),
                textColor = textColor,
                backgroundColor = backgroundColor,
            )
        )
        added = true
    }
    return added
}

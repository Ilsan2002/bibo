package com.bibo.ui

import androidx.core.graphics.ColorUtils
import com.bibo.data.ActivityBlock
import com.bibo.data.DeviceCalendarEvent
import com.bibo.data.UsageSession
import de.tobiasschuerg.weekview.data.Event
import de.tobiasschuerg.weekview.data.LocalDateRange
import de.tobiasschuerg.weekview.data.WeekData
import de.tobiasschuerg.weekview.util.TimeSpan
import java.time.*

private const val FALLBACK_EVENT_COLOR = 0xFF1A73E8.toInt()

/** Authoritative source objects remain separate from disposable, clipped rendering segments. */
sealed interface CalendarItem {
    data class Device(val event: DeviceCalendarEvent) : CalendarItem
    data class Block(val block: ActivityBlock) : CalendarItem
    data class Usage(val session: UsageSession) : CalendarItem
}
val CalendarItem.identity: String get() = when (this) {
    is CalendarItem.Device -> "device:${event.occurrenceId}"
    is CalendarItem.Block -> "block:${block.id}"
    is CalendarItem.Usage -> "usage:${if (session.id > 0) session.id else session.start}:${session.packageName}"
}
val CalendarItem.title: String get() = when (this) {
    is CalendarItem.Device -> event.title
    is CalendarItem.Block -> block.title
    is CalendarItem.Usage -> session.label
}
val CalendarItem.startMillis: Long get() = when (this) {
    is CalendarItem.Device -> event.begin
    is CalendarItem.Block -> block.startMillis
    is CalendarItem.Usage -> session.start
}
val CalendarItem.endMillis: Long get() = when (this) {
    is CalendarItem.Device -> event.end
    is CalendarItem.Block -> block.endMillis
    is CalendarItem.Usage -> session.end
}
val CalendarItem.isAllDay: Boolean get() = when (this) {
    is CalendarItem.Device -> event.allDay
    is CalendarItem.Block -> block.allDay
    is CalendarItem.Usage -> false
}
val CalendarItem.isPlan: Boolean get() = this is CalendarItem.Device || this is CalendarItem.Block && block.source in setOf("PLAN", "MANUAL")
val CalendarItem.sourceDescription: String get() = when (this) {
    is CalendarItem.Device -> event.calendarName
    is CalendarItem.Block -> sourceLabel(block.source)
    is CalendarItem.Usage -> "Observed app usage"
}

data class BuiltCalendar(val weekData: WeekData, val items: Map<Long, CalendarItem>)

fun sourceColor(source: String): Int = when (source) {
    "TIMER", "TASK_TIMER" -> 0xFF2E7D32.toInt()
    "FOCUS" -> 0xFF00695C.toInt()
    "TODO", "COMPLETION" -> 0xFF6A1B9A.toInt()
    "VOICE" -> 0xFFEF6C00.toInt()
    else -> 0xFF1565C0.toInt()
}
fun sourceLabel(source: String): String = when (source) {
    "TIMER" -> "Measured timer session"
    "TASK_TIMER" -> "Measured task session"
    "FOCUS" -> "Focus session"
    "TODO" -> "Legacy task log"
    "COMPLETION" -> "Task completed · point in time"
    "VOICE" -> "Reported activity · voice log"
    "PLAN", "MANUAL" -> "Local plan · Bibo only"
    else -> "Activity log"
}
fun textColorFor(background: Int): Int = if (android.graphics.Color.luminance(background) > 0.5f) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
private fun normalizeColor(color: Int): Int = if (color == 0) FALLBACK_EVENT_COLOR else (color or 0xFF000000.toInt())

fun buildCalendar(startDate: LocalDate, days: Int, blocks: List<ActivityBlock>, deviceEvents: List<DeviceCalendarEvent>,
                  usageSessions: List<UsageSession>, darkTheme: Boolean, zone: ZoneId = ZoneId.systemDefault()): BuiltCalendar {
    val range = LocalDateRange(startDate, startDate.plusDays(days - 1L))
    val data = WeekData(range, LocalTime.MIN, LocalTime.MAX)
    val items = LinkedHashMap<Long, CalendarItem>()
    fun add(item: CalendarItem, bg: Int, fg: Int) {
        if (item.isAllDay) {
            val first = Instant.ofEpochMilli(item.startMillis).atZone(ZoneOffset.UTC).toLocalDate()
            val last = Instant.ofEpochMilli(item.endMillis).minusNanos(1).atZone(ZoneOffset.UTC).toLocalDate()
            if (last < range.start || first > range.endInclusive || item.endMillis <= item.startMillis) return
            val id = stableCalendarId(item.identity)
            items[id] = item
            if (first == last) data.add(Event.AllDay(id, first, item.title, item.title, fg, bg))
            else data.add(Event.MultiDay(id, first, item.title, item.title, last, fg, bg))
            return
        }
        val fill = if (item.isPlan) ColorUtils.setAlphaComponent(bg, 0x26) else bg
        val ink = if (item.isPlan) ColorUtils.blendARGB(bg, if (darkTheme) 0xFFFFFFFF.toInt() else 0xFF000000.toInt(), .55f) else fg
        addClipped(data, range, item, fill, ink, zone, items, if (item.isPlan) bg else null)
    }
    deviceEvents.forEach { val item = CalendarItem.Device(it); val bg = normalizeColor(it.color); add(item, bg, textColorFor(bg)) }
    blocks.forEach { val item = CalendarItem.Block(it); val bg = normalizeColor(it.color ?: sourceColor(it.source)); add(item, bg, textColorFor(bg)) }
    usageSessions.forEach { session ->
        val fill = ColorUtils.setAlphaComponent(session.color, 0x4D)
        val text = ColorUtils.blendARGB(session.color, if (darkTheme) 0xFFFFFFFF.toInt() else 0xFF000000.toInt(), if (darkTheme) 0.7f else 0.5f)
        add(CalendarItem.Usage(session), fill, text)
    }
    return BuiltCalendar(data, items)
}

/** Deterministic source identities replace query indices; each clipped segment has its own key. */
internal fun stableCalendarId(identity: String): Long {
    var hash = -3750763034362895579L
    identity.forEach { hash = (hash xor it.code.toLong()) * 1099511628211L }
    return hash
}

/** Numeric day coordinates include 24:00. Offset transitions split a real interval instead of inventing duration. */
private fun addClipped(data: WeekData, range: LocalDateRange, item: CalendarItem, backgroundColor: Int, textColor: Int,
                       zone: ZoneId, items: MutableMap<Long, CalendarItem>, borderColor: Int?) {
    val isPoint = item.endMillis == item.startMillis
    if (item.endMillis < item.startMillis) return
    for (date in range) {
        val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        if (isPoint) {
            if (item.startMillis < dayStart || item.startMillis >= dayEnd) continue
            val time = Instant.ofEpochMilli(item.startMillis).atZone(zone).toLocalTime()
            val second = time.toSecondOfDay() + time.nano / 1e9
            val id = stableCalendarId("${item.identity}:$date:point")
            items[id] = item
            data.add(Event.Single(id, date, item.title, item.title, subTitle = item.sourceDescription,
                timeSpan = TimeSpan.fromSeconds(second, minOf(86400.0, second + 0.001)), textColor = textColor,
                backgroundColor = backgroundColor, isPoint = true, borderColor = borderColor))
            continue
        }
        var cursor = maxOf(item.startMillis, dayStart)
        val end = minOf(item.endMillis, dayEnd)
        if (end <= cursor) continue
        var segmentIndex = 0
        while (cursor < end) {
            val instant = Instant.ofEpochMilli(cursor)
            val transition = zone.rules.nextTransition(instant)
            val boundary = minOf(end, transition?.instant?.toEpochMilli()?.takeIf { it > cursor } ?: end)
            val time = instant.atZone(zone).toLocalTime()
            val startSecond = time.toSecondOfDay() + time.nano / 1e9
            val endSecond = minOf(86400.0, startSecond + (boundary - cursor) / 1000.0)
            if (endSecond > startSecond) {
                val id = stableCalendarId("${item.identity}:$date:$segmentIndex")
                items[id] = item
                val continuation = if (cursor > item.startMillis || boundary < item.endMillis) " · continues" else ""
                data.add(Event.Single(id, date, item.title, item.title.take(24), subTitle = item.sourceDescription + continuation,
                    timeSpan = TimeSpan.fromSeconds(startSecond, endSecond), textColor = textColor, backgroundColor = backgroundColor, borderColor = borderColor))
            }
            cursor = boundary
            segmentIndex++
        }
    }
}

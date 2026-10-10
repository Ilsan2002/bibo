package de.tobiasschuerg.weekview.util

import de.tobiasschuerg.weekview.data.Event
import java.time.LocalDate

internal data class AllDayHeaderLayout(val rows: List<List<Event>>, val hidden: List<Event>)

internal fun Event.allDayLastDate(): LocalDate = when (this) {
    is Event.AllDay -> date
    is Event.MultiDay -> lastDate
    is Event.Single -> error("Timed events do not belong in the all-day header")
}

/** Single-day and spanning events share one row budget and retain their original click identity. */
internal fun allDayHeaderLayout(events: List<Event>, firstDay: LocalDate, lastDay: LocalDate, maxRows: Int): AllDayHeaderLayout {
    val visibleEvents = events.filter { it !is Event.Single && it.date <= lastDay && it.allDayLastDate() >= firstDay }
        .distinctBy { it.id }
        .sortedWith(compareBy<Event> { maxOf(firstDay, it.date) }.thenByDescending { it.allDayLastDate() }.thenBy { it.id })
    val rows = mutableListOf<MutableList<Event>>()
    visibleEvents.forEach { event ->
        val start = maxOf(firstDay, event.date)
        val end = minOf(lastDay, event.allDayLastDate())
        val row = rows.firstOrNull { existing -> existing.all { other ->
            end < maxOf(firstDay, other.date) || start > minOf(lastDay, other.allDayLastDate())
        } }
        if (row == null) rows.add(mutableListOf(event)) else row.add(event)
    }
    val allowed = maxRows.coerceAtLeast(0)
    return AllDayHeaderLayout(rows.take(allowed), rows.drop(allowed).flatten())
}

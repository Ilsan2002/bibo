package de.tobiasschuerg.weekview.util

import de.tobiasschuerg.weekview.data.Event
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class AllDayHeaderLayoutTest {
    private val first = LocalDate.parse("2026-10-10")
    private val last = first.plusDays(2)
    private fun single(id: Long, day: LocalDate = first) = Event.AllDay(id, day, "Single $id", "", 0, 0)
    private fun spanning(id: Long) = Event.MultiDay(id, first.minusDays(1), "Span $id", "", last.plusDays(1), 0, 0)

    @Test fun overlappingSingleAndMultiDayItemsShareOneStrictCap() {
        val events = (1L..10L).map(::spanning) + (11L..20L).map { single(it) }
        val layout = allDayHeaderLayout(events, first, last, 3)
        assertEquals(3, layout.rows.size)
        assertEquals(17, layout.hidden.size)
        assertEquals(events.map { it.id }.toSet(), (layout.rows.flatten() + layout.hidden).map { it.id }.toSet())
    }
    @Test fun zeroRowsKeepsEveryItemReachableInOverflow() {
        val events = listOf(single(1), spanning(2))
        val layout = allDayHeaderLayout(events, first, last, 0)
        assertTrue(layout.rows.isEmpty())
        assertEquals(events.map { it.id }.toSet(), layout.hidden.map { it.id }.toSet())
    }
    @Test fun NonoverlappingDaysFitInTheSameRow() {
        val events = listOf(single(1), single(2, first.plusDays(1)), single(3, last))
        val layout = allDayHeaderLayout(events, first, last, 1)
        assertEquals(3, layout.rows.single().size)
        assertTrue(layout.hidden.isEmpty())
    }
    @Test fun continuationUsesTheOriginalIdentityAndExcludesItemsOutsideWindow() {
        val continued = spanning(1)
        val layout = allDayHeaderLayout(listOf(continued, single(2, first.minusDays(2))), first, last, 3)
        assertSame(continued, layout.rows.single().single())
        assertTrue(layout.hidden.isEmpty())
    }
}

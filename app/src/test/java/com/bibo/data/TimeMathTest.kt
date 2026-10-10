package com.bibo.data

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class TimeMathTest {
    @Test fun midnightPartitionsMeasuredWork() {
        val zone = ZoneId.of("UTC")
        val day = LocalDate.of(2026, 10, 10)
        val first = AppTime.window(day, zone)
        val second = AppTime.window(day.plusDays(1), zone)
        val start = first.endMillis - 30 * 60_000
        val end = second.startMillis + 30 * 60_000
        assertEquals(30 * 60_000L, TimeMath.overlapMillis(start, end, first.startMillis, first.endMillis))
        assertEquals(30 * 60_000L, TimeMath.overlapMillis(start, end, second.startMillis, second.endMillis))
    }

    @Test fun overlappingObservationsAreCountedOnce() {
        assertEquals(150L, TimeMath.unionMillis(listOf(0L to 100L, 50L to 150L, 100L to 120L), 0, 200))
        assertEquals(50L, TimeMath.unionMillis(listOf(0L to 100L, 50L to 150L), 75, 125))
    }

    @Test fun dstDayBoundsUseActualElapsedLength() {
        val zone = ZoneId.of("America/New_York")
        val spring = AppTime.window(LocalDate.of(2026, 3, 8), zone)
        val autumn = AppTime.window(LocalDate.of(2026, 11, 1), zone)
        assertEquals(23 * 3_600_000L, spring.endMillis - spring.startMillis)
        assertEquals(25 * 3_600_000L, autumn.endMillis - autumn.startMillis)
    }

    @Test fun completionAndLegacyGuessAreNotMeasuredWork() {
        val blocks = listOf(
            ActivityBlock(title = "Done", startMillis = 100, endMillis = 100, source = "COMPLETION"),
            ActivityBlock(title = "Guess", startMillis = 0, endMillis = 900, source = "TODO"),
            ActivityBlock(title = "Task work", startMillis = 0, endMillis = 100, source = "TASK_TIMER"),
        )
        assertEquals(100L, AppTime.measuredWorkMillis(blocks, 0, 1000))
    }
}

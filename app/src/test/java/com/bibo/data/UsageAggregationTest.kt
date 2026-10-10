package com.bibo.data

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class UsageAggregationTest {
    @Test fun inactiveGapsAreNeverCountedAsUsage() {
        val rows = listOf(UsageInterval(0, 60_000), UsageInterval(120_000, 180_000))
        assertEquals(120_000L, UsageAggregation.unionMillis(rows, 0, 180_000))
        assertEquals(2, UsageAggregation.union(rows, 0, 180_000).size)
    }
    @Test fun overlappingForegroundAppsCountOnceInTheHeadline() {
        val rows = listOf(UsageInterval(20, 100), UsageInterval(0, 40), UsageInterval(90, 150), UsageInterval(100, 120))
        assertEquals(listOf(UsageInterval(0, 150)), UsageAggregation.union(rows, -100, 200))
        assertEquals(150L, UsageAggregation.unionMillis(rows, -100, 200))
    }
    @Test fun adjacentDaysUseHalfOpenClippingWithoutDuplicates() {
        val rows = listOf(UsageInterval(90, 110), UsageInterval(50, 50), UsageInterval(200, 100))
        assertEquals(10L, UsageAggregation.unionMillis(rows, 0, 100))
        assertEquals(10L, UsageAggregation.unionMillis(rows, 100, 200))
        assertEquals(0L, UsageAggregation.unionMillis(rows, 100, 100))
    }
    @Test fun emptyAndPartialCollectionRemainDistinctFromObservedZero() {
        assertEquals(UsageCoverageState.UNKNOWN, UsageAggregation.coverage(emptyList(), 0, 100))
        assertEquals(UsageCoverageState.PARTIAL, UsageAggregation.coverage(listOf(UsageInterval(50, 100)), 0, 100))
        assertEquals(UsageCoverageState.COMPLETE, UsageAggregation.coverage(listOf(UsageInterval(0, 75), UsageInterval(50, 100)), 0, 100))
    }
    @Test fun springForwardSkipsHourAndFallBackCombinesRepeatedHour() {
        val zone = ZoneId.of("America/New_York")
        fun matrix(day: LocalDate): LongArray {
            val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            return UsageAggregation.hourlyMillis(listOf(UsageInterval(start, end)), day, zone)
        }
        val spring = matrix(LocalDate.of(2026, 3, 8))
        assertEquals(23 * 3_600_000L, spring.sum())
        assertEquals(0L, spring[2])
        val fall = matrix(LocalDate.of(2026, 11, 1))
        assertEquals(25 * 3_600_000L, fall.sum())
        assertEquals(2 * 3_600_000L, fall[1])
    }
    @Test fun halfHourDstTransitionsPreserveAllElapsedTime() {
        val zone = ZoneId.of("Australia/Lord_Howe")
        for ((day, duration) in listOf(LocalDate.of(2026, 4, 5) to 24.5, LocalDate.of(2026, 10, 4) to 23.5)) {
            val windows = UsageAggregation.hourWindows(day, zone)
            assertEquals((duration * 3_600_000).toLong(), windows.sumOf { it.end - it.start })
            assertTrue(windows.zipWithNext().all { (a, b) -> a.end == b.start })
            assertTrue(windows.all { it.end > it.start })
        }
    }
}

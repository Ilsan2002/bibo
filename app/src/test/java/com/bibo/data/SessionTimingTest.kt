package com.bibo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTimingTest {
    @Test fun lateResumeUsesTheOriginalCycleWithoutExtendingBreaks() {
        assertEquals(SessionTiming.Phase("WORK", 25 * 60_000L, 2), SessionTiming.phase(60 * 60_000L, 25, 5))
        assertEquals(SessionTiming.Phase("BREAK", 3 * 60_000L, 1), SessionTiming.phase(27 * 60_000L, 25, 5))
    }

    @Test fun workAndBreakSegmentsPreserveEveryMillisecondWithoutCountingBreakAsWork() {
        val parts = SessionTiming.intervals(62 * 60_000L, true, 25, 5)
        assertEquals(listOf("WORK", "BREAK", "WORK", "BREAK", "WORK"), parts.map { it.kind })
        assertEquals(52 * 60_000L, parts.filter { it.kind == "WORK" }.sumOf { it.endOffset - it.startOffset })
        assertEquals(10 * 60_000L, parts.filter { it.kind == "BREAK" }.sumOf { it.endOffset - it.startOffset })
        assertTrue(parts.zipWithNext().all { (a, b) -> a.endOffset == b.startOffset })
    }

    @Test fun reportingClipsMidnightAndWeeklyBoundaries() {
        assertEquals(10 * 60_000L, SessionTiming.clippedDuration(-10 * 60_000L, 10 * 60_000L, 0, 24 * 60 * 60_000L))
        assertEquals(0L, SessionTiming.clippedDuration(0, 100, 200, 300))
    }

    @Test fun monotonicElapsedDoesNotDependOnWallClockCorrection() {
        assertEquals(6500L, SessionTiming.elapsed(1000, 500, 7000))
        assertEquals(500L, SessionTiming.elapsed(1000, 500, 200))
    }

    @Test fun zeroAndInvalidDurationsCannotProduceNegativeOrEndlessSegments() {
        assertTrue(SessionTiming.intervals(0, true, 0, 0).isEmpty())
        val parts = SessionTiming.intervals(61_000, true, 0, 0)
        assertEquals(2, parts.size)
        assertTrue(parts.all { it.endOffset > it.startOffset })
    }
}

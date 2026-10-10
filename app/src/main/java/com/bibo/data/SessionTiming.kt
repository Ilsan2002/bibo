package com.bibo.data

/** Pure monotonic timing rules shared by the service, UI and durable finalization. */
object SessionTiming {
    data class Phase(val kind: String, val remainingMillis: Long, val pomodorosDone: Long)
    data class Interval(val kind: String, val startOffset: Long, val endOffset: Long)

    fun elapsed(anchorElapsed: Long, elapsedAtAnchor: Long, nowElapsed: Long): Long =
        elapsedAtAnchor.coerceAtLeast(0) + (nowElapsed - anchorElapsed).coerceAtLeast(0)

    fun phase(elapsedMillis: Long, workMin: Int, breakMin: Int): Phase {
        val work = workMin.coerceIn(1, 180) * 60_000L
        val pause = breakMin.coerceIn(1, 60) * 60_000L
        val elapsed = elapsedMillis.coerceAtLeast(0)
        val cycle = work + pause
        val inCycle = elapsed % cycle
        return if (inCycle < work) Phase("WORK", work - inCycle, elapsed / cycle)
        else Phase("BREAK", cycle - inCycle, elapsed / cycle + 1)
    }

    fun intervals(elapsedMillis: Long, pomodoro: Boolean, workMin: Int, breakMin: Int): List<Interval> {
        val end = elapsedMillis.coerceAtLeast(0)
        if (!pomodoro) return if (end > 0) listOf(Interval("WORK", 0, end)) else emptyList()
        val work = workMin.coerceIn(1, 180) * 60_000L
        val pause = breakMin.coerceIn(1, 60) * 60_000L
        val result = mutableListOf<Interval>()
        var offset = 0L
        var working = true
        while (offset < end) {
            val next = minOf(end, offset + if (working) work else pause)
            result += Interval(if (working) "WORK" else "BREAK", offset, next)
            offset = next
            working = !working
        }
        return result
    }

    fun clippedDuration(start: Long, end: Long, windowStart: Long, windowEnd: Long): Long =
        (minOf(end, windowEnd) - maxOf(start, windowStart)).coerceAtLeast(0)
}

package com.bibo.ui

import java.time.LocalTime

data class ParsedLog(val title: String, val start: LocalTime, val end: LocalTime)

/** Strips time/duration phrases and filler so leftover text becomes a clean title. */
fun cleanVoiceTitle(text: String): String =
    text
        .replace(RANGE, " ")
        .replace(DURATION, " ")
        .replace(AT, " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .replace(FILLERS, "")
        .trim()
        .replaceFirstChar { it.uppercase() }
        .ifBlank { "Voice log" }

private val RANGE = Regex(
    """(?:from\s+)?(\d{1,2})(?::(\d{2}))?\s*(am|pm)?\s*(?:to|until|till|-)\s*(\d{1,2})(?::(\d{2}))?\s*(am|pm)?""",
    RegexOption.IGNORE_CASE,
)
private val DURATION = Regex(
    """for\s+(?:the\s+)?(?:last\s+|past\s+)?(\d+)\s*(hours?|hrs?|minutes?|mins?)""",
    RegexOption.IGNORE_CASE,
)
private val AT = Regex(
    """at\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm)?""",
    RegexOption.IGNORE_CASE,
)
private val FILLERS = Regex(
    """^(i\s+was|i\s+am|i'?m|i\s+have\s+been|i've\s+been|just|been)\s+""",
    RegexOption.IGNORE_CASE,
)

/**
 * Turns a spoken phrase like "played tennis from 5 to 7" into a titled time block.
 * Times without am/pm are resolved to the interpretation closest in the past.
 */
fun parseVoiceLog(text: String, now: LocalTime = LocalTime.now()): ParsedLog {
    var title = text.trim()
    var start: LocalTime? = null
    var end: LocalTime? = null

    val range = RANGE.find(text)
    if (range != null) {
        val (h1, m1, mer1, h2, m2, mer2) = range.destructured
        start = resolveTime(h1.toInt(), m1.toIntOrNull() ?: 0, mer1, now)
        end = resolveTime(h2.toInt(), m2.toIntOrNull() ?: 0, mer2, now)
        if (!end.isAfter(start)) {
            // "11 to 1" style: bump the end by 12h if that fixes ordering
            val bumped = end.plusHours(12)
            end = if (bumped.isAfter(start)) bumped else start.plusMinutes(30)
        }
        title = title.replace(range.value, " ")
    } else {
        val dur = DURATION.find(text)
        if (dur != null) {
            val amount = dur.groupValues[1].toLong()
            val minutes =
                if (dur.groupValues[2].lowercase().startsWith("h")) amount * 60 else amount
            val at = AT.find(text)
            if (at != null) {
                val (h, m, mer) = at.destructured
                start = resolveTime(h.toInt(), m.toIntOrNull() ?: 0, mer, now)
                end = safePlusMinutes(start, minutes)
                title = title.replace(at.value, " ")
            } else {
                end = now
                start = safeMinusMinutes(now, minutes)
            }
            title = title.replace(dur.value, " ")
        } else {
            val at = AT.find(text)
            if (at != null) {
                val (h, m, mer) = at.destructured
                start = resolveTime(h.toInt(), m.toIntOrNull() ?: 0, mer, now)
                end = if (now.isAfter(start)) now else safePlusMinutes(start, 30)
                title = title.replace(at.value, " ")
            }
        }
    }

    // No time was stated → anchor at the current time (a 30-minute block starting now).
    if (start == null || end == null) {
        start = now
        end = safePlusMinutes(now, 30)
    }

    title = title
        .replace(Regex("\\s+"), " ")
        .trim()
        .replace(FILLERS, "")
        .trim()
        .replaceFirstChar { it.uppercase() }
        .ifBlank { "Voice log" }

    return ParsedLog(title, start, end)
}

/** Picks am/pm for an ambiguous hour so the time lands closest in the past. */
private fun resolveTime(hourRaw: Int, minute: Int, meridiem: String, now: LocalTime): LocalTime {
    val hour = hourRaw.coerceIn(0, 23)
    val min = minute.coerceIn(0, 59)
    return when (meridiem.lowercase()) {
        "am" -> LocalTime.of(if (hour == 12) 0 else hour % 12, min)
        "pm" -> LocalTime.of(if (hour == 12) 12 else (hour % 12) + 12, min)
        else -> {
            if (hour > 12) return LocalTime.of(hour, min)
            val amCandidate = LocalTime.of(hour % 12, min)
            val pmCandidate = LocalTime.of((hour % 12) + 12, min)
            when {
                !pmCandidate.isAfter(now) -> pmCandidate
                !amCandidate.isAfter(now) -> amCandidate
                else -> amCandidate
            }
        }
    }
}

private fun safePlusMinutes(t: LocalTime, minutes: Long): LocalTime {
    val result = t.plusMinutes(minutes)
    return if (result.isAfter(t)) result else LocalTime.of(23, 59)
}

private fun safeMinusMinutes(t: LocalTime, minutes: Long): LocalTime {
    val result = t.minusMinutes(minutes)
    return if (result.isBefore(t)) result else LocalTime.of(0, 0)
}

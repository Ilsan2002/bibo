package com.bibo.ui

import java.time.LocalTime

data class ParsedLog(val title: String, val start: LocalTime, val end: LocalTime)

/**
 * How to read an ambiguous bare hour (no am/pm):
 * - [LOG] for logging past activity by voice — pick the reading nearest in the past.
 * - [PLAN] for the editor — pick the daytime reading (1–6 → afternoon, 7–12 → morning),
 *   so "gym 6 to 7" plans 6–7 pm rather than 6 am.
 */
enum class TimeBias { LOG, PLAN }

/**
 * Result of pulling an explicit time out of free text. [start]/[end] are null when the
 * text names no time; [title] is the text with the time phrase removed.
 */
data class TimeExtraction(val title: String, val start: LocalTime?, val end: LocalTime?)

/**
 * Extracts an explicit clock time from any text — used by both the voice logger and the
 * editor's Title field, so typing "gym 6 to 7" sets the time and leaves "gym" as the title.
 * Returns null start/end when no time is stated (the caller decides the default).
 */
fun extractActivity(
    text: String,
    now: LocalTime = LocalTime.now(),
    bias: TimeBias = TimeBias.LOG,
): TimeExtraction {
    var title = text
    var start: LocalTime? = null
    var end: LocalTime? = null

    val range = RANGE.find(text)
    if (range != null) {
        val (h1, m1, mer1, h2, m2, mer2) = range.destructured
        start = resolveTime(h1.toInt(), m1.toIntOrNull() ?: 0, mer1, now, bias)
        var e = resolveTime(h2.toInt(), m2.toIntOrNull() ?: 0, mer2, now, bias)
        if (!e.isAfter(start)) {
            val bumped = e.plusHours(12)
            e = if (bumped.isAfter(start)) bumped else start.plusMinutes(30)
        }
        end = e
        title = title.replace(range.value, " ")
    } else {
        val dur = DURATION.find(text)
        val point = AT.find(text) ?: MERIDIEM_TIME.find(text)
        if (dur != null) {
            val amount = dur.groupValues[1].toLong()
            val minutes =
                if (dur.groupValues[2].startsWith("h", ignoreCase = true)) amount * 60 else amount
            if (point != null) {
                start = resolveTime(
                    point.groupValues[1].toInt(), point.groupValues[2].toIntOrNull() ?: 0,
                    point.groupValues[3], now, bias,
                )
                end = safePlusMinutes(start, minutes)
                title = title.replace(point.value, " ")
            } else {
                end = now
                start = safeMinusMinutes(now, minutes)
            }
            title = title.replace(dur.value, " ")
        } else if (point != null) {
            val s = resolveTime(
                point.groupValues[1].toInt(), point.groupValues[2].toIntOrNull() ?: 0,
                point.groupValues[3], now, bias,
            )
            start = s
            end = if (now.isAfter(s)) now else safePlusMinutes(s, 30)
            title = title.replace(point.value, " ")
        }
    }

    title = title.replace(Regex("\\s+"), " ").trim()
    return TimeExtraction(title, start, end)
}

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
// A bare clock time that must carry am/pm so it can't match stray numbers in a title
// (e.g. "call mom 3pm", "lunch at 12:30pm").
private val MERIDIEM_TIME = Regex(
    """\b(\d{1,2})(?::(\d{2}))?\s*(am|pm)\b""",
    RegexOption.IGNORE_CASE,
)
private val FILLERS = Regex(
    """^(i\s+was|i\s+am|i'?m|i\s+have\s+been|i've\s+been|just|been)\s+""",
    RegexOption.IGNORE_CASE,
)

/**
 * Turns a spoken phrase like "played tennis from 5 to 7" into a titled time block.
 * Times without am/pm are resolved to the interpretation closest in the past; when no
 * time is stated the block anchors at the current time (30 minutes).
 */
fun parseVoiceLog(text: String, now: LocalTime = LocalTime.now()): ParsedLog {
    val ex = extractActivity(text, now)
    val start = ex.start ?: now
    val end = ex.end ?: safePlusMinutes(now, 30)
    val title = ex.title
        .replace(FILLERS, "")
        .trim()
        .replaceFirstChar { it.uppercase() }
        .ifBlank { "Voice log" }
    return ParsedLog(title, start, end)
}

/** Resolves am/pm for an hour. Explicit am/pm always wins; a bare hour is read per [bias]. */
private fun resolveTime(
    hourRaw: Int,
    minute: Int,
    meridiem: String,
    now: LocalTime,
    bias: TimeBias,
): LocalTime {
    val hour = hourRaw.coerceIn(0, 23)
    val min = minute.coerceIn(0, 59)
    return when (meridiem.lowercase()) {
        "am" -> LocalTime.of(if (hour == 12) 0 else hour % 12, min)
        "pm" -> LocalTime.of(if (hour == 12) 12 else (hour % 12) + 12, min)
        else -> {
            if (hour > 12) return LocalTime.of(hour, min) // already 24-hour ("14")
            when (bias) {
                TimeBias.PLAN -> {
                    // Daytime reading: 1–6 → afternoon, 7–12 → morning (noon for 12).
                    val h24 = when {
                        hour == 12 -> 12
                        hour in 1..6 -> hour + 12
                        else -> hour
                    }
                    LocalTime.of(h24, min)
                }
                TimeBias.LOG -> {
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

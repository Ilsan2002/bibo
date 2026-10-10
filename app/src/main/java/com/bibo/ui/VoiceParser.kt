package com.bibo.ui

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.roundToLong

/** Dates remain attached to clock times all the way to the calendar editor. */
data class ParsedLog(
    val title: String,
    val start: LocalTime,
    val end: LocalTime,
    val date: LocalDate = LocalDate.now(),
    val endDate: LocalDate = if (end.isAfter(start)) date else date.plusDays(1),
    val warnings: List<String> = emptyList(),
    val requiresCorrection: Boolean = false,
)
enum class TimeBias { LOG, PLAN }
data class TimeExtraction(
    val title: String,
    val start: LocalTime?,
    val end: LocalTime?,
    val startDayOffset: Long = 0,
    val endDayOffset: Long = 0,
    val warning: String? = null,
)

private val RANGE = Regex("""(?<![\d-])\b(?:from\s+)?(\d{1,2})(?::(\d{2}))?\s*(am|pm)?\s*(?:to|until|till|-)\s*(\d{1,2})(?::(\d{2}))?\s*(am|pm)?\b""", RegexOption.IGNORE_CASE)
private val DURATION = Regex("""\bfor\s+(?:the\s+)?(?:last\s+|past\s+)?(-?\d+(?:\.\d+)?)\s*(hours?|hrs?|minutes?|mins?)\b""", RegexOption.IGNORE_CASE)
private val AT = Regex("""\bat\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm)?\b""", RegexOption.IGNORE_CASE)
private val MERIDIEM = Regex("""\b(\d{1,2})(?::(\d{2}))?\s*(am|pm)\b""", RegexOption.IGNORE_CASE)
private val FILLERS = Regex("""^(i\s+was|i\s+am|i'?m|i\s+have\s+been|i've\s+been|just|been)\s+""", RegexOption.IGNORE_CASE)
private val DAY = Regex("""\b(yesterday|today|tomorrow|\d{4}-\d{2}-\d{2})\b""", RegexOption.IGNORE_CASE)
private val BASE_DATE = LocalDate.of(2000, 1, 2)

/** Invalid explicit times never get silently coerced into a different interval. */
fun extractActivity(text: String, now: LocalTime = LocalTime.now(), bias: TimeBias = TimeBias.LOG): TimeExtraction {
    fun result(title: String, s: LocalDateTime, e: LocalDateTime, warning: String? = null) =
        TimeExtraction(title.trim().replace(Regex("\\s+"), " "), s.toLocalTime(), e.toLocalTime(),
            s.toLocalDate().toEpochDay() - BASE_DATE.toEpochDay(), e.toLocalDate().toEpochDay() - BASE_DATE.toEpochDay(), warning)
    val range = RANGE.find(text)
    if (range != null) {
        val (h1, m1, am1, h2, m2, am2) = range.destructured
        // A suffix such as “9 to 10pm” qualifies both ends.
        if (h1.toInt() == h2.toInt() && (m1.toIntOrNull() ?: 0) == (m2.toIntOrNull() ?: 0) && am1.equals(am2, true)) return TimeExtraction(text, null, null, warning = "Equal endpoints need explicit dates and times.")
        val firstMeridiem = am1.ifBlank { am2 }
        val secondMeridiem = am2.ifBlank { am1 }
        var start = resolveTime(h1.toInt(), m1.toIntOrNull() ?: 0, firstMeridiem, now, bias)
        var end = resolveTime(h2.toInt(), m2.toIntOrNull() ?: 0, secondMeridiem, now, bias)
        if (start == null || end == null) return TimeExtraction(text, null, null, warning = "That clock time is invalid. Set the time in the editor.")
        val explicit24 = am1.isBlank() && am2.isBlank() && (h1.toInt() > 12 || h2.toInt() > 12 || h1.startsWith("0") || h2.startsWith("0"))
        if (explicit24) { start = LocalTime.of(h1.toInt(), m1.toIntOrNull() ?: 0); end = LocalTime.of(h2.toInt(), m2.toIntOrNull() ?: 0) }
        var s = BASE_DATE.atTime(start)
        var e = BASE_DATE.atTime(end)
        if (!explicit24 && am1.isBlank() && am2.isBlank() && h1.toInt() in 1..12 && h2.toInt() in 1..12) {
            if (bias == TimeBias.LOG) {
                val candidates = mutableListOf<Pair<LocalDateTime, LocalDateTime>>()
                for (dayOffset in -1L..0L) for (shift1 in listOf(0, 12)) for (shift2 in listOf(0, 12)) {
                    val a = BASE_DATE.plusDays(dayOffset).atTime(h1.toInt() % 12 + shift1, m1.toIntOrNull() ?: 0)
                    var b = a.toLocalDate().atTime(h2.toInt() % 12 + shift2, m2.toIntOrNull() ?: 0)
                    if (!b.isAfter(a)) b = b.plusDays(1)
                    val minutes = java.time.Duration.between(a, b).toMinutes()
                    if (minutes in 1..720 && !b.isAfter(BASE_DATE.atTime(now))) candidates += a to b
                }
                candidates.maxByOrNull { it.second }?.let { s = it.first; e = it.second }
            } else if (!e.isAfter(s)) {
                end = end.plusHours(12)
                e = BASE_DATE.atTime(end)
            }
        }
        if (!e.isAfter(s)) e = e.plusDays(1)
        if (e == s.plusDays(1)) return TimeExtraction(text, null, null, warning = "Equal start and end times are ambiguous. Choose the dates and times explicitly.")
        if (bias == TimeBias.LOG && e.isAfter(BASE_DATE.atTime(now))) { s = s.minusDays(1); e = e.minusDays(1) }
        val warning = if (am1.isBlank() && am2.isBlank() && h1.toInt() <= 12 && h2.toInt() <= 12) "Check the inferred AM/PM before saving." else null
        return result(text.replaceRange(range.range, " "), s, e, warning)
    }
    val duration = DURATION.find(text)
    val point = AT.find(text) ?: MERIDIEM.find(text)
    val minutes = duration?.let {
        val amount = it.groupValues[1].toDoubleOrNull()
        val value = amount?.times(if (it.groupValues[2].startsWith("h", true)) 60 else 1)
        if (value == null || !value.isFinite() || value <= 0 || value > 7 * 24 * 60) return TimeExtraction(text, null, null, warning = "Duration must be greater than zero and no longer than seven days.")
        value.roundToLong().coerceAtLeast(1)
    }
    val time = point?.let { resolveTime(it.groupValues[1].toInt(), it.groupValues[2].toIntOrNull() ?: 0, it.groupValues[3], now, bias) }
    if (point != null && time == null) return TimeExtraction(text, null, null, warning = "That clock time is invalid. Set the time in the editor.")
    var title = text
    if (point != null) title = title.replaceRange(point.range, " ")
    if (duration != null) title = title.replace(duration.value, " ")
    if (minutes != null) {
        val s = time?.let { BASE_DATE.atTime(it) }
        val e = if (s != null) s.plusMinutes(minutes) else BASE_DATE.atTime(now)
        return result(title, s ?: e.minusMinutes(minutes), e)
    }
    if (time != null) {
        var s = BASE_DATE.atTime(time)
        if (bias == TimeBias.LOG && s.isAfter(BASE_DATE.atTime(now))) s = s.minusDays(1)
        val e = if (bias == TimeBias.LOG) BASE_DATE.atTime(now).takeIf { it.isAfter(s) } ?: s.plusMinutes(30) else s.plusMinutes(30)
        return result(title, s, e)
    }
    return TimeExtraction(text.trim(), null, null)
}

fun cleanVoiceTitle(text: String): String = text.replace(RANGE, " ").replace(DURATION, " ")
    .replace(AT, " ").replace(MERIDIEM, " ").replace(DAY, " ").replace(Regex("\\s+"), " ")
    .trim().replace(FILLERS, "").trim().replaceFirstChar { it.uppercase() }.ifBlank { "Voice log" }

fun parseVoiceLog(text: String, now: LocalTime = LocalTime.now(), referenceDate: LocalDate = LocalDate.now()): ParsedLog {
    val explicitDay = DAY.find(text)
    val invalidDate = explicitDay?.value?.takeIf { it.firstOrNull()?.isDigit() == true }?.let { runCatching { LocalDate.parse(it) }.isFailure } == true
    val target = when (val day = explicitDay?.value?.lowercase()) {
        "yesterday" -> referenceDate.minusDays(1)
        "tomorrow" -> referenceDate.plusDays(1)
        "today", null -> referenceDate
        else -> runCatching { LocalDate.parse(day) }.getOrDefault(referenceDate)
    }
    val cleaned = explicitDay?.let { text.replaceRange(it.range, " ") } ?: text
    // On an explicitly named day, clock interpretation should not shift that stated date.
    val ex = extractActivity(cleaned, if (explicitDay != null) LocalTime.MAX else now)
    val start = target.plusDays(if (explicitDay != null && RANGE.containsMatchIn(cleaned)) 0 else ex.startDayOffset).atTime(ex.start ?: now)
    val end = if (ex.end != null) target.plusDays(if (explicitDay != null && RANGE.containsMatchIn(cleaned)) ex.endDayOffset - ex.startDayOffset else ex.endDayOffset).atTime(ex.end) else start.plusMinutes(30)
    return ParsedLog(cleanVoiceTitle(ex.title), start.toLocalTime(), end.toLocalTime(), start.toLocalDate(), end.toLocalDate(), listOfNotNull(ex.warning, if (invalidDate) "That date is invalid. Choose the date explicitly." else null), invalidDate || (ex.warning != null && ex.start == null))
}

private fun resolveTime(hour: Int, minute: Int, meridiem: String, now: LocalTime, bias: TimeBias): LocalTime? {
    if (minute !in 0..59 || hour !in 0..23 || (meridiem.isNotBlank() && hour !in 1..12)) return null
    return when (meridiem.lowercase()) {
        "am" -> LocalTime.of(hour % 12, minute)
        "pm" -> LocalTime.of(hour % 12 + 12, minute)
        else -> when {
            hour > 12 || hour == 0 -> LocalTime.of(hour, minute)
            bias == TimeBias.PLAN -> LocalTime.of(if (hour in 1..6) hour + 12 else hour, minute)
            else -> listOf(LocalTime.of(hour % 12, minute), LocalTime.of(hour % 12 + 12, minute)).filter { !it.isAfter(now) }.maxOrNull() ?: LocalTime.of(hour % 12, minute)
        }
    }
}

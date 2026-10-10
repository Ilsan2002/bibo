package com.bibo.ui

import android.content.Context
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * One validated local interpretation on every device. Speech recognition is a separate
 * capability; its privacy choice is shown by HoldToTalkButton. Calendar edits remain a
 * preview, never an automatic write. No model download can stall an accepted transcript.
 */
class SmartVoiceParser(@Suppress("UNUSED_PARAMETER") context: Context) {
    suspend fun warmUp() = Unit

    suspend fun parse(text: String, referenceDate: LocalDate = LocalDate.now(), now: LocalTime = LocalTime.now()): ParsedLog =
        withTimeout(2_000) {
            withContext(Dispatchers.Default) {
                val parsed = parseVoiceLog(normalizeSpokenTimes(text), now, referenceDate)
                if (!Regex("""\b(from|at|to|until|till|for|am|pm)\b|\d{1,2}:\d{2}""", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
                    parsed.copy(warnings = parsed.warnings + "No time stated: check the suggested 30-minute block.")
                } else parsed
            }
        }
}

/** Spoken number words only become digits beside a time/duration phrase. */
internal fun normalizeSpokenTimes(text: String): String {
    val numbers = mapOf("zero" to "0", "one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5", "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9", "ten" to "10", "eleven" to "11", "twelve" to "12")
    var result = text.replace(Regex("""\bfor (?:the last |the past )?(?:half an?|a half) hour\b""", RegexOption.IGNORE_CASE), "for 30 minutes")
    result = result.replace(Regex("""\b(from|at|to|until|till|for)\s+(zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)\b""", RegexOption.IGNORE_CASE)) {
        it.groupValues[1] + " " + numbers.getValue(it.groupValues[2].lowercase())
    }
    return result
}

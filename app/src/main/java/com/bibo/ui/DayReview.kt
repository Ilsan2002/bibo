package com.bibo.ui

import android.content.Context
import com.bibo.data.BiboDb
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import java.time.LocalDate
import java.time.ZoneId

/**
 * Builds an end-of-day recap from everything Bibo knows — focus sessions, habits, and
 * intake — and has Gemini Nano write it up in a sentence or two, with a plain template
 * fallback when the model isn't available.
 */
class DayReview(private val context: Context) {

    suspend fun generate(epochDay: Long): String {
        val facts = gatherFacts(epochDay)
        if (facts.isBlank()) return "Nothing logged yet today — start a focus session or log a meal."
        return geminiNarrative(facts) ?: facts
    }

    private suspend fun gatherFacts(epochDay: Long): String {
        val db = BiboDb.get(context)
        val zone = ZoneId.systemDefault()
        val date = LocalDate.ofEpochDay(epochDay)
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val blocks = runCatching { db.activityBlocks().blocksInList(start, end) }.getOrDefault(emptyList())
        val focus = blocks.filter { it.source == "FOCUS" || it.source == "TIMER" }
        val focusMin = focus.sumOf { (it.endMillis - it.startMillis) / 60_000 }

        val habit = runCatching { db.habits().get(epochDay) }.getOrNull()
        val foods = runCatching { db.foods().forDayOnce(epochDay) }.getOrDefault(emptyList())

        val lines = mutableListOf<String>()
        if (focus.isNotEmpty()) {
            lines += "Focus: ${focus.size} session${if (focus.size > 1) "s" else ""}, ${focusMin / 60}h ${focusMin % 60}m."
        }
        if (habit != null) {
            val done = buildList {
                if (habit.showered) add("showered")
                if (habit.workedOut) add("worked out")
                if (habit.prayed) add("prayed")
                if (habit.cleanClothes) add("clean clothes")
            }
            val missed = buildList {
                if (!habit.showered) add("shower")
                if (!habit.workedOut) add("workout")
                if (!habit.prayed) add("prayer")
            }
            if (done.isNotEmpty()) lines += "Did: ${done.joinToString(", ")}."
            if (missed.isNotEmpty()) lines += "Missed: ${missed.joinToString(", ")}."
        }
        if (foods.isNotEmpty()) {
            val cal = foods.sumOf { it.calories }
            val sugar = foods.sumOf { it.sugarG }.toInt()
            val caf = foods.sumOf { it.caffeineMg }
            lines += "Intake: $cal kcal, ${sugar}g sugar, ${caf}mg caffeine."
        }
        return lines.joinToString("\n")
    }

    private suspend fun geminiNarrative(facts: String): String? =
        try {
            val model = Generation.getClient()
            if (model.checkStatus() != FeatureStatus.AVAILABLE) {
                null
            } else {
                val prompt = """
                    Write a warm, brief 2–3 sentence recap of the person's day, in second person ("you"),
                    based only on these facts. Be encouraging but honest; no bullet points, no preamble.
                    Facts:
                    $facts
                    Recap:
                """.trimIndent()
                model.generateContent(prompt).candidates.firstOrNull()?.text?.trim()?.ifBlank { null }
            }
        } catch (_: Throwable) {
            null
        }
}

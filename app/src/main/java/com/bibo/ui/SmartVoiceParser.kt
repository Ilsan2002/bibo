package com.bibo.ui

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.nl.entityextraction.EntityExtraction
import com.google.mlkit.nl.entityextraction.EntityExtractionParams
import com.google.mlkit.nl.entityextraction.EntityExtractorOptions
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.TimeZone
import kotlinx.coroutines.flow.collect
import org.json.JSONObject

/**
 * Turns a spoken phrase into a titled time block using the best available on-device
 * engine, falling back safely so the feature always works:
 *
 *   1. Gemini Nano (ML Kit GenAI Prompt) — best; understands free-form phrasing.
 *   2. ML Kit Entity Extraction — deterministic date/time extraction.
 *   3. Rule-based regex ([parseVoiceLog]) — always succeeds.
 *
 * Everything runs on-device; nothing leaves the phone.
 */
class SmartVoiceParser(private val context: Context) {

    /** Kick off the Gemini Nano model download so the first voice log can use it. */
    suspend fun warmUp() {
        runCatching {
            val model = Generation.getClient()
            if (model.checkStatus() == FeatureStatus.DOWNLOADABLE) {
                model.download().collect { }
            }
        }
    }

    suspend fun parse(text: String): ParsedLog {
        geminiParse(text)?.let { return it }
        entityParse(text)?.let { return it }
        return parseVoiceLog(text)
    }

    private suspend fun geminiParse(text: String): ParsedLog? =
        try {
            val model = Generation.getClient()
            // Only use Nano if the model is ready — never block a voice log on a
            // first-time download; warmUp() handles that ahead of time.
            if (model.checkStatus() != FeatureStatus.AVAILABLE) {
                null
            } else {
                val now = LocalTime.now()
                val response = model.generateContent(buildPrompt(text, now))
                response.candidates.firstOrNull()?.text?.let { parseJson(it) }
            }
        } catch (_: Throwable) {
            null
        }

    private fun buildPrompt(text: String, now: LocalTime): String {
        val nowStr = now.format(HM)
        return """
            You convert a spoken activity log into JSON. The current local time is $nowStr (24-hour).
            Return ONLY a compact JSON object, no prose, no markdown:
            {"title": <short activity name>, "start": "H:mm", "end": "H:mm"}
            Rules:
            - Times are 24-hour local. If only a duration is stated, end at the current time.
            - If NO time or duration is stated, start at the current time ($nowStr) with a 30-minute duration.
            - Keep the title short; drop filler like "I was", "I'm".
            Examples:
            Input: "played tennis from 5 to 7" -> {"title":"Tennis","start":"17:00","end":"19:00"}
            Input: "in a meeting for the last 30 minutes" -> {"title":"Meeting","start":"${now.minusMinutes(30).format(HM)}","end":"$nowStr"}
            Input: "reading a book" -> {"title":"Reading","start":"$nowStr","end":"${now.plusMinutes(30).format(HM)}"}
            Input: "${text.replace("\"", "'")}" ->
        """.trimIndent()
    }

    private fun parseJson(raw: String): ParsedLog? {
        val s = raw.indexOf('{')
        val e = raw.lastIndexOf('}')
        if (s < 0 || e <= s) return null
        return runCatching {
            val obj = JSONObject(raw.substring(s, e + 1))
            val title = obj.optString("title").trim().ifBlank { return null }
            val start = parseHm(obj.optString("start")) ?: return null
            var end = parseHm(obj.optString("end")) ?: start.plusMinutes(30)
            if (!end.isAfter(start)) end = start.plusMinutes(30)
            ParsedLog(title.replaceFirstChar { it.uppercase() }, start, end)
        }.getOrNull()
    }

    private fun entityParse(text: String): ParsedLog? =
        try {
            val extractor = EntityExtraction.getClient(
                EntityExtractorOptions.Builder(EntityExtractorOptions.ENGLISH).build()
            )
            Tasks.await(extractor.downloadModelIfNeeded())
            val params = EntityExtractionParams.Builder(text)
                .setReferenceTime(System.currentTimeMillis())
                .setReferenceTimeZone(TimeZone.getDefault())
                .build()
            val zone = ZoneId.systemDefault()
            val times = Tasks.await(extractor.annotate(params))
                .flatMap { it.entities }
                .mapNotNull { it.asDateTimeEntity()?.timestampMillis }
                .map { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() }
                .sorted()
            if (times.isEmpty()) {
                null
            } else {
                val now = LocalTime.now()
                val start = times.first()
                var end = if (times.size >= 2) times[1] else now
                if (!end.isAfter(start)) end = start.plusMinutes(30)
                ParsedLog(cleanVoiceTitle(text), start, end)
            }
        } catch (_: Throwable) {
            null
        }

    private fun parseHm(s: String): LocalTime? {
        val t = s.trim()
        return runCatching { LocalTime.parse(t, HM) }.getOrNull()
            ?: runCatching { LocalTime.parse(t) }.getOrNull()
    }

    companion object {
        private val HM = DateTimeFormatter.ofPattern("H:mm")
    }
}

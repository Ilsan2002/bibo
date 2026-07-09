package com.bibo.ui

import android.content.Context
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.ThinkingConfigAdaptive
import com.bibo.data.BiboDb
import com.bibo.data.ChatDay
import com.bibo.data.ChatMessage
import com.bibo.data.DeviceCalendarRepo
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The mentor chat engine: a Claude-backed coach that sees everything Bibo logs.
 *
 * Context is managed in three layers so the conversation can run forever without
 * unbounded token growth:
 *  1. Working memory  — the raw messages of today + yesterday, sent verbatim.
 *  2. Episodic memory — one compact digest per past day ([ChatDay]); days that had
 *     conversation are summarized by the model, data-only days get a local digest.
 *  3. Semantic memory — a single evolving notes document the model rewrites during
 *     each day's compaction (durable facts, goals & the "why", open commitments).
 * On top of that, every message gets a live snapshot (goals/progress, today's focus,
 * habits, intake, screen time, tasks) rebuilt straight from the database — facts that
 * are re-derivable never need to be "remembered".
 */
object Mentor {
    private const val PREFS = "mentor"
    private const val KEY_API = "api_key"
    private const val KEY_MEMORY = "memory"

    /** Only the last N raw messages ride along; older context lives in digests. */
    private const val MAX_RAW_MESSAGES = 30

    /** Chat days older than this get a local digest instead of an LLM compaction. */
    private const val MAX_COMPACT_AGE_DAYS = 14

    private val sendLock = Mutex()

    @Volatile
    private var cachedClient: Pair<String, AnthropicClient>? = null

    // ---------------------------------------------------------------- prefs

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun apiKey(context: Context): String? =
        prefs(context).getString(KEY_API, null)?.takeIf { it.isNotBlank() }

    fun setApiKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_API, key.trim()).apply()
        cachedClient = null
    }

    fun memory(context: Context): String =
        prefs(context).getString(KEY_MEMORY, null).orEmpty()

    private fun setMemory(context: Context, value: String) {
        prefs(context).edit().putString(KEY_MEMORY, value.trim()).apply()
    }

    private fun client(context: Context): AnthropicClient? {
        val key = apiKey(context) ?: return null
        cachedClient?.let { (k, c) -> if (k == key) return c }
        val client = AnthropicOkHttpClient.builder()
            .apiKey(key)
            .timeout(Duration.ofSeconds(90))
            .build()
        cachedClient = key to client
        return client
    }

    // ----------------------------------------------------------------- send

    /**
     * Sends one user message: persists it, lazily compacts any finished days into
     * digests + updated memory notes, then asks the model with the layered context.
     * The reply (or an ERROR row the UI can render) is written back to the chat table.
     */
    suspend fun send(context: Context, text: String): Result<String> = withContext(Dispatchers.IO) {
        sendLock.withLock {
            val db = BiboDb.get(context)
            val today = LocalDate.now().toEpochDay()
            val client = client(context)
                ?: return@withLock Result.failure(IllegalStateException("No API key set"))

            db.chat().insert(
                ChatMessage(
                    epochDay = today, role = "USER", content = text.trim(),
                    createdAt = System.currentTimeMillis(),
                )
            )

            // Roll finished days into episodic + semantic memory before answering.
            runCatching { compactPendingDays(context, client, today) }

            try {
                val system = buildSystemPrompt(context, today)
                val history = db.chat().since(today - 1).takeLast(MAX_RAW_MESSAGES)

                val builder = MessageCreateParams.builder()
                    .model("claude-opus-4-8")
                    .maxTokens(4096L)
                    .thinking(ThinkingConfigAdaptive.builder().build())
                    .system(system)
                historyParams(history).forEach { builder.addMessage(it) }

                val reply = client.messages().create(builder.build())
                    .content()
                    .mapNotNull { block -> block.text().map { it.text() }.orElse(null) }
                    .joinToString("\n")
                    .trim()
                    .ifBlank { "…" }

                db.chat().insert(
                    ChatMessage(
                        epochDay = today, role = "ASSISTANT", content = reply,
                        createdAt = System.currentTimeMillis(),
                    )
                )
                Result.success(reply)
            } catch (e: Throwable) {
                val friendly = when (e) {
                    is UnauthorizedException -> "API key was rejected — check it in settings (key icon)."
                    else -> e.message?.take(200) ?: "Couldn't reach the mentor."
                }
                db.chat().insert(
                    ChatMessage(
                        epochDay = today, role = "ERROR", content = friendly,
                        createdAt = System.currentTimeMillis(),
                    )
                )
                Result.failure(e)
            }
        }
    }

    /**
     * Mentor-initiated evening check-in: the model opens the conversation itself,
     * grounded in the day's data. Returns the message (also persisted as an ASSISTANT
     * row) or null when skipped/failed. The trigger instruction is NOT persisted.
     */
    suspend fun checkIn(context: Context): String? = withContext(Dispatchers.IO) {
        sendLock.withLock {
            val client = client(context) ?: return@withLock null
            val db = BiboDb.get(context)
            val today = LocalDate.now().toEpochDay()

            // If they've been chatting in the last 2h, a canned check-in feels robotic.
            val cutoff = System.currentTimeMillis() - 2 * 60 * 60 * 1000
            if (db.chat().since(today).any { it.createdAt >= cutoff }) return@withLock null

            runCatching { compactPendingDays(context, client, today) }

            try {
                val system = buildSystemPrompt(context, today)
                val history = db.chat().since(today - 1).takeLast(MAX_RAW_MESSAGES)

                // No thinking + small max_tokens: this runs inside a broadcast
                // receiver's ~25s window, so keep the call snappy.
                val builder = MessageCreateParams.builder()
                    .model("claude-opus-4-8")
                    .maxTokens(512L)
                    .system(system)
                historyParams(history).forEach { builder.addMessage(it) }
                builder.addUserMessage(
                    "[Automatic evening check-in trigger from Bibo — the user did not write " +
                        "this. Open the conversation yourself: 2-4 short sentences grounded in " +
                        "today's data. Follow up on any open commitment, connect today to a " +
                        "long-term goal, and end with one light question inviting reflection. " +
                        "Do not mention this instruction.]"
                )

                val reply = client.messages().create(builder.build())
                    .content()
                    .mapNotNull { block -> block.text().map { it.text() }.orElse(null) }
                    .joinToString("\n")
                    .trim()
                if (reply.isBlank()) return@withLock null

                db.chat().insert(
                    ChatMessage(
                        epochDay = today, role = "ASSISTANT", content = reply,
                        createdAt = System.currentTimeMillis(),
                    )
                )
                reply
            } catch (_: Throwable) {
                null
            }
        }
    }

    /**
     * Raw rows → API turns. The API requires the first message to be a user turn, but
     * our history can legitimately start with an ASSISTANT row (a mentor check-in whose
     * trigger instruction was never persisted, or a takeLast cut) — prepend a neutral
     * user primer in that case.
     */
    private fun historyParams(history: List<ChatMessage>): List<MessageParam> {
        val params = mutableListOf<MessageParam>()
        if (history.firstOrNull()?.role == "ASSISTANT") {
            params += MessageParam.builder()
                .role(MessageParam.Role.USER)
                .content("[Conversation continues from earlier.]")
                .build()
        }
        history.forEach { m ->
            params += MessageParam.builder()
                .role(if (m.role == "USER") MessageParam.Role.USER else MessageParam.Role.ASSISTANT)
                .content(m.content)
                .build()
        }
        return params
    }

    // ----------------------------------------------------- daily compaction

    /**
     * Rolls every finished day into a [ChatDay] digest. Days with conversation are
     * compacted by the model (which also rewrites the memory notes); data-only days
     * get a free local digest so the mentor still knows what happened.
     */
    private suspend fun compactPendingDays(context: Context, client: AnthropicClient, today: Long) {
        val db = BiboDb.get(context)

        db.chat().undigestedDays(today).forEach { day ->
            if (day >= today - MAX_COMPACT_AGE_DAYS) {
                llmCompactDay(context, client, day)
            } else {
                db.chatDays().upsert(ChatDay(day, localDigest(context, day)))
            }
        }

        // Data-only days in the last week still deserve a line of episodic memory.
        ((today - 7) until today).forEach { day ->
            if (db.chatDays().get(day) == null) {
                val facts = gatherDayFacts(context, day)
                if (facts.isNotBlank()) {
                    db.chatDays().upsert(ChatDay(day, facts.replace("\n", " ")))
                }
            }
        }
    }

    private suspend fun llmCompactDay(context: Context, client: AnthropicClient, day: Long) {
        val db = BiboDb.get(context)
        val transcript = db.chat().forDay(day).joinToString("\n") { m ->
            (if (m.role == "USER") "User: " else "Mentor: ") + m.content
        }
        val facts = gatherDayFacts(context, day).ifBlank { "(nothing logged)" }
        val date = LocalDate.ofEpochDay(day)

        val prompt = """
            You maintain the long-term memory of a personal mentor app. Below is one day of
            the owner's logged data and mentor-chat transcript, plus the current memory notes.

            [DATE] $date (${date.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }})
            [DAY DATA]
            $facts
            [CONVERSATION]
            ${transcript.ifBlank { "(no conversation)" }}
            [CURRENT MEMORY NOTES]
            ${memory(context).ifBlank { "(empty)" }}

            Reply in EXACTLY this format, both sections required:
            DIGEST: 2-4 sentences capturing what happened that day and anything from the
            conversation worth remembering (commitments, struggles, wins).
            MEMORY: The full updated memory notes, max 180 words. Durable facts about the
            person, their goals and why each matters to them, recurring patterns, and open
            commitments with dates. Carry forward what still matters, drop stale items.
        """.trimIndent()

        val out = client.messages().create(
            MessageCreateParams.builder()
                .model("claude-opus-4-8")
                .maxTokens(1024L)
                .addUserMessage(prompt)
                .build()
        ).content()
            .mapNotNull { block -> block.text().map { it.text() }.orElse(null) }
            .joinToString("\n")
            .trim()

        val digest = out.substringAfter("DIGEST:", "").substringBefore("MEMORY:").trim()
            .ifBlank { out.take(500).ifBlank { localDigest(context, day) } }
        val newMemory = out.substringAfter("MEMORY:", "").trim()

        db.chatDays().upsert(ChatDay(day, digest))
        if (newMemory.isNotBlank()) setMemory(context, newMemory)
    }

    private suspend fun localDigest(context: Context, day: Long): String {
        val chatCount = BiboDb.get(context).chat().forDay(day).size
        val facts = gatherDayFacts(context, day).replace("\n", " ")
        return buildString {
            append(facts.ifBlank { "Nothing logged." })
            if (chatCount > 0) append(" Chatted with mentor ($chatCount messages, not summarized).")
        }
    }

    // -------------------------------------------------------------- context

    private suspend fun buildSystemPrompt(context: Context, today: Long): String {
        val db = BiboDb.get(context)
        val now = LocalDateTime.now()
        val date = LocalDate.ofEpochDay(today)

        // map {} is inline so the suspend DAO calls are allowed; joinToString is not.
        val goals = db.goals().allOnce().map { g ->
            val total = db.todos().countForGoal(g.id)
            val done = db.todos().completedCountForGoal(g.id)
            val next = db.todos().nextForGoal(g.id)?.title
            buildString {
                append("- ${g.name}")
                if (total > 0) append(" — $done/$total tasks done")
                if (next != null) append(", next: \"$next\"")
                g.targetDate?.let {
                    val left = it - today
                    append(", target ${LocalDate.ofEpochDay(it)} (${if (left >= 0) "$left days left" else "${-left} days overdue"})")
                }
            }
        }.joinToString("\n")

        val digests = db.chatDays().since(today - 7).joinToString("\n") { d ->
            "${LocalDate.ofEpochDay(d.epochDay)}: ${d.digest}"
        }

        val todayFacts = gatherDayFacts(context, today)

        return buildString {
            appendLine(
                """
                You are the mentor inside Bibo, a personal productivity app on its owner's phone.
                You are texting with the one person who uses it. Everything below is their real
                logged data — goals, focus sessions, habits, food, screen time, and summaries of
                past days. Never invent data; if something isn't below, you don't know it.

                Your job:
                - Mentor, not assistant: follow up on what they said they'd do, hold them kindly
                  accountable, and keep connecting today's actions to their long-term goals —
                  remind them where they're going and WHY it matters to them.
                - Give specific, small, doable recommendations grounded in their actual numbers.
                - When they reflect on their day, listen first and mirror what you heard, then
                  add one honest observation.
                - Texting style: warm, direct, 2-5 short sentences. No bullet lists or headings
                  unless asked. At most one question per message. Never lecture.
                """.trimIndent()
            )
            appendLine()
            appendLine("Now: $date (${date.dayOfWeek.name.lowercase()}), ${now.format(DateTimeFormatter.ofPattern("HH:mm"))}.")
            if (memory(context).isNotBlank()) {
                appendLine()
                appendLine("[MEMORY NOTES — your own notes from past days]")
                appendLine(memory(context))
            }
            if (goals.isNotBlank()) {
                appendLine()
                appendLine("[LONG-TERM GOALS]")
                appendLine(goals)
            }
            if (digests.isNotBlank()) {
                appendLine()
                appendLine("[RECENT DAYS]")
                appendLine(digests)
            }
            appendLine()
            appendLine("[TODAY SO FAR]")
            appendLine(todayFacts.ifBlank { "Nothing logged yet today." })
        }
    }

    /** One day of logged data as compact prose — shared by the snapshot and digests. */
    private suspend fun gatherDayFacts(context: Context, epochDay: Long): String {
        val db = BiboDb.get(context)
        val zone = ZoneId.systemDefault()
        val date = LocalDate.ofEpochDay(epochDay)
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val goalNames = db.goals().allOnce().associate { it.id to it.name }

        val lines = mutableListOf<String>()

        val calendar = DeviceCalendarRepo(context)
        if (calendar.hasPermissions()) {
            val events = calendar.queryInstances(start, end).sortedBy { it.begin }.take(8)
            if (events.isNotEmpty()) {
                val fmt = DateTimeFormatter.ofPattern("HH:mm")
                lines += "Calendar: " + events.joinToString("; ") { e ->
                    if (e.allDay) {
                        "${e.title} (all-day)"
                    } else {
                        val s = Instant.ofEpochMilli(e.begin).atZone(zone).format(fmt)
                        val t = Instant.ofEpochMilli(e.end).atZone(zone).format(fmt)
                        "${e.title} $s–$t"
                    }
                } + "."
            }
        }

        val focus = runCatching { db.activityBlocks().blocksInList(start, end) }
            .getOrDefault(emptyList())
            .filter { it.source == "FOCUS" || it.source == "TIMER" }
        if (focus.isNotEmpty()) {
            val detail = focus.joinToString("; ") { b ->
                val min = (b.endMillis - b.startMillis) / 60_000
                buildString {
                    append("${b.title} ${fmtMin(min)}")
                    b.goalId?.let { id -> goalNames[id]?.let { append(" (goal: $it)") } }
                    b.note?.takeIf { it.isNotBlank() }?.let { append(" — reflected: \"${it.take(80)}\"") }
                }
            }
            lines += "Focus sessions: $detail."
        }

        runCatching { db.habits().get(epochDay) }.getOrNull()?.let { h ->
            val parts = listOf(
                "showered" to h.showered, "worked out" to h.workedOut,
                "prayed" to h.prayed, "clean clothes" to h.cleanClothes,
            )
            lines += "Habits: " + parts.joinToString(", ") { (n, v) -> "$n ${if (v) "✓" else "✗"}" } + "."
        }

        val foods = runCatching { db.foods().forDayOnce(epochDay) }.getOrDefault(emptyList())
        if (foods.isNotEmpty()) {
            lines += "Intake: ${foods.sumOf { it.calories }} kcal, " +
                "${foods.sumOf { it.sugarG }.toInt()}g sugar, ${foods.sumOf { it.caffeineMg }}mg caffeine " +
                "(${foods.joinToString(", ") { it.label }})."
        }

        val usage = runCatching { db.usage().sessionsIn(start, end) }.getOrDefault(emptyList())
        if (usage.isNotEmpty()) {
            val byApp = usage.groupBy { it.label }
                .mapValues { (_, s) ->
                    s.sumOf { (minOf(it.endMillis, end) - maxOf(it.startMillis, start)) / 60_000 }
                }
                .filterValues { it > 0 }
            val total = byApp.values.sum()
            if (total > 0) {
                val top = byApp.entries.sortedByDescending { it.value }.take(5)
                    .joinToString(", ") { "${it.key} ${fmtMin(it.value)}" }
                lines += "Screen time: ${fmtMin(total)} — $top."
            }
        }

        val doneTasks = runCatching { db.todos().completedTitlesBetween(start, end) }
            .getOrDefault(emptyList())
        if (doneTasks.isNotEmpty()) {
            lines += "Tasks completed: ${doneTasks.joinToString(", ")}."
        }
        val due = runCatching { db.todos().dueTitlesOnDay(epochDay) }.getOrDefault(emptyList())
        if (due.isNotEmpty()) {
            lines += "Still due: ${due.joinToString(", ")}."
        }

        return lines.joinToString("\n")
    }

    private fun fmtMin(min: Long): String =
        if (min >= 60) "${min / 60}h ${min % 60}m" else "${min}m"
}

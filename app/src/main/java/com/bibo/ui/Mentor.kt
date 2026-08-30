package com.bibo.ui

import android.content.Context
import com.bibo.data.BiboDb
import com.bibo.data.ChatDay
import com.bibo.data.ChatMessage
import com.bibo.data.DeviceCalendarRepo
import com.bibo.data.Rewards
import com.bibo.data.TimerController
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * The mentor chat engine: a Kimi K3-backed coach that sees everything Bibo logs.
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
    /**
     * Kimi K3 (Moonshot AI) via OpenRouter — chosen as the Sonnet-tier equivalent: same
     * $3/$15-per-MTok list pricing as Claude Sonnet 5, matching ~1M context, and the
     * strongest of the current Chinese flagships specifically on tool-heavy agentic work
     * (the thing this app lives on). OpenRouter speaks OpenAI's chat-completions wire
     * format, not Anthropic's Messages API, so this file talks to it directly over
     * OkHttp/org.json rather than through an SDK — there's no Kotlin/Android SDK for
     * OpenRouter and no Anthropic-compatible endpoint to point the old client at.
     */
    private const val MODEL = "moonshotai/kimi-k3"
    private const val OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions"

    private const val PREFS = "mentor"
    private const val KEY_API = "api_key"
    private const val KEY_MEMORY = "memory"
    private const val KEY_PERSONA = "persona"

    /**
     * The editable half of the system prompt — voice and personality only. The operating
     * rules (tools, memory, data honesty) are appended separately so a persona edit can
     * never break the machinery.
     */
    val DEFAULT_PERSONA: String = """
        You are their mentor inside Bibo — an elite performance coach crossed with a sharp
        startup operator. You've watched a hundred people attempt what they're attempting;
        you know exactly where they stall, and you don't sugarcoat it.

        Voice: confident, direct, specific — a smart friend who happens to be an expert,
        not a support bot. Use contractions and the occasional bit of dry humor. Push back
        when they drift, call out wins like you actually mean it, and never lecture. No
        corporate filler, no hedging, no "as an AI".

        Texting style: 2-5 short sentences. No bullet lists or headings unless asked.
        At most one question per message.
    """.trimIndent()

    /** Only the last N raw messages ride along; older context lives in digests. */
    private const val MAX_RAW_MESSAGES = 30

    /** Chat days older than this get a local digest instead of an LLM compaction. */
    private const val MAX_COMPACT_AGE_DAYS = 14

    /** Safety cap on the tool-call loop so a misbehaving turn can't spin forever. */
    private const val MAX_TOOL_ITERATIONS = 10

    /** Intraday memory grows by append; nightly compaction consolidates. Cap the lines. */
    private const val MAX_MEMORY_LINES = 60

    private val sendLock = Mutex()

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder().callTimeout(Duration.ofSeconds(90)).build()
    }

    private class OpenRouterException(val code: Int, message: String) : Exception(message)

    // ---------------------------------------------------------------- prefs

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun apiKey(context: Context): String? =
        prefs(context).getString(KEY_API, null)?.takeIf { it.isNotBlank() }

    fun setApiKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_API, key.trim()).apply()
    }

    fun memory(context: Context): String =
        prefs(context).getString(KEY_MEMORY, null).orEmpty()

    private fun setMemory(context: Context, value: String) {
        prefs(context).edit().putString(KEY_MEMORY, value.trim()).apply()
    }

    /** User-facing edit of the memory notes (from the Mentor tab's Memory view). */
    fun saveMemory(context: Context, value: String) = setMemory(context, value)

    /** The active persona: the user's custom one, or the expert default. */
    fun persona(context: Context): String =
        prefs(context).getString(KEY_PERSONA, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_PERSONA

    /** Save a custom persona; blank restores the default. */
    fun setPersona(context: Context, value: String) {
        prefs(context).edit().putString(KEY_PERSONA, value.trim()).apply()
    }

    /**
     * Append one durable fact to the memory notes right now (mid-conversation), so
     * progress and project/goal facts are saved the moment they surface rather than
     * waiting for the nightly consolidation. Kept bounded by line count; the daily
     * compaction rewrites and de-dupes the whole document.
     */
    fun appendMemory(context: Context, fact: String) {
        val clean = fact.trim().removePrefix("-").trim()
        if (clean.isBlank()) return
        val current = memory(context)
        if (current.contains(clean, ignoreCase = true)) return // already known
        val merged = (if (current.isBlank()) "- $clean" else "$current\n- $clean").lines()
        val trimmed = if (merged.size > MAX_MEMORY_LINES) merged.takeLast(MAX_MEMORY_LINES) else merged
        setMemory(context, trimmed.joinToString("\n"))
    }

    /**
     * One call to OpenRouter's OpenAI-compatible chat-completions endpoint. `messages` is
     * the full turn history as role-tagged JSON objects (system/user/assistant/tool);
     * `tools` is omitted entirely for the fast, no-action call sites. `reasoningEnabled`
     * matters a lot here: Kimi K3 reasons by default, and a simple reply can burn 1000+
     * reasoning tokens before it says anything — fine for the main chat loop, but the
     * three fast paths (check-in, start-cheer, day-compaction) need it off both for cost
     * and because check-in has to finish inside a ~25s broadcast-receiver window.
     */
    private fun openRouterChat(
        apiKey: String,
        messages: JSONArray,
        tools: JSONArray?,
        maxTokens: Int,
        reasoningEnabled: Boolean,
    ): JSONObject {
        val body = JSONObject().apply {
            put("model", MODEL)
            put("messages", messages)
            put("max_tokens", maxTokens)
            if (!reasoningEnabled) put("reasoning", JSONObject().put("enabled", false))
            if (tools != null) {
                put("tools", tools)
                put("tool_choice", "auto")
            }
        }
        val request = Request.Builder()
            .url(OPENROUTER_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        httpClient.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse {
                throw OpenRouterException(resp.code, "Bad response (HTTP ${resp.code}): ${text.take(200)}")
            }
            if (!resp.isSuccessful || json.has("error")) {
                val err = json.optJSONObject("error")
                throw OpenRouterException(resp.code, err?.optString("message")?.ifBlank { null } ?: "HTTP ${resp.code}")
            }
            return json
        }
    }

    private fun messageContent(response: JSONObject): String =
        response.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            .optString("content", "").trim()

    private fun JSONObject.toMap(): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        keys().forEach { k -> map[k] = jsonToKotlin(get(k)) }
        return map
    }

    private fun jsonToKotlin(v: Any?): Any? = when (v) {
        is JSONObject -> v.toMap()
        is JSONArray -> (0 until v.length()).map { jsonToKotlin(v.get(it)) }
        JSONObject.NULL -> null
        else -> v
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
            val key = apiKey(context)
                ?: return@withLock Result.failure(IllegalStateException("No API key set"))

            db.chat().insert(
                ChatMessage(
                    epochDay = today, role = "USER", content = text.trim(),
                    createdAt = System.currentTimeMillis(),
                )
            )

            // Roll finished days into episodic + semantic memory before answering.
            runCatching { compactPendingDays(context, key, today) }

            // Every tool action this turn gets logged here and PERSISTED with the reply
            // (or on its own if the turn then fails). Without this, a crash after tools ran
            // left no record in chat history — so on the next turn the model, seeing no
            // evidence, would re-create the same tasks. That was the duplicate-task bug.
            val actionLog = mutableListOf<String>()
            try {
                val system = buildSystemPrompt(context, today)
                val history = db.chat().since(today - 1).takeLast(MAX_RAW_MESSAGES)
                val messages = historyParams(system, history)
                val tools = MentorTools.definitions()

                // Agentic loop: the model may call tools (create task/goal/event, complete
                // task) before its final text. Execute each against Room, feed results back,
                // repeat until it stops calling tools.
                var reply = "…"
                var iterations = 0
                while (iterations++ < MAX_TOOL_ITERATIONS) {
                    val response = openRouterChat(key, messages, tools, maxTokens = 4096, reasoningEnabled = true)
                    val message = response.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                    val toolCalls = message.optJSONArray("tool_calls")

                    if (toolCalls == null || toolCalls.length() == 0) {
                        reply = message.optString("content", "").trim().ifBlank { "…" }
                        break
                    }

                    // Echo the assistant turn (text + tool_calls) verbatim, then run the
                    // tools and return their results as "tool"-role turns, keyed by the
                    // same call IDs the model just handed us.
                    messages.put(
                        JSONObject().apply {
                            put("role", "assistant")
                            put("content", if (message.isNull("content")) JSONObject.NULL else message.opt("content"))
                            put("tool_calls", toolCalls)
                        }
                    )

                    for (i in 0 until toolCalls.length()) {
                        val tc = toolCalls.getJSONObject(i)
                        val fn = tc.getJSONObject("function")
                        val name = fn.getString("name")
                        val inputMap = runCatching { JSONObject(fn.optString("arguments", "{}")).toMap() }
                            .getOrDefault(emptyMap())
                        val result = MentorTools.execute(context, name, inputMap)
                        // Receipts are for state-changing actions only — reads and quiet
                        // memory work would just be noise.
                        if (name !in setOf("remember", "edit_memory", "search_history", "recall_day") &&
                            result.isNotBlank()
                        ) {
                            actionLog += result
                        }
                        messages.put(
                            JSONObject()
                                .put("role", "tool")
                                .put("tool_call_id", tc.getString("id"))
                                .put("content", result)
                        )
                    }
                }

                // The action receipt rides inside the stored reply, so future turns (and the
                // user) always see what was actually done — even days later.
                val stored = if (actionLog.isEmpty()) reply
                else reply + "\n\n⚙️ " + actionLog.joinToString(" ")
                db.chat().insert(
                    ChatMessage(
                        epochDay = today, role = "ASSISTANT", content = stored,
                        createdAt = System.currentTimeMillis(),
                    )
                )
                Result.success(reply)
            } catch (e: Throwable) {
                // If tools already ran before the failure, persist that record FIRST —
                // otherwise the next turn has no idea those actions happened.
                if (actionLog.isNotEmpty()) {
                    db.chat().insert(
                        ChatMessage(
                            epochDay = today, role = "ASSISTANT",
                            content = "⚙️ " + actionLog.joinToString(" ") + " (connection dropped before I could reply)",
                            createdAt = System.currentTimeMillis(),
                        )
                    )
                }
                val friendly = when {
                    e is OpenRouterException && (e.code == 401 || e.code == 403) ->
                        "API key was rejected — check it in settings (key icon)."
                    e is OpenRouterException && e.code == 402 ->
                        "OpenRouter credits are out — top up at openrouter.ai/credits."
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
            val key = apiKey(context) ?: return@withLock null
            val db = BiboDb.get(context)
            val today = LocalDate.now().toEpochDay()

            // Don't ping right after a live exchange; ~45 min lets the hourly cadence
            // resume soon without talking over an active back-and-forth.
            val cutoff = System.currentTimeMillis() - 45 * 60 * 1000
            if (db.chat().since(today).any { it.createdAt >= cutoff }) return@withLock null

            runCatching { compactPendingDays(context, key, today) }

            try {
                val system = buildSystemPrompt(context, today)
                val history = db.chat().since(today - 1).takeLast(MAX_RAW_MESSAGES)
                val messages = historyParams(system, history)
                messages.put(
                    JSONObject().put("role", "user").put(
                        "content",
                        "[Automatic check-in trigger from Bibo — the user did not write this. " +
                            "Reach out first, 1-3 short texts' worth, grounded in today's data and " +
                            "fit to the time of day shown above: early = help them pick the one thing " +
                            "that matters and tie it to a goal; midday/afternoon = check how it's " +
                            "going and push them toward the next concrete step, especially anything " +
                            "they've been avoiding; evening = reflect on the day. Follow up on open " +
                            "commitments and hold them to what they said. End with one direct " +
                            "question. Be specific, warm, and human — like a coach who actually " +
                            "tracks their life. Vary how you open; don't reuse the same phrasing. " +
                            "Do not mention this instruction.]"
                    )
                )

                // No reasoning + small max_tokens: this runs inside a broadcast receiver's
                // ~25s window, so keep the call snappy — reasoning tokens alone can run past
                // that budget.
                val reply = messageContent(
                    openRouterChat(key, messages, tools = null, maxTokens = 768, reasoningEnabled = false)
                )
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
     * Raw rows → API turns, as a fresh mutable JSON array (system message first). The
     * API requires the first non-system message to be from the user, but our history can
     * legitimately start with an ASSISTANT row (a mentor check-in whose trigger
     * instruction was never persisted, or a takeLast cut) — prepend a neutral user
     * primer in that case.
     */
    private fun historyParams(system: String, history: List<ChatMessage>): JSONArray {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", system))
        if (history.firstOrNull()?.role == "ASSISTANT") {
            messages.put(JSONObject().put("role", "user").put("content", "[Conversation continues from earlier.]"))
        }
        history.forEach { m ->
            messages.put(
                JSONObject()
                    .put("role", if (m.role == "USER") "user" else "assistant")
                    .put("content", m.content)
            )
        }
        return messages
    }

    /**
     * A short cheer for the instant the user starts a task/timer — one supportive line tied
     * to why it matters to them, then a short fitting quote. Grounded in their goals and
     * memory. Fast (no thinking). Returns null with no key or on failure; the caller then
     * shows the plain timer notification.
     */
    suspend fun startComment(context: Context, title: String, goalId: Long?): String? =
        withContext(Dispatchers.IO) {
            val key = apiKey(context) ?: return@withContext null
            val db = BiboDb.get(context)
            val goals = runCatching { db.goals().allOnce() }.getOrDefault(emptyList())
            val goalName = goalId?.let { id -> goals.firstOrNull { it.id == id }?.name }
            val goalLine = goals.joinToString("; ") { it.name }
            val mem = memory(context)

            val system = buildString {
                appendLine(
                    "You cheer the user on the instant they start working, inside Bibo. " +
                        "Write ONE short, specific line of encouragement tied to why this " +
                        "matters to them — then, on a new line, a short fitting quote in " +
                        "quotes with its author. Under 35 words total. Warm and genuine, no " +
                        "preamble, at most one emoji."
                )
                if (goalLine.isNotBlank()) appendLine("Their long-term goals: $goalLine.")
                if (mem.isNotBlank()) {
                    appendLine("What you know about them:")
                    appendLine(mem.take(600))
                }
            }
            val user = "They just started: \"$title\"" +
                (goalName?.let { " (part of the goal: $it)" } ?: "") + ". Cheer them on."

            try {
                val messages = JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user))
                messageContent(
                    openRouterChat(key, messages, tools = null, maxTokens = 300, reasoningEnabled = false)
                ).ifBlank { null }
            } catch (_: Throwable) {
                null
            }
        }

    // ----------------------------------------------------- daily compaction

    /**
     * Rolls every finished day into a [ChatDay] digest. Days with conversation are
     * compacted by the model (which also rewrites the memory notes); data-only days
     * get a free local digest so the mentor still knows what happened.
     */
    private suspend fun compactPendingDays(context: Context, apiKey: String, today: Long) {
        val db = BiboDb.get(context)

        db.chat().undigestedDays(today).forEach { day ->
            if (day >= today - MAX_COMPACT_AGE_DAYS) {
                llmCompactDay(context, apiKey, day)
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

    private suspend fun llmCompactDay(context: Context, apiKey: String, day: Long) {
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

        val out = messageContent(
            openRouterChat(
                apiKey,
                JSONArray().put(JSONObject().put("role", "user").put("content", prompt)),
                tools = null, maxTokens = 1536, reasoningEnabled = false,
            )
        )

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

        // The mentor must see the live task list — without it, it can't know what already
        // exists and will happily re-create the same plan (the duplicate-task bug).
        val allTasks = db.todos().allOnce()
        val goalNames = db.goals().allOnce().associate { it.id to it.name }
        val openTasks = allTasks
            .filter { it.parentId == null && it.completedAt == null }
            .take(25)
            .joinToString("\n") { t ->
                val subs = allTasks.filter { it.parentId == t.id }
                buildString {
                    append("- ${t.title}")
                    val bits = mutableListOf<String>()
                    t.goalId?.let { id -> goalNames[id]?.let { bits += "goal: $it" } }
                    if (subs.isNotEmpty()) bits += "${subs.count { it.completedAt != null }}/${subs.size} steps done"
                    if (t.rewardCents > 0) bits += "worth ${t.rewardCents / 100}$"
                    t.dueEpochDay?.let {
                        bits += if (it < today) "OVERDUE since ${LocalDate.ofEpochDay(it)}"
                        else "due ${LocalDate.ofEpochDay(it)}"
                    }
                    if (bits.isNotEmpty()) append(" (${bits.joinToString(", ")})")
                }
            }

        val todayFacts = gatherDayFacts(context, today)

        // What's happening right now (running timer / focus session).
        val rightNow = if (TimerController.isRunning(context)) {
            val min = (System.currentTimeMillis() - TimerController.runningStart(context)) / 60_000
            "Timing \"${TimerController.runningTitle(context)}\" — ${min}m in" +
                (TimerController.goalId(context)?.let { id -> goalNames[id]?.let { " (goal: $it)" } } ?: "")
        } else {
            null
        }

        // The week ahead: calendar events, due tasks, goal targets (tomorrow .. +7 days).
        val zone = ZoneId.systemDefault()
        val fmtDay = DateTimeFormatter.ofPattern("EEE MMM d")
        val fmtTime = DateTimeFormatter.ofPattern("HH:mm")
        val upcoming = buildList {
            val calendar = DeviceCalendarRepo(context)
            if (calendar.hasPermissions()) {
                val start = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val end = date.plusDays(8).atStartOfDay(zone).toInstant().toEpochMilli()
                calendar.queryInstances(start, end).sortedBy { it.begin }.take(15).forEach { e ->
                    val z = Instant.ofEpochMilli(e.begin).atZone(zone)
                    add(
                        "${z.format(fmtDay)}: ${e.title}" +
                            if (e.allDay) " (all-day)" else " at ${z.format(fmtTime)}"
                    )
                }
            }
            allTasks.filter {
                it.parentId == null && it.completedAt == null &&
                    it.dueEpochDay != null && it.dueEpochDay > today && it.dueEpochDay <= today + 7
            }.sortedBy { it.dueEpochDay }.forEach { t ->
                add("${LocalDate.ofEpochDay(t.dueEpochDay!!).format(fmtDay)}: task \"${t.title}\" due")
            }
            db.goals().allOnce().filter { it.targetDate != null && it.targetDate!! in (today + 1)..(today + 7) }
                .forEach { g ->
                    add("${LocalDate.ofEpochDay(g.targetDate!!).format(fmtDay)}: GOAL TARGET — ${g.name}")
                }
        }.joinToString("\n")

        // Treat-money state: what they've earned, can spend, and are saving toward.
        val treats = buildString {
            val earned = runCatching { Rewards.earnedCents(context) }.getOrDefault(0)
            val available = runCatching { Rewards.availableCents(context) }.getOrDefault(0)
            append("Earned ${Rewards.format(earned)} of ${Rewards.format(Rewards.budgetCents(context))} weekly budget")
            append("; ${Rewards.format(available)} available to spend (resets Monday).")
            val wishes = runCatching { db.wishlist().allOnce() }.getOrDefault(emptyList())
            if (wishes.isNotEmpty()) {
                append(" Wishlist: ")
                append(
                    wishes.joinToString(", ") { w ->
                        "${w.name} ${Rewards.format(w.priceCents)}" + if (w.redeemedAt != null) " (treated ✓)" else ""
                    }
                )
            }
        }

        return buildString {
            appendLine(persona(context))
            appendLine()
            appendLine(
                """
                Operating rules (always apply, regardless of the personality above):
                - You are texting with the one person who uses this app. Everything below is
                  their real logged data — goals, tasks, focus sessions, habits, food, screen
                  time, day summaries. Never invent data; if it isn't below and a search finds
                  nothing, say you don't know.
                - Mentor, not assistant: follow up on what they said they'd do, hold them
                  accountable, and keep tying today's actions to their long-term goals and WHY
                  those matter to them. Ground recommendations in their actual numbers.
                - You can ACT, not just talk: create tasks (broken into the smallest concrete
                  steps as subtasks, filed under the right goal, with treat-money rewards),
                  complete / edit / delete tasks, add steps to existing tasks, create / edit /
                  retire goals, add / move / cancel calendar events, log food they mention,
                  tick habits, start or stop their focus timer, and set reminders. Don't ask
                  permission for obvious, reversible actions — do it and say what you set up
                  in one sentence.
                - TODAY SO FAR + RIGHT NOW + NEXT 7 DAYS + TREATS below are their complete
                  current picture — read it before answering. Plan against the week ahead
                  (warn about tomorrow's events, overdue tasks, approaching goal targets),
                  and use treat money as a lever: remind them what finishing a task earns
                  and what's within reach on their wishlist.
                - OPEN TASKS below is the live list of what already exists. NEVER create a
                  task that duplicates one of them (same plan, reworded) — reference the
                  existing one, or use edit_task / delete_task to change or clean it up.
                - Your context only shows the last ~2 days of chat and 7 days of summaries,
                  but EVERYTHING older is searchable. When they ask about something you don't
                  see — a past decision, an old plan, "what did we say about X" — use
                  search_history (and recall_day for a specific date) BEFORE saying you don't
                  remember.
                - Keep a memory. Use remember to save durable facts the moment they surface —
                  decisions, project/goal details, deadlines, milestones, preferences,
                  commitments — from what they say OR what you notice in their data. Use
                  edit_memory to rewrite your notes when something changed or went stale.
                  Save quietly; don't announce it.
                - When they're stuck or avoiding a first step, set a reminder on that step at
                  a concrete time, with a note tying the tiny action to the big payoff.
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
            appendLine()
            appendLine("[OPEN TASKS — these already exist; never re-create them]")
            appendLine(openTasks.ifBlank { "(none)" })
            if (digests.isNotBlank()) {
                appendLine()
                appendLine("[RECENT DAYS]")
                appendLine(digests)
            }
            appendLine()
            appendLine("[TODAY SO FAR]")
            appendLine(todayFacts.ifBlank { "Nothing logged yet today." })
            rightNow?.let {
                appendLine()
                appendLine("[RIGHT NOW]")
                appendLine(it)
            }
            if (upcoming.isNotBlank()) {
                appendLine()
                appendLine("[NEXT 7 DAYS]")
                appendLine(upcoming)
            }
            appendLine()
            appendLine("[TREATS THIS WEEK]")
            appendLine(treats)
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

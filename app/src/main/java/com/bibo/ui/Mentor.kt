package com.bibo.ui

import android.content.Context
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.ThinkingConfigAdaptive
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.ToolUseBlockParam
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

            // Every tool action this turn gets logged here and PERSISTED with the reply
            // (or on its own if the turn then fails). Without this, a crash after tools ran
            // left no record in chat history — so on the next turn the model, seeing no
            // evidence, would re-create the same tasks. That was the duplicate-task bug.
            val actionLog = mutableListOf<String>()
            try {
                val system = buildSystemPrompt(context, today)
                val history = db.chat().since(today - 1).takeLast(MAX_RAW_MESSAGES)
                val messages = historyParams(history).toMutableList()

                // Agentic loop: the model may call tools (create task/goal/event, complete
                // task) before its final text. Execute each against Room, feed results back,
                // repeat until it stops calling tools.
                var reply = "…"
                var iterations = 0
                while (iterations++ < MAX_TOOL_ITERATIONS) {
                    val builder = MessageCreateParams.builder()
                        .model("claude-opus-4-8")
                        .maxTokens(4096L)
                        .thinking(ThinkingConfigAdaptive.builder().build())
                        .system(system)
                    MentorTools.definitions().forEach { builder.addTool(it) }
                    messages.forEach { builder.addMessage(it) }

                    val response = client.messages().create(builder.build())
                    val toolUses = response.content().mapNotNull { it.toolUse().orElse(null) }

                    if (toolUses.isEmpty()) {
                        reply = responseText(response).ifBlank { "…" }
                        break
                    }

                    // Echo the assistant turn (text + tool_use blocks) verbatim, then run
                    // the tools and return their results as the next user turn.
                    messages += MessageParam.builder()
                        .role(MessageParam.Role.ASSISTANT)
                        .contentOfBlockParams(assistantEcho(response))
                        .build()

                    val results = toolUses.map { tu ->
                        val inputMap = runCatching { tu._input().convert(Map::class.java) as? Map<*, *> }
                            .getOrNull() ?: emptyMap<Any, Any>()
                        val result = MentorTools.execute(context, tu.name(), inputMap)
                        // Receipts are for state-changing actions only — reads and quiet
                        // memory work would just be noise.
                        if (tu.name() !in setOf("remember", "edit_memory", "search_history", "recall_day") &&
                            result.isNotBlank()
                        ) {
                            actionLog += result
                        }
                        ContentBlockParam.ofToolResult(
                            ToolResultBlockParam.builder().toolUseId(tu.id()).content(result).build()
                        )
                    }
                    messages += MessageParam.builder()
                        .role(MessageParam.Role.USER)
                        .contentOfBlockParams(results)
                        .build()
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

            // Don't ping right after a live exchange; ~45 min lets the hourly cadence
            // resume soon without talking over an active back-and-forth.
            val cutoff = System.currentTimeMillis() - 45 * 60 * 1000
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
    /** The plain-text portion of a response, joined across text blocks. */
    private fun responseText(response: Message): String =
        response.content()
            .mapNotNull { block -> block.text().map { it.text() }.orElse(null) }
            .joinToString("\n")
            .trim()

    /**
     * Rebuild the assistant turn (text + tool_use blocks) as params so it can be echoed
     * back — the API requires the tool_use blocks to precede their tool_result blocks.
     * Thinking blocks are dropped; they aren't required for the follow-up turn here.
     */
    private fun assistantEcho(response: Message): List<ContentBlockParam> =
        response.content().mapNotNull { block ->
            block.text().map { t ->
                ContentBlockParam.ofText(TextBlockParam.builder().text(t.text()).build())
            }.orElseGet {
                block.toolUse().map { tu ->
                    ContentBlockParam.ofToolUse(
                        ToolUseBlockParam.builder()
                            .id(tu.id())
                            .name(tu.name())
                            .input(tu._input().convert(ToolUseBlockParam.Input::class.java)!!)
                            .build()
                    )
                }.orElse(null)
            }
        }

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

    /**
     * A short cheer for the instant the user starts a task/timer — one supportive line tied
     * to why it matters to them, then a short fitting quote. Grounded in their goals and
     * memory. Fast (no thinking). Returns null with no key or on failure; the caller then
     * shows the plain timer notification.
     */
    suspend fun startComment(context: Context, title: String, goalId: Long?): String? =
        withContext(Dispatchers.IO) {
            val client = client(context) ?: return@withContext null
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
                val resp = client.messages().create(
                    MessageCreateParams.builder()
                        .model("claude-opus-4-8")
                        .maxTokens(200L)
                        .system(system)
                        .addUserMessage(user)
                        .build()
                )
                responseText(resp).ifBlank { null }
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
                    t.dueEpochDay?.let { bits += "due ${LocalDate.ofEpochDay(it)}" }
                    if (bits.isNotEmpty()) append(" (${bits.joinToString(", ")})")
                }
            }

        val todayFacts = gatherDayFacts(context, today)

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
                  complete / edit / delete tasks, create goals, add calendar events, and set
                  reminders. Don't ask permission for obvious, reversible actions — do it and
                  say what you set up in one sentence.
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

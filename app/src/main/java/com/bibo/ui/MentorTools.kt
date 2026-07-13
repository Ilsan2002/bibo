package com.bibo.ui

import android.content.Context
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Tool
import com.bibo.data.ActivityBlock
import com.bibo.data.BiboDb
import com.bibo.data.DeviceCalendarEvent
import com.bibo.data.DeviceCalendarRepo
import com.bibo.data.FocusConfig
import com.bibo.data.FoodEntry
import com.bibo.data.Goal
import com.bibo.data.HabitDay
import com.bibo.data.TimerController
import com.bibo.data.TaskReminders
import com.bibo.data.TodoTask
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The tools the mentor can call to actually change things — create tasks (auto-broken
 * into small subtasks), goals, and calendar events, and tick tasks off. Everything runs
 * against the same Room DB and Google Calendar the rest of the app uses, so changes show
 * up instantly in the Tasks / Calendar / Goals tabs.
 */
object MentorTools {

    // Distinct goal accent colors (ARGB) the mentor rotates through for new goals.
    private val PALETTE = listOf(
        0xFF6750A4.toInt(), 0xFF386A20.toInt(), 0xFFB3261E.toInt(),
        0xFF00639B.toInt(), 0xFF8C4A00.toInt(), 0xFF6D4AFF.toInt(),
    )

    private fun strProp(desc: String) =
        JsonValue.from(mapOf("type" to "string", "description" to desc))

    fun definitions(): List<Tool> = listOf(
        Tool.builder()
            .name("create_task")
            .description(
                "Create a to-do task for the user. Break anything non-trivial into the " +
                    "smallest concrete first steps and pass them as subtasks — a big task " +
                    "feels doable once it's split up. File it under a goal when it clearly " +
                    "advances one."
            )
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(
                        Tool.InputSchema.Properties.builder()
                            .putAdditionalProperty("title", strProp("Short title of the overall task"))
                            .putAdditionalProperty(
                                "subtasks",
                                JsonValue.from(
                                    mapOf(
                                        "type" to "array",
                                        "items" to mapOf("type" to "string"),
                                        "description" to "Ordered smallest concrete steps to finish it",
                                    )
                                )
                            )
                            .putAdditionalProperty("goal", strProp("Name of an existing goal to file this under (optional)"))
                            .putAdditionalProperty("due_date", strProp("Due date as YYYY-MM-DD (optional)"))
                            .putAdditionalProperty("reminder", strProp("When to nudge them, as 'YYYY-MM-DD HH:mm' 24h (optional)"))
                            .putAdditionalProperty(
                                "reminder_note",
                                strProp(
                                    "One motivating line for the reminder that ties this small step to the " +
                                        "bigger goal (e.g. 'Open the laptop and make the first call — this is " +
                                        "what kicks off your first client'). Required if reminder is set."
                                )
                            )
                            .putAdditionalProperty(
                                "reward_dollars",
                                JsonValue.from(
                                    mapOf(
                                        "type" to "number",
                                        "description" to "Treat-money reward for finishing, by difficulty: ~1 " +
                                            "for a quick task, 3 medium, 5 hard, 10 for a big one (optional).",
                                    )
                                )
                            )
                            .build()
                    )
                    .required(listOf("title"))
                    .build()
            )
            .build(),
        Tool.builder()
            .name("create_goal")
            .description("Create a long-term goal folder. Capture the why in details when the user shares it.")
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(
                        Tool.InputSchema.Properties.builder()
                            .putAdditionalProperty("name", strProp("Short name of the goal"))
                            .putAdditionalProperty("details", strProp("Description and why it matters to them (optional)"))
                            .putAdditionalProperty("target_date", strProp("Target date as YYYY-MM-DD (optional)"))
                            .build()
                    )
                    .required(listOf("name"))
                    .build()
            )
            .build(),
        Tool.builder()
            .name("add_calendar_event")
            .description("Put an event on the user's calendar (their Google Calendar if connected).")
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(
                        Tool.InputSchema.Properties.builder()
                            .putAdditionalProperty("title", strProp("Event title"))
                            .putAdditionalProperty("date", strProp("Date as YYYY-MM-DD"))
                            .putAdditionalProperty("start_time", strProp("Start time as HH:mm (24h)"))
                            .putAdditionalProperty("end_time", strProp("End time as HH:mm (24h); optional, defaults to +1h"))
                            .build()
                    )
                    .required(listOf("title", "date", "start_time"))
                    .build()
            )
            .build(),
        Tool.builder()
            .name("complete_task")
            .description("Mark one of the user's open tasks done, matched by its title.")
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(
                        Tool.InputSchema.Properties.builder()
                            .putAdditionalProperty("title", strProp("Title (or part of it) of the task to complete"))
                            .build()
                    )
                    .required(listOf("title"))
                    .build()
            )
            .build(),
        Tool.builder()
            .name("delete_task")
            .description(
                "Delete a task (and its subtasks) by title — for clearing duplicates or things " +
                    "that no longer matter. Deletes one match per call and tells you how many " +
                    "similar ones remain, so call it again to remove more (e.g. to dedupe, delete " +
                    "the extras and keep one). Set all_matching to true to remove every match at once."
            )
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(
                        Tool.InputSchema.Properties.builder()
                            .putAdditionalProperty("title", strProp("Title (or part of it) of the task to delete"))
                            .putAdditionalProperty(
                                "all_matching",
                                JsonValue.from(mapOf("type" to "boolean", "description" to "Delete every task matching the title (optional)"))
                            )
                            .build()
                    )
                    .required(listOf("title"))
                    .build()
            )
            .build(),
        Tool.builder()
            .name("edit_task")
            .description("Change an existing task — rename it, re-file it under a goal, set a due date, or set its reward.")
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(
                        Tool.InputSchema.Properties.builder()
                            .putAdditionalProperty("title", strProp("Current title (or part of it) of the task to edit"))
                            .putAdditionalProperty("new_title", strProp("New title (optional)"))
                            .putAdditionalProperty("goal", strProp("Move it under this existing goal by name (optional)"))
                            .putAdditionalProperty("due_date", strProp("Set the due date as YYYY-MM-DD (optional)"))
                            .putAdditionalProperty(
                                "reward_dollars",
                                JsonValue.from(mapOf("type" to "number", "description" to "Set the treat-money reward in dollars (optional)"))
                            )
                            .build()
                    )
                    .required(listOf("title"))
                    .build()
            )
            .build(),
        Tool.builder()
            .name("edit_goal")
            .description("Change a long-term goal — rename it, rewrite its details/why, or set its target date.")
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(
                        Tool.InputSchema.Properties.builder()
                            .putAdditionalProperty("name", strProp("Current name (or part) of the goal"))
                            .putAdditionalProperty("new_name", strProp("New name (optional)"))
                            .putAdditionalProperty("details", strProp("New description / why it matters (optional)"))
                            .putAdditionalProperty("target_date", strProp("New target date YYYY-MM-DD (optional)"))
                            .build()
                    )
                    .required(listOf("name"))
                    .build()
            )
            .build(),
        Tool.builder()
            .name("delete_goal")
            .description("Retire a goal that no longer matters (it's archived; its tasks stay but lose the folder).")
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(
                        Tool.InputSchema.Properties.builder()
                            .putAdditionalProperty("name", strProp("Name (or part) of the goal to retire"))
                            .build()
                    )
                    .required(listOf("name"))
                    .build()
            )
            .build(),
        Tool.builder()
            .name("edit_calendar_event")
            .description(
                "Move or rename an upcoming calendar event (next 14 days), matched by title. " +
                    "Recurring Google events can't be edited — you'll be told if so."
            )
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(
                        Tool.InputSchema.Properties.builder()
                            .putAdditionalProperty("title", strProp("Title (or part) of the event to change"))
                            .putAdditionalProperty("new_title", strProp("New title (optional)"))
                            .putAdditionalProperty("new_date", strProp("New date YYYY-MM-DD (optional)"))
                            .putAdditionalProperty("new_start_time", strProp("New start HH:mm 24h (optional)"))
                            .putAdditionalProperty("new_end_time", strProp("New end HH:mm 24h (optional)"))
                            .build()
                    )
                    .required(listOf("title"))
                    .build()
            )
            .build(),
        Tool.builder()
            .name("delete_calendar_event")
            .description("Cancel an upcoming calendar event (next 14 days), matched by title.")
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(
                        Tool.InputSchema.Properties.builder()
                            .putAdditionalProperty("title", strProp("Title (or part) of the event to cancel"))
                            .build()
                    )
                    .required(listOf("title"))
                    .build()
            )
            .build(),
        Tool.builder()
            .name("add_subtask")
            .description("Add one or more steps to an EXISTING task (appended after its current steps).")
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(
                        Tool.InputSchema.Properties.builder()
                            .putAdditionalProperty("task", strProp("Title (or part) of the existing task"))
                            .putAdditionalProperty(
                                "steps",
                                JsonValue.from(
                                    mapOf(
                                        "type" to "array", "items" to mapOf("type" to "string"),
                                        "description" to "The step(s) to add, in order",
                                    )
                                )
                            )
                            .build()
                    )
                    .required(listOf("task", "steps"))
                    .build()
            )
            .build(),
        Tool.builder()
            .name("log_food")
            .description(
                "Log something they ate or drank today (they mention food in chat -> log it). " +
                    "YOU estimate the nutrition numbers."
            )
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(
                        Tool.InputSchema.Properties.builder()
                            .putAdditionalProperty("label", strProp("Short name, e.g. 'burger and fries'"))
                            .putAdditionalProperty("calories", JsonValue.from(mapOf("type" to "number", "description" to "Estimated kcal")))
                            .putAdditionalProperty("sugar_g", JsonValue.from(mapOf("type" to "number", "description" to "Estimated sugar grams (optional)")))
                            .putAdditionalProperty("caffeine_mg", JsonValue.from(mapOf("type" to "number", "description" to "Estimated caffeine mg (optional)")))
                            .build()
                    )
                    .required(listOf("label", "calories"))
                    .build()
            )
            .build(),
        Tool.builder()
            .name("set_habit")
            .description("Mark one of today's habits done or not: showered, clean_clothes, worked_out, prayed.")
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(
                        Tool.InputSchema.Properties.builder()
                            .putAdditionalProperty(
                                "habit",
                                JsonValue.from(
                                    mapOf(
                                        "type" to "string",
                                        "enum" to listOf("showered", "clean_clothes", "worked_out", "prayed"),
                                    )
                                )
                            )
                            .putAdditionalProperty("done", JsonValue.from(mapOf("type" to "boolean")))
                            .build()
                    )
                    .required(listOf("habit", "done"))
                    .build()
            )
            .build(),
        Tool.builder()
            .name("start_focus")
            .description(
                "Start the shared focus timer on something (shows on the Focus page, notification, " +
                    "and lock screen). Use when they say they're starting work on something now. Set " +
                    "block_distractions when they want to lock in — it silences the phone (DND) and " +
                    "blocks the distracting apps they blocked last time."
            )
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(
                        Tool.InputSchema.Properties.builder()
                            .putAdditionalProperty("intention", strProp("What they're focusing on"))
                            .putAdditionalProperty("goal", strProp("Existing goal this serves, by name (optional)"))
                            .putAdditionalProperty(
                                "block_distractions",
                                JsonValue.from(
                                    mapOf(
                                        "type" to "boolean",
                                        "description" to "Silence the phone + block their usual distracting apps",
                                    )
                                )
                            )
                            .build()
                    )
                    .required(listOf("intention"))
                    .build()
            )
            .build(),
        Tool.builder()
            .name("stop_timer")
            .description("Stop the currently running timer/focus session; it's saved to the calendar.")
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(
                        Tool.InputSchema.Properties.builder()
                            .putAdditionalProperty("reflection", strProp("One-line note on how it went (optional)"))
                            .build()
                    )
                    .build()
            )
            .build(),
        Tool.builder()
            .name("search_history")
            .description(
                "Search EVERYTHING you and the user have ever said, all past day summaries, " +
                    "and your memory notes — far beyond what's in your context. Use it whenever " +
                    "they reference something you don't see (an old decision, plan, name, or " +
                    "number) before saying you don't remember. Keyword or short phrase; if no " +
                    "hits, retry with a different word."
            )
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(
                        Tool.InputSchema.Properties.builder()
                            .putAdditionalProperty("query", strProp("Keyword or short phrase to search for"))
                            .build()
                    )
                    .required(listOf("query"))
                    .build()
            )
            .build(),
        Tool.builder()
            .name("recall_day")
            .description("Pull up a specific past day: its summary plus the full conversation from that date.")
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(
                        Tool.InputSchema.Properties.builder()
                            .putAdditionalProperty("date", strProp("The day to recall, as YYYY-MM-DD"))
                            .build()
                    )
                    .required(listOf("date"))
                    .build()
            )
            .build(),
        Tool.builder()
            .name("edit_memory")
            .description(
                "Rewrite your long-term memory notes in full — to correct a fact, drop stale " +
                    "items, or reorganize. Pass the complete new notes; they replace the old ones."
            )
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(
                        Tool.InputSchema.Properties.builder()
                            .putAdditionalProperty("notes", strProp("The complete new memory notes (max ~180 words)"))
                            .build()
                    )
                    .required(listOf("notes"))
                    .build()
            )
            .build(),
        Tool.builder()
            .name("remember")
            .description(
                "Save one durable fact to your long-term memory so you keep it across days. " +
                    "Use it whenever something worth carrying forward surfaces — a decision or " +
                    "change of direction, a project or goal detail, a milestone or progress " +
                    "update, a preference, a deadline, or a commitment — whether it comes from " +
                    "what they say, or from what you see in their tasks and calendar. Save " +
                    "quietly; don't announce every save. Keep each fact to one concise sentence."
            )
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(
                        Tool.InputSchema.Properties.builder()
                            .putAdditionalProperty("fact", strProp("The single fact to remember, one concise sentence"))
                            .build()
                    )
                    .required(listOf("fact"))
                    .build()
            )
            .build(),
    )

    /** Dispatch a tool call. Returns a short result string fed back to the model. */
    suspend fun execute(context: Context, name: String, input: Map<*, *>): String =
        try {
            when (name) {
                "create_task" -> createTask(context, input)
                "create_goal" -> createGoal(context, input)
                "add_calendar_event" -> addEvent(context, input)
                "complete_task" -> completeTask(context, input)
                "delete_task" -> deleteTask(context, input)
                "edit_task" -> editTask(context, input)
                "edit_goal" -> editGoal(context, input)
                "delete_goal" -> deleteGoal(context, input)
                "edit_calendar_event" -> editCalendarEvent(context, input)
                "delete_calendar_event" -> deleteCalendarEvent(context, input)
                "add_subtask" -> addSubtask(context, input)
                "log_food" -> logFood(context, input)
                "set_habit" -> setHabit(context, input)
                "start_focus" -> startFocus(context, input)
                "stop_timer" -> stopTimerTool(context, input)
                "search_history" -> searchHistory(context, input)
                "recall_day" -> recallDay(context, input)
                "edit_memory" -> editMemory(context, input)
                "remember" -> remember(context, input)
                else -> "Unknown tool: $name"
            }
        } catch (e: Throwable) {
            "Failed: ${e.message ?: "error"}"
        }

    private fun str(input: Map<*, *>, key: String): String? =
        (input[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }

    private fun day(input: Map<*, *>, key: String): Long? =
        str(input, key)?.let { runCatching { LocalDate.parse(it).toEpochDay() }.getOrNull() }

    private fun matchGoal(goals: List<Goal>, q: String?): Goal? {
        if (q.isNullOrBlank()) return null
        return goals.firstOrNull { it.name.equals(q, true) }
            ?: goals.firstOrNull { it.name.contains(q, true) || q.contains(it.name, true) }
    }

    private suspend fun createTask(context: Context, input: Map<*, *>): String {
        val db = BiboDb.get(context)
        val title = str(input, "title") ?: return "A task needs a title."
        // Hard duplicate guard: an identical open task means this was already created
        // (a retried turn, or the model forgot). Refuse rather than double up.
        val existingOpen = db.todos().allOnce()
            .firstOrNull { it.parentId == null && it.completedAt == null && it.title.equals(title, true) }
        if (existingOpen != null) {
            return "\"$title\" is ALREADY on the list — not creating a duplicate. " +
                "Reference it, or use edit_task / delete_task to change it."
        }
        val subtasks = (input["subtasks"] as? List<*>)
            ?.mapNotNull { (it as? String)?.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val goal = matchGoal(db.goals().allOnce(), str(input, "goal"))
        val due = day(input, "due_date")
        val now = System.currentTimeMillis()
        val reminderAt = str(input, "reminder")?.let {
            runCatching {
                LocalDateTime.parse(it, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }.getOrNull()
        }
        val reminderNote = str(input, "reminder_note")
        val rewardCents = (input["reward_dollars"] as? Number)?.let { (it.toDouble() * 100).toInt() }
            ?.coerceIn(0, 10000) ?: 0

        val parentId = db.todos().insert(
            TodoTask(
                title = title, createdAt = now, sortOrder = now, goalId = goal?.id, dueEpochDay = due,
                reminderAt = reminderAt, reminderNote = reminderNote, rewardCents = rewardCents,
            )
        )
        subtasks.forEachIndexed { i, s ->
            db.todos().insert(
                TodoTask(title = s, parentId = parentId, createdAt = now + i + 1, sortOrder = (i + 1).toLong(), goalId = goal?.id)
            )
        }
        reminderAt?.let { TaskReminders.schedule(context, parentId, it) }
        return buildString {
            append("Created \"$title\"")
            if (subtasks.isNotEmpty()) append(" with ${subtasks.size} step${if (subtasks.size > 1) "s" else ""}")
            goal?.let { append(" under goal \"${it.name}\"") }
            due?.let { append(", due ${LocalDate.ofEpochDay(it).format(DateTimeFormatter.ofPattern("EEE MMM d"))}") }
            reminderAt?.let {
                append(", reminder set for ${LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(it), ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE MMM d HH:mm"))}")
            }
            append(".")
        }
    }

    private suspend fun createGoal(context: Context, input: Map<*, *>): String {
        val db = BiboDb.get(context)
        val name = str(input, "name") ?: return "A goal needs a name."
        val existing = db.goals().allOnce()
        if (existing.any { it.name.equals(name, true) }) return "A goal called \"$name\" already exists."
        db.goals().insert(
            Goal(
                name = name,
                color = PALETTE[existing.size % PALETTE.size],
                targetDate = day(input, "target_date"),
                createdAt = System.currentTimeMillis(),
                details = str(input, "details"),
            )
        )
        return "Created goal \"$name\"."
    }

    private suspend fun addEvent(context: Context, input: Map<*, *>): String {
        val db = BiboDb.get(context)
        val title = str(input, "title") ?: return "An event needs a title."
        val date = str(input, "date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: return "Couldn't read the date (need YYYY-MM-DD)."
        val start = str(input, "start_time")?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
            ?: return "Couldn't read the start time (need HH:mm)."
        val end = str(input, "end_time")?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
            ?: start.plusHours(1)
        val zone = ZoneId.systemDefault()
        val startMillis = date.atTime(start).atZone(zone).toInstant().toEpochMilli()
        val endMillis = date.atTime(if (end.isAfter(start)) end else start.plusHours(1))
            .atZone(zone).toInstant().toEpochMilli()

        val repo = DeviceCalendarRepo(context)
        val when0 = "${date.format(DateTimeFormatter.ofPattern("EEE MMM d"))} at $start"
        return if (repo.hasPermissions() && repo.insertEvent(title, startMillis, endMillis)) {
            "Added \"$title\" to your Google Calendar, $when0."
        } else {
            db.activityBlocks().insert(
                ActivityBlock(title = title, startMillis = startMillis, endMillis = endMillis, source = "MANUAL")
            )
            "Added \"$title\" to your Bibo calendar, $when0."
        }
    }

    private suspend fun deleteTask(context: Context, input: Map<*, *>): String {
        val db = BiboDb.get(context)
        val q = str(input, "title") ?: return "Which task?"
        val all = db.todos().allOnce()
        val parents = all.filter { it.parentId == null }
        var matches = parents.filter { it.title.equals(q, true) }
            .ifEmpty { parents.filter { it.title.contains(q, true) || q.contains(it.title, true) } }
        if (matches.isEmpty()) {
            // No parent matched — try subtasks (deleting one removes just that step).
            val subs = all.filter { it.parentId != null }
            matches = subs.filter { it.title.equals(q, true) }
                .ifEmpty { subs.filter { it.title.contains(q, true) || q.contains(it.title, true) } }
        }
        if (matches.isEmpty()) return "No task matching \"$q\"."

        val deleteAll = input["all_matching"] == true
        val toDelete = if (deleteAll) matches else listOf(matches.first())
        toDelete.forEach { t ->
            db.todos().deleteChildren(t.id)
            db.todos().delete(t)
        }
        val remaining = matches.size - toDelete.size
        return if (deleteAll) {
            "Deleted ${toDelete.size} task(s) matching \"$q\"."
        } else {
            "Deleted \"${toDelete.first().title}\"." +
                if (remaining > 0) " $remaining more like it remain — call again to remove another." else ""
        }
    }

    private suspend fun editTask(context: Context, input: Map<*, *>): String {
        val db = BiboDb.get(context)
        val q = str(input, "title") ?: return "Which task?"
        val everything = db.todos().allOnce()
        val all = everything.filter { it.parentId == null }
        val task = all.firstOrNull { it.title.equals(q, true) }
            ?: all.firstOrNull { it.title.contains(q, true) || q.contains(it.title, true) }
            ?: everything.firstOrNull { it.parentId != null && it.title.equals(q, true) } // subtask rename
            ?: everything.firstOrNull { it.parentId != null && (it.title.contains(q, true) || q.contains(it.title, true)) }
            ?: return "No task matching \"$q\"."

        val newTitle = str(input, "new_title")
        val goal = matchGoal(db.goals().allOnce(), str(input, "goal"))
        val due = day(input, "due_date")
        val reward = (input["reward_dollars"] as? Number)?.let { (it.toDouble() * 100).toInt() }?.coerceIn(0, 10000)

        db.todos().update(
            task.copy(
                title = newTitle ?: task.title,
                goalId = goal?.id ?: task.goalId,
                dueEpochDay = due ?: task.dueEpochDay,
                rewardCents = reward ?: task.rewardCents,
            )
        )
        return "Updated \"${newTitle ?: task.title}\"."
    }

    private fun remember(context: Context, input: Map<*, *>): String {
        val fact = str(input, "fact") ?: return "Nothing to remember."
        Mentor.appendMemory(context, fact)
        return "Saved to memory."
    }

    private suspend fun editGoal(context: Context, input: Map<*, *>): String {
        val db = BiboDb.get(context)
        // Reach archived goals too — editing a retired goal revives it.
        val goal = matchGoal(db.goals().allWithArchived(), str(input, "name"))
            ?: return "No goal matching \"${str(input, "name")}\"."
        db.goals().update(
            goal.copy(
                name = str(input, "new_name") ?: goal.name,
                details = str(input, "details") ?: goal.details,
                targetDate = day(input, "target_date") ?: goal.targetDate,
                archived = false,
            )
        )
        val newName = str(input, "new_name") ?: goal.name
        return if (goal.archived) "Restored and updated goal \"$newName\"."
        else "Updated goal \"$newName\"."
    }

    private suspend fun deleteGoal(context: Context, input: Map<*, *>): String {
        val db = BiboDb.get(context)
        val goal = matchGoal(db.goals().allWithArchived(), str(input, "name"))
            ?: return "No goal matching \"${str(input, "name")}\"."
        if (goal.archived) return "Goal \"${goal.name}\" is already retired."
        db.goals().update(goal.copy(archived = true))
        return "Retired goal \"${goal.name}\" (its tasks remain, just without the folder)."
    }

    /** Find an upcoming Google Calendar instance (next 14 days) by fuzzy title. */
    private fun findUpcomingEvent(context: Context, q: String): DeviceCalendarEvent? {
        val repo = DeviceCalendarRepo(context)
        if (!repo.hasPermissions()) return null
        val zone = ZoneId.systemDefault()
        val start = LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli()
        val end = LocalDate.now().plusDays(14).atStartOfDay(zone).toInstant().toEpochMilli()
        val all = repo.queryInstances(start, end).sortedBy { it.begin }
        return all.firstOrNull { it.title.equals(q, true) }
            ?: all.firstOrNull { it.title.contains(q, true) || q.contains(it.title, true) }
    }

    private fun editCalendarEvent(context: Context, input: Map<*, *>): String {
        val q = str(input, "title") ?: return "Which event?"
        val ev = findUpcomingEvent(context, q)
            ?: return "No upcoming event matching \"$q\" in the next 14 days."
        val zone = ZoneId.systemDefault()
        val oldStart = java.time.Instant.ofEpochMilli(ev.begin).atZone(zone)
        val newDate = str(input, "new_date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: oldStart.toLocalDate()
        val newStart = str(input, "new_start_time")?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
            ?: oldStart.toLocalTime()
        val durationMs = (ev.end - ev.begin).coerceAtLeast(15 * 60_000L)
        val startMs = newDate.atTime(newStart).atZone(zone).toInstant().toEpochMilli()
        val endMs = str(input, "new_end_time")?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
            ?.let { newDate.atTime(it).atZone(zone).toInstant().toEpochMilli() }
            ?.takeIf { it > startMs }
            ?: (startMs + durationMs)
        val newTitle = str(input, "new_title") ?: ev.title
        return if (DeviceCalendarRepo(context).updateEvent(ev.id, newTitle, startMs, endMs)) {
            "Moved \"$newTitle\" to ${newDate.format(DateTimeFormatter.ofPattern("EEE MMM d"))} at $newStart."
        } else {
            "Couldn't change \"${ev.title}\" — it's likely a recurring event, which I can't safely edit."
        }
    }

    private fun deleteCalendarEvent(context: Context, input: Map<*, *>): String {
        val q = str(input, "title") ?: return "Which event?"
        val ev = findUpcomingEvent(context, q)
            ?: return "No upcoming event matching \"$q\" in the next 14 days."
        return if (DeviceCalendarRepo(context).deleteEvent(ev.id)) "Cancelled \"${ev.title}\"."
        else "Couldn't cancel \"${ev.title}\"."
    }

    private suspend fun addSubtask(context: Context, input: Map<*, *>): String {
        val db = BiboDb.get(context)
        val q = str(input, "task") ?: return "Which task?"
        val steps = (input["steps"] as? List<*>)
            ?.mapNotNull { (it as? String)?.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        if (steps.isEmpty()) return "No steps given."
        val all = db.todos().allOnce()
        val parents = all.filter { it.parentId == null && it.completedAt == null }
        val parent = parents.firstOrNull { it.title.equals(q, true) }
            ?: parents.firstOrNull { it.title.contains(q, true) || q.contains(it.title, true) }
            ?: return "No open task matching \"$q\"."
        val baseOrder = (all.filter { it.parentId == parent.id }.maxOfOrNull { it.sortOrder } ?: 0L) + 1
        val now = System.currentTimeMillis()
        steps.forEachIndexed { i, s ->
            db.todos().insert(
                TodoTask(title = s, parentId = parent.id, createdAt = now + i, sortOrder = baseOrder + i, goalId = parent.goalId)
            )
        }
        return "Added ${steps.size} step${if (steps.size > 1) "s" else ""} to \"${parent.title}\"."
    }

    private suspend fun logFood(context: Context, input: Map<*, *>): String {
        val label = str(input, "label") ?: return "Log what?"
        val cal = (input["calories"] as? Number)?.toInt()?.coerceIn(0, 5000) ?: return "Need a calorie estimate."
        val sugar = (input["sugar_g"] as? Number)?.toDouble()?.coerceIn(0.0, 500.0) ?: 0.0
        val caf = (input["caffeine_mg"] as? Number)?.toInt()?.coerceIn(0, 1000) ?: 0
        BiboDb.get(context).foods().insert(
            FoodEntry(
                epochDay = LocalDate.now().toEpochDay(), createdAt = System.currentTimeMillis(),
                label = label, calories = cal, sugarG = sugar, caffeineMg = caf,
            )
        )
        return "Logged \"$label\" (~$cal kcal${if (caf > 0) ", ${caf}mg caffeine" else ""})."
    }

    private suspend fun setHabit(context: Context, input: Map<*, *>): String {
        val habit = str(input, "habit") ?: return "Which habit?"
        val done = input["done"] == true
        val db = BiboDb.get(context)
        val day = LocalDate.now().toEpochDay()
        val cur = db.habits().get(day) ?: HabitDay(day)
        val next = when (habit) {
            "showered" -> cur.copy(showered = done)
            "clean_clothes" -> cur.copy(cleanClothes = done)
            "worked_out" -> cur.copy(workedOut = done)
            "prayed" -> cur.copy(prayed = done)
            else -> return "Unknown habit \"$habit\"."
        }
        db.habits().upsert(next)
        return "Marked ${habit.replace('_', ' ')} ${if (done) "done" else "not done"} for today."
    }

    private suspend fun startFocus(context: Context, input: Map<*, *>): String {
        val intention = str(input, "intention") ?: return "Focus on what?"
        val goal = matchGoal(BiboDb.get(context).goals().allOnce(), str(input, "goal"))
        val lockIn = input["block_distractions"] == true
        val apps = if (lockIn) TimerController.lastBlockedApps(context) else emptySet()
        TimerController.startFocus(
            context,
            FocusConfig(intention = intention, goalId = goal?.id, blockedApps = apps, dnd = lockIn),
        )
        val lock = when {
            lockIn && apps.isNotEmpty() ->
                " — phone silenced + ${apps.size} app${if (apps.size > 1) "s" else ""} blocked"
            lockIn ->
                " — phone silenced (no saved app-block list yet; block some apps once in Focus and I'll reuse them)"
            else -> ""
        }
        return "Focus started on \"$intention\"" + (goal?.let { " (goal: ${it.name})" } ?: "") +
            lock + ", timing now."
    }

    private fun stopTimerTool(context: Context, input: Map<*, *>): String {
        if (!TimerController.isRunning(context)) return "No timer is running."
        val title = TimerController.runningTitle(context)
        TimerController.stopTimer(context, str(input, "reflection"))
        return "Stopped \"$title\" — saved to the calendar."
    }

    /**
     * Keyword retrieval over the full history — chat, day digests, memory notes. This is
     * what makes context effectively infinite: nothing ever said is out of reach, it just
     * has to be searched for instead of carried in the window.
     */
    private suspend fun searchHistory(context: Context, input: Map<*, *>): String {
        val q = str(input, "query") ?: return "Search for what?"
        val db = BiboDb.get(context)
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        val msgs = db.chat().search(q, 10)
        val days = db.chatDays().search(q, 6)
        val memLines = Mentor.memory(context).lines().filter { it.contains(q, true) }

        if (msgs.isEmpty() && days.isEmpty() && memLines.isEmpty()) {
            return "No matches for \"$q\" anywhere in history. Try a different keyword."
        }
        return buildString {
            if (memLines.isNotEmpty()) {
                appendLine("From memory notes:")
                memLines.take(5).forEach { appendLine("  $it") }
            }
            if (days.isNotEmpty()) {
                appendLine("From day summaries:")
                days.forEach { d ->
                    appendLine("  ${LocalDate.ofEpochDay(d.epochDay).format(fmt)}: ${d.digest.take(220)}")
                }
            }
            if (msgs.isNotEmpty()) {
                appendLine("From conversation:")
                msgs.forEach { m ->
                    val who = if (m.role == "USER") "them" else "you"
                    appendLine("  ${LocalDate.ofEpochDay(m.epochDay).format(fmt)} ($who): ${m.content.take(220)}")
                }
            }
        }.trim()
    }

    /** memory_get equivalent: one specific day — its digest plus the full transcript. */
    private suspend fun recallDay(context: Context, input: Map<*, *>): String {
        val date = str(input, "date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: return "Couldn't read the date (need YYYY-MM-DD)."
        val db = BiboDb.get(context)
        val day = date.toEpochDay()
        val digest = db.chatDays().get(day)?.digest
        val transcript = db.chat().forDay(day).joinToString("\n") { m ->
            (if (m.role == "USER") "them: " else "you: ") + m.content.take(300)
        }
        if (digest == null && transcript.isBlank()) return "Nothing recorded on $date."
        return buildString {
            appendLine("$date:")
            digest?.let { appendLine("Summary: $it") }
            if (transcript.isNotBlank()) {
                appendLine("Conversation:")
                appendLine(transcript.take(2500))
            }
        }.trim()
    }

    private fun editMemory(context: Context, input: Map<*, *>): String {
        val notes = str(input, "notes") ?: return "Pass the complete new notes."
        Mentor.saveMemory(context, notes)
        return "Memory notes rewritten."
    }

    private suspend fun completeTask(context: Context, input: Map<*, *>): String {
        val db = BiboDb.get(context)
        val q = str(input, "title") ?: return "Which task?"
        val open = db.todos().incompleteOnce()
        val match = open.firstOrNull { it.title.equals(q, true) }
            ?: open.firstOrNull { it.title.contains(q, true) || q.contains(it.title, true) }
            ?: return "No open task matching \"$q\"."
        val end = System.currentTimeMillis()
        // If this task is the one currently being timed, tear the shared timer down
        // WITHOUT letting it write its own block — we write the single block below.
        // (Otherwise stopping the timer later would double-log the same work.)
        val timedStart = match.startedAt
        if (TimerController.linkedTaskId(context) == match.id) {
            TimerController.clear(context)
        }
        val start = if (timedStart != null && end - timedStart >= 60_000L) timedStart
        else end - 15 * 60_000L
        db.todos().update(match.copy(completedAt = end, startedAt = null))
        // Land it on the calendar too, matching the Tasks-tab complete flow.
        db.activityBlocks().insert(
            ActivityBlock(
                title = match.title, startMillis = start,
                endMillis = end, source = "TODO", goalId = match.goalId,
            )
        )
        return "Marked \"${match.title}\" done."
    }
}

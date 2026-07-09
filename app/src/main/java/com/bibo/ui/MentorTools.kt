package com.bibo.ui

import android.content.Context
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Tool
import com.bibo.data.ActivityBlock
import com.bibo.data.BiboDb
import com.bibo.data.DeviceCalendarRepo
import com.bibo.data.Goal
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

        val parentId = db.todos().insert(
            TodoTask(
                title = title, createdAt = now, sortOrder = now, goalId = goal?.id, dueEpochDay = due,
                reminderAt = reminderAt, reminderNote = reminderNote,
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

    private fun remember(context: Context, input: Map<*, *>): String {
        val fact = str(input, "fact") ?: return "Nothing to remember."
        Mentor.appendMemory(context, fact)
        return "Saved to memory."
    }

    private suspend fun completeTask(context: Context, input: Map<*, *>): String {
        val db = BiboDb.get(context)
        val q = str(input, "title") ?: return "Which task?"
        val open = db.todos().incompleteOnce()
        val match = open.firstOrNull { it.title.equals(q, true) }
            ?: open.firstOrNull { it.title.contains(q, true) || q.contains(it.title, true) }
            ?: return "No open task matching \"$q\"."
        db.todos().update(match.copy(completedAt = System.currentTimeMillis(), startedAt = null))
        return "Marked \"${match.title}\" done."
    }
}

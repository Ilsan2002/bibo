package com.bibo.ui

import android.content.Context
import androidx.room.withTransaction
import com.bibo.data.*
import java.time.*
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** Models discover identities and propose commands; domain commands own all invariants. */
object MentorTools {
    private val actionLock = Mutex()
    suspend fun <T> withExclusiveActions(block: suspend () -> T): T = actionLock.withLock { block() }
    private fun string(description: String) = JSONObject().put("type", "string").put("description", description)
    private fun number(description: String) = JSONObject().put("type", "number").put("description", description)
    private fun bool(description: String) = JSONObject().put("type", "boolean").put("description", description)
    private fun strings(description: String) = JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")).put("description", description)
    private fun tool(name: String, description: String, required: List<String> = emptyList(), props: JSONObject.() -> Unit) = JSONObject().put("type", "function").put("function", JSONObject().put("name", name).put("description", description).put("parameters", JSONObject().put("type", "object").put("additionalProperties", false).put("required", JSONArray(required)).put("properties", JSONObject().apply(props))))
    fun definitions() = JSONArray().apply {
        put(tool("find_tasks", "Discover task IDs, goal, state, steps, and reminders. Returns explicit ambiguity; do not infer an ID from a title.") { put("query", string("Title keyword; empty lists tasks")); put("offset", number("Page offset, default 0")); put("include_completed", bool("Include completed tasks")) })
        put(tool("find_goals", "Discover goal IDs including archived goals.") { put("query", string("Name keyword")) })
        put(tool("find_calendar_events", "Discover local plans and device occurrences, recurrence and destination. Read failures are explicit.") { put("query", string("Title keyword")); put("from_date", string("YYYY-MM-DD, default today")); put("to_date", string("YYYY-MM-DD, exclusive, default +14 days")); put("offset", number("Page offset")) })
        put(tool("create_task", "Create one task tree; search first to avoid duplicates.", listOf("title")) {
            put("title", string("Short task title")); put("subtasks", strings("Ordered distinct step titles")); put("goal_id", number("Existing goal ID")); put("due_date", string("YYYY-MM-DD")); reminderProps(); put("reward_dollars", number("Reward estimate in dollars, 0–100"))
        })
        put(tool("create_goal", "Create an active goal, search first.", listOf("name")) { put("name", string("Name")); put("details", string("Why it matters")); put("target_date", string("YYYY-MM-DD")) })
        put(tool("complete_task", "Complete the identified task and its children through the shared completion command. Set completed=false to reopen.", listOf("id")) { put("id", number("Discovered task ID")); put("completed", bool("Default true")) })
        put(tool("delete_task", "Propose deleting this identified task tree. The user applies the action card; no bulk fuzzy deletes.", listOf("id")) { put("id", number("Discovered task ID")) })
        put(tool("edit_task", "Patch an identified task. Explicit clear flags unset fields. Existing reminders can be set, moved, or removed.", listOf("id")) {
            put("id", number("Task ID")); put("new_title", string("Replacement title")); put("goal_id", number("Destination goal ID")); put("clear_goal", bool("Unfile task tree")); put("due_date", string("YYYY-MM-DD")); put("clear_due_date", bool("Remove due date")); reminderProps(); put("clear_reminder", bool("Cancel reminder")); put("reward_dollars", number("Reward dollars 0–100"))
        })
        put(tool("add_subtask", "Append distinct steps to an identified open parent task.", listOf("id", "steps")) { put("id", number("Parent task ID")); put("steps", strings("Step titles")) })
        put(tool("edit_goal", "Patch the identified goal. Archived goals remain archived unless restore=true.", listOf("id")) { put("id", number("Goal ID")); put("new_name", string("New name")); put("details", string("Why")); put("clear_details", bool("Clear why")); put("target_date", string("YYYY-MM-DD")); put("clear_target_date", bool("Clear target")); put("restore", bool("Explicitly restore archived goal")) })
        put(tool("delete_goal", "Propose archiving an identified goal; task/history links remain available for restoration.", listOf("id")) { put("id", number("Goal ID")) })
        put(tool("add_calendar_event", "Propose a planned event in an explicit destination. No silent fallback. Overnight end times roll to the next date.", listOf("title", "date", "destination")) { put("title", string("Title")); calendarTimeProps(); put("destination", string("local or device")); put("calendar_id", number("Discovered writable device calendar ID; required for device")) })
        put(tool("edit_calendar_event", "Propose editing a discovered local plan or non-recurring writable device occurrence.", listOf("id")) { put("id", string("local:ID or device:EVENT_ID:BEGIN_MILLIS")); put("new_title", string("New title")); put("new_date", string("YYYY-MM-DD")); put("new_start_time", string("HH:mm")); put("new_end_time", string("HH:mm")); put("new_end_date", string("YYYY-MM-DD")); put("new_start_offset", string("UTC offset if the new start clock time occurs twice")); put("new_end_offset", string("UTC offset if the new end clock time occurs twice")) })
        put(tool("delete_calendar_event", "Propose cancelling the exact identified event. Recurring events cannot be deleted here; open their calendar app.", listOf("id")) { put("id", string("Discovered local/device occurrence identity")) })
        put(tool("log_food", "Log an actual meal on its explicit date, with labeled nutrition estimates. Do not log hypothetical food.", listOf("label", "date", "calories")) { put("label", string("Meal name")); put("date", string("YYYY-MM-DD")); put("calories", number("Estimated kcal 0–5000")); put("sugar_g", number("Estimated grams 0–500")); put("caffeine_mg", number("Estimated mg 0–1000")) })
        put(tool("set_habit", "Record done/not done for an explicitly named habit and date. Omitted habits remain unknown.", listOf("habit", "date", "done")) { put("habit", string("showered, clean_clothes, worked_out, prayed")); put("date", string("YYYY-MM-DD")); put("done", bool("Actual done/not done value")) })
        put(tool("start_focus", "Start focus only with available system capabilities; do not replace an existing timer automatically.", listOf("intention")) { put("intention", string("Intention")); put("goal_id", number("Discovered goal ID")); put("block_distractions", bool("Require DND and saved app blockers")) })
        put(tool("stop_timer", "Stop the exact currently discovered session; already closed/stale sessions are no-ops.", listOf("session_id")) { put("session_id", string("Current running session UUID from snapshot")); put("reflection", string("Optional reflection")) })
        put(tool("search_history", "Search older conversation and summaries by keyword.", listOf("query")) { put("query", string("Keyword")); put("offset", number("Page offset")) })
        put(tool("recall_day", "Recall a past date with paged chat, plus summary.", listOf("date")) { put("date", string("YYYY-MM-DD")); put("offset", number("Message offset")) })
        put(tool("remember", "Append one concise durable fact. Do not contradict user-edited memory.", listOf("fact")) { put("fact", string("Fact, max 500 characters")) })
        put(tool("edit_memory", "Propose replacing memory notes. The user reviews and applies this card.", listOf("notes")) { put("notes", string("Complete replacement, max 8000 characters")) })
    }
    private fun JSONObject.reminderProps() { put("reminder", string("Local YYYY-MM-DD HH:mm, strictly in the future")); put("reminder_note", string("Helpful reminder text")) }
    private fun JSONObject.calendarTimeProps() { put("date", string("Start YYYY-MM-DD")); put("start_time", string("HH:mm; required unless all_day")); put("end_time", string("HH:mm; default +1 hour")); put("end_date", string("Explicit end date; all-day exclusive")); put("all_day", bool("All-day plan")); put("zone", string("IANA timezone, defaults to device zone")); put("start_offset", string("Explicit UTC offset for a repeated local start time, e.g. -04:00")); put("end_offset", string("Explicit UTC offset for a repeated local end time")) }
    private val reads = setOf("find_tasks", "find_goals", "find_calendar_events", "search_history", "recall_day")
    private val proposals = setOf("delete_task", "delete_goal", "add_calendar_event", "edit_calendar_event", "delete_calendar_event", "edit_memory")
    internal fun category(name: String) = when (name) { "create_task", "complete_task", "delete_task", "edit_task", "add_subtask", "find_tasks" -> "tasks"; "create_goal", "edit_goal", "delete_goal", "find_goals" -> "goals"; "add_calendar_event", "edit_calendar_event", "delete_calendar_event", "find_calendar_events" -> "calendar"; "log_food" -> "food"; "set_habit" -> "habits"; "start_focus", "stop_timer" -> "activity"; else -> null }
    suspend fun execute(context: Context, name: String, input: Map<*, *>, commandId: String, approved: Boolean = false): String = actionLock.withLock {
        executeLocked(context, name, input, commandId, approved)
    }
    private suspend fun executeLocked(context: Context, name: String, input: Map<*, *>, commandId: String, approved: Boolean): String {
        val db = BiboDb.get(context); val now = System.currentTimeMillis()
        category(name)?.let { if (!MentorSettings.shared(context, it)) return result("DISABLED", "Enable $it sharing in Mentor settings before Mentor reads or changes it.") }
        if (name in reads) return try { dispatch(context, name, input, commandId) } catch (e: CancellationException) { throw e } catch (e: Exception) { result("FAILED", e.message ?: "Could not read records") }
        var storedInput = JSONObject().put("tool", name).put("args", JSONObject(input)).toString()
        val old = db.commands().byId(commandId)
        if (old?.status in setOf("APPLIED", "UNDONE", "CANCELLED")) return old!!.result
        if (old?.status in setOf("UNCERTAIN", "AMBIGUOUS") || old?.status == "PENDING" && name in setOf("add_calendar_event", "edit_calendar_event", "delete_calendar_event") && str(input, "destination") != "local" && str(input, "id")?.startsWith("local:") != true) return result("PENDING", "The earlier action may have reached its destination. Verify it before trying again.")
        if (name in proposals && !approved) {
            return try {
                if (old != null && old.status != "AWAITING_CONFIRMATION") return old.result.ifBlank { result("PENDING", "The earlier proposal has no confirmed outcome. Review its action card.") }
                if (old == null) {
                    val expected = expectedState(context, name, input)
                    storedInput = JSONObject(storedInput).put("expected", expected).toString()
                    db.commands().upsert(CommandRecord(commandId, name, "AWAITING_CONFIRMATION", storedInput, result("AWAITING_CONFIRMATION", preview(context, name, input)), now, now))
                }
                result("AWAITING_CONFIRMATION", "An action card is ready for the user to review and apply. This action is not applied.")
            } catch (e: CancellationException) { throw e } catch (e: Exception) { result("FAILED", e.message ?: "The proposal is invalid.") }
        }
        currentCoroutineContext().ensureActive()
        db.commands().upsert(CommandRecord(commandId, name, "PENDING", storedInput, "", old?.createdAt ?: now, now))
        return try {
            val outcome = dispatch(context, name, input, commandId)
            val status = JSONObject(outcome).optString("status", "FAILED")
            db.commands().upsert(CommandRecord(commandId, name, status, storedInput, outcome, old?.createdAt ?: now, System.currentTimeMillis()))
            outcome
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val outcome = result("FAILED", e.message ?: "The action could not be applied.")
            db.commands().upsert(CommandRecord(commandId, name, "FAILED", storedInput, outcome, old?.createdAt ?: now, System.currentTimeMillis())); outcome
        }
    }
    suspend fun applyPending(context: Context, id: String): String = actionLock.withLock {
        applyPendingLocked(context, id)
    }
    private suspend fun applyPendingLocked(context: Context, id: String): String {
        val record = BiboDb.get(context).commands().byId(id) ?: return result("FAILED", "Action unavailable")
        if (record.status != "AWAITING_CONFIRMATION") return record.result
        val json = JSONObject(record.input)
        val name = json.getString("tool"); val args = json.getJSONObject("args").toMap()
        val expected = json.optString("expected", "")
        if (expected.isNotBlank() && expectedState(context, name, args) != expected) {
            val failed = result("FAILED", "This record changed after the proposal. Refresh it and ask again.")
            BiboDb.get(context).commands().upsert(record.copy(status = "FAILED", result = failed, updatedAt = System.currentTimeMillis()))
            return failed
        }
        return executeLocked(context, name, args, id, approved = true)
    }
    suspend fun dismissPending(context: Context, id: String) = actionLock.withLock { val dao = BiboDb.get(context).commands(); dao.byId(id)?.let { if (it.status == "AWAITING_CONFIRMATION") dao.upsert(it.copy(status = "CANCELLED", result = result("CANCELLED", "Proposal dismissed."), updatedAt = System.currentTimeMillis())) } }
    suspend fun undo(context: Context, id: String): String = actionLock.withLock { undoLocked(context, id) }
    private suspend fun undoLocked(context: Context, id: String): String {
        val db = BiboDb.get(context); val record = db.commands().byId(id) ?: return result("FAILED", "Action unavailable")
        if (record.status != "APPLIED") return record.result
        val entity = JSONObject(record.result).optLong("entity_id", -1)
        val outcome = when (record.kind) {
            "create_task" -> { TaskCommands.delete(context, entity, "$id:undo"); result("UNDONE", "Task removed.") }
            "complete_task" -> { val completionId = JSONObject(record.result).optLong("completion_id", -1); require(completionId > 0) { "This action did not create a completion to undo." }; TaskCommands.undoCompletion(context, entity, completionId); result("UNDONE", "Task reopened.") }
            "delete_task" -> { val tasks = JSONArray(JSONObject(record.result).optString("deleted_tree", "[]")); val tree = (0 until tasks.length()).map { taskFromJson(tasks.getJSONObject(it)) }; TaskCommands.restore(context, DeletedTaskTree(tree)); result("UNDONE", "Task tree restored.") }
            "delete_goal" -> { TaskCommands.archiveGoal(context, entity, false, "$id:undo"); result("UNDONE", "Goal restored.") }
            else -> return result("FAILED", "Open the changed record to adjust this action.")
        }
        db.commands().upsert(record.copy(status = "UNDONE", result = outcome, updatedAt = System.currentTimeMillis())); return outcome
    }
    private suspend fun preview(context: Context, name: String, input: Map<*, *>) = when (name) {
        "delete_task" -> "Delete “${task(context, input).title}” and its steps? This can be undone."
        "delete_goal" -> "Archive “${goal(context, input).name}”? Linked tasks and history are retained."
        "edit_memory" -> "Replace memory notes with:\n${required(input, "notes")}"
        "add_calendar_event" -> {
            val interval = eventInterval(input); val destination = if (required(input, "destination") == "local") "Bibo" else "the selected device calendar"
            "Plan “${required(input, "title")}” in $destination on ${required(input, "date")}${str(input, "start_time")?.let { " at $it" }.orEmpty()}?"
        }
        "delete_calendar_event", "edit_calendar_event" -> previewCalendarChange(context, name, input)
        else -> "Review the proposed change."
    }
    private data class CalendarPreview(val title: String, val begin: Long, val end: Long, val allDay: Boolean, val zone: ZoneId, val calendar: String)
    private suspend fun previewCalendarChange(context: Context, name: String, input: Map<*, *>): String {
        val identity = required(input, "id")
        val old = if (identity.startsWith("local:")) {
            val block = BiboDb.get(context).activityBlocks().byId(identity.removePrefix("local:").toLong()) ?: error("The plan was removed.")
            CalendarPreview(block.title, block.startMillis, block.endMillis, block.allDay, ZoneId.of(block.eventZone ?: ZoneId.systemDefault().id), "Bibo")
        } else {
            val parts = identity.removePrefix("device:").split(':'); require(parts.size == 2)
            val begin = parts[1].toLong(); val records = DeviceCalendarRepo(context).readInstances(begin - 86400_000, begin + 86400_000)
            require(records is CalendarResult.Success) { "The device calendar is unavailable." }
            val event = records.value.firstOrNull { it.occurrenceId == "${parts[0]}:${parts[1]}" } ?: error("The event moved or was removed.")
            CalendarPreview(event.title, event.begin, event.end, event.allDay, ZoneId.of(event.eventZone), event.calendarName)
        }
        if (name == "delete_calendar_event") return "Cancel ${calendarSummary(old)}?"
        val interval = editedEventInterval(old.begin, old.end, old.allDay, old.zone, input)
        val updated = old.copy(title = str(input, "new_title") ?: old.title, begin = interval.first, end = interval.second)
        return "Change ${calendarSummary(old)} to:\n${calendarSummary(updated)}?"
    }
    private fun calendarSummary(event: CalendarPreview): String {
        val zone = if (event.allDay) ZoneOffset.UTC else event.zone
        val first = Instant.ofEpochMilli(event.begin).atZone(zone); val last = Instant.ofEpochMilli(event.end).atZone(zone)
        val range = if (event.allDay) {
            val lastDate = last.toLocalDate().minusDays(1)
            if (first.toLocalDate() == lastDate) "${first.toLocalDate()} (all day)" else "${first.toLocalDate()}–$lastDate (all day)"
        } else {
            val format = DateTimeFormatter.ofPattern("EEE, MMM d uuuu, HH:mm z")
            "${first.format(format)}–${last.format(format)}"
        }
        return "“${event.title}” on $range in ${event.calendar}"
    }
    private suspend fun expectedState(context: Context, name: String, input: Map<*, *>): String = when (name) {
        "delete_task" -> taskJson(task(context, input)).toString()
        "delete_goal" -> goal(context, input).toString()
        "edit_memory" -> { require(required(input, "notes").length <= 8000); (BiboDb.get(context).mentorMemory().latest()?.id ?: 0).toString() }
        "add_calendar_event" -> {
            eventInterval(input); val destination = required(input, "destination"); require(destination in setOf("local", "device")) { "Choose a calendar destination." }
            if (destination == "device") {
                val choices = DeviceCalendarRepo(context).writableCalendars()
                require(choices is CalendarResult.Success) { "Writable device calendars are unavailable." }
                require(choices.value.any { it.id == numberId(input, "calendar_id") }) { "Choose a writable calendar from the discovered list." }
            }
            ""
        }
        "edit_calendar_event", "delete_calendar_event" -> {
            val identity = required(input, "id")
            if (identity.startsWith("local:")) {
                val block = BiboDb.get(context).activityBlocks().byId(identity.removePrefix("local:").toLong()) ?: error("The plan was removed.")
                require(block.source == "PLAN"); block.toString()
            } else {
                require(identity.startsWith("device:")); val parts = identity.removePrefix("device:").split(':'); require(parts.size == 2)
                val begin = parts[1].toLong(); val read = DeviceCalendarRepo(context).readInstances(begin - 86400_000, begin + 86400_000)
                require(read is CalendarResult.Success) { "The device calendar is unavailable." }
                val event = read.value.firstOrNull { it.occurrenceId == "${parts[0]}:${parts[1]}" } ?: error("The event moved or was removed.")
                require(event.writable && !event.recurring) { "Open this event in its calendar app to choose recurrence scope or edit a read-only calendar." }
                event.toString()
            }
        }
        else -> ""
    }
    private suspend fun dispatch(context: Context, name: String, input: Map<*, *>, commandId: String): String {
        val db = BiboDb.get(context)
        val localReceipt = db.commands().byId("$commandId:local")
        if (localReceipt?.status == "COMPLETED") {
            if (name == "edit_goal" && input["restore"] == true) TaskCommands.archiveGoal(context, numberId(input), false, "$commandId:restore:local")
            val entityId = if (name in setOf("create_task", "create_goal")) localReceipt.result.toLongOrNull() else (input["id"] as? Number)?.toLong()
            val restored = JSONObject(result("APPLIED", "This action was already committed and was not repeated.", entityId = entityId))
            if (name == "complete_task" && input["completed"] != false) restored.put("completion_id", localReceipt.result.toLongOrNull())
            if (name == "delete_task") {
                val deletion = JSONObject(localReceipt.result)
                val ids = deletion.getJSONArray("ids")
                val rows = (0 until ids.length()).mapNotNull { db.todos().byId(ids.getLong(it))?.copy(deletedBy = deletion.getString("deletionId")) }
                restored.put("deleted_tree", JSONArray(rows.map(::taskJson)).toString())
            }
            return restored.toString()
        }
        return when (name) {
            "find_tasks" -> {
                val query = str(input, "query").orEmpty(); val all = db.todos().allOnce().filter { (input["include_completed"] == true || it.completedAt == null) && it.title.contains(query, true) }; val offset = offset(input)
                result("READ", "Tasks", JSONArray(all.drop(offset).take(30).map { taskJson(it) }), all.size, offset + 30 < all.size)
            }
            "find_goals" -> { val all = db.goals().allWithArchived().filter { it.name.contains(str(input, "query").orEmpty(), true) }; result("READ", "Goals", JSONArray(all.map { JSONObject().put("id", it.id).put("name", it.name).put("archived", it.archived) })) }
            "find_calendar_events" -> findEvents(context, input)
            "create_task" -> {
                val title = required(input, "title"); require(title.length <= 500)
                val existing = db.todos().allOnce().firstOrNull { it.parentId == null && it.completedAt == null && normalize(it.title) == normalize(title) }
                if (existing != null) result("EXISTS", "Task already exists.", entityId = existing.id)
                else {
                    val now = System.currentTimeMillis(); val goal = goalId(context, input)
                    val task = TodoTask(title = title, createdAt = now, sortOrder = now, goalId = goal, dueEpochDay = day(input, "due_date"), reminderAt = reminder(input), reminderNote = str(input, "reminder_note"), rewardCents = reward(input) ?: 0)
                    val steps = stringList(input, "subtasks").distinctBy(::normalize)
                    val id = TaskCommands.createTree(context, task, steps, "$commandId:local"); result("APPLIED", "Created “$title” with ${steps.size} steps.", entityId = id)
                }
            }
            "complete_task" -> { val task = task(context, input); val done = boolean(input, "completed", true); val completionId = TaskCommands.complete(context, task.id, done, "$commandId:local"); JSONObject(result("APPLIED", if (done) "Completed “${task.title}” and its steps." else "Reopened “${task.title}”.", entityId = task.id)).put("completion_id", if (done) completionId else null).toString() }
            "delete_task" -> { val task = task(context, input); val tree = TaskCommands.delete(context, task.id, "$commandId:local"); JSONObject(result("APPLIED", "Deleted “${task.title}” and its steps.", entityId = task.id)).put("deleted_tree", JSONArray(tree.tasks.map(::taskJson)).toString()).toString() }
            "edit_task" -> {
                val task = task(context, input)
                val patch = TaskPatch(title = str(input, "new_title"), goalId = when { input["clear_goal"] == true -> PatchField.Set(null); input.containsKey("goal_id") -> PatchField.Set(goalId(context, input)); else -> PatchField.Keep }, dueEpochDay = patchDay(input, "due_date", "clear_due_date"), reminderAt = when { input["clear_reminder"] == true -> PatchField.Set(null); input.containsKey("reminder") -> PatchField.Set(reminder(input)); else -> PatchField.Keep }, reminderNote = when { input["clear_reminder"] == true -> PatchField.Set(null); input.containsKey("reminder_note") -> PatchField.Set(str(input, "reminder_note")); else -> PatchField.Keep }, rewardCents = reward(input))
                TaskCommands.update(context, task.id, patch, "$commandId:local"); result("APPLIED", "Updated “${str(input, "new_title") ?: task.title}”.", entityId = task.id)
            }
            "add_subtask" -> {
                val parent = task(context, input); require(parent.parentId == null && parent.completedAt == null) { "Choose an open parent task." }
                val existing = db.todos().allOnce().filter { it.parentId == parent.id }.map { normalize(it.title) }.toSet()
                val fresh = stringList(input, "steps").distinctBy(::normalize).filter { normalize(it) !in existing }
                if (fresh.isEmpty()) return result("EXISTS", "These steps already exist.", entityId = parent.id)
                TaskCommands.addSteps(context, parent.id, fresh, "$commandId:local")
                result("APPLIED", "Added ${fresh.size} steps to “${parent.title}”.", entityId = parent.id)
            }
            "create_goal" -> {
                val nameValue = required(input, "name"); val existing = db.goals().allWithArchived().filter { it.name.equals(nameValue, true) }
                if (existing.isNotEmpty()) result("EXISTS", "Goal already exists; restore or edit it by ID.", entityId = existing.first().id)
                else { val id = TaskCommands.saveGoal(context, Goal(name = nameValue, color = 0xFF6750A4.toInt(), details = str(input, "details"), targetDate = day(input, "target_date"), createdAt = System.currentTimeMillis()), "$commandId:local"); result("APPLIED", "Created goal “$nameValue”.", entityId = id) }
            }
            "edit_goal" -> {
                val goal = goal(context, input)
                val patch = GoalPatch(name = str(input, "new_name"), details = when { input["clear_details"] == true -> PatchField.Set(null); input.containsKey("details") -> PatchField.Set(str(input, "details")); else -> PatchField.Keep }, targetDate = patchDay(input, "target_date", "clear_target_date"))
                TaskCommands.updateGoal(context, goal.id, patch, "$commandId:local")
                if (input["restore"] == true) TaskCommands.archiveGoal(context, goal.id, false, "$commandId:restore:local")
                result("APPLIED", "Updated goal “${str(input, "new_name") ?: goal.name}”.", entityId = goal.id)
            }
            "delete_goal" -> { val goal = goal(context, input); TaskCommands.archiveGoal(context, goal.id, true, "$commandId:local"); result("APPLIED", "Archived “${goal.name}”; links are retained.", entityId = goal.id) }
            "add_calendar_event" -> createEvent(context, input, commandId)
            "edit_calendar_event", "delete_calendar_event" -> changeEvent(context, name, input, commandId)
            "log_food" -> {
                val now = System.currentTimeMillis(); val label = required(input, "label"); val date = day(input, "date") ?: error("Use an explicit date.")
                val calories = numeric(input, "calories", 0.0, 5000.0, true)!!.toInt(); val sugar = numeric(input, "sugar_g", 0.0, 500.0) ?: 0.0; val caffeine = (numeric(input, "caffeine_mg", 0.0, 1000.0) ?: 0.0).toInt()
                db.withTransaction { val id = HealthCommands(db).logFood(FoodEntry(epochDay = date, createdAt = now, label = label, calories = calories, sugarG = sugar, caffeineMg = caffeine, estimated = true, provenance = "mentor_estimate")); val out = result("APPLIED", "Logged “$label” on ${LocalDate.ofEpochDay(date)} with estimated nutrition.", entityId = id); db.commands().upsert(CommandRecord(commandId, name, "APPLIED", JSONObject(input).toString(), out, now, now)); out }
            }
            "set_habit" -> {
                val day = day(input, "date") ?: error("Use an explicit date."); val done = boolean(input, "done"); val habit = required(input, "habit"); val now = System.currentTimeMillis()
                db.withTransaction { val target = when (habit) { "showered" -> HealthHabit.SHOWER; "clean_clothes" -> HealthHabit.CLOTHES; "worked_out" -> HealthHabit.WORKOUT; "prayed" -> HealthHabit.PRAY; else -> error("Unknown habit") }; HealthCommands(db).setHabit(day, target, done); val out = result("APPLIED", "Recorded ${habit.replace('_', ' ')} ${if (done) "done" else "not done"} for ${LocalDate.ofEpochDay(day)}."); db.commands().upsert(CommandRecord(commandId, name, "APPLIED", JSONObject(input).toString(), out, now, now)); out }
            }
            "start_focus" -> {
                val lock = boolean(input, "block_distractions", false); val config = FocusConfig(intention = required(input, "intention"), goalId = goalId(context, input), blockedApps = if (lock) TimerController.lastBlockedApps(context) else emptySet(), dnd = lock)
                val session = TimerController.startFocusAwait(context, config, commandId = commandId, requireIdle = true).getOrThrow(); result("APPLIED", "Started focus with the requested available capabilities.", entityString = session)
            }
            "stop_timer" -> { val stopped = TimerController.stopSession(context, required(input, "session_id"), str(input, "reflection")).getOrThrow(); result("APPLIED", if (stopped == null) "That session was already closed; nothing else was stopped." else "Stopped and saved the session.", entityString = stopped) }
            "remember" -> { Mentor.appendMemory(context, required(input, "fact")); result("APPLIED", "Fact appended to memory.") }
            "edit_memory" -> { Mentor.saveMemoryAwait(context, required(input, "notes"), "USER"); result("APPLIED", "Memory replaced; its previous version remains available.") }
            "search_history" -> {
                val query = required(input, "query"); val startOffset = offset(input)
                var cursor = startOffset; var more = true; var batches = 0
                val matches = mutableListOf<ChatMessage>()
                while (more && matches.size < 20 && batches++ < 10) {
                    currentCoroutineContext().ensureActive()
                    val page = db.mentorHistory().historyPage(100, cursor)
                    more = page.size == 100
                    for (row in page) {
                        cursor++
                        if (row.content.contains(query, ignoreCase = true)) matches += row
                        if (matches.size == 20) { more = true; break }
                    }
                }
                val digests = db.mentorHistory().digestPage(100, startOffset).filter { it.digest.contains(query, ignoreCase = true) }.take(10)
                val notes = db.mentorMemory().latest()?.notes?.takeIf { it.contains(query, ignoreCase = true) }
                JSONObject(result("READ", "Unicode keyword matches in this bounded history scan; use next_offset for older conversation records. Summary coverage is the next 100 dates from the requested offset.", JSONArray(matches.take(20).map { JSONObject().put("date", LocalDate.ofEpochDay(it.epochDay).toString()).put("role", it.role).put("text", it.content.take(2000)) }), matches.size, more)).put("next_offset", cursor).put("summary_matches", JSONArray(digests.map { JSONObject().put("date", LocalDate.ofEpochDay(it.epochDay).toString()).put("text", it.digest).put("conversation_compacted", it.conversationCompacted) })).put("memory_match", notes?.take(2000)).toString()
            }
            "recall_day" -> { val day = day(input, "date") ?: error("Use an explicit date"); val offset = offset(input); val page = db.mentorHistory().forDayPage(day, 20, offset); val total = db.mentorHistory().dayCount(day); result("READ", db.chatDays().get(day)?.digest ?: "No digest.", JSONArray(page.map { JSONObject().put("role", it.role).put("text", it.content.take(2000)) }), total, offset + page.size < total) }
            else -> result("FAILED", "Unknown tool: $name")
        }
    }
    private suspend fun findEvents(context: Context, input: Map<*, *>): String {
        val zone = ZoneId.systemDefault(); val from = day(input, "from_date")?.let(LocalDate::ofEpochDay) ?: LocalDate.now(); val to = day(input, "to_date")?.let(LocalDate::ofEpochDay) ?: from.plusDays(14)
        require(to > from && to <= from.plusDays(90)) { "Choose a positive range of at most 90 days." }
        val start = from.atStartOfDay(zone).toInstant().toEpochMilli(); val end = to.atStartOfDay(zone).toInstant().toEpochMilli(); val query = str(input, "query").orEmpty()
        val utcStart = from.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(); val utcEnd = to.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val local = BiboDb.get(context).activityBlocks().blocksInList(minOf(start, utcStart), maxOf(end, utcEnd)).filter { it.source == "PLAN" && it.title.contains(query, true) && calendarRangeIncludes(it.startMillis, it.endMillis, it.allDay, from, to, zone) }.map { JSONObject().put("id", "local:${it.id}").put("title", it.title).put("start", it.startMillis).put("end", it.endMillis).put("destination", "Bibo").put("all_day", it.allDay) }
        val repo = DeviceCalendarRepo(context); val provider = repo.readInstances(start, end)
        val external = (provider as? CalendarResult.Success)?.value.orEmpty().filter { it.title.contains(query, true) && calendarRangeIncludes(it.begin, it.end, it.allDay, from, to, zone) }.map { JSONObject().put("id", "device:${it.occurrenceId}").put("title", it.title).put("start", it.begin).put("end", it.end).put("calendar", it.calendarName).put("recurring", it.recurring).put("writable", it.writable).put("all_day", it.allDay) }
        val calendars = (repo.writableCalendars() as? CalendarResult.Success)?.value.orEmpty()
        return JSONObject(result("READ", if (provider is CalendarResult.Failure) "Local plans loaded; device calendar unavailable: ${provider.message}" else "Calendar events", JSONArray((local + external).drop(offset(input)).take(30)), local.size + external.size, offset(input) + 30 < local.size + external.size)).put("writable_calendars", JSONArray(calendars.map { JSONObject().put("id", it.id).put("name", it.label) })).toString()
    }
    internal fun eventInterval(input: Map<*, *>): Pair<Long, Long> {
        val date = LocalDate.parse(required(input, "date")); val allDay = boolean(input, "all_day", false)
        val zone = if (allDay) ZoneOffset.UTC else str(input, "zone")?.let(ZoneId::of) ?: ZoneId.systemDefault()
        val start = if (allDay) date.atStartOfDay() else date.atTime(LocalTime.parse(required(input, "start_time")))
        var end = if (allDay) (str(input, "end_date")?.let(LocalDate::parse) ?: date.plusDays(1)).atStartOfDay() else {
            val time = str(input, "end_time")?.let(LocalTime::parse)
            if (time == null) start.plusHours(1) else (str(input, "end_date")?.let(LocalDate::parse) ?: date).atTime(time).let { if (str(input, "end_date") == null && it < start) it.plusDays(1) else it }
        }
        val checked = if (allDay) CalendarTime.allDay(start.toLocalDate(), end.toLocalDate()) else CalendarTime.timed(start, end, zone, str(input, "start_offset")?.let(ZoneOffset::of), str(input, "end_offset")?.let(ZoneOffset::of))
        return when (checked) { is CalendarTimeResult.Valid -> checked.interval.startMillis to checked.interval.endMillis; is CalendarTimeResult.Invalid -> error(checked.message) }
    }
    private suspend fun createEvent(context: Context, input: Map<*, *>, commandId: String): String {
        val title = required(input, "title"); val interval = eventInterval(input); val allDay = boolean(input, "all_day", false); val destination = required(input, "destination")
        if (destination == "local") {
            val db = BiboDb.get(context); val now = System.currentTimeMillis()
            return db.withTransaction { val id = db.activityBlocks().insert(ActivityBlock(title = title, startMillis = interval.first, endMillis = interval.second, source = "PLAN", allDay = allDay, eventZone = str(input, "zone") ?: ZoneId.systemDefault().id, provenance = "PLANNED", commandId = commandId)); val out = result("APPLIED", "Planned “$title” in Bibo.", entityId = id); db.commands().upsert(CommandRecord(commandId, "add_calendar_event", "APPLIED", JSONObject(input).toString(), out, now, now)); out }
        }
        require(destination == "device") { "Choose local or device destination." }
        val request = CalendarWriteRequest(numberId(input, "calendar_id"), title, interval.first, interval.second, allDay, str(input, "zone") ?: ZoneId.systemDefault().id, commandId = commandId)
        return calendarOutcome(DeviceCalendarRepo(context).createEvent(request))
    }
    private suspend fun changeEvent(context: Context, name: String, input: Map<*, *>, commandId: String): String {
        val identity = required(input, "id"); val delete = name == "delete_calendar_event"
        if (identity.startsWith("local:")) {
            val db = BiboDb.get(context); val old = db.activityBlocks().byId(identity.removePrefix("local:").toLong()) ?: error("Plan was removed."); require(old.source == "PLAN") { "Only planned events can be changed here." }
            return db.withTransaction {
                val current = db.activityBlocks().byId(old.id) ?: error("The plan was removed. Refresh it and ask again.")
                require(current == old) { "This plan changed while applying the proposal. Refresh it and ask again." }
                if (delete) db.activityBlocks().delete(old) else {
                    val bounds = editedEventInterval(old.startMillis, old.endMillis, old.allDay, ZoneId.of(old.eventZone ?: ZoneId.systemDefault().id), input)
                    db.activityBlocks().update(old.copy(title = str(input, "new_title") ?: old.title, startMillis = bounds.first, endMillis = bounds.second))
                }
                val now = System.currentTimeMillis(); val out = result("APPLIED", if (delete) "Cancelled “${old.title}” in Bibo." else "Updated “${old.title}” in Bibo.", entityId = old.id); db.commands().upsert(CommandRecord(commandId, name, "APPLIED", JSONObject(input).toString(), out, now, now)); out
            }
        }
        require(identity.startsWith("device:")) { "Use a discovered event identity." }
        val bits = identity.removePrefix("device:").split(':'); require(bits.size == 2)
        val repo = DeviceCalendarRepo(context); val begin = bits[1].toLong()
        val read = repo.readInstances(begin - 86400_000, begin + 86400_000)
        if (read is CalendarResult.Failure) return calendarOutcome(read)
        val old = (read as CalendarResult.Success).value.firstOrNull { it.occurrenceId == "${bits[0]}:${bits[1]}" } ?: return result("FAILED", "The occurrence was moved or removed. Refresh and select again.")
        if (old.recurring) return result("FAILED", "This is a recurring series. Open its calendar app to choose occurrence or series scope.")
        if (delete) return calendarOutcome(repo.removeEvent(old))
        val bounds = editedEventInterval(old.begin, old.end, old.allDay, ZoneId.of(old.eventZone), input)
        return calendarOutcome(repo.changeEvent(old, CalendarWriteRequest(old.calendarId, str(input, "new_title") ?: old.title, bounds.first, bounds.second, old.allDay, old.eventZone)))
    }
    internal fun calendarRangeIncludes(begin: Long, end: Long, allDay: Boolean, from: LocalDate, to: LocalDate, zone: ZoneId): Boolean {
        val displayZone = if (allDay) ZoneOffset.UTC else zone
        val first = from.atStartOfDay(displayZone).toInstant().toEpochMilli(); val last = to.atStartOfDay(displayZone).toInstant().toEpochMilli()
        return if (begin == end) begin >= first && begin < last else begin < last && end > first
    }
    internal fun editedEventInterval(begin: Long, end: Long, allDay: Boolean, zone: ZoneId, input: Map<*, *>): Pair<Long, Long> {
        if (listOf("new_date", "new_start_time", "new_end_time", "new_end_date").none { input.containsKey(it) }) return begin to end
        val z = if (allDay) ZoneOffset.UTC else zone
        val oldStart = Instant.ofEpochMilli(begin).atZone(z); val oldEnd = Instant.ofEpochMilli(end).atZone(z)
        val newDate = str(input, "new_date")?.let(LocalDate::parse) ?: oldStart.toLocalDate()
        if (allDay) {
            require(str(input, "new_start_time") == null && str(input, "new_end_time") == null) { "An all-day event uses dates, not clock times." }
            val lastDate = str(input, "new_end_date")?.let(LocalDate::parse) ?: newDate.plusDays(java.time.temporal.ChronoUnit.DAYS.between(oldStart.toLocalDate(), oldEnd.toLocalDate()))
            return eventInterval(mapOf("date" to newDate.toString(), "end_date" to lastDate.toString(), "all_day" to true))
        }
        val start = newDate.atTime(str(input, "new_start_time")?.let(LocalTime::parse) ?: oldStart.toLocalTime())
        val startOffset = str(input, "new_start_offset")?.let(ZoneOffset::of) ?: oldStart.offset.takeIf { start == oldStart.toLocalDateTime() }
        if (str(input, "new_end_time") == null && str(input, "new_end_date") == null) {
            val offsets = zone.rules.getValidOffsets(start)
            require(offsets.isNotEmpty()) { "This clock time does not exist because daylight saving time moves forward." }
            require(offsets.size == 1 || startOffset != null && startOffset in offsets) { "This clock time occurs twice. Select its UTC offset." }
            val newBegin = start.toInstant(startOffset?.takeIf { it in offsets } ?: offsets.single()).toEpochMilli()
            return newBegin to Math.addExact(newBegin, end - begin)
        }
        val values = mutableMapOf<String, Any>("date" to newDate.toString(), "start_time" to start.toLocalTime().toString(), "end_time" to (str(input, "new_end_time") ?: oldEnd.toLocalTime().toString()), "zone" to zone.id)
        str(input, "new_end_date")?.let { values["end_date"] = it }
        if (str(input, "new_end_time") == null && !values.containsKey("end_date")) values["end_date"] = newDate.plusDays(java.time.temporal.ChronoUnit.DAYS.between(oldStart.toLocalDate(), oldEnd.toLocalDate())).toString()
        startOffset?.let { values["start_offset"] = it.id }
        str(input, "new_end_offset")?.let { values["end_offset"] = it }
        val proposedEndDate = str(values, "end_date")?.let(LocalDate::parse) ?: newDate
        if (proposedEndDate.atTime(LocalTime.parse(values.getValue("end_time") as String)) == oldEnd.toLocalDateTime() && !values.containsKey("end_offset")) values["end_offset"] = oldEnd.offset.id
        return eventInterval(values)
    }
    private fun calendarOutcome(value: CalendarResult<*>): String = when (value) { is CalendarResult.Success -> { val receipt = value.value as? CalendarWriteReceipt; result("APPLIED", "Calendar change applied${receipt?.calendarName?.let { " in $it" }.orEmpty()}.", entityId = receipt?.eventId) }; is CalendarResult.Failure -> result(if (value.reason.name == "WRITE_UNCERTAIN") "AMBIGUOUS" else "FAILED", value.message) }
    private suspend fun task(context: Context, input: Map<*, *>) = BiboDb.get(context).todos().byId(numberId(input)).takeIf { it?.deletedAt == null } ?: error("Task removed; discover a current ID.")
    private suspend fun goal(context: Context, input: Map<*, *>) = BiboDb.get(context).goals().byId(numberId(input)) ?: error("Goal removed; discover a current ID.")
    private suspend fun goalId(context: Context, input: Map<*, *>): Long? { if (!input.containsKey("goal_id")) return null; val id = numberId(input, "goal_id"); require(BiboDb.get(context).goals().byId(id)?.archived == false) { "Select an active goal ID." }; return id }
    private fun str(input: Map<*, *>, key: String) = (input[key] as? String)?.trim()?.takeIf { it.isNotBlank() }
    private fun required(input: Map<*, *>, key: String) = str(input, key) ?: error("$key is required.")
    private fun numberId(input: Map<*, *>, key: String = "id"): Long { val n = input[key] as? Number ?: error("Use the discovered numeric $key."); require(n.toDouble().isFinite() && n.toDouble() == n.toLong().toDouble() && n.toLong() > 0); return n.toLong() }
    private fun day(input: Map<*, *>, key: String): Long? = if (!input.containsKey(key)) null else LocalDate.parse(required(input, key)).toEpochDay()
    private fun patchDay(input: Map<*, *>, key: String, clear: String): PatchField<Long?> = when { input[clear] == true -> PatchField.Set(null); input.containsKey(key) -> PatchField.Set(day(input, key)); else -> PatchField.Keep }
    private fun reminder(input: Map<*, *>): Long? { val value = str(input, "reminder") ?: return null; val time = LocalDateTime.parse(value, DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(); require(time > System.currentTimeMillis()) { "Reminder must be in the future." }; return time }
    private fun reward(input: Map<*, *>) = numeric(input, "reward_dollars", 0.0, 100.0)?.let { kotlin.math.round(it * 100).toInt() }
    private fun numeric(input: Map<*, *>, key: String, min: Double, max: Double, required: Boolean = false): Double? { if (!input.containsKey(key)) { require(!required) { "$key is required." }; return null }; val n = (input[key] as? Number)?.toDouble() ?: error("$key must be a number."); require(n.isFinite() && n in min..max) { "$key must be in $min–$max." }; return n }
    private fun boolean(input: Map<*, *>, key: String, default: Boolean? = null): Boolean = if (!input.containsKey(key) && default != null) default else input[key] as? Boolean ?: error("$key must be true or false.")
    private fun offset(input: Map<*, *>) = ((input["offset"] as? Number)?.toInt() ?: 0).coerceAtLeast(0)
    private fun stringList(input: Map<*, *>, key: String) = (input[key] as? List<*>)?.map { (it as? String)?.trim()?.takeIf { s -> s.isNotBlank() && s.length <= 500 } ?: error("$key contains an invalid title.") }?.take(50).orEmpty()
    private fun normalize(s: String) = s.trim().lowercase().trimEnd('.', '!', '?').trim()
    private fun result(status: String, message: String, items: JSONArray? = null, total: Int? = null, hasMore: Boolean? = null, entityId: Long? = null, entityString: String? = null) = JSONObject().put("status", status).put("message", message).apply { items?.let { put("items", it) }; total?.let { put("total", it) }; hasMore?.let { put("has_more", it) }; entityId?.let { put("entity_id", it) }; entityString?.let { put("session_id", it) } }.toString()
    internal fun taskJson(t: TodoTask) = JSONObject().put("id", t.id).put("title", t.title).put("parent_id", t.parentId).put("goal_id", t.goalId).put("created_at", t.createdAt).put("sort_order", t.sortOrder).put("completed_at", t.completedAt).put("started_at", t.startedAt).put("due_day", t.dueEpochDay).put("reminder_at", t.reminderAt).put("reminder_note", t.reminderNote).put("reward_cents", t.rewardCents).put("deleted_at", t.deletedAt).put("deleted_by", t.deletedBy).put("completed_by", t.completedBy)
    internal fun taskFromJson(j: JSONObject): TodoTask {
        fun nullable(key: String): Long? = if (j.isNull(key) || !j.has(key)) null else j.getLong(key)
        return TodoTask(id = j.getLong("id"), title = j.getString("title"), parentId = nullable("parent_id"), goalId = nullable("goal_id"), createdAt = j.getLong("created_at"), sortOrder = j.getLong("sort_order"), completedAt = nullable("completed_at"), startedAt = nullable("started_at"), dueEpochDay = nullable("due_day"), reminderAt = nullable("reminder_at"), reminderNote = if (j.isNull("reminder_note")) null else j.optString("reminder_note"), rewardCents = j.optInt("reward_cents"), deletedAt = nullable("deleted_at"), deletedBy = if (j.isNull("deleted_by") || !j.has("deleted_by")) null else j.getString("deleted_by"), completedBy = nullable("completed_by")) }
    private fun JSONObject.toMap(): Map<String, Any?> = keys().asSequence().associateWith { jsonValue(get(it)) }
    private fun jsonValue(value: Any?): Any? = when (value) { is JSONObject -> value.toMap(); is JSONArray -> (0 until value.length()).map { jsonValue(value.get(it)) }; JSONObject.NULL -> null; else -> value }
}

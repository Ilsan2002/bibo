package com.bibo.data

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.room.withTransaction
import com.bibo.widget.BiboWidget
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

sealed interface PatchField<out T> {
    data object Keep : PatchField<Nothing>
    data class Set<T>(val value: T) : PatchField<T>
}

data class TaskPatch(
    val title: String? = null,
    val goalId: PatchField<Long?> = PatchField.Keep,
    val dueEpochDay: PatchField<Long?> = PatchField.Keep,
    val reminderAt: PatchField<Long?> = PatchField.Keep,
    val reminderNote: PatchField<String?> = PatchField.Keep,
    val rewardCents: Int? = null,
)

data class GoalPatch(
    val name: String? = null,
    val color: Int? = null,
    val details: PatchField<String?> = PatchField.Keep,
    val targetDate: PatchField<Long?> = PatchField.Keep,
)

data class DeletedTaskTree(val tasks: List<TodoTask>)

/** One implementation of each task workflow, used by UI, tools, and secondary surfaces. */
object TaskCommands {
    private fun <T> PatchField<T>.or(old: T): T = when (this) {
        PatchField.Keep -> old
        is PatchField.Set -> value
    }

    private suspend fun current(db: BiboDb, id: Long): TodoTask =
        db.todos().byId(id)?.takeIf { it.deletedAt == null }
            ?: throw IllegalArgumentException("This task is no longer available.")

    private suspend fun validateGoal(db: BiboDb, id: Long?) {
        if (id != null) require(db.goals().byId(id)?.archived == false) { "Choose an active goal or the inbox." }
    }

    private fun validate(task: TodoTask) {
        require(task.title.trim().isNotEmpty() && task.title.length <= 500) { "Enter a task title of 1–500 characters." }
        require(task.rewardCents in 0..1_000_000) { "Reward must be between $0 and $10,000." }
        require(task.reminderAt == null || task.reminderAt > AppTime.nowMillis()) { "Choose a future reminder time." }
        require(task.reminderNote == null || task.reminderNote.length <= 2_000) { "Reminder note is too long." }
    }

    private fun subtree(tasks: List<TodoTask>, root: Long): List<TodoTask> {
        val selected = linkedSetOf(root)
        var changed: Boolean
        do {
            changed = false
            tasks.forEach { if (it.parentId in selected && selected.add(it.id)) changed = true }
        } while (changed)
        return tasks.filter { it.id in selected }
    }

    private suspend fun replay(db: BiboDb, id: String?, kind: String, input: String): String? {
        if (id == null) return null
        val record = db.commands().byId(id) ?: return null
        require(record.kind == kind && record.input == input) { "This action was already submitted with different details. Review its saved result before submitting another action." }
        return record.result.takeIf { record.status == "COMPLETED" }
    }

    private suspend fun receipt(db: BiboDb, id: String?, kind: String, input: String, result: String) {
        if (id == null) return
        val now = AppTime.nowMillis()
        val old = db.commands().byId(id)
        db.commands().upsert(CommandRecord(id, kind, "COMPLETED", input, result, old?.createdAt ?: now, now))
    }

    suspend fun create(context: Context, task: TodoTask, commandId: String? = null): Long =
        createTree(context, task, emptyList(), commandId)

    suspend fun createTree(context: Context, task: TodoTask, subtaskTitles: List<String>, commandId: String? = null): Long {
        val db = BiboDb.get(context)
        val input = JSONObject().put("title", task.title.trim()).put("parentId", task.parentId ?: JSONObject.NULL)
            .put("goalId", task.goalId ?: JSONObject.NULL).put("due", task.dueEpochDay ?: JSONObject.NULL)
            .put("reminder", task.reminderAt ?: JSONObject.NULL).put("note", task.reminderNote ?: JSONObject.NULL)
            .put("reward", task.rewardCents).put("steps", JSONArray(subtaskTitles.map(String::trim))).toString()
        val id = db.withTransaction {
            replay(db, commandId, "CREATE_TASK", input)?.let { return@withTransaction it.toLong() }
            validate(task)
            require(subtaskTitles.size <= 50) { "Use at most 50 steps per task." }
            val parent = task.parentId?.let { current(db, it) }
            require(parent == null || parent.parentId == null && parent.completedAt == null) { "Add steps to an open top-level task." }
            require(parent == null || subtaskTitles.isEmpty()) { "Nested step lists are not supported." }
            validateGoal(db, if (parent != null) parent.goalId else task.goalId)
            val at = AppTime.nowMillis()
            val rootTask = task.copy(id = 0, title = task.title.trim(), goalId = if (parent != null) parent.goalId else task.goalId,
                createdAt = at, completedAt = null, completedBy = null, startedAt = null, deletedAt = null, deletedBy = null)
            val rootId = db.todos().insert(rootTask)
            subtaskTitles.forEachIndexed { index, text ->
                val child = TodoTask(title = text.trim(), parentId = rootId, goalId = rootTask.goalId,
                    createdAt = at, sortOrder = index.toLong())
                validate(child)
                db.todos().insert(child)
            }
            receipt(db, commandId, "CREATE_TASK", input, rootId.toString())
            rootId
        }
        changed(context)
        return id
    }

    suspend fun addSteps(context: Context, parentId: Long, titles: List<String>, commandId: String? = null): List<Long> {
        val db = BiboDb.get(context)
        val input = JSONObject().put("parentId", parentId).put("titles", JSONArray(titles.map(String::trim))).toString()
        val result = db.withTransaction {
            replay(db, commandId, "ADD_STEPS", input)?.let { encoded ->
                val ids = JSONArray(encoded)
                return@withTransaction (0 until ids.length()).map(ids::getLong)
            }
            val parent = current(db, parentId)
            require(parent.parentId == null && parent.completedAt == null) { "Add steps to an open top-level task." }
            validateGoal(db, parent.goalId)
            val existing = db.todos().allOnce().filter { it.parentId == parentId }
            require(titles.isNotEmpty() && titles.size + existing.size <= 50) { "Use at most 50 steps per task." }
            val now = AppTime.nowMillis()
            val next = existing.maxOfOrNull { it.sortOrder }?.coerceAtMost(Long.MAX_VALUE - 51)?.plus(1) ?: 0L
            val ids = titles.mapIndexed { index, title ->
                val row = TodoTask(title = title.trim(), parentId = parentId, goalId = parent.goalId, createdAt = now, sortOrder = next + index)
                validate(row)
                db.todos().insert(row)
            }
            receipt(db, commandId, "ADD_STEPS", input, JSONArray(ids).toString())
            ids
        }
        changed(context)
        return result
    }

    /** Returns the event identity so an old Undo cannot reverse a newer completion. */
    suspend fun complete(context: Context, taskId: Long, completed: Boolean = true, commandId: String? = null): Long? {
        val db = BiboDb.get(context)
        val completedIds = mutableSetOf<Long>()
        val result = db.withTransaction {
            val kind = if (completed) "COMPLETE_TASK" else "REOPEN_TASK"
            replay(db, commandId, kind, "$taskId")?.let { return@withTransaction it.toLongOrNull() }
            val task = current(db, taskId)
            if (!completed) {
                val event = task.completedBy
                reopen(db, task)
                receipt(db, commandId, kind, "$taskId", event?.toString().orEmpty())
                return@withTransaction event
            }
            if (task.completedAt != null) {
                receipt(db, commandId, kind, "$taskId", task.completedBy?.toString().orEmpty())
                return@withTransaction task.completedBy
            }
            val affected = subtree(db.todos().allOnce(), taskId)
            TimerController.finishSessionInTransaction(context, affected.map { it.id }.toSet())
            val at = AppTime.nowMillis()
            val event = db.completions().insert(TaskCompletion(taskId = taskId, atMillis = at))
            affected.filter { it.completedAt == null }.forEach {
                db.todos().setCompleted(it.id, at, event)
                completedIds += it.id
                grantTaskReward(db, it, event, at)
            }
            db.activityBlocks().insert(ActivityBlock(title = task.title, startMillis = at, endMillis = at,
                source = "COMPLETION", goalId = task.goalId, taskId = taskId, completionId = event,
                provenance = "REPORTED"))
            receipt(db, commandId, kind, "$taskId", event.toString())
            event
        }
        completedIds.forEach { TaskReminders.cancel(context, it) }
        changed(context, reconcileTimer = true)
        return result
    }

    private suspend fun reopen(db: BiboDb, task: TodoTask) {
        if (task.completedAt == null) return
        val event = task.completedBy
        if (event == null) db.todos().setCompleted(task.id, null, null)
        else {
            // Child reopening must not reopen its parent or independently completed siblings.
            val completion = db.completions().byId(event)
            val affected = if (completion?.taskId == task.id) db.todos().completedBy(event) else listOf(task)
            affected.forEach { db.todos().setCompleted(it.id, null, null) }
            if (completion?.taskId == task.id) {
                db.completions().reverse(event, AppTime.nowMillis())
                db.activityBlocks().deleteCompletion(event)
            }
        }
    }

    suspend fun undoCompletion(context: Context, taskId: Long, completionId: Long) {
        val db = BiboDb.get(context)
        db.withTransaction {
            val task = current(db, taskId)
            check(task.completedBy == completionId) { "This task changed after completion. Open it to review its current state." }
            reopen(db, task)
        }
        changed(context)
    }

    suspend fun delete(context: Context, taskId: Long, commandId: String? = null): DeletedTaskTree {
        val db = BiboDb.get(context)
        val tree = db.withTransaction {
            replay(db, commandId, "DELETE_TASK", "$taskId")?.let { saved ->
                val receipt = JSONObject(saved)
                val batch = receipt.getString("deletionId")
                val ids = receipt.getJSONArray("ids")
                return@withTransaction DeletedTaskTree((0 until ids.length()).mapNotNull { index ->
                    db.todos().byId(ids.getLong(index))?.copy(deletedBy = batch)
                })
            }
            current(db, taskId)
            val rows = subtree(db.todos().allOnce(), taskId)
            TimerController.finishSessionInTransaction(context, rows.map { it.id }.toSet())
            val at = AppTime.nowMillis()
            val batch = UUID.randomUUID().toString()
            rows.forEach { db.todos().setDeleted(it.id, at, batch) }
            val result = JSONObject().put("deletionId", batch).put("ids", JSONArray(rows.map { it.id })).toString()
            receipt(db, commandId, "DELETE_TASK", "$taskId", result)
            DeletedTaskTree(rows.map { it.copy(deletedAt = at, deletedBy = batch, startedAt = null) })
        }
        tree.tasks.forEach { TaskReminders.cancel(context, it.id) }
        changed(context, reconcileTimer = true)
        return tree
    }

    suspend fun restore(context: Context, tree: DeletedTaskTree) {
        val db = BiboDb.get(context)
        db.withTransaction {
            tree.tasks.sortedBy { it.parentId != null }.forEach { original ->
                val row = db.todos().byId(original.id) ?: return@forEach
                if (row.deletedAt == null || original.deletedBy == null || row.deletedBy != original.deletedBy) return@forEach
                val staleCompletion = row.completedBy?.let { db.completions().byId(it)?.reversedAt != null } == true
                val restored = if (staleCompletion) row.copy(completedAt = null, completedBy = null) else row
                val parent = restored.parentId?.let { db.todos().byId(it) }?.takeIf { it.deletedAt == null }
                val parentId = parent?.id?.takeUnless { parent.completedAt != null && restored.completedAt == null }
                val goal = (if (parent != null) parent.goalId else row.goalId)?.takeIf { db.goals().byId(it) != null }
                db.todos().update(restored.copy(parentId = parentId, goalId = goal, deletedAt = null, deletedBy = null, startedAt = null,
                    reminderAt = row.reminderAt?.takeIf { it > AppTime.nowMillis() }))
            }
        }
        changed(context)
    }

    suspend fun move(context: Context, taskId: Long, goalId: Long?) =
        update(context, taskId, TaskPatch(goalId = PatchField.Set(goalId)))

    suspend fun update(context: Context, taskId: Long, patch: TaskPatch, commandId: String? = null) {
        val db = BiboDb.get(context)
        val input = JSONObject().put("taskId", taskId).apply {
            patch.title?.let { put("title", it.trim()) }; patch.rewardCents?.let { put("reward", it) }
            patchField("goal", patch.goalId); patchField("due", patch.dueEpochDay)
            patchField("reminder", patch.reminderAt); patchField("note", patch.reminderNote)
        }.toString()
        db.withTransaction {
            if (replay(db, commandId, "UPDATE_TASK", input) != null) return@withTransaction
            val task = current(db, taskId)
            val goalId = patch.goalId.or(task.goalId)
            if (patch.goalId is PatchField.Set) {
                require(task.parentId == null) { "Move the parent task to change the goal of its steps." }
                validateGoal(db, goalId)
            }
            val updated = task.copy(title = patch.title?.trim() ?: task.title, goalId = goalId,
                dueEpochDay = patch.dueEpochDay.or(task.dueEpochDay), reminderAt = patch.reminderAt.or(task.reminderAt),
                reminderNote = patch.reminderNote.or(task.reminderNote), rewardCents = patch.rewardCents ?: task.rewardCents)
            // Existing overdue reminders must remain editable without forcing a title edit to reschedule.
            validate(updated.copy(reminderAt = if (patch.reminderAt is PatchField.Keep) null else updated.reminderAt))
            db.todos().update(updated)
            if (patch.goalId is PatchField.Set) subtree(db.todos().allOnce(), taskId).filter { it.id != taskId }.forEach {
                db.todos().update(it.copy(goalId = goalId))
            }
            receipt(db, commandId, "UPDATE_TASK", input, "Updated task $taskId")
        }
        TaskReminders.cancel(context, taskId)
        changed(context)
    }

    suspend fun reorder(context: Context, orderedIds: List<Long>) {
        val db = BiboDb.get(context)
        db.withTransaction {
            require(orderedIds.distinct().size == orderedIds.size) { "Task order contains duplicates." }
            val all = db.todos().allOnce().filter { it.parentId == null && it.completedAt == null }
            val selected = orderedIds.toSet()
            require(all.map { it.id }.containsAll(selected)) { "The task list changed. Try reordering again." }
            val positions = all.mapIndexedNotNull { i, task -> i.takeIf { task.id in selected } }
            val ids = all.map { it.id }.toMutableList()
            positions.forEachIndexed { i, position -> ids[position] = orderedIds[i] }
            ids.forEachIndexed { i, id -> db.todos().updateSortOrder(id, i.toLong()) }
        }
        changed(context)
    }

    suspend fun saveGoal(context: Context, goal: Goal, commandId: String? = null): Long {
        val db = BiboDb.get(context)
        val input = JSONObject().put("id", goal.id).put("name", goal.name.trim()).put("color", goal.color)
            .put("details", goal.details ?: JSONObject.NULL).put("target", goal.targetDate ?: JSONObject.NULL).toString()
        val id = db.withTransaction {
            replay(db, commandId, "SAVE_GOAL", input)?.let { return@withTransaction it.toLong() }
            require(goal.name.trim().isNotEmpty() && goal.name.length <= 200) { "Enter a goal name of 1–200 characters." }
            require(goal.details == null || goal.details.length <= 10_000) { "Goal details are too long." }
            val current = if (goal.id > 0) db.goals().byId(goal.id) ?: error("This goal is no longer available.") else null
            val cleaned = goal.copy(name = goal.name.trim(), createdAt = current?.createdAt ?: AppTime.nowMillis(),
                archived = current?.archived ?: goal.archived)
            val saved = if (current == null) db.goals().insert(cleaned.copy(id = 0)) else {
                db.goals().update(cleaned); current.id
            }
            receipt(db, commandId, "SAVE_GOAL", input, saved.toString())
            saved
        }
        changed(context)
        return id
    }

    private fun JSONObject.patchField(name: String, patch: PatchField<*>) {
        if (patch is PatchField.Set) put(name, patch.value ?: JSONObject.NULL)
    }

    suspend fun updateGoal(context: Context, goalId: Long, patch: GoalPatch, commandId: String? = null) {
        val db = BiboDb.get(context)
        val input = JSONObject().put("id", goalId).apply {
            patch.name?.let { put("name", it.trim()) }; patch.color?.let { put("color", it) }
            patchField("details", patch.details); patchField("target", patch.targetDate)
        }.toString()
        db.withTransaction {
            if (replay(db, commandId, "UPDATE_GOAL", input) != null) return@withTransaction
            val goal = db.goals().byId(goalId) ?: error("This goal is no longer available.")
            val updated = goal.copy(name = patch.name?.trim() ?: goal.name, color = patch.color ?: goal.color,
                details = patch.details.or(goal.details), targetDate = patch.targetDate.or(goal.targetDate))
            require(updated.name.isNotEmpty() && updated.name.length <= 200) { "Enter a goal name of 1–200 characters." }
            require(updated.details == null || updated.details.length <= 10_000) { "Goal details are too long." }
            db.goals().update(updated)
            receipt(db, commandId, "UPDATE_GOAL", input, "Updated goal $goalId")
        }
        changed(context)
    }

    suspend fun archiveGoal(context: Context, goalId: Long, archived: Boolean = true, commandId: String? = null) {
        val db = BiboDb.get(context)
        db.withTransaction {
            if (replay(db, commandId, "ARCHIVE_GOAL", "$goalId:$archived") != null) return@withTransaction
            require(db.goals().byId(goalId) != null) { "This goal is no longer available." }
            db.goals().setArchived(goalId, archived)
            receipt(db, commandId, "ARCHIVE_GOAL", "$goalId:$archived", "Goal ${if (archived) "archived" else "restored"}")
        }
        changed(context)
    }

    private suspend fun changed(context: Context, reconcileTimer: Boolean = false) {
        // The local command has already committed. Platform failures must not undo its result.
        if (reconcileTimer) runCatching { TimerController.reconcile(context) }
        runCatching { TaskReminders.rescheduleAllAwait(context) }
        runCatching { BiboWidget().updateAll(context.applicationContext) }
    }
}

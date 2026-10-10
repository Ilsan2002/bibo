package com.bibo.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.bibo.data.AppScope
import com.bibo.data.AppTime
import com.bibo.data.TimerController
import com.bibo.data.BiboDb
import com.bibo.data.Goal
import com.bibo.data.GoalPatch
import com.bibo.data.PatchField
import com.bibo.data.RewardBalance
import com.bibo.data.Rewards
import com.bibo.data.TaskCommands
import com.bibo.data.DeletedTaskTree
import com.bibo.data.TaskPatch
import com.bibo.data.TodoTask
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class TaskDraft(
    val id: Long? = null, val parentId: Long? = null, val title: String = "",
    val goalId: Long? = null, val dueEpochDay: Long? = null, val reminderAt: Long? = null,
    val reminderNote: String = "", val rewardCents: Int = 0, val changed: Set<String> = emptySet(),
    val commandId: String = java.util.UUID.randomUUID().toString(),
)

data class GoalDraft(val id: Long? = null, val name: String = "", val color: Int = 0xFF2962FF.toInt(),
    val details: String = "", val targetDate: Long? = null, val createdAt: Long = AppTime.nowMillis(), val archived: Boolean = false,
    val commandId: String = java.util.UUID.randomUUID().toString(), val changed: Set<String> = emptySet()) {
    fun toGoal() = Goal(id = id ?: 0, name = name, color = color, targetDate = targetDate, createdAt = createdAt, archived = archived, details = details.ifBlank { null })
}

data class TaskNotice(val message: String, val undo: (suspend () -> Unit)? = null)

/** Presentation and saved drafts only; shared commands own every domain mutation. */
class TaskViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    private val context: Application = application
    private val db = BiboDb.get(context)
    private val _tasksLoaded = MutableStateFlow(false)
    val tasksLoaded = _tasksLoaded.asStateFlow()
    private val _goalsLoaded = MutableStateFlow(false)
    val goalsLoaded = _goalsLoaded.asStateFlow()
    val tasks = db.todos().all().onEach { _tasksLoaded.value = true }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val deleted = db.todos().deleted().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val goals = db.goals().allIncludingArchived().onEach { _goalsLoaded.value = true }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val wallet = Rewards.observe(context).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RewardBalance())
    val filterGoalId = saved.getStateFlow<Long?>("task.filter", null)
    val profileGoalId = saved.getStateFlow<Long?>("task.profile", null)
    val showArchive = saved.getStateFlow("task.archive", false)
    private val _draft = MutableStateFlow(readDraft())
    val draft = _draft.asStateFlow()
    private val _goalDraft = MutableStateFlow(readGoalDraft())
    val goalDraft = _goalDraft.asStateFlow()
    private val _pending = MutableStateFlow<Set<Long>>(buildSet {
        _draft.value?.let { add(it.id ?: -1L) }
        if (_goalDraft.value != null) add(-3L)
    })
    val pending = _pending.asStateFlow()
    private val _error = MutableStateFlow<Pair<Long, String>?>(null)
    val error = _error.asStateFlow()
    private val _notices = MutableSharedFlow<TaskNotice>(extraBufferCapacity = 32)
    val notices = _notices.asSharedFlow()

    init {
        // A command can commit immediately before process death. Consume that receipt before
        // the restored draft becomes editable, so retrying cannot create a second record.
        val restoredTask = _draft.value
        val restoredGoal = _goalDraft.value
        if (restoredTask != null || restoredGoal != null) AppScope.io.launch {
            try {
                if (restoredTask != null) {
                    val receipt = db.commands().byId(restoredTask.commandId)
                    if (receipt?.status == "COMPLETED" && receipt.kind == if (restoredTask.id == null) "CREATE_TASK" else "UPDATE_TASK") {
                        withContext(Dispatchers.Main) { if (_draft.value?.commandId == restoredTask.commandId) editDraft(null) }
                        _notices.emit(TaskNotice("Your task was already saved"))
                    }
                }
                if (restoredGoal != null) {
                    val receipt = db.commands().byId(restoredGoal.commandId)
                    if (receipt?.status == "COMPLETED" && receipt.kind == if (restoredGoal.id == null) "SAVE_GOAL" else "UPDATE_GOAL") {
                        withContext(Dispatchers.Main) { if (_goalDraft.value?.commandId == restoredGoal.commandId) persistGoalDraft(null) }
                        _notices.emit(TaskNotice("Your goal was already saved"))
                    }
                }
            } catch (error: Exception) {
                _error.value = (restoredTask?.id ?: if (restoredTask != null) -1L else -3L) to (error.message ?: "Could not check whether your draft was already saved. Try again.")
            } finally {
                synchronized(this@TaskViewModel) {
                    _pending.value = _pending.value - setOfNotNull(restoredTask?.let { it.id ?: -1L }, restoredGoal?.let { -3L })
                }
            }
        }
    }

    fun filter(id: Long?) { saved["task.filter"] = id }
    fun profile(id: Long?) { saved["task.profile"] = id }
    fun archiveVisible(value: Boolean) { saved["task.archive"] = value }
    fun newTask(parent: TodoTask? = null) = editDraft(TaskDraft(parentId = parent?.id, goalId = if (parent != null) parent.goalId else filterGoalId.value))
    fun edit(task: TodoTask) = editDraft(TaskDraft(task.id, task.parentId, task.title, task.goalId, task.dueEpochDay, task.reminderAt, task.reminderNote.orEmpty(), task.rewardCents))
    fun changeDraft(value: TaskDraft?) {
        if ((draft.value?.id ?: -1L) in _pending.value) return
        val previous = draft.value
        if (previous == null || value == null) { editDraft(value); return }
        val changes = buildSet {
            addAll(previous.changed)
            if (value.title != previous.title) add("title")
            if (value.goalId != previous.goalId) add("goal")
            if (value.dueEpochDay != previous.dueEpochDay) add("due")
            if (value.reminderAt != previous.reminderAt) add("reminder")
            if (value.reminderNote != previous.reminderNote) add("note")
            if (value.rewardCents != previous.rewardCents) add("reward")
        }
        editDraft(value.copy(changed = changes))
    }
    fun editDraft(value: TaskDraft?) {
        _draft.value = value
        saved["task.draft.open"] = value != null
        if (value == null) return
        saved["task.draft.id"] = value.id
        saved["task.draft.parent"] = value.parentId
        saved["task.draft.title"] = value.title
        saved["task.draft.goal"] = value.goalId
        saved["task.draft.due"] = value.dueEpochDay
        saved["task.draft.reminder"] = value.reminderAt
        saved["task.draft.note"] = value.reminderNote
        saved["task.draft.reward"] = value.rewardCents
        saved["task.draft.changed"] = ArrayList(value.changed)
        saved["task.draft.command"] = value.commandId
    }
    private fun readDraft(): TaskDraft? {
        if (saved.get<Boolean>("task.draft.open") != true) return null
        return TaskDraft(saved["task.draft.id"], saved["task.draft.parent"], saved["task.draft.title"] ?: "",
            saved["task.draft.goal"], saved["task.draft.due"], saved["task.draft.reminder"],
            saved["task.draft.note"] ?: "", saved["task.draft.reward"] ?: 0,
            saved.get<ArrayList<String>>("task.draft.changed")?.toSet() ?: emptySet(), saved["task.draft.command"] ?: java.util.UUID.randomUUID().toString())
    }
    fun saveDraft() {
        val value = draft.value ?: return
        if (value.title.isBlank() || ("reminder" in value.changed && value.reminderAt != null && value.reminderAt <= AppTime.nowMillis())) return
        command(value.id ?: -1) {
            if (value.id == null) {
                val now = AppTime.nowMillis()
                TaskCommands.create(context, TodoTask(title = value.title.trim(), parentId = value.parentId,
                    createdAt = now, sortOrder = now, goalId = value.goalId, dueEpochDay = value.dueEpochDay,
                    reminderAt = value.reminderAt, reminderNote = value.reminderNote.trim().ifBlank { null }, rewardCents = value.rewardCents), commandId = value.commandId)
            } else TaskCommands.update(context, value.id, TaskPatch(title = value.title.trim().takeIf { "title" in value.changed },
                goalId = if ("goal" in value.changed && value.parentId == null) PatchField.Set(value.goalId) else PatchField.Keep,
                dueEpochDay = if ("due" in value.changed) PatchField.Set(value.dueEpochDay) else PatchField.Keep,
                reminderAt = if ("reminder" in value.changed) PatchField.Set(value.reminderAt) else PatchField.Keep,
                reminderNote = if ("note" in value.changed) PatchField.Set(value.reminderNote.trim().ifBlank { null }) else PatchField.Keep,
                rewardCents = value.rewardCents.takeIf { "reward" in value.changed }), commandId = value.commandId)
            withContext(Dispatchers.Main) {
                if (value.parentId == null && filterGoalId.value != null && value.goalId != filterGoalId.value) filter(null)
                if (draft.value == value) editDraft(null)
            }
            _notices.emit(TaskNotice("Task saved"))
        }
    }
    fun complete(task: TodoTask, completed: Boolean) = command(task.id) {
        val event = TaskCommands.complete(context, task.id, completed)
        _notices.emit(TaskNotice(if (completed) "Task completed" else "Task reopened",
            if (completed && event != null) ({ TaskCommands.undoCompletion(context, task.id, event) }) else null))
    }
    fun delete(task: TodoTask) = command(task.id) {
        val tree = TaskCommands.delete(context, task.id)
        _notices.emit(TaskNotice("Deleted “${task.title}”") { TaskCommands.restore(context, tree) })
    }
    fun timer(task: TodoTask, displayedSessionId: String?) = command(task.id) {
        if (displayedSessionId != null) {
            val stopped = TimerController.stopSession(context, displayedSessionId).getOrThrow()
            _notices.emit(TaskNotice(if (stopped == null) "That session already ended" else "Session saved"))
        } else {
            TimerController.startTaskAwait(context, task.id, task.title, task.goalId, requireIdle = true).getOrThrow()
            _notices.emit(TaskNotice("Task timer started"))
        }
    }
    fun restoreDeleted(task: TodoTask) = command(task.id) {
        val deletedRows = db.todos().deletedOnce()
        val selected = linkedSetOf(task.id)
        var changed: Boolean
        do {
            changed = false
            deletedRows.filter { task.deletedBy != null && it.deletedBy == task.deletedBy }.forEach { if (it.parentId in selected && selected.add(it.id)) changed = true }
        } while (changed)
        TaskCommands.restore(context, DeletedTaskTree(deletedRows.filter { it.id in selected }))
        _notices.emit(TaskNotice("Restored task and its deleted steps. Steps with missing or completed parents may become independent tasks."))
    }
    fun reorder(ids: List<Long>) = command(-2) { TaskCommands.reorder(context, ids) }
    fun editGoal(goal: Goal?) = persistGoalDraft(if (goal == null) GoalDraft() else GoalDraft(goal.id, goal.name, goal.color, goal.details.orEmpty(), goal.targetDate, goal.createdAt, goal.archived))
    fun changeGoalDraft(value: GoalDraft?) {
        if (-3L in _pending.value && value != null) return
        val previous = goalDraft.value
        if (previous == null || value == null) { persistGoalDraft(value); return }
        val changed = buildSet {
            addAll(previous.changed)
            if (value.name != previous.name) add("name")
            if (value.color != previous.color) add("color")
            if (value.details != previous.details) add("details")
            if (value.targetDate != previous.targetDate) add("target")
        }
        persistGoalDraft(value.copy(changed = changed))
    }
    private fun persistGoalDraft(value: GoalDraft?) {
        _goalDraft.value = value; saved["goal.draft.open"] = value != null
        if (value == null) return
        saved["goal.draft.id"] = value.id; saved["goal.draft.name"] = value.name; saved["goal.draft.color"] = value.color
        saved["goal.draft.details"] = value.details; saved["goal.draft.target"] = value.targetDate
        saved["goal.draft.created"] = value.createdAt; saved["goal.draft.archived"] = value.archived; saved["goal.draft.command"] = value.commandId
        saved["goal.draft.changed"] = ArrayList(value.changed)
    }
    private fun readGoalDraft(): GoalDraft? {
        if (saved.get<Boolean>("goal.draft.open") != true) return null
        return GoalDraft(saved["goal.draft.id"], saved["goal.draft.name"] ?: "", saved["goal.draft.color"] ?: 0xFF2962FF.toInt(),
            saved["goal.draft.details"] ?: "", saved["goal.draft.target"], saved["goal.draft.created"] ?: AppTime.nowMillis(), saved["goal.draft.archived"] ?: false, saved["goal.draft.command"] ?: java.util.UUID.randomUUID().toString(), saved.get<ArrayList<String>>("goal.draft.changed")?.toSet() ?: emptySet())
    }
    fun saveGoalDraft() {
        val value = goalDraft.value ?: return
        command(-3) {
            if (value.id == null) TaskCommands.saveGoal(context, value.toGoal(), commandId = value.commandId)
            else TaskCommands.updateGoal(context, value.id, GoalPatch(
                name = value.name.takeIf { "name" in value.changed },
                color = value.color.takeIf { "color" in value.changed },
                details = if ("details" in value.changed) PatchField.Set(value.details.trim().ifBlank { null }) else PatchField.Keep,
                targetDate = if ("target" in value.changed) PatchField.Set(value.targetDate) else PatchField.Keep), commandId = value.commandId)
            withContext(Dispatchers.Main) { if (goalDraft.value == value) changeGoalDraft(null) }
            _notices.emit(TaskNotice("Goal saved"))
        }
    }
    fun archiveGoal(goal: Goal, archived: Boolean = true) = command(-3) {
        TaskCommands.archiveGoal(context, goal.id, archived)
        withContext(Dispatchers.Main) {
            if (filterGoalId.value == goal.id && archived) filter(null)
            if (profileGoalId.value == goal.id && archived) profile(null)
            if (goalDraft.value?.id == goal.id) changeGoalDraft(null)
        }
        _notices.emit(TaskNotice(if (archived) "Goal archived" else "Goal restored") {
            TaskCommands.archiveGoal(context, goal.id, !archived)
        })
    }
    private fun command(id: Long, operation: suspend () -> Unit) {
        synchronized(this) {
            if (id in _pending.value) return
            _pending.value = _pending.value + id
            if (_error.value?.first == id) _error.value = null
        }
        AppScope.io.launch {
            try { operation() }
            catch (error: Exception) {
                val message = error.message ?: "Action could not be completed. Try again."
                _error.value = id to message
                _notices.emit(TaskNotice(message))
            }
            finally { synchronized(this@TaskViewModel) { _pending.value = _pending.value - id } }
        }
    }
}

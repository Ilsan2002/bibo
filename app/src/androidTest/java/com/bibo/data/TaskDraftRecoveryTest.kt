package com.bibo.data

import android.app.Application
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bibo.ui.TaskViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TaskDraftRecoveryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: BiboDb
    private val stores = mutableListOf<ViewModelStore>()

    @Before fun prepare() = runBlocking {
        TimerController.resetForDatabaseReplacement(context)
        db = Room.inMemoryDatabaseBuilder(context, BiboDb::class.java).build()
        BiboDb.replaceInstanceForTesting(db)
    }

    @After fun close() = runBlocking {
        withContext(Dispatchers.Main) { stores.forEach { it.clear() } }
        TimerController.resetForDatabaseReplacement(context)
        BiboDb.replaceInstanceForTesting(null)
    }

    private fun model(state: Map<String, Any?>): TaskViewModel {
        val store = ViewModelStore().also { stores += it }
        return TaskViewModel(context.applicationContext as Application, SavedStateHandle(state)).also { store.put("tasks", it) }
    }

    private suspend fun awaitIdle(vm: TaskViewModel) = withTimeout(5_000) {
        while (vm.pending.value.isNotEmpty()) delay(10)
    }

    @Test fun committedTaskDraftIsConsumedBeforeItCanBeEditedOrResubmitted() = runBlocking {
        TaskCommands.create(context, TodoTask(title = "Saved task", createdAt = 0), commandId = "task-saved")
        val vm = withContext(Dispatchers.Main) {
            model(mapOf("task.draft.open" to true, "task.draft.title" to "Saved task", "task.draft.command" to "task-saved")).also {
                assertTrue(-1L in it.pending.value)
                it.changeDraft(it.draft.value!!.copy(title = "A second task"))
                assertEquals("Saved task", it.draft.value!!.title)
                it.saveDraft()
            }
        }
        awaitIdle(vm)
        assertNull(vm.draft.value)
        assertEquals(1, db.todos().allOnce().size)
        assertNull(vm.error.value)
    }

    @Test fun committedGoalDraftIsConsumedWithoutCreatingAnotherGoal() = runBlocking {
        TaskCommands.saveGoal(context, Goal(name = "Saved goal", color = 1, createdAt = 0), commandId = "goal-saved")
        val vm = withContext(Dispatchers.Main) {
            model(mapOf("goal.draft.open" to true, "goal.draft.name" to "Saved goal", "goal.draft.command" to "goal-saved"))
        }
        awaitIdle(vm)
        assertNull(vm.goalDraft.value)
        assertEquals(1, db.goals().allOnce().size)
        assertNull(vm.error.value)
    }

    @Test fun restoredDirtyGoalDraftPreservesChangesMadeByAnotherSurface() = runBlocking {
        val id = TaskCommands.saveGoal(context, Goal(name = "Original", color = 1, createdAt = 0, targetDate = 21000))
        TaskCommands.updateGoal(context, id, GoalPatch(name = "Current name", color = 2))
        val vm = withContext(Dispatchers.Main) {
            model(mapOf("goal.draft.open" to true, "goal.draft.id" to id, "goal.draft.name" to "Original",
                "goal.draft.color" to 1, "goal.draft.details" to "My new details", "goal.draft.target" to 21000L,
                "goal.draft.changed" to arrayListOf("details"), "goal.draft.command" to "restored-goal-edit"))
        }
        awaitIdle(vm)
        withContext(Dispatchers.Main) { vm.saveGoalDraft() }
        awaitIdle(vm)
        val current = db.goals().byId(id)!!
        assertEquals("Current name", current.name)
        assertEquals(2, current.color)
        assertEquals("My new details", current.details)
        assertEquals(21000L, current.targetDate)
        assertNull(vm.goalDraft.value)
        assertNull(vm.error.value)
    }
}

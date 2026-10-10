package com.bibo.data

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TaskWorkflowTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: BiboDb
    @Before fun prepare() = runBlocking {
        TimerController.resetForDatabaseReplacement(context)
        db = Room.inMemoryDatabaseBuilder(context, BiboDb::class.java).build()
        BiboDb.replaceInstanceForTesting(db)
    }
    @After fun close() = runBlocking {
        TimerController.resetForDatabaseReplacement(context)
        BiboDb.replaceInstanceForTesting(null)
    }
    private suspend fun task(title: String, parent: Long? = null, reward: Int = 0): Long =
        db.todos().insert(TodoTask(title = title, parentId = parent, rewardCents = reward, createdAt = AppTime.nowMillis()))

    @Test fun completionStopsLinkedSessionAndUndoOnlyReopensItsOwnCascade() = runBlocking {
        val parent = task("Outcome", reward = 300)
        val independent = task("Already done", parent, 100)
        val remaining = task("Remaining", parent, 200)
        TaskCommands.complete(context, independent)
        val at = AppTime.nowMillis()
        db.workSessions().insert(WorkSession(id = "running", title = "Remaining", kind = "TASK", taskId = remaining,
            startMillis = at - 5000, elapsedAnchorMillis = SystemClock.elapsedRealtime() - 5000,
            lastCheckpointMillis = at, bootCount = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)))
        val event = TaskCommands.complete(context, parent)!!
        assertNull(db.workSessions().active())
        assertNotNull(db.todos().byId(remaining)!!.completedAt)
        val blocks = db.activityBlocks().blocksInList(at - 10_000, at + 10_000)
        assertEquals(1, blocks.count { it.sessionId == "running" && it.source == "TASK_TIMER" })
        assertTrue(blocks.filter { it.source == "COMPLETION" }.all { it.startMillis == it.endMillis })
        assertTrue(AppTime.measuredWorkMillis(blocks, at - 10_000, at + 10_000) in 5000..10_000)
        TaskCommands.undoCompletion(context, parent, event)
        assertNull(db.todos().byId(parent)!!.completedAt)
        assertNull(db.todos().byId(remaining)!!.completedAt)
        assertNotNull(db.todos().byId(independent)!!.completedAt)
        assertEquals(200, db.rewards().entry("task:$remaining")!!.amountCents)
    }

    @Test fun dirtyPatchAndReorderPreserveTimerCompletionAndReminderFields() = runBlocking {
        val first = task("A")
        val second = task("B")
        val reminder = AppTime.nowMillis() + 3_600_000
        db.todos().update(db.todos().byId(first)!!.copy(startedAt = 200, dueEpochDay = 21000, reminderAt = reminder, rewardCents = 250))
        TaskCommands.update(context, first, TaskPatch(title = "Renamed"))
        TaskCommands.reorder(context, listOf(second, first))
        val current = db.todos().byId(first)!!
        assertEquals("Renamed", current.title)
        assertEquals(200L, current.startedAt)
        assertEquals(21000L, current.dueEpochDay)
        assertEquals(reminder, current.reminderAt)
        assertEquals(250, current.rewardCents)
        assertEquals(listOf(second, first), db.todos().allOnce().map { it.id })
    }

    @Test fun retryAndOldUndoCannotDuplicateOrReverseNewerCompletion() = runBlocking {
        val id = TaskCommands.create(context, TodoTask(title = "Once", createdAt = 0, rewardCents = 500), "create-1")
        assertEquals(id, TaskCommands.create(context, TodoTask(title = "Once", createdAt = 0, rewardCents = 500), "create-1"))
        val first = TaskCommands.complete(context, id, commandId = "complete-1")!!
        assertEquals(first, TaskCommands.complete(context, id, commandId = "complete-1"))
        TaskCommands.complete(context, id, false)
        val newer = TaskCommands.complete(context, id)!!
        assertNotEquals(first, newer)
        assertTrue(runCatching { TaskCommands.undoCompletion(context, id, first) }.isFailure)
        assertEquals(newer, db.todos().byId(id)!!.completedBy)
        assertEquals(500, db.rewards().entry("task:$id")!!.amountCents)
        assertEquals(1, db.todos().allOnce().size)
    }

    @Test fun deleteAndRestoreUseCurrentRowsAndCannotRestoreAnOrphan() = runBlocking {
        val parent = task("Parent")
        val child = task("Child", parent)
        val tree = TaskCommands.delete(context, parent)
        assertTrue(db.todos().allOnce().isEmpty())
        assertEquals(2, db.todos().deletedOnce().size)
        db.todos().update(db.todos().byId(child)!!.copy(title = "Latest child"))
        db.todos().delete(db.todos().byId(parent)!!)
        TaskCommands.restore(context, tree)
        val restored = db.todos().byId(child)!!
        assertEquals("Latest child", restored.title)
        assertNull(restored.parentId)
        assertNull(restored.deletedAt)
    }

    @Test fun editingAnArchivedGoalCannotSilentlyRestoreIt() = runBlocking {
        val goal = Goal(name = "Goal", color = 42, createdAt = 100)
        val id = TaskCommands.saveGoal(context, goal)
        TaskCommands.archiveGoal(context, id)
        TaskCommands.saveGoal(context, goal.copy(id = id, details = "Edit from earlier draft"))
        assertTrue(db.goals().byId(id)!!.archived)
        assertEquals("Edit from earlier draft", db.goals().byId(id)!!.details)
    }

    @Test fun staleDeleteUndoAndRetriedDeleteCannotRestoreANewerDeletion() = runBlocking {
        val id = task("Task")
        val first = TaskCommands.delete(context, id, commandId = "delete-original")
        TaskCommands.restore(context, first)
        val second = TaskCommands.delete(context, id)
        assertNotEquals(first.tasks.single().deletedBy, second.tasks.single().deletedBy)

        TaskCommands.restore(context, first)
        assertEquals(second.tasks.single().deletedBy, db.todos().byId(id)!!.deletedBy)
        val retriedFirst = TaskCommands.delete(context, id, commandId = "delete-original")
        TaskCommands.restore(context, retriedFirst)
        assertEquals(second.tasks.single().deletedBy, db.todos().byId(id)!!.deletedBy)

        TaskCommands.restore(context, second)
        assertNull(db.todos().byId(id)!!.deletedAt)
    }

    @Test fun separatelyDeletedChildKeepsItsBatchWhenParentIsDeletedAtTheSameTime() = runBlocking {
        val oldClock = AppTime.clock
        try {
            AppTime.clock = java.time.Clock.fixed(java.time.Instant.ofEpochMilli(2_000_000_000_000), java.time.ZoneOffset.UTC)
            val parent = task("Parent")
            val child = task("Child", parent)
            val deletedChild = TaskCommands.delete(context, child)
            val deletedParent = TaskCommands.delete(context, parent)
            assertEquals(deletedChild.tasks.single().deletedAt, deletedParent.tasks.single().deletedAt)
            assertNotEquals(deletedChild.tasks.single().deletedBy, deletedParent.tasks.single().deletedBy)
            assertEquals(listOf(parent), deletedParent.tasks.map { it.id })

            TaskCommands.restore(context, deletedParent)
            assertNull(db.todos().byId(parent)!!.deletedAt)
            assertEquals(deletedChild.tasks.single().deletedBy, db.todos().byId(child)!!.deletedBy)
        } finally { AppTime.clock = oldClock }
    }

    @Test fun restoredChildInheritsItsCurrentParentsGoal() = runBlocking {
        val firstGoal = TaskCommands.saveGoal(context, Goal(name = "First", color = 1, createdAt = 0))
        val secondGoal = TaskCommands.saveGoal(context, Goal(name = "Second", color = 2, createdAt = 0))
        val parent = TaskCommands.create(context, TodoTask(title = "Parent", goalId = firstGoal, createdAt = 0))
        val child = TaskCommands.create(context, TodoTask(title = "Child", parentId = parent, createdAt = 0))
        val deleted = TaskCommands.delete(context, child)
        TaskCommands.move(context, parent, secondGoal)
        TaskCommands.restore(context, deleted)

        assertEquals(parent, db.todos().byId(child)!!.parentId)
        assertEquals(secondGoal, db.todos().byId(child)!!.goalId)
    }

    @Test fun restoringAnIncompleteStepOfACompletedParentPromotesItWithoutRewardingIt() = runBlocking {
        val parent = task("Parent")
        val child = task("Child", parent, reward = 500)
        val deleted = TaskCommands.delete(context, child)
        TaskCommands.complete(context, parent)
        TaskCommands.restore(context, deleted)

        val restored = db.todos().byId(child)!!
        assertNull(restored.parentId)
        assertNull(restored.completedAt)
        assertNull(restored.completedBy)
        assertNull(restored.startedAt)
        assertNull(db.rewards().entry("task:$child"))
        assertNotNull(db.todos().byId(parent)!!.completedAt)
    }

    @Test fun restoringADeletedCascadeStepDoesNotReviveAReversedCompletion() = runBlocking {
        val parent = task("Parent")
        val child = task("Child", parent, reward = 200)
        val completion = TaskCommands.complete(context, parent)!!
        val deleted = TaskCommands.delete(context, child)
        TaskCommands.undoCompletion(context, parent, completion)
        TaskCommands.restore(context, deleted)

        val restored = db.todos().byId(child)!!
        assertEquals(parent, restored.parentId)
        assertNull(restored.completedAt)
        assertNull(restored.completedBy)
        assertEquals(200, db.rewards().entry("task:$child")!!.amountCents)
        assertNotNull(db.completions().byId(completion)!!.reversedAt)
    }

    @Test fun goalPatchPreservesConcurrentFieldsAndCanExplicitlyClearNullableValues() = runBlocking {
        val id = TaskCommands.saveGoal(context, Goal(name = "Original", color = 1, createdAt = 100,
            details = "Original details", targetDate = 21000))
        TaskCommands.updateGoal(context, id, GoalPatch(name = "Renamed elsewhere", color = 2))
        TaskCommands.archiveGoal(context, id)
        TaskCommands.updateGoal(context, id, GoalPatch(details = PatchField.Set("Edited details"), targetDate = PatchField.Set(null)))

        val current = db.goals().byId(id)!!
        assertEquals("Renamed elsewhere", current.name)
        assertEquals(2, current.color)
        assertEquals("Edited details", current.details)
        assertNull(current.targetDate)
        assertTrue(current.archived)
        TaskCommands.updateGoal(context, id, GoalPatch(details = PatchField.Set(null)))
        assertNull(db.goals().byId(id)!!.details)
    }

    @Test fun commandIdentityRejectsAChangedTaskOrGoalPayload() = runBlocking {
        val id = TaskCommands.create(context, TodoTask(title = "Original", createdAt = 0), "create-original")
        assertTrue(runCatching { TaskCommands.create(context, TodoTask(title = "Changed", createdAt = 0), "create-original") }.isFailure)
        assertEquals(listOf(id), db.todos().allOnce().map { it.id })
        TaskCommands.update(context, id, TaskPatch(title = "Saved"), commandId = "update-original")
        assertTrue(runCatching { TaskCommands.update(context, id, TaskPatch(title = "Changed"), commandId = "update-original") }.isFailure)
        assertEquals("Saved", db.todos().byId(id)!!.title)

        val goal = TaskCommands.saveGoal(context, Goal(name = "Goal", color = 1, createdAt = 0))
        TaskCommands.updateGoal(context, goal, GoalPatch(details = PatchField.Set("Saved")), commandId = "goal-update")
        assertTrue(runCatching { TaskCommands.updateGoal(context, goal, GoalPatch(details = PatchField.Set("Changed")), commandId = "goal-update") }.isFailure)
        assertEquals("Saved", db.goals().byId(goal)!!.details)
    }
}

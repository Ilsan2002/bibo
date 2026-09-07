package com.bibo.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import com.bibo.data.AppScope
import com.bibo.data.BiboDb
import com.bibo.data.TodoTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Delete a task and its subtasks immediately, then offer Undo as a restore.
 *
 * Shared by the Tasks list and the goal profile so both behave identically. Deleting
 * first (rather than hiding the row and deferring the write until an Undo snackbar times
 * out) is deliberate: the deferred version ran on the screen's own coroutine scope, so
 * leaving the screen cancelled it and the delete silently never happened.
 *
 * Undo re-inserts with the original ids — Room only auto-generates one when id is 0 — so
 * restored subtasks re-attach to their parent.
 */
fun deleteTaskWithUndo(db: BiboDb, task: TodoTask, snackbarHostState: SnackbarHostState) {
    AppScope.io.launch {
        val children = db.todos().allOnce().filter { it.parentId == task.id }
        db.todos().deleteChildren(task.id)
        db.todos().delete(task)

        // Bounded: with no SnackbarHost composed (screen already left) showSnackbar would
        // suspend forever and leak this coroutine.
        val restored = withTimeoutOrNull(8_000) {
            withContext(Dispatchers.Main) {
                snackbarHostState.showSnackbar(
                    message = "Deleted \"${task.title}\"",
                    actionLabel = "Undo",
                ) == SnackbarResult.ActionPerformed
            }
        } ?: false

        if (restored) {
            db.todos().insert(task)
            children.forEach { db.todos().insert(it) }
        }
    }
}

package com.bibo.ui

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import com.bibo.data.AppScope
import com.bibo.data.TaskCommands
import com.bibo.data.TodoTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Compatibility helper for surfaces without a TaskViewModel. Restore is relationship-aware. */
fun deleteTaskWithUndo(context: Context, task: TodoTask, snackbarHostState: SnackbarHostState) {
    val app = context.applicationContext
    AppScope.io.launch {
        try {
            val tree = TaskCommands.delete(app, task.id)
            val undo = withTimeoutOrNull(10_000) {
                withContext(Dispatchers.Main) { snackbarHostState.showSnackbar("Deleted “${task.title}”", "Undo") == SnackbarResult.ActionPerformed }
            } == true
            if (undo) TaskCommands.restore(app, tree)
        } catch (error: Exception) {
            withTimeoutOrNull(5_000) { withContext(Dispatchers.Main) { snackbarHostState.showSnackbar(error.message ?: "Deletion failed") } }
        }
    }
}

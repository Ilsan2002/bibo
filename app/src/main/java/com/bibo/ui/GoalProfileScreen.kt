package com.bibo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bibo.data.AppTime
import com.bibo.data.Goal
import com.bibo.data.TodoTask
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId

/** Reads the same task tree and invokes the same commands as the inbox. */
@Composable
fun GoalProfileScreen(goal: Goal, onBack: () -> Unit, onEdit: () -> Unit,
    onTaskEdit: (TodoTask) -> Unit, onComplete: (TodoTask, Boolean) -> Unit,
    onDelete: (TodoTask) -> Unit, onTimer: (TodoTask) -> Unit, modifier: Modifier = Modifier, pending: Set<Long> = emptySet()) {
    val vm: TaskViewModel = viewModel()
    val allTasks by vm.tasks.collectAsStateWithLifecycle()
    val tasks = allTasks.filter { it.goalId == goal.id }
    val parents = tasks.filter { it.parentId == null }
    val children = tasks.filter { it.parentId != null }.groupBy { it.parentId }
    val outcomesDone = parents.count { it.completedAt != null }
    val steps = tasks.filter { it.parentId != null }
    val next = nextGoalAction(tasks)
    var now by remember { mutableLongStateOf(AppTime.nowMillis()) }
    LaunchedEffect(Unit) { while (true) { now = AppTime.nowMillis(); delay(1_000) } }
    BackHandler(onBack = onBack)
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(8.dp)) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to tasks") }
                Text(goal.name, Modifier.weight(1f).padding(12.dp), style = MaterialTheme.typography.headlineSmall)
                IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Edit goal") }
            }
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (goal.archived) Text("Archived · tasks and history are preserved")
                goal.details?.let { Text(it) }
                Text("$outcomesDone/${parents.size} outcomes · ${steps.count { it.completedAt != null }}/${steps.size} steps")
                LinearProgressIndicator(progress = { if (parents.isEmpty()) 0f else outcomesDone.toFloat() / parents.size }, modifier = Modifier.fillMaxWidth())
                goal.targetDate?.let { day ->
                    val today = Instant.ofEpochMilli(now).atZone(AppTime.zone()).toLocalDate().toEpochDay()
                    Text("Target ${java.time.LocalDate.ofEpochDay(day)} · ${when { day > today -> "${day - today} days left"; day == today -> "today"; else -> "${today - day} days ago" }}")
                }
                Text(next?.let { "Next step: ${it.title}" } ?: if (parents.isEmpty()) "Add a first step" else "All outcomes completed", style = MaterialTheme.typography.titleMedium)
                if (!goal.archived) OutlinedButton(onClick = { vm.filter(goal.id); vm.newTask() }) { Icon(Icons.Default.Add, null); Text("Add task") }
            }
        }
        if (parents.isEmpty()) item { Text("No tasks in this goal", Modifier.padding(20.dp)) }
        items(parents.flatMap { listOf(it) + children[it.id].orEmpty() }, key = { it.id }) { task ->
            TaskRow(task, task.id in pending, goal.color, children[task.id].orEmpty(), expanded = true,
                onToggleComplete = { onComplete(task, it) }, onEdit = { onTaskEdit(task) }, onDelete = { onDelete(task) },
                onTimer = if (task.completedAt == null && (task.parentId == null || parents.firstOrNull { it.id == task.parentId }?.completedAt == null)) ({ onTimer(task) }) else null,
                onAddSubtask = if (task.parentId == null && task.completedAt == null && !goal.archived) ({ vm.newTask(task) }) else null)
        }
    }
}

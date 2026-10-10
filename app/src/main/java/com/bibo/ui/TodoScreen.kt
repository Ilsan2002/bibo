@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.bibo.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bibo.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun TodoScreen(initialTaskId: Long? = null, initialGoalId: Long? = null, onNavigationConsumed: () -> Unit = {}) {
    val context = LocalContext.current
    val vm: TaskViewModel = viewModel()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val deletedTasks by vm.deleted.collectAsStateWithLifecycle()
    val tasksLoaded by vm.tasksLoaded.collectAsStateWithLifecycle()
    val goalsLoaded by vm.goalsLoaded.collectAsStateWithLifecycle()
    val allGoals by vm.goals.collectAsStateWithLifecycle()
    val goals = allGoals.filter { !it.archived }
    val wallet by vm.wallet.collectAsStateWithLifecycle()
    val activeSession by TimerController.state.collectAsStateWithLifecycle()
    val filterId by vm.filterGoalId.collectAsStateWithLifecycle()
    val profileId by vm.profileGoalId.collectAsStateWithLifecycle()
    val archiveVisible by vm.showArchive.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val goalDraft by vm.goalDraft.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val commandError by vm.error.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var now by remember { mutableLongStateOf(AppTime.nowMillis()) }
    LaunchedEffect(Unit) { while (true) { now = AppTime.nowMillis(); delay(1_000) } }
    LaunchedEffect(vm) {
        vm.notices.collect { notice ->
            val result = snackbar.showSnackbar(notice.message, if (notice.undo != null) "Undo" else null,
                duration = if (notice.undo != null) SnackbarDuration.Long else SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) scope.launch {
                try { notice.undo?.invoke() } catch (error: Exception) { snackbar.showSnackbar(error.message ?: "Undo failed") }
            }
        }
    }
    var openedTaskId by rememberSaveable { mutableStateOf<Long?>(null) }
    var openedGoalId by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(initialTaskId, tasks, tasksLoaded) {
        if (initialTaskId == null) openedTaskId = null
        if (initialTaskId != null && openedTaskId != initialTaskId) {
            val selected = tasks.firstOrNull { it.id == initialTaskId }
            if (selected != null) { vm.edit(selected); openedTaskId = initialTaskId; onNavigationConsumed() }
            else if (tasksLoaded) { openedTaskId = initialTaskId; onNavigationConsumed(); snackbar.showSnackbar("This task is no longer available") }
        }
    }
    LaunchedEffect(initialGoalId, allGoals, goalsLoaded) {
        if (initialGoalId == null) openedGoalId = null
        if (initialGoalId != null && openedGoalId != initialGoalId && allGoals.any { it.id == initialGoalId }) {
            vm.profile(initialGoalId); openedGoalId = initialGoalId; onNavigationConsumed()
        } else if (initialGoalId != null && openedGoalId != initialGoalId && goalsLoaded) {
            openedGoalId = initialGoalId; onNavigationConsumed(); snackbar.showSnackbar("This goal is no longer available")
        }
    }
    LaunchedEffect(allGoals, goalsLoaded, filterId) {
        if (goalsLoaded && filterId != null && allGoals.none { it.id == filterId && !it.archived }) vm.filter(null)
    }
    var showTreats by rememberSaveable { mutableStateOf(false) }
    var showArchivedGoals by rememberSaveable { mutableStateOf(false) }
    var showTrash by rememberSaveable { mutableStateOf(false) }
    var spotlightEnabled by remember { mutableStateOf(GoalReminders.enabled(context)) }
    var spotlightTime by remember { mutableStateOf(false) }
    var confirmCompletion by remember { mutableStateOf<TodoTask?>(null) }
    val children = tasks.filter { it.parentId != null }.groupBy { it.parentId }
    val goalsById = allGoals.associateBy { it.id }
    val parentTasks = tasks.filter { it.parentId == null && (filterId == null || it.goalId == filterId) }
    val activeParents = parentTasks.filter { it.completedAt == null }
    val activeById = activeParents.associateBy { it.id }
    var orderedIds by remember { mutableStateOf<List<Long>>(emptyList()) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(activeParents.map { it.id }) {
        if (!dragging) orderedIds = activeParents.map { it.id }
    }
    val expanded = rememberSaveable { mutableStateOf<List<Long>>(emptyList()) }
    fun toggleExpanded(id: Long) { expanded.value = if (id in expanded.value) expanded.value - id else expanded.value + id }
    fun toggle(task: TodoTask, complete: Boolean) {
        if (complete && children[task.id].orEmpty().any { it.completedAt == null }) confirmCompletion = task
        else vm.complete(task, complete)
    }
    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        val fromId = from.key as? Long
        val toId = to.key as? Long
        val old = orderedIds.indexOf(fromId)
        val next = orderedIds.indexOf(toId)
        if (old >= 0 && next >= 0) orderedIds = orderedIds.toMutableList().apply { add(next, removeAt(old)) }
    }
    val profile = allGoals.firstOrNull { it.id == profileId }
    BackHandler(enabled = profile != null) { vm.profile(null) }
    val requestNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun askNotifications() {
        if (TaskReminders.deliveryEnabled(context)) return
        val globalEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        if (!globalEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val preferences = context.getSharedPreferences("reminder_permission", android.content.Context.MODE_PRIVATE)
            val activity = generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }.filterIsInstance<android.app.Activity>().firstOrNull()
            val mayAsk = !preferences.getBoolean("asked", false) || activity?.let { androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.POST_NOTIFICATIONS) } == true
            if (mayAsk) {
                preferences.edit().putBoolean("asked", true).apply()
                requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName))
    }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, floatingActionButton = {
        if (profile == null) FloatingActionButton(onClick = { vm.newTask() }) { Icon(Icons.Default.Add, "Add task") }
    }) { padding ->
        if (profile != null) {
            GoalProfileScreen(profile, onBack = { vm.profile(null) },
                onEdit = { vm.editGoal(profile) },
                onTaskEdit = vm::edit, onComplete = ::toggle, onDelete = vm::delete, pending = pending,
                onTimer = { task -> askNotifications(); vm.timer(task, activeSession?.takeIf { it.taskId == task.id }?.id) },
                modifier = Modifier.padding(padding))
        } else LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 96.dp)) {
            item(key = "header") {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Tasks", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    AssistChip(onClick = { showTreats = true }, label = { Text("Treats ${Rewards.format(wallet.available)}") })
                }
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.editGoal(null) }) { Text("New goal") }
                    TextButton(onClick = { showArchivedGoals = !showArchivedGoals }) { Text(if (showArchivedGoals) "Active goals" else "Archived goals") }
                }
                TextButton(onClick = { showTrash = !showTrash }, modifier = Modifier.padding(horizontal = 16.dp)) { Text("${if (showTrash) "Hide" else "Show"} deleted tasks (${deletedTasks.size})") }
                val cards = if (showArchivedGoals) allGoals.filter { it.archived } else goals
                if (cards.isNotEmpty()) LazyRow(contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(cards, key = { it.id }) { goal ->
                        GoalCard(goal, tasks.filter { it.goalId == goal.id }, onOpen = { vm.profile(goal.id) },
                            onRestore = if (goal.archived) ({ vm.archiveGoal(goal, false) }) else null)
                    }
                }
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(selected = filterId == null, onClick = { vm.filter(null) }, label = { Text("All tasks") }) }
                    items(goals, key = { it.id }) { goal -> FilterChip(selected = filterId == goal.id,
                        onClick = { vm.filter(if (filterId == goal.id) null else goal.id) }, label = { Text(goal.name) }) }
                }
                if (filterId != null) Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Showing ${goalsById[filterId]?.name ?: "unavailable goal"}", modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.filter(null) }) { Text("Clear filter") }
                }
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Daily goal spotlight", Modifier.weight(1f))
                    Switch(checked = spotlightEnabled, onCheckedChange = { spotlightEnabled = it; GoalReminders.configure(context, it); if (it) askNotifications() })
                }
                if (spotlightEnabled) TextButton(onClick = { spotlightTime = true }, modifier = Modifier.padding(horizontal = 16.dp)) { Text("Spotlight time %02d:%02d".format(java.util.Locale.ROOT, GoalReminders.hour(context), GoalReminders.minute(context))) }
                if (!TaskReminders.deliveryEnabled(context)) {
                    TextButton(onClick = ::askNotifications, modifier = Modifier.padding(horizontal = 16.dp)) { Text("Notifications off · Enable reminders") }
                }
            }
            if (activeParents.isEmpty()) item(key = "empty") {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (parentTasks.isEmpty()) "No tasks here yet" else "No open tasks", style = MaterialTheme.typography.titleMedium)
                    Text("Add a task or choose a goal. Completed steps remain available below.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val activeRows = orderedIds.mapNotNull(activeById::get).flatMap { parent ->
                listOf(parent) + if (parent.id in expanded.value) children[parent.id].orEmpty() else emptyList()
            }
            items(activeRows, key = { it.id }) { task ->
                val sub = children[task.id].orEmpty()
                val row: @Composable () -> Unit = {
                    TaskRow(task, task.id in pending, goalsById[task.goalId]?.color, sub,
                        expanded = task.id in expanded.value, onExpand = { toggleExpanded(task.id) },
                        onToggleComplete = { toggle(task, it) }, onEdit = { vm.edit(task) }, onDelete = { vm.delete(task) },
                        onAddSubtask = if (task.parentId == null && task.completedAt == null) ({ vm.newTask(task) }) else null,
                        onTimer = {
                            askNotifications(); vm.timer(task, activeSession?.takeIf { it.taskId == task.id }?.id)
                        })
                }
                if (task.parentId == null) ReorderableItem(reorder, key = task.id) { _ ->
                    SwipeableTaskRow(task, if (task.id !in pending) ({ toggle(task, true) }) else null,
                        { vm.delete(task) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { row() }
                            Icon(Icons.Default.DragIndicator, "Reorder task", modifier = Modifier.size(40.dp)
                                .longPressDraggableHandle(onDragStarted = { dragging = true }, onDragStopped = {
                                    val live = activeParents.map { it.id }
                                    orderedIds = orderedIds.filter { it in live } + live.filter { it !in orderedIds }
                                    vm.reorder(orderedIds); dragging = false
                                }).padding(8.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else row()
            }
            if (showTrash) {
                item(key = "trash-title") {
                    Column(Modifier.padding(16.dp)) {
                        Text("Deleted tasks", style = MaterialTheme.typography.titleMedium)
                        Text("Restore keeps previous completion and reward history. It does not restart a timer.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (deletedTasks.isEmpty()) item(key = "trash-empty") { Text("No deleted tasks", Modifier.padding(16.dp)) }
                val deletedIds = deletedTasks.map { it.id }.toSet()
                val deletedRoots = deletedTasks.filter { task -> task.parentId !in deletedIds || task.deletedBy == null || deletedTasks.firstOrNull { it.id == task.parentId }?.deletedBy != task.deletedBy }
                items(deletedRoots, key = { "deleted-${it.id}" }) { task ->
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(task.title); Text("Deleted ${task.deletedAt?.let { Instant.ofEpochMilli(it).atZone(AppTime.zone()).toLocalDate() }}", style = MaterialTheme.typography.bodySmall) }
                        OutlinedButton(enabled = task.id !in pending, onClick = { vm.restoreDeleted(task) }) { Text("Restore") }
                    }
                }
            }
            val today = Instant.ofEpochMilli(now).atZone(AppTime.zone()).toLocalDate().atStartOfDay(AppTime.zone()).toInstant().toEpochMilli()
            val doneToday = parentTasks.filter { (it.completedAt ?: 0) >= today }
            if (doneToday.isNotEmpty()) item(key = "done-title") { Text("Done today", Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium) }
            items(doneToday.flatMap { listOf(it) + if (it.id in expanded.value) children[it.id].orEmpty() else emptyList() }, key = { it.id }) { task ->
                TaskRow(task, task.id in pending, goalsById[task.goalId]?.color, children[task.id].orEmpty(),
                    expanded = task.id in expanded.value, onExpand = { toggleExpanded(task.id) }, onToggleComplete = { toggle(task, it) },
                    onEdit = { vm.edit(task) }, onDelete = { vm.delete(task) })
            }
            val older = parentTasks.filter { it.completedAt != null && it.completedAt < today }
            if (older.isNotEmpty()) item(key = "archive") {
                TextButton(onClick = { vm.archiveVisible(!archiveVisible) }) { Text("${if (archiveVisible) "Hide" else "Show"} completed (${older.size})") }
            }
            if (archiveVisible) items(older.flatMap { listOf(it) + if (it.id in expanded.value) children[it.id].orEmpty() else emptyList() }, key = { it.id }) { task ->
                TaskRow(task, task.id in pending, goalsById[task.goalId]?.color, children[task.id].orEmpty(),
                    expanded = task.id in expanded.value, onExpand = { toggleExpanded(task.id) }, onToggleComplete = { toggle(task, it) },
                    onEdit = { vm.edit(task) }, onDelete = { vm.delete(task) })
            }
        }
    }
    confirmCompletion?.let { task -> AlertDialog(onDismissRequest = { confirmCompletion = null },
        title = { Text("Complete remaining steps?") }, text = { Text("Completing “${task.title}” also completes its remaining steps. Undo reverses this completion's changes.") },
        confirmButton = { TextButton(onClick = { confirmCompletion = null; vm.complete(task, true) }) { Text("Complete all") } },
        dismissButton = { TextButton(onClick = { confirmCompletion = null }) { Text("Cancel") } }) }
    draft?.let { current -> TaskDetailSheet(current, tasks, allGoals, pending = (current.id ?: -1) in pending, error = commandError?.takeIf { it.first == (current.id ?: -1L) }?.second,
        onChange = vm::changeDraft, onDismiss = { vm.editDraft(null) }, onSave = vm::saveDraft, onNotifications = ::askNotifications) }
    goalDraft?.let { value -> GoalEditorSheet(value, pending = -3L in pending, error = commandError?.takeIf { it.first == -3L }?.second,
        onChange = vm::changeGoalDraft, onDismiss = { vm.changeGoalDraft(null) }, onSave = vm::saveGoalDraft,
        onArchive = { vm.archiveGoal(value.toGoal(), !value.archived) }) }
    if (showTreats) TreatsSheet { showTreats = false }
    if (spotlightTime) {
        val time = rememberTimePickerState(initialHour = GoalReminders.hour(context), initialMinute = GoalReminders.minute(context), is24Hour = true)
        AlertDialog(onDismissRequest = { spotlightTime = false }, title = { Text("Daily spotlight time") }, text = { TimeInput(time) },
            confirmButton = { TextButton(onClick = { GoalReminders.configure(context, spotlightEnabled, time.hour, time.minute); spotlightTime = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { spotlightTime = false }) { Text("Cancel") } })
    }
}

@Composable
internal fun GoalCard(goal: Goal, tasks: List<TodoTask>, onOpen: () -> Unit, onRestore: (() -> Unit)? = null) {
    val outcomes = tasks.filter { it.parentId == null }
    val steps = tasks.filter { it.parentId != null }
    val next = nextGoalAction(tasks)
    Card(onClick = onOpen, modifier = Modifier.width(220.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(Color(goal.color)))
                Text(goal.name, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleSmall)
            }
            Text("${outcomes.count { it.completedAt != null }}/${outcomes.size} outcomes · ${steps.count { it.completedAt != null }}/${steps.size} steps", style = MaterialTheme.typography.bodySmall)
            Text(next?.let { "Next: ${it.title}" } ?: if (tasks.isEmpty()) "Add your first step" else "All outcomes completed", style = MaterialTheme.typography.bodySmall)
            if (onRestore != null) TextButton(onClick = onRestore) { Text("Restore goal") }
        }
    }
}

internal fun nextGoalAction(tasks: List<TodoTask>): TodoTask? {
    val parent = tasks.filter { it.parentId == null && it.completedAt == null }.minWithOrNull(compareBy<TodoTask> { it.sortOrder }.thenBy { it.id }) ?: return null
    return tasks.filter { it.parentId == parent.id && it.completedAt == null }.minWithOrNull(compareBy<TodoTask> { it.sortOrder }.thenBy { it.id }) ?: parent
}

@Composable
internal fun TaskRow(task: TodoTask, pending: Boolean = false, goalColor: Int? = null,
    children: List<TodoTask> = emptyList(), expanded: Boolean = false, onExpand: () -> Unit = {},
    onToggleComplete: (Boolean) -> Unit, onEdit: () -> Unit, onDelete: () -> Unit,
    onAddSubtask: (() -> Unit)? = null, onTimer: (() -> Unit)? = null) {
    val complete = task.completedAt != null
    val activeSession by TimerController.state.collectAsStateWithLifecycle()
    val linkedSession = activeSession?.takeIf { it.taskId == task.id }
    val running = linkedSession != null
    // Read a ticking state in this row itself. Passing an unused parent clock lets
    // Compose skip the row, leaving its elapsed text frozen until another action.
    val elapsedMillis by produceState(linkedSession?.let(TimerController::elapsedMillis) ?: 0L, linkedSession) {
        val session = linkedSession ?: run { value = 0L; return@produceState }
        while (true) { value = TimerController.elapsedMillis(session); delay(1_000) }
    }
    var menu by remember(task.id) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = if (task.parentId == null) 8.dp else 30.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (children.isNotEmpty()) IconButton(onClick = onExpand, modifier = Modifier.size(40.dp)) {
            Icon(if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight, if (expanded) "Collapse steps" else "Expand steps")
        }
        Checkbox(checked = complete, enabled = !pending, onCheckedChange = onToggleComplete,
            modifier = Modifier.semantics { contentDescription = "Complete ${task.title}" })
        Column(Modifier.weight(1f).clickable(enabled = !pending, onClick = onEdit).padding(4.dp)) {
            Text(task.title, textDecoration = if (complete) TextDecoration.LineThrough else null,
                color = if (complete) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            if (children.isNotEmpty()) Text("${children.count { it.completedAt != null }}/${children.size} steps", style = MaterialTheme.typography.bodySmall)
            task.dueEpochDay?.let { Text("Due ${LocalDate.ofEpochDay(it)}", style = MaterialTheme.typography.bodySmall) }
            task.reminderAt?.let { if (!complete) Text("Reminder ${Instant.ofEpochMilli(it).atZone(AppTime.zone()).format(DateTimeFormatter.ofPattern("MMM d HH:mm"))}", style = MaterialTheme.typography.bodySmall) }
            if (task.rewardCents > 0) Text("Reward ${Rewards.format(task.rewardCents)} · once", style = MaterialTheme.typography.bodySmall)
            if (running) Text("Timing ${formatDuration(elapsedMillis)}", style = MaterialTheme.typography.bodySmall)
        }
        if (goalColor != null) Box(Modifier.size(8.dp).clip(CircleShape).background(Color(goalColor)))
        if (!complete && onTimer != null) IconButton(onClick = onTimer, enabled = !pending) { Icon(if (running) Icons.Default.Stop else Icons.Default.PlayArrow, if (running) "Stop timer" else "Start timer") }
        Box {
            IconButton(onClick = { menu = true }, enabled = !pending) { Icon(Icons.Default.MoreVert, "Task actions") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Details") }, onClick = { menu = false; onEdit() })
                if (onAddSubtask != null) DropdownMenuItem(text = { Text("Add step") }, onClick = { menu = false; onAddSubtask() })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

@Composable
internal fun SwipeableTaskRow(task: TodoTask, onComplete: (() -> Unit)?, onDelete: () -> Unit, content: @Composable () -> Unit) {
    val complete by rememberUpdatedState(onComplete)
    val delete by rememberUpdatedState(onDelete)
    key(task.id) {
        val state = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> complete?.invoke()
                SwipeToDismissBoxValue.EndToStart -> delete()
                else -> Unit
            }
            false
        })
        SwipeToDismissBox(state = state, enableDismissFromStartToEnd = onComplete != null, backgroundContent = {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant).padding(20.dp),
                contentAlignment = if (state.dismissDirection == SwipeToDismissBoxValue.StartToEnd) Alignment.CenterStart else Alignment.CenterEnd) {
                Text(if (state.dismissDirection == SwipeToDismissBoxValue.StartToEnd) "Complete" else "Delete")
            }
        }, content = { content() })
    }
}

@Composable
private fun TaskDetailSheet(draft: TaskDraft, tasks: List<TodoTask>, goals: List<Goal>, pending: Boolean, error: String?,
    onChange: (TaskDraft?) -> Unit, onDismiss: () -> Unit, onSave: () -> Unit, onNotifications: () -> Unit) {
    val context = LocalContext.current
    var pickDate by rememberSaveable { mutableStateOf<String?>(null) }
    var reminderDay by rememberSaveable { mutableStateOf<Long?>(null) }
    var pickTime by rememberSaveable { mutableStateOf(false) }
    var reminderTimeError by remember { mutableStateOf<String?>(null) }
    var rewardInput by rememberSaveable(draft.id, draft.parentId) { mutableStateOf(java.math.BigDecimal.valueOf(draft.rewardCents.toLong(), 2).toPlainString()) }
    val parsedReward = if (rewardInput.trim() in setOf("0", "0.0", "0.00")) 0 else RewardPolicy.parseCents(rewardInput)
    val validReminder = draft.reminderAt == null || draft.reminderAt > AppTime.nowMillis() || (draft.id != null && "reminder" !in draft.changed)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (draft.id == null) "New ${if (draft.parentId == null) "task" else "step"}" else "Task details", style = MaterialTheme.typography.headlineSmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            draft.parentId?.let { parent ->
                val parentTask = tasks.firstOrNull { it.id == parent }
                Text("Step of ${parentTask?.title ?: "missing parent"}")
                if (parentTask?.completedAt != null) Text("Reopen the parent before starting a remaining step timer", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(value = draft.title, onValueChange = { onChange(draft.copy(title = it)) }, label = { Text("What needs doing?") }, modifier = Modifier.fillMaxWidth())
            if (draft.parentId == null) {
                Text("Goal", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = draft.goalId == null, onClick = { onChange(draft.copy(goalId = null)) }, label = { Text("Inbox") })
                    goals.filter { !it.archived || it.id == draft.goalId }.forEach { goal -> FilterChip(selected = draft.goalId == goal.id,
                        onClick = { onChange(draft.copy(goalId = goal.id)) }, label = { Text(goal.name) }) }
                }
            } else Text("This step inherits its parent's goal", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { pickDate = "due" }) { Text(draft.dueEpochDay?.let { "Due ${LocalDate.ofEpochDay(it)}" } ?: "Set due date") }
                if (draft.dueEpochDay != null) TextButton(onClick = { onChange(draft.copy(dueEpochDay = null)) }) { Text("Clear") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { pickDate = "reminder" }) { Text(draft.reminderAt?.let {
                    Instant.ofEpochMilli(it).atZone(AppTime.zone()).format(DateTimeFormatter.ofPattern("MMM d HH:mm")) } ?: "Set reminder") }
                if (draft.reminderAt != null) TextButton(onClick = { onChange(draft.copy(reminderAt = null, reminderNote = "")) }) { Text("Cancel reminder") }
            }
            if (draft.reminderAt != null) {
                OutlinedTextField(value = draft.reminderNote, onValueChange = { onChange(draft.copy(reminderNote = it)) }, label = { Text("Reminder note (optional)") }, modifier = Modifier.fillMaxWidth())
                if (!validReminder) Text("Choose a future reminder time", color = MaterialTheme.colorScheme.error)
                if (!TaskReminders.deliveryEnabled(context)) TextButton(onClick = onNotifications) { Text("Enable notifications to receive this reminder") }
            }
            Text("Reward in USD · earned once per task, even after reopening", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value = rewardInput, onValueChange = { rewardInput = it; val cents = if (it.trim() in setOf("0", "0.0", "0.00")) 0 else RewardPolicy.parseCents(it); if (cents != null) onChange(draft.copy(rewardCents = cents)) },
                label = { Text("Reward amount (USD)") }, modifier = Modifier.fillMaxWidth(), isError = parsedReward == null || (parsedReward ?: 0) > 1_000_000,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Rewards.REWARD_TIERS.forEach { cents -> FilterChip(selected = draft.rewardCents == cents,
                    onClick = { rewardInput = java.math.BigDecimal.valueOf(cents.toLong(), 2).toPlainString(); onChange(draft.copy(rewardCents = cents)) }, label = { Text(if (cents == 0) "None" else Rewards.format(cents)) }) }
            }
            Button(onClick = onSave, enabled = draft.title.isNotBlank() && validReminder && parsedReward != null && parsedReward <= 1_000_000 && !pending, modifier = Modifier.fillMaxWidth()) { Text(if (pending) "Saving…" else "Save task") }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }
    pickDate?.let { purpose ->
        val initial = if (purpose == "due") draft.dueEpochDay else draft.reminderAt?.let { Instant.ofEpochMilli(it).atZone(AppTime.zone()).toLocalDate().toEpochDay() }
        val state = rememberDatePickerState(initialSelectedDateMillis = (initial ?: AppTime.today().toEpochDay()) * 86_400_000L)
        DatePickerDialog(onDismissRequest = { pickDate = null }, confirmButton = { TextButton(onClick = {
            state.selectedDateMillis?.let { millis ->
                val day = millis / 86_400_000L
                if (purpose == "due") onChange(draft.copy(dueEpochDay = day))
                else { reminderDay = day; pickTime = true }
            }; pickDate = null
        }) { Text("Next") } }, dismissButton = { TextButton(onClick = { pickDate = null }) { Text("Cancel") } }) { DatePicker(state) }
    }
    if (pickTime) {
        val initialTime = draft.reminderAt?.let { Instant.ofEpochMilli(it).atZone(AppTime.zone()).toLocalTime() } ?: Instant.ofEpochMilli(AppTime.nowMillis()).atZone(AppTime.zone()).toLocalTime().plusHours(1)
        val time = rememberTimePickerState(initialHour = initialTime.hour, initialMinute = initialTime.minute, is24Hour = true)
        AlertDialog(onDismissRequest = { pickTime = false }, title = { Text("Reminder time") }, text = { Column { TimeInput(time); reminderTimeError?.let { Text(it, color = MaterialTheme.colorScheme.error) } } },
            confirmButton = { TextButton(onClick = {
                val day = LocalDate.ofEpochDay(reminderDay ?: AppTime.today().toEpochDay())
                val local = LocalDateTime.of(day, LocalTime.of(time.hour, time.minute))
                if (AppTime.zone().rules.getValidOffsets(local).isEmpty()) { reminderTimeError = "This local time does not exist because the clock changes. Choose another time."; return@TextButton }
                val at = local.atZone(AppTime.zone()).toInstant().toEpochMilli()
                reminderTimeError = null
                onChange(draft.copy(reminderAt = at)); pickTime = false; onNotifications()
            }) { Text("Set reminder") } }, dismissButton = { TextButton(onClick = { pickTime = false }) { Text("Cancel") } })
    }
}

private val GOAL_COLORS = listOf(0xFF2962FF, 0xFF00875A, 0xFFC45B00, 0xFFB5215B, 0xFF7443B4).map { it.toInt() }

@Composable
private fun GoalEditorSheet(draft: GoalDraft, pending: Boolean, error: String?, onChange: (GoalDraft?) -> Unit, onDismiss: () -> Unit, onSave: () -> Unit, onArchive: () -> Unit) {
    var pickDate by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (draft.id == null) "New goal" else "Edit goal", style = MaterialTheme.typography.headlineSmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedTextField(draft.name, { onChange(draft.copy(name = it)) }, label = { Text("Goal name") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(draft.details, { onChange(draft.copy(details = it)) }, label = { Text("Why it matters") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { GOAL_COLORS.forEachIndexed { index, candidate ->
                FilterChip(selected = draft.color == candidate, onClick = { onChange(draft.copy(color = candidate)) }, label = { Text("Color ${index + 1}") },
                    leadingIcon = { Box(Modifier.size(14.dp).clip(CircleShape).background(Color(candidate))) })
            } }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { pickDate = true }) { Text(draft.targetDate?.let { "Target ${LocalDate.ofEpochDay(it)}" } ?: "Set target date") }
                if (draft.targetDate != null) TextButton(onClick = { onChange(draft.copy(targetDate = null)) }) { Text("Clear") }
            }
            Button(enabled = draft.name.isNotBlank() && !pending, onClick = onSave, modifier = Modifier.fillMaxWidth()) { Text(if (pending) "Saving…" else "Save goal") }
            if (draft.id != null) OutlinedButton(enabled = !pending, onClick = onArchive, modifier = Modifier.fillMaxWidth()) { Text(if (draft.archived) "Restore goal" else "Archive goal") }
            Text("Archiving preserves tasks and history. Restore it from Archived goals.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    }
    if (pickDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = (draft.targetDate ?: AppTime.today().toEpochDay()) * 86_400_000L)
        DatePickerDialog(onDismissRequest = { pickDate = false }, confirmButton = { TextButton(onClick = { state.selectedDateMillis?.let { onChange(draft.copy(targetDate = it / 86_400_000L)) }; pickDate = false }) { Text("Set date") } }) { DatePicker(state) }
    }
}

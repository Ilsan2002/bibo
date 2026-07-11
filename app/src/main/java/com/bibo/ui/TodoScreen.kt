@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package com.bibo.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SubdirectoryArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.bibo.data.ActivityBlock
import com.bibo.data.BiboDb
import com.bibo.data.Goal
import com.bibo.data.Rewards
import com.bibo.data.TimerController
import com.bibo.data.TodoTask
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun TodoScreen() {
    val context = LocalContext.current
    val db = remember { BiboDb.get(context) }
    val scope = rememberCoroutineScope()
    val zone = remember { ZoneId.systemDefault() }
    val haptics = rememberHaptics()
    val snackbarHostState = remember { SnackbarHostState() }

    val tasks by db.todos().all().collectAsState(initial = emptyList())
    val goals by db.goals().all().collectAsState(initial = emptyList())
    val goalsById = goals.associateBy { it.id }

    var addSheetParent by remember { mutableStateOf<TodoTask?>(null) }
    var showAddSheet by remember { mutableStateOf(false) }
    var editTask by remember { mutableStateOf<TodoTask?>(null) }
    var archiveExpanded by remember { mutableStateOf(false) }
    var filterGoalId by remember { mutableStateOf<Long?>(null) }
    var showGoalEditor by remember { mutableStateOf(false) }
    var editGoal by remember { mutableStateOf<Goal?>(null) }
    var profileGoal by remember { mutableStateOf<Goal?>(null) }
    var showTreats by remember { mutableStateOf(false) }
    // Treat money earned this week — recomputed whenever tasks change (e.g. one completed).
    var earnedThisWeek by remember { mutableIntStateOf(0) }
    LaunchedEffect(tasks) {
        earnedThisWeek = withContext(Dispatchers.IO) { Rewards.earnedCents(context) }
    }
    // ids whose delete is pending an undo window — filtered out of the UI meanwhile
    val pendingDelete = remember { mutableStateOf(setOf<Long>()) }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val anyRunning = tasks.any { it.startedAt != null }
    LaunchedEffect(anyRunning) {
        while (anyRunning) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    val startOfToday = LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli()

    // Parents whose subtasks are expanded (collapsed by default so big tasks stay tidy).
    val expandedParents = remember { mutableStateSetOf<Long>() }

    val visible = tasks.filter { it.id !in pendingDelete.value }
    val children = visible.filter { it.parentId != null }.groupBy { it.parentId }
    val allParents = visible.filter { it.parentId == null }
    // When a goal folder is open, show only that goal's tasks.
    val parents = if (filterGoalId == null) allParents else allParents.filter { it.goalId == filterGoalId }
    val archivedDone = parents.filter { it.completedAt != null && it.completedAt < startOfToday }
    val doneToday = parents.filter { (it.completedAt ?: 0) >= startOfToday }

    // Local, drag-mutable copy of the active top-level tasks (reorder source of truth
    // while dragging; re-derived whenever the DB emits).
    val activeParents = parents.filter { it.completedAt == null }
    val ordered: SnapshotStateList<TodoTask> =
        remember(activeParents.map { it.id }) { activeParents.toMutableStateList() }

    val lazyListState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val fromId = from.key as? Long ?: return@rememberReorderableLazyListState
        val toId = to.key as? Long ?: return@rememberReorderableLazyListState
        val fromIdx = ordered.indexOfFirst { it.id == fromId }
        val toIdx = ordered.indexOfFirst { it.id == toId }
        if (fromIdx != -1 && toIdx != -1) {
            ordered.add(toIdx, ordered.removeAt(fromIdx))
            haptics.tick()
        }
    }

    fun persistOrder() {
        val snapshot = ordered.toList()
        scope.launch(Dispatchers.IO) {
            db.todos().updateAll(snapshot.mapIndexed { i, t -> t.copy(sortOrder = i.toLong()) })
        }
    }

    fun setCompleted(task: TodoTask, complete: Boolean) {
        scope.launch {
            withContext(Dispatchers.IO) {
                if (complete) {
                    val end = System.currentTimeMillis()
                    val timedStart = task.startedAt
                    // If this task is the running shared timer, tear it down (Focus page +
                    // notification stop) — we write the block ourselves just below.
                    if (timedStart != null && TimerController.linkedTaskId(context) == task.id) {
                        TimerController.clear(context)
                    }
                    // Every completed task lands on the calendar: its real timed interval
                    // when the timer ran long enough, otherwise a short marker ending now.
                    val start =
                        if (timedStart != null && end - timedStart >= 60_000L) timedStart
                        else end - 15 * 60_000L
                    db.activityBlocks().insert(
                        ActivityBlock(
                            title = task.title,
                            startMillis = start,
                            endMillis = end,
                            source = "TODO",
                            goalId = task.goalId,
                        )
                    )
                    db.todos().update(task.copy(completedAt = end, startedAt = null))
                    children[task.id]?.filter { it.completedAt == null }?.forEach {
                        db.todos().update(it.copy(completedAt = end, startedAt = null))
                    }
                } else {
                    db.todos().update(task.copy(completedAt = null))
                }
            }
        }
    }

    fun requestDelete(task: TodoTask) {
        pendingDelete.value = pendingDelete.value + task.id
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = "Deleted \"${task.title}\"",
                actionLabel = "Undo",
            )
            if (result == SnackbarResult.ActionPerformed) {
                pendingDelete.value = pendingDelete.value - task.id
            } else {
                withContext(Dispatchers.IO) {
                    db.todos().deleteChildren(task.id)
                    db.todos().delete(task)
                }
                pendingDelete.value = pendingDelete.value - task.id
            }
        }
    }

    fun toggleTimer(task: TodoTask) {
        // Route through the shared TimerController so a task's timer is the same session the
        // Focus page shows (and the notification / widget). Start links this task; pause/stop
        // writes the TODO block and clears its running state — from either screen.
        if (task.startedAt == null) {
            haptics.toggleOn()
            TimerController.startTask(context, task.id, task.title, task.goalId)
        } else {
            haptics.toggleOff()
            TimerController.stopTimer(context)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = { addSheetParent = null; showAddSheet = true }) {
                Icon(Icons.Filled.Add, "Add task")
            }
        },
    ) { padding ->
        if (allParents.isEmpty() && goals.isEmpty()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Filled.Checklist,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "No tasks yet",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Add one with the + button",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        } else {
            LazyColumn(
                state = lazyListState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(bottom = 96.dp),
            ) {
                item(key = "goals") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Tasks",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        AssistChip(
                            onClick = { showTreats = true },
                            label = { Text("💰 ${Rewards.format(earnedThisWeek)}") },
                        )
                    }
                    GoalSummaryRow(
                        goals = goals,
                        tasks = allParents,
                        selected = filterGoalId,
                        onSelect = { filterGoalId = if (filterGoalId == it) null else it },
                        onNewGoal = { editGoal = null; showGoalEditor = true },
                        onOpenProfile = { profileGoal = it },
                    )
                }

                if (activeParents.isEmpty()) {
                    item(key = "goal-empty") {
                        Text(
                            if (filterGoalId != null) "No open tasks in this goal." else "No open tasks.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }

                items(ordered, key = { it.id }) { task ->
                    val subs = children[task.id].orEmpty()
                    val hasSubs = subs.isNotEmpty()
                    val isExpanded = task.id in expandedParents
                    ReorderableItem(reorderState, key = task.id) { isDragging ->
                        SwipeableTaskRow(
                            task = task,
                            onComplete = { haptics.confirm(); setCompleted(task, true) },
                            onDelete = { haptics.reject(); requestDelete(task) },
                        ) {
                            TaskRow(
                                task = task,
                                isChild = false,
                                dragging = isDragging,
                                now = now,
                                goalColor = task.goalId?.let { goalsById[it]?.color },
                                subtaskProgress = if (hasSubs) {
                                    subs.count { it.completedAt != null } to subs.size
                                } else null,
                                onToggleComplete = { done ->
                                    if (done) haptics.confirm()
                                    setCompleted(task, done)
                                },
                                onToggleTimer = { toggleTimer(task) },
                                onAddSubtask = { addSheetParent = task; showAddSheet = true },
                                onEdit = { editTask = task },
                                onDelete = { haptics.reject(); requestDelete(task) },
                                dragHandle = {
                                    Icon(
                                        Icons.Filled.DragIndicator,
                                        contentDescription = "Reorder",
                                        tint = MaterialTheme.colorScheme.outline,
                                        modifier = Modifier.longPressDraggableHandle(
                                            onDragStarted = { haptics.longPress() },
                                            onDragStopped = { persistOrder() },
                                        ),
                                    )
                                },
                                expanded = isExpanded,
                                onToggleExpand = if (hasSubs) {
                                    {
                                        haptics.tick()
                                        if (isExpanded) expandedParents.remove(task.id)
                                        else expandedParents.add(task.id)
                                    }
                                } else null,
                            )
                        }
                    }
                    if (isExpanded) children[task.id]?.filter { it.completedAt == null }?.forEach { sub ->
                        // subtasks are not reorderable; still swipeable
                        SwipeableTaskRow(
                            task = sub,
                            onComplete = { haptics.confirm(); setCompleted(sub, true) },
                            onDelete = { haptics.reject(); requestDelete(sub) },
                        ) {
                            TaskRow(
                                task = sub,
                                isChild = true,
                                dragging = false,
                                now = now,
                                subtaskProgress = null,
                                onToggleComplete = { done ->
                                    if (done) haptics.confirm()
                                    setCompleted(sub, done)
                                },
                                onToggleTimer = { toggleTimer(sub) },
                                onAddSubtask = null,
                                onEdit = { editTask = sub },
                                onDelete = { haptics.reject(); requestDelete(sub) },
                                dragHandle = null,
                            )
                        }
                    }
                }

                if (doneToday.isNotEmpty()) {
                    item(key = "done-header") {
                        Text(
                            "Done today",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
                        )
                    }
                    items(doneToday, key = { it.id }) { task ->
                        SwipeableTaskRow(
                            task = task,
                            onComplete = null,
                            onDelete = { haptics.reject(); requestDelete(task) },
                        ) {
                            TaskRow(
                                task = task,
                                isChild = false,
                                dragging = false,
                                now = now,
                                subtaskProgress = null,
                                onToggleComplete = { setCompleted(task, false) },
                                onToggleTimer = null,
                                onAddSubtask = null,
                                onEdit = { editTask = task },
                                onDelete = { haptics.reject(); requestDelete(task) },
                                dragHandle = null,
                            )
                        }
                    }
                }

                if (archivedDone.isNotEmpty()) {
                    item(key = "archive-header") {
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = { archiveExpanded = !archiveExpanded }) {
                                Text("Completed (${archivedDone.size})")
                                Spacer(Modifier.width(4.dp))
                                Icon(
                                    if (archiveExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                    contentDescription = null,
                                )
                            }
                        }
                    }
                    if (archiveExpanded) {
                        items(archivedDone, key = { it.id }) { task ->
                            TaskRow(
                                task = task,
                                isChild = false,
                                dragging = false,
                                now = now,
                                subtaskProgress = null,
                                onToggleComplete = { setCompleted(task, false) },
                                onToggleTimer = null,
                                onAddSubtask = null,
                                onEdit = { editTask = task },
                                onDelete = { haptics.reject(); requestDelete(task) },
                                dragHandle = null,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddSheet) {
        val parent = addSheetParent
        AddTaskSheet(
            parent = parent,
            goals = goals,
            // subtasks inherit their parent's goal; new top-level tasks default to the open folder
            initialGoalId = parent?.goalId ?: filterGoalId,
            showGoalPicker = parent == null,
            onDismiss = { showAddSheet = false },
            onAdd = { title, goalId ->
                showAddSheet = false
                haptics.confirm()
                val parentId = parent?.id
                val resolvedGoal = parent?.goalId ?: goalId
                scope.launch(Dispatchers.IO) {
                    val now2 = System.currentTimeMillis()
                    db.todos().insert(
                        TodoTask(
                            title = title,
                            parentId = parentId,
                            createdAt = now2,
                            sortOrder = now2, // new tasks sort to the bottom
                            goalId = resolvedGoal,
                        )
                    )
                }
            },
        )
    }

    // Full-screen goal profile overlay (tap a goal card). Re-derive the live goal so
    // edits reflect immediately; close if the goal was deleted.
    profileGoal?.let { pg ->
        val live = goals.find { it.id == pg.id }
        if (live == null) {
            LaunchedEffect(pg.id) { profileGoal = null }
        } else {
            GoalProfileScreen(
                goal = live,
                onBack = { profileGoal = null },
                onEdit = { editGoal = live; showGoalEditor = true },
            )
        }
    }

    if (showTreats) {
        TreatsSheet(onDismiss = { showTreats = false })
    }

    if (showGoalEditor) {
        GoalEditorSheet(
            goal = editGoal,
            onDismiss = { showGoalEditor = false },
            onSave = { name, color, targetDay, details ->
                showGoalEditor = false
                haptics.confirm()
                val existing = editGoal
                scope.launch(Dispatchers.IO) {
                    if (existing == null) {
                        db.goals().insert(
                            Goal(
                                name = name, color = color, targetDate = targetDay,
                                createdAt = System.currentTimeMillis(), details = details,
                            )
                        )
                    } else {
                        db.goals().update(existing.copy(name = name, color = color, targetDate = targetDay, details = details))
                    }
                }
            },
            onDelete = editGoal?.let { g ->
                {
                    showGoalEditor = false
                    if (filterGoalId == g.id) filterGoalId = null
                    scope.launch(Dispatchers.IO) {
                        db.todos().clearGoal(g.id)
                        db.goals().delete(g)
                    }
                }
            },
        )
    }

    editTask?.let { task ->
        var newTitle by remember(task.id) { mutableStateOf(task.title) }
        AlertDialog(
            onDismissRequest = { editTask = null },
            title = { Text("Edit task") },
            text = {
                OutlinedTextField(
                    value = newTitle,
                    onValueChange = { newTitle = it },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newTitle.isNotBlank(),
                    onClick = {
                        val t = newTitle.trim()
                        editTask = null
                        scope.launch(Dispatchers.IO) { db.todos().update(task.copy(title = t)) }
                    },
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editTask = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SwipeableTaskRow(
    task: TodoTask,
    onComplete: (() -> Unit)?,
    onDelete: () -> Unit,
    content: @Composable () -> Unit,
) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    if (onComplete != null) onComplete()
                    false // snap back; the row moves itself via state change
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    onDelete()
                    false // snap back; row is filtered out while delete is pending
                }
                SwipeToDismissBoxValue.Settled -> false
            }
        },
    )

    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = onComplete != null,
        backgroundContent = {
            val (color, alignment, icon, tint) = when (state.dismissDirection) {
                SwipeToDismissBoxValue.StartToEnd -> SwipeBg(
                    MaterialTheme.colorScheme.primaryContainer, Alignment.CenterStart,
                    Icons.Filled.Check, MaterialTheme.colorScheme.onPrimaryContainer,
                )
                else -> SwipeBg(
                    MaterialTheme.colorScheme.errorContainer, Alignment.CenterEnd,
                    Icons.Filled.DeleteOutline, MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp, vertical = 1.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(color)
                    .padding(horizontal = 24.dp),
                contentAlignment = alignment,
            ) {
                Icon(icon, contentDescription = null, tint = tint)
            }
        },
        content = { content() },
    )
}

private data class SwipeBg(
    val color: Color,
    val alignment: Alignment,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val tint: Color,
)

@Composable
private fun TaskRow(
    task: TodoTask,
    isChild: Boolean,
    dragging: Boolean,
    now: Long,
    goalColor: Int? = null,
    subtaskProgress: Pair<Int, Int>?,
    onToggleComplete: (Boolean) -> Unit,
    onToggleTimer: (() -> Unit)?,
    onAddSubtask: (() -> Unit)?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    dragHandle: (@Composable () -> Unit)?,
    expanded: Boolean = false,
    onToggleExpand: (() -> Unit)? = null,
) {
    val completed = task.completedAt != null
    val running = task.startedAt != null
    var menuOpen by remember(task.id) { mutableStateOf(false) }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                when {
                    dragging -> MaterialTheme.colorScheme.surfaceVariant
                    running -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    else -> MaterialTheme.colorScheme.surface
                }
            )
            .padding(start = if (isChild) 24.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isChild) {
            Icon(
                Icons.Filled.SubdirectoryArrowRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.outline,
            )
        }
        // Expand/collapse chevron for parents that have subtasks.
        if (onToggleExpand != null && subtaskProgress != null) {
            IconButton(onClick = onToggleExpand, modifier = Modifier.size(28.dp)) {
                Icon(
                    if (expanded) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight,
                    contentDescription = if (expanded) "Collapse subtasks" else "Expand subtasks",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(2.dp))
        }
        Checkbox(checked = completed, onCheckedChange = onToggleComplete)
        if (goalColor != null) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Color(goalColor))
            )
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                task.title,
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (completed) TextDecoration.LineThrough else null,
                color = if (completed) {
                    MaterialTheme.colorScheme.outline
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            when {
                running -> Text(
                    "⏱ ${formatDuration(now - task.startedAt!!)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                subtaskProgress != null -> Text(
                    "${subtaskProgress.first}/${subtaskProgress.second} subtasks",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!completed && onToggleTimer != null) {
            IconButton(onClick = onToggleTimer) {
                Icon(
                    if (running) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription = if (running) "Stop timer" else "Start timer",
                    tint = if (running) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "More",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (onAddSubtask != null && !completed) {
                    DropdownMenuItem(
                        text = { Text("Add subtask") },
                        onClick = { menuOpen = false; onAddSubtask() },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Edit") },
                    onClick = { menuOpen = false; onEdit() },
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    onClick = { menuOpen = false; onDelete() },
                )
            }
        }
        if (dragHandle != null) {
            dragHandle()
            Spacer(Modifier.width(4.dp))
        }
    }
}

@Composable
private fun AddTaskSheet(
    parent: TodoTask?,
    goals: List<Goal>,
    initialGoalId: Long?,
    showGoalPicker: Boolean,
    onDismiss: () -> Unit,
    onAdd: (String, Long?) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var title by remember { mutableStateOf("") }
    var goalId by remember { mutableStateOf(initialGoalId) }
    val focusRequester = remember { FocusRequester() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                if (parent == null) "New task" else "Subtask of \"${parent.title}\"",
                style = MaterialTheme.typography.titleLarge,
            )
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("What needs doing?") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (title.isNotBlank()) onAdd(title.trim(), goalId)
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
            if (showGoalPicker && goals.isNotEmpty()) {
                Text("Goal", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = goalId == null,
                        onClick = { goalId = null },
                        label = { Text("None") },
                    )
                    goals.forEach { g ->
                        FilterChip(
                            selected = goalId == g.id,
                            onClick = { goalId = g.id },
                            leadingIcon = {
                                Box(Modifier.size(10.dp).clip(CircleShape).background(Color(g.color)))
                            },
                            label = { Text(g.name) },
                        )
                    }
                }
            }
            Button(
                onClick = { onAdd(title.trim(), goalId) },
                enabled = title.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Add") }
        }
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

@Composable
private fun GoalSummaryRow(
    goals: List<Goal>,
    tasks: List<TodoTask>,
    selected: Long?,
    onSelect: (Long) -> Unit,
    onNewGoal: () -> Unit,
    onOpenProfile: (Goal) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(goals, key = { it.id }) { g ->
            val goalTasks = tasks.filter { it.goalId == g.id }
            val done = goalTasks.count { it.completedAt != null }
            val next = goalTasks.filter { it.completedAt == null }.minByOrNull { it.sortOrder }
            val isSel = selected == g.id
            Column(
                Modifier
                    .width(168.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (isSel) Color(g.color).copy(alpha = 0.20f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    )
                    .combinedClickable(
                        onClick = { onOpenProfile(g) },
                        onLongClick = { onSelect(g.id) },
                    )
                    .padding(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Color(g.color)))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        g.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "$done / ${goalTasks.size} done",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    next?.let { "Next: ${it.title}" } ?: "All done 🎉",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        item(key = "new-goal") {
            Column(
                Modifier
                    .width(120.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
                    .combinedClickable(onClick = onNewGoal)
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Filled.Add, "New goal", tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(4.dp))
                Text("New goal", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

private val GOAL_COLORS = listOf(
    0xFF5B9DFF, 0xFF4FC28C, 0xFFF4A63C, 0xFFEC407A, 0xFFB08CFF,
    0xFF26C6A6, 0xFFEF6C00, 0xFF8D6E63, 0xFF3FC6D8, 0xFFE53935,
).map { it.toInt() }

@Composable
private fun GoalEditorSheet(
    goal: Goal?,
    onDismiss: () -> Unit,
    onSave: (String, Int, Long?, String?) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember { mutableStateOf(goal?.name ?: "") }
    var details by remember { mutableStateOf(goal?.details ?: "") }
    var color by remember { mutableStateOf(goal?.color ?: GOAL_COLORS.first()) }
    var targetDay by remember { mutableStateOf(goal?.targetDate) }
    var pickDate by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(if (goal == null) "New goal" else "Edit goal", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Goal name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = details,
                onValueChange = { details = it },
                label = { Text("Why it matters / details") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Color", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GOAL_COLORS.forEach { c ->
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color(c))
                            .then(
                                if (c == color) Modifier.padding(0.dp) else Modifier
                            )
                            .combinedClickable(onClick = { color = c }),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (c == color) Icon(Icons.Filled.Check, null, tint = Color.White)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = { pickDate = true },
                    label = {
                        Text(
                            targetDay?.let { "Target: " + LocalDate.ofEpochDay(it).format(java.time.format.DateTimeFormatter.ofPattern("MMM d")) }
                                ?: "Set target date"
                        )
                    },
                )
                if (targetDay != null) {
                    TextButton(onClick = { targetDay = null }) { Text("Clear") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (onDelete != null) {
                    OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f)) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
                Button(
                    onClick = { onSave(name.trim(), color, targetDay, details.trim().ifBlank { null }) },
                    enabled = name.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) { Text("Save") }
            }
        }
    }

    if (pickDate) {
        val dpState = rememberDatePickerState(
            initialSelectedDateMillis = (targetDay ?: LocalDate.now().toEpochDay()) * 86_400_000L
        )
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    dpState.selectedDateMillis?.let { targetDay = it / 86_400_000L }
                    pickDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel") } },
        ) { DatePicker(state = dpState) }
    }
}

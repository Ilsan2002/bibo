@file:OptIn(ExperimentalMaterial3Api::class)

package com.bibo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SubdirectoryArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
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

    var addSheetParent by remember { mutableStateOf<TodoTask?>(null) }
    var showAddSheet by remember { mutableStateOf(false) }
    var editTask by remember { mutableStateOf<TodoTask?>(null) }
    var archiveExpanded by remember { mutableStateOf(false) }
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

    val visible = tasks.filter { it.id !in pendingDelete.value }
    val children = visible.filter { it.parentId != null }.groupBy { it.parentId }
    val parents = visible.filter { it.parentId == null }
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
                    val start = task.startedAt ?: (end - 15 * 60_000L)
                    if (task.startedAt != null && end - start >= 60_000L) {
                        db.activityBlocks().insert(
                            ActivityBlock(
                                title = task.title,
                                startMillis = start,
                                endMillis = end,
                                source = "TODO",
                            )
                        )
                    }
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
        if (task.startedAt == null) haptics.toggleOn() else haptics.toggleOff()
        scope.launch {
            withContext(Dispatchers.IO) {
                val started = task.startedAt
                if (started == null) {
                    db.todos().update(task.copy(startedAt = System.currentTimeMillis()))
                } else {
                    val end = System.currentTimeMillis()
                    if (end - started >= 60_000L) {
                        db.activityBlocks().insert(
                            ActivityBlock(
                                title = task.title,
                                startMillis = started,
                                endMillis = end,
                                source = "TODO",
                            )
                        )
                    }
                    db.todos().update(task.copy(startedAt = null))
                }
            }
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
        if (parents.isEmpty()) {
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
                item(key = "header") {
                    Text(
                        "Tasks",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                    )
                }

                items(ordered, key = { it.id }) { task ->
                    ReorderableItem(reorderState, key = task.id) { isDragging ->
                        val subs = children[task.id].orEmpty()
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
                                subtaskProgress = if (subs.isNotEmpty()) {
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
                            )
                        }
                    }
                    children[task.id]?.filter { it.completedAt == null }?.forEach { sub ->
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
        AddTaskSheet(
            parent = addSheetParent,
            onDismiss = { showAddSheet = false },
            onAdd = { title ->
                showAddSheet = false
                haptics.confirm()
                val parentId = addSheetParent?.id
                scope.launch(Dispatchers.IO) {
                    val now2 = System.currentTimeMillis()
                    db.todos().insert(
                        TodoTask(
                            title = title,
                            parentId = parentId,
                            createdAt = now2,
                            sortOrder = now2, // new tasks sort to the bottom
                        )
                    )
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
    subtaskProgress: Pair<Int, Int>?,
    onToggleComplete: (Boolean) -> Unit,
    onToggleTimer: (() -> Unit)?,
    onAddSubtask: (() -> Unit)?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    dragHandle: (@Composable () -> Unit)?,
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
        Checkbox(checked = completed, onCheckedChange = onToggleComplete)
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
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var title by remember { mutableStateOf("") }
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
                    if (title.isNotBlank()) onAdd(title.trim())
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
            Button(
                onClick = { onAdd(title.trim()) },
                enabled = title.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Add") }
        }
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

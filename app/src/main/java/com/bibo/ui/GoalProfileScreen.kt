package com.bibo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.SubdirectoryArrowRight
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bibo.data.BiboDb
import com.bibo.data.Goal
import com.bibo.data.TodoTask
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Full-screen "profile" for one goal: the why (details), overall progress, the target
 * countdown, the next step, and every task + subtask under it with tap-to-complete.
 */
@Composable
fun GoalProfileScreen(goal: Goal, onBack: () -> Unit, onEdit: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { BiboDb.get(context) }
    val accent = Color(goal.color)

    val tasks by db.todos().forGoal(goal.id).collectAsStateWithLifecycle(emptyList())
    val parents = tasks.filter { it.parentId == null }
    val childrenOf = tasks.filter { it.parentId != null }.groupBy { it.parentId }

    val done = tasks.count { it.completedAt != null }
    val total = tasks.size
    val nextTask = parents.filter { it.completedAt == null }.minByOrNull { it.sortOrder }

    fun toggle(task: TodoTask) {
        scope.launch(Dispatchers.IO) {
            val complete = task.completedAt == null
            val now = System.currentTimeMillis()
            db.todos().update(task.copy(completedAt = if (complete) now else null, startedAt = null))
            if (complete && task.parentId == null) {
                childrenOf[task.id]?.filter { it.completedAt == null }
                    ?.forEach { db.todos().update(it.copy(completedAt = now, startedAt = null)) }
            }
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 8.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    Box(
                        Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(accent)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        goal.name,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit goal")
                    }
                }
            }

            item {
                Column(
                    Modifier.padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (!goal.details.isNullOrBlank()) {
                        Text(
                            goal.details!!,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    val pct = if (total > 0) done.toFloat() / total else 0f
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row {
                            Text(
                                "$done of $total done",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                "${(pct * 100).toInt()}%",
                                style = MaterialTheme.typography.labelLarge,
                                color = accent,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        LinearProgressIndicator(
                            progress = { pct },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = accent,
                            trackColor = accent.copy(alpha = 0.15f),
                        )
                    }

                    goal.targetDate?.let { td ->
                        val left = td - LocalDate.now().toEpochDay()
                        val dateStr = LocalDate.ofEpochDay(td).format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
                        Text(
                            when {
                                left > 0 -> "🎯 Target $dateStr — $left day${if (left == 1L) "" else "s"} left"
                                left == 0L -> "🎯 Target is today ($dateStr)"
                                else -> "🎯 Target was $dateStr (${-left} day${if (left == -1L) "" else "s"} ago)"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (nextTask != null) {
                        Surface(
                            color = accent.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    "NEXT STEP",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = accent,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(nextTask.title, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }

                    Text(
                        "Tasks",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }

            if (parents.isEmpty()) {
                item {
                    Text(
                        "No tasks under this goal yet. Ask your mentor to break it down, or add one from the Tasks tab.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            }

            items(parents, key = { it.id }) { parent ->
                GoalTaskRow(parent, isChild = false, onToggle = { toggle(parent) })
                childrenOf[parent.id].orEmpty().forEach { sub ->
                    GoalTaskRow(sub, isChild = true, onToggle = { toggle(sub) })
                }
            }
        }
    }
}

@Composable
private fun GoalTaskRow(task: TodoTask, isChild: Boolean, onToggle: () -> Unit) {
    val completed = task.completedAt != null
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(start = if (isChild) 36.dp else 20.dp, end = 20.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isChild) {
            Icon(
                Icons.Filled.SubdirectoryArrowRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.outline,
            )
        }
        Checkbox(checked = completed, onCheckedChange = { onToggle() })
        Text(
            task.title,
            style = if (isChild) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
            textDecoration = if (completed) TextDecoration.LineThrough else null,
            color = if (completed) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
        )
    }
}

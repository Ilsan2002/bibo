package de.tobiasschuerg.weekview.compose.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.tobiasschuerg.weekview.data.Event
import de.tobiasschuerg.weekview.util.allDayHeaderLayout
import de.tobiasschuerg.weekview.util.allDayLastDate
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** One bounded header for every all-day source, with all hidden items available by touch or Agenda. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AllDayEventsHeader(
    days: List<LocalDate>,
    events: List<Event>,
    leftOffsetDp: Dp,
    columnWidth: Dp,
    heightBudget: Dp,
    onEventClick: ((Event) -> Unit)? = null,
    onEventLongPress: ((Event) -> Unit)? = null,
) {
    if (days.isEmpty() || events.isEmpty() || heightBudget <= 0.dp) return
    val rowHeight = 24.dp
    val overflowHeight = minOf(40.dp, heightBudget)
    val capacity = (heightBudget / rowHeight).toInt().coerceAtLeast(0)
    val initial = remember(events, days, capacity) { allDayHeaderLayout(events, days.first(), days.last(), capacity) }
    val layout = if (initial.hidden.isEmpty()) initial else {
        val visibleRows = ((heightBudget - overflowHeight) / rowHeight).toInt().coerceAtLeast(0)
        remember(events, days, visibleRows) { allDayHeaderLayout(events, days.first(), days.last(), visibleRows) }
    }
    var showOverflow by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        layout.rows.forEach { row ->
            Box(Modifier.fillMaxWidth().height(rowHeight)) {
                row.forEach { event ->
                    val first = maxOf(event.date, days.first())
                    val last = minOf(event.allDayLastDate(), days.last())
                    val startIndex = ChronoUnit.DAYS.between(days.first(), first).toInt()
                    val span = ChronoUnit.DAYS.between(first, last).toInt() + 1
                    val background = when (event) { is Event.AllDay -> event.backgroundColor; is Event.MultiDay -> event.backgroundColor; else -> 0 }
                    val foreground = when (event) { is Event.AllDay -> event.textColor; is Event.MultiDay -> event.textColor; else -> 0 }
                    key(event.id) {
                        Box(Modifier.offset(x = leftOffsetDp + columnWidth * startIndex).width(columnWidth * span)
                            .height(rowHeight).padding(1.dp).clip(RoundedCornerShape(4.dp)).background(Color(background))
                            .combinedClickable(role = Role.Button, onClickLabel = "Details for ${event.title}, all day",
                                onClick = { onEventClick?.invoke(event) }, onLongClick = onEventLongPress?.let { callback -> { callback(event) } })
                            .padding(horizontal = 4.dp), contentAlignment = Alignment.CenterStart) {
                            Text(event.title, color = Color(foreground), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        if (layout.hidden.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().height(overflowHeight).padding(start = leftOffsetDp)
                .combinedClickable(role = Role.Button, onClickLabel = "Show ${layout.hidden.size} more all-day events",
                    onClick = { showOverflow = true }), contentAlignment = Alignment.CenterStart) {
                Text("${layout.hidden.size} more all-day events", color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    if (showOverflow) AlertDialog(onDismissRequest = { showOverflow = false }, title = { Text("More all-day events") },
        text = { LazyColumn { items(layout.hidden, key = { it.id }) { event ->
            TextButton(onClick = { showOverflow = false; onEventClick?.invoke(event) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth()) {
                    Text(event.title)
                    Text(if (event.date == event.allDayLastDate()) event.date.toString() else "${event.date} – ${event.allDayLastDate()}", style = MaterialTheme.typography.bodySmall)
                }
            }
        } } }, confirmButton = { TextButton(onClick = { showOverflow = false }) { Text("Close") } })
}

package de.tobiasschuerg.weekview.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
import java.time.LocalDate

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AllDayEventsRow(
    days: List<LocalDate>,
    allDayEvents: List<Event.AllDay>,
    leftOffsetDp: Dp,
    columnWidth: Dp,
    onEventClick: ((event: Event) -> Unit)? = null,
    onEventLongPress: ((event: Event) -> Unit)? = null,
) {
    var overflow by remember { mutableStateOf<List<Event.AllDay>>(emptyList()) }
    Row(modifier = Modifier.fillMaxWidth()) {
        Spacer(modifier = Modifier.width(leftOffsetDp))
        days.forEach { date ->
            val eventsForDay = allDayEvents.filter { it.date == date }
            Column(
                modifier = Modifier.width(columnWidth).padding(horizontal = 1.dp),
            ) {
                eventsForDay.take(3).forEach { event ->
                    key(event.id) { Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(24.dp)
                                .padding(vertical = 1.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(event.backgroundColor))
                                .combinedClickable(role = Role.Button, onClickLabel = "Details for ${event.title}, all day", onClick = { onEventClick?.invoke(event) }, onLongClick = onEventLongPress?.let { callback -> { callback(event) } })
                                .padding(horizontal = 4.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            text = event.title,
                            color = Color(event.textColor),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } }
                }
                if (eventsForDay.size > 3) androidx.compose.material3.TextButton(onClick = { overflow = eventsForDay }) { Text("${eventsForDay.size - 3} more") }
            }
        }
    }
    if (overflow.isNotEmpty()) androidx.compose.material3.AlertDialog(
        onDismissRequest = { overflow = emptyList() }, title = { Text("All-day events") },
        text = { androidx.compose.foundation.lazy.LazyColumn { items(overflow.size) { index -> val event = overflow[index]; androidx.compose.material3.TextButton(onClick = { onEventClick?.invoke(event); overflow = emptyList() }) { Text(event.title) } } } },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { overflow = emptyList() }) { Text("Close") } },
    )
}

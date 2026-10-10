package com.bibo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bibo.data.AppTime
import com.bibo.data.UsageCoverageState
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun WeeklyTrendChart(
    days: List<LocalDate>, minutes: List<Long>, modifier: Modifier = Modifier,
    coverage: List<UsageCoverageState> = emptyList(), onSelectDay: (LocalDate) -> Unit = {},
    exactMillis: List<Long>? = null,
) {
    val values = exactMillis ?: minutes.map { it * 60_000 }
    val max = (values.maxOrNull() ?: 0).coerceAtLeast(1)
    val locale = LocalConfiguration.current.locales[0]
    Column(modifier.fillMaxWidth()) {
        Text("Last 7 days", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            days.forEachIndexed { index, day ->
                val millis = values.getOrElse(index) { 0 }
                val state = coverage.getOrElse(index) { UsageCoverageState.COMPLETE }
                val label = if (state == UsageCoverageState.UNKNOWN && millis == 0L) "?" else formatDuration(millis)
                Column(Modifier.weight(1f).clickable { onSelectDay(day) }.semantics(mergeDescendants = true) {
                    contentDescription = "${day.format(DateTimeFormatter.ofPattern("EEEE, MMM d", locale))}: ${if (label == "?") "unknown coverage" else "$label, ${state.name.lowercase()} coverage"}"
                }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, style = MaterialTheme.typography.labelSmall)
                    Box(Modifier.fillMaxWidth().height(96.dp).padding(horizontal = 6.dp), contentAlignment = Alignment.BottomCenter) {
                        if (millis > 0) Box(Modifier.fillMaxWidth().height((96f * millis / max).dp.coerceAtLeast(2.dp)).clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = if (state == UsageCoverageState.COMPLETE) 1f else 0.5f)))
                    }
                    Text(day.format(DateTimeFormatter.ofPattern("EEE", locale)), style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (day == AppTime.today()) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
        Text("Tap a day. ? means unknown; faded bars have incomplete collection coverage.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun UsageHeatmap(
    days: List<LocalDate>, matrix: Array<LongArray>, modifier: Modifier = Modifier,
    coverage: List<UsageCoverageState> = emptyList(),
) {
    val locale = LocalConfiguration.current.locales[0]
    val max = matrix.flatMap { it.asList() }.maxOrNull()?.coerceAtLeast(1) ?: 1
    val empty = MaterialTheme.colorScheme.surfaceVariant
    val hot = MaterialTheme.colorScheme.primary
    var selected by remember(days) { mutableStateOf<Pair<Int, Int>?>(null) }
    Column(modifier.fillMaxWidth()) {
        Text("Time by hour", style = MaterialTheme.typography.titleMedium)
        Text("Scroll and tap a cell for its exact value. Repeated clock hours are combined; skipped hours contain no time.", style = MaterialTheme.typography.bodySmall)
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            Row {
                Spacer(Modifier.width(64.dp))
                repeat(24) { hour -> Text(hour.toString().padStart(2, '0'), style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(48.dp)) }
            }
            days.forEachIndexed { index, day ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(day.format(DateTimeFormatter.ofPattern("MMM d", locale)), style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(64.dp))
                    repeat(24) { hour ->
                        val millis = matrix.getOrNull(index)?.getOrNull(hour) ?: 0
                        val state = coverage.getOrElse(index) { UsageCoverageState.COMPLETE }
                        val label = if (millis == 0L && state != UsageCoverageState.COMPLETE) "No observation; coverage ${state.name.lowercase()}" else formatDuration(millis)
                        Box(Modifier.padding(1.dp).size(46.dp).clip(RoundedCornerShape(3.dp))
                            .background(if (millis == 0L) empty else lerp(empty, hot, (0.25f + 0.75f * millis.toFloat() / max).coerceIn(0f, 1f)))
                            .clickable { selected = index to hour }
                            .semantics { contentDescription = "$day, hour $hour: $label" })
                    }
                }
            }
        }
        selected?.let { (dayIndex, hour) ->
            val millis = matrix.getOrNull(dayIndex)?.getOrNull(hour) ?: 0
            val state = coverage.getOrElse(dayIndex) { UsageCoverageState.COMPLETE }
            Text("${days[dayIndex]} ${hour.toString().padStart(2, '0')}:00 — ${millis / 1000} seconds observed (${state.name.lowercase()} daily coverage)", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

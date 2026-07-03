package com.bibo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * Weekly screen-time trend: one bar per day, height proportional to total foreground
 * time, today emphasized. Hand-rolled in plain Compose (fully themed, tappable-ready).
 */
@Composable
fun WeeklyTrendChart(
    days: List<LocalDate>,
    minutes: List<Long>,
    modifier: Modifier = Modifier,
) {
    val today = LocalDate.now()
    val maxMin = (minutes.maxOrNull() ?: 0L).coerceAtLeast(1L)
    val barMaxHeight = 96.dp

    Column(modifier.fillMaxWidth()) {
        Text(
            "Last 7 days",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            days.forEachIndexed { i, day ->
                val mins = minutes.getOrElse(i) { 0L }
                val frac = mins.toFloat() / maxMin
                val isToday = day == today
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        if (mins >= 60) "${mins / 60}h" else if (mins > 0) "${mins}m" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(2.dp))
                    Box(
                        Modifier
                            .width(22.dp)
                            .height(barMaxHeight),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height((barMaxHeight.value * frac).dp.coerceAtLeast(3.dp))
                                .clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp))
                                .background(
                                    if (isToday) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                                )
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        day.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                        color = if (isToday) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * GitHub-style usage heatmap: rows = days, columns = hours of the day, cell intensity
 * proportional to foreground time in that hour. Reveals when the phone gets used.
 */
@Composable
fun UsageHeatmap(
    days: List<LocalDate>,
    matrix: Array<LongArray>,
    modifier: Modifier = Modifier,
) {
    val maxCell = matrix.flatMap { it.asList() }.maxOrNull()?.coerceAtLeast(1L) ?: 1L
    val empty = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    val hot = MaterialTheme.colorScheme.primary

    Column(modifier.fillMaxWidth()) {
        Text(
            "When you're on your phone",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        days.forEachIndexed { dayIdx, day ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    day.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Row(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    for (hour in 0 until 24) {
                        val v = matrix.getOrNull(dayIdx)?.getOrNull(hour) ?: 0L
                        val frac = (v.toFloat() / maxCell).coerceIn(0f, 1f)
                        Box(
                            Modifier
                                .weight(1f)
                                .height(14.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(if (v == 0L) empty else lerp(empty, hot, 0.25f + 0.75f * frac))
                        )
                    }
                }
            }
        }
        // hour axis ticks
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, top = 2.dp)) {
            listOf("12a", "6a", "12p", "6p").forEach { label ->
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

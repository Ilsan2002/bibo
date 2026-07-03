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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.bibo.data.AppUsageTotal
import com.bibo.data.UsageRepo
import com.bibo.data.WebsiteRepo
import com.bibo.data.WebsiteTotal
import com.bibo.data.isWebTrackingEnabled
import com.bibo.data.openAccessibilitySettings
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun formatDuration(millis: Long): String {
    val totalMinutes = millis / 60_000
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

@Composable
fun StatsScreen() {
    val context = LocalContext.current
    val usageRepo = remember { UsageRepo(context) }
    val webRepo = remember { WebsiteRepo(context) }
    val zone = remember { ZoneId.systemDefault() }

    var epochDay by rememberSaveable { mutableLongStateOf(LocalDate.now().toEpochDay()) }
    val date = LocalDate.ofEpochDay(epochDay)

    var hasPermission by remember { mutableStateOf(usageRepo.hasPermission()) }
    var webEnabled by remember { mutableStateOf(isWebTrackingEnabled(context)) }
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasPermission = usageRepo.hasPermission()
        webEnabled = isWebTrackingEnabled(context)
        refresh++
    }

    if (!hasPermission) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Bibo needs Usage Access to see which apps you use and for how long.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = { usageRepo.openUsageAccessSettings() }) {
                Text("Grant usage access")
            }
            Text(
                "Find Bibo in the list and allow it.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        return
    }

    val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
    val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    val totals by produceState(
        initialValue = emptyList<AppUsageTotal>(),
        dayStart, dayEnd, refresh,
    ) {
        value = withContext(Dispatchers.IO) { usageRepo.queryTotals(dayStart, dayEnd) }
    }

    // 7 days ending on the selected day, for the trend chart + heatmap
    val week = remember(epochDay) { (6 downTo 0).map { date.minusDays(it.toLong()) } }
    val dailyMinutes by produceState(initialValue = List(7) { 0L }, week, refresh) {
        value = withContext(Dispatchers.IO) {
            usageRepo.dailyTotals(week, zone).map { it / 60_000L }
        }
    }
    val hourly by produceState(initialValue = Array(7) { LongArray(24) }, week, refresh) {
        value = withContext(Dispatchers.IO) { usageRepo.hourlyMatrix(week, zone) }
    }
    val webTotals by produceState(emptyList<WebsiteTotal>(), dayStart, dayEnd, webEnabled, refresh) {
        value = if (webEnabled) {
            withContext(Dispatchers.IO) { webRepo.totals(dayStart, dayEnd) }
        } else {
            emptyList()
        }
    }

    val grandTotal = totals.sumOf { it.totalMillis }
    val maxTotal = totals.maxOfOrNull { it.totalMillis } ?: 1L

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Screen time",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { epochDay-- }) { Icon(Icons.Filled.ChevronLeft, "Previous day") }
            Text(
                date.format(DateTimeFormatter.ofPattern("MMM d")),
                style = MaterialTheme.typography.titleSmall,
            )
            IconButton(
                onClick = { epochDay++ },
                enabled = epochDay < LocalDate.now().toEpochDay(),
            ) { Icon(Icons.Filled.ChevronRight, "Next day") }
        }
        if (epochDay != LocalDate.now().toEpochDay()) {
            TextButton(
                onClick = { epochDay = LocalDate.now().toEpochDay() },
                modifier = Modifier.padding(start = 8.dp),
            ) { Text("Back to today") }
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item(key = "total") {
                Text(
                    formatDuration(grandTotal),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                if (totals.isNotEmpty()) {
                    val top = totals.take(6)
                    val rest = grandTotal - top.sumOf { it.totalMillis }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                            .height(14.dp)
                            .clip(RoundedCornerShape(7.dp)),
                    ) {
                        top.forEach { app ->
                            Box(
                                Modifier
                                    .weight(app.totalMillis.toFloat())
                                    .fillMaxSize()
                                    .background(Color(app.color))
                            )
                        }
                        if (rest > 0) {
                            Box(
                                Modifier
                                    .weight(rest.toFloat())
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            )
                        }
                    }
                }
            }

            item(key = "trend") {
                WeeklyTrendChart(
                    days = week,
                    minutes = dailyMinutes,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }

            item(key = "heatmap") {
                UsageHeatmap(
                    days = week,
                    matrix = hourly,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            if (totals.isEmpty()) {
                item(key = "empty") {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "No app usage recorded for this day.",
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            } else {
                item(key = "apps-header") {
                    Text(
                        "Apps",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                items(totals, key = { it.packageName }) { app ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 5.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(10.dp)
                                    .background(Color(app.color), CircleShape)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                app.label,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                formatDuration(app.totalMillis),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Spacer(Modifier.height(3.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(3.dp),
                                )
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth(app.totalMillis.toFloat() / maxTotal)
                                    .height(5.dp)
                                    .background(Color(app.color), RoundedCornerShape(3.dp))
                            )
                        }
                    }
                }
            }

            item(key = "web-header") {
                Text(
                    "Websites",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
                )
            }
            if (!webEnabled) {
                item(key = "web-enable") {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            .padding(16.dp),
                    ) {
                        Text(
                            "See time per website",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "Turn on Bibo's accessibility service to track which sites you spend time on. Reads only the address bar; stays on your phone.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                        Button(onClick = { openAccessibilitySettings(context) }) {
                            Text("Enable website tracking")
                        }
                    }
                }
            } else if (webTotals.isEmpty()) {
                item(key = "web-empty") {
                    Text(
                        "No website time recorded yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
            } else {
                val webMax = webTotals.maxOf { it.totalMillis }.coerceAtLeast(1L)
                items(webTotals, key = { it.domain }) { site ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 5.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(10.dp)
                                    .background(Color(site.color), CircleShape)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                site.domain,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                formatDuration(site.totalMillis),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Spacer(Modifier.height(3.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(3.dp),
                                )
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth(site.totalMillis.toFloat() / webMax)
                                    .height(5.dp)
                                    .background(Color(site.color), RoundedCornerShape(3.dp))
                            )
                        }
                    }
                }
            }
        }
    }
}

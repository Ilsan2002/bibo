@file:OptIn(ExperimentalLayoutApi::class)

package com.bibo.ui

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bibo.data.ActivityBlock
import com.bibo.data.BiboDb
import com.bibo.data.TimerController
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PREFS = "bibo_timer"
private const val KEY_START = "running_start"
private const val KEY_TITLE = "running_title"
private val sessionTimeFmt = DateTimeFormatter.ofPattern("H:mm")

@Composable
fun TimerScreen() {
    val context = LocalContext.current
    val db = remember { BiboDb.get(context) }
    val scope = rememberCoroutineScope()
    val zone = remember { ZoneId.systemDefault() }
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    val haptics = rememberHaptics()
    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    var title by remember { mutableStateOf(prefs.getString(KEY_TITLE, "") ?: "") }
    var runningStart by remember { mutableLongStateOf(prefs.getLong(KEY_START, 0L)) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val isRunning = runningStart > 0L

    LaunchedEffect(isRunning) {
        while (isRunning) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    val recents by produceState(initialValue = emptyList<String>(), runningStart) {
        value = withContext(Dispatchers.IO) { db.activityBlocks().recentTimerTitles() }
    }

    val dayStart = LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli()
    val dayEnd = LocalDate.now().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val todayBlocksFlow = remember(dayStart) { db.activityBlocks().blocksIn(dayStart, dayEnd) }
    val todayBlocks by todayBlocksFlow.collectAsState(initial = emptyList())
    val sessions = todayBlocks.filter { it.source == "TIMER" }.sortedByDescending { it.startMillis }

    Column(Modifier.fillMaxSize()) {
        Text(
            "Timer",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
        )

        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val elapsed = if (isRunning) now - runningStart else 0L
            val totalSec = elapsed / 1000

            if (isRunning) {
                Text(
                    title.ifBlank { "Untitled activity" },
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
            }

            Text(
                "%02d:%02d:%02d".format(totalSec / 3600, (totalSec % 3600) / 60, totalSec % 60),
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )

            Spacer(Modifier.height(12.dp))

            if (!isRunning) {
                OutlinedTextField(
                    value = title,
                    onValueChange = {
                        title = it
                        prefs.edit().putString(KEY_TITLE, it).apply()
                    },
                    label = { Text("What are you doing?") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (recents.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        recents.forEach { r ->
                            SuggestionChip(
                                onClick = {
                                    title = r
                                    prefs.edit().putString(KEY_TITLE, r).apply()
                                },
                                label = { Text(r) },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        haptics.toggleOn()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        TimerController.startTimer(context, title.trim().ifBlank { "Timing" })
                        runningStart = TimerController.runningStart(context)
                    },
                    enabled = title.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Start") }
            } else {
                OutlinedButton(
                    onClick = {
                        haptics.toggleOff()
                        TimerController.stopTimer(context)
                        runningStart = 0L
                        title = ""
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Stop & save") }
                Text(
                    "Shows on your calendar when you stop.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

        Text(
            "Today",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        if (sessions.isEmpty()) {
            Text(
                "Nothing tracked yet today.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(sessions, key = { it.id }) { s ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(10.dp)
                                .background(Color(sourceColor("TIMER")), CircleShape)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.title, style = MaterialTheme.typography.bodyLarge)
                            val st = Instant.ofEpochMilli(s.startMillis).atZone(zone)
                            val et = Instant.ofEpochMilli(s.endMillis).atZone(zone)
                            Text(
                                "${st.format(sessionTimeFmt)} – ${et.format(sessionTimeFmt)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            formatDuration(s.endMillis - s.startMillis),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

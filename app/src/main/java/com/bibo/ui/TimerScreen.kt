@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.bibo.ui

import android.Manifest
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.bibo.data.AppInfo
import com.bibo.data.BiboDb
import com.bibo.data.FocusConfig
import com.bibo.data.FocusDnd
import com.bibo.data.Goal
import com.bibo.data.TimerController
import com.bibo.data.canDrawOverlays
import com.bibo.data.launchableApps
import com.bibo.data.requestOverlayPermission
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val sessionTimeFmt = DateTimeFormatter.ofPattern("H:mm")

@Composable
fun TimerScreen() {
    val context = LocalContext.current
    val db = remember { BiboDb.get(context) }
    val scope = rememberCoroutineScope()
    val zone = remember { ZoneId.systemDefault() }
    val haptics = rememberHaptics()
    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    // 1-second heartbeat; running/phase state is re-read from TimerController each tick.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffectEverySecond { now = it }

    val running = TimerController.isRunning(context)

    val goals by db.goals().all().collectAsState(initial = emptyList())

    // Today's timer/focus sessions
    val dayStart = LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli()
    val dayEnd = LocalDate.now().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val todayBlocksFlow = remember(dayStart) { db.activityBlocks().blocksIn(dayStart, dayEnd) }
    val todayBlocks by todayBlocksFlow.collectAsState(initial = emptyList())
    val sessions = todayBlocks
        .filter { it.source == "TIMER" || it.source == "FOCUS" }
        .sortedByDescending { it.startMillis }

    var showReflection by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item(key = "title") {
                Text(
                    "Focus",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                )
            }

            item(key = "main") {
                if (running) {
                    RunningPanel(
                        now = now,
                        onStop = { showReflection = true },
                    )
                } else {
                    SetupPanel(
                        goals = goals,
                        onStart = { config ->
                            haptics.toggleOn()
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            TimerController.startFocus(context, config)
                            now = System.currentTimeMillis()
                        },
                    )
                }
            }

            if (sessions.isNotEmpty()) {
                item(key = "today-head") {
                    Text(
                        "Today",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(16.dp),
                    )
                }
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
                                .background(
                                    Color(if (s.source == "FOCUS") 0xFF5B9DFF.toInt() else sourceColor("TIMER")),
                                    CircleShape,
                                )
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.title, style = MaterialTheme.typography.bodyLarge)
                            val st = Instant.ofEpochMilli(s.startMillis).atZone(zone)
                            val et = Instant.ofEpochMilli(s.endMillis).atZone(zone)
                            Text(
                                "${st.format(sessionTimeFmt)} – ${et.format(sessionTimeFmt)}" +
                                    (s.note?.let { " · $it" } ?: ""),
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

    if (showReflection) {
        ReflectionSheet(
            onDismiss = { showReflection = false },
            onSave = { note ->
                showReflection = false
                haptics.toggleOff()
                TimerController.stopTimer(context, note)
                now = System.currentTimeMillis()
            },
        )
    }
}

@Composable
private fun LaunchedEffectEverySecond(onTick: (Long) -> Unit) {
    LaunchedEffect(Unit) {
        while (true) {
            onTick(System.currentTimeMillis())
            delay(1000)
        }
    }
}

@Composable
private fun RunningPanel(now: Long, onStop: () -> Unit) {
    val context = LocalContext.current
    val pomodoro = TimerController.isPomodoro(context)
    val title = TimerController.runningTitle(context)
    val start = TimerController.runningStart(context)
    val blocked = TimerController.blockedApps(context)

    Column(
        Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (pomodoro) {
            val work = TimerController.phase(context) == TimerController.PHASE_WORK
            val remain = (TimerController.phaseEnd(context) - now).coerceAtLeast(0) / 1000
            Text(
                if (work) "Focus" else "Break",
                style = MaterialTheme.typography.titleMedium,
                color = if (work) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(
                "%02d:%02d".format(remain / 60, remain % 60),
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                "${TimerController.pomodorosDone(context)} pomodoros done",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val elapsed = (now - start) / 1000
            Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "%02d:%02d:%02d".format(elapsed / 3600, (elapsed % 3600) / 60, elapsed % 60),
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
        }

        if (blocked.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Block, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(6.dp))
                Text(
                    "${blocked.size} app${if (blocked.size > 1) "s" else ""} blocked",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
            Text("Stop & reflect")
        }
    }
}

@Composable
private fun SetupPanel(goals: List<Goal>, onStart: (FocusConfig) -> Unit) {
    val context = LocalContext.current
    var intention by remember { mutableStateOf("") }
    var goalId by remember { mutableStateOf<Long?>(null) }
    var pomodoro by remember { mutableStateOf(false) }
    var workMin by remember { mutableStateOf(25) }
    var breakMin by remember { mutableStateOf(5) }
    var dnd by remember { mutableStateOf(false) }
    var blocked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showAppPicker by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        OutlinedTextField(
            value = intention,
            onValueChange = { intention = it },
            label = { Text("What are you focusing on?") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        if (goals.isNotEmpty()) {
            Text("Goal", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = goalId == null, onClick = { goalId = null }, label = { Text("None") })
                goals.forEach { g ->
                    FilterChip(
                        selected = goalId == g.id,
                        onClick = { goalId = g.id },
                        leadingIcon = { Box(Modifier.size(10.dp).background(Color(g.color), CircleShape)) },
                        label = { Text(g.name) },
                    )
                }
            }
        }

        // Block apps
        OutlinedButton(onClick = { showAppPicker = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Block, null)
            Spacer(Modifier.width(8.dp))
            Text(if (blocked.isEmpty()) "Block distracting apps" else "${blocked.size} app${if (blocked.size > 1) "s" else ""} blocked")
        }

        // Pomodoro
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Pomodoro (${workMin}m / ${breakMin}m)", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(checked = pomodoro, onCheckedChange = { pomodoro = it })
        }
        if (pomodoro) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15, 25, 50).forEach { m ->
                    FilterChip(selected = workMin == m, onClick = { workMin = m }, label = { Text("${m}m work") })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5, 10, 15).forEach { m ->
                    FilterChip(selected = breakMin == m, onClick = { breakMin = m }, label = { Text("${m}m break") })
                }
            }
        }

        // DND
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.NotificationsOff, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text("Silence phone (DND)", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = dnd,
                onCheckedChange = {
                    if (it && !FocusDnd.hasAccess(context)) FocusDnd.openSettings(context) else dnd = it
                },
            )
        }

        Button(
            onClick = {
                onStart(
                    FocusConfig(
                        intention = intention.trim(),
                        goalId = goalId,
                        blockedApps = blocked,
                        pomodoro = pomodoro,
                        workMin = workMin,
                        breakMin = breakMin,
                        dnd = dnd,
                    )
                )
            },
            enabled = intention.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Start focus") }
    }

    if (showAppPicker) {
        AppPickerSheet(
            selected = blocked,
            onDismiss = { showAppPicker = false },
            onDone = { blocked = it; showAppPicker = false },
        )
    }
}

@Composable
private fun AppPickerSheet(
    selected: Set<String>,
    onDismiss: () -> Unit,
    onDone: (Set<String>) -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var chosen by remember { mutableStateOf(selected) }
    val apps by produceState(initialValue = emptyList<AppInfo>()) {
        value = withContext(Dispatchers.IO) { launchableApps(context) }
    }
    val overlayOk = canDrawOverlays(context)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
            Text(
                "Block during focus",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            if (!overlayOk) {
                Text(
                    "For a full block screen, allow Bibo to display over other apps. (Without it, blocked apps just bounce you home.)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                )
                TextButton(
                    onClick = { requestOverlayPermission(context) },
                    modifier = Modifier.padding(horizontal = 12.dp),
                ) { Text("Allow display over apps") }
            }
            LazyColumn(Modifier.fillMaxWidth().height(360.dp)) {
                items(apps, key = { it.packageName }) { app ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = app.packageName in chosen,
                            onCheckedChange = {
                                chosen = if (it) chosen + app.packageName else chosen - app.packageName
                            },
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(app.label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            Button(
                onClick = { onDone(chosen) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) { Text("Done (${chosen.size})") }
        }
    }
}

@Composable
private fun ReflectionSheet(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var note by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = { onSave("") }, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Session done 🎉", style = MaterialTheme.typography.titleLarge)
            Text(
                "How did it go? What did you get done?",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("A line of reflection (optional)") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { onSave("") }, modifier = Modifier.weight(1f)) { Text("Skip") }
                Button(onClick = { onSave(note.trim()) }, modifier = Modifier.weight(1f)) { Text("Save") }
            }
        }
    }
}

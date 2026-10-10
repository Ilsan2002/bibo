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
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.bibo.data.AppScope
import com.bibo.data.Capabilities
import com.bibo.data.SessionTiming
import com.bibo.data.isWebTrackingEnabled
import com.bibo.data.openAccessibilitySettings
import com.bibo.data.AppTime
import com.bibo.data.DayContext
import com.bibo.data.WorkSession
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Surface
import androidx.compose.ui.text.style.TextOverflow
import com.bibo.data.TodoTask
import java.time.DayOfWeek

private val sessionTimeFmt = DateTimeFormatter.ofPattern("H:mm")

@Composable
fun TimerScreen() {
    val context = LocalContext.current
    val db = remember { BiboDb.get(context) }
    val scope = rememberCoroutineScope()
    val dayContext by AppTime.days.collectAsState(initial = DayContext(AppTime.today(), AppTime.zone()))
    val zone = dayContext.zone
    val timerViewModel: TimerViewModel = viewModel()
    val haptics = rememberHaptics()
    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    // 1-second heartbeat; running/phase state is re-read from TimerController each tick.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffectEverySecond { now = it }

    val activeSession by TimerController.state.collectAsState()
    val sessionError by TimerController.error.collectAsState()
    val running = activeSession != null
    LaunchedEffect(Unit) { TimerController.initialize(context) }

    val goals by db.goals().all().collectAsState(initial = emptyList())
    val interrupted by db.workSessions().interruptedFlow().collectAsState(initial = emptyList())

    // Today's timer/focus sessions
    val dayStart = dayContext.date.atStartOfDay(zone).toInstant().toEpochMilli()
    val dayEnd = dayContext.date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val todayBlocksFlow = remember(dayStart, dayEnd) { db.activityBlocks().blocksIn(dayStart, dayEnd) }
    val todayBlocks by todayBlocksFlow.collectAsState(initial = emptyList())
    val sessions = todayBlocks
        .filter { it.source == "TIMER" || it.source == "FOCUS" || it.source == "TASK_TIMER" }
        .sortedByDescending { it.startMillis }

    val reflectionSessionId by timerViewModel.reflectionSessionId.collectAsState()

    // The screen should propose, not just ask: next open top-level tasks become one-tap intentions.
    val allTasks by db.todos().all().collectAsState(initial = emptyList())
    val suggestions = remember(allTasks) {
        allTasks.filter { it.completedAt == null && it.parentId == null }.take(4)
    }
    // Focused time today / this week (Mon-based) as the screen's feedback loop.
    val weekStart = remember(dayStart, zone) {
        dayContext.date.with(DayOfWeek.MONDAY).atStartOfDay(zone).toInstant().toEpochMilli()
    }
    val weekBlocksFlow = remember(weekStart, dayEnd) { db.activityBlocks().blocksIn(weekStart, dayEnd) }
    val weekBlocks by weekBlocksFlow.collectAsState(initial = emptyList())
    val todayFocusMs = AppTime.measuredWorkMillis(todayBlocks, dayStart, dayEnd)
    val weekFocusMs = AppTime.measuredWorkMillis(weekBlocks, weekStart, dayEnd)

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            if (sessionError != null) item(key = "session-error") {
                Column(Modifier.padding(16.dp)) {
                    Text(sessionError.orEmpty(), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { TimerController.acknowledgeError() }) { Text("Dismiss") }
                }
            }
            item(key = "title") {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
                    Text("Focus", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    if (!running) {
                        Text(
                            "One thing. Phone down.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(18.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                            FocusStat(focusLabel(todayFocusMs), "focused today")
                            FocusStat(focusLabel(weekFocusMs), "this week")
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }

            item(key = "main") {
                if (running) {
                    RunningPanel(
                        session = activeSession!!,
                        now = now,
                        onStop = {
                            activeSession?.id?.let(timerViewModel::stopAndReflect)
                        },
                    )
                } else {
                    SetupPanel(
                        goals = goals,
                        suggestions = suggestions,
                        onStart = { config ->
                            haptics.toggleOn()
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            timerViewModel.start(config)
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
                            .clickable(enabled = s.sessionId != null) { s.sessionId?.let(timerViewModel::reflect) }
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
                            val fmt = if (st.toLocalDate() == et.toLocalDate()) sessionTimeFmt else DateTimeFormatter.ofPattern("MMM d H:mm")
                            Text(
                                "${st.format(fmt)} – ${et.format(fmt)}" +
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
            if (interrupted.isNotEmpty()) {
                item(key = "interrupted-head") { Text("Interrupted sessions", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(16.dp)) }
                items(interrupted, key = { "interrupted-${it.id}" }) { session ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(session.title, fontWeight = FontWeight.Medium)
                        Text(if (session.kind == "LEGACY") session.note ?: "Earlier session needs its end time reviewed on the calendar."
                            else "Saved ${formatDuration(session.elapsedAtAnchorMillis)} of confirmed time; unattended downtime excluded.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    reflectionSessionId?.let { sessionId ->
        ReflectionSheet(
            sessionId = sessionId,
            onDismiss = timerViewModel::dismissReflection,
            onSave = { note ->
                haptics.toggleOff()
                timerViewModel.saveReflection(sessionId, note)
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
private fun RunningPanel(session: WorkSession, now: Long, onStop: () -> Unit) {
    val context = LocalContext.current
    val pomodoro = session.pomodoro
    val title = session.title
    val elapsedMillis = remember(session, now) { TimerController.elapsedMillis(session) }
    val currentPhase = SessionTiming.phase(elapsedMillis, session.workMin, session.breakMin)
    val blocked = session.blockedPackages.split(',').filter { it.isNotBlank() }.toSet()

    Column(
        Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (pomodoro) {
            val work = currentPhase.kind == TimerController.PHASE_WORK
            val remain = currentPhase.remainingMillis / 1000
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
                "${currentPhase.pomodorosDone} pomodoros done",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val elapsed = elapsedMillis / 1000
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
                    if (!isWebTrackingEnabled(context) || !Capabilities.accessibilityServiceConnected(context)) "App blocking unavailable — enable Accessibility"
                    else if (pomodoro && currentPhase.kind == TimerController.PHASE_BREAK) "Apps available during break"
                    else "${blocked.size} app${if (blocked.size > 1) "s" else ""} blocked",
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
private fun SetupPanel(goals: List<Goal>, suggestions: List<TodoTask>, onStart: (FocusConfig) -> Unit) {
    val context = LocalContext.current
    val presets = remember { context.getSharedPreferences("bibo_focus_defaults", android.content.Context.MODE_PRIVATE) }
    var intention by rememberSaveable { mutableStateOf(presets.getString("intention", "").orEmpty()) }
    var goalId by rememberSaveable { mutableStateOf(presets.getLong("goal", -1).takeIf { it > 0 }) }
    var selectedTaskId by rememberSaveable { mutableStateOf(presets.getLong("task_id", -1).takeIf { it > 0 }) }
    var pomodoro by rememberSaveable { mutableStateOf(presets.getBoolean("pomodoro", false)) }
    var workMin by rememberSaveable { mutableStateOf(presets.getInt("work", 25)) }
    var breakMin by rememberSaveable { mutableStateOf(presets.getInt("break", 5)) }
    var dnd by rememberSaveable { mutableStateOf(presets.getBoolean("dnd", false)) }
    var phaseSound by rememberSaveable { mutableStateOf(presets.getBoolean("phase_sound", true)) }
    var lockscreenDetails by rememberSaveable { mutableStateOf(presets.getBoolean("lockscreen_details", false)) }
    var sessionComment by rememberSaveable { mutableStateOf(presets.getBoolean("session_comment", false)) }
    var blocked by remember { mutableStateOf(TimerController.lastBlockedApps(context)) }
    var showAppPicker by remember { mutableStateOf(false) }
    LaunchedEffect(intention, goalId, selectedTaskId, pomodoro, workMin, breakMin, dnd, phaseSound, blocked, lockscreenDetails, sessionComment) {
        presets.edit().putString("intention", intention).putLong("goal", goalId ?: -1).putBoolean("pomodoro", pomodoro)
            .putInt("work", workMin).putInt("break", breakMin).putBoolean("dnd", dnd).putBoolean("phase_sound", phaseSound)
            .putBoolean("lockscreen_details", lockscreenDetails).putBoolean("session_comment", sessionComment)
            .putLong("task_id", selectedTaskId ?: -1)
            .putStringSet("last_blocked_apps", blocked).apply()
    }
    val capabilities by produceState(initialValue = false to false) {
        while (true) {
            value = (isWebTrackingEnabled(context) && Capabilities.accessibilityServiceConnected(context)) to FocusDnd.hasAccess(context)
            delay(1000)
        }
    }
    val blockingReady = capabilities.first
    val dndReady = capabilities.second

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        OutlinedTextField(
            value = intention,
            onValueChange = { intention = it; selectedTaskId = null },
            placeholder = {
                Text("What are you focusing on?", style = MaterialTheme.typography.titleMedium)
            },
            textStyle = MaterialTheme.typography.titleMedium,
            singleLine = true,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        )

        if (suggestions.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Next up",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    suggestions.forEach { t ->
                        FilterChip(
                            selected = selectedTaskId == t.id,
                            onClick = {
                                intention = t.title
                                goalId = t.goalId
                                selectedTaskId = t.id
                            },
                            leadingIcon = { Icon(Icons.Filled.PlayArrow, null, Modifier.size(16.dp)) },
                            label = { Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
            }
        }

        if (goals.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Goal", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = goalId == null, onClick = { goalId = null; selectedTaskId = null }, label = { Text("None") })
                    goals.forEach { g ->
                        FilterChip(
                            selected = goalId == g.id,
                            onClick = { goalId = g.id; selectedTaskId = null },
                            leadingIcon = { Box(Modifier.size(10.dp).background(Color(g.color), CircleShape)) },
                            label = { Text(g.name) },
                        )
                    }
                }
            }
        }

        // The three session options live in one card so the form has structure, not floating rows.
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { showAppPicker = true }
                        .padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Block, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(14.dp))
                    Text(
                        if (blocked.isEmpty()) "Block distracting apps"
                        else "${blocked.size} app${if (blocked.size > 1) "s" else ""} blocked",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        if (blocked.isEmpty()) "Choose" else "Edit",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(38.dp))
                    Text("Pomodoro (${workMin}m / ${breakMin}m)", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = pomodoro, onCheckedChange = { pomodoro = it })
                }
                if (pomodoro) {
                    Column(Modifier.padding(start = 38.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Sound phase changes", Modifier.weight(1f))
                        Switch(checked = phaseSound, onCheckedChange = { phaseSound = it })
                    }
                }

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.NotificationsOff, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(14.dp))
                    Text("Do Not Disturb (priority exceptions)", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = dnd,
                        onCheckedChange = {
                            dnd = it
                            if (it && !FocusDnd.hasAccess(context)) FocusDnd.openSettings(context)
                        },
                    )
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Show session details on lock screen", Modifier.weight(1f))
                    Switch(checked = lockscreenDetails, onCheckedChange = { lockscreenDetails = it })
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Include Mentor encouragement", Modifier.weight(1f))
                    Switch(checked = sessionComment, onCheckedChange = { sessionComment = it })
                }
            }
        }

        if (blocked.isNotEmpty() && !blockingReady) {
            Text("App blocking needs Bibo Accessibility access. Website recording is optional.", color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { openAccessibilitySettings(context) }) { Text("Enable app blocking") }
        }
        if (dnd && !dndReady) {
            Text("Do Not Disturb access is required for this preset.", color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { FocusDnd.openSettings(context) }) { Text("Allow Do Not Disturb") }
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
                        taskId = selectedTaskId,
                    )
                )
            },
            enabled = intention.isNotBlank() && (blocked.isEmpty() || blockingReady) && (!dnd || dndReady),
            shape = MaterialTheme.shapes.large,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            Icon(Icons.Filled.PlayArrow, null)
            Spacer(Modifier.width(8.dp))
            Text(
                if (intention.isBlank()) "Type or pick what to focus on" else "Start focus",
                style = MaterialTheme.typography.titleMedium,
            )
        }
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
            if (!isWebTrackingEnabled(context) || !Capabilities.accessibilityServiceConnected(context)) {
                Text("App blocking requires Bibo Accessibility access. Grant it before starting focus.", modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
                TextButton(onClick = { openAccessibilitySettings(context) }, modifier = Modifier.padding(horizontal = 12.dp)) { Text("Enable app blocking") }
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
private fun ReflectionSheet(sessionId: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val context = LocalContext.current
    val drafts = remember { context.getSharedPreferences("bibo_reflection_drafts", android.content.Context.MODE_PRIVATE) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var note by rememberSaveable(sessionId) { mutableStateOf(drafts.getString(sessionId, "").orEmpty()) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
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
                onValueChange = { note = it; drafts.edit().putString(sessionId, it).apply() },
                label = { Text("A line of reflection (optional)") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { drafts.edit().remove(sessionId).apply(); onDismiss() }, modifier = Modifier.weight(1f)) { Text("Skip") }
                Button(onClick = { onSave(note.trim()) }, modifier = Modifier.weight(1f)) { Text("Save") }
            }
        }
    }
}

private fun focusLabel(ms: Long): String = if (ms < 60_000L) "0m" else formatDuration(ms)

@Composable
private fun FocusStat(value: String, label: String) {
    Column {
        Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

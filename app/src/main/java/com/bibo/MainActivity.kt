package com.bibo

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bibo.data.*
import com.bibo.ui.*
import com.bibo.ui.theme.BiboTheme
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class NavigationRequest(val tab: Int, val taskId: Long? = null, val goalId: Long? = null)

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_OPEN_TAB = "open_tab"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_GOAL_ID = "goal_id"
        const val TAB_TIMER = 2
        const val TAB_TASKS = 3
        const val TAB_MENTOR = 5
    }
    private val navigation = mutableStateOf<NavigationRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppStartup.start(applicationContext)
        if (savedInstanceState == null) route(intent)
        setContent { BiboTheme { BiboApp(navigation) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        route(intent)
    }

    private fun route(intent: Intent?) {
        val tab = intent?.getIntExtra(EXTRA_OPEN_TAB, -1) ?: -1
        if (tab in 0..5) navigation.value = NavigationRequest(tab,
            intent?.getLongExtra(EXTRA_TASK_ID, -1)?.takeIf { it > 0 },
            intent?.getLongExtra(EXTRA_GOAL_ID, -1)?.takeIf { it > 0 })
    }
}

private data class Tab(val label: String, val icon: ImageVector)
private val tabs = listOf(
    Tab("Calendar", Icons.Filled.CalendarMonth), Tab("Stats", Icons.Filled.BarChart),
    Tab("Focus", Icons.Filled.Timer), Tab("Tasks", Icons.Filled.Checklist),
    Tab("Daily", Icons.Filled.MonitorHeart), Tab("Mentor", Icons.AutoMirrored.Filled.Chat),
)

/** Local UI state survives tab changes; authoritative records stay in repositories. */
@Composable
private fun BiboApp(navigation: MutableState<NavigationRequest?>) {
    val context = LocalContext.current
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var linkedTask by rememberSaveable { mutableStateOf<Long?>(null) }
    var linkedGoal by rememberSaveable { mutableStateOf<Long?>(null) }
    val saved = rememberSaveableStateHolder()
    val session by TimerController.state.collectAsStateWithLifecycle()
    val timerError by TimerController.error.collectAsStateWithLifecycle()
    val appError by AppScope.error.collectAsStateWithLifecycle()
    val maintenance by BackupRecovery.maintenance.collectAsStateWithLifecycle()
    val recoveryOperation by BackupRecovery.operation.collectAsStateWithLifecycle()
    val recoveryBusy = recoveryOperation.busy
    val ready by AppStartup.ready.collectAsStateWithLifecycle()
    val startupError by AppStartup.error.collectAsStateWithLifecycle()

    LaunchedEffect(navigation.value, maintenance, recoveryBusy) {
        navigation.value?.takeIf { !maintenance && !recoveryBusy }?.let {
            selected = it.tab; linkedTask = it.taskId; linkedGoal = it.goalId
            settings = false; navigation.value = null
        }
    }
    LaunchedEffect(ready, maintenance, recoveryBusy) {
        if (ready && !maintenance && !recoveryBusy) AppScope.io.launch { TimerController.reconcile(context.applicationContext) }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (ready && !BackupRecovery.isMaintenance && !BackupRecovery.operation.value.busy) AppScope.io.launch {
            TimerController.initialize(context.applicationContext)
            runCatching { TimerController.reconcile(context.applicationContext) }
            TaskReminders.rescheduleAllAwait(context.applicationContext)
        }
    }
    BackHandler(settings && !maintenance && !recoveryBusy) { settings = false }
    LaunchedEffect(maintenance, recoveryBusy) { if (maintenance || recoveryBusy) settings = true }
    val openTask: (Long) -> Unit = { linkedTask = it; linkedGoal = null; selected = MainActivity.TAB_TASKS }
    val openGoal: (Long) -> Unit = { linkedGoal = it; linkedTask = null; selected = MainActivity.TAB_TASKS }
    val consumeLinks: () -> Unit = { linkedTask = null; linkedGoal = null }

    NavigationSuiteScaffold(navigationSuiteItems = {
        tabs.forEachIndexed { index, tab ->
            item(selected = selected == index && !settings, onClick = { if (!maintenance && !recoveryBusy) { selected = index; settings = false } },
                icon = { Icon(tab.icon, contentDescription = tab.label) }, label = { Text(tab.label) })
        }
    }) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            Row(Modifier.fillMaxWidth().height(40.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Bibo", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                TextButton(enabled = ready || startupError != null || maintenance, onClick = { settings = true }) {
                    Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp)); Text("Settings")
                }
            }
            if (!settings && !maintenance && !recoveryBusy && selected != MainActivity.TAB_TIMER) session?.let { running ->
                val seconds by produceState(0L, running.id) {
                    while (true) { value = (TimerController.elapsedMillis(context) / 1000).coerceAtLeast(0); delay(1_000) }
                }
                Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(running.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60), style = MaterialTheme.typography.labelLarge)
                        TextButton(onClick = { selected = MainActivity.TAB_TIMER }) { Text("Open") }
                        TextButton(onClick = { AppScope.io.launch { TimerController.stopSession(context.applicationContext, running.id) } }) { Text("Stop") }
                    }
                }
            }
            (timerError ?: appError)?.let { message ->
                Surface(color = MaterialTheme.colorScheme.errorContainer) {
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { TimerController.acknowledgeError(); AppScope.clearError() }) { Text("Dismiss") }
                    }
                }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                if (settings || maintenance || recoveryBusy) saved.SaveableStateProvider("settings") { SettingsScreen(onBack = { settings = false }) }
                else if (!ready) Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    if (startupError == null) { CircularProgressIndicator(); Spacer(Modifier.height(16.dp)); Text("Opening your local data…") }
                    else { Text(startupError.orEmpty()); TextButton(onClick = { AppStartup.start(context.applicationContext) }) { Text("Retry") } }
                }
                else if (maxWidth >= 720.dp && (selected == 0 || selected == MainActivity.TAB_TASKS)) {
                    Row(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxHeight()) { saved.SaveableStateProvider("calendar") { CalendarScreen(openGoal, openTask) } }
                        VerticalDivider()
                        Box(Modifier.weight(1f).fillMaxHeight()) { saved.SaveableStateProvider("tasks") { TodoScreen(linkedTask, linkedGoal, consumeLinks) } }
                    }
                } else when (selected) {
                    0 -> saved.SaveableStateProvider("calendar") { CalendarScreen(openGoal, openTask) }
                    1 -> saved.SaveableStateProvider("stats") { StatsScreen() }
                    2 -> saved.SaveableStateProvider("focus") { TimerScreen() }
                    3 -> saved.SaveableStateProvider("tasks") { TodoScreen(linkedTask, linkedGoal, consumeLinks) }
                    4 -> saved.SaveableStateProvider("daily") { HealthScreen() }
                    5 -> saved.SaveableStateProvider("mentor") { ChatScreen() }
                }
            }
        }
    }
}

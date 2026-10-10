package com.bibo.ui

import android.Manifest
import android.app.Activity
import android.net.Uri
import android.os.Build
import android.os.Process
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bibo.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    val connected by Capabilities.accessibilityConnection.collectAsStateWithLifecycle()
    val maintenance by BackupRecovery.maintenance.collectAsStateWithLifecycle()
    val recoveryOperation by BackupRecovery.operation.collectAsStateWithLifecycle()
    val startupReady by AppStartup.ready.collectAsStateWithLifecycle()
    val startupError by AppStartup.error.collectAsStateWithLifecycle()
    val recordingError by WebsiteTrackingSettings.error.collectAsStateWithLifecycle()
    var inspecting by remember { mutableStateOf(false) }
    val busy = inspecting || recoveryOperation.busy
    var message by remember { mutableStateOf<String?>(null) }
    var healthDialog by remember { mutableStateOf(false) }
    var exportDialog by remember { mutableStateOf(false) }
    var previousDialog by remember { mutableStateOf(false) }
    var importSource by remember { mutableStateOf<Uri?>(null) }
    var preview by remember { mutableStateOf<BackupPreview?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { revision++ }
    val capabilities = remember(revision, connected) { Capabilities.snapshot(context) }
    val healthStore = remember { HealthPreferenceStore(context) }
    val health = remember(revision, recoveryOperation.result, recoveryOperation.error) { healthStore.read() }
    val recording = remember(revision, recoveryOperation.result, recoveryOperation.error) { WebsiteTrackingSettings.enabled(context) }
    var repairLimit by remember { mutableIntStateOf(10) }
    val repairs by produceState<List<MigrationRepair>>(emptyList(), maintenance, startupReady, recoveryOperation.busy) {
        value = emptyList()
        if (!maintenance && startupReady && !recoveryOperation.busy) try {
            flow { emitAll(BiboDb.get(context.applicationContext).migrationRepairs().all()) }.flowOn(Dispatchers.IO).collect { value = it }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { message = "Upgrade repair history could not be read: ${failure.message}" }
    }
    fun runRecovery(action: suspend () -> BackupResult) {
        message = null
        scope.launch {
            // Recovery owns a finite critical section; leaving composition cannot cancel half a restore.
            try { withContext(NonCancellable) { action() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (recoveryOperation.busy) message = failure.message }
            finally { revision++ }
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) runRecovery { BackupRecovery.export(context.applicationContext, uri) }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            inspecting = true
            scope.launch {
                try { preview = BackupRecovery.inspectImport(context.applicationContext, uri); importSource = uri }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { message = "Backup was not accepted: ${failure.message}" }
                finally { inspecting = false }
            }
        }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { revision++ }
    fun request(capability: Capability) {
        val requested = when (capability) {
            Capability.CALENDAR -> arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
            Capability.MICROPHONE -> arrayOf(Manifest.permission.RECORD_AUDIO)
            Capability.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= 33 && !capabilities.state(capability).granted) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()
            else -> emptyArray()
        }
        if (requested.isNotEmpty() && !capabilities.state(capability).granted) permissions.launch(requested)
        else Capabilities.openSettings(context, capability).onFailure { message = "Settings could not be opened: ${it.message}" }
    }
    BackHandler(enabled = busy || maintenance) { message = if (busy) "Please wait for backup verification to finish." else "Close and reopen Bibo to finish recovery." }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Settings & recovery") }, navigationIcon = {
            IconButton(onClick = onBack, enabled = !busy && !maintenance) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Verifying and preserving local data…") }
            message?.let { Text(it) }
            recoveryOperation.result?.let { Text(it.message) }
            recoveryOperation.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (maintenance) {
                Text("Restart required", style = MaterialTheme.typography.titleLarge)
                Text("Local background work is paused. Close Bibo and open it again before using your data.")
                if (BackupRecovery.recoveryPending(context)) {
                    Text("An interrupted restore is pending. Bibo has retained recovery files and kept normal data access paused.")
                    OutlinedButton(enabled = !busy, onClick = { runRecovery { BackupRecovery.retryInterruptedRecovery(context.applicationContext) } }) { Text("Retry verified recovery") }
                }
                if (startupError != null && !startupReady) OutlinedButton(enabled = !busy, onClick = { open.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text("Inspect another backup") }
                Button(enabled = !busy, onClick = { (context as? Activity)?.finishAffinity(); Process.killProcess(Process.myPid()) }) { Text("Close Bibo") }
            } else {
                Text("Access & readiness", style = MaterialTheme.typography.titleLarge)
                Text("Saved local data stays available when a collection permission is disabled. Enable only the features you use.")
                capabilities.states.forEach { state ->
                    ListItem(headlineContent = { Text(capabilityLabel(state.capability)) }, supportingContent = { Text(state.detail) }, trailingContent = {
                        TextButton(enabled = !busy, onClick = { request(state.capability) }) { Text(if (state.healthy) "Manage" else "Set up") }
                    })
                }
                TextButton(enabled = !busy, onClick = { Capabilities.openSettings(context, Capability.CALENDAR).onFailure { message = it.message } }) { Text("Open Bibo permission settings") }
                Text("If Android no longer shows a grant dialog, enable access in Bibo's permission settings.", style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                Text("Website privacy", style = MaterialTheme.typography.titleLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Record website time"); Text("Optional supported-browser address sampling stays on this device. App blocking has its own controls.", style = MaterialTheme.typography.bodySmall) }
                    Switch(checked = recording, enabled = !busy, onCheckedChange = { WebsiteTrackingSettings.setEnabled(context, it); revision++ })
                }
                recordingError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text("Pause collection here; delete recorded visits in Screen time.", style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                Text("Health preferences", style = MaterialTheme.typography.titleLarge)
                Text(if (health.targetsEnabled) "Personal targets: ${health.calorieTarget} kcal, ${health.sugarTarget} g sugar, ${health.caffeineTarget} mg caffeine" else "Nutrition targets are hidden until you choose them.")
                TextButton(enabled = !busy, onClick = { healthDialog = true }) { Text("Choose targets & visible habits") }
                HorizontalDivider()
                MentorSettingsContent()
                if (repairs.isNotEmpty()) {
                    HorizontalDivider()
                    Text("Upgrade repairs", style = MaterialTheme.typography.titleLarge)
                    Text("The upgrade repaired ${repairs.size} task links so affected tasks remain reachable. Their previous links are preserved below. Review these tasks in Tasks if you want to organize them again.")
                    repairs.take(repairLimit).forEach { repair ->
                        val field = when (repair.field) { "parentId" -> "Parent task"; "goalId" -> "Goal"; else -> repair.field }
                        Text("Task ${repair.taskId}: $field ${repair.previousValue ?: "none"} → ${repair.newValue ?: "none"}. ${repair.reason}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (repairs.size > repairLimit) TextButton(onClick = { repairLimit += 10 }) { Text("Show 10 more repairs") }
                }
                HorizontalDivider()
                Text("Backup & recovery", style = MaterialTheme.typography.titleLarge)
                Text("Export saves a verified local database and selected preferences. It includes personal records, chat and memory. API keys and Android permission grants are excluded. Keep the file private; Bibo does not encrypt exported files.")
                Text("Export or restore ends an active session using confirmed work and pauses Bibo until restart. Restore replaces current local data after validation and retains a recovery snapshot. Calendar-provider records and Android permissions are managed outside this backup.", style = MaterialTheme.typography.bodySmall)
                Row {
                    OutlinedButton(enabled = !busy, onClick = { exportDialog = true }) { Text("Export") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(enabled = !busy, onClick = { open.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text("Inspect backup") }
                }
                if (BackupRecovery.previousBackupAvailable(context)) TextButton(enabled = !busy, onClick = { previousDialog = true }) { Text("Restore previous local snapshot") }
            }
        }
    }
    if (exportDialog) AlertDialog(onDismissRequest = { exportDialog = false }, title = { Text("Export local data?") }, text = { Text("Bibo will save confirmed work, pause background activity, and require restart after export. The archive includes private local records and excludes API keys.") },
        confirmButton = { TextButton(onClick = { exportDialog = false; export.launch("bibo-${AppTime.today()}.bibo.zip") }) { Text("Choose destination") } }, dismissButton = { TextButton(onClick = { exportDialog = false }) { Text("Cancel") } })
    val acceptedPreview = preview
    val acceptedSource = importSource
    if (acceptedPreview != null && acceptedSource != null) AlertDialog(onDismissRequest = { preview = null; importSource = null }, title = { Text("Restore verified backup?") }, text = {
        val preservation = if (!startupReady && startupError != null)
            "Bibo cannot fully open current data. Original database files and selected preferences will be retained locally for manual recovery; those files may not be a valid rollback backup."
        else "A previous verified snapshot is retained for recovery."
        Text("Validated schema ${acceptedPreview.schemaVersion}: ${acceptedPreview.tasks} tasks and ${acceptedPreview.blocks} activity records. This replaces current local data and selected preferences. $preservation Reconnect Mentor and restart afterward.")
    }, confirmButton = { TextButton(onClick = { preview = null; importSource = null; runRecovery { BackupRecovery.restore(context.applicationContext, acceptedSource, acceptedPreview.sourceDigest) } }) { Text("Replace local data") } }, dismissButton = { TextButton(onClick = { preview = null; importSource = null }) { Text("Cancel") } })
    if (previousDialog) AlertDialog(onDismissRequest = { previousDialog = false }, title = { Text("Restore previous snapshot?") }, text = { Text("The previous verified local snapshot will replace current data. Current data will become the next recovery snapshot. Restart and reconnect Mentor afterward.") }, confirmButton = { TextButton(onClick = { previousDialog = false; runRecovery { BackupRecovery.restorePrevious(context.applicationContext) } }) { Text("Restore") } }, dismissButton = { TextButton(onClick = { previousDialog = false }) { Text("Cancel") } })
    if (healthDialog) HealthPreferencesDialog(health, onDismiss = { healthDialog = false }, onSave = { healthStore.save(it); revision++; healthDialog = false })
}

private fun capabilityLabel(capability: Capability) = when (capability) {
    Capability.USAGE_ACCESS -> "Screen time collection"
    Capability.CALENDAR -> "Calendar integration"
    Capability.NOTIFICATIONS -> "Notifications"
    Capability.MICROPHONE -> "Voice input"
    Capability.ACCESSIBILITY -> "App blocking service"
    Capability.OVERLAY -> "Blocking overlay (optional)"
    Capability.DND -> "Do Not Disturb (optional)"
    Capability.LOCAL_SPEECH -> "Local voice recognition"
}

@Composable
private fun HealthPreferencesDialog(initial: HealthPreferences, onDismiss: () -> Unit, onSave: (HealthPreferences) -> Unit) {
    var enabled by remember { mutableStateOf(initial.targetsEnabled) }
    var calories by remember { mutableStateOf(initial.calorieTarget.toString()) }
    var sugar by remember { mutableStateOf(initial.sugarTarget.toString()) }
    var caffeine by remember { mutableStateOf(initial.caffeineTarget.toString()) }
    var mask by remember { mutableIntStateOf(initial.visibleHabitMask) }
    val valid = (calories.toIntOrNull() ?: 0) in 1..100_000 && (sugar.toIntOrNull() ?: 0) in 1..10_000 && (caffeine.toIntOrNull() ?: 0) in 1..10_000
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Personal health preferences") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Show chosen targets", Modifier.weight(1f)); Switch(enabled, { enabled = it }) }
            Text("Choose your own targets. These are display preferences, not medical recommendations.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(calories, { calories = it }, label = { Text("Calories (kcal)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(sugar, { sugar = it }, label = { Text("Sugar (g)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(caffeine, { caffeine = it }, label = { Text("Caffeine (mg)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Text("Visible daily habits", style = MaterialTheme.typography.titleSmall)
            HealthHabit.entries.forEach { habit ->
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(mask and habit.bit != 0, { mask = if (it) mask or habit.bit else mask and habit.bit.inv() }); Text(habit.label) }
            }
            if (!valid) Text("Enter positive whole numbers within the supported range.", color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(enabled = valid, onClick = { onSave(HealthPreferences(enabled, calories.toInt(), sugar.toInt(), caffeine.toInt(), mask)) }) { Text("Save") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

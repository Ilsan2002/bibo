package com.bibo.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bibo.data.MentorCheckin
import java.time.LocalTime
import kotlinx.coroutines.flow.MutableStateFlow

object MentorSettings {
    val revision = MutableStateFlow(0L)
    val categories = listOf("tasks", "goals", "calendar", "activity", "habits", "food", "usage", "rewards")
    private fun prefs(context: Context) = context.getSharedPreferences("mentor", Context.MODE_PRIVATE)
    fun changed() { revision.value += 1 }
    fun enabled(context: Context) = prefs(context).getBoolean("checkins_enabled", false)
    fun automaticCheers(context: Context) = prefs(context).getBoolean("automatic_cheers", false)
    fun shared(context: Context, category: String) = prefs(context).getBoolean("share_$category", false)
    fun cadenceHours(context: Context) = prefs(context).getInt("cadence_hours", 4).coerceIn(1, 24)
    fun quietStart(context: Context) = prefs(context).getInt("quiet_start", 20)
    fun quietEnd(context: Context) = prefs(context).getInt("quiet_end", 8)
    fun setBoolean(context: Context, name: String, value: Boolean) {
        prefs(context).edit().putBoolean(name, value).apply(); changed(); MentorCheckin.schedule(context)
    }
    fun setInt(context: Context, name: String, value: Int) { prefs(context).edit().putInt(name, value).apply(); changed() }
    fun inQuietHours(context: Context, time: LocalTime = LocalTime.now()): Boolean {
        val from = quietStart(context); val until = quietEnd(context)
        return if (from == until) false else if (from < until) time.hour >= from && time.hour < until else time.hour >= from || time.hour < until
    }
}

@Composable
fun MentorSettingsContent() {
    val context = LocalContext.current
    val revision by MentorSettings.revision.collectAsStateWithLifecycle()
    var showKey by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Mentor & privacy", style = MaterialTheme.typography.titleLarge)
        Text("Mentor sends your messages, selected records, and memory notes to OpenRouter and its model provider (GLM). These cloud requests can incur API charges. Your local tasks and timer work without Mentor.")
        Text(if (Mentor.isConnected(context)) "Connected to OpenRouter" else "Mentor is disconnected")
        Row {
            Button(onClick = { showKey = true }) { Text(if (Mentor.isConnected(context)) "Replace key" else "Connect") }
            if (Mentor.isConnected(context)) TextButton(onClick = { Mentor.disconnect(context) }) { Text("Disconnect") }
        }
        SettingSwitch("Automatic check-ins", MentorSettings.enabled(context)) { Mentor.setCheckInsEnabled(context, it) }
        SettingSwitch("Encouragement when starting a timer", MentorSettings.automaticCheers(context)) { Mentor.setEncouragementEnabled(context, it) }
        if (MentorSettings.enabled(context)) {
            Row { listOf(1, 4, 12, 24).forEach { hours -> TextButton(onClick = { MentorSettings.setInt(context, "cadence_hours", hours) }) { Text("${hours}h${if (MentorSettings.cadenceHours(context) == hours) " ✓" else ""}") } } }
            Text("Quiet hours: ${MentorSettings.quietStart(context)}:00–${MentorSettings.quietEnd(context)}:00")
            Row {
                TextButton(onClick = { MentorSettings.setInt(context, "quiet_start", (MentorSettings.quietStart(context) + 1) % 24) }) { Text("Change start") }
                TextButton(onClick = { MentorSettings.setInt(context, "quiet_end", (MentorSettings.quietEnd(context) + 1) % 24) }) { Text("Change end") }
            }
            Text("Check-ins run when Android and the network permit. Notification permission controls whether they can alert you.", style = MaterialTheme.typography.bodySmall)
        }
        Text("Records shared with Mentor", style = MaterialTheme.typography.titleMedium)
        Text("These categories control current record reads and changes. Chat, summaries, and memory remain part of a connected conversation and can contain previously shared information. Delete chat and memory history to erase that context.", style = MaterialTheme.typography.bodySmall)
        MentorSettings.categories.forEach { category -> SettingSwitch(category.replaceFirstChar { it.uppercase() }, MentorSettings.shared(context, category)) { MentorSettings.setBoolean(context, "share_$category", it) } }
        TextButton(onClick = { Mentor.clearHistory(context) }) { Text("Delete chat and memory history") }
        if (revision < 0) Text("")
    }
    if (showKey) MentorKeyDialog(onDismiss = { showKey = false })
}
@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth()) { Text(label, Modifier.weight(1f)); Switch(checked = checked, onCheckedChange = onChange) }
}
@Composable
fun MentorSettingsDialog(onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Mentor settings") }, text = { Column(Modifier.verticalScroll(rememberScrollState())) { MentorSettingsContent() } }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
}
@Composable
fun MentorKeyDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var key by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    var consent by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Connect OpenRouter") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Get a key at openrouter.ai/keys. Connecting permits cloud processing of your chat and memory. Automatic requests are off until enabled; choose shared data in Mentor settings. Keep the key private.")
            OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text("API key") }, singleLine = true, visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation())
            TextButton(onClick = { reveal = !reveal }) { Text(if (reveal) "Hide key" else "Show key") }
            Row { Checkbox(checked = consent, onCheckedChange = { consent = it }); Text("I agree to send my chat to these providers.") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = consent && key.trim().length > 10, onClick = { runCatching { Mentor.setApiKey(context, key) }.onSuccess { onDismiss() }.onFailure { error = "Could not store the key securely. Try again." } }) { Text("Connect") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

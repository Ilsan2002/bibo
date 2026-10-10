package com.bibo.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bibo.data.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun ChatScreen() {
    val context = LocalContext.current.applicationContext
    val db = remember { BiboDb.get(context) }
    var pageSize by remember { mutableIntStateOf(100) }
    val messageFlow = remember(pageSize) { db.mentorHistory().recent(pageSize) }
    val messages by messageFlow.collectAsStateWithLifecycle(emptyList())
    val turns by db.mentorTurns().all().collectAsStateWithLifecycle(emptyList())
    val commands by db.commands().all().collectAsStateWithLifecycle(emptyList())
    val settingsRevision by MentorSettings.revision.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showMemory by remember { mutableStateOf(false) }
    var showPersona by remember { mutableStateOf(false) }
    var showKey by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val today by produceState(LocalDate.now().toEpochDay()) { while (true) { value = LocalDate.now().toEpochDay(); delay(30_000) } }
    val busy = submitting || turns.any { it.status in setOf("QUEUED", "GENERATING", "APPLYING") }
    val connected = remember(settingsRevision) { Mentor.isConnected(context) }
    LaunchedEffect(Unit) { Mentor.recover(context) }
    fun submit() {
        if (input.isBlank() || busy || !connected) return
        val text = input.trim(); submitting = true
        AppScope.io.launch {
            runCatching { Mentor.enqueue(context, text) }.onSuccess { input = "" }.onFailure { localError = it.message ?: "Message could not be queued." }
            submitting = false
        }
    }
    val timeline = remember(messages, turns, commands) {
        buildList<Pair<Long, Any>> {
            messages.forEach { add(it.createdAt to it) }
            turns.filter { it.status != "DONE" }.forEach { add((it.createdAt + 1) to it) }
            commands.filter { it.kind.any(Char::isLowerCase) && !it.id.contains(":local") }.forEach { add(it.createdAt to it) }
        }.sortedByDescending { it.first }
    }
    LaunchedEffect(messages.firstOrNull()?.id) { if (listState.firstVisibleItemIndex <= 2 && timeline.isNotEmpty()) listState.animateScrollToItem(0) }
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Mentor", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { showMemory = true }) { Icon(Icons.Filled.Bookmarks, "Memory notes") }
            IconButton(onClick = { showPersona = true }) { Icon(Icons.Filled.Tune, "Coach tone") }
            IconButton(onClick = { showSettings = true }) { Icon(Icons.Filled.Settings, "Mentor settings") }
        }
        if (!connected) {
            Card(Modifier.padding(16.dp)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("An optional coach for your plans and progress", style = MaterialTheme.typography.titleMedium)
                Text("Your local tasks, calendar, and timer work without a connection. Mentor uses OpenRouter cloud processing; you choose which records to share and whether it can contact you automatically.")
                Button(onClick = { showKey = true }) { Text("Connect Mentor") }
            } }
        }
        LazyColumn(state = listState, reverseLayout = true, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(timeline.size, key = { index -> when (val row = timeline[index].second) { is ChatMessage -> "message:${row.id}"; is MentorTurn -> "turn:${row.id}"; is CommandRecord -> "command:${row.id}"; else -> "row:$index" } }) { index ->
                when (val row = timeline[index].second) {
                    is ChatMessage -> MessageBubble(row, today)
                    is MentorTurn -> TurnCard(row, onCancel = { Mentor.cancel(context, row.id) }, onRetry = { Mentor.retry(context, row.id) })
                    is CommandRecord -> ActionCard(row)
                }
            }
            if (messages.size >= pageSize) item { TextButton(onClick = { pageSize += 100 }) { Text("Load older messages") } }
            if (timeline.isEmpty()) item { Text("Choose a small next step, reflect on your day, or ask about a goal. Only the categories you enable in Mentor settings are shared.", Modifier.padding(16.dp)) }
        }
        localError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
        if (connected) Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(input, { input = it }, modifier = Modifier.weight(1f), placeholder = { Text("Message your mentor…") }, maxLines = 4, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { submit() }))
            HoldToTalkButton(compact = true, onTranscript = { input = (input.trim() + " " + it).trim() })
            IconButton(onClick = { submit() }, enabled = input.isNotBlank() && !busy) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
        }
    }
    if (showSettings) MentorSettingsDialog { showSettings = false }
    if (showKey) MentorKeyDialog { showKey = false }
    if (showMemory) MemoryDialog { showMemory = false }
    if (showPersona) PersonaDialog { showPersona = false }
}

@Composable
private fun MessageBubble(message: ChatMessage, today: Long) {
    val user = message.role == "USER"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Surface(color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(16.dp), modifier = Modifier.widthIn(max = 340.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text(message.content)
                if (message.epochDay != today) Text(LocalDate.ofEpochDay(message.epochDay).format(DateTimeFormatter.ofPattern("EEE, MMM d")), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
@Composable
private fun TurnCard(turn: MentorTurn, onCancel: () -> Unit, onRetry: () -> Unit) {
    val pending = turn.status in setOf("QUEUED", "GENERATING", "APPLYING")
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
        Text(when (turn.status) { "QUEUED" -> "Message queued"; "GENERATING" -> "Mentor is thinking…"; "APPLYING" -> "Applying a recorded action…"; "CANCELLED" -> "Message cancelled"; "INTERRUPTED" -> "Conversation interrupted"; else -> "Mentor could not finish" }, style = MaterialTheme.typography.titleSmall)
        turn.error?.let { Text(it) }
        if (pending) TextButton(onClick = onCancel) { Text("Cancel") } else TextButton(onClick = onRetry) { Text("Retry safely") }
    } }
}
@Composable
private fun ActionCard(record: CommandRecord) {
    val context = LocalContext.current.applicationContext
    var running by remember(record.id) { mutableStateOf(false) }
    var error by remember(record.id) { mutableStateOf<String?>(null) }
    val details = remember(record.result) { runCatching { JSONObject(record.result) }.getOrNull() }
    val scope = rememberCoroutineScope()
    fun act(block: suspend () -> Unit) {
        running = true
        AppScope.io.launch {
            try { block() } catch (e: Exception) { error = e.message ?: "Action could not be applied." } finally { running = false }
        }
    }
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(when (record.status) { "AWAITING_CONFIRMATION" -> "Review proposed change"; "APPLIED" -> "Change applied"; "UNDONE" -> "Change undone"; "EXISTS" -> "Already in your records"; "AMBIGUOUS", "PENDING" -> "Check the destination before retrying"; "CANCELLED" -> "Proposal dismissed"; else -> "Change could not be applied" }, style = MaterialTheme.typography.titleSmall)
        Text(details?.optString("message")?.ifBlank { null } ?: "This action has no confirmed outcome yet.")
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row {
            if (record.status == "AWAITING_CONFIRMATION") {
                TextButton(enabled = !running, onClick = { act { MentorTools.applyPending(context, record.id) } }) { Text("Apply") }
                TextButton(enabled = !running, onClick = { act { MentorTools.dismissPending(context, record.id) } }) { Text("Dismiss") }
            }
            if (record.status == "APPLIED" && (record.kind in setOf("create_task", "delete_task", "delete_goal") || record.kind == "complete_task" && (details?.optLong("completion_id", -1) ?: -1) > 0)) TextButton(enabled = !running, onClick = { act { MentorTools.undo(context, record.id) } }) { Text("Undo") }
        }
    } }
}
@Composable
private fun MemoryDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val db = remember { BiboDb.get(context) }
    val revisions by db.mentorMemory().all().collectAsStateWithLifecycle(emptyList())
    var text by remember { mutableStateOf(Mentor.memory(context)) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Memory notes") }, text = { Column {
        Text("Your edits are authoritative. Automatic summaries cannot replace them. Previous versions can be restored.")
        OutlinedTextField(text, { text = it.take(8000) }, minLines = 5, maxLines = 12)
        revisions.drop(1).take(3).forEach { revision -> TextButton(onClick = { text = revision.notes }) { Text("Restore version ${revision.id}") } }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    } }, confirmButton = { TextButton(enabled = !saving, onClick = { saving = true; scope.launch { try { Mentor.saveMemoryAwait(context, text); onDismiss() } catch (_: Exception) { error = "Notes could not be saved." } finally { saving = false } } }) { Text("Save") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
@Composable
private fun PersonaDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf(Mentor.persona(context)) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Coach tone") }, text = { Column {
        Text("Adjust the tone. App safeguards and data-sharing choices always apply.")
        OutlinedTextField(text, { text = it.take(4000) }, minLines = 6, maxLines = 12)
        TextButton(onClick = { text = Mentor.DEFAULT_PERSONA }) { Text("Reset") }
    } }, confirmButton = { TextButton(onClick = { Mentor.setPersona(context, text); onDismiss() }) { Text("Save") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

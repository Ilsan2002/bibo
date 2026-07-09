package com.bibo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bibo.data.BiboDb
import com.bibo.data.ChatMessage
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/** The Mentor tab: a continuous chat with a coach that sees everything Bibo logs. */
@Composable
fun ChatScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { BiboDb.get(context) }
    val messages by db.chat().all().collectAsStateWithLifecycle(emptyList())

    var hasKey by remember { mutableStateOf(Mentor.apiKey(context) != null) }
    var showKeyDialog by remember { mutableStateOf(false) }
    var showMemory by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }

    val today = remember { LocalDate.now().toEpochDay() }
    val listState = rememberLazyListState()

    fun submit(raw: String) {
        val text = raw.trim()
        if (text.isBlank() || sending || !hasKey) return
        input = ""
        sending = true
        scope.launch {
            Mentor.send(context, text)
            sending = false
        }
    }

    // Newest message is index 0 (reverseLayout) — snap there whenever one lands.
    LaunchedEffect(messages.size, sending) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(0)
    }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Mentor", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (hasKey) {
                IconButton(onClick = { showMemory = true }) {
                    Icon(Icons.Filled.Bookmarks, contentDescription = "What the mentor remembers")
                }
            }
            IconButton(onClick = { showKeyDialog = true }) {
                Icon(Icons.Filled.Key, contentDescription = "API key")
            }
        }

        if (!hasKey) {
            KeySetupCard(
                onSave = { key ->
                    Mentor.setApiKey(context, key)
                    hasKey = Mentor.apiKey(context) != null
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(24.dp),
            )
        } else {
            // Ascending messages interleaved with date headers, reversed for reverseLayout.
            val display = remember(messages, sending) {
                buildList {
                    var lastDay = -1L
                    messages.forEach { m ->
                        if (m.epochDay != lastDay) {
                            add(ChatRow.DateHeader(m.epochDay))
                            lastDay = m.epochDay
                        }
                        add(ChatRow.Msg(m))
                    }
                    if (sending) add(ChatRow.Typing)
                }.reversed()
            }

            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 12.dp, vertical = 8.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (display.isEmpty()) {
                    item { EmptyChatHint() }
                }
                items(display.size) { i ->
                    when (val row = display[i]) {
                        is ChatRow.DateHeader -> DateHeader(row.epochDay, today)
                        is ChatRow.Msg -> MessageBubble(row.message)
                        ChatRow.Typing -> TypingBubble()
                    }
                }
            }

            val todayHasChat = messages.any { it.epochDay == today && it.role == "USER" }
            if (!todayHasChat && !sending) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        "Reflect on my day",
                        "What should I focus on right now?",
                        "How am I doing on my goals?",
                    ).forEach { suggestion ->
                        AssistChip(onClick = { submit(suggestion) }, label = { Text(suggestion) })
                    }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("Message your mentor…") },
                    modifier = Modifier.weight(1f),
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit(input) }),
                )
                HoldToTalkButton(
                    onTranscript = { spoken ->
                        input = (input.trim() + " " + spoken).trim()
                    },
                )
                IconButton(onClick = { submit(input) }, enabled = input.isNotBlank() && !sending) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }

    if (showKeyDialog) {
        KeyDialog(
            initial = Mentor.apiKey(context).orEmpty(),
            onDismiss = { showKeyDialog = false },
            onSave = { key ->
                Mentor.setApiKey(context, key)
                hasKey = Mentor.apiKey(context) != null
                showKeyDialog = false
            },
        )
    }

    if (showMemory) {
        MemoryDialog(
            initial = Mentor.memory(context),
            onDismiss = { showMemory = false },
            onSave = { text ->
                Mentor.saveMemory(context, text)
                showMemory = false
            },
        )
    }
}

@Composable
private fun MemoryDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What your mentor remembers") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Durable facts your mentor carries across days. Edit or delete anything — " +
                        "it's rewritten and tidied each night.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("Nothing saved yet — facts appear here as you talk.") },
                    minLines = 6,
                    maxLines = 16,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private sealed interface ChatRow {
    data class Msg(val message: ChatMessage) : ChatRow
    data class DateHeader(val epochDay: Long) : ChatRow
    data object Typing : ChatRow
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == "USER"
    val isError = message.role == "ERROR"
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = when {
                isError -> MaterialTheme.colorScheme.errorContainer
                isUser -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceContainerHigh
            },
            shape = RoundedCornerShape(
                topStart = 18.dp, topEnd = 18.dp,
                bottomStart = if (isUser) 18.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 18.dp,
            ),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Text(
                message.content,
                style = MaterialTheme.typography.bodyLarge,
                color = when {
                    isError -> MaterialTheme.colorScheme.onErrorContainer
                    isUser -> MaterialTheme.colorScheme.onPrimaryContainer
                    else -> MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun TypingBubble() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(18.dp),
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Text("thinking…", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun DateHeader(epochDay: Long, today: Long) {
    val label = when (epochDay) {
        today -> "Today"
        today - 1 -> "Yesterday"
        else -> LocalDate.ofEpochDay(epochDay).format(DateTimeFormatter.ofPattern("EEE, MMM d"))
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 6.dp),
        )
    }
}

@Composable
private fun EmptyChatHint() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.Psychology,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Your mentor sees your goals, focus sessions, habits, food, and screen time — " +
                "and remembers your conversations from day to day. Say hi, or reflect on today.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun KeySetupCard(onSave: (String) -> Unit, modifier: Modifier = Modifier) {
    var key by remember { mutableStateOf("") }
    Box(modifier, contentAlignment = Alignment.Center) {
        Card {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Connect your mentor", style = MaterialTheme.typography.titleLarge)
                Text(
                    "The mentor runs on Claude. Paste an Anthropic API key " +
                        "(console.anthropic.com → API keys). It's stored only on this phone " +
                        "and used only for your mentor chats.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    placeholder = { Text("sk-ant-…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { onSave(key) },
                    enabled = key.trim().length > 10,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Save") }
            }
        }
    }
}

@Composable
private fun KeyDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var key by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Anthropic API key") },
        text = {
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                placeholder = { Text("sk-ant-…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(key) }, enabled = key.trim().length > 10) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

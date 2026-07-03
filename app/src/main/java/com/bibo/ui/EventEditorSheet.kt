@file:OptIn(ExperimentalMaterial3Api::class)

package com.bibo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val dateChipFmt = DateTimeFormatter.ofPattern("EEE, MMM d")
private val timeChipFmt = DateTimeFormatter.ofPattern("H:mm")

/**
 * Bottom sheet for creating/adjusting a time block: title, date, start/end
 * times via Material pickers, and quick duration chips.
 */
@Composable
fun EventEditorSheet(
    sheetTitle: String,
    initialDate: LocalDate,
    initialTitle: String = "",
    initialStart: LocalTime? = null,
    initialEnd: LocalTime? = null,
    saveLabel: String = "Save",
    onDismiss: () -> Unit,
    onSave: (LocalDate, LocalTime, LocalTime, String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var title by remember { mutableStateOf(initialTitle) }
    var date by remember { mutableStateOf(initialDate) }
    val defaultStart = initialStart ?: LocalTime.now().withMinute(0).withSecond(0)
    var start by remember { mutableStateOf(defaultStart) }
    var end by remember { mutableStateOf(initialEnd ?: safePlus(defaultStart, 60)) }
    var pickStart by remember { mutableStateOf(false) }
    var pickEnd by remember { mutableStateOf(false) }
    var pickDate by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(sheetTitle, style = MaterialTheme.typography.titleLarge)

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AssistChip(
                    onClick = { pickDate = true },
                    label = { Text(date.format(dateChipFmt)) },
                )
                AssistChip(
                    onClick = { pickStart = true },
                    label = { Text(start.format(timeChipFmt)) },
                )
                Text("–", style = MaterialTheme.typography.bodyLarge)
                AssistChip(
                    onClick = { pickEnd = true },
                    label = { Text(end.format(timeChipFmt)) },
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(30L to "30 min", 60L to "1 hour", 120L to "2 hours").forEach { (minutes, label) ->
                    SuggestionChip(
                        onClick = { end = safePlus(start, minutes) },
                        label = { Text(label) },
                    )
                }
            }

            Button(
                onClick = { onSave(date, start, end, title.trim()) },
                enabled = title.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(saveLabel) }
        }
    }

    if (pickStart) {
        TimePickDialog(
            initial = start,
            onDismiss = { pickStart = false },
            onConfirm = {
                start = it
                if (!end.isAfter(start)) end = safePlus(start, 60)
                pickStart = false
            },
        )
    }
    if (pickEnd) {
        TimePickDialog(
            initial = end,
            onDismiss = { pickEnd = false },
            onConfirm = {
                end = it
                pickEnd = false
            },
        )
    }
    if (pickDate) {
        val dpState = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    dpState.selectedDateMillis?.let {
                        date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                    pickDate = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { pickDate = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = dpState)
        }
    }
}

private fun safePlus(t: LocalTime, minutes: Long): LocalTime {
    val result = t.plusMinutes(minutes)
    return if (result.isAfter(t)) result else LocalTime.of(23, 59)
}

@Composable
private fun TimePickDialog(
    initial: LocalTime,
    onDismiss: () -> Unit,
    onConfirm: (LocalTime) -> Unit,
) {
    val state = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        is24Hour = true,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select time") },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onConfirm(LocalTime.of(state.hour, state.minute)) }) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

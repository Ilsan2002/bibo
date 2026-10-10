@file:OptIn(ExperimentalMaterial3Api::class)
package com.bibo.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bibo.data.WritableCalendar
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val dateChipFmt = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy")
private val timeChipFmt = DateTimeFormatter.ofPattern("H:mm")

data class CalendarEditorValue(
    val title: String,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val allDay: Boolean,
    val zone: ZoneId,
    val startOffset: ZoneOffset?,
    val endOffset: ZoneOffset?,
    val calendarId: Long?,
    val timeCorrectionAccepted: Boolean = true,
)

/** Date ranges stay intact; parsed text is a suggestion, never an implicit mutation. */
@Composable
fun EventEditorSheet(
    sheetTitle: String,
    initialDate: LocalDate,
    initialTitle: String = "",
    initialStart: LocalTime? = null,
    initialEnd: LocalTime? = null,
    initialEndDate: LocalDate? = null,
    initialAllDay: Boolean = false,
    initialZone: ZoneId = ZoneId.systemDefault(),
    initialStartOffset: ZoneOffset? = null,
    initialEndOffset: ZoneOffset? = null,
    initialCalendarId: Long? = null,
    destinations: List<WritableCalendar> = emptyList(),
    allowDestinationChange: Boolean = true,
    destinationLocked: Boolean = false,
    lockedCalendarId: Long? = null,
    allowAllDay: Boolean = true,
    isSaving: Boolean = false,
    error: String? = null,
    requiresTimeCorrection: Boolean = false,
    initialTimeCorrectionAccepted: Boolean = false,
    onDraftChange: (CalendarEditorValue) -> Unit = {},
    saveLabel: String = "Save",
    onDismiss: () -> Unit,
    onSave: (CalendarEditorValue) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var title by rememberSaveable { mutableStateOf(initialTitle) }
    var startDay by rememberSaveable { mutableStateOf(initialDate.toEpochDay()) }
    val defaultStart = initialStart ?: LocalTime.now(initialZone).truncatedTo(ChronoUnit.MINUTES)
    val defaultEnd = initialDate.atTime(defaultStart).plusHours(1)
    var endDay by rememberSaveable { mutableStateOf((initialEndDate ?: defaultEnd.toLocalDate()).toEpochDay()) }
    var startNanos by rememberSaveable { mutableStateOf(defaultStart.toNanoOfDay()) }
    var endNanos by rememberSaveable { mutableStateOf((initialEnd ?: defaultEnd.toLocalTime()).toNanoOfDay()) }
    var allDay by rememberSaveable { mutableStateOf(initialAllDay) }
    var calendarId by rememberSaveable { mutableStateOf(initialCalendarId) }
    var startOffsetSeconds by rememberSaveable { mutableStateOf(initialStartOffset?.totalSeconds) }
    var endOffsetSeconds by rememberSaveable { mutableStateOf(initialEndOffset?.totalSeconds) }
    var pickTime by remember { mutableStateOf<String?>(null) }
    var pickDate by remember { mutableStateOf<String?>(null) }
    var timeCorrected by rememberSaveable { mutableStateOf(!requiresTimeCorrection || initialTimeCorrectionAccepted) }
    var destinationMenu by remember { mutableStateOf(false) }
    var confirmClosePending by remember { mutableStateOf(false) }
    val pendingDestination = if (lockedCalendarId == null) "Bibo only" else destinations.firstOrNull { it.id == lockedCalendarId }?.label ?: "Device calendar #$lockedCalendarId"
    val startDate = LocalDate.ofEpochDay(startDay)
    val endDate = LocalDate.ofEpochDay(endDay)
    val startTime = LocalTime.ofNanoOfDay(startNanos)
    val endTime = LocalTime.ofNanoOfDay(endNanos)
    val startDateTime = startDate.atTime(startTime)
    val endDateTime = endDate.atTime(endTime)
    val startOffset = startOffsetSeconds?.let(ZoneOffset::ofTotalSeconds)
    val endOffset = endOffsetSeconds?.let(ZoneOffset::ofTotalSeconds)
    val intervalResult = if (allDay) CalendarTime.allDay(startDate, endDate.plusDays(1))
        else CalendarTime.timed(startDateTime, endDateTime, initialZone, startOffset, endOffset)
    val suggestion = remember(title) { runCatching { extractActivity(title, bias = TimeBias.PLAN) }.getOrNull() }

    LaunchedEffect(title, startDay, endDay, startNanos, endNanos, allDay, calendarId, startOffsetSeconds, endOffsetSeconds, timeCorrected) {
        onDraftChange(CalendarEditorValue(title, if (allDay) startDate.atStartOfDay() else startDateTime,
            if (allDay) endDate.plusDays(1).atStartOfDay() else endDateTime, allDay, initialZone, startOffset, endOffset, calendarId, timeCorrected))
    }

    ModalBottomSheet(onDismissRequest = { if (!isSaving) { if (destinationLocked) confirmClosePending = true else onDismiss() } }, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(sheetTitle, style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(value = title, onValueChange = { title = it }, enabled = !isSaving,
                label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Times in the title are suggestions. Apply them when ready.") })
            if (suggestion?.start != null && suggestion.end != null) {
                AssistChip(enabled = !isSaving, onClick = {
                    startNanos = suggestion.start.toNanoOfDay()
                    endNanos = suggestion.end.toNanoOfDay()
                    endDay = startDay + (suggestion.endDayOffset - suggestion.startDayOffset)
                    startOffsetSeconds = null; endOffsetSeconds = null; timeCorrected = true
                    title = suggestion.title.ifBlank { title }
                }, label = { Text("Apply ${suggestion.start.format(timeChipFmt)}–${suggestion.end.format(timeChipFmt)}") })
            }
            if (destinationLocked) {
                Text("Pending save to $pendingDestination. Retry keeps the same destination and avoids creating a second entry.", style = MaterialTheme.typography.bodySmall)
                TextButton(enabled = !isSaving, onClick = { confirmClosePending = true }) { Text("Close pending draft") }
            } else if (allowDestinationChange) {
                Box {
                    OutlinedButton(enabled = !isSaving, onClick = { destinationMenu = true }) {
                        Text("Save to: " + (destinations.firstOrNull { it.id == calendarId }?.label ?: "Bibo only"))
                    }
                    DropdownMenu(expanded = destinationMenu, onDismissRequest = { destinationMenu = false }) {
                        DropdownMenuItem(text = { Text("Bibo only · local plan") }, onClick = { calendarId = null; destinationMenu = false })
                        destinations.forEach { destination ->
                            DropdownMenuItem(text = { Text(destination.label) }, onClick = { calendarId = destination.id; destinationMenu = false })
                        }
                    }
                }
                Text(if (calendarId == null) "This plan stays in Bibo. It is not synced to your device calendar." else "This appointment will be saved to the selected device calendar.", style = MaterialTheme.typography.bodySmall)
            }
            if (allowAllDay) Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = allDay, enabled = !isSaving, onCheckedChange = { allDay = it; if (endDay < startDay) endDay = startDay })
                Text("All day")
            }
            Text(if (allDay) "First day" else "Start", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                AssistChip(enabled = !isSaving, onClick = { pickDate = "start" }, label = { Text(startDate.format(dateChipFmt)) })
                if (!allDay) AssistChip(enabled = !isSaving, onClick = { pickTime = "start" }, label = { Text(startTime.format(timeChipFmt)) })
            }
            Text(if (allDay) "Last day (included)" else "End", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                AssistChip(enabled = !isSaving, onClick = { pickDate = "end" }, label = { Text(endDate.format(dateChipFmt)) })
                if (!allDay) AssistChip(enabled = !isSaving, onClick = { pickTime = "end" }, label = { Text(endTime.format(timeChipFmt)) })
            }
            if (!allDay) {
                Text("Timezone: ${initialZone.id}", style = MaterialTheme.typography.bodySmall)
                OffsetChoice("Start", startDateTime, initialZone, startOffsetSeconds) { startOffsetSeconds = it }
                OffsetChoice("End", endDateTime, initialZone, endOffsetSeconds) { endOffsetSeconds = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(30L to "30 min", 60L to "1 hour", 120L to "2 hours").forEach { (minutes, label) ->
                        SuggestionChip(enabled = !isSaving, onClick = {
                            val startOffsets = initialZone.rules.getValidOffsets(startDateTime)
                            val offset = startOffset?.takeIf { it in startOffsets } ?: startOffsets.singleOrNull()
                            if (offset != null) {
                                val end = startDateTime.toInstant(offset).plusSeconds(minutes * 60).atZone(initialZone)
                                endDay = end.toLocalDate().toEpochDay(); endNanos = end.toLocalTime().toNanoOfDay(); endOffsetSeconds = end.offset.totalSeconds; timeCorrected = true
                            }
                        }, label = { Text(label) })
                    }
                }
            }
            when (intervalResult) {
                is CalendarTimeResult.Valid -> Text(if (allDay) "${endDay - startDay + 1} day(s) · all day" else CalendarTime.elapsedLabel(intervalResult.interval.startMillis, intervalResult.interval.endMillis) + if (endDay != startDay) " · ends ${endDate.format(dateChipFmt)}" else "", style = MaterialTheme.typography.bodyMedium)
                is CalendarTimeResult.Invalid -> Text(intervalResult.message, color = MaterialTheme.colorScheme.error)
            }
            if (!timeCorrected) Text("Check and apply the activity date or time before saving this voice log.", color = MaterialTheme.colorScheme.error)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(enabled = title.isNotBlank() && intervalResult is CalendarTimeResult.Valid && timeCorrected && !isSaving,
                onClick = { onSave(CalendarEditorValue(title.trim(), if (allDay) startDate.atStartOfDay() else startDateTime,
                    if (allDay) endDate.plusDays(1).atStartOfDay() else endDateTime, allDay, initialZone, startOffset, endOffset, calendarId, timeCorrected)) },
                modifier = Modifier.fillMaxWidth()) { Text(if (isSaving) "Saving…" else saveLabel) }
        }
    }
    if (confirmClosePending) AlertDialog(onDismissRequest = { confirmClosePending = false }, title = { Text("Close pending draft?") },
        text = { Text("The save may already exist in $pendingDestination. This draft lets you retry the same save. Check that destination before closing this draft and making a new entry, so you do not create a duplicate.") },
        confirmButton = { TextButton(onClick = { confirmClosePending = false; onDismiss() }) { Text("Close draft") } },
        dismissButton = { TextButton(onClick = { confirmClosePending = false }) { Text("Keep draft") } })
    pickTime?.let { which ->
        TimePickDialog(initial = if (which == "start") startTime else endTime, onDismiss = { pickTime = null }, onConfirm = { time ->
            if (which == "start") { startNanos = time.toNanoOfDay(); startOffsetSeconds = null }
            else { endNanos = time.toNanoOfDay(); endOffsetSeconds = null }
            timeCorrected = true; pickTime = null
        })
    }
    pickDate?.let { which ->
        val date = if (which == "start") startDate else endDate
        val state = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest = { pickDate = null }, confirmButton = { TextButton(onClick = {
            state.selectedDateMillis?.let { value ->
                val day = Instant.ofEpochMilli(value).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
                if (which == "start") { startDay = day; startOffsetSeconds = null }
                else { endDay = day; endOffsetSeconds = null }
            }; timeCorrected = true; pickDate = null
        }) { Text("Apply date") } }, dismissButton = { TextButton(onClick = { pickDate = null }) { Text("Cancel") } }) { DatePicker(state = state) }
    }
}

@Composable
private fun OffsetChoice(label: String, time: LocalDateTime, zone: ZoneId, selected: Int?, onSelect: (Int) -> Unit) {
    val offsets = zone.rules.getValidOffsets(time)
    if (offsets.size < 2) return
    Text("$label time occurs twice because daylight saving time ends.", style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        offsets.forEachIndexed { index, offset ->
            FilterChip(selected = selected == offset.totalSeconds, onClick = { onSelect(offset.totalSeconds) },
                label = { Text("${if (index == 0) "First" else "Second"} · $offset") })
        }
    }
}

@Composable
private fun TimePickDialog(initial: LocalTime, onDismiss: () -> Unit, onConfirm: (LocalTime) -> Unit) {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Select time") }, text = { TimePicker(state = state) },
        confirmButton = { TextButton(onClick = { onConfirm(LocalTime.of(state.hour, state.minute)) }) { Text("Apply time") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

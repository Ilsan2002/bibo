@file:OptIn(ExperimentalMaterial3Api::class)
package com.bibo.ui

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import com.bibo.data.*
import de.tobiasschuerg.weekview.compose.WeekViewActions
import de.tobiasschuerg.weekview.compose.WeekViewCompose
import de.tobiasschuerg.weekview.compose.style.WeekViewStyle
import de.tobiasschuerg.weekview.compose.style.defaultWeekViewColors
import de.tobiasschuerg.weekview.compose.style.defaultWeekViewStyle
import de.tobiasschuerg.weekview.data.EventConfig
import de.tobiasschuerg.weekview.data.WeekViewConfig
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PAGER_CENTER = 5000
private val monthFmt = DateTimeFormatter.ofPattern("MMM yyyy")
private val detailDateFmt = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy")
private val detailTimeFmt = DateTimeFormatter.ofPattern("H:mm")

private fun Context.viewModelOwner(): ViewModelStoreOwner? = when (this) {
    is ViewModelStoreOwner -> this
    is ContextWrapper -> baseContext.viewModelOwner()
    else -> null
}

@Composable
fun CalendarScreen(onGoalClick: (Long) -> Unit = {}, onTaskClick: (Long) -> Unit = {}) {
    val context = LocalContext.current
    val vm = remember(context) {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T = CalendarViewModel(context.applicationContext, extras.createSavedStateHandle()) as T
        }
        val owner = requireNotNull(context.viewModelOwner()) { "CalendarScreen requires an Activity ViewModel and saved-state owner." }
        ViewModelProvider(owner, factory)[CalendarViewModel::class.java]
    }
    val db = vm.db
    val scope = rememberCoroutineScope()
    val voiceParser = remember { SmartVoiceParser(context.applicationContext) }
    LaunchedEffect(voiceParser) { withContext(Dispatchers.IO) { voiceParser.warmUp() } }
    val dayContext by AppTime.days.collectAsStateWithLifecycle(initialValue = DayContext(AppTime.today(), AppTime.zone()))
    val zone = dayContext.zone
    val today = dayContext.date
    val darkTheme = isSystemInDarkTheme()
    var daysVisible by rememberSaveable { mutableIntStateOf(1) }
    var anchorEpochDay by rememberSaveable { mutableLongStateOf(today.toEpochDay()) }
    var pagerReset by rememberSaveable { mutableIntStateOf(0) }
    var scaling by rememberSaveable { mutableFloatStateOf(1f) }
    var agenda by rememberSaveable { mutableStateOf(false) }
    var showPlans by rememberSaveable { mutableStateOf(true) }
    var showActivity by rememberSaveable { mutableStateOf(true) }
    var showUsage by rememberSaveable { mutableStateOf(false) }
    var visibleEpochDay by rememberSaveable { mutableLongStateOf(anchorEpochDay) }
    var hasCalRead by remember { mutableStateOf(vm.calendar.hasReadPermission()) }
    var hasCalWrite by remember { mutableStateOf(vm.calendar.hasWritePermission()) }
    var hasUsagePermission by remember { mutableStateOf(vm.usage.hasPermission()) }
    var refresh by remember { mutableIntStateOf(0) }
    var voiceReference by remember { mutableStateOf(Instant.ofEpochMilli(AppTime.nowMillis()).atZone(zone)) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasCalRead = vm.calendar.hasReadPermission(); hasCalWrite = vm.calendar.hasWritePermission(); refresh++
    }
    fun requestCalendarAccess() { permissionLauncher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasCalRead = vm.calendar.hasReadPermission(); hasCalWrite = vm.calendar.hasWritePermission()
        hasUsagePermission = vm.usage.hasPermission(); refresh++
    }
    val goals by remember(db) { db.goals().all() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val goalById = remember(goals) { goals.associateBy { it.id } }
    val dotWindowStart = visibleEpochDay - 7
    val dotWindowEnd = visibleEpochDay + daysVisible + 7
    val dueTasks by remember(db, dotWindowStart, dotWindowEnd) { db.todos().dueInRange(dotWindowStart, dotWindowEnd) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val milestones by remember(db, dotWindowStart, dotWindowEnd) { db.goals().milestonesInRange(dotWindowStart, dotWindowEnd) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val dotsByDay = remember(dueTasks, milestones, goalById) {
        val colors = HashMap<Long, LinkedHashSet<Int>>()
        dueTasks.forEach { task -> task.dueEpochDay?.let { day -> task.goalId?.let(goalById::get)?.color?.let { color -> colors.getOrPut(day) { LinkedHashSet() }.add(color) } } }
        milestones.forEach { goal -> goal.targetDate?.let { day -> colors.getOrPut(day) { LinkedHashSet() }.add(goal.color) } }
        colors.mapValues { it.value.toList() }
    }
    val destinationsResult by produceState<CalendarReadResult<List<WritableCalendar>>?>(null, hasCalRead, hasCalWrite, refresh) {
        value = withContext(Dispatchers.IO) { vm.calendar.writableCalendars() }
    }
    val destinations = if (hasCalWrite) (destinationsResult as? CalendarResult.Success)?.value.orEmpty() else emptyList()
    val editor by vm.editor.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()
    val operation by vm.operation.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(operation.notice) { operation.notice?.let { vm.clearNotice(); snackbar.showSnackbar(it) } }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            key(daysVisible, anchorEpochDay, pagerReset) {
                val pager = rememberPagerState(initialPage = PAGER_CENTER) { PAGER_CENTER * 2 }
                val visibleStart = LocalDate.ofEpochDay(anchorEpochDay + (pager.currentPage - PAGER_CENTER).toLong() * daysVisible)
                LaunchedEffect(visibleStart) { visibleEpochDay = visibleStart.toEpochDay() }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(visibleStart.format(monthFmt), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    FilledTonalIconButton(onClick = { anchorEpochDay = today.toEpochDay(); pagerReset++ }) { Icon(Icons.Default.Today, "Go to today") }
                    TextButton(onClick = { anchorEpochDay = visibleStart.toEpochDay(); daysVisible = if (daysVisible == 1) 3 else 1; pagerReset++ }) { Text(if (daysVisible == 1) "3 days" else "Day") }
                }
                DateStrip(visibleStart, daysVisible, today, dotsByDay) { date -> anchorEpochDay = date.toEpochDay(); pagerReset++ }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = showPlans, onClick = { showPlans = !showPlans }, label = { Text("Plans") })
                    FilterChip(selected = showActivity, onClick = { showActivity = !showActivity }, label = { Text("Activity") })
                    FilterChip(selected = showUsage, onClick = { showUsage = !showUsage }, label = { Text("App usage") })
                    FilterChip(selected = agenda, onClick = { agenda = !agenda }, label = { Text("Agenda list") })
                }
                Text("Plans outlined · activity filled · app usage translucent", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 12.dp))
                if (showPlans && (!hasCalRead || !hasCalWrite)) {
                    SourceStatus(if (!hasCalRead) "Device calendar is disconnected. Local plans still work." else "Device calendar is readable. Enable write access to save appointments there.", "Enable calendar") { requestCalendarAccess() }
                }
                if (showUsage && !hasUsagePermission) {
                    SourceStatus("Usage access is off. Saved history remains available; live usage will not update.", "Usage settings") {
                        runCatching { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }.onFailure { vm.showError("Usage settings are unavailable on this device.") }
                    }
                }
                Box(Modifier.weight(1f)) {
                    HorizontalPager(state = pager, beyondViewportPageCount = 1, modifier = Modifier.fillMaxSize()) { page ->
                        val startDate = LocalDate.ofEpochDay(anchorEpochDay + (page - PAGER_CENTER).toLong() * daysVisible)
                        TimelinePage(startDate, daysVisible, vm, hasCalRead, refresh, scaling, { scaling = it }, zone, darkTheme,
                            showPlans, showActivity, showUsage, agenda, onItemClick = vm::showDetail)
                    }
                    HoldToTalkButton(onTranscript = { transcript ->
                        scope.launch {
                            try {
                                val now = voiceReference
                                val parsed = withContext(Dispatchers.IO) { voiceParser.parse(transcript, referenceDate = now.toLocalDate(), now = now.toLocalTime()) }
                                vm.beginEdit(CalendarEditRequest("Log activity", parsed.title, parsed.start, parsed.end, parsed.date, "VOICE", parsed.endDate, zone = zone, warning = parsed.warnings.joinToString("\n").takeIf { it.isNotBlank() }, requiresTimeCorrection = parsed.requiresCorrection))
                            } catch (_: Exception) { vm.showError("Could not read the activity. Add it manually or try speaking again.") }
                        }
                    }, enabled = !operation.saving && editor == null, onRecordingStart = { voiceReference = Instant.ofEpochMilli(AppTime.nowMillis()).atZone(zone) }, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp))
                    FloatingActionButton(onClick = { vm.beginEdit(CalendarEditRequest("New plan", "", null, null, visibleStart, "PLAN", zone = zone)) },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)) { Icon(Icons.Default.Add, "Add plan") }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
    editor?.let { request ->
        key(request) {
            EventEditorSheet(sheetTitle = request.sheetTitle, initialDate = draft?.start?.toLocalDate() ?: request.date, initialTitle = draft?.title ?: request.title,
                initialStart = draft?.start?.toLocalTime() ?: request.start, initialEnd = draft?.end?.toLocalTime() ?: request.end, initialEndDate = draft?.let { if (it.allDay) it.end.toLocalDate().minusDays(1) else it.end.toLocalDate() } ?: request.endDate,
                initialAllDay = draft?.allDay ?: request.allDay, initialZone = request.zone, initialStartOffset = if (draft != null) draft?.startOffset else request.startOffset,
                initialEndOffset = if (draft != null) draft?.endOffset else request.endOffset, initialCalendarId = draft?.calendarId ?: (request.editItem as? CalendarItem.Device)?.event?.calendarId,
                destinations = destinations, allowDestinationChange = request.editItem == null && request.source == "PLAN",
                destinationLocked = request.destinationLocked, lockedCalendarId = request.lockedCalendarId,
                allowAllDay = request.source == "PLAN" || request.editItem is CalendarItem.Device || (request.editItem as? CalendarItem.Block)?.block?.source in setOf("PLAN", "MANUAL"),
                isSaving = operation.saving, error = operation.error ?: request.warning, requiresTimeCorrection = request.requiresTimeCorrection, initialTimeCorrectionAccepted = draft?.timeCorrectionAccepted == true, onDraftChange = vm::updateDraft, onDismiss = vm::dismissEditor, onSave = vm::save)
        }
    }
    detail?.let { item ->
        key(item.identity) {
            ItemDetailSheet(item, zone, operation, goalById[(item as? CalendarItem.Block)?.block?.goalId]?.name,
                onDismiss = vm::dismissDetail, onEdit = { editRequestFor(item, zone)?.let(vm::beginEdit) }, onDelete = { vm.delete(item) },
                onOpen = {
                    if (item is CalendarItem.Device) runCatching { context.startActivity(vm.calendar.viewEventIntent(item.event.id, item.event.begin, item.event.end)) }
                        .onFailure { vm.showError("No calendar app can open this event. Install or enable your calendar app.") }
                }, onGoalClick = onGoalClick, onTaskClick = onTaskClick)
        }
    }
    if (operation.error != null && editor == null && detail == null) {
        AlertDialog(onDismissRequest = vm::clearError, title = { Text("Could not finish") }, text = { Text(operation.error.orEmpty()) },
            confirmButton = { TextButton(onClick = vm::clearError) { Text("OK") } })
    }
}

@Composable
private fun SourceStatus(message: String, action: String, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun DateStrip(center: LocalDate, count: Int, today: LocalDate, dots: Map<Long, List<Int>>, onPick: (LocalDate) -> Unit) {
    val locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        (-3..3).forEach { offset ->
            val date = center.plusDays(offset.toLong())
            val selected = date >= center && date < center.plusDays(count.toLong())
            Column(Modifier.clip(RoundedCornerShape(14.dp)).background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                .clickable(role = Role.Button, onClickLabel = "Show ${date.format(detailDateFmt)}") { onPick(date) }.padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale), style = MaterialTheme.typography.labelSmall)
                Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = if (selected || date == today) FontWeight.Bold else FontWeight.Normal)
                Row(Modifier.height(8.dp).padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    dots[date.toEpochDay()].orEmpty().take(4).forEach { color -> Box(Modifier.size(5.dp).clip(CircleShape).background(Color(color))) }
                }
            }
        }
    }
}

@Composable
private fun biboWeekStyle(): WeekViewStyle = defaultWeekViewStyle(defaultWeekViewColors(
    todayHighlight = MaterialTheme.colorScheme.primary.copy(alpha = .05f), nowIndicator = MaterialTheme.colorScheme.error,
    dayHeaderText = MaterialTheme.colorScheme.onSurfaceVariant, timeLabelTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
    gridLineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f),
    currentDayBackground = MaterialTheme.colorScheme.primaryContainer, currentDayText = MaterialTheme.colorScheme.onPrimaryContainer))

@Composable
private fun TimelinePage(startDate: LocalDate, days: Int, vm: CalendarViewModel, hasCalRead: Boolean, refresh: Int,
                         scaling: Float, onScalingChange: (Float) -> Unit, zone: ZoneId, dark: Boolean,
                         showPlans: Boolean, showActivity: Boolean, showUsage: Boolean, agenda: Boolean, onItemClick: (CalendarItem) -> Unit) {
    val windowStart = startDate.atStartOfDay(zone).toInstant().toEpochMilli()
    val windowEnd = startDate.plusDays(days.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
    val blocks by remember(windowStart, windowEnd, vm) { vm.db.activityBlocks().blocksIn(windowStart - 86400_000, windowEnd + 86400_000) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val deviceFlow = remember(windowStart, windowEnd, hasCalRead, refresh, showPlans, vm) {
        if (hasCalRead && showPlans) vm.calendar.observeInstances(windowStart, windowEnd) else flowOf(CalendarResult.Success(emptyList<DeviceCalendarEvent>()))
    }
    val deviceResult by deviceFlow.collectAsStateWithLifecycle(initialValue = null)
    val deviceEvents = (deviceResult as? CalendarResult.Success)?.value.orEmpty()
    var usageError by remember { mutableStateOf<String?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(showUsage, lifecycle, refresh) {
        if (showUsage) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val result = withContext(Dispatchers.IO) { vm.usage.refresh() }
                usageError = result.error?.let { "Usage history may be stale. Refresh failed: $it" }
                delay(30_000)
            }
        }
    }
    val sessions by remember(windowStart, windowEnd, showUsage, refresh, vm) { if (showUsage) vm.usage.observeSessions(windowStart, windowEnd) else flowOf(emptyList<UsageSession>()) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val selectedBlocks = remember(blocks, showPlans, showActivity) { blocks.filter { (if (it.source in setOf("PLAN", "MANUAL")) showPlans else showActivity) } }
    val built = remember(startDate, days, selectedBlocks, deviceEvents, sessions, dark, zone, showPlans) {
        buildCalendar(startDate, days, selectedBlocks, if (showPlans) deviceEvents else emptyList(), sessions, dark, zone)
    }
    Column(Modifier.fillMaxSize()) {
        usageError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
        if (deviceResult == null && hasCalRead && showPlans) LinearProgressIndicator(Modifier.fillMaxWidth())
        (deviceResult as? CalendarResult.Failure)?.let { Text(it.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
        if (!showPlans && !showActivity && !showUsage) Text("Choose a source above to see plans or activity.", Modifier.padding(20.dp))
        if (agenda) {
            val agendaItems = built.items.values.distinctBy { it.identity }.sortedWith(compareBy<CalendarItem> { !it.isAllDay }.thenBy { it.startMillis }.thenBy { it.identity })
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
                if (agendaItems.isEmpty()) item { Text("No items in this date range.", Modifier.padding(20.dp)) }
                items(agendaItems, key = { it.identity }) { item ->
                    ListItem(headlineContent = { Text(item.title) }, supportingContent = { Text(item.sourceDescription + "\n" + itemTimeDescription(item, zone)) },
                        modifier = Modifier.clickable(role = Role.Button, onClickLabel = "Details for ${item.title}") { onItemClick(item) })
                    HorizontalDivider()
                }
            }
        } else {
            val now = Instant.ofEpochMilli(AppTime.nowMillis()).atZone(zone)
            val containsToday = now.toLocalDate() >= startDate && now.toLocalDate() < startDate.plusDays(days.toLong())
            WeekViewCompose(built.weekData, WeekViewConfig(scalingFactor = scaling, showCurrentTimeIndicator = containsToday,
                currentTimeLineOnlyToday = true, highlightCurrentDay = days > 1, showDayHeader = days > 1), modifier = Modifier.fillMaxSize(),
                eventConfig = EventConfig(alwaysUseFullName = true, eventSpacingDp = 1),
                actions = WeekViewActions(onEventClick = { event -> built.items[event.id]?.let(onItemClick) }, onScalingFactorChange = onScalingChange),
                style = biboWeekStyle(), initialScrollTime = if (containsToday) now.toLocalTime().minusHours(1).takeIf { now.hour > 0 } ?: LocalTime.MIN else LocalTime.of(8, 0), bottomContentPadding = 120.dp)
        }
    }
}

internal fun editRequestFor(item: CalendarItem, displayZone: ZoneId): CalendarEditRequest? {
    if (item is CalendarItem.Usage || item is CalendarItem.Block && item.block.source == "COMPLETION" || item is CalendarItem.Device && !item.event.canEdit) return null
    val eventZone = when (item) {
        is CalendarItem.Device -> runCatching { ZoneId.of(item.event.eventZone) }.getOrDefault(displayZone)
        is CalendarItem.Block -> item.block.eventZone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: displayZone
        else -> displayZone
    }
    val zone = if (item.isAllDay) ZoneOffset.UTC else eventZone
    val start = Instant.ofEpochMilli(item.startMillis).atZone(zone)
    val end = Instant.ofEpochMilli(item.endMillis).atZone(zone)
    return CalendarEditRequest("Edit ${if (item.isPlan) "plan" else "activity"}", item.title,
        if (item.isAllDay) null else start.toLocalTime(), if (item.isAllDay) null else end.toLocalTime(), start.toLocalDate(),
        (item as? CalendarItem.Block)?.block?.source ?: "PLAN", if (item.isAllDay) end.toLocalDate().minusDays(1) else end.toLocalDate(),
        item.isAllDay, zone, start.offset, end.offset, item)
}

internal fun itemTimeDescription(item: CalendarItem, zone: ZoneId): String {
    if (item.isAllDay) {
        val start = Instant.ofEpochMilli(item.startMillis).atZone(ZoneOffset.UTC).toLocalDate()
        val last = Instant.ofEpochMilli(item.endMillis).minusNanos(1).atZone(ZoneOffset.UTC).toLocalDate()
        return "All day · ${start.format(detailDateFmt)}" + if (last != start) " – ${last.format(detailDateFmt)}" else ""
    }
    val start = Instant.ofEpochMilli(item.startMillis).atZone(zone)
    val end = Instant.ofEpochMilli(item.endMillis).atZone(zone)
    val startText = "${start.format(detailDateFmt)} · ${start.format(detailTimeFmt)}"
    if (item.endMillis == item.startMillis) return startText + " · point in time"
    val endText = (if (start.toLocalDate() != end.toLocalDate()) "${end.format(detailDateFmt)} · " else "") + end.format(detailTimeFmt)
    val offsetDescription = if (start.offset != end.offset) " · ${zone.id} (${start.offset} → ${end.offset})" else ""
    return "$startText – $endText · ${CalendarTime.elapsedLabel(item.startMillis, item.endMillis)}$offsetDescription"
}

@Composable
private fun ItemDetailSheet(item: CalendarItem, zone: ZoneId, operation: CalendarOperationState, goalTitle: String?, onDismiss: () -> Unit,
                            onEdit: () -> Unit, onDelete: () -> Unit, onOpen: () -> Unit, onGoalClick: (Long) -> Unit, onTaskClick: (Long) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val editable = item !is CalendarItem.Usage && !(item is CalendarItem.Block && item.block.source == "COMPLETION") && !(item is CalendarItem.Device && !item.event.canEdit)
    ModalBottomSheet(onDismissRequest = { if (!operation.saving) onDismiss() }, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleLarge)
            Text(itemTimeDescription(item, zone), style = MaterialTheme.typography.bodyLarge)
            Text(item.sourceDescription, style = MaterialTheme.typography.bodyMedium)
            if (item is CalendarItem.Device) {
                if (item.event.recurring) Text("Repeating event. Open its calendar app to choose one occurrence or the entire series.")
                else if (!item.event.writable) Text("This calendar is read-only.")
            }
            if (item is CalendarItem.Block) {
                item.block.note?.takeIf { it.isNotBlank() }?.let { Text("Reflection", fontWeight = FontWeight.Bold); Text(it) }
                item.block.goalId?.let { id -> TextButton(onClick = { onGoalClick(id) }) { Text("Goal: ${goalTitle ?: "Open goal"}") } }
                item.block.taskId?.let { id -> TextButton(onClick = { onTaskClick(id) }) { Text("Open linked task") } }
                if (item.block.source == "COMPLETION") Text("This records completion, not elapsed work. Reopen the task to correct it.")
            }
            operation.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (operation.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (editable) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !operation.saving, onClick = onEdit) { Text("Edit") }
                OutlinedButton(enabled = !operation.saving, onClick = { confirmDelete = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
            if (item is CalendarItem.Device) OutlinedButton(enabled = !operation.saving, onClick = onOpen) { Text("Open calendar app") }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete “${item.title}”?") },
        text = { Text(if (item is CalendarItem.Device) "This removes this non-repeating event from ${item.event.calendarName}." else "This removes the local ${if (item.isPlan) "plan" else "activity log"} from Bibo.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
}

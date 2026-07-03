@file:OptIn(ExperimentalMaterial3Api::class)

package com.bibo.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarViewDay
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.bibo.data.ActivityBlock
import com.bibo.data.BiboDb
import com.bibo.data.DeviceCalendarEvent
import com.bibo.data.DeviceCalendarRepo
import com.bibo.data.UsageRepo
import com.bibo.data.UsageSession
import de.tobiasschuerg.weekview.compose.WeekViewActions
import de.tobiasschuerg.weekview.compose.WeekViewCompose
import de.tobiasschuerg.weekview.compose.style.WeekViewStyle
import de.tobiasschuerg.weekview.compose.style.defaultWeekViewColors
import de.tobiasschuerg.weekview.compose.style.defaultWeekViewStyle
import de.tobiasschuerg.weekview.data.EventConfig
import de.tobiasschuerg.weekview.data.WeekViewConfig
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PAGER_CENTER = 5000
private val monthFmt = DateTimeFormatter.ofPattern("MMMM yyyy")
private val detailDateFmt = DateTimeFormatter.ofPattern("EEE, MMM d")
private val detailTimeFmt = DateTimeFormatter.ofPattern("H:mm")

private data class EditorRequest(
    val sheetTitle: String,
    val title: String,
    val start: LocalTime?,
    val end: LocalTime?,
    val date: LocalDate,
    val source: String,
    val editItem: CalendarItem? = null,
)

@Composable
fun CalendarScreen() {
    val context = LocalContext.current
    val db = remember { BiboDb.get(context) }
    val calRepo = remember { DeviceCalendarRepo(context) }
    val usageRepo = remember { UsageRepo(context) }
    val voiceParser = remember { SmartVoiceParser(context) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { voiceParser.warmUp() } }
    val scope = rememberCoroutineScope()
    val zone = remember { ZoneId.systemDefault() }
    val darkTheme = isSystemInDarkTheme()

    var daysVisible by rememberSaveable { mutableIntStateOf(1) }
    var anchorEpochDay by rememberSaveable { mutableLongStateOf(LocalDate.now().toEpochDay()) }
    var scaling by rememberSaveable { mutableFloatStateOf(1f) }

    var hasCalPermission by remember { mutableStateOf(calRepo.hasPermissions()) }
    var hasUsagePermission by remember { mutableStateOf(usageRepo.hasPermission()) }
    var refresh by remember { mutableIntStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        hasCalPermission = grants[Manifest.permission.READ_CALENDAR] == true &&
            grants[Manifest.permission.WRITE_CALENDAR] == true
    }
    LaunchedEffect(Unit) {
        if (!hasCalPermission) {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
            )
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasUsagePermission = usageRepo.hasPermission()
        hasCalPermission = calRepo.hasPermissions()
        refresh++
    }

    var editor by remember { mutableStateOf<EditorRequest?>(null) }
    var detail by remember { mutableStateOf<CalendarItem?>(null) }

    Column(Modifier.fillMaxSize()) {
        key(daysVisible, anchorEpochDay) {
            val pagerState = rememberPagerState(initialPage = PAGER_CENTER) { PAGER_CENTER * 2 }
            val visibleStart = LocalDate.ofEpochDay(
                anchorEpochDay + (pagerState.currentPage - PAGER_CENTER).toLong() * daysVisible
            )

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    visibleStart.format(monthFmt),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                FilledTonalIconButton(onClick = {
                    anchorEpochDay = LocalDate.now().toEpochDay()
                }) {
                    Icon(Icons.Filled.Today, "Go to today")
                }
                Spacer(Modifier.width(8.dp))
                SingleChoiceSegmentedButtonRow {
                    SegmentedButton(
                        selected = daysVisible == 1,
                        onClick = {
                            if (daysVisible != 1) {
                                anchorEpochDay = visibleStart.toEpochDay()
                                daysVisible = 1
                            }
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        icon = {},
                    ) { Text("Day") }
                    SegmentedButton(
                        selected = daysVisible == 3,
                        onClick = {
                            if (daysVisible != 3) {
                                anchorEpochDay = visibleStart.toEpochDay()
                                daysVisible = 3
                            }
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        icon = {},
                    ) { Text("3 days") }
                }
            }

            DateStrip(
                center = visibleStart,
                selStart = visibleStart,
                selCount = daysVisible,
                onPick = { d -> anchorEpochDay = d.toEpochDay() },
            )

            Box(Modifier.weight(1f)) {
                HorizontalPager(
                    state = pagerState,
                    beyondViewportPageCount = 1,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    val startDate = LocalDate.ofEpochDay(
                        anchorEpochDay + (page - PAGER_CENTER).toLong() * daysVisible
                    )
                    TimelinePage(
                        startDate = startDate,
                        days = daysVisible,
                        db = db,
                        calRepo = calRepo,
                        usageRepo = usageRepo,
                        hasCalPermission = hasCalPermission,
                        hasUsagePermission = hasUsagePermission,
                        refresh = refresh,
                        scaling = scaling,
                        onScalingChange = { scaling = it },
                        darkTheme = darkTheme,
                        zone = zone,
                        onItemClick = { detail = it },
                    )
                }

                HoldToTalkButton(
                    onTranscript = { transcript ->
                        scope.launch {
                            val parsed = withContext(Dispatchers.IO) { voiceParser.parse(transcript) }
                            editor = EditorRequest(
                                sheetTitle = "Log activity",
                                title = parsed.title,
                                start = parsed.start,
                                end = parsed.end,
                                date = LocalDate.now(),
                                source = "VOICE",
                            )
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 20.dp),
                )

                FloatingActionButton(
                    onClick = {
                        editor = EditorRequest(
                            sheetTitle = "New event",
                            title = "",
                            start = null,
                            end = null,
                            date = visibleStart,
                            source = "MANUAL",
                        )
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(20.dp),
                ) {
                    Icon(Icons.Filled.Add, "Add event")
                }
            }
        }
    }

    editor?.let { req ->
        EventEditorSheet(
            sheetTitle = req.sheetTitle,
            initialDate = req.date,
            initialTitle = req.title,
            initialStart = req.start,
            initialEnd = req.end,
            onDismiss = { editor = null },
            onSave = { date, st, et, title ->
                editor = null
                scope.launch {
                    val startMillis = date.atTime(st).atZone(zone).toInstant().toEpochMilli()
                    val endDateTime =
                        if (et.isAfter(st)) date.atTime(et) else date.plusDays(1).atTime(et)
                    val endMillis = endDateTime.atZone(zone).toInstant().toEpochMilli()
                    withContext(Dispatchers.IO) {
                        when (val editing = req.editItem) {
                            is CalendarItem.Block ->
                                db.activityBlocks().update(
                                    editing.block.copy(
                                        title = title,
                                        startMillis = startMillis,
                                        endMillis = endMillis,
                                    )
                                )
                            is CalendarItem.Device -> {
                                val ok = calRepo.updateEvent(editing.event.id, title, startMillis, endMillis)
                                if (!ok) {
                                    withContext(Dispatchers.Main) {
                                        android.widget.Toast.makeText(
                                            context,
                                            "Can't edit a repeating event here — change it in Google Calendar.",
                                            android.widget.Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                }
                            }
                            is CalendarItem.Usage, null -> {
                                val toGoogle = req.source == "MANUAL" && hasCalPermission &&
                                    calRepo.insertEvent(title, startMillis, endMillis)
                                if (!toGoogle) {
                                    db.activityBlocks().insert(
                                        ActivityBlock(
                                            title = title,
                                            startMillis = startMillis,
                                            endMillis = endMillis,
                                            source = req.source,
                                        )
                                    )
                                }
                            }
                        }
                    }
                    refresh++
                }
            },
        )
    }

    detail?.let { item ->
        ItemDetailSheet(
            item = item,
            zone = zone,
            onDismiss = { detail = null },
            onEdit = {
                val req = editRequestFor(item, zone)
                detail = null
                if (req != null) editor = req
            },
            onDelete = {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        when (item) {
                            is CalendarItem.Block -> db.activityBlocks().delete(item.block)
                            is CalendarItem.Device -> calRepo.deleteEvent(item.event.id)
                            is CalendarItem.Usage -> Unit
                        }
                    }
                    detail = null
                    refresh++
                }
            },
            onOpen = {
                if (item is CalendarItem.Device) {
                    runCatching { context.startActivity(calRepo.viewEventIntent(item.event.id)) }
                }
                detail = null
            },
        )
    }
}

@Composable
private fun DateStrip(
    center: LocalDate,
    selStart: LocalDate,
    selCount: Int,
    onPick: (LocalDate) -> Unit,
) {
    val today = LocalDate.now()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        (-3..3).forEach { offset ->
            val d = center.plusDays(offset.toLong())
            val selected = !d.isBefore(selStart) && d.isBefore(selStart.plusDays(selCount.toLong()))
            val isToday = d == today
            Column(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surface
                    )
                    .clickable { onPick(d) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    d.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    d.dayOfMonth.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isToday || selected) FontWeight.Bold else FontWeight.Normal,
                    color = when {
                        selected -> MaterialTheme.colorScheme.onPrimaryContainer
                        isToday -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}

@Composable
private fun biboWeekStyle(): WeekViewStyle = defaultWeekViewStyle(
    defaultWeekViewColors(
        todayHighlight = MaterialTheme.colorScheme.primary.copy(alpha = 0.05f),
        nowIndicator = MaterialTheme.colorScheme.error,
        dayHeaderText = MaterialTheme.colorScheme.onSurfaceVariant,
        timeLabelTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
        gridLineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        currentDayBackground = MaterialTheme.colorScheme.primary,
        currentDayText = MaterialTheme.colorScheme.onSurface,
    )
)

@Composable
private fun TimelinePage(
    startDate: LocalDate,
    days: Int,
    db: BiboDb,
    calRepo: DeviceCalendarRepo,
    usageRepo: UsageRepo,
    hasCalPermission: Boolean,
    hasUsagePermission: Boolean,
    refresh: Int,
    scaling: Float,
    onScalingChange: (Float) -> Unit,
    darkTheme: Boolean,
    zone: ZoneId,
    onItemClick: (CalendarItem) -> Unit,
) {
    val windowStart = startDate.atStartOfDay(zone).toInstant().toEpochMilli()
    val windowEnd =
        startDate.plusDays(days.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()

    val blocksFlow = remember(windowStart, windowEnd) {
        db.activityBlocks().blocksIn(windowStart, windowEnd)
    }
    val blocks by blocksFlow.collectAsState(initial = emptyList())

    val deviceEvents by produceState(
        initialValue = emptyList<DeviceCalendarEvent>(),
        windowStart, hasCalPermission, refresh,
    ) {
        value = if (hasCalPermission) {
            withContext(Dispatchers.IO) { calRepo.queryInstances(windowStart, windowEnd) }
        } else {
            emptyList()
        }
    }

    val usageSessions by produceState(
        initialValue = emptyList<UsageSession>(),
        windowStart, hasUsagePermission, refresh,
    ) {
        value = if (hasUsagePermission) {
            withContext(Dispatchers.IO) { usageRepo.querySessions(windowStart, windowEnd) }
        } else {
            emptyList()
        }
    }

    val built = remember(startDate, days, blocks, deviceEvents, usageSessions, darkTheme) {
        buildCalendar(startDate, days, blocks, deviceEvents, usageSessions, darkTheme)
    }

    val today = LocalDate.now()
    val containsToday = !today.isBefore(startDate) && today.isBefore(startDate.plusDays(days.toLong()))
    val initialScroll = if (containsToday) {
        val now = LocalTime.now()
        if (now.hour == 0) LocalTime.MIN else now.minusHours(1)
    } else {
        LocalTime.of(8, 0)
    }

    WeekViewCompose(
        weekData = built.weekData,
        weekViewConfig = WeekViewConfig(
            scalingFactor = scaling,
            showCurrentTimeIndicator = true,
            highlightCurrentDay = days > 1,
            showDayHeader = days > 1,
        ),
        modifier = Modifier.fillMaxSize(),
        eventConfig = EventConfig(alwaysUseFullName = true, eventSpacingDp = 1),
        actions = WeekViewActions(
            onEventClick = { ev -> built.items[ev.id]?.let(onItemClick) },
            onScalingFactorChange = onScalingChange,
        ),
        style = biboWeekStyle(),
        initialScrollTime = initialScroll,
        bottomContentPadding = 120.dp,
    )
}

private fun editRequestFor(item: CalendarItem, zone: ZoneId): EditorRequest? = when (item) {
    is CalendarItem.Usage -> null
    is CalendarItem.Block -> {
        val s = Instant.ofEpochMilli(item.block.startMillis).atZone(zone)
        val e = Instant.ofEpochMilli(item.block.endMillis).atZone(zone)
        EditorRequest(
            "Edit", item.block.title, s.toLocalTime(), e.toLocalTime(),
            s.toLocalDate(), item.block.source, item,
        )
    }
    is CalendarItem.Device -> {
        val s = Instant.ofEpochMilli(item.event.begin).atZone(zone)
        val e = Instant.ofEpochMilli(item.event.end).atZone(zone)
        EditorRequest(
            "Edit event", item.event.title, s.toLocalTime(), e.toLocalTime(),
            s.toLocalDate(), "MANUAL", item,
        )
    }
}

@Composable
private fun ItemDetailSheet(
    item: CalendarItem,
    zone: ZoneId,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var confirmDelete by remember { mutableStateOf(false) }

    val (title, startMillis, endMillis, subtitle, colorInt) = when (item) {
        is CalendarItem.Device -> DetailData(
            item.event.title, item.event.begin, item.event.end,
            "Google Calendar", item.event.color,
        )
        is CalendarItem.Block -> DetailData(
            item.block.title, item.block.startMillis, item.block.endMillis,
            sourceLabel(item.block.source), item.block.color ?: sourceColor(item.block.source),
        )
        is CalendarItem.Usage -> DetailData(
            item.session.label, item.session.start, item.session.end,
            "App usage · " + formatDuration(item.session.end - item.session.start),
            item.session.color,
        )
    }

    val start = Instant.ofEpochMilli(startMillis).atZone(zone)
    val end = Instant.ofEpochMilli(endMillis).atZone(zone)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .width(14.dp)
                        .height(14.dp)
                        .background(
                            androidx.compose.ui.graphics.Color(
                                if (colorInt == 0) 0xFF1A73E8.toInt()
                                else (colorInt or 0xFF000000.toInt())
                            ),
                            CircleShape,
                        )
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                start.format(detailDateFmt) + " · " +
                    start.format(detailTimeFmt) + " – " + end.format(detailTimeFmt),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (item !is CalendarItem.Usage) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Edit, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Edit time")
                    }
                    if (item is CalendarItem.Device) {
                        OutlinedButton(onClick = onOpen, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.OpenInNew, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Open")
                        }
                    }
                    OutlinedButton(
                        onClick = { confirmDelete = true },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            Icons.Filled.DeleteOutline, null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete \"$title\"?") },
            text = {
                if (item is CalendarItem.Device) {
                    Text("This deletes the event from your Google Calendar.")
                } else {
                    Text("This removes the logged block.")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

private data class DetailData(
    val title: String,
    val start: Long,
    val end: Long,
    val subtitle: String,
    val color: Int,
)

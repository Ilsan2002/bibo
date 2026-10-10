package com.bibo.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bibo.data.*
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

fun formatDuration(millis: Long): String {
    val seconds = millis.coerceAtLeast(0) / 1000
    if (seconds < 60) return "${seconds}s"
    val minutes = seconds / 60
    return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
}

@Composable
fun StatsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val usage = remember { UsageRepo(context) }
    val websites = remember { WebsiteRepo(context) }
    var epochDay by rememberSaveable { mutableLongStateOf(AppTime.today().toEpochDay()) }
    var today by remember { mutableStateOf(AppTime.today()) }
    var zone by remember { mutableStateOf(AppTime.zone()) }
    var refresh by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(WebsiteTrackingSettings.enabled(context)) }
    var capabilityRefresh by remember { mutableIntStateOf(0) }
    val connected by Capabilities.accessibilityConnection.collectAsStateWithLifecycle()
    val recordingError by WebsiteTrackingSettings.error.collectAsStateWithLifecycle()
    // Read live grants on resume and periodically; midnight follows Today without moving a historical selection.
    fun updateContext() {
        val nextToday = AppTime.today()
        if (epochDay == today.toEpochDay()) epochDay = nextToday.toEpochDay()
        today = nextToday
        zone = AppTime.zone()
        recording = WebsiteTrackingSettings.enabled(context)
        capabilityRefresh++
        refresh++
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { updateContext() }
    LaunchedEffect(Unit) { while (true) { delay(30_000); updateContext() } }
    val capability = remember(capabilityRefresh, connected) { Capabilities.snapshot(context) }
    LaunchedEffect(refresh) {
        refreshing = true
        try { error = usage.refresh(force = true).error }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "Refresh failed" }
        finally { refreshing = false }
    }
    val date = LocalDate.ofEpochDay(epochDay)
    val days = remember(date) { (6 downTo 0).map { date.minusDays(it.toLong()) } }
    val locale = LocalConfiguration.current.locales[0]
    val snapshot by produceState<UsageSnapshot?>(null, days, zone) {
        value = null // never show the previous day's values under a new date
        try { usage.observeSnapshot(days, zone).flowOn(Dispatchers.IO).collect { value = it } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "Saved history could not be read" }
    }
    val selected = snapshot?.takeIf { it.days == days && it.zone == zone }
    val window = AppTime.window(date, zone)
    var websiteError by remember(date) { mutableStateOf<String?>(null) }
    val siteSnapshot by produceState<Pair<Pair<Long, Long>, List<WebsiteTotal>>?>(null, window.startMillis, window.endMillis) {
        value = null
        try { websites.observeTotals(window.startMillis, window.endMillis).flowOn(Dispatchers.IO).collect { value = (window.startMillis to window.endMillis) to it } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { websiteError = failure.message ?: "Website history could not be read" }
    }
    val siteTotals = siteSnapshot?.takeIf { it.first == (window.startMillis to window.endMillis) }?.second
    var appLimit by remember(date) { mutableIntStateOf(20) }
    var siteLimit by remember(date) { mutableIntStateOf(20) }
    var confirmClear by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Screen time", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = { epochDay-- }) { Icon(Icons.Filled.ChevronLeft, "Previous day") }
                Text(date.format(DateTimeFormatter.ofPattern("MMM d", locale)))
                IconButton(onClick = { epochDay++ }, enabled = date < today) { Icon(Icons.Filled.ChevronRight, "Next day") }
                IconButton(onClick = { refresh++ }, enabled = !refreshing) { Icon(Icons.Filled.Refresh, "Refresh observations") }
            }
            if (date != today) TextButton(onClick = { epochDay = today.toEpochDay() }) { Text("Back to today") }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item("capability") {
                    if (!capability.state(Capability.USAGE_ACCESS).granted) {
                        Text("Usage collection is paused. Previously saved history is still available.")
                        TextButton(onClick = { Capabilities.openSettings(context, Capability.USAGE_ACCESS).onFailure { error = it.message } }) { Text("Allow usage collection") }
                    }
                    error?.let { Text("Refresh unavailable: $it. Showing stored observations.", color = MaterialTheme.colorScheme.error) }
                }
                item("total") {
                    if (selected == null) {
                        if (error == null) CircularProgressIndicator() else Text("History unavailable. Retry refresh.")
                    } else {
                        val coverage = selected.coverage.last()
                        Text(if (coverage == UsageCoverageState.UNKNOWN && selected.totals.isEmpty()) "Unknown" else formatDuration(selected.selectedDayMillis),
                            style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                        Text(when (coverage) {
                            UsageCoverageState.COMPLETE -> "Observed foreground time; overlapping apps counted once"
                            UsageCoverageState.PARTIAL -> "Partial coverage. This is observed time, not a complete daily total."
                            UsageCoverageState.UNKNOWN -> "Collection coverage is unknown; stored observations may be incomplete."
                        }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        selected.collectedAt?.let { Text("Last collected ${java.time.Instant.ofEpochMilli(it).atZone(zone).format(DateTimeFormatter.ofPattern("MMM d, HH:mm", locale))}", style = MaterialTheme.typography.bodySmall) }
                    }
                }
                selected?.let { data ->
                    item("trend") { WeeklyTrendChart(days, data.dailyMillis.map { it / 60_000 }, coverage = data.coverage, onSelectDay = { epochDay = it.toEpochDay() }, exactMillis = data.dailyMillis) }
                    item("heatmap") { UsageHeatmap(days, data.hourly, coverage = data.coverage) }
                    item("app-heading") {
                        Text("Apps", style = MaterialTheme.typography.titleMedium)
                        Text("Each app's foreground time. Totals can overlap in split screen.", style = MaterialTheme.typography.bodySmall)
                        if (data.totals.isEmpty()) Text(if (data.coverage.last() == UsageCoverageState.COMPLETE) "No foreground use observed." else "No saved observations for this day; missing history is unknown.")
                    }
                    items(data.totals.take(appLimit), key = { it.packageName }) { app ->
                        ListItem(headlineContent = { Text(app.label) }, supportingContent = { Text(app.packageName, style = MaterialTheme.typography.bodySmall) }, trailingContent = { Text(formatDuration(app.totalMillis)) })
                    }
                    if (data.totals.size > appLimit) item("more-apps") { TextButton(onClick = { appLimit += 20 }) { Text("Show more apps (${data.totals.size - appLimit})") } }
                }
                item("web-setting") {
                    Text("Websites", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Record website time")
                            Text("Optional address-bar sampling stays on this device. Blocking works independently.", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = recording, onCheckedChange = { recording = it; WebsiteTrackingSettings.setEnabled(context, it) })
                    }
                    if (recording && !capability.state(Capability.ACCESSIBILITY).healthy) {
                        Text(capability.state(Capability.ACCESSIBILITY).detail)
                        TextButton(onClick = { Capabilities.openSettings(context, Capability.ACCESSIBILITY) }) { Text("Set up accessibility service") }
                    }
                    if (!recording) Text("Recording paused. Saved website history remains available.", style = MaterialTheme.typography.bodySmall)
                    Text("Only sampled supported-browser visits are recorded; absence of history is unknown.", style = MaterialTheme.typography.bodySmall)
                    websiteError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    recordingError?.let { Text("Recording unavailable: $it", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { confirmClear = true }) { Text("Clear website history") }
                }
                val sites = siteTotals
                if (sites == null) item("web-loading") { Text("Loading website observations…") }
                else if (sites.isEmpty()) item("web-empty") { Text("No website observations for this day.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                else {
                    items(sites.take(siteLimit), key = { it.domain }) { site -> ListItem(headlineContent = { Text(site.domain) }, trailingContent = { Text(formatDuration(site.totalMillis)) }) }
                    if (sites.size > siteLimit) item("more-sites") { TextButton(onClick = { siteLimit += 20 }) { Text("Show more websites (${sites.size - siteLimit})") } }
                }
            }
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("Clear website history?") }, text = { Text("All saved website visits will be deleted. Recording can remain paused independently of app blocking.") },
        confirmButton = { TextButton(onClick = { confirmClear = false; scope.launch { try { kotlinx.coroutines.withContext(Dispatchers.IO) { websites.clearHistory() }; snackbar.showSnackbar("Website history cleared") } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { snackbar.showSnackbar(failure.message ?: "History could not be cleared") } } }) { Text("Clear") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } })
}

@file:OptIn(ExperimentalMaterial3Api::class)
package com.bibo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.bibo.data.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext

private val dateFmt = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy")
private data class NutritionEditor(val food: FoodItem, val day: Long, val entry: FoodEntry? = null, val draftIndex: Int? = null)

@Composable
fun HealthScreen() {
    val context = LocalContext.current
    val db = remember { BiboDb.get(context) }
    val model: HealthViewModel = viewModel(factory = remember { HealthViewModel.Factory(context.applicationContext) })
    val operation by model.state.collectAsState()
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var epochDay by rememberSaveable { mutableLongStateOf(AppTime.today().toEpochDay()) }
    val liveDay by AppTime.days.collectAsState(initial = DayContext(AppTime.today(), AppTime.zone()))
    var previousToday by rememberSaveable { mutableLongStateOf(AppTime.today().toEpochDay()) }
    LaunchedEffect(liveDay) {
        if (epochDay == previousToday) epochDay = liveDay.date.toEpochDay()
        previousToday = liveDay.date.toEpochDay()
    }
    val date = LocalDate.ofEpochDay(epochDay)
    val settingsStore = remember { HealthPreferenceStore(context) }
    var preferences by remember { mutableStateOf(settingsStore.read()) }
    var showPreferences by remember { mutableStateOf(false) }
    var loadRevision by remember { mutableIntStateOf(0) }
    var addingDay by rememberSaveable { mutableStateOf<Long?>(null) }
    var nutritionEditor by remember { mutableStateOf<NutritionEditor?>(null) }
    val reviewer = remember { DayReview(context) }
    var reviewRequest by remember(epochDay) { mutableIntStateOf(0) }
    var handledReviewRequest by remember(epochDay) { mutableIntStateOf(0) }
    var reviewCancellation by remember(epochDay) { mutableIntStateOf(0) }
    var voiceTargetDay by remember { mutableLongStateOf(epochDay) }
    var voiceReferenceDay by remember { mutableLongStateOf(AppTime.today().toEpochDay()) }
    var review by remember(epochDay) { mutableStateOf<String?>(null) }
    var reviewError by remember(epochDay) { mutableStateOf<String?>(null) }
    var reviewing by remember(epochDay) { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { if (reviewing) reviewCancellation++ }

    LaunchedEffect(operation.message, operation.error) {
        val notice = operation.error ?: operation.message
        if (notice != null) { snackbar.showSnackbar(notice); model.clearNotice() }
    }
    // Keying the entire daily content prevents retained Room emissions from another date.
    key(epochDay) {
        val daily by remember(epochDay, liveDay.zone, loadRevision) { model.observeDay(epochDay, liveDay.zone) }.collectAsState(initial = HealthDayState(epochDay))
        val habit = daily.habit
        val foods = daily.foods
        val blocks = daily.blocks
        val todayHabit = habit ?: HabitDay(epochDay)
        // Date-keyed job is canceled on navigation and invalidated by factual edits.
        LaunchedEffect(epochDay, reviewRequest, reviewCancellation, habit, foods, blocks, liveDay.zone, daily.loaded) {
            review = null; reviewError = null; reviewing = false
            if (reviewRequest > handledReviewRequest && daily.loaded) {
                handledReviewRequest = reviewRequest
                reviewing = true
                val reviewDay = epochDay
                try {
                    val result = withContext(Dispatchers.IO) {
                        val before = reviewer.revision(reviewDay)
                        val narrative = reviewer.generate(reviewDay)
                        if (before == reviewer.revision(reviewDay)) narrative else null
                    }
                    if (result != null) review = result else reviewError = "The records changed. Generate the review again."
                }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { reviewError = "Review unavailable. Try again." }
                finally { reviewing = false }
            }
        }
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 220.dp)) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Daily", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                IconButton(onClick = { showPreferences = true }) { Icon(Icons.Filled.Tune, "Daily targets and visible habits") }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { epochDay-- }) { Icon(Icons.Filled.ChevronLeft, "Previous day") }
                                Text(date.format(dateFmt), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                IconButton(onClick = { epochDay++ }, enabled = epochDay < AppTime.today().toEpochDay()) { Icon(Icons.Filled.ChevronRight, "Next day") }
                            }
                            if (date != AppTime.today()) TextButton(onClick = { epochDay = AppTime.today().toEpochDay() }) { Text("Back to today") }
                        }
                    }
                    if (!daily.loaded) item {
                        Column(Modifier.padding(16.dp)) {
                            Text(daily.error ?: "Loading this day’s records…", color = if (daily.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                            if (daily.error != null) TextButton(onClick = { loadRevision++ }) { Text("Retry") }
                        }
                    }
                    if (daily.loaded) {
                    item {
                        // Vertical cards retain labels/values at large font scale and narrow widths.
                        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            IntakeCard("Calories", foods.sumOf { it.calories }.toDouble(), "kcal", preferences.calorieTarget, preferences.targetsEnabled)
                            IntakeCard("Sugar", foods.sumOf { it.sugarG }, "g", preferences.sugarTarget, preferences.targetsEnabled)
                            IntakeCard("Caffeine", foods.sumOf { it.caffeineMg }.toDouble(), "mg", preferences.caffeineTarget, preferences.targetsEnabled)
                            if (foods.any { it.estimated }) Text("Includes estimated servings. Tap an entry to correct it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    item {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Habits", style = MaterialTheme.typography.titleMedium)
                            Text("Tap to cycle: unlogged → done → skipped → unlogged.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            HealthHabit.entries.filter { preferences.visibleHabitMask and it.bit != 0 }.forEach { which ->
                                HabitToggle(which, todayHabit.value(which), daily.loaded) {
                                    haptics.tick()
                                    model.cycle(epochDay, which)
                                }
                            }
                            if (preferences.visibleHabitMask == 0) Text("Choose the habits that matter to you in Daily settings.")
                        }
                    }
                    item {
                        val week = remember(epochDay) { (6 downTo 0).map { date.minusDays(it.toLong()) } }
                        var weekLoaded by remember(epochDay, loadRevision) { mutableStateOf(false) }
                        var weekError by remember(epochDay, loadRevision) { mutableStateOf(false) }
                        val history by remember(epochDay, loadRevision) { db.habits().range(week.first().toEpochDay(), epochDay)
                            .onStart { weekLoaded = false; weekError = false }
                            .onEach { weekLoaded = true }
                            .catch { if (it is CancellationException) throw it; weekError = true; emit(emptyList()) }
                        }.collectAsState(initial = emptyList())
                        Column(Modifier.padding(horizontal = 16.dp)) {
                            Text("Last 7 days", style = MaterialTheme.typography.titleMedium)
                            if (weekError) {
                                Text("Week history unavailable.", color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = { loadRevision++ }) { Text("Retry history") }
                            }
                            else if (!weekLoaded) Text("Loading week history…")
                            else week.forEach { d ->
                                val row = history.firstOrNull { it.epochDay == d.toEpochDay() }
                                val done = HealthHabit.entries.count { preferences.visibleHabitMask and it.bit != 0 && row?.value(it) == true }
                                val known = HealthHabit.entries.count { preferences.visibleHabitMask and it.bit != 0 && row?.value(it) != null }
                                TextButton(onClick = { epochDay = d.toEpochDay() }, modifier = Modifier.fillMaxWidth().semantics {
                                    contentDescription = "${d.format(dateFmt)}, $done habits done, $known logged"
                                    stateDescription = if (d == date) "Selected day" else ""
                                }) { Text("${d.format(DateTimeFormatter.ofPattern("EEE, MMM d"))} · $done done, $known logged") }
                            }
                        }
                    }
                    item {
                        Surface(Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                            Column(Modifier.padding(14.dp)) {
                                Text("Day review", style = MaterialTheme.typography.titleMedium)
                                TextButton(enabled = !reviewing && daily.loaded, onClick = { reviewRequest++ }) { Text(if (reviewing) "Reviewing…" else "Generate from this day's records") }
                                if (reviewing) TextButton(onClick = { reviewCancellation++ }) { Text("Cancel review") }
                                review?.let { Text(it) }
                                reviewError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                    item { Text("Food & drink", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp)) }
                    if (daily.loaded && foods.isEmpty()) item { Text("No food recorded for this day. Add a description or enter nutrition manually.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp)) }
                    items(foods, key = { it.id }) { entry ->
                        FoodRow(entry, onEdit = { nutritionEditor = NutritionEditor(FoodItem(entry.label, entry.calories, entry.sugarG, entry.caffeineMg, entry.estimated, entry.provenance), entry.epochDay, entry) }, onDelete = {
                            model.deleteFood(entry) {
                                scope.launch {
                                    if (snackbar.showSnackbar("Deleted ${entry.label}", actionLabel = "Undo") == SnackbarResult.ActionPerformed) model.restoreFood(entry)
                                }
                            }
                        })
                    }
                    }
                }
                HoldToTalkButton(onTranscript = { model.parse(it, voiceTargetDay, voiceReferenceDay) }, onRecordingStart = { voiceTargetDay = epochDay; voiceReferenceDay = AppTime.today().toEpochDay() }, enabled = !operation.parsing && !operation.saving,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp))
                FloatingActionButton(onClick = { if (!operation.parsing && !operation.saving) addingDay = epochDay }, modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)) { Icon(Icons.Filled.Add, "Add food or habit") }
                if (operation.parsing || operation.saving) Surface(Modifier.align(Alignment.BottomStart).padding(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Text(if (operation.parsing) "Preparing preview…" else "Saving…", modifier = Modifier.padding(12.dp))
                }
            }
        }
    }
    addingDay?.let { day -> AddFoodSheet(onDismiss = { addingDay = null }, onParse = { text -> addingDay = null; model.parse(text, day) }, onManual = {
        addingDay = null; nutritionEditor = NutritionEditor(FoodItem("", 0, 0.0, 0, false, "Manual nutrition"), day)
    }) }
    operation.draft?.let { draft -> HealthPreviewSheet(draft, operation.saving, onDismiss = model::dismissDraft, onSave = model::saveDraft, onChange = model::revise, onMoveDay = model::moveDraftDay,
        onEdit = { index -> nutritionEditor = NutritionEditor(if (index >= 0) draft.log.foods[index] else FoodItem("", 0, 0.0, 0, false, "Manual nutrition"), draft.day, draftIndex = index) }) }
    nutritionEditor?.let { editor -> NutritionSheet(editor.food, day = if (editor.draftIndex == null) editor.day else null, onMoveDay = { delta -> nutritionEditor = editor.copy(day = editor.day + delta) }, onDismiss = { nutritionEditor = null }) { food ->
        nutritionEditor = null
        when {
            editor.entry != null -> model.updateFood(editor.entry.copy(epochDay = editor.day, label = food.label, calories = food.calories, sugarG = food.sugarG, caffeineMg = food.caffeineMg, estimated = food.estimated, provenance = food.provenance))
            editor.draftIndex != null -> operation.draft?.let { draft -> model.revise(draft.log.copy(foods = draft.log.foods.toMutableList().also { if (editor.draftIndex < 0) it.add(food) else it[editor.draftIndex] = food })) }
            else -> model.manual(food, editor.day)
        }
    } }
    if (showPreferences) HealthSettingsDialog(preferences, onDismiss = { showPreferences = false }) {
        preferences = it; settingsStore.save(it); showPreferences = false
    }
}

@Composable private fun IntakeCard(label: String, value: Double, unit: String, target: Int, targetsEnabled: Boolean) {
    val display = if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(value)
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("$label: $display $unit", style = MaterialTheme.typography.titleMedium)
            if (targetsEnabled) {
                Text("Personal target $target $unit" + if (value > target) " · over by ${value.toInt() - target} $unit" else "", color = MaterialTheme.colorScheme.onSurfaceVariant)
                LinearProgressIndicator(progress = { (value / target).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        }
    }
}
@Composable private fun HabitToggle(habit: HealthHabit, value: Boolean?, enabled: Boolean, onClick: () -> Unit) {
    val status = when (value) { true -> "Done"; false -> "Skipped"; null -> "Unlogged" }
    Surface(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(12.dp), color = if (value == true) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().semantics { stateDescription = status; contentDescription = habit.label }) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(habit.label, modifier = Modifier.weight(1f)); Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable private fun FoodRow(food: FoodEntry, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).clickable(role = Role.Button, onClick = onEdit).padding(vertical = 8.dp)) {
            Text(food.label, style = MaterialTheme.typography.bodyLarge)
            Text("${if (food.estimated) "Estimate · " else ""}${food.calories} kcal · ${food.sugarG} g sugar · ${food.caffeineMg} mg caffeine", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${food.provenance} · Tap to edit", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onDelete) { Icon(Icons.Filled.DeleteOutline, "Delete ${food.label}") }
    }
}
@Composable private fun AddFoodSheet(onDismiss: () -> Unit, onParse: (String) -> Unit, onManual: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().imePadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Add food or habit", style = MaterialTheme.typography.titleLarge)
            Text("Descriptions create an editable preview. Other foods can use manual nutrition entry.")
            OutlinedTextField(text, { text = it }, label = { Text("e.g. two coffees and a banana") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { onParse(text.trim()) }, enabled = text.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Preview description") }
            TextButton(onClick = onManual, modifier = Modifier.fillMaxWidth()) { Text("Enter nutrition manually") }
        }
    }
}
@Composable private fun HealthPreviewSheet(draft: HealthDraft, saving: Boolean, onDismiss: () -> Unit, onSave: () -> Unit, onChange: (HealthLog) -> Unit, onMoveDay: (Long) -> Unit, onEdit: (Int) -> Unit) {
    ModalBottomSheet(onDismissRequest = { if (!saving) onDismiss() }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Check before saving", style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onMoveDay(-1) }, enabled = !saving) { Icon(Icons.Filled.ChevronLeft, "Log on previous day") }
                Text("For ${LocalDate.ofEpochDay(draft.day).format(dateFmt)}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { onMoveDay(1) }, enabled = !saving && draft.day < AppTime.today().toEpochDay()) { Icon(Icons.Filled.ChevronRight, "Log on next day") }
            }
            draft.log.warnings.forEach { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            draft.log.foods.forEachIndexed { index, food ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onEdit(index) }, enabled = !saving, modifier = Modifier.weight(1f)) { Text("${food.label} · ${food.calories} kcal · ${food.sugarG} g sugar · ${food.caffeineMg} mg caffeine") }
                    IconButton(onClick = { onChange(draft.log.copy(foods = draft.log.foods.filterIndexed { i, _ -> i != index })) }, enabled = !saving) { Icon(Icons.Filled.Close, "Remove ${food.label}") }
                }
            }
            TextButton(onClick = { onEdit(-1) }, enabled = !saving) { Text("Add missing food manually") }
            val values = listOf(draft.log.showered, draft.log.cleanClothes, draft.log.workedOut, draft.log.prayed)
            HealthHabit.entries.forEachIndexed { index, habit ->
                values[index]?.let { value ->
                    TextButton(enabled = !saving, onClick = {
                        val next: Boolean? = if (value) false else null
                        onChange(when (habit) { HealthHabit.SHOWER -> draft.log.copy(showered = next); HealthHabit.CLOTHES -> draft.log.copy(cleanClothes = next); HealthHabit.WORKOUT -> draft.log.copy(workedOut = next); HealthHabit.PRAY -> draft.log.copy(prayed = next) })
                    }) { Text("${habit.label}: ${if (value) "done" else "skipped"} · tap to change/remove") }
                }
            }
            Button(onClick = onSave, enabled = !saving && !draft.log.isEmpty && draft.log.foods.all { it.valid }, modifier = Modifier.fillMaxWidth()) { Text(if (saving) "Saving…" else "Save to this day") }
            TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") }
        }
    }
}
@Composable private fun NutritionSheet(initial: FoodItem, day: Long?, onMoveDay: (Long) -> Unit, onDismiss: () -> Unit, onSave: (FoodItem) -> Unit) {
    var name by remember { mutableStateOf(initial.label) }
    var calories by remember { mutableStateOf(initial.calories.toString()) }
    var sugar by remember { mutableStateOf(initial.sugarG.toString()) }
    var caffeine by remember { mutableStateOf(initial.caffeineMg.toString()) }
    var estimated by remember { mutableStateOf(initial.estimated) }
    val item = FoodItem(name.trim(), calories.toIntOrNull() ?: -1, sugar.toDoubleOrNull() ?: Double.NaN, caffeine.toIntOrNull() ?: -1, estimated, if (estimated) "Edited serving estimate" else "Manual nutrition")
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Food nutrition") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            day?.let { target -> Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onMoveDay(-1) }) { Icon(Icons.Filled.ChevronLeft, "Previous food date") }
                Text(LocalDate.ofEpochDay(target).format(dateFmt), modifier = Modifier.weight(1f))
                IconButton(onClick = { onMoveDay(1) }, enabled = target < AppTime.today().toEpochDay()) { Icon(Icons.Filled.ChevronRight, "Next food date") }
            } }
            OutlinedTextField(name, { name = it }, label = { Text("Food or drink") })
            OutlinedTextField(calories, { calories = it }, label = { Text("Calories (kcal)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(sugar, { sugar = it }, label = { Text("Sugar (g)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(caffeine, { caffeine = it }, label = { Text("Caffeine (mg)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(estimated, { estimated = it }); Text("These values are estimates") }
            if (!item.valid) Text("Enter a name and finite, nonnegative nutrition values.", color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = { onSave(item) }, enabled = item.valid) { Text("Use these values") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
@Composable private fun HealthSettingsDialog(initial: HealthPreferences, onDismiss: () -> Unit, onSave: (HealthPreferences) -> Unit) {
    var enabled by remember { mutableStateOf(initial.targetsEnabled) }
    var calories by remember { mutableStateOf(initial.calorieTarget.toString()) }
    var sugar by remember { mutableStateOf(initial.sugarTarget.toString()) }
    var caffeine by remember { mutableStateOf(initial.caffeineTarget.toString()) }
    var mask by remember { mutableIntStateOf(initial.visibleHabitMask) }
    val valid = (calories.toIntOrNull() ?: 0) in 1..100_000 && (sugar.toIntOrNull() ?: 0) in 1..10_000 && (caffeine.toIntOrNull() ?: 0) in 1..10_000
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Your Daily view") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(enabled, { enabled = it }); Text("Show my personal targets") }
            Text("Targets are optional personal preferences.")
            OutlinedTextField(calories, { calories = it }, label = { Text("Calories target (kcal)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(sugar, { sugar = it }, label = { Text("Sugar target (g)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(caffeine, { caffeine = it }, label = { Text("Caffeine target (mg)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Text("Visible habits", style = MaterialTheme.typography.titleSmall)
            HealthHabit.entries.forEach { habit -> Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(mask and habit.bit != 0, { mask = if (it) mask or habit.bit else mask and habit.bit.inv() }); Text(habit.label)
            } }
        }
    }, confirmButton = { TextButton(enabled = valid, onClick = { onSave(HealthPreferences(enabled, calories.toInt(), sugar.toInt(), caffeine.toInt(), mask)) }) { Text("Save preferences") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

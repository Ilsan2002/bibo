@file:OptIn(ExperimentalMaterial3Api::class)

package com.bibo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.Shower
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bibo.data.BiboDb
import com.bibo.data.FoodEntry
import com.bibo.data.HabitDay
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val CAL = Color(0xFFEF6C00)
private val SUGAR = Color(0xFFEC407A)
private val CAFFEINE = Color(0xFF8D6E63)
private val dateFmt = DateTimeFormatter.ofPattern("EEE, MMM d")

private enum class Habit { SHOWER, CLOTHES, WORKOUT, PRAY }

@Composable
fun HealthScreen() {
    val context = LocalContext.current
    val db = remember { BiboDb.get(context) }
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val parser = remember { HealthParser(context) }
    val snackbar = remember { SnackbarHostState() }

    var epochDay by rememberSaveable { mutableLongStateOf(LocalDate.now().toEpochDay()) }
    val date = LocalDate.ofEpochDay(epochDay)
    val isToday = epochDay == LocalDate.now().toEpochDay()

    val habitFlow = remember(epochDay) { db.habits().forDay(epochDay) }
    val habitDay by habitFlow.collectAsState(initial = null)
    val habit = habitDay ?: HabitDay(epochDay)

    val foodFlow = remember(epochDay) { db.foods().forDay(epochDay) }
    val foods by foodFlow.collectAsState(initial = emptyList())
    val calories = foods.sumOf { it.calories }
    val sugar = foods.sumOf { it.sugarG }
    val caffeine = foods.sumOf { it.caffeineMg }

    val week = remember(epochDay) { (6 downTo 0).map { date.minusDays(it.toLong()) } }
    val weekFlow = remember(epochDay) {
        db.habits().range(week.first().toEpochDay(), week.last().toEpochDay())
    }
    val weekHabits by weekFlow.collectAsState(initial = emptyList())
    val weekByDay = weekHabits.associateBy { it.epochDay }

    var showAdd by remember { mutableStateOf(false) }

    fun toggle(which: Habit) {
        haptics.toggleOn()
        val next = when (which) {
            Habit.SHOWER -> habit.copy(showered = !habit.showered)
            Habit.CLOTHES -> habit.copy(cleanClothes = !habit.cleanClothes)
            Habit.WORKOUT -> habit.copy(workedOut = !habit.workedOut)
            Habit.PRAY -> habit.copy(prayed = !habit.prayed)
        }
        scope.launch(Dispatchers.IO) { db.habits().upsert(next) }
    }

    fun logText(text: String) {
        scope.launch {
            val log = withContext(Dispatchers.IO) { parser.parse(text) }
            if (log.isEmpty) {
                haptics.reject()
                snackbar.showSnackbar("Didn't catch any food or habit")
                return@launch
            }
            withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                log.foods.forEach {
                    db.foods().insert(
                        FoodEntry(
                            epochDay = epochDay, createdAt = now, label = it.label,
                            calories = it.calories, sugarG = it.sugarG, caffeineMg = it.caffeineMg,
                        )
                    )
                }
                val cur = db.habits().get(epochDay) ?: HabitDay(epochDay)
                val merged = cur.copy(
                    showered = log.showered ?: cur.showered,
                    cleanClothes = log.cleanClothes ?: cur.cleanClothes,
                    workedOut = log.workedOut ?: cur.workedOut,
                    prayed = log.prayed ?: cur.prayed,
                )
                if (merged != cur) db.habits().upsert(merged)
            }
            haptics.confirm()
            val parts = buildList {
                if (log.foods.isNotEmpty()) add(log.foods.joinToString(", ") { it.label })
                val n = listOfNotNull(log.showered, log.cleanClothes, log.workedOut, log.prayed).size
                if (n > 0) add("$n habit${if (n > 1) "s" else ""}")
            }
            snackbar.showSnackbar("Logged " + parts.joinToString(" · "))
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 120.dp),
            ) {
                item(key = "head") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 8.dp, top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Daily",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { epochDay-- }) {
                            Icon(Icons.Filled.ChevronLeft, "Previous day")
                        }
                        Text(date.format(dateFmt), style = MaterialTheme.typography.titleSmall)
                        IconButton(onClick = { epochDay++ }, enabled = !isToday) {
                            Icon(Icons.Filled.ChevronRight, "Next day")
                        }
                    }
                }

                item(key = "intake") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        IntakeCard("Calories", calories.toString(), "kcal", calories / 2000f, CAL, Modifier.weight(1f))
                        IntakeCard("Sugar", sugar.toInt().toString(), "g", sugar.toFloat() / 50f, SUGAR, Modifier.weight(1f))
                        IntakeCard("Caffeine", caffeine.toString(), "mg", caffeine / 400f, CAFFEINE, Modifier.weight(1f))
                    }
                }

                item(key = "habits") {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            HabitToggle("Showered", Icons.Filled.Shower, habit.showered, { toggle(Habit.SHOWER) }, Modifier.weight(1f))
                            HabitToggle("Clean clothes", Icons.Filled.Checkroom, habit.cleanClothes, { toggle(Habit.CLOTHES) }, Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            HabitToggle("Worked out", Icons.Filled.FitnessCenter, habit.workedOut, { toggle(Habit.WORKOUT) }, Modifier.weight(1f))
                            HabitToggle("Prayed", Icons.Filled.SelfImprovement, habit.prayed, { toggle(Habit.PRAY) }, Modifier.weight(1f))
                        }
                    }
                }

                item(key = "week") {
                    WeeklyStrip(week, weekByDay, epochDay) { epochDay = it }
                }

                item(key = "food-head") {
                    Text(
                        "Food & drink",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
                    )
                }

                if (foods.isEmpty()) {
                    item(key = "food-empty") {
                        Text(
                            "Nothing logged yet. Hold the mic and say what you had, or tap +.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                } else {
                    items(foods, key = { it.id }) { f ->
                        FoodRow(f, onDelete = { scope.launch(Dispatchers.IO) { db.foods().delete(f) } })
                    }
                }
            }

            HoldToTalkButton(
                onTranscript = { logText(it) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 20.dp),
            )
            FloatingActionButton(
                onClick = { showAdd = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(20.dp),
            ) { Icon(Icons.Filled.Add, "Add food") }
        }
    }

    if (showAdd) {
        AddFoodSheet(
            onDismiss = { showAdd = false },
            onAdd = { text -> showAdd = false; logText(text) },
        )
    }
}

@Composable
private fun IntakeCard(
    label: String,
    value: String,
    unit: String,
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(2.dp))
            Text(unit, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(bottom = 3.dp))
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(color),
            )
        }
    }
}

@Composable
private fun HabitToggle(
    label: String,
    icon: ImageVector,
    done: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (done) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = modifier,
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (done) FontWeight.SemiBold else FontWeight.Normal,
                color = if (done) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun WeeklyStrip(
    week: List<LocalDate>,
    byDay: Map<Long, HabitDay>,
    selected: Long,
    onPick: (Long) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
        Text(
            "This week",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            week.forEach { d ->
                val h = byDay[d.toEpochDay()]
                val flags = listOf(h?.showered == true, h?.cleanClothes == true, h?.workedOut == true, h?.prayed == true)
                val isSel = d.toEpochDay() == selected
                Column(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .clickable { onPick(d.toEpochDay()) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        d.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSel) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    // 2x2 dot grid of habit completion
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            Dot(flags[0]); Dot(flags[1])
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            Dot(flags[2]); Dot(flags[3])
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Dot(on: Boolean) {
    Box(
        Modifier
            .size(7.dp)
            .clip(CircleShape)
            .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
    )
}

@Composable
private fun FoodRow(food: FoodEntry, onDelete: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Restaurant,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(food.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                buildString {
                    append("${food.calories} kcal")
                    if (food.sugarG > 0) append(" · ${food.sugarG.toInt()}g sugar")
                    if (food.caffeineMg > 0) append(" · ${food.caffeineMg}mg caffeine")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.DeleteOutline, "Delete", tint = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun AddFoodSheet(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Add food or drink", style = MaterialTheme.typography.titleLarge)
            Text(
                "Type what you had — nutrition is estimated for you.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("e.g. coffee and a banana") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) onAdd(text.trim()) }),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { onAdd(text.trim()) },
                enabled = text.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Add") }
        }
    }
}

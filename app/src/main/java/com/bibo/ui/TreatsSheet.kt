@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.bibo.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bibo.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun TreatsSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val db = remember(context) { BiboDb.get(context) }
    val items by db.wishlist().all().collectAsStateWithLifecycle(emptyList())
    val ledger by db.rewards().all().collectAsStateWithLifecycle(emptyList())
    val wallet by remember(context) { Rewards.observe(context) }.collectAsStateWithLifecycle(RewardBalance(budget = Rewards.budgetCents(context)))
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var price by rememberSaveable { mutableStateOf("") }
    var budgetInput by rememberSaveable { mutableStateOf("") }
    var editBudget by rememberSaveable { mutableStateOf(false) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val snackbar = remember { SnackbarHostState() }
    fun perform(id: Long, action: suspend () -> String) {
        if (id in pending) return
        pending = pending + id
        AppScope.io.launch {
            val message = try { action() } catch (error: Exception) { error.message ?: "Action failed. Try again." }
            withContext(Dispatchers.Main) {
                pending = pending - id
                snackbar.showSnackbar(message)
            }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Treats", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close treats") }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Available ${Rewards.format(wallet.available)}", style = MaterialTheme.typography.headlineSmall)
                    Text("Earned ${Rewards.format(wallet.earned)} · spent ${Rewards.format(wallet.spent)} · weekly cap ${Rewards.format(wallet.budget)}")
                    LinearProgressIndicator(progress = { if (wallet.budget == 0) 0f else (wallet.earned.toFloat() / wallet.budget).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text("USD · one reward per task. Reopening, editing, or deleting tasks keeps earned credit. Unspent weekly credit expires Monday in your local timezone.", style = MaterialTheme.typography.bodySmall)
                    if (editBudget) {
                        OutlinedTextField(budgetInput, { budgetInput = it }, label = { Text("Weekly budget (USD)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = -1L !in pending, onClick = {
                                val cents = if (budgetInput.trim() in setOf("0", "0.0", "0.00")) 0 else RewardPolicy.parseCents(budgetInput)
                                if (cents != null) perform(-1) {
                                    if (Rewards.setBudgetCents(context, cents)) {
                                        withContext(Dispatchers.Main) { editBudget = false }
                                        "Budget updated"
                                    } else "Budget cannot be below money already spent this week"
                                } else perform(-1) { "Enter a valid amount with no more than two decimal places" }
                            }) { Text("Set budget") }
                            TextButton(onClick = { editBudget = false }) { Text("Cancel") }
                        }
                    } else TextButton(onClick = { budgetInput = BigDecimal.valueOf(wallet.budget.toLong(), 2).toPlainString(); editBudget = true }) { Text("Change weekly budget") }
                }
            }
            Text("Wishlist", style = MaterialTheme.typography.titleLarge)
            if (items.isEmpty()) Text("Add something you want to work toward.")
            items.forEach { item -> key(item.id) {
                var menu by remember { mutableStateOf(false) }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(item.name, style = MaterialTheme.typography.titleMedium)
                                Text(Rewards.format(item.priceCents))
                                if (item.redeemedAt != null) Text("Treated · debit stays in history", style = MaterialTheme.typography.bodySmall)
                                else if (item.priceCents > wallet.budget) Text("Above this week's cap · increase budget to earn it", style = MaterialTheme.typography.bodySmall)
                            }
                            Box {
                                IconButton(enabled = item.id !in pending, onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Manage ${item.name}") }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    if (item.redeemedAt == null) DropdownMenuItem(text = { Text("Edit") }, onClick = {
                                        editingId = item.id; name = item.name; price = BigDecimal.valueOf(item.priceCents.toLong(), 2).toPlainString(); menu = false
                                    })
                                    DropdownMenuItem(text = { Text("Remove from wishlist") }, onClick = {
                                        menu = false; perform(item.id) { Rewards.removeWish(context, item.id); "Removed from wishlist. Spending history is unchanged." }
                                    })
                                }
                            }
                        }
                        if (item.redeemedAt == null) Button(enabled = item.id !in pending && wallet.available >= item.priceCents, onClick = {
                            perform(item.id) { if (Rewards.redeem(context, item.id)) "Enjoy your treat" else "This treat is no longer affordable or was already redeemed" }
                        }) { Text(if (item.id in pending) "Working…" else "Treat") }
                    }
                }
            } }
            Text(if (editingId == null) "Add a wish" else "Edit wish", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(name, { name = it }, label = { Text("Something you want") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(price, { price = it }, label = { Text("Price (USD)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            val cents = RewardPolicy.parseCents(price)
            if (price.isNotBlank() && cents == null) Text("Enter a positive amount with at most two decimal places", color = MaterialTheme.colorScheme.error)
            Button(enabled = name.isNotBlank() && cents != null && -2L !in pending, modifier = Modifier.fillMaxWidth(), onClick = {
                val capturedId = editingId; val capturedName = name; val capturedCents = cents ?: return@Button
                perform(-2) {
                    if (Rewards.saveWish(context, capturedId, capturedName, capturedCents)) {
                        withContext(Dispatchers.Main) { editingId = null; name = ""; price = "" }
                        "Wishlist saved"
                    } else "Wish could not be changed; redeemed amounts stay immutable"
                }
            }) { Text(if (editingId == null) "Add wish" else "Save wish") }
            if (editingId != null) TextButton(onClick = { editingId = null; name = ""; price = "" }) { Text("Cancel edit") }
            TextButton(onClick = { showHistory = !showHistory }) { Text(if (showHistory) "Hide reward history" else "Show reward history") }
            if (showHistory) {
                if (ledger.isEmpty()) Text("No rewards or spending yet")
                ledger.filter { it.amountCents > 0 }.forEach { entry ->
                    Text("${if (entry.kind == "CREDIT") "+" else "−"}${Rewards.format(entry.amountCents)} · ${entry.label}")
                    Text(Instant.ofEpochMilli(entry.atMillis).atZone(AppTime.zone()).format(DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm")) +
                        if (entry.provenance == "LEGACY_SNAPSHOT") " · legacy opening snapshot" else "", style = MaterialTheme.typography.bodySmall)
                }
            }
            SnackbarHost(snackbar)
        }
    }
}

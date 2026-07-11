@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bibo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bibo.data.BiboDb
import com.bibo.data.Rewards
import com.bibo.data.WishlistItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "Treats" — earn money by finishing tasks, spend it on your хотелки. */
@Composable
fun TreatsSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { BiboDb.get(context) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val items by db.wishlist().all().collectAsStateWithLifecycle(emptyList())
    var earned by remember { mutableIntStateOf(0) }
    var available by remember { mutableIntStateOf(0) }
    var budget by remember { mutableIntStateOf(Rewards.budgetCents(context)) }
    var refresh by remember { mutableIntStateOf(0) }

    // Recompute money whenever the wishlist changes or we ask for a refresh.
    LaunchedEffect(items, refresh) {
        earned = withContext(Dispatchers.IO) { Rewards.earnedCents(context) }
        available = withContext(Dispatchers.IO) { Rewards.availableCents(context) }
        budget = Rewards.budgetCents(context)
    }

    var newName by remember { mutableStateOf("") }
    var newPrice by remember { mutableStateOf("") }
    var editingBudget by remember { mutableStateOf(false) }
    var budgetInput by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Treats 💰", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Close") }
            }

            // This week's earnings toward the budget.
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Earned this week",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${Rewards.format(earned)} / ${Rewards.format(budget)}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    LinearProgressIndicator(
                        progress = { if (budget > 0) (earned.toFloat() / budget).coerceIn(0f, 1f) else 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Available to spend: ${Rewards.format(available)}  ·  resets Monday",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (editingBudget) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = budgetInput,
                                onValueChange = { budgetInput = it.filter { c -> c.isDigit() || c == '.' } },
                                label = { Text("Weekly budget $") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                                modifier = Modifier.weight(1f),
                            )
                            Button(onClick = {
                                val cents = ((budgetInput.toDoubleOrNull() ?: 0.0) * 100).toInt()
                                Rewards.setBudgetCents(context, cents)
                                editingBudget = false
                                refresh++
                            }) { Text("Set") }
                        }
                    } else {
                        TextButton(onClick = { budgetInput = (budget / 100).toString(); editingBudget = true }) {
                            Text("Change weekly budget")
                        }
                    }
                }
            }

            Text("хотелки — what you're working toward", style = MaterialTheme.typography.titleMedium)

            if (items.isEmpty()) {
                Text(
                    "Add something you want. Finish tasks to earn it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items.forEach { item ->
                WishlistRow(
                    item = item,
                    canAfford = item.redeemedAt == null && available >= item.priceCents,
                    onRedeem = {
                        scope.launch {
                            if (Rewards.redeem(context, item)) refresh++
                        }
                    },
                    onDelete = { scope.launch { withContext(Dispatchers.IO) { db.wishlist().delete(item) } } },
                )
            }

            // Add a new хотелка.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Something you want") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = newPrice,
                    onValueChange = { newPrice = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("$") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    modifier = Modifier.width(96.dp),
                )
            }
            Button(
                onClick = {
                    val cents = ((newPrice.toDoubleOrNull() ?: 0.0) * 100).toInt()
                    if (newName.isNotBlank() && cents > 0) {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                db.wishlist().insert(
                                    WishlistItem(name = newName.trim(), priceCents = cents, createdAt = System.currentTimeMillis())
                                )
                            }
                            newName = ""; newPrice = ""
                        }
                    }
                },
                enabled = newName.isNotBlank() && (newPrice.toDoubleOrNull() ?: 0.0) > 0,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Add to wishlist") }
        }
    }
}

@Composable
private fun WishlistRow(
    item: WishlistItem,
    canAfford: Boolean,
    onRedeem: () -> Unit,
    onDelete: () -> Unit,
) {
    val redeemed = item.redeemedAt != null
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    textDecoration = if (redeemed) TextDecoration.LineThrough else null,
                    color = if (redeemed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    Rewards.format(item.priceCents),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when {
                redeemed -> Text("✓ treated", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                canAfford -> Button(onClick = onRedeem) { Text("Treat!") }
                else -> OutlinedButton(onClick = onDelete) { Text("Remove") }
            }
        }
    }
}

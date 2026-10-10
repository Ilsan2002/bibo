package com.bibo.data

import android.content.Context
import android.content.SharedPreferences
import androidx.room.withTransaction
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import java.text.NumberFormat
import java.time.ZoneId
import java.util.Currency
import java.util.Locale

/** USD reward game: lifetime task credits; unused weekly credit expires on Monday. */
object Rewards {
    private const val PREFS = "rewards"
    private const val KEY_BUDGET = "weekly_budget_cents"
    const val DEFAULT_BUDGET_CENTS = 3000
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun weekStart(): Long = RewardPolicy.week(AppTime.nowMillis(), AppTime.zone()).start
    fun budgetCents(context: Context): Int = prefs(context).getInt(KEY_BUDGET, DEFAULT_BUDGET_CENTS).coerceAtLeast(0)

    suspend fun setBudgetCents(context: Context, cents: Int): Boolean {
        if (cents < 0) return false
        return BiboDb.get(context).withTransaction {
            if (spentCents(context) > cents) false
            else prefs(context).edit().putInt(KEY_BUDGET, cents).commit()
        }
    }
    suspend fun balance(context: Context): RewardBalance {
        val week = RewardPolicy.week(AppTime.nowMillis(), AppTime.zone())
        val dao = BiboDb.get(context).rewards()
        return RewardPolicy.balance(dao.creditCentsSince(week.start, week.end),
            dao.debitCentsSince(week.start, week.end), budgetCents(context))
    }
    suspend fun earnedCents(context: Context): Int = balance(context).earned
    suspend fun spentCents(context: Context): Int = balance(context).spent
    suspend fun availableCents(context: Context): Int = balance(context).available

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observe(context: Context): Flow<RewardBalance> {
        val app = context.applicationContext
        val weeks = flow {
            while (true) { emit(RewardPolicy.week(AppTime.nowMillis(), AppTime.zone())); delay(30_000) }
        }.distinctUntilChanged()
        return weeks.flatMapLatest { week ->
            combine(BiboDb.get(app).rewards().listForWeek(week.start, week.end), budgetFlow(app)) { entries, budget ->
                RewardPolicy.balance(entries.filter { it.kind == "CREDIT" }.sumOf { it.amountCents.toLong() },
                    entries.filter { it.kind == "DEBIT" }.sumOf { it.amountCents.toLong() }, budget)
            }
        }
    }
    private fun budgetFlow(context: Context): Flow<Int> = callbackFlow {
        val preferences = prefs(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_BUDGET) trySend(budgetCents(context))
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(budgetCents(context))
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    /** Reread item and debit under a single transaction; stale/double taps cannot spend twice. */
    suspend fun redeem(context: Context, item: WishlistItem): Boolean = redeem(context, item.id)
    suspend fun redeem(context: Context, itemId: Long): Boolean {
        val db = BiboDb.get(context)
        return db.withTransaction {
            redeemInTransaction(db, itemId, budgetCents(context), AppTime.nowMillis(), AppTime.zone())
        }
    }
    suspend fun saveWish(context: Context, id: Long?, name: String, cents: Int): Boolean {
        if (name.isBlank() || cents <= 0) return false
        val db = BiboDb.get(context)
        return db.withTransaction {
            if (id == null) db.wishlist().insert(WishlistItem(name = name.trim(), priceCents = cents, createdAt = AppTime.nowMillis()))
            else {
                val item = db.wishlist().allOnce().firstOrNull { it.id == id } ?: return@withTransaction false
                if (item.redeemedAt != null) return@withTransaction false
                db.wishlist().update(item.copy(name = name.trim(), priceCents = cents))
            }
            true
        }
    }
    suspend fun removeWish(context: Context, id: Long) {
        val db = BiboDb.get(context)
        db.withTransaction { db.wishlist().allOnce().firstOrNull { it.id == id }?.let { db.wishlist().delete(it) } }
        // Its immutable debit remains; removing a redeemed wish is not a refund.
    }
    fun format(cents: Int): String = NumberFormat.getCurrencyInstance(Locale.US).apply {
        currency = Currency.getInstance("USD")
    }.format(cents / 100.0)
    val REWARD_TIERS = listOf(0, 100, 300, 500, 1000)
    val QUOTES = listOf("Small steps every day add up to big results.",
        "You don't have to be great to start, but you have to start to be great.",
        "One task. Right now. That's all it takes.", "Future you is watching. Make them proud.")
}

/** Caller owns the transaction, making credits + current-item check + debit one unit. */
internal suspend fun redeemInTransaction(db: BiboDb, itemId: Long, budget: Int, at: Long, zone: ZoneId): Boolean {
    val current = db.wishlist().allOnce().firstOrNull { it.id == itemId } ?: return false
    if (current.redeemedAt != null || current.priceCents <= 0) return false
    val week = RewardPolicy.week(at, zone)
    val balance = RewardPolicy.balance(db.rewards().creditCentsSince(week.start, week.end),
        db.rewards().debitCentsSince(week.start, week.end), budget)
    if (balance.available < current.priceCents) return false
    val inserted = db.rewards().insertIgnore(RewardEntry(entryKey = "wish:$itemId", kind = "DEBIT",
        amountCents = current.priceCents, atMillis = at, wishlistId = itemId, label = current.name))
    if (inserted < 0) return false
    db.wishlist().update(current.copy(redeemedAt = at))
    return true
}

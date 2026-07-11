package com.bibo.data

import android.content.Context
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * The "earn treat money by doing tasks" game. Each completed task banks its reward into the
 * current week's earnings (capped at a weekly budget the user sets aside). Wishlist items
 * (хотелки) are redeemed against those earnings. Everything is computed relative to the
 * current week's Monday, so it resets on its own each week — no reset job needed.
 */
object Rewards {
    private const val PREFS = "rewards"
    private const val KEY_BUDGET = "weekly_budget_cents"
    const val DEFAULT_BUDGET_CENTS = 3000 // $30 / week

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Start of the current week (Monday 00:00, local time) as epoch millis. */
    fun weekStart(): Long {
        val monday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return monday.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    fun budgetCents(context: Context): Int =
        prefs(context).getInt(KEY_BUDGET, DEFAULT_BUDGET_CENTS)

    fun setBudgetCents(context: Context, cents: Int) =
        prefs(context).edit().putInt(KEY_BUDGET, cents.coerceAtLeast(0)).apply()

    /** Money earned this week from completed tasks, capped at the weekly budget. */
    suspend fun earnedCents(context: Context): Int =
        BiboDb.get(context).todos().earnedCentsSince(weekStart())
            .coerceAtMost(budgetCents(context))

    /** Money already redeemed on wishlist items this week. */
    suspend fun spentCents(context: Context): Int =
        BiboDb.get(context).wishlist().redeemedCentsSince(weekStart())

    /** What's still available to treat yourself with this week. */
    suspend fun availableCents(context: Context): Int =
        (earnedCents(context) - spentCents(context)).coerceAtLeast(0)

    /** Treat yourself to a wishlist item if this week's earnings cover it. */
    suspend fun redeem(context: Context, item: WishlistItem): Boolean {
        if (item.redeemedAt != null) return false
        if (availableCents(context) < item.priceCents) return false
        BiboDb.get(context).wishlist().update(item.copy(redeemedAt = System.currentTimeMillis()))
        return true
    }

    fun format(cents: Int): String {
        val d = cents / 100
        val c = cents % 100
        return if (c == 0) "$$d" else "$%d.%02d".format(d, c)
    }

    /** Quick reward tiers offered when adding a task (by difficulty). */
    val REWARD_TIERS = listOf(0, 100, 300, 500, 1000) // none, $1, $3, $5, $10

    /** Rotating lines for the idle widget — gentle nudges + inspiration. */
    val QUOTES = listOf(
        "Small steps every day add up to big results.",
        "\"The secret of getting ahead is getting started.\" — Mark Twain",
        "You don't have to be great to start, but you have to start to be great.",
        "\"Discipline is choosing between what you want now and what you want most.\"",
        "One task. Right now. That's all it takes.",
        "\"Well done is better than well said.\" — Benjamin Franklin",
        "Future you is watching. Make them proud.",
        "\"Action is the foundational key to all success.\" — Picasso",
        "The work you avoid is usually the work that matters.",
        "\"Do the hard jobs first. The easy jobs will take care of themselves.\"",
    )
}

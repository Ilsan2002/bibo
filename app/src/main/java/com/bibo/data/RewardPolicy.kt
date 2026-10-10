package com.bibo.data

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

data class RewardWeek(val start: Long, val end: Long)
data class RewardBalance(val earned: Int = 0, val spent: Int = 0, val available: Int = 0, val budget: Int = 3000)

object RewardPolicy {
    fun week(at: Long, zone: ZoneId): RewardWeek {
        val monday = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return RewardWeek(monday.atStartOfDay(zone).toInstant().toEpochMilli(),
            monday.plusWeeks(1).atStartOfDay(zone).toInstant().toEpochMilli())
    }
    fun balance(credits: Long, debits: Long, budget: Int): RewardBalance {
        val limit = budget.coerceAtLeast(0)
        val earned = credits.coerceIn(0, limit.toLong()).toInt()
        val spent = debits.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        return RewardBalance(earned, spent, (earned.toLong() - spent).coerceAtLeast(0).toInt(), limit)
    }
    fun parseCents(input: String): Int? = runCatching {
        if (!input.trim().matches(Regex("\\d+(\\.\\d{1,2})?"))) return null
        BigDecimal(input.trim()).movePointRight(2).setScale(0, RoundingMode.UNNECESSARY)
            .intValueExact().takeIf { it > 0 }
    }.getOrNull()
}

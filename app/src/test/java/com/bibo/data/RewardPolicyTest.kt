package com.bibo.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class RewardPolicyTest {
    @Test fun centsAreExactAndMalformedAmountsAreRejected() {
        assertEquals(3001, RewardPolicy.parseCents("30.01"))
        assertEquals(1, RewardPolicy.parseCents("0.01"))
        for (amount in listOf("-1", "0", "1.001", "1.2.3", "NaN", "Infinity", "99999999999999999")) assertNull(amount, RewardPolicy.parseCents(amount))
    }
    @Test fun weekUsesLocalMondayAndDstAwareEnd() {
        val zone = ZoneId.of("America/New_York")
        val week = RewardPolicy.week(Instant.parse("2026-03-08T16:00:00Z").toEpochMilli(), zone)
        assertEquals(Instant.parse("2026-03-02T05:00:00Z").toEpochMilli(), week.start)
        assertEquals(Instant.parse("2026-03-09T04:00:00Z").toEpochMilli(), week.end)
        assertEquals(167 * 60 * 60 * 1000L, week.end - week.start)
    }
    @Test fun availableMoneyHonorsCapAndNeverWrapsOnLargeCreditSum() {
        assertEquals(RewardBalance(3000, 1000, 2000, 3000), RewardPolicy.balance(Long.MAX_VALUE, 1000, 3000))
        assertEquals(0, RewardPolicy.balance(1000, 2000, 3000).available)
        assertEquals(0, RewardPolicy.balance(1000, 0, 0).available)
    }
}

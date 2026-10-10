package com.bibo.data

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class RewardLedgerInstrumentedTest {
    private lateinit var db: BiboDb
    private val at = Instant.parse("2026-10-10T12:00:00Z").toEpochMilli()
    private val zone = ZoneId.of("UTC")
    @Before fun createDatabase() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), BiboDb::class.java).build() }
    @After fun closeDatabase() { db.close() }

    @Test fun concurrentPurchasesCannotBothSpendTheSameCredit() = runBlocking {
        db.rewards().insertIgnore(RewardEntry(entryKey = "task:1", kind = "CREDIT", amountCents = 1000, atMillis = at))
        val wishes = (1..2).map { db.wishlist().insert(WishlistItem(name = "Wish $it", priceCents = 1000, createdAt = at)) }
        val results = wishes.map { id -> async(Dispatchers.Default) {
            db.withTransaction { redeemInTransaction(db, id, 3000, at, zone) }
        } }.awaitAll()
        assertEquals(1, results.count { it })
        assertEquals(1000L, db.rewards().debitCentsSince(RewardPolicy.week(at, zone).start, RewardPolicy.week(at, zone).end))
        assertEquals(1, db.wishlist().allOnce().count { it.redeemedAt != null })
    }
    @Test fun repeatedRedemptionAndTaskRecompletionAreIdempotent() = runBlocking {
        val id = db.todos().insert(TodoTask(title = "Task", createdAt = at, rewardCents = 1000))
        val task = db.todos().byId(id)!!
        db.withTransaction { grantTaskReward(db, task, null, at); grantTaskReward(db, task.copy(rewardCents = 5000), null, at + 1) }
        assertEquals(1000, db.rewards().entry("task:$id")!!.amountCents)
        val wish = db.wishlist().insert(WishlistItem(name = "Wish", priceCents = 1000, createdAt = at))
        assertTrue(db.withTransaction { redeemInTransaction(db, wish, 3000, at, zone) })
        assertFalse(db.withTransaction { redeemInTransaction(db, wish, 3000, at, zone) })
        db.todos().delete(task)
        db.wishlist().delete(db.wishlist().allOnce().single())
        assertEquals(1000L, db.rewards().creditCentsSince(RewardPolicy.week(at, zone).start, RewardPolicy.week(at, zone).end))
        assertEquals(1000L, db.rewards().debitCentsSince(RewardPolicy.week(at, zone).start, RewardPolicy.week(at, zone).end))
    }
    @Test fun firstZeroRewardStillLocksTheLifetimeCredit() = runBlocking {
        val task = TodoTask(id = 7, title = "Initially free", createdAt = at, rewardCents = 0)
        db.withTransaction { grantTaskReward(db, task, null, at); grantTaskReward(db, task.copy(rewardCents = 1000), null, at + 1) }
        assertEquals(0, db.rewards().entry("task:7")!!.amountCents)
    }
}

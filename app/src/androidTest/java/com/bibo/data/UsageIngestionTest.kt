package com.bibo.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UsageIngestionTest {
    private lateinit var db: BiboDb
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, BiboDb::class.java).build() }
    @After fun close() { db.close() }
    private fun row(start: Long, end: Long) = UsageSessionEntity(packageName = "app", label = "App", startMillis = start, endMillis = end)

    @Test fun movingLookbackDoesNotDuplicateIntervalsOrChangeStableStartIdentity() = runBlocking {
        db.usage().upsertAll(listOf(row(0, 60), row(80, 140)))
        val originalId = db.usage().sessionsIn(0, 200).last().id
        db.usageCoverage().insert(UsageCoverage(0, 100, 100))
        persistUsageCollection(db, 70, 150, listOf(row(80, 150)), 1_000)
        val rows = db.usage().sessionsIn(0, 200)
        assertEquals(listOf(0L, 80L), rows.map { it.startMillis })
        assertEquals(originalId, rows.last().id)
        assertEquals(130L, rows.sumOf { it.endMillis - it.startMillis })
        assertEquals(listOf(UsageCoverage(0, 150, 150)), db.usageCoverage().allOnce())
        persistUsageCollection(db, 90, 160, listOf(row(100, 160)), 1_000)
        assertEquals(listOf(0L to 60L, 80L to 90L, 100L to 160L), db.usage().sessionsIn(0, 200).map { it.startMillis to it.endMillis })
    }
    @Test fun failedInsertRollsBackDeletionAndCoverageSoRefreshCanRetry() = runBlocking {
        db.usage().upsertAll(listOf(row(0, 100)))
        db.usageCoverage().insert(UsageCoverage(0, 100, 100))
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_usage BEFORE INSERT ON usage_sessions BEGIN SELECT RAISE(ABORT,'test failure'); END")
        assertTrue(runCatching { persistUsageCollection(db, 0, 150, listOf(row(0, 150)), 1_000) }.isFailure)
        assertEquals(100L, db.usage().sessionsIn(0, 200).single().endMillis)
        assertEquals(listOf(UsageCoverage(0, 100, 100)), db.usageCoverage().allOnce())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_usage")
        persistUsageCollection(db, 0, 150, listOf(row(0, 150)), 1_000)
        assertEquals(150L, db.usage().sessionsIn(0, 200).single().endMillis)
    }
    @Test fun anEmptyEventQueryPreservesHistoryAndDoesNotInventZeroCoverage() = runBlocking {
        db.usage().upsertAll(listOf(row(0, 100)))
        db.usageCoverage().insert(UsageCoverage(0, 100, 100))
        persistUsageCollection(db, null, 200, emptyList(), 1_000)
        assertEquals(100L, db.usage().sessionsIn(0, 300).single().endMillis)
        assertEquals(listOf(UsageCoverage(0, 100, 100)), db.usageCoverage().allOnce())
    }
}

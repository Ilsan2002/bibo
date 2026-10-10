package com.bibo.data

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionPersistenceTest {
    private lateinit var db: BiboDb
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, BiboDb::class.java).build() }
    @After fun close() { db.close() }
    private fun session(id: String = "A", pomodoro: Boolean = false) = WorkSession(id = id, title = id, kind = "FOCUS", startMillis = 1_000,
        elapsedAnchorMillis = 0, lastCheckpointMillis = 1_000, bootCount = 1, pomodoro = pomodoro)

    @Test fun duplicateStopsSaveOnce() = runBlocking {
        db.workSessions().insert(session())
        val applied = coroutineScope { (1..10).map { async(Dispatchers.Default) { db.withTransaction { SessionFinalizer.finishInTransaction(db, "A", 60_000) } } }.awaitAll() }
        assertEquals(1, applied.count { it })
        assertNull(db.workSessions().active())
        assertEquals(1, db.sessionSegments().forSession("A").size)
        assertEquals(1, db.activityBlocks().blocksInList(0, 100_000).size)
    }

    @Test fun stoppedSessionCannotStopReplacementOrAttachItsReflectionToReplacement() = runBlocking {
        db.workSessions().insert(session())
        db.withTransaction { SessionFinalizer.finishInTransaction(db, "A", 60_000) }
        db.workSessions().insert(session("B"))
        assertFalse(db.withTransaction { SessionFinalizer.finishInTransaction(db, "A", 120_000, "old reflection") })
        db.withTransaction { db.workSessions().setReflection("A", "old reflection"); db.sessionSegments().setReflection("A", "old reflection") }
        assertEquals("B", db.workSessions().active()!!.id)
        assertNull(db.workSessions().active()!!.note)
        assertEquals("old reflection", db.activityBlocks().blocksInList(0, 100_000).single().note)
    }

    @Test fun failedHistoryInsertKeepsSessionRecoverable() = runBlocking {
        db.workSessions().insert(session())
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_session_block BEFORE INSERT ON activity_blocks BEGIN SELECT RAISE(ABORT,'test rejection'); END")
        assertTrue(runCatching { db.withTransaction { SessionFinalizer.finishInTransaction(db, "A", 60_000) } }.isFailure)
        assertEquals("A", db.workSessions().active()!!.id)
        assertTrue(db.sessionSegments().forSession("A").isEmpty())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_session_block")
        assertTrue(db.withTransaction { SessionFinalizer.finishInTransaction(db, "A", 60_000) })
    }

    @Test fun sqliteEnforcesOneActiveSessionAndBreaksNeverBecomeMeasuredWork() = runBlocking {
        db.workSessions().insert(session(pomodoro = true))
        assertTrue(runCatching { db.workSessions().insert(session("B")) }.isFailure)
        db.withTransaction { SessionFinalizer.finishInTransaction(db, "A", 30 * 60_000L) }
        val parts = db.sessionSegments().forSession("A")
        assertEquals(listOf("WORK", "BREAK"), parts.map { it.kind })
        assertNull(parts.last().activityBlockId)
        val blocks = db.activityBlocks().blocksInList(0, Long.MAX_VALUE)
        assertEquals(25 * 60_000L, blocks.sumOf { it.endMillis - it.startMillis })
        assertEquals("MEASURED", blocks.single().provenance)
        assertEquals("A", blocks.single().sessionId)
    }
}

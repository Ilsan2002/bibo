package com.bibo.data

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MentorStateTest {
    private lateinit var db: BiboDb
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), BiboDb::class.java).build() }
    @After fun close() { db.close() }
    @Test fun factsOnlyDigestDoesNotDischargeConversationCompaction() = runBlocking {
        (100L..102L).forEach { day -> db.chat().insert(ChatMessage(epochDay = day, role = "USER", content = "Commitment $day", createdAt = day)) }
        db.chatDays().upsert(ChatDay(100, "conversation summary", conversationCompacted = true))
        db.chatDays().upsert(ChatDay(101, "facts only", conversationCompacted = false))
        assertEquals(listOf(101L, 102L), db.chat().undigestedDays(103))
        assertEquals("Commitment 101", db.chat().forDay(101).single().content)
    }
    @Test fun userCorrectionWinsOverStaleGeneratedMemory() = runBlocking {
        val initial = db.mentorMemory().insert(MentorMemoryRevision(notes = "Old fact", source = "MODEL", createdAt = 1))
        val user = db.mentorMemory().appendIfCurrent(initial, MentorMemoryRevision(notes = "Corrected fact", source = "USER", createdAt = 2))
        assertNotNull(user)
        val stale = db.mentorMemory().appendIfCurrent(initial, MentorMemoryRevision(notes = "Old generated fact", source = "COMPACTION", createdAt = 3))
        assertNull(stale)
        assertEquals("Corrected fact", db.mentorMemory().latest()?.notes)
        assertEquals(2, db.mentorMemory().all().first().size)
    }
    @Test fun historyPageIsBoundedOrderedAndIncludesExplicitErrorsOnlyInUi() = runBlocking {
        (1..50).forEach { i -> db.chat().insert(ChatMessage(epochDay = 100, role = if (i == 50) "ERROR" else "USER", content = "Message $i", createdAt = i.toLong())) }
        assertEquals(10, db.mentorHistory().recent(10).first().size)
        val context = db.mentorHistory().recentOnce(99, 5)
        assertEquals(5, context.size)
        assertEquals("Message 49", context.first().content)
        assertTrue(context.none { it.role == "ERROR" })
    }
    @Test fun turnStateSurvivesScreenReentry() = runBlocking {
        val pending = MentorTurn("turn-id", 100, "Start working", status = "APPLYING", createdAt = 1, updatedAt = 2)
        db.mentorTurns().insert(pending)
        assertEquals("APPLYING", db.mentorTurns().all().first().single().status)
        db.mentorTurns().update(pending.copy(status = "INTERRUPTED", error = "Review receipts", updatedAt = 3))
        assertTrue(db.mentorTurns().pendingOnce().isEmpty())
        assertEquals("Review receipts", db.mentorTurns().byId("turn-id")?.error)
    }
    @Test fun partialCompactionRetainsItsCursorWithoutDischargingRemainingMessages() = runBlocking {
        (1..45).forEach { index -> db.chat().insert(ChatMessage(epochDay = 100, role = "USER", content = "Fact $index", createdAt = index.toLong())) }
        db.withTransaction {
            db.mentorCompaction().upsert(MentorCompactionProgress(100, 20, "First page"))
            db.chatDays().upsert(ChatDay(100, "First page", conversationCompacted = false))
        }
        assertEquals(20, db.mentorCompaction().byDay(100)?.nextOffset)
        assertEquals("Fact 21", db.mentorHistory().forDayPage(100, 20, 20).first().content)
        assertEquals(listOf(100L), db.chat().undigestedDays(101))
        db.withTransaction {
            db.mentorCompaction().upsert(MentorCompactionProgress(100, 45, "All pages"))
            db.chatDays().upsert(ChatDay(100, "All pages", conversationCompacted = true))
        }
        assertTrue(db.chat().undigestedDays(101).isEmpty())
        assertEquals(45, db.mentorHistory().dayCount(100))
    }
    @Test fun deletedTreeReceiptRetainsTheDeletionBatchNeededForSafeUndo() {
        val row = TodoTask(id = 10, title = "Parent", createdAt = 1, sortOrder = 1, deletedAt = 2, deletedBy = "batch-A")
        val encoded = com.bibo.ui.MentorTools.taskJson(row)
        assertEquals(row, com.bibo.ui.MentorTools.taskFromJson(encoded))
    }
}

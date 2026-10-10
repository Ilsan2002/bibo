package com.bibo.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "mentor_turns")
data class MentorTurn(
    @PrimaryKey val id: String,
    val epochDay: Long,
    val text: String,
    val status: String = "QUEUED",
    val reply: String? = null,
    val error: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val attempt: Int = 0,
)

@Dao
interface MentorTurnDao {
    @Insert suspend fun insert(turn: MentorTurn)
    @Update suspend fun update(turn: MentorTurn)
    @Query("SELECT * FROM mentor_turns ORDER BY createdAt DESC LIMIT 100") fun all(): Flow<List<MentorTurn>>
    @Query("SELECT * FROM mentor_turns WHERE id = :id") suspend fun byId(id: String): MentorTurn?
    @Query("SELECT * FROM mentor_turns WHERE status IN ('QUEUED','GENERATING','APPLYING')") suspend fun pendingOnce(): List<MentorTurn>
    @Query("DELETE FROM mentor_turns") suspend fun clear()
}

@Entity(tableName = "mentor_memory_revisions")
data class MentorMemoryRevision(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val notes: String,
    val source: String,
    val createdAt: Long,
)

@Dao
interface MentorMemoryDao {
    @Insert suspend fun insert(revision: MentorMemoryRevision): Long
    @Query("SELECT * FROM mentor_memory_revisions ORDER BY id DESC LIMIT 1") suspend fun latest(): MentorMemoryRevision?
    @Query("SELECT * FROM mentor_memory_revisions ORDER BY id DESC LIMIT 20") fun all(): Flow<List<MentorMemoryRevision>>
    @Query("SELECT * FROM mentor_memory_revisions WHERE id = :id") suspend fun byId(id: Long): MentorMemoryRevision?
    @Query("DELETE FROM mentor_memory_revisions") suspend fun clear()
    @Transaction suspend fun appendIfCurrent(expectedId: Long, revision: MentorMemoryRevision): Long? {
        if ((latest()?.id ?: 0L) != expectedId) return null
        return insert(revision)
    }
}

@Dao
interface MentorHistoryDao {
    @Query("SELECT * FROM chat_messages ORDER BY id DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<ChatMessage>>
    @Query("SELECT * FROM chat_messages WHERE role != 'ERROR' AND epochDay >= :sinceDay ORDER BY id DESC LIMIT :limit")
    suspend fun recentOnce(sinceDay: Long, limit: Int): List<ChatMessage>
    @Query("SELECT * FROM chat_messages WHERE role != 'ERROR' AND epochDay=:day ORDER BY id LIMIT :limit OFFSET :offset")
    suspend fun forDayPage(day: Long, limit: Int, offset: Int): List<ChatMessage>
    @Query("SELECT COUNT(*) FROM chat_messages WHERE role != 'ERROR' AND epochDay=:day")
    suspend fun dayCount(day: Long): Int
    @Query("SELECT * FROM chat_messages WHERE role != 'ERROR' AND lower(content) LIKE '%' || lower(:query) || '%' ORDER BY id DESC LIMIT :limit OFFSET :offset")
    suspend fun searchPage(query: String, limit: Int, offset: Int): List<ChatMessage>
    @Query("SELECT * FROM chat_messages WHERE role != 'ERROR' ORDER BY id DESC LIMIT :limit OFFSET :offset")
    suspend fun historyPage(limit: Int, offset: Int): List<ChatMessage>
    @Query("SELECT * FROM chat_days ORDER BY epochDay DESC LIMIT :limit OFFSET :offset")
    suspend fun digestPage(limit: Int, offset: Int): List<ChatDay>
}

@Entity(tableName = "mentor_compaction_progress")
data class MentorCompactionProgress(
    @PrimaryKey val epochDay: Long,
    val nextOffset: Int,
    val digest: String,
)

@Dao
interface MentorCompactionDao {
    @Query("SELECT * FROM mentor_compaction_progress WHERE epochDay=:day")
    suspend fun byDay(day: Long): MentorCompactionProgress?
    @Upsert suspend fun upsert(progress: MentorCompactionProgress)
    @Query("DELETE FROM mentor_compaction_progress") suspend fun clear()
}

fun createMentorSchema(db: SupportSQLiteDatabase) {
    db.execSQL("CREATE TABLE IF NOT EXISTS mentor_turns (id TEXT NOT NULL, epochDay INTEGER NOT NULL, text TEXT NOT NULL, status TEXT NOT NULL, reply TEXT, error TEXT, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, attempt INTEGER NOT NULL, PRIMARY KEY(id))")
    db.execSQL("CREATE TABLE IF NOT EXISTS mentor_memory_revisions (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, notes TEXT NOT NULL, source TEXT NOT NULL, createdAt INTEGER NOT NULL)")
    db.execSQL("CREATE TABLE IF NOT EXISTS mentor_compaction_progress (epochDay INTEGER NOT NULL PRIMARY KEY, nextOffset INTEGER NOT NULL, digest TEXT NOT NULL)")
}

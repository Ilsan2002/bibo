package com.bibo.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** A durable session. The unique nullable slot makes multiple active sessions impossible. */
@Entity(tableName = "work_sessions", indices = [Index(value = ["activeSlot"], unique = true)])
data class WorkSession(
    @PrimaryKey val id: String,
    val activeSlot: Int? = 1,
    val status: String = "ACTIVE",
    val title: String,
    val kind: String,
    val taskId: Long? = null,
    val goalId: Long? = null,
    val startMillis: Long,
    val elapsedAnchorMillis: Long,
    val elapsedAtAnchorMillis: Long = 0,
    val lastCheckpointMillis: Long,
    val bootCount: Int,
    val pomodoro: Boolean = false,
    val workMin: Int = 25,
    val breakMin: Int = 5,
    val blockedPackages: String = "",
    val dnd: Boolean = false,
    val endMillis: Long? = null,
    val note: String? = null,
    val comment: String = "",
)

@Entity(tableName = "session_segments", indices = [Index(value = ["sessionId"])])
data class SessionSegment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val kind: String,
    val startMillis: Long,
    val endMillis: Long,
    val activityBlockId: Long? = null,
)

@Dao
interface WorkSessionDao {
    @Insert suspend fun insert(session: WorkSession)
    @Update suspend fun update(session: WorkSession)
    @Query("SELECT * FROM work_sessions WHERE activeSlot = 1 LIMIT 1")
    suspend fun active(): WorkSession?
    @Query("SELECT * FROM work_sessions WHERE activeSlot = 1 LIMIT 1")
    fun activeFlow(): Flow<WorkSession?>
    @Query("SELECT * FROM work_sessions WHERE status = 'INTERRUPTED' ORDER BY lastCheckpointMillis DESC LIMIT 10")
    fun interruptedFlow(): Flow<List<WorkSession>>
    @Query("SELECT * FROM work_sessions WHERE id = :id")
    suspend fun byId(id: String): WorkSession?
    @Query("UPDATE work_sessions SET comment = :comment WHERE id = :id AND activeSlot = 1")
    suspend fun setComment(id: String, comment: String)
    @Query("UPDATE work_sessions SET note = :note WHERE id = :id AND activeSlot IS NULL")
    suspend fun setReflection(id: String, note: String?)
    @Query("UPDATE todo_tasks SET startedAt = :startedAt WHERE id = :taskId AND completedAt IS NULL")
    suspend fun setTaskRunning(taskId: Long, startedAt: Long?)
    @Query("UPDATE todo_tasks SET startedAt = NULL WHERE startedAt IS NOT NULL")
    suspend fun clearLegacyTaskMarkers()
}

@Dao
interface SessionSegmentDao {
    @Insert suspend fun insert(segment: SessionSegment): Long
    @Query("SELECT * FROM session_segments WHERE sessionId = :sessionId ORDER BY startMillis")
    suspend fun forSession(sessionId: String): List<SessionSegment>
    @Query("UPDATE activity_blocks SET note = :note WHERE id IN (SELECT activityBlockId FROM session_segments WHERE sessionId = :sessionId)")
    suspend fun setReflection(sessionId: String, note: String?)
    @Query("SELECT * FROM session_segments WHERE kind = 'WORK' AND startMillis < :end AND endMillis > :start ORDER BY startMillis")
    fun workIn(start: Long, end: Long): Flow<List<SessionSegment>>
}

fun createSessionSchema(db: SupportSQLiteDatabase) {
    db.execSQL("CREATE TABLE IF NOT EXISTS work_sessions (id TEXT NOT NULL PRIMARY KEY, activeSlot INTEGER, status TEXT NOT NULL, title TEXT NOT NULL, kind TEXT NOT NULL, taskId INTEGER, goalId INTEGER, startMillis INTEGER NOT NULL, elapsedAnchorMillis INTEGER NOT NULL, elapsedAtAnchorMillis INTEGER NOT NULL, lastCheckpointMillis INTEGER NOT NULL, bootCount INTEGER NOT NULL, pomodoro INTEGER NOT NULL, workMin INTEGER NOT NULL, breakMin INTEGER NOT NULL, blockedPackages TEXT NOT NULL, dnd INTEGER NOT NULL, endMillis INTEGER, note TEXT, comment TEXT NOT NULL)")
    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_work_sessions_activeSlot ON work_sessions(activeSlot)")
    db.execSQL("CREATE TABLE IF NOT EXISTS session_segments (id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, sessionId TEXT NOT NULL, kind TEXT NOT NULL, startMillis INTEGER NOT NULL, endMillis INTEGER NOT NULL, activityBlockId INTEGER)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_session_segments_sessionId ON session_segments(sessionId)")
}

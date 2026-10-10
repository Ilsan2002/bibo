package com.bibo.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** The result commits beside local effects, so retry is based on identity, not a title. */
@Entity(tableName = "command_records", indices = [Index(value = ["status"])])
data class CommandRecord(
    @PrimaryKey val id: String,
    val kind: String,
    val status: String,
    val input: String,
    val result: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Dao
interface CommandDao {
    @Query("DELETE FROM command_records WHERE kind=lower(kind) OR id IN (SELECT id || ':local' FROM command_records WHERE kind=lower(kind)) OR EXISTS (SELECT 1 FROM command_records AS outer_record WHERE outer_record.kind=lower(outer_record.kind) AND command_records.id LIKE outer_record.id || ':%:local')")
    suspend fun clearMentor()

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertIgnore(record: CommandRecord): Long
    @Upsert suspend fun upsert(record: CommandRecord)
    @Query("SELECT * FROM command_records WHERE id=:id") suspend fun byId(id: String): CommandRecord?
    @Query("SELECT * FROM command_records WHERE status IN ('PENDING','APPLYING','AMBIGUOUS') ORDER BY createdAt")
    suspend fun allPending(): List<CommandRecord>
    @Query("SELECT * FROM command_records ORDER BY createdAt DESC LIMIT 200") fun all(): Flow<List<CommandRecord>>
}

@Entity(tableName = "task_completions", indices = [Index(value = ["taskId"])])
data class TaskCompletion(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val atMillis: Long,
    val reversedAt: Long? = null,
)

@Dao
interface TaskCompletionDao {
    @Insert suspend fun insert(completion: TaskCompletion): Long
    @Query("SELECT * FROM task_completions WHERE id=:id") suspend fun byId(id: Long): TaskCompletion?
    @Query("UPDATE task_completions SET reversedAt=:at WHERE id=:id AND reversedAt IS NULL")
    suspend fun reverse(id: Long, at: Long)
}

fun createCommandSchema(db: SupportSQLiteDatabase) {
    db.execSQL("CREATE TABLE IF NOT EXISTS command_records (id TEXT NOT NULL PRIMARY KEY, kind TEXT NOT NULL, status TEXT NOT NULL, input TEXT NOT NULL, result TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_command_records_status ON command_records(status)")
    db.execSQL("CREATE TABLE IF NOT EXISTS task_completions (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, taskId INTEGER NOT NULL, atMillis INTEGER NOT NULL, reversedAt INTEGER)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_task_completions_taskId ON task_completions(taskId)")
}

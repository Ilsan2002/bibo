package com.bibo.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** Immutable reward history. Entity removal and reopening never remove earned/spent money. */
@Entity(tableName = "reward_entries", indices = [
    Index(value = ["entryKey"], unique = true), Index(value = ["atMillis"]),
])
data class RewardEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entryKey: String,
    val kind: String,
    val amountCents: Int,
    val atMillis: Long,
    val taskId: Long? = null,
    val wishlistId: Long? = null,
    val completionId: Long? = null,
    val provenance: String = "COMMAND",
    val label: String = "",
)

@Dao
interface RewardDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entry: RewardEntry): Long
    @Query("SELECT * FROM reward_entries WHERE entryKey = :key LIMIT 1")
    suspend fun entry(key: String): RewardEntry?
    @Query("SELECT * FROM reward_entries ORDER BY atMillis DESC, id DESC")
    fun all(): Flow<List<RewardEntry>>
    @Query("SELECT * FROM reward_entries WHERE atMillis >= :since AND atMillis < :until ORDER BY atMillis DESC, id DESC")
    fun listForWeek(since: Long, until: Long): Flow<List<RewardEntry>>
    @Query("SELECT COALESCE(SUM(amountCents), 0) FROM reward_entries WHERE kind = 'CREDIT' AND atMillis >= :since AND atMillis < :until")
    suspend fun creditCentsSince(since: Long, until: Long): Long
    @Query("SELECT COALESCE(SUM(amountCents), 0) FROM reward_entries WHERE kind = 'DEBIT' AND atMillis >= :since AND atMillis < :until")
    suspend fun debitCentsSince(since: Long, until: Long): Long
}

/** Called inside the same transaction as the completion. One credit per task, for life. */
suspend fun grantTaskReward(db: BiboDb, task: TodoTask, completionId: Long?, at: Long) {
    if (task.rewardCents < 0) return
    db.rewards().insertIgnore(RewardEntry(
        entryKey = "task:${task.id}", kind = "CREDIT", amountCents = task.rewardCents,
        atMillis = at, taskId = task.id, completionId = completionId, label = task.title,
    ))
}

/** A labelled opening snapshot, not a claim that legacy mutable history is reconstructable. */
fun createRewardSchema(db: SupportSQLiteDatabase) {
    db.execSQL("CREATE TABLE IF NOT EXISTS reward_entries (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, entryKey TEXT NOT NULL, kind TEXT NOT NULL, amountCents INTEGER NOT NULL, atMillis INTEGER NOT NULL, taskId INTEGER, wishlistId INTEGER, completionId INTEGER, provenance TEXT NOT NULL, label TEXT NOT NULL)")
    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_reward_entries_entryKey ON reward_entries(entryKey)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_reward_entries_atMillis ON reward_entries(atMillis)")
    db.execSQL("INSERT OR IGNORE INTO reward_entries(entryKey, kind, amountCents, atMillis, taskId, provenance, label) SELECT 'task:' || id, 'CREDIT', MAX(rewardCents, 0), completedAt, id, 'LEGACY_SNAPSHOT', title FROM todo_tasks WHERE completedAt IS NOT NULL")
    db.execSQL("INSERT OR IGNORE INTO reward_entries(entryKey, kind, amountCents, atMillis, wishlistId, provenance, label) SELECT 'wish:' || id, 'DEBIT', MAX(priceCents, 0), redeemedAt, id, 'LEGACY_SNAPSHOT', name FROM wishlist_items WHERE redeemedAt IS NOT NULL AND priceCents > 0")
}

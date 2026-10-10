package com.bibo.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "usage_coverage")
data class UsageCoverage(
    @PrimaryKey val startMillis: Long,
    val endMillis: Long,
    val collectedAt: Long,
)

@Dao
interface UsageCoverageDao {
    @Query("SELECT * FROM usage_coverage ORDER BY startMillis")
    fun all(): Flow<List<UsageCoverage>>

    @Query("SELECT * FROM usage_coverage ORDER BY startMillis")
    suspend fun allOnce(): List<UsageCoverage>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(coverage: UsageCoverage)

    @Query("DELETE FROM usage_coverage")
    suspend fun clear()

    @Query("DELETE FROM usage_coverage WHERE endMillis < :before")
    suspend fun pruneBefore(before: Long)
}

fun createUsageSchema(db: SupportSQLiteDatabase) {
    db.execSQL("CREATE TABLE IF NOT EXISTS usage_coverage (startMillis INTEGER NOT NULL PRIMARY KEY, endMillis INTEGER NOT NULL, collectedAt INTEGER NOT NULL)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_usage_sessions_startMillis ON usage_sessions(startMillis)")
}

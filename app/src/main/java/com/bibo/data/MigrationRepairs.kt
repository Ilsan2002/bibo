package com.bibo.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** Original invalid links stay available for inspection after an additive upgrade. */
@Entity(tableName = "migration_repairs")
data class MigrationRepair(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val field: String,
    val previousValue: String?,
    val newValue: String?,
    val reason: String,
)

@Dao
interface MigrationRepairDao {
    @Query("SELECT * FROM migration_repairs ORDER BY id") fun all(): Flow<List<MigrationRepair>>
    @Query("SELECT * FROM migration_repairs ORDER BY id") suspend fun allOnce(): List<MigrationRepair>
}

fun createMigrationRepairSchema(db: SupportSQLiteDatabase) {
    db.execSQL("CREATE TABLE IF NOT EXISTS migration_repairs (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, taskId INTEGER NOT NULL, field TEXT NOT NULL, previousValue TEXT, newValue TEXT, reason TEXT NOT NULL)")
}

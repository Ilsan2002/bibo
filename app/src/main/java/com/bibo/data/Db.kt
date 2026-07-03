package com.bibo.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * A logged block of time that shows up on the calendar:
 * manually added events, timer sessions, completed todos, voice logs.
 */
@Entity(tableName = "activity_blocks")
data class ActivityBlock(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val source: String, // MANUAL, TIMER, TODO, VOICE
    val color: Int? = null,
)

@Dao
interface ActivityBlockDao {
    @Insert
    suspend fun insert(block: ActivityBlock): Long

    @Update
    suspend fun update(block: ActivityBlock)

    @Query(
        "SELECT * FROM activity_blocks " +
            "WHERE startMillis < :windowEnd AND endMillis > :windowStart " +
            "ORDER BY startMillis"
    )
    fun blocksIn(windowStart: Long, windowEnd: Long): Flow<List<ActivityBlock>>

    @Query(
        "SELECT * FROM activity_blocks " +
            "WHERE startMillis < :windowEnd AND endMillis > :windowStart " +
            "ORDER BY startMillis"
    )
    suspend fun blocksInList(windowStart: Long, windowEnd: Long): List<ActivityBlock>

    @Query(
        "SELECT DISTINCT title FROM activity_blocks " +
            "WHERE source = 'TIMER' ORDER BY id DESC LIMIT 8"
    )
    suspend fun recentTimerTitles(): List<String>

    @Delete
    suspend fun delete(block: ActivityBlock)
}

@Entity(tableName = "todo_tasks")
data class TodoTask(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val parentId: Long? = null,
    val createdAt: Long,
    val completedAt: Long? = null,
    val startedAt: Long? = null, // non-null while its timer is running
    val sortOrder: Long = 0, // manual drag-reorder position; defaults to createdAt
)

@Dao
interface TodoDao {
    @Insert
    suspend fun insert(task: TodoTask): Long

    @Update
    suspend fun update(task: TodoTask)

    @Update
    suspend fun updateAll(tasks: List<TodoTask>)

    @Delete
    suspend fun delete(task: TodoTask)

    @Query("DELETE FROM todo_tasks WHERE parentId = :parentId")
    suspend fun deleteChildren(parentId: Long)

    @Query("SELECT * FROM todo_tasks ORDER BY sortOrder")
    fun all(): Flow<List<TodoTask>>
}

/**
 * Persisted app-usage session (foreground interval). Cached into Room because the
 * system prunes detailed usage events after a few days; unique on (package, start)
 * so re-ingesting an in-progress session just updates its end time.
 */
@Entity(
    tableName = "usage_sessions",
    indices = [Index(value = ["packageName", "startMillis"], unique = true)],
)
data class UsageSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val label: String,
    val startMillis: Long,
    val endMillis: Long,
)

@Dao
interface UsageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(sessions: List<UsageSessionEntity>)

    @Query(
        "SELECT * FROM usage_sessions " +
            "WHERE startMillis < :windowEnd AND endMillis > :windowStart " +
            "ORDER BY startMillis"
    )
    suspend fun sessionsIn(windowStart: Long, windowEnd: Long): List<UsageSessionEntity>

    @Query("DELETE FROM usage_sessions WHERE endMillis < :before")
    suspend fun pruneBefore(before: Long)

    @Query("DELETE FROM usage_sessions WHERE startMillis >= :from")
    suspend fun deleteFrom(from: Long)
}

private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE todo_tasks ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE todo_tasks SET sortOrder = createdAt")
    }
}

/** A visit to a website domain, from the accessibility URL-bar tracker. */
@Entity(tableName = "website_sessions", indices = [Index(value = ["startMillis"])])
data class WebsiteSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val domain: String,
    val startMillis: Long,
    val endMillis: Long,
)

@Dao
interface WebsiteDao {
    @Insert
    suspend fun insert(session: WebsiteSession)

    @Query(
        "SELECT * FROM website_sessions " +
            "WHERE startMillis < :windowEnd AND endMillis > :windowStart " +
            "ORDER BY startMillis"
    )
    suspend fun sessionsIn(windowStart: Long, windowEnd: Long): List<WebsiteSession>

    @Query("DELETE FROM website_sessions WHERE endMillis < :before")
    suspend fun pruneBefore(before: Long)
}

private val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS usage_sessions (" +
                "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                "packageName TEXT NOT NULL, label TEXT NOT NULL, " +
                "startMillis INTEGER NOT NULL, endMillis INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_usage_sessions_packageName_startMillis " +
                "ON usage_sessions (packageName, startMillis)"
        )
    }
}

private val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS website_sessions (" +
                "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                "domain TEXT NOT NULL, startMillis INTEGER NOT NULL, endMillis INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_website_sessions_startMillis " +
                "ON website_sessions (startMillis)"
        )
    }
}

@Database(
    entities = [ActivityBlock::class, TodoTask::class, UsageSessionEntity::class, WebsiteSession::class],
    version = 5,
    exportSchema = false,
)
abstract class BiboDb : RoomDatabase() {
    abstract fun activityBlocks(): ActivityBlockDao
    abstract fun todos(): TodoDao
    abstract fun usage(): UsageDao
    abstract fun websites(): WebsiteDao

    companion object {
        @Volatile
        private var instance: BiboDb? = null

        fun get(context: Context): BiboDb =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    BiboDb::class.java,
                    "bibo.db",
                ).addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build().also { instance = it }
            }
    }
}

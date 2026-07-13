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
import androidx.room.Upsert
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
    val source: String, // MANUAL, TIMER, TODO, VOICE, FOCUS
    val color: Int? = null,
    val note: String? = null, // post-session reflection (focus sessions)
    val goalId: Long? = null, // goal a focus session counted toward
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
    val goalId: Long? = null, // the long-term goal this task belongs to
    val dueEpochDay: Long? = null, // optional due date, drives the calendar dot
    val reminderAt: Long? = null, // when to fire a reminder notification (epoch millis)
    val reminderNote: String? = null, // motivational framing tying the step to the goal
    val rewardCents: Int = 0, // treat money earned when this task is completed
)

/** A "хотелка" — something the user wants, funded by earning treat money from tasks. */
@Entity(tableName = "wishlist_items")
data class WishlistItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val priceCents: Int,
    val createdAt: Long,
    val redeemedAt: Long? = null, // set when the user treats themselves to it
)

@Dao
interface WishlistDao {
    @Insert
    suspend fun insert(item: WishlistItem): Long

    @Update
    suspend fun update(item: WishlistItem)

    @Delete
    suspend fun delete(item: WishlistItem)

    @Query("SELECT * FROM wishlist_items ORDER BY redeemedAt IS NOT NULL, priceCents")
    fun all(): Flow<List<WishlistItem>>

    @Query("SELECT * FROM wishlist_items ORDER BY redeemedAt IS NOT NULL, priceCents")
    suspend fun allOnce(): List<WishlistItem>

    @Query("SELECT COALESCE(SUM(priceCents),0) FROM wishlist_items WHERE redeemedAt >= :since")
    suspend fun redeemedCentsSince(since: Long): Int
}

/** A long-term goal — a colored folder that groups tasks and shows on the calendar. */
@Entity(tableName = "goals")
data class Goal(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val color: Int,
    val targetDate: Long? = null, // optional milestone, as an epoch-day
    val createdAt: Long,
    val archived: Boolean = false,
    val details: String? = null, // the "why" + description shown on the goal profile
)

@Dao
interface GoalDao {
    @Insert
    suspend fun insert(goal: Goal): Long

    @Update
    suspend fun update(goal: Goal)

    @Delete
    suspend fun delete(goal: Goal)

    @Query("SELECT * FROM goals WHERE archived = 0 ORDER BY createdAt")
    fun all(): Flow<List<Goal>>

    @Query("SELECT * FROM goals WHERE archived = 0 ORDER BY createdAt")
    suspend fun allOnce(): List<Goal>

    /** Every goal, including archived ones — so the mentor can edit or restore a retired goal. */
    @Query("SELECT * FROM goals ORDER BY createdAt")
    suspend fun allWithArchived(): List<Goal>

    @Query("SELECT * FROM goals WHERE archived = 0 AND targetDate BETWEEN :start AND :end")
    fun milestonesInRange(start: Long, end: Long): Flow<List<Goal>>
}

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

    @Query(
        "SELECT * FROM todo_tasks " +
            "WHERE dueEpochDay BETWEEN :start AND :end AND completedAt IS NULL"
    )
    fun dueInRange(start: Long, end: Long): Flow<List<TodoTask>>

    @Query("UPDATE todo_tasks SET goalId = NULL WHERE goalId = :goalId")
    suspend fun clearGoal(goalId: Long)

    @Query(
        "SELECT * FROM todo_tasks WHERE goalId = :goalId AND completedAt IS NULL " +
            "AND parentId IS NULL ORDER BY sortOrder LIMIT 1"
    )
    suspend fun nextForGoal(goalId: Long): TodoTask?

    @Query("SELECT COUNT(*) FROM todo_tasks WHERE goalId = :goalId")
    suspend fun countForGoal(goalId: Long): Int

    @Query("SELECT COUNT(*) FROM todo_tasks WHERE goalId = :goalId AND completedAt IS NOT NULL")
    suspend fun completedCountForGoal(goalId: Long): Int

    @Query("SELECT title FROM todo_tasks WHERE completedAt BETWEEN :start AND :end")
    suspend fun completedTitlesBetween(start: Long, end: Long): List<String>

    @Query("SELECT title FROM todo_tasks WHERE dueEpochDay = :epochDay AND completedAt IS NULL")
    suspend fun dueTitlesOnDay(epochDay: Long): List<String>

    @Query("SELECT * FROM todo_tasks WHERE completedAt IS NULL")
    suspend fun incompleteOnce(): List<TodoTask>

    @Query("SELECT * FROM todo_tasks ORDER BY sortOrder")
    suspend fun allOnce(): List<TodoTask>

    @Query("SELECT * FROM todo_tasks WHERE goalId = :goalId ORDER BY sortOrder")
    fun forGoal(goalId: Long): Flow<List<TodoTask>>

    @Query("SELECT * FROM todo_tasks WHERE id = :id")
    suspend fun byId(id: Long): TodoTask?

    @Query("SELECT * FROM todo_tasks WHERE reminderAt IS NOT NULL AND completedAt IS NULL")
    suspend fun withReminders(): List<TodoTask>

    @Query("SELECT COALESCE(SUM(rewardCents),0) FROM todo_tasks WHERE completedAt >= :since")
    suspend fun earnedCentsSince(since: Long): Int

    @Query("SELECT COALESCE(SUM(rewardCents),0) FROM todo_tasks WHERE completedAt >= :since")
    fun earnedCentsSinceFlow(since: Long): Flow<Int>
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

/** One row per day holding the yes/no daily habits. */
@Entity(tableName = "habit_days")
data class HabitDay(
    @PrimaryKey val epochDay: Long,
    val showered: Boolean = false,
    val cleanClothes: Boolean = false,
    val workedOut: Boolean = false,
    val prayed: Boolean = false,
)

/** A single food/drink logged for a day, with estimated nutrition. */
@Entity(tableName = "food_entries", indices = [Index(value = ["epochDay"])])
data class FoodEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epochDay: Long,
    val createdAt: Long,
    val label: String,
    val calories: Int,
    val sugarG: Double,
    val caffeineMg: Int,
)

@Dao
interface HabitDao {
    @Upsert
    suspend fun upsert(day: HabitDay)

    @Query("SELECT * FROM habit_days WHERE epochDay = :epochDay")
    fun forDay(epochDay: Long): Flow<HabitDay?>

    @Query("SELECT * FROM habit_days WHERE epochDay = :epochDay")
    suspend fun get(epochDay: Long): HabitDay?

    @Query("SELECT * FROM habit_days WHERE epochDay BETWEEN :start AND :end")
    fun range(start: Long, end: Long): Flow<List<HabitDay>>
}

@Dao
interface FoodDao {
    @Insert
    suspend fun insert(entry: FoodEntry): Long

    @Delete
    suspend fun delete(entry: FoodEntry)

    @Query("SELECT * FROM food_entries WHERE epochDay = :epochDay ORDER BY createdAt")
    fun forDay(epochDay: Long): Flow<List<FoodEntry>>

    @Query("SELECT * FROM food_entries WHERE epochDay = :epochDay")
    suspend fun forDayOnce(epochDay: Long): List<FoodEntry>

    @Query(
        "SELECT epochDay AS epochDay, " +
            "COALESCE(SUM(calories),0) AS calories, " +
            "COALESCE(SUM(sugarG),0) AS sugarG, " +
            "COALESCE(SUM(caffeineMg),0) AS caffeineMg " +
            "FROM food_entries WHERE epochDay BETWEEN :start AND :end GROUP BY epochDay"
    )
    fun dailyTotals(start: Long, end: Long): Flow<List<DayTotals>>
}

data class DayTotals(
    val epochDay: Long,
    val calories: Int,
    val sugarG: Double,
    val caffeineMg: Int,
)

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

private val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS habit_days (" +
                "epochDay INTEGER NOT NULL PRIMARY KEY, " +
                "showered INTEGER NOT NULL DEFAULT 0, cleanClothes INTEGER NOT NULL DEFAULT 0, " +
                "workedOut INTEGER NOT NULL DEFAULT 0, prayed INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS food_entries (" +
                "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, epochDay INTEGER NOT NULL, " +
                "createdAt INTEGER NOT NULL, label TEXT NOT NULL, calories INTEGER NOT NULL, " +
                "sugarG REAL NOT NULL, caffeineMg INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_food_entries_epochDay ON food_entries (epochDay)")
    }
}

private val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS goals (" +
                "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, " +
                "color INTEGER NOT NULL, targetDate INTEGER, createdAt INTEGER NOT NULL, " +
                "archived INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL("ALTER TABLE todo_tasks ADD COLUMN goalId INTEGER")
        db.execSQL("ALTER TABLE todo_tasks ADD COLUMN dueEpochDay INTEGER")
    }
}

private val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE activity_blocks ADD COLUMN note TEXT")
        db.execSQL("ALTER TABLE activity_blocks ADD COLUMN goalId INTEGER")
    }
}

/** One message in the ongoing mentor chat. */
@Entity(tableName = "chat_messages", indices = [Index(value = ["epochDay"])])
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epochDay: Long,
    val role: String, // USER, ASSISTANT, or ERROR (shown in UI, never sent to the model)
    val content: String,
    val createdAt: Long,
)

/**
 * Compact summary of one past day — the mentor's episodic memory. Written by the model
 * for days that had conversation, or assembled locally from logged data otherwise.
 */
@Entity(tableName = "chat_days")
data class ChatDay(
    @PrimaryKey val epochDay: Long,
    val digest: String,
)

@Dao
interface ChatDao {
    @Insert
    suspend fun insert(message: ChatMessage): Long

    @Query("SELECT * FROM chat_messages ORDER BY id")
    fun all(): Flow<List<ChatMessage>>

    @Query("SELECT * FROM chat_messages WHERE epochDay >= :sinceDay AND role != 'ERROR' ORDER BY id")
    suspend fun since(sinceDay: Long): List<ChatMessage>

    @Query("SELECT * FROM chat_messages WHERE epochDay = :epochDay AND role != 'ERROR' ORDER BY id")
    suspend fun forDay(epochDay: Long): List<ChatMessage>

    @Query(
        "SELECT DISTINCT epochDay FROM chat_messages WHERE epochDay < :today " +
            "AND epochDay NOT IN (SELECT epochDay FROM chat_days) ORDER BY epochDay"
    )
    suspend fun undigestedDays(today: Long): List<Long>

    @Query(
        "SELECT * FROM chat_messages WHERE role != 'ERROR' AND content LIKE '%' || :q || '%' " +
            "ORDER BY id DESC LIMIT :limit"
    )
    suspend fun search(q: String, limit: Int): List<ChatMessage>
}

@Dao
interface ChatDayDao {
    @Upsert
    suspend fun upsert(day: ChatDay)

    @Query("SELECT * FROM chat_days WHERE epochDay = :epochDay")
    suspend fun get(epochDay: Long): ChatDay?

    @Query("SELECT * FROM chat_days WHERE epochDay >= :sinceDay ORDER BY epochDay")
    suspend fun since(sinceDay: Long): List<ChatDay>

    @Query("SELECT * FROM chat_days WHERE digest LIKE '%' || :q || '%' ORDER BY epochDay DESC LIMIT :limit")
    suspend fun search(q: String, limit: Int): List<ChatDay>
}

private val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS chat_messages (" +
                "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, epochDay INTEGER NOT NULL, " +
                "role TEXT NOT NULL, content TEXT NOT NULL, createdAt INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_messages_epochDay ON chat_messages (epochDay)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS chat_days (" +
                "epochDay INTEGER NOT NULL PRIMARY KEY, digest TEXT NOT NULL)"
        )
    }
}

private val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE goals ADD COLUMN details TEXT")
    }
}

private val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE todo_tasks ADD COLUMN reminderAt INTEGER")
        db.execSQL("ALTER TABLE todo_tasks ADD COLUMN reminderNote TEXT")
    }
}

private val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE todo_tasks ADD COLUMN rewardCents INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS wishlist_items (" +
                "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, " +
                "priceCents INTEGER NOT NULL, createdAt INTEGER NOT NULL, redeemedAt INTEGER)"
        )
    }
}

@Database(
    entities = [
        ActivityBlock::class, TodoTask::class, UsageSessionEntity::class, WebsiteSession::class,
        HabitDay::class, FoodEntry::class, Goal::class, ChatMessage::class, ChatDay::class,
        WishlistItem::class,
    ],
    version = 12,
    exportSchema = false,
)
abstract class BiboDb : RoomDatabase() {
    abstract fun activityBlocks(): ActivityBlockDao
    abstract fun todos(): TodoDao
    abstract fun usage(): UsageDao
    abstract fun websites(): WebsiteDao
    abstract fun habits(): HabitDao
    abstract fun foods(): FoodDao
    abstract fun goals(): GoalDao
    abstract fun chat(): ChatDao
    abstract fun chatDays(): ChatDayDao
    abstract fun wishlist(): WishlistDao

    companion object {
        @Volatile
        private var instance: BiboDb? = null

        fun get(context: Context): BiboDb =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    BiboDb::class.java,
                    "bibo.db",
                ).addMigrations(
                    MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                    MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12,
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build().also { instance = it }
            }
    }
}

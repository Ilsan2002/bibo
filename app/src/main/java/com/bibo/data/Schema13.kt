package com.bibo.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Additive upgrade: legacy observations retain their original, ambiguous provenance. */
val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        listOf(
            "ALTER TABLE activity_blocks ADD COLUMN taskId INTEGER",
            "ALTER TABLE activity_blocks ADD COLUMN sessionId TEXT",
            "ALTER TABLE activity_blocks ADD COLUMN completionId INTEGER",
            "ALTER TABLE activity_blocks ADD COLUMN provenance TEXT NOT NULL DEFAULT 'LEGACY'",
            "ALTER TABLE activity_blocks ADD COLUMN allDay INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE activity_blocks ADD COLUMN eventZone TEXT",
            "ALTER TABLE activity_blocks ADD COLUMN commandId TEXT",
            "CREATE UNIQUE INDEX IF NOT EXISTS index_activity_blocks_commandId ON activity_blocks(commandId)",
            "ALTER TABLE todo_tasks ADD COLUMN deletedAt INTEGER",
            "ALTER TABLE todo_tasks ADD COLUMN completedBy INTEGER",
            "ALTER TABLE todo_tasks ADD COLUMN deletedBy TEXT",
            "CREATE INDEX IF NOT EXISTS index_todo_tasks_parentId ON todo_tasks(parentId)",
            "CREATE INDEX IF NOT EXISTS index_todo_tasks_goalId ON todo_tasks(goalId)",
            "ALTER TABLE habit_days ADD COLUMN loggedMask INTEGER NOT NULL DEFAULT 0",
            "UPDATE habit_days SET loggedMask=(CASE WHEN showered=1 THEN 1 ELSE 0 END) | (CASE WHEN cleanClothes=1 THEN 2 ELSE 0 END) | (CASE WHEN workedOut=1 THEN 4 ELSE 0 END) | (CASE WHEN prayed=1 THEN 8 ELSE 0 END)",
            "ALTER TABLE food_entries ADD COLUMN estimated INTEGER NOT NULL DEFAULT 1",
            "ALTER TABLE food_entries ADD COLUMN provenance TEXT NOT NULL DEFAULT 'manual'",
            "UPDATE food_entries SET provenance='legacy'",
            "ALTER TABLE chat_days ADD COLUMN conversationCompacted INTEGER NOT NULL DEFAULT 1",
            // Existing digests might be facts-only because of the old compaction bug.
            "UPDATE chat_days SET conversationCompacted=0 WHERE epochDay IN (SELECT DISTINCT epochDay FROM chat_messages WHERE role='USER')",
        ).forEach(db::execSQL)
        createCommandSchema(db)
        createSessionSchema(db)
        createRewardSchema(db)
        createUsageSchema(db)
        createMentorSchema(db)
        createMigrationRepairSchema(db)
        repairLegacyHierarchy(db)
    }
}

/** Flatten unsupported nested steps and expose cycles without deleting any task. */
private fun repairLegacyHierarchy(db: SupportSQLiteDatabase) {
    data class Link(val parent: Long?, val goal: Long?)
    val original = mutableMapOf<Long, Link>()
    val goals = mutableSetOf<Long>()
    db.query("SELECT id FROM goals").use { while (it.moveToNext()) goals += it.getLong(0) }
    db.query("SELECT id,parentId,goalId FROM todo_tasks").use { cursor ->
        while (cursor.moveToNext()) original[cursor.getLong(0)] = Link(
            if (cursor.isNull(1)) null else cursor.getLong(1), if (cursor.isNull(2)) null else cursor.getLong(2))
    }
    fun record(id: Long, field: String, before: Long?, after: Long?, reason: String) {
        if (before != after) db.execSQL(
            "INSERT INTO migration_repairs(taskId,field,previousValue,newValue,reason) VALUES(?,?,?,?,?)",
            arrayOf<Any?>(id, field, before?.toString(), after?.toString(), reason))
    }
    original.forEach { (id, link) ->
        val seen = mutableSetOf(id)
        var parent = link.parent
        var root: Long? = null
        while (parent != null && parent in original && seen.add(parent)) {
            root = parent
            parent = original[parent]?.parent
        }
        val repairedParent = if (parent != null) null else root
        val proposedGoal = if (repairedParent != null) original[repairedParent]?.goal else link.goal
        val repairedGoal = proposedGoal?.takeIf { it in goals }
        record(id, "parentId", link.parent, repairedParent,
            if (parent != null) "Unreachable parent or cycle promoted to top-level task" else "Nested step flattened under its top-level task")
        record(id, "goalId", link.goal, repairedGoal,
            if (proposedGoal != null && proposedGoal !in goals) "Missing goal promoted to inbox" else "Step inherits its top-level task's goal")
        if (link.parent != repairedParent || link.goal != repairedGoal)
            db.execSQL("UPDATE todo_tasks SET parentId=?,goalId=? WHERE id=?", arrayOf(repairedParent, repairedGoal, id))
    }
}

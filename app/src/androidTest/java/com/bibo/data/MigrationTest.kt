package com.bibo.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val assets get() = InstrumentationRegistry.getInstrumentation().context.assets

    @Test fun releasedVersion12PreservesHistoryAndRepairsUnreachableTasks() = runBlocking {
        val name = "migration-12-test.db"
        context.deleteDatabase(name)
        val old = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        assets.open("legacy-v12.sql").bufferedReader().use { it.readText() }.split(';')
            .map(String::trim).filter(String::isNotEmpty).forEach(old::execSQL)
        old.execSQL("INSERT INTO goals VALUES(1,'Goal',42,NULL,100,0,'Reason')")
        old.execSQL("INSERT INTO todo_tasks VALUES(1,'Done',NULL,100,200,NULL,100,1,NULL,NULL,NULL,250)")
        old.execSQL("INSERT INTO todo_tasks VALUES(2,'Orphan',99,101,NULL,NULL,101,99,NULL,NULL,NULL,0)")
        old.execSQL("INSERT INTO todo_tasks VALUES(3,'Cycle A',4,102,NULL,NULL,102,NULL,NULL,NULL,NULL,0)")
        old.execSQL("INSERT INTO todo_tasks VALUES(4,'Cycle B',3,103,NULL,NULL,103,NULL,NULL,NULL,NULL,0)")
        old.execSQL("INSERT INTO activity_blocks VALUES(1,'Old todo',100,900100,'TODO',NULL,'Keep note',1)")
        old.execSQL("INSERT INTO habit_days VALUES(20000,1,0,0,1)")
        old.execSQL("INSERT INTO food_entries VALUES(1,20000,300,'Tea',5,1.5,30)")
        old.execSQL("INSERT INTO chat_messages VALUES(1,20000,'USER','Keep conversation',300)")
        old.execSQL("INSERT INTO chat_days VALUES(20000,'Facts only')")
        old.execSQL("INSERT INTO wishlist_items VALUES(1,'Tea',100,100,201)")
        old.close()
        val upgraded = BiboDb.builder(context, name).build()
        try {
            // Opening forces the actual Room schema validation, including defaults and indices.
            assertEquals(13, upgraded.openHelper.writableDatabase.version)
            assertEquals(4, upgraded.todos().allOnce().size)
            assertNull(upgraded.todos().byId(2)!!.parentId)
            assertNull(upgraded.todos().byId(2)!!.goalId)
            assertNull(upgraded.todos().byId(3)!!.parentId)
            assertNull(upgraded.todos().byId(4)!!.parentId)
            assertTrue(upgraded.migrationRepairs().allOnce().any { it.taskId == 2L && it.field == "parentId" && it.previousValue == "99" })
            assertEquals("Keep note", upgraded.activityBlocks().byId(1)!!.note)
            assertEquals("LEGACY", upgraded.activityBlocks().byId(1)!!.provenance)
            assertEquals(0L, AppTime.measuredWorkMillis(upgraded.activityBlocks().blocksInList(0, 1_000_000), 0, 1_000_000))
            assertEquals(9, upgraded.habits().get(20000)!!.loggedMask)
            assertEquals("legacy", upgraded.foods().byId(1)!!.provenance)
            assertEquals(listOf(20000L), upgraded.chat().undigestedDays(20001))
            assertEquals("LEGACY_SNAPSHOT", upgraded.rewards().entry("task:1")!!.provenance)
            assertEquals(250, upgraded.rewards().entry("task:1")!!.amountCents)
            assertEquals(100, upgraded.rewards().entry("wish:1")!!.amountCents)
            assertNull(upgraded.workSessions().active())
        } finally { upgraded.close(); context.deleteDatabase(name) }
    }

    @Test fun everySupportedLegacyVersionUpgradesThroughTheFullChain() = runBlocking {
        for (version in 2..11) {
            val name = "migration-$version-test.db"
            context.deleteDatabase(name)
            val helper = FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                    .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                        override fun onCreate(old: SupportSQLiteDatabase) {
                            old.execSQL("CREATE TABLE activity_blocks(id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,title TEXT NOT NULL,startMillis INTEGER NOT NULL,endMillis INTEGER NOT NULL,source TEXT NOT NULL,color INTEGER)")
                            old.execSQL("CREATE TABLE todo_tasks(id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,title TEXT NOT NULL,parentId INTEGER,createdAt INTEGER NOT NULL,completedAt INTEGER,startedAt INTEGER)")
                            old.execSQL("INSERT INTO todo_tasks VALUES(1,'Preserved v$version',NULL,100,NULL,NULL)")
                            BiboDb.migrations().filter { it.startVersion >= 2 && it.endVersion <= version }.forEach { it.migrate(old) }
                        }
                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    }).build())
            helper.writableDatabase
            helper.close()
            val upgraded = BiboDb.builder(context, name).build()
            try {
                assertEquals("v$version schema", 13, upgraded.openHelper.writableDatabase.version)
                assertEquals("Preserved v$version", upgraded.todos().byId(1)!!.title)
                assertEquals(100L, upgraded.todos().byId(1)!!.sortOrder)
            } finally { upgraded.close(); context.deleteDatabase(name) }
        }
    }
}

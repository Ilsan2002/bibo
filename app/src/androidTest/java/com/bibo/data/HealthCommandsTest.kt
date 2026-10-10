package com.bibo.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bibo.ui.FoodItem
import com.bibo.ui.HealthLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class HealthCommandsTest {
    private lateinit var db: BiboDb
    private lateinit var commands: HealthCommands
    private var databaseName: String? = null
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, BiboDb::class.java).build()
        commands = HealthCommands(db)
    }
    @After fun close() { db.close(); databaseName?.let { InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(it) } }
    @Test fun concurrentFieldsDoNotOverwriteEachOther() = runBlocking {
        coroutineScope {
            listOf(async(Dispatchers.Default) { commands.setHabit(10, HealthHabit.SHOWER, true) },
                async(Dispatchers.Default) { commands.setHabit(10, HealthHabit.CLOTHES, true) }).awaitAll()
        }
        val day = db.habits().get(10)!!
        assertEquals(true, day.value(HealthHabit.SHOWER)); assertEquals(true, day.value(HealthHabit.CLOTHES))
        assertNull(day.value(HealthHabit.PRAY))
    }
    @Test fun rapidCyclesUseLatestStoredValue() = runBlocking {
        coroutineScope { (1..20).map { async(Dispatchers.Default) { commands.cycleHabit(10, HealthHabit.SHOWER) } }.awaitAll() }
        assertEquals(false, db.habits().get(10)!!.value(HealthHabit.SHOWER))
    }
    @Test fun acceptedLogIsAtomicWhenAnInsertFails() = runBlocking {
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_food BEFORE INSERT ON food_entries WHEN NEW.label='Reject' BEGIN SELECT RAISE(ABORT,'test rejection'); END")
        val operation = UUID.randomUUID().toString()
        try {
            commands.saveLog(10, HealthLog(listOf(FoodItem("First", 10, 0.0, 0), FoodItem("Reject", 20, 0.0, 0)), showered = true), operation)
            fail("Expected rejected insert")
        } catch (_: android.database.SQLException) { }
        assertTrue(db.foods().forDayOnce(10).isEmpty()); assertNull(db.habits().get(10))
        assertNull(db.commands().byId("health-log:$operation"))
    }
    @Test fun repeatedSubmissionIsIdempotentAndDayDoesNotMove() = runBlocking {
        val log = HealthLog(listOf(FoodItem("Meal", 100, 2.0, 0)), prayed = false)
        val operation = UUID.randomUUID().toString()
        val first = commands.saveLog(10, log, operation)
        val second = HealthCommands(db).saveLog(11, log, operation)
        assertEquals(first, second); assertEquals(1, db.foods().forDayOnce(10).size)
        assertTrue(db.foods().forDayOnce(11).isEmpty()); assertEquals(false, db.habits().get(10)!!.value(HealthHabit.PRAY))
    }
    @Test fun receiptPersistsAcrossDatabaseCloseAndReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "health-receipt-${UUID.randomUUID()}.db"
        databaseName = name
        db.close()
        db = Room.databaseBuilder(context, BiboDb::class.java, name).build()
        val operation = UUID.randomUUID().toString()
        val log = HealthLog(listOf(FoodItem("Meal", 100, 2.0, 0)), prayed = false)
        val first = HealthCommands(db).saveLog(10, log, operation)
        db.close()
        db = Room.databaseBuilder(context, BiboDb::class.java, name).build()
        val second = HealthCommands(db).saveLog(11, log, operation)
        assertEquals(first, second)
        assertEquals(1, db.foods().forDayOnce(10).size); assertTrue(db.foods().forDayOnce(11).isEmpty())
        val receipt = db.commands().byId("health-log:$operation")!!
        assertEquals("HEALTH_LOG", receipt.kind); assertEquals("APPLIED", receipt.status)
        assertEquals(10L, JSONObject(receipt.input).getLong("day"))
    }
    @Test fun changedPayloadUnderCommittedIdentityConflicts() = runBlocking {
        val operation = UUID.randomUUID().toString()
        val log = HealthLog(listOf(FoodItem("Meal", 100, 2.0, 0)))
        val ids = commands.saveLog(10, log, operation)
        val receipt = db.commands().byId("health-log:$operation")
        try {
            HealthCommands(db).saveLog(11, log.copy(foods = listOf(FoodItem("Meal", 200, 2.0, 0))), operation)
            fail("Expected changed-payload conflict")
        } catch (_: HealthLogConflict) { }
        assertEquals(ids.single(), db.foods().forDayOnce(10).single().id)
        assertEquals(100, db.foods().forDayOnce(10).single().calories)
        assertTrue(db.foods().forDayOnce(11).isEmpty()); assertEquals(receipt, db.commands().byId("health-log:$operation"))
    }
    @Test fun receiptFailureRollsBackFoodsAndHabitPatches() = runBlocking {
        val operation = UUID.randomUUID().toString()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_health_receipt BEFORE INSERT ON command_records WHEN NEW.kind='HEALTH_LOG' BEGIN SELECT RAISE(ABORT,'test receipt rejection'); END")
        try {
            commands.saveLog(10, HealthLog(listOf(FoodItem("Meal", 100, 2.0, 0)), showered = true), operation)
            fail("Expected rejected receipt")
        } catch (_: android.database.SQLException) { }
        assertTrue(db.foods().forDayOnce(10).isEmpty()); assertNull(db.habits().get(10))
        assertNull(db.commands().byId("health-log:$operation"))
    }
    @Test fun operationIdentityDoesNotLeakIntoAReplacementDatabase() = runBlocking {
        val operation = UUID.randomUUID().toString()
        val log = HealthLog(listOf(FoodItem("Meal", 100, 2.0, 0)))
        commands.saveLog(10, log, operation)
        db.close()
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, BiboDb::class.java).build()
        HealthCommands(db).saveLog(11, log, operation)
        assertTrue(db.foods().forDayOnce(10).isEmpty()); assertEquals(1, db.foods().forDayOnce(11).size)
        assertNotNull(db.commands().byId("health-log:$operation"))
    }
}

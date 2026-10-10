package com.bibo.data

import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject

enum class HealthHabit(val bit: Int, val label: String) {
    SHOWER(1, "Showered"), CLOTHES(2, "Clean clothes"), WORKOUT(4, "Worked out"), PRAY(8, "Prayed");
}
class HealthLogConflict : IllegalStateException("This health log was already saved with different values. Check the saved day before adding or editing it.")
fun HabitDay.value(habit: HealthHabit): Boolean? = if (loggedMask and habit.bit == 0) null else when (habit) {
    HealthHabit.SHOWER -> showered
    HealthHabit.CLOTHES -> cleanClothes
    HealthHabit.WORKOUT -> workedOut
    HealthHabit.PRAY -> prayed
}
private fun HabitDay.patch(habit: HealthHabit, value: Boolean?): HabitDay {
    val mask = if (value == null) loggedMask and habit.bit.inv() else loggedMask or habit.bit
    return when (habit) {
        HealthHabit.SHOWER -> copy(showered = value == true, loggedMask = mask)
        HealthHabit.CLOTHES -> copy(cleanClothes = value == true, loggedMask = mask)
        HealthHabit.WORKOUT -> copy(workedOut = value == true, loggedMask = mask)
        HealthHabit.PRAY -> copy(prayed = value == true, loggedMask = mask)
    }
}

/** All health writes capture a day and read/patch the latest row inside a transaction. */
class HealthCommands(private val db: BiboDb) {
    /** Caller may wrap this validation + insert in its own durable command transaction. */
    suspend fun logFood(entry: FoodEntry): Long {
        require(FoodItem(entry.label, entry.calories, entry.sugarG, entry.caffeineMg, entry.estimated, entry.provenance).valid) { "Check the food name and nonnegative nutrition values." }
        require(entry.id == 0L) { "A new food log must not replace an existing entry." }
        return db.foods().insert(entry)
    }
    suspend fun cycleHabit(day: Long, habit: HealthHabit) = db.withTransaction {
        val current = db.habits().get(day) ?: HabitDay(day)
        val next = when (current.value(habit)) { null -> true; true -> false; false -> null }
        db.habits().upsert(current.patch(habit, next))
    }
    suspend fun setHabit(day: Long, habit: HealthHabit, value: Boolean?) = db.withTransaction {
        val current = db.habits().get(day) ?: HabitDay(day)
        db.habits().upsert(current.patch(habit, value))
    }
    suspend fun saveLog(day: Long, log: HealthLog, operationId: String): List<Long> {
        require(operationId.isNotBlank() && operationId.length <= 200) { "A health log needs a stable operation identity." }
        require(!log.isEmpty) { "Add a food or habit first." }
        require(log.foods.all { it.valid }) { "Check the food name and nonnegative nutrition values." }
        val commandId = "health-log:$operationId"
        val payload = payload(log)
        return db.withTransaction {
            db.commands().byId(commandId)?.let { receipt ->
                check(receipt.kind == "HEALTH_LOG" && receipt.status == "APPLIED") { "This health operation cannot be replayed." }
                if (JSONObject(receipt.input).getString("payload") != payload) throw HealthLogConflict()
                // The first accepted date belongs to the operation, even if navigation changed.
                val ids = JSONObject(receipt.result).getJSONArray("foodIds")
                return@withTransaction (0 until ids.length()).map(ids::getLong)
            }
            val now = AppTime.nowMillis()
            val result = log.foods.map { food ->
                logFood(FoodEntry(epochDay = day, createdAt = now, label = food.label.trim(), calories = food.calories,
                    sugarG = food.sugarG, caffeineMg = food.caffeineMg, estimated = food.estimated, provenance = food.provenance))
            }
            var current = db.habits().get(day) ?: HabitDay(day)
            listOf(HealthHabit.SHOWER to log.showered, HealthHabit.CLOTHES to log.cleanClothes,
                HealthHabit.WORKOUT to log.workedOut, HealthHabit.PRAY to log.prayed).forEach { (habit, value) ->
                if (value != null) current = current.patch(habit, value)
            }
            if (listOf(log.showered, log.cleanClothes, log.workedOut, log.prayed).any { it != null }) db.habits().upsert(current)
            db.commands().upsert(CommandRecord(commandId, "HEALTH_LOG", "APPLIED",
                JSONObject().put("day", day).put("payload", payload).toString(),
                JSONObject().put("foodIds", JSONArray(result)).toString(), now, now))
            result
        }
    }

    private fun payload(log: HealthLog): String = JSONObject()
        .put("foods", JSONArray(log.foods.map { food -> JSONObject()
            .put("label", food.label.trim()).put("calories", food.calories).put("sugarG", food.sugarG)
            .put("caffeineMg", food.caffeineMg).put("estimated", food.estimated).put("provenance", food.provenance) }))
        .put("showered", log.showered ?: JSONObject.NULL).put("cleanClothes", log.cleanClothes ?: JSONObject.NULL)
        .put("workedOut", log.workedOut ?: JSONObject.NULL).put("prayed", log.prayed ?: JSONObject.NULL).toString()
}

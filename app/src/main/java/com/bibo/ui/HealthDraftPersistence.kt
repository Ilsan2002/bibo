package com.bibo.ui

/** Only primitive values enter saved state; the operation identity survives recreation. */
internal object HealthDraftPersistence {
    fun encode(draft: HealthDraft): ArrayList<String> = arrayListOf("1", draft.day.toString(), draft.operationId,
        draft.log.showered?.toString().orEmpty(), draft.log.cleanClothes?.toString().orEmpty(),
        draft.log.workedOut?.toString().orEmpty(), draft.log.prayed?.toString().orEmpty(),
        draft.log.dayOffset.toString(), draft.log.explicitEpochDay?.toString().orEmpty(), draft.log.foods.size.toString()).apply {
        draft.log.foods.forEach { food -> addAll(listOf(food.label, food.calories.toString(), food.sugarG.toString(),
            food.caffeineMg.toString(), food.estimated.toString(), food.provenance)) }
        add(draft.log.warnings.size.toString()); addAll(draft.log.warnings)
    }
    fun decode(value: List<String>): HealthDraft? = runCatching {
        val fields = value.iterator()
        fun next() = fields.next()
        fun habit() = next().takeIf(String::isNotEmpty)?.toBooleanStrict()
        if (next() != "1") return null
        val day = next().toLong(); val operation = next()
        if (operation.isBlank()) return null
        val shower = habit(); val clothes = habit(); val workout = habit(); val prayer = habit()
        val offset = next().toLong(); val explicitDay = next().takeIf(String::isNotEmpty)?.toLong()
        val count = next().toInt(); if (count !in 0..1000) return null
        val foods = List(count) { FoodItem(next(), next().toInt(), next().toDouble(), next().toInt(), next().toBooleanStrict(), next()) }
        if (!foods.all { it.valid }) return null
        val warningCount = next().toInt(); if (warningCount !in 0..1000) return null
        val warnings = List(warningCount) { next() }
        if (fields.hasNext()) return null
        HealthDraft(day, HealthLog(foods, shower, clothes, workout, prayer, warnings, offset, explicitDay), operation)
    }.getOrNull()
}

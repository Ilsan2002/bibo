package com.bibo.ui

import java.time.LocalDate

// Compatibility aliases; domain records belong to the data layer.
typealias FoodItem = com.bibo.data.FoodItem
typealias HealthLog = com.bibo.data.HealthLog

private data class FoodTemplate(val pattern: Regex, val food: FoodItem)
private fun food(pattern: String, label: String, kcal: Int, sugar: Double, caffeine: Int = 0) =
    FoodTemplate(Regex("\\b(?:$pattern)\\b", RegexOption.IGNORE_CASE), FoodItem(label, kcal, sugar, caffeine))
private val FOODS = listOf(
    food("green teas?|matcha", "Green tea", 2, 0.0, 30),
    food("protein shakes?", "Protein shake", 160, 4.0),
    food("energy drinks?|red ?bull|monster", "Energy drink", 110, 27.0, 80),
    food("espresso(?:s)?", "Espresso", 3, 0.0, 64),
    food("lattes?|cappuccinos?", "Latte", 120, 10.0, 75),
    food("coffees?", "Coffee", 5, 0.0, 95),
    food("teas?", "Tea", 2, 0.0, 47),
    food("coke|cola|sodas?|pepsi", "Soda", 140, 39.0, 34),
    food("donuts?", "Donut", 260, 14.0), food("bananas?", "Banana", 105, 14.0),
    food("apples?", "Apple", 95, 19.0), food("chocolate|candy", "Chocolate", 210, 24.0, 12),
    food("pizza(?: slices?)?", "Pizza slice", 285, 4.0), food("sandwich(?:es)?|subs?", "Sandwich", 350, 6.0),
    food("rice(?: bowls?)?", "Rice bowl", 350, 1.0), food("eggs?", "Egg", 78, 0.2),
    food("chicken", "Chicken", 240, 0.0), food("water", "Water", 0, 0.0),
)
private val NEGATIVE = Regex("""\b(?:no|not|never|without|skip(?:ped)?|missed|didn['’]?t|haven['’]?t|don['’]?t)\b""", RegexOption.IGNORE_CASE)
private val FUTURE = Regex("""\b(?:will|tomorrow|plan(?:ning)?|want|might|should|going to|if)\b""", RegexOption.IGNORE_CASE)
private val QUANTITY = Regex("""(?:^|(?<=\s))([-+]?\d+(?:\.\d+)?|half(?:\s+an?)?|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|dozen|a|an)\s*(?:(?:cups?|glasses?|servings?|slices?)(?:\s+of)?\s*)?$""", RegexOption.IGNORE_CASE)
private val WEIGHT_VOLUME = Regex("""\b\d+(?:\.\d+)?\s*(?:g|grams?|kg|ml|litres?|liters?|oz|ounces?)\s*(?:of\s*)?$""", RegexOption.IGNORE_CASE)
private val UNSUPPORTED_SERVING = Regex("""\b(?:large|medium|small|big|double|triple|bottles?|cans?|packs?|pieces?)\s*(?:of\s*)?$""", RegexOption.IGNORE_CASE)
private val WORD_NUMBERS = mapOf("one" to 1.0, "two" to 2.0, "three" to 3.0, "four" to 4.0, "five" to 5.0, "six" to 6.0, "seven" to 7.0, "eight" to 8.0, "nine" to 9.0, "ten" to 10.0, "eleven" to 11.0, "twelve" to 12.0, "dozen" to 12.0, "a" to 1.0, "an" to 1.0, "half" to 0.5, "half a" to 0.5, "half an" to 0.5)
private val ISO_DATE = Regex("""\b\d{4}-\d{1,2}-\d{1,2}\b""")
private val RELATIVE_DATE = Regex("""\b(?:yesterday|today|tomorrow)\b""", RegexOption.IGNORE_CASE)
private val UNSUPPORTED_DATE = Regex("""\b(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday|(?:last|next)\s+(?:week|month|year))\b|\b\d{1,2}/\d{1,2}(?:/\d{2,4})?\b|\b(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:tember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\s+\d{1,2}\b""", RegexOption.IGNORE_CASE)
private val HABITS = listOf(
    Regex("""\bshower(?:ed|ing)?\b""", RegexOption.IGNORE_CASE),
    Regex("""\b(?:clean clothes|fresh clothes|change(?:d)? (?:my )?clothes)\b""", RegexOption.IGNORE_CASE),
    Regex("""\b(?:work(?:ed)? out|workout|(?:went|go) to (?:the )?gym|exercise(?:d)?|went (?:for a )?(?:run|jog)|ran \d+\s*(?:km|miles?))\b""", RegexOption.IGNORE_CASE),
    Regex("""\b(?:pray(?:ed|ing)?|namaz|sala[ht])\b""", RegexOption.IGNORE_CASE),
)

/** Clause-scoped negation/tense and longest nonoverlapping food matches. Pure and testable. */
fun parseHealthDraft(text: String, referenceDate: LocalDate = LocalDate.now()): HealthLog {
    val input = text.take(4000)
    val statedDates = ISO_DATE.findAll(input).map { it.value }.distinct().toList()
    val relativeDates = RELATIVE_DATE.findAll(input).map { it.value.lowercase() }.distinct().toList()
    if (statedDates.size > 1 || relativeDates.size > 1 || (statedDates.isNotEmpty() && relativeDates.isNotEmpty()))
        return HealthLog(warnings = listOf("Use a separate log for each day."))
    if (UNSUPPORTED_DATE.containsMatchIn(input))
        return HealthLog(warnings = listOf("That date is ambiguous. Use YYYY-MM-DD, today or yesterday, or choose the day before entering the description."))
    val statedDate = statedDates.singleOrNull()?.let { raw ->
        try { LocalDate.parse(raw) } catch (_: Exception) { return HealthLog(warnings = listOf("That date is invalid. Use a valid YYYY-MM-DD date.")) }
    }
    val foods = mutableListOf<FoodItem>()
    val habits = arrayOfNulls<Boolean>(4)
    val warnings = linkedSetOf("Estimates cover recognized foods and typical servings. Check quantities and add missing items before saving.")
    val dayOffset = when {
        "yesterday" in relativeDates -> -1L
        "tomorrow" in relativeDates -> 1L
        else -> 0L
    }
    val explicitDay = statedDate ?: relativeDates.singleOrNull()?.let { referenceDate.plusDays(dayOffset) }
    if (dayOffset > 0 || (explicitDay != null && explicitDay > referenceDate)) return HealthLog(warnings = listOf("Future plans are not completed health records. Log them after they happen."))
    val quantityText = input.replace(Regex("""\b(one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|dozen|\d+) and a half\b""", RegexOption.IGNORE_CASE)) {
        val whole = it.groupValues[1].toDoubleOrNull() ?: WORD_NUMBERS.getValue(it.groupValues[1].lowercase())
        (whole + 0.5).toString()
    }
    quantityText.split(Regex("""[,;!]|(?<!\d)\.|\.(?!\d)|\b(?:and|but|then)\b""", RegexOption.IGNORE_CASE)).forEach { raw ->
        val clause = raw.trim()
        if (clause.isBlank()) return@forEach
        if (FUTURE.containsMatchIn(clause)) {
            warnings += "Plans and conditional statements were not marked as completed."
            return@forEach
        }
        var recognized = false
        HABITS.forEachIndexed { index, pattern ->
            pattern.find(clause)?.let { match -> recognized = true; habits[index] = !NEGATIVE.containsMatchIn(clause.substring(0, match.range.first)) }
        }
        val occupied = mutableListOf<IntRange>()
        // Table is specificity ordered. A generic “tea” cannot match inside “green tea”.
        FOODS.forEach { template -> template.pattern.findAll(clause).forEach matchLoop@ { match ->
            if (occupied.any { it.first <= match.range.last && match.range.first <= it.last }) return@matchLoop
            occupied += match.range
            recognized = true
            val prefix = clause.substring(0, match.range.first)
            if (NEGATIVE.containsMatchIn(prefix)) return@matchLoop
            if (WEIGHT_VOLUME.containsMatchIn(prefix.trimEnd())) {
                warnings += "Weight or volume for ${template.food.label} needs manual nutrition values."
                return@matchLoop
            }
            if (UNSUPPORTED_SERVING.containsMatchIn(prefix.trimEnd())) {
                warnings += "That serving size for ${template.food.label} needs manual nutrition values."
                return@matchLoop
            }
            val quantityText = QUANTITY.find(prefix.trimEnd())?.groupValues?.get(1)?.lowercase()
            val quantity = quantityText?.toDoubleOrNull() ?: WORD_NUMBERS[quantityText] ?: 1.0
            if (!quantity.isFinite() || quantity <= 0 || quantity > 100) {
                warnings += "A quantity was outside the supported range. Enter that item manually."
                return@matchLoop
            }
            foods += template.food.copy(
                label = if (quantity == 1.0) template.food.label else "${quantity.toString().removeSuffix(".0")} × ${template.food.label}",
                calories = (template.food.calories * quantity).toInt(), sugarG = template.food.sugarG * quantity,
                caffeineMg = (template.food.caffeineMg * quantity).toInt(),
            )
        } }
        if (!recognized && !NEGATIVE.containsMatchIn(clause)) warnings += "No estimate for “${clause.take(80)}”. Add it manually if needed."
    }
    if (foods.isEmpty()) warnings.remove("Estimates cover recognized foods and typical servings. Check quantities and add missing items before saving.")
    return HealthLog(foods, habits[0], habits[1], habits[2], habits[3], warnings.toList(), dayOffset, explicitDay?.toEpochDay())
}

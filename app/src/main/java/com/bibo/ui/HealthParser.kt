package com.bibo.ui

import android.content.Context
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import org.json.JSONObject

data class FoodItem(
    val label: String,
    val calories: Int,
    val sugarG: Double,
    val caffeineMg: Int,
)

/** Foods plus any habits mentioned; each habit is null when not mentioned. */
data class HealthLog(
    val foods: List<FoodItem> = emptyList(),
    val showered: Boolean? = null,
    val cleanClothes: Boolean? = null,
    val workedOut: Boolean? = null,
    val prayed: Boolean? = null,
) {
    val isEmpty: Boolean
        get() = foods.isEmpty() &&
            showered == null && cleanClothes == null && workedOut == null && prayed == null
}

/**
 * Turns "had a coffee and a donut, showered and prayed" into logged foods (with
 * on-device nutrition estimates from Gemini Nano) plus the habits that were mentioned.
 * Falls back to a keyword + food-table pass when Nano isn't available.
 */
class HealthParser(private val context: Context) {

    suspend fun parse(text: String): HealthLog {
        geminiParse(text)?.takeUnless { it.isEmpty }?.let { return it }
        return fallbackParse(text)
    }

    private suspend fun geminiParse(text: String): HealthLog? =
        try {
            val model = Generation.getClient()
            if (model.checkStatus() != FeatureStatus.AVAILABLE) {
                null
            } else {
                val json = model.generateContent(prompt(text))
                    .candidates.firstOrNull()?.text
                json?.let { parseJson(it) }
            }
        } catch (_: Throwable) {
            null
        }

    private fun prompt(text: String): String = """
        You extract nutrition and daily habits from a health log. Return ONLY compact JSON:
        {"foods":[{"name":str,"calories":int,"sugar_g":number,"caffeine_mg":int}],
         "showered":true/false/null,"clean_clothes":true/false/null,
         "worked_out":true/false/null,"prayed":true/false/null}
        Estimate typical nutrition for each food/drink. A habit is true if done, false if
        explicitly not done, null if not mentioned.
        Input: "had a coffee and a glazed donut, showered and prayed" ->
        {"foods":[{"name":"Coffee","calories":5,"sugar_g":0,"caffeine_mg":95},{"name":"Glazed donut","calories":260,"sugar_g":14,"caffeine_mg":0}],"showered":true,"clean_clothes":null,"worked_out":null,"prayed":true}
        Input: "${text.replace("\"", "'")}" ->
    """.trimIndent()

    private fun parseJson(raw: String): HealthLog? {
        val s = raw.indexOf('{')
        val e = raw.lastIndexOf('}')
        if (s < 0 || e <= s) return null
        return runCatching {
            val obj = JSONObject(raw.substring(s, e + 1))
            val foods = mutableListOf<FoodItem>()
            obj.optJSONArray("foods")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val f = arr.optJSONObject(i) ?: continue
                    val name = f.optString("name").trim()
                    if (name.isBlank()) continue
                    foods += FoodItem(
                        label = name.replaceFirstChar { it.uppercase() },
                        calories = f.optInt("calories", 0),
                        sugarG = f.optDouble("sugar_g", 0.0),
                        caffeineMg = f.optInt("caffeine_mg", 0),
                    )
                }
            }
            HealthLog(
                foods = foods,
                showered = obj.optBooleanOrNull("showered"),
                cleanClothes = obj.optBooleanOrNull("clean_clothes"),
                workedOut = obj.optBooleanOrNull("worked_out"),
                prayed = obj.optBooleanOrNull("prayed"),
            )
        }.getOrNull()
    }

    // ---- offline fallback ----------------------------------------------------

    private fun fallbackParse(text: String): HealthLog {
        val t = text.lowercase()
        val foods = FOOD_TABLE.mapNotNull { (re, template) ->
            if (re.containsMatchIn(t)) template else null
        }
        return HealthLog(
            foods = foods,
            showered = detect(t, SHOWER),
            cleanClothes = detect(t, CLOTHES),
            workedOut = detect(t, WORKOUT),
            prayed = detect(t, PRAY),
        )
    }

    private fun detect(text: String, keyword: Regex): Boolean? {
        val m = keyword.find(text) ?: return null
        val before = text.substring(0, m.range.first).takeLast(24)
        val negated = NEGATION.containsMatchIn(before)
        return !negated
    }

    companion object {
        private val SHOWER = Regex("""\bshower""", RegexOption.IGNORE_CASE)
        private val CLOTHES = Regex("""\b(clean clothes|fresh clothes|laundry|washed clothes)""", RegexOption.IGNORE_CASE)
        private val WORKOUT = Regex("""\b(work(ed)? out|workout|gym|exercis|train|ran|run|jog|lift)""", RegexOption.IGNORE_CASE)
        private val PRAY = Regex("""\b(pray|namaz|sala[ht])""", RegexOption.IGNORE_CASE)
        private val NEGATION = Regex("""\b(no|not|n'?t|did ?n'?t|have ?n'?t|skip|without|missed)\b""", RegexOption.IGNORE_CASE)

        // Rough offline estimates: label -> per-typical-serving macros.
        private val FOOD_TABLE: List<Pair<Regex, FoodItem>> = listOf(
            Regex("""\bespresso\b""") to FoodItem("Espresso", 3, 0.0, 64),
            Regex("""\b(latte|cappuccino)\b""") to FoodItem("Latte", 120, 10.0, 75),
            Regex("""\bcoffee\b""") to FoodItem("Coffee", 5, 0.0, 95),
            Regex("""\b(green tea|matcha)\b""") to FoodItem("Green tea", 2, 0.0, 30),
            Regex("""\btea\b""") to FoodItem("Tea", 2, 0.0, 47),
            Regex("""\b(red ?bull|monster|energy drink)\b""") to FoodItem("Energy drink", 110, 27.0, 80),
            Regex("""\b(coke|cola|soda|pepsi)\b""") to FoodItem("Soda", 140, 39.0, 34),
            Regex("""\bdonut\b""") to FoodItem("Donut", 260, 14.0, 0),
            Regex("""\bbanana\b""") to FoodItem("Banana", 105, 14.0, 0),
            Regex("""\bapple\b""") to FoodItem("Apple", 95, 19.0, 0),
            Regex("""\b(chocolate|candy)\b""") to FoodItem("Chocolate", 210, 24.0, 12),
            Regex("""\bpizza\b""") to FoodItem("Pizza slice", 285, 4.0, 0),
            Regex("""\b(sandwich|sub)\b""") to FoodItem("Sandwich", 350, 6.0, 0),
            Regex("""\b(protein shake|protein)\b""") to FoodItem("Protein shake", 160, 4.0, 0),
            Regex("""\b(rice|bowl)\b""") to FoodItem("Rice bowl", 350, 1.0, 0),
            Regex("""\b(egg|eggs)\b""") to FoodItem("Eggs", 155, 1.0, 0),
            Regex("""\bchicken\b""") to FoodItem("Chicken", 240, 0.0, 0),
            Regex("""\bwater\b""") to FoodItem("Water", 0, 0.0, 0),
        )
    }
}

private fun JSONObject.optBooleanOrNull(key: String): Boolean? =
    if (!has(key) || isNull(key)) null else optBoolean(key)

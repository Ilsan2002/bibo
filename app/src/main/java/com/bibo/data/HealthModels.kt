package com.bibo.data

/** Nutrition estimates are drafts; manual edits can replace them with known values. */
data class FoodItem(
    val label: String,
    val calories: Int,
    val sugarG: Double,
    val caffeineMg: Int,
    val estimated: Boolean = true,
    val provenance: String = "Offline serving estimate",
) {
    val valid: Boolean get() = label.isNotBlank() && label.length <= 160 && calories in 0..100_000 && sugarG.isFinite() && sugarG in 0.0..10_000.0 && caffeineMg in 0..10_000
}
data class HealthLog(
    val foods: List<FoodItem> = emptyList(),
    val showered: Boolean? = null,
    val cleanClothes: Boolean? = null,
    val workedOut: Boolean? = null,
    val prayed: Boolean? = null,
    val warnings: List<String> = emptyList(),
    val dayOffset: Long = 0,
    val explicitEpochDay: Long? = null,
) {
    val isEmpty: Boolean get() = foods.isEmpty() && showered == null && cleanClothes == null && workedOut == null && prayed == null
}

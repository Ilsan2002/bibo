package com.bibo.ui

import android.content.Context

data class HealthPreferences(val targetsEnabled: Boolean = false, val calorieTarget: Int = 2000,
    val sugarTarget: Int = 50, val caffeineTarget: Int = 400, val visibleHabitMask: Int = 15)
class HealthPreferenceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("bibo_health_preferences", Context.MODE_PRIVATE)
    fun read() = HealthPreferences(prefs.getBoolean("targets_enabled", false), prefs.getInt("calories", 2000),
        prefs.getInt("sugar", 50), prefs.getInt("caffeine", 400), prefs.getInt("visible_habits", 15))
    fun save(value: HealthPreferences) { prefs.edit().putBoolean("targets_enabled", value.targetsEnabled)
        .putInt("calories", value.calorieTarget).putInt("sugar", value.sugarTarget).putInt("caffeine", value.caffeineTarget)
        .putInt("visible_habits", value.visibleHabitMask).apply() }
}

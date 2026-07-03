package de.tobiasschuerg.weekview.data

data class WeekViewConfig(
    val scalingFactor: Float = 1f,
    val minScalingFactor: Float = 0.5f,
    val maxScalingFactor: Float = 2f,
    val showCurrentTimeIndicator: Boolean = true,
    val highlightCurrentDay: Boolean = true,
    val currentTimeLineOnlyToday: Boolean = false,
    // Bibo patch: hide the built-in day header when the host app renders its own
    val showDayHeader: Boolean = true,
) {
    init {
        require(minScalingFactor > 0f) { "minScalingFactor must be positive, but was $minScalingFactor" }
        require(minScalingFactor <= maxScalingFactor) {
            "minScalingFactor ($minScalingFactor) must be <= maxScalingFactor ($maxScalingFactor)"
        }
    }
}

package de.tobiasschuerg.weekview.compose

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.tobiasschuerg.weekview.compose.style.WeekViewStyle
import de.tobiasschuerg.weekview.compose.style.defaultWeekViewStyle
import de.tobiasschuerg.weekview.data.EventConfig
import de.tobiasschuerg.weekview.data.WeekData
import de.tobiasschuerg.weekview.data.WeekViewConfig
import de.tobiasschuerg.weekview.util.TimeSpan
import java.time.Duration
import java.time.LocalTime

/**
 * Main Composable for the WeekView component.
 *
 * Pinch-to-zoom only activates on multi-touch (2+ fingers) so that single-finger
 * horizontal swipes pass through to a parent HorizontalPager or similar container.
 *
 * Bibo patch: style pass-through, initial scroll position, bottom content padding.
 */
@Composable
fun WeekViewCompose(
    weekData: WeekData,
    weekViewConfig: WeekViewConfig,
    modifier: Modifier = Modifier,
    eventConfig: EventConfig = EventConfig(),
    actions: WeekViewActions = WeekViewActions(),
    style: WeekViewStyle = defaultWeekViewStyle(),
    initialScrollTime: LocalTime? = null,
    bottomContentPadding: Dp = 0.dp,
) {
    var localScalingFactor by remember { mutableFloatStateOf(weekViewConfig.scalingFactor) }
    val activeWeekConfig = weekViewConfig.copy(scalingFactor = localScalingFactor)

    Box(
        modifier =
            modifier
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            if (event.changes.size >= 2) {
                                val zoom = event.calculateZoom()
                                if (zoom != 1f) {
                                    val newScalingFactor =
                                        (localScalingFactor * zoom)
                                            .coerceIn(
                                                activeWeekConfig.minScalingFactor,
                                                activeWeekConfig.maxScalingFactor,
                                            )
                                    if (newScalingFactor != localScalingFactor) {
                                        localScalingFactor = newScalingFactor
                                        actions.onScalingFactorChange?.invoke(newScalingFactor)
                                    }
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                },
    ) {
        WeekBackgroundCompose(
            modifier = Modifier.fillMaxSize(),
            dateRange = weekData.dateRange,
            timeRange =
                weekData.getTimeSpan() ?: TimeSpan.of(
                    LocalTime.of(6, 0),
                    Duration.ofHours(12),
                ),
            events = weekData.getSingleEvents(),
            allDayEvents = weekData.getAllDayEvents(),
            multiDayEvents = weekData.getMultiDayEvents(),
            eventConfig = eventConfig,
            weekViewConfig = activeWeekConfig,
            onEventClick = actions.onEventClick,
            onEventLongPress = actions.onEventLongPress,
            style = style,
            initialScrollTime = initialScrollTime,
            bottomContentPadding = bottomContentPadding,
        )
    }
}

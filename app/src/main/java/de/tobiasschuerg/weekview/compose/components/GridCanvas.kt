package de.tobiasschuerg.weekview.compose.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Dp
import de.tobiasschuerg.weekview.compose.style.WeekViewStyle
import de.tobiasschuerg.weekview.compose.style.defaultWeekViewStyle
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

// Bibo patch: hairline grid, slightly bolder now-line.
@Composable
internal fun GridCanvas(
    modifier: Modifier = Modifier,
    columnCount: Int,
    rowHeightDp: Dp,
    totalHours: Float,
    days: List<LocalDate>,
    today: LocalDate,
    showNowIndicator: Boolean,
    highlightCurrentDay: Boolean,
    currentTimeLineOnlyToday: Boolean,
    now: LocalTime,
    gridStartTime: LocalTime,
    effectiveEndTime: LocalTime,
    style: WeekViewStyle = defaultWeekViewStyle(),
) {
    Canvas(modifier = modifier) {
        val columnWidthPx = if (columnCount > 0) size.width / columnCount else size.width
        val rowHeightPx = rowHeightDp.toPx()

        // Today highlight (behind the grid lines)
        if (highlightCurrentDay && days.contains(today)) {
            val todayColumnIndex = days.indexOf(today)
            val left = todayColumnIndex * columnWidthPx
            drawRect(
                color = style.colors.todayHighlight,
                topLeft = Offset(left, 0f),
                size = Size(columnWidthPx, size.height),
            )
        }

        // Vertical lines (day columns) — only interior lines, skip outer edges
        for (i in 1 until columnCount) {
            val x = i * columnWidthPx
            drawLine(
                color = style.colors.gridLineColor,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 1f,
            )
        }

        // Horizontal lines (hours) - full width
        val hourLineCount = kotlin.math.ceil(totalHours).toInt()
        for (i in 0..hourLineCount) {
            val y = i * rowHeightPx
            drawLine(
                color = style.colors.gridLineColor,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1f,
            )
        }

        // Now indicator line
        if (showNowIndicator && now.isAfter(gridStartTime) && now.isBefore(effectiveEndTime)) {
            val nowPositionMinutes = ChronoUnit.MINUTES.between(gridStartTime, now)
            val nowY = (nowPositionMinutes / 60f) * rowHeightPx
            if (nowY >= 0 && nowY <= size.height) {
                val dotRadius = 10f
                if (currentTimeLineOnlyToday) {
                    if (days.contains(today)) {
                        val todayColumnIndex = days.indexOf(today)
                        val left = todayColumnIndex * columnWidthPx
                        val right = left + columnWidthPx
                        drawLine(
                            color = style.colors.nowIndicator,
                            start = Offset(left, nowY),
                            end = Offset(right, nowY),
                            strokeWidth = 4f,
                        )
                        drawCircle(
                            color = style.colors.nowIndicator,
                            radius = dotRadius,
                            center = Offset(left, nowY),
                        )
                    }
                } else {
                    drawLine(
                        color = style.colors.nowIndicator,
                        start = Offset(0f, nowY),
                        end = Offset(size.width, nowY),
                        strokeWidth = 4f,
                    )
                    drawCircle(
                        color = style.colors.nowIndicator,
                        radius = dotRadius,
                        center = Offset(0f, nowY),
                    )
                }
            }
        }
    }
}

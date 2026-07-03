package de.tobiasschuerg.weekview.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.tobiasschuerg.weekview.data.Event
import de.tobiasschuerg.weekview.data.EventConfig
import de.tobiasschuerg.weekview.util.EventOverlapCalculator
import de.tobiasschuerg.weekview.util.EventPositionUtil
import de.tobiasschuerg.weekview.util.toLocalString
import java.time.LocalTime

/**
 * Composable that renders individual events on the week view grid.
 *
 * Bibo patch: adaptive content — text size, line count, and what is shown
 * depend on the block's rendered height, so short blocks stay readable
 * instead of clipping three lines of text. Also enforces a minimum height.
 */
@Composable
fun EventCompose(
    modifier: Modifier = Modifier,
    event: Event.Single,
    scalingFactor: Float,
    eventConfig: EventConfig,
    startTime: LocalTime,
    columnWidth: Dp,
    eventLayout: EventOverlapCalculator.EventLayout,
    onEventClick: ((event: Event) -> Unit)? = null,
    onEventLongPress: ((event: Event) -> Unit)? = null,
) {
    val (topOffset, rawHeight) =
        EventPositionUtil.calculateVerticalOffsets(
            event = event,
            startTime = startTime,
            scalingFactor = scalingFactor,
        )
    val eventHeight = if (rawHeight < 14.dp) 14.dp else rawHeight

    val eventWidth = columnWidth * eventLayout.widthFraction
    val horizontalOffset = columnWidth * eventLayout.offsetFraction

    val backgroundColor = Color(event.backgroundColor)
    val textColor = Color(event.textColor)

    val compact = eventHeight < 26.dp
    val showTime = eventConfig.showTimeEnd && eventHeight >= 48.dp
    val titleLines =
        when {
            compact || showTime -> 1
            eventHeight >= 40.dp -> 2
            else -> 1
        }

    Box(
        modifier =
            modifier
                .testTag("EventView_${event.id}")
                .offset(x = horizontalOffset, y = topOffset)
                .size(width = eventWidth, height = eventHeight)
                .let { if (eventConfig.eventSpacingDp > 0) it.padding(eventConfig.eventSpacingDp.dp) else it }
                .clip(RoundedCornerShape(6.dp))
                .background(backgroundColor)
                .pointerInput(event.id) {
                    detectTapGestures(
                        onTap = { onEventClick?.invoke(event) },
                        onLongPress = { onEventLongPress?.invoke(event) },
                    )
                }
                .padding(horizontal = 6.dp, vertical = if (compact) 1.dp else 3.dp),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .testTag("EventViewInner_${event.id}"),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = if (compact) Arrangement.Center else Arrangement.Top,
        ) {
            Text(
                text = event.title,
                color = textColor,
                fontSize = if (compact) 10.sp else 12.sp,
                lineHeight = if (compact) 11.sp else 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = titleLines,
                overflow = TextOverflow.Ellipsis,
            )

            if (eventConfig.showSubtitle && event.subTitle?.isNotBlank() == true && eventHeight >= 60.dp) {
                Text(
                    text = event.subTitle,
                    color = textColor.copy(alpha = 0.8f),
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (showTime) {
                val timeText = "${event.timeSpan.start.toLocalString()} – ${event.timeSpan.endExclusive.toLocalString()}"
                Text(
                    text = timeText,
                    color = textColor.copy(alpha = 0.75f),
                    fontSize = 9.sp,
                    lineHeight = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

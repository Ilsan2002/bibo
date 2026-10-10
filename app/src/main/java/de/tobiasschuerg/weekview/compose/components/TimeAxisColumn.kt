package de.tobiasschuerg.weekview.compose.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.tobiasschuerg.weekview.compose.style.WeekViewStyle
import de.tobiasschuerg.weekview.compose.style.defaultWeekViewStyle
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val hourFormatter = DateTimeFormatter.ofPattern("H:mm")

/** Time axis and grid have identical content bounds, so sharing ScrollState cannot fight over its maximum. */
@Composable
internal fun TimeAxisColumn(timeLabels: List<LocalTime>, now: LocalTime, gridStartTime: LocalTime,
    gridEndTime: LocalTime, rowHeightDp: Dp, gridHeightDp: Dp, leftOffsetDp: Dp,
    scrollState: ScrollState, showNowIndicator: Boolean, style: WeekViewStyle = defaultWeekViewStyle(),
    bottomContentPadding: Dp = 0.dp) {
    val density = LocalDensity.current
    Box(Modifier.width(leftOffsetDp).fillMaxHeight().clipToBounds()) {
        Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
            Box(Modifier.width(leftOffsetDp).height(gridHeightDp)) {
                timeLabels.forEach { label ->
                    val minutes = ChronoUnit.MINUTES.between(gridStartTime, label)
                    Text(label.format(hourFormatter), modifier = Modifier.offset(y = rowHeightDp * (minutes / 60f)).fillMaxWidth().padding(end = 6.dp),
                        maxLines = 1, textAlign = TextAlign.End, style = TextStyle(fontSize = 11.sp, color = style.colors.timeLabelTextColor))
                }
            }
            Spacer(Modifier.height(bottomContentPadding))
        }
        if (showNowIndicator && now >= gridStartTime && now < gridEndTime) {
            val minutes = ChronoUnit.MINUTES.between(gridStartTime, now)
            val markerY = with(density) { (rowHeightDp * (minutes / 60f) - 12.dp).roundToPx() }
            Box(Modifier.offset { IntOffset(0, markerY - scrollState.value) }.width(leftOffsetDp).height(24.dp), contentAlignment = Alignment.Center) {
                Text(now.format(hourFormatter), modifier = Modifier.fillMaxWidth().padding(end = 6.dp), maxLines = 1,
                    style = TextStyle(fontSize = 11.sp, color = style.colors.nowIndicator, fontWeight = FontWeight.Bold, textAlign = TextAlign.End))
            }
        }
    }
}

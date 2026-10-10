package com.bibo.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.bibo.MainActivity
import com.bibo.data.BiboDb
import com.bibo.data.Rewards
import com.bibo.data.TimerController
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Everything the widget renders, loaded off the main thread in provideGlance. */
private data class WidgetData(
    val running: Boolean,
    val runningTitle: String,
    val elapsedMin: Long,
    val suggestedTitle: String?,
    val suggestedReward: Int,
    val earnedCents: Int,
    val budgetCents: Int,
    val quote: String,
    val sessionId: String?,
    val suggestedTaskId: Long?,
    val startedAt: Long,
)

private object WidgetParameters {
    val sessionId = ActionParameters.Key<String>("session_id")
    val taskId = ActionParameters.Key<Long>("task_id")
}

class BiboWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = load(context)
        provideContent {
            GlanceTheme {
                WidgetBody(data)
            }
        }
    }

    private suspend fun load(context: Context): WidgetData = withContext(Dispatchers.IO) {
        TimerController.initialize(context)
        val session = BiboDb.get(context).workSessions().active()
        val running = session != null
        val start = session?.startMillis ?: 0
        val elapsed = session?.let(TimerController::elapsedMillis)?.div(60_000) ?: 0
        val top = runCatching {
            BiboDb.get(context).todos().incompleteOnce()
                .filter { it.parentId == null }.minByOrNull { it.sortOrder }
        }.getOrNull()
        val earned = runCatching { Rewards.earnedCents(context) }.getOrDefault(0)
        val budget = Rewards.budgetCents(context)
        val quote = Rewards.QUOTES[(LocalDate.now().toEpochDay() % Rewards.QUOTES.size).toInt()]
        WidgetData(
            running = running,
            runningTitle = session?.title.orEmpty(),
            elapsedMin = elapsed,
            suggestedTitle = top?.title,
            suggestedReward = top?.rewardCents ?: 0,
            earnedCents = earned,
            budgetCents = budget,
            quote = quote,
            sessionId = session?.id,
            suggestedTaskId = top?.id,
            startedAt = start,
        )
    }
}

class BiboWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BiboWidget()
}

/** Stop the running timer (widget "Stop" tap). */
class ToggleTimerAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        parameters[WidgetParameters.sessionId]?.let { TimerController.stopSession(context, it) }
        BiboWidget().updateAll(context)
    }
}

/** Start the top open task's timer straight from the widget ("Start" tap). */
class StartTopTaskAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val taskId = parameters[WidgetParameters.taskId]
        if (taskId != null) TimerController.startTaskAwait(context, taskId, "Task", null, requireIdle = true)
        BiboWidget().updateAll(context)
    }
}

@Composable
private fun WidgetBody(data: WidgetData) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.background)
            .cornerRadius(16.dp)
            .padding(12.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        // Header: date + this-week treat money.
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                LocalDate.now().format(DateTimeFormatter.ofPattern("EEE, MMM d")),
                style = TextStyle(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = GlanceTheme.colors.onSurface,
                ),
                modifier = GlanceModifier.defaultWeight(),
            )
            Text(
                "💰 ${Rewards.format(data.earnedCents)}/${Rewards.format(data.budgetCents)}",
                style = TextStyle(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = GlanceTheme.colors.primary,
                ),
            )
        }
        Spacer(GlanceModifier.height(8.dp))

        if (data.running) {
            // Running: what you're on + elapsed + stop.
            Text(
                "⏱ ${data.runningTitle.ifBlank { "Focusing" }}",
                maxLines = 1,
                style = TextStyle(
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = GlanceTheme.colors.onSurface,
                ),
            )
            Spacer(GlanceModifier.height(2.dp))
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Started ${java.time.Instant.ofEpochMilli(data.startedAt).atZone(java.time.ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("H:mm"))}",
                    style = TextStyle(fontSize = 13.sp, color = GlanceTheme.colors.onSurfaceVariant),
                    modifier = GlanceModifier.defaultWeight(),
                )
                Text(
                    "⏹ Stop",
                    style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, color = GlanceTheme.colors.primary),
                    modifier = GlanceModifier
                        .clickable(actionRunCallback<ToggleTimerAction>(parameters = androidx.glance.action.actionParametersOf(WidgetParameters.sessionId to data.sessionId.orEmpty())))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        } else {
            // Idle: a nudge, then a proposed task to start.
            Text(
                data.quote,
                maxLines = 3,
                style = TextStyle(
                    fontSize = 13.sp,
                    color = GlanceTheme.colors.onSurfaceVariant,
                ),
            )
            Spacer(GlanceModifier.height(10.dp))
            if (data.suggestedTitle != null) {
                Text(
                    "Next up",
                    style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurfaceVariant),
                )
                Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        data.suggestedTitle + if (data.suggestedReward > 0) "  ${Rewards.format(data.suggestedReward)}" else "",
                        maxLines = 2,
                        style = TextStyle(
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = GlanceTheme.colors.onSurface,
                        ),
                        modifier = GlanceModifier.defaultWeight(),
                    )
                    Text(
                        "▶ Start",
                        style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.primary),
                        modifier = GlanceModifier
                            .clickable(actionRunCallback<StartTopTaskAction>(parameters = androidx.glance.action.actionParametersOf(WidgetParameters.taskId to (data.suggestedTaskId ?: -1L))))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            } else {
                Text(
                    "No open tasks — add one to get going.",
                    style = TextStyle(fontSize = 13.sp, color = GlanceTheme.colors.onSurfaceVariant),
                )
            }
        }
    }
}

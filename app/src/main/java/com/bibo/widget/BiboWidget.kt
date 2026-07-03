package com.bibo.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.GlanceTheme
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.bibo.MainActivity
import com.bibo.data.BiboDb
import com.bibo.data.DeviceCalendarRepo
import com.bibo.data.TimerController
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class AgendaItem(val start: Long, val title: String)

private val widgetTimeFmt = DateTimeFormatter.ofPattern("H:mm")

class BiboWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val items = loadAgenda(context)
        val running = TimerController.isRunning(context)
        provideContent {
            GlanceTheme {
                WidgetBody(items, running)
            }
        }
    }

    private suspend fun loadAgenda(context: Context): List<AgendaItem> =
        withContext(Dispatchers.IO) {
            val zone = ZoneId.systemDefault()
            val dayStart = LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli()
            val dayEnd = LocalDate.now().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

            val blocks = runCatching {
                BiboDb.get(context).activityBlocks().blocksInList(dayStart, dayEnd)
                    .map { AgendaItem(it.startMillis, it.title) }
            }.getOrDefault(emptyList())

            val calRepo = DeviceCalendarRepo(context)
            val events = if (calRepo.hasPermissions()) {
                runCatching {
                    calRepo.queryInstances(dayStart, dayEnd)
                        .filter { !it.allDay }
                        .map { AgendaItem(it.begin, it.title) }
                }.getOrDefault(emptyList())
            } else {
                emptyList()
            }

            (blocks + events).sortedBy { it.start }.take(6)
        }
}

class BiboWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BiboWidget()
}

class ToggleTimerAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        if (TimerController.isRunning(context)) {
            TimerController.stopTimer(context)
        } else {
            TimerController.startTimer(context, "Quick timer")
        }
        BiboWidget().updateAll(context)
    }
}

@Composable
private fun WidgetBody(items: List<AgendaItem>, timerRunning: Boolean) {
    val zone = ZoneId.systemDefault()
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.background)
            .cornerRadius(16.dp)
            .padding(12.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMM d")),
                style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = GlanceTheme.colors.onSurface,
                ),
                modifier = GlanceModifier.defaultWeight(),
            )
            Text(
                if (timerRunning) "⏹ Stop" else "▶ Timer",
                style = TextStyle(fontSize = 13.sp, color = GlanceTheme.colors.primary),
                modifier = GlanceModifier
                    .clickable(actionRunCallback<ToggleTimerAction>())
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        Spacer(GlanceModifier.height(6.dp))

        if (items.isEmpty()) {
            Text(
                "Nothing scheduled today",
                style = TextStyle(fontSize = 13.sp, color = GlanceTheme.colors.onSurfaceVariant),
            )
        } else {
            LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                items(items) { item ->
                    Row(
                        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            Instant.ofEpochMilli(item.start).atZone(zone).toLocalTime()
                                .format(widgetTimeFmt),
                            style = TextStyle(
                                fontSize = 12.sp,
                                color = GlanceTheme.colors.primary,
                                fontWeight = FontWeight.Medium,
                            ),
                            modifier = GlanceModifier.width(44.dp),
                        )
                        Text(
                            item.title,
                            maxLines = 1,
                            style = TextStyle(fontSize = 13.sp, color = GlanceTheme.colors.onSurface),
                        )
                    }
                }
            }
        }
    }
}

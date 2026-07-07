package com.bibo.data

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * A gentle daily "goal spotlight": once a day it surfaces one long-term goal and its next
 * task, rotating through your goals so none quietly falls off your radar.
 */
object GoalReminders {
    private const val CHANNEL_ID = "goal_reminders"
    private const val NOTIF_ID = 77
    const val ACTION_SHOW = "com.bibo.action.GOAL_SPOTLIGHT"
    private const val HOUR_OF_DAY = 9 // ~9am

    /** (Re)schedule the daily spotlight. Idempotent — safe to call on every launch. */
    fun schedule(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        var first = LocalDate.now().atTime(LocalTime.of(HOUR_OF_DAY, 0)).atZone(zone).toInstant().toEpochMilli()
        if (first <= now) first += AlarmManager.INTERVAL_DAY
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP, first, AlarmManager.INTERVAL_DAY, pendingIntent(context))
    }

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, 0,
            Intent(context, GoalReminderReceiver::class.java).setAction(ACTION_SHOW),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun notify(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            val db = BiboDb.get(context)
            val goals = runCatching { db.goals().allOnce() }.getOrDefault(emptyList())
            if (goals.isEmpty()) return@launch
            // Rotate which goal is spotlighted from day to day.
            val goal = goals[(LocalDate.now().toEpochDay() % goals.size).toInt()]
            val next = runCatching { db.todos().nextForGoal(goal.id) }.getOrNull()

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                manager.getNotificationChannel(CHANNEL_ID) == null
            ) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Goal reminders", NotificationManager.IMPORTANCE_DEFAULT)
                )
            }

            val open = PendingIntent.getActivity(
                context, 1,
                context.packageManager.getLaunchIntentForPackage(context.packageName)
                    ?.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("🎯 ${goal.name}")
                .setContentText(next?.let { "Next: ${it.title}" } ?: "No next task yet — add one")
                .setSmallIcon(android.R.drawable.star_on)
                .setColor(goal.color)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
            runCatching { manager.notify(NOTIF_ID, notification) }
        }
    }
}

class GoalReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> GoalReminders.schedule(context)
            else -> GoalReminders.notify(context)
        }
    }
}

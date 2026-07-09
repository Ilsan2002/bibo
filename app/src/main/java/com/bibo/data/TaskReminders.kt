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
import com.bibo.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Per-task reminders that frame the small next action inside its bigger goal — the nudge
 * that says "open the laptop, make the first call — this is what kicks off your first
 * client." The framing line is written by the mentor when it creates the task and stored
 * on the task; the alarm just surfaces it at the chosen time.
 */
object TaskReminders {
    private const val CHANNEL_ID = "task_reminders"
    const val EXTRA_TASK_ID = "task_id"

    fun schedule(context: Context, taskId: Long, whenMillis: Long) {
        if (whenMillis <= System.currentTimeMillis()) return
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // setAndAllowWhileIdle fires through Doze and needs no exact-alarm permission.
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMillis, pendingIntent(context, taskId))
    }

    fun cancel(context: Context, taskId: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(context, taskId))
    }

    /** Re-arm every future reminder — call on boot and app launch. */
    fun rescheduleAll(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            val now = System.currentTimeMillis()
            runCatching { BiboDb.get(context).todos().withReminders() }.getOrDefault(emptyList())
                .forEach { t -> t.reminderAt?.let { if (it > now) schedule(context, t.id, it) } }
        }
    }

    private fun pendingIntent(context: Context, taskId: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            taskId.toInt(),
            Intent(context, TaskReminderReceiver::class.java)
                .setAction("com.bibo.action.TASK_REMINDER")
                .putExtra(EXTRA_TASK_ID, taskId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun notify(context: Context, taskTitle: String, body: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            manager.getNotificationChannel(CHANNEL_ID) == null
        ) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Task reminders", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val open = PendingIntent.getActivity(
            context, 4,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_TAB, MainActivity.TAB_TASKS),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("▶ $taskTitle")
            .setContentText(body.take(140))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        // Notification id namespaced away from the goal/check-in ids (77, 78).
        runCatching { manager.notify(1000, notification) }
    }
}

class TaskReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            TaskReminders.rescheduleAll(context)
            return
        }
        val taskId = intent.getLongExtra(TaskReminders.EXTRA_TASK_ID, -1)
        if (taskId < 0) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val task = runCatching { BiboDb.get(context).todos().byId(taskId) }.getOrNull()
                if (task != null && task.completedAt == null) {
                    val body = task.reminderNote?.takeIf { it.isNotBlank() }
                        ?: "Small step, real momentum — do this one now."
                    TaskReminders.notify(context, task.title, body)
                }
            } finally {
                pending.finish()
            }
        }
    }
}

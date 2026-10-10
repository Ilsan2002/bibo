package com.bibo.data

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.bibo.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object TaskReminders {
    private const val CHANNEL_ID = "task_reminders"
    const val EXTRA_TASK_ID = "task_id"
    private const val EXTRA_AT = "reminder_at"
    const val ACTION_SHOW = "com.bibo.action.TASK_REMINDER"
    const val ACTION_SNOOZE = "com.bibo.action.TASK_SNOOZE"
    const val ACTION_COMPLETE = "com.bibo.action.TASK_COMPLETE"

    fun deliveryEnabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
    }
    fun schedule(context: Context, taskId: Long, whenMillis: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(alarmIntent(context, taskId, whenMillis))
        if (whenMillis > AppTime.nowMillis()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
            whenMillis, alarmIntent(context, taskId, whenMillis))
    }
    fun cancel(context: Context, taskId: Long) {
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(alarmIntent(context, taskId, 0))
        NotificationManagerCompat.from(context).cancel("task:$taskId", 1000)
    }
    suspend fun rescheduleAllAwait(context: Context) {
        val now = AppTime.nowMillis()
        BiboDb.get(context).todos().withReminders().forEach { task ->
            task.reminderAt?.let { if (it > now) schedule(context, task.id, it) }
        }
    }
    fun rescheduleAll(context: Context) {
        AppScope.io.launch { runCatching { rescheduleAllAwait(context.applicationContext) } }
    }
    private fun alarmIntent(context: Context, id: Long, at: Long): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, TaskReminderReceiver::class.java).setAction(ACTION_SHOW)
            .setData(Uri.parse("bibo://task-reminder/$id")).putExtra(EXTRA_TASK_ID, id).putExtra(EXTRA_AT, at),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    private fun actionIntent(context: Context, id: Long, action: String): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, TaskReminderReceiver::class.java).setAction(action)
            .setData(Uri.parse("bibo://task-action/$id/$action")).putExtra(EXTRA_TASK_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun notify(context: Context, task: TodoTask, body: String, canComplete: Boolean = true): Boolean {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Task reminders", NotificationManager.IMPORTANCE_HIGH))
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled() || manager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        val open = PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java).setAction("com.bibo.action.OPEN_TASK")
                .setData(Uri.parse("bibo://tasks/${task.id}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_TAB, MainActivity.TAB_TASKS).putExtra(EXTRA_TASK_ID, task.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID).setContentTitle(task.title)
            .setContentText(body.take(140)).setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(android.R.drawable.ic_menu_my_calendar).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setGroup("bibo.tasks").setAutoCancel(true).setContentIntent(open)
            .addAction(android.R.drawable.ic_lock_idle_alarm, "Snooze 15 min", actionIntent(context, task.id, ACTION_SNOOZE))
        if (canComplete) builder.addAction(android.R.drawable.checkbox_on_background, "Complete", actionIntent(context, task.id, ACTION_COMPLETE))
        // Tag retains the full Long identity and keeps independent tasks visible together.
        return try {
            NotificationManagerCompat.from(context).notify("task:${task.id}", 1000, builder.build())
            true
        } catch (error: SecurityException) {
            android.util.Log.w("TaskReminders", "Notification permission changed before task delivery", error)
            false
        }
    }

    suspend fun receive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_TASK_ID, -1)
        if (id <= 0) return
        val db = BiboDb.get(context)
        val task = db.todos().byId(id)?.takeIf { it.completedAt == null && it.deletedAt == null } ?: return
        when (intent.action) {
            ACTION_SNOOZE -> {
                TaskCommands.update(context, id, TaskPatch(reminderAt = PatchField.Set(AppTime.nowMillis() + 15 * 60_000L)))
                NotificationManagerCompat.from(context).cancel("task:$id", 1000)
            }
            ACTION_COMPLETE -> {
                if (db.todos().allOnce().any { it.parentId == id && it.completedAt == null }) {
                    notify(context, task, "Open this task to review and complete its remaining steps.", false)
                } else TaskCommands.complete(context, id)
            }
            ACTION_SHOW -> {
                val expected = intent.getLongExtra(EXTRA_AT, -1)
                if (task.reminderAt == null || task.reminderAt != expected || expected > AppTime.nowMillis()) return
                val hasOpenChildren = db.todos().allOnce().any { it.parentId == id && it.completedAt == null }
                notify(context, task, task.reminderNote?.takeIf { it.isNotBlank() } ?: "A small next step toward your goal.", !hasOpenChildren)
            }
        }
    }
}

class TaskReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val system = intent.action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)
        if (!system && intent.action !in setOf(TaskReminders.ACTION_SHOW, TaskReminders.ACTION_SNOOZE, TaskReminders.ACTION_COMPLETE)) return
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (system) TaskReminders.rescheduleAllAwait(app) else TaskReminders.receive(app, intent)
            } catch (error: Exception) { android.util.Log.w("TaskReminders", "Reminder could not be delivered", error) }
            finally { pending.finish() }
        }
    }
}

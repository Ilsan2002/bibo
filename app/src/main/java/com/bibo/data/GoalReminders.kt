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
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** One-shot local-clock alarm, recomputed after delivery and time/timezone changes. */
object GoalReminders {
    private const val CHANNEL_ID = "goal_reminders"
    const val ACTION_SHOW = "com.bibo.action.GOAL_SPOTLIGHT"
    private fun prefs(context: Context) = context.getSharedPreferences("goal_reminders", Context.MODE_PRIVATE)
    fun enabled(context: Context): Boolean = prefs(context).getBoolean("enabled", true)
    fun hour(context: Context): Int = prefs(context).getInt("hour", 9).coerceIn(0, 23)
    fun minute(context: Context): Int = prefs(context).getInt("minute", 0).coerceIn(0, 59)
    fun configure(context: Context, enabled: Boolean, hour: Int = hour(context), minute: Int = minute(context)) {
        prefs(context).edit().putBoolean("enabled", enabled).putInt("hour", hour.coerceIn(0, 23)).putInt("minute", minute.coerceIn(0, 59)).apply()
        schedule(context)
    }
    fun schedule(context: Context, afterDelivery: Boolean = false) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = pendingIntent(context)
        am.cancel(pending)
        if (!enabled(context)) {
            NotificationManagerCompat.from(context).cancel(77)
            return
        }
        val zone = AppTime.zone()
        val today = AppTime.today(zone)
        val time = LocalTime.of(hour(context), minute(context))
        var next = today.atTime(time).atZone(zone).toInstant().toEpochMilli()
        if (next <= AppTime.nowMillis()) {
            val deliveredToday = prefs(context).getLong("delivered_day", Long.MIN_VALUE) == today.toEpochDay()
            // Opening the app while an inexact 9am alarm is pending must not postpone it to tomorrow.
            next = if (!afterDelivery && !deliveredToday && AppTime.nowMillis() - next < 2 * 60 * 60_000L) AppTime.nowMillis() + 1_000L
                else today.plusDays(1).atTime(time).atZone(zone).toInstant().toEpochMilli()
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending)
    }
    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(context, 0,
        Intent(context, GoalReminderReceiver::class.java).setAction(ACTION_SHOW), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    suspend fun notify(context: Context): Boolean {
        if (!enabled(context)) return false
        val today = AppTime.today().toEpochDay()
        if (prefs(context).getLong("delivered_day", Long.MIN_VALUE) == today) return false
        val db = BiboDb.get(context)
        val goals = db.goals().allOnce()
        val tasks = db.todos().allOnce()
        // Finished goals do not receive a misleading request to invent more work.
        val eligible = goals.filter { goal -> tasks.any { it.goalId == goal.id && it.completedAt == null && it.parentId == null } }
        if (eligible.isEmpty()) return false
        val goal = eligible[Math.floorMod(AppTime.today().toEpochDay(), eligible.size.toLong()).toInt()]
        val parent = tasks.filter { it.goalId == goal.id && it.parentId == null && it.completedAt == null }.minByOrNull { it.sortOrder } ?: return false
        val next = tasks.filter { it.parentId == parent.id && it.completedAt == null }.minByOrNull { it.sortOrder } ?: parent
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Goal spotlight", NotificationManager.IMPORTANCE_DEFAULT))
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled() || manager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java)
            .setAction("com.bibo.action.OPEN_GOAL").setData(Uri.parse("bibo://goals/${goal.id}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_OPEN_TAB, MainActivity.TAB_TASKS).putExtra("goal_id", goal.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return try {
            NotificationManagerCompat.from(context).notify(77, NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(goal.name).setContentText("Next step: ${next.title}")
                .setSmallIcon(android.R.drawable.star_on).setColor(goal.color).setAutoCancel(true).setContentIntent(open).build())
            prefs(context).edit().putLong("delivered_day", today).commit()
            true
        } catch (error: SecurityException) {
            android.util.Log.w("GoalReminders", "Notification permission changed before spotlight delivery", error)
            false
        }
    }
}

class GoalReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(GoalReminders.ACTION_SHOW, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)) return
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { if (intent.action == GoalReminders.ACTION_SHOW) GoalReminders.notify(app) }
            catch (error: Exception) { android.util.Log.w("GoalReminders", "Spotlight could not be delivered", error) }
            finally { try { GoalReminders.schedule(app, afterDelivery = intent.action == GoalReminders.ACTION_SHOW) } finally { pending.finish() } }
        }
    }
}

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
import com.bibo.ui.Mentor
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Evening mentor check-in: once a day (~9:30pm) the mentor looks at the day's data and
 * opens the conversation itself — posted as a notification and inserted into the chat
 * thread so it reads as one continuous relationship. Skipped when there's no API key,
 * when it already ran today, or when the user chatted within the last two hours.
 */
object MentorCheckin {
    private const val CHANNEL_ID = "mentor_checkin"
    private const val NOTIF_ID = 78
    const val ACTION_CHECKIN = "com.bibo.action.MENTOR_CHECKIN"
    private const val HOUR_OF_DAY = 21
    private const val MINUTE = 30
    private const val KEY_LAST_DAY = "last_checkin_day"

    /** (Re)schedule the daily check-in. Idempotent — safe to call on every launch. */
    fun schedule(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        var first = LocalDate.now().atTime(LocalTime.of(HOUR_OF_DAY, MINUTE))
            .atZone(zone).toInstant().toEpochMilli()
        if (first <= now) first += AlarmManager.INTERVAL_DAY
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP, first, AlarmManager.INTERVAL_DAY, pendingIntent(context))
    }

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, 2,
            Intent(context, MentorCheckinReceiver::class.java).setAction(ACTION_CHECKIN),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun alreadyRanToday(context: Context): Boolean =
        context.getSharedPreferences("mentor", Context.MODE_PRIVATE)
            .getLong(KEY_LAST_DAY, -1) == LocalDate.now().toEpochDay()

    fun markRanToday(context: Context) {
        context.getSharedPreferences("mentor", Context.MODE_PRIVATE)
            .edit().putLong(KEY_LAST_DAY, LocalDate.now().toEpochDay()).apply()
    }

    fun notify(context: Context, message: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            manager.getNotificationChannel(CHANNEL_ID) == null
        ) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Mentor check-ins", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }

        val open = PendingIntent.getActivity(
            context, 3,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_TAB, MainActivity.TAB_MENTOR),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Your mentor")
            .setContentText(message.take(140))
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { manager.notify(NOTIF_ID, notification) }
    }
}

class MentorCheckinReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> MentorCheckin.schedule(context)
            else -> {
                if (Mentor.apiKey(context) == null) return
                if (MentorCheckin.alreadyRanToday(context)) return
                // goAsync keeps the process alive past onReceive; the API call is kept
                // fast (no thinking, 512 max tokens) to fit the receiver's window.
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val reply = withTimeoutOrNull(25_000) { Mentor.checkIn(context) }
                        if (reply != null) {
                            MentorCheckin.markRanToday(context)
                            MentorCheckin.notify(context, reply)
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}

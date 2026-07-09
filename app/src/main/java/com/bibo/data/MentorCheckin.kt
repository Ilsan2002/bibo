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
 * Proactive mentor check-ins: several times a day the mentor looks at your data and opens
 * the conversation itself — plan the morning, push on an avoided task midday, hold you
 * accountable in the afternoon, reflect in the evening. Each is posted as a notification
 * and inserted into the chat thread so it reads as one continuous relationship. A given
 * slot is skipped when there's no API key, when another check-in fired in the last ~90
 * minutes, or when you chatted within the last two hours (so it never talks over you).
 */
object MentorCheckin {
    private const val CHANNEL_ID = "mentor_checkin"
    private const val NOTIF_ID = 78
    const val ACTION_CHECKIN = "com.bibo.action.MENTOR_CHECKIN"
    private const val KEY_LAST_AT = "last_checkin_at"

    /** Don't fire two check-ins closer together than this, whatever the schedule says. */
    private const val MIN_GAP_MS = 90 * 60 * 1000L

    /** Times of day the mentor reaches out (hour, minute), across waking hours. */
    private val SLOTS = listOf(
        8 to 30, 11 to 0, 13 to 30, 16 to 0, 18 to 30, 21 to 0,
    )

    /** (Re)schedule all daily check-ins. Idempotent — safe to call on every launch. */
    fun schedule(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        SLOTS.forEachIndexed { i, (hour, minute) ->
            var first = LocalDate.now().atTime(LocalTime.of(hour, minute))
                .atZone(zone).toInstant().toEpochMilli()
            if (first <= now) first += AlarmManager.INTERVAL_DAY
            am.setInexactRepeating(
                AlarmManager.RTC_WAKEUP, first, AlarmManager.INTERVAL_DAY, pendingIntent(context, i)
            )
        }
    }

    // Unique request code per slot so the alarms don't overwrite each other.
    private fun pendingIntent(context: Context, slot: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, 200 + slot,
            Intent(context, MentorCheckinReceiver::class.java).setAction(ACTION_CHECKIN),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun tooSoon(context: Context): Boolean {
        val last = context.getSharedPreferences("mentor", Context.MODE_PRIVATE)
            .getLong(KEY_LAST_AT, 0)
        return System.currentTimeMillis() - last < MIN_GAP_MS
    }

    fun markRan(context: Context) {
        context.getSharedPreferences("mentor", Context.MODE_PRIVATE)
            .edit().putLong(KEY_LAST_AT, System.currentTimeMillis()).apply()
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
                if (MentorCheckin.tooSoon(context)) return
                // Reserve this slot up-front so two alarms firing close together can't
                // both pass the gap check; the mentor still skips if you chatted recently.
                MentorCheckin.markRan(context)
                // goAsync keeps the process alive past onReceive; the API call is kept
                // fast (no thinking, 512 max tokens) to fit the receiver's window.
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val reply = withTimeoutOrNull(25_000) { Mentor.checkIn(context) }
                        if (reply != null) MentorCheckin.notify(context, reply)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}

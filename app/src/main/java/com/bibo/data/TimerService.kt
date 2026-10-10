package com.bibo.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Keeps a running timer / focus session alive with an ongoing notification (live time +
 * Stop action). Pomodoro phase truth comes from monotonic session timing; an alarm
 * wakes the phase projection. Lock-screen details and Mentor text are opt-in.
 */
class TimerService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastPhase: String? = null
    private var lastCheckpoint = 0L

    private val tick = object : Runnable {
        override fun run() {
            val ctx = applicationContext
            if (!TimerController.isRunning(ctx)) {
                stopSelf()
                return
            }
            if (TimerController.isPomodoro(ctx)) {
                if (TimerController.phase(ctx) != lastPhase) refresh(alert = true, phaseLabel = TimerController.phase(ctx))
            }
            if (android.os.SystemClock.elapsedRealtime() - lastCheckpoint >= 30_000L) {
                lastCheckpoint = android.os.SystemClock.elapsedRealtime()
                AppScope.io.launch { TimerController.checkpoint(ctx) }
            }
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            val expectedId = intent.getStringExtra(EXTRA_SESSION_ID)
            if (expectedId != null) AppScope.io.launch { TimerController.stopSession(applicationContext, expectedId) }
            return START_STICKY
        }
        // Meet the foreground deadline immediately, even when restoring Room state.
        startForegroundCompat(buildTimerNotification(applicationContext, alert = false))
        handler.removeCallbacks(tick)
        AppScope.io.launch {
            TimerController.initialize(applicationContext)
            withContext(Dispatchers.Main) {
                if (!TimerController.isRunning(applicationContext)) { stopSelf(); return@withContext }
                lastPhase = if (TimerController.isPomodoro(applicationContext)) TimerController.phase(applicationContext) else null
                refresh(false, TimerController.phase(applicationContext))
                handler.removeCallbacks(tick)
                handler.postDelayed(tick, 1_000L)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(tick)
    }

    private fun refresh(alert: Boolean, phaseLabel: String) {
        lastPhase = phaseLabel
        refreshNotification(applicationContext, alert)
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    companion object {
        // New id (the old "bibo_timer" was created at LOW importance, which One UI hides
        // from the lock screen; a channel's importance can't be raised after creation).
        private const val CHANNEL_ID = "bibo_focus_timer"
        private const val PHASE_CHANNEL_ID = "bibo_phase_alerts"
        private const val NOTIF_ID = 42
        const val ACTION_STOP = "com.bibo.action.STOP_TIMER"
        const val EXTRA_SESSION_ID = "session_id"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, TimerService::class.java)
            )
            schedulePhase(context)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TimerService::class.java))
            (context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager).cancel(phaseIntent(context))
        }

        private fun phaseIntent(context: Context) = PendingIntent.getBroadcast(context, 43,
            Intent(context, TimerPhaseReceiver::class.java).putExtra(EXTRA_SESSION_ID, TimerController.sessionId(context)), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        fun schedulePhase(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val pending = phaseIntent(context)
            if (!TimerController.isPomodoro(context)) { am.cancel(pending); return }
            val at = TimerController.phaseEnd(context)
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, pending)
                return
            }
            try {
                am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, pending)
            } catch (_: SecurityException) {
                // Access can be revoked between the capability check and scheduling.
                am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, pending)
            }
        }

        /**
         * Re-post the ongoing notification with the latest state — used when the mentor's
         * start comment arrives after the session began. Updating an existing notification
         * by id is safe from any thread and doesn't touch the service lifecycle.
         */
        @Synchronized fun refreshNotification(context: Context, alert: Boolean = false) {
            val snapshot = TimerController.state.value ?: return
            if (Looper.myLooper() != Looper.getMainLooper()) {
                Handler(Looper.getMainLooper()).post { if (TimerController.state.value?.id == snapshot.id) refreshNotification(context, alert) }
                return
            }
            val phase = SessionTiming.phase(TimerController.elapsedMillis(snapshot), snapshot.workMin, snapshot.breakMin)
            val key = "${snapshot.id}:${phase.kind}:${phase.pomodorosDone}"
            val p = context.getSharedPreferences("bibo_phase_receipts", Context.MODE_PRIVATE)
            val alreadyAlerted = p.getString("last_alert", null) == key
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID, buildTimerNotification(context.applicationContext, alert && !alreadyAlerted, snapshot))
            if (alert && !alreadyAlerted) p.edit().putString("last_alert", key).apply()
        }

        /** Build the current ongoing notification purely from [TimerController] state. */
        private fun buildTimerNotification(context: Context, alert: Boolean, snapshot: WorkSession? = TimerController.state.value): Notification {
            val title = snapshot?.title.orEmpty()
            val elapsed = snapshot?.let(TimerController::elapsedMillis) ?: 0
            val start = System.currentTimeMillis() - elapsed
            val pomodoro = snapshot?.pomodoro == true

            val displayTitle: String
            val fallbackText: String
            val whenTime: Long
            val countDown: Boolean
            val alertOnce: Boolean
            if (pomodoro) {
                val currentPhase = SessionTiming.phase(elapsed, snapshot!!.workMin, snapshot.breakMin)
                val work = currentPhase.kind == TimerController.PHASE_WORK
                displayTitle = (if (work) "Focus · " else "Break · ") + title
                fallbackText = if (alert) (if (work) "Back to work" else "Break time 🎉") else "Tap to open Bibo"
                whenTime = System.currentTimeMillis() + currentPhase.remainingMillis
                countDown = true
                alertOnce = !alert
            } else {
                displayTitle = title
                fallbackText = "Tap to open Bibo"
                whenTime = start
                countDown = false
                alertOnce = true
            }

            // The mentor's cheer becomes the body + expandable text, except during a Pomodoro
            // phase-change alert, which shows its own "Back to work" / "Break time" line.
            val preferences = context.getSharedPreferences("bibo_focus_defaults", Context.MODE_PRIVATE)
            val comment = if (alert || !preferences.getBoolean("session_comment", false)) "" else snapshot?.comment.orEmpty()
            val body = comment.ifBlank { fallbackText }

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(NotificationChannel(PHASE_CHANNEL_ID, "Pomodoro phase changes", NotificationManager.IMPORTANCE_DEFAULT))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                manager.getNotificationChannel(CHANNEL_ID) == null
            ) {
                // DEFAULT importance (not LOW) so One UI shows it as a real card on the lock
                // screen — but with no sound/vibration, so a running timer stays silent.
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Focus & timer", NotificationManager.IMPORTANCE_DEFAULT)
                        .apply {
                            setShowBadge(false)
                            setSound(null, null)
                            enableVibration(false)
                            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                        }
                )
                runCatching { manager.deleteNotificationChannel("bibo_timer") } // retire the old LOW one
            }

            val openApp = PendingIntent.getActivity(
                context, 0,
                context.packageManager.getLaunchIntentForPackage(context.packageName)
                    ?.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    ?.putExtra(com.bibo.MainActivity.EXTRA_OPEN_TAB, 2),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val stopIntent = PendingIntent.getService(
                context, snapshot?.id?.hashCode() ?: 1,
                Intent(context, TimerService::class.java).setAction(ACTION_STOP)
                    .setData(android.net.Uri.parse("bibo://session/${snapshot?.id}"))
                    .putExtra(EXTRA_SESSION_ID, snapshot?.id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

            val sound = context.getSharedPreferences("bibo_focus_defaults", Context.MODE_PRIVATE).getBoolean("phase_sound", true)
            val builder = NotificationCompat.Builder(context, if (alert && sound) PHASE_CHANNEL_ID else CHANNEL_ID)
                .setContentTitle(displayTitle.ifBlank { "Timing…" })
                .setContentText(body)
                .setSmallIcon(android.R.drawable.ic_menu_recent_history)
                .setOngoing(true)
                .setOnlyAlertOnce(alertOnce)
                .setUsesChronometer(true)
                .setChronometerCountDown(countDown)
                .setWhen(whenTime)
                .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
                .setVisibility(if (preferences.getBoolean("lockscreen_details", false)) NotificationCompat.VISIBILITY_PUBLIC else NotificationCompat.VISIBILITY_PRIVATE)
                .setContentIntent(openApp)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
                // Android 16 "Live Update": promote to a live status-bar chip + prominent
                // lock-screen / always-on-display / Samsung Now Bar item that updates in
                // real time. Promotable notifications must be colorized. No-op < Android 16.
                .setColorized(true)
                .setColor(0xFF5B9DFF.toInt())
                .setRequestPromotedOngoing(true)
                .setShortCriticalText(displayTitle.take(20).ifBlank { "Timer" })
            if (comment.isNotBlank()) {
                builder.setStyle(NotificationCompat.BigTextStyle().bigText(comment))
            }
            return builder.build()
        }
    }
}

/** Wake at a phase boundary; phase truth is derived from monotonic elapsed time. */
class TimerPhaseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        AppScope.io.launch {
            try {
                TimerController.initialize(context)
                if (intent.getStringExtra(TimerService.EXTRA_SESSION_ID) != TimerController.sessionId(context)) return@launch
                TimerController.checkpoint(context)
                TimerService.refreshNotification(context, alert = true)
                TimerService.schedulePhase(context)
            } finally { pending.finish() }
        }
    }
}

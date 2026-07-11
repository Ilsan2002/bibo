package com.bibo.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Keeps a running timer / focus session alive with an ongoing notification (live time +
 * Stop action). For Pomodoro focus sessions it also drives the work↔break phase changes.
 * The notification is public so it shows in full on the lock screen — including the
 * mentor's start-of-session cheer once it arrives.
 */
class TimerService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastPhase: String? = null

    private val tick = object : Runnable {
        override fun run() {
            val ctx = applicationContext
            if (!TimerController.isRunning(ctx)) {
                stopSelf()
                return
            }
            if (TimerController.isPomodoro(ctx)) {
                val phaseEnd = TimerController.phaseEnd(ctx)
                if (phaseEnd in 1..System.currentTimeMillis()) {
                    val newPhase = TimerController.advancePhase(ctx)
                    refresh(alert = true, phaseLabel = newPhase)
                } else if (TimerController.phase(ctx) != lastPhase) {
                    refresh(alert = false, phaseLabel = TimerController.phase(ctx))
                }
                handler.postDelayed(this, 1_000L)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            TimerController.stopTimer(applicationContext)
            stopSelf()
            return START_NOT_STICKY
        }
        val start = TimerController.runningStart(applicationContext)
        if (start <= 0L) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat(buildTimerNotification(applicationContext, alert = false))
        lastPhase = if (TimerController.isPomodoro(applicationContext)) TimerController.phase(applicationContext) else null
        handler.removeCallbacks(tick)
        if (TimerController.isPomodoro(applicationContext)) handler.postDelayed(tick, 1_000L)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(tick)
    }

    private fun refresh(alert: Boolean, phaseLabel: String) {
        lastPhase = phaseLabel
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildTimerNotification(applicationContext, alert))
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    companion object {
        // New id (the old "bibo_timer" was created at LOW importance, which One UI hides
        // from the lock screen; a channel's importance can't be raised after creation).
        private const val CHANNEL_ID = "bibo_focus_timer"
        private const val NOTIF_ID = 42
        const val ACTION_STOP = "com.bibo.action.STOP_TIMER"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, TimerService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TimerService::class.java))
        }

        /**
         * Re-post the ongoing notification with the latest state — used when the mentor's
         * start comment arrives after the session began. Updating an existing notification
         * by id is safe from any thread and doesn't touch the service lifecycle.
         */
        fun refreshNotification(context: Context) {
            if (!TimerController.isRunning(context)) return
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID, buildTimerNotification(context.applicationContext, alert = false))
        }

        /** Build the current ongoing notification purely from [TimerController] state. */
        private fun buildTimerNotification(context: Context, alert: Boolean): Notification {
            val title = TimerController.runningTitle(context)
            val start = TimerController.runningStart(context)
            val pomodoro = TimerController.isPomodoro(context)

            val displayTitle: String
            val fallbackText: String
            val whenTime: Long
            val countDown: Boolean
            val alertOnce: Boolean
            if (pomodoro) {
                val work = TimerController.phase(context) == TimerController.PHASE_WORK
                displayTitle = (if (work) "Focus · " else "Break · ") + title
                fallbackText = if (alert) (if (work) "Back to work" else "Break time 🎉") else "Tap to open Bibo"
                whenTime = TimerController.phaseEnd(context)
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
            val comment = if (alert) "" else TimerController.comment(context)
            val body = comment.ifBlank { fallbackText }

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
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
                            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                        }
                )
                runCatching { manager.deleteNotificationChannel("bibo_timer") } // retire the old LOW one
            }

            val openApp = PendingIntent.getActivity(
                context, 0,
                context.packageManager.getLaunchIntentForPackage(context.packageName)
                    ?.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val stopIntent = PendingIntent.getService(
                context, 1,
                Intent(context, TimerService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE,
            )

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(displayTitle.ifBlank { "Timing…" })
                .setContentText(body)
                .setSmallIcon(android.R.drawable.ic_menu_recent_history)
                .setOngoing(true)
                .setOnlyAlertOnce(alertOnce)
                .setUsesChronometer(true)
                .setChronometerCountDown(countDown)
                .setWhen(whenTime)
                .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
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

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
        startForegroundCompat(currentNotification(alert = false))
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
            .notify(NOTIF_ID, currentNotification(alert))
    }

    private fun currentNotification(alert: Boolean): Notification {
        val ctx = applicationContext
        val title = TimerController.runningTitle(ctx)
        val start = TimerController.runningStart(ctx)
        return if (TimerController.isPomodoro(ctx)) {
            val work = TimerController.phase(ctx) == TimerController.PHASE_WORK
            buildNotification(
                title = (if (work) "Focus · " else "Break · ") + title,
                text = if (alert) (if (work) "Back to work" else "Break time 🎉") else "Tap to open Bibo",
                whenTime = TimerController.phaseEnd(ctx),
                countDown = true,
                alertOnce = !alert,
            )
        } else {
            buildNotification(title = title, text = "Tap to open Bibo", whenTime = start, countDown = false, alertOnce = true)
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(
        title: String,
        text: String,
        whenTime: Long,
        countDown: Boolean,
        alertOnce: Boolean,
    ): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            manager.getNotificationChannel(CHANNEL_ID) == null
        ) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "Focus & timer", NotificationManager.IMPORTANCE_LOW
                ).apply { setShowBadge(false) }
            )
        }

        val openApp = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, TimerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title.ifBlank { "Timing…" })
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setOngoing(true)
            .setOnlyAlertOnce(alertOnce)
            .setUsesChronometer(true)
            .setChronometerCountDown(countDown)
            .setWhen(whenTime)
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "bibo_timer"
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
    }
}

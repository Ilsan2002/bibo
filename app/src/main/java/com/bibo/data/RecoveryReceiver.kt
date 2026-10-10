package com.bibo.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Only protected system broadcasts enter here; internal alarm receivers stay private. */
class RecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)) return
        val pending = goAsync()
        val app = context.applicationContext
        AppScope.io.launch {
            try {
                withTimeout(8_000) {
                    TimerController.initialize(app)
                    TaskReminders.rescheduleAllAwait(app)
                    GoalReminders.schedule(app)
                    MentorCheckin.schedule(app)
                    // Boot closes interrupted work to its last checkpoint; no background service start.
                }
            } catch (e: Exception) {
                Log.w("BiboRecovery", "Could not reconcile scheduled local work", e)
            } finally { pending.finish() }
        }
    }
}

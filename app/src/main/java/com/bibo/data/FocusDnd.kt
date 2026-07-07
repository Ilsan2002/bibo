package com.bibo.data

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings

/** Silences the phone (Do Not Disturb) for the length of a focus session. */
object FocusDnd {
    fun hasAccess(context: Context): Boolean =
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .isNotificationPolicyAccessGranted

    fun openSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun enable(context: Context) {
        if (!hasAccess(context)) return
        runCatching {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        }
    }

    fun disable(context: Context) {
        if (!hasAccess(context)) return
        runCatching {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        }
    }
}

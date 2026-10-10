package com.bibo.data

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/** Own only Bibo's DND change; preserve other rules and the prior phone state. */
object FocusDnd {
    private fun manager(context: Context) = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private fun prefs(context: Context) = context.getSharedPreferences("bibo_dnd_owner", Context.MODE_PRIVATE)
    fun hasAccess(context: Context): Boolean = manager(context).isNotificationPolicyAccessGranted
    fun openSettings(context: Context) { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    fun isApplied(context: Context, sessionId: String): Boolean = hasAccess(context) && prefs(context).getString("session", null) == sessionId && prefs(context).getBoolean("applied", false)

    fun reconcile(context: Context, session: WorkSession) {
        if (!session.dnd) { disable(context); return }
        check(hasAccess(context)) { "Do Not Disturb access was removed. Session is running without phone silencing." }
        val p = prefs(context)
        if (p.getString("session", null) == session.id && p.getBoolean("applied", false)) return
        val previous = if (p.getString("session", null) == null) manager(context).currentInterruptionFilter
            else p.getInt("previous", NotificationManager.INTERRUPTION_FILTER_ALL)
        // Persist ownership before touching phone state so a process death cannot
        // strand an unowned DND change that recovery is unable to restore.
        check(p.edit().putInt("previous", previous).putString("session", session.id).putBoolean("applied", true).commit()) { "Could not save DND recovery state." }
        manager(context).setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
    }

    fun disable(context: Context) {
        val p = prefs(context)
        if (!p.getBoolean("applied", false)) return
        if (!hasAccess(context)) { p.edit().clear().apply(); return }
        val nm = manager(context)
        // Target 35+: setInterruptionFilter controls the app's implicit rule, leaving
        // other active rules intact. Older Android mutates global state: restore only
        // if it still matches the filter Bibo applied.
        if (Build.VERSION.SDK_INT >= 35) nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        else if (nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_PRIORITY) {
            nm.setInterruptionFilter(p.getInt("previous", NotificationManager.INTERRUPTION_FILTER_ALL))
        }
        p.edit().clear().apply()
    }
}

package com.bibo.data

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.bibo.widget.BiboWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Single source of truth for the running activity timer, shared by the Timer screen,
 * the Quick Settings tile, and the widget. State lives in SharedPreferences; a stopped
 * session is written to Room as an ActivityBlock.
 */
object TimerController {
    const val PREFS = "bibo_timer"
    const val KEY_START = "running_start"
    const val KEY_TITLE = "running_title"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun runningStart(context: Context): Long = prefs(context).getLong(KEY_START, 0L)

    fun runningTitle(context: Context): String =
        prefs(context).getString(KEY_TITLE, "") ?: ""

    fun isRunning(context: Context): Boolean = runningStart(context) > 0L

    fun start(context: Context, title: String) {
        prefs(context).edit()
            .putLong(KEY_START, System.currentTimeMillis())
            .putString(KEY_TITLE, title)
            .apply()
    }

    /** Stops the timer and returns the block to persist, or null if nothing/too short. */
    fun stopAndBuild(context: Context): ActivityBlock? {
        val start = runningStart(context)
        val title = runningTitle(context).ifBlank { "Untitled activity" }
        prefs(context).edit().remove(KEY_START).remove(KEY_TITLE).apply()
        if (start <= 0L) return null
        val end = System.currentTimeMillis()
        if (end - start < 10_000L) return null
        return ActivityBlock(title = title, startMillis = start, endMillis = end, source = "TIMER")
    }

    /** Start the timer and its ongoing notification. */
    fun startTimer(context: Context, title: String) {
        start(context, title)
        TimerService.start(context)
    }

    /** Stop the timer, persist the session, refresh the widget, and drop the notification. */
    fun stopTimer(context: Context) {
        val block = stopAndBuild(context)
        TimerService.stop(context)
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            if (block != null) BiboDb.get(appContext).activityBlocks().insert(block)
            runCatching { BiboWidget().updateAll(appContext) }
        }
    }
}

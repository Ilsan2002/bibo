package com.bibo.data

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.bibo.widget.BiboWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Everything a focus session carries beyond a plain timer. */
data class FocusConfig(
    val intention: String,
    val goalId: Long? = null,
    val blockedApps: Set<String> = emptySet(),
    val pomodoro: Boolean = false,
    val workMin: Int = 25,
    val breakMin: Int = 5,
    val dnd: Boolean = false,
)

/**
 * Source of truth for the running timer / focus session, shared by the Focus screen, the
 * accessibility blocker, the Quick Settings tile, and the widget. State lives in
 * SharedPreferences; a finished session is written to Room as an ActivityBlock.
 */
object TimerController {
    const val PREFS = "bibo_timer"
    const val KEY_START = "running_start"
    const val KEY_TITLE = "running_title"
    private const val KEY_FOCUS = "is_focus"
    private const val KEY_GOAL = "goal_id"
    private const val KEY_BLOCKED = "blocked_apps"
    private const val KEY_DND = "dnd"
    private const val KEY_POMO = "pomodoro"
    private const val KEY_WORK = "work_min"
    private const val KEY_BREAK = "break_min"
    private const val KEY_PHASE = "phase" // WORK / BREAK
    private const val KEY_PHASE_END = "phase_end"
    private const val KEY_POMOS = "pomos_done"

    const val PHASE_WORK = "WORK"
    const val PHASE_BREAK = "BREAK"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun runningStart(context: Context): Long = prefs(context).getLong(KEY_START, 0L)
    fun runningTitle(context: Context): String = prefs(context).getString(KEY_TITLE, "") ?: ""
    fun isRunning(context: Context): Boolean = runningStart(context) > 0L
    fun isFocus(context: Context): Boolean = prefs(context).getBoolean(KEY_FOCUS, false)
    fun goalId(context: Context): Long? = prefs(context).getLong(KEY_GOAL, -1L).takeIf { it > 0 }

    fun blockedApps(context: Context): Set<String> =
        prefs(context).getString(KEY_BLOCKED, "").orEmpty()
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    fun isPomodoro(context: Context): Boolean = prefs(context).getBoolean(KEY_POMO, false)
    fun workMin(context: Context): Int = prefs(context).getInt(KEY_WORK, 25)
    fun breakMin(context: Context): Int = prefs(context).getInt(KEY_BREAK, 5)
    fun phase(context: Context): String = prefs(context).getString(KEY_PHASE, PHASE_WORK) ?: PHASE_WORK
    fun phaseEnd(context: Context): Long = prefs(context).getLong(KEY_PHASE_END, 0L)
    fun pomodorosDone(context: Context): Int = prefs(context).getInt(KEY_POMOS, 0)

    // ---- lifecycle -----------------------------------------------------------

    /** Start a plain quick timer (Quick Settings tile / widget). */
    fun startTimer(context: Context, title: String) {
        prefs(context).edit().clear()
            .putLong(KEY_START, System.currentTimeMillis())
            .putString(KEY_TITLE, title)
            .putBoolean(KEY_FOCUS, false)
            .apply()
        TimerService.start(context)
    }

    /** Start a full focus session with its blocking, DND, and Pomodoro settings. */
    fun startFocus(context: Context, config: FocusConfig) {
        val now = System.currentTimeMillis()
        prefs(context).edit().clear()
            .putLong(KEY_START, now)
            .putString(KEY_TITLE, config.intention.ifBlank { "Focus" })
            .putBoolean(KEY_FOCUS, true)
            .putLong(KEY_GOAL, config.goalId ?: -1L)
            .putString(KEY_BLOCKED, config.blockedApps.joinToString(","))
            .putBoolean(KEY_DND, config.dnd)
            .putBoolean(KEY_POMO, config.pomodoro)
            .putInt(KEY_WORK, config.workMin)
            .putInt(KEY_BREAK, config.breakMin)
            .putString(KEY_PHASE, PHASE_WORK)
            .putLong(KEY_PHASE_END, if (config.pomodoro) now + config.workMin * 60_000L else 0L)
            .putInt(KEY_POMOS, 0)
            .apply()
        if (config.dnd) FocusDnd.enable(context)
        TimerService.start(context)
    }

    /** Pomodoro phase transition, driven by the service when a phase's time is up. */
    fun advancePhase(context: Context): String {
        val p = prefs(context)
        val now = System.currentTimeMillis()
        return if (phase(context) == PHASE_WORK) {
            p.edit()
                .putString(KEY_PHASE, PHASE_BREAK)
                .putLong(KEY_PHASE_END, now + breakMin(context) * 60_000L)
                .putInt(KEY_POMOS, pomodorosDone(context) + 1)
                .apply()
            PHASE_BREAK
        } else {
            p.edit()
                .putString(KEY_PHASE, PHASE_WORK)
                .putLong(KEY_PHASE_END, now + workMin(context) * 60_000L)
                .apply()
            PHASE_WORK
        }
    }

    /** Stop the running session, optionally with a reflection, and persist it. */
    fun stopTimer(context: Context, reflection: String? = null) {
        val p = prefs(context)
        val start = runningStart(context)
        val title = runningTitle(context).ifBlank { "Untitled activity" }
        val focus = isFocus(context)
        val goal = goalId(context)
        val hadDnd = p.getBoolean(KEY_DND, false)
        p.edit().clear().apply()

        if (hadDnd) FocusDnd.disable(context)
        TimerService.stop(context)

        val appContext = context.applicationContext
        val end = System.currentTimeMillis()
        val block = if (start > 0L && end - start >= 10_000L) {
            ActivityBlock(
                title = title,
                startMillis = start,
                endMillis = end,
                source = if (focus) "FOCUS" else "TIMER",
                note = reflection?.trim()?.ifBlank { null },
                goalId = if (focus) goal else null,
            )
        } else {
            null
        }
        CoroutineScope(Dispatchers.IO).launch {
            if (block != null) BiboDb.get(appContext).activityBlocks().insert(block)
            runCatching { BiboWidget().updateAll(appContext) }
        }
    }
}

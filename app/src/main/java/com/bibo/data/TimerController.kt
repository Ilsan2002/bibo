package com.bibo.data

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.glance.appwidget.updateAll
import androidx.room.withTransaction
import com.bibo.widget.BiboWidget
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class FocusConfig(
    val intention: String,
    val goalId: Long? = null,
    val blockedApps: Set<String> = emptySet(),
    val pomodoro: Boolean = false,
    val workMin: Int = 25,
    val breakMin: Int = 5,
    val dnd: Boolean = false,
    val taskId: Long? = null,
)

/** Room owns sessions and history; all surfaces submit identity-scoped commands. */
object TimerController {
    const val PREFS = "bibo_timer"
    const val KEY_START = "running_start"
    const val KEY_TITLE = "running_title"
    const val PHASE_WORK = "WORK"
    const val PHASE_BREAK = "BREAK"
    private val commands = Mutex()
    private val initialization = Mutex()
    private val effects = Mutex()
    @Volatile private var initialized = false
    private var observer: Job? = null
    private val mutableState = MutableStateFlow<WorkSession?>(null)
    val state: StateFlow<WorkSession?> = mutableState
    private val mutableError = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = mutableError

    private fun defaults(context: Context) = context.getSharedPreferences("bibo_focus_defaults", Context.MODE_PRIVATE)
    fun lastBlockedApps(context: Context): Set<String> = defaults(context).getStringSet("last_blocked_apps", emptySet())?.toSet().orEmpty()
    fun sessionId(context: Context): String? = state.value?.id
    fun runningStart(context: Context): Long = state.value?.startMillis ?: 0
    fun runningTitle(context: Context): String = state.value?.title.orEmpty()
    fun isRunning(context: Context): Boolean = state.value != null
    fun isFocus(context: Context): Boolean = state.value?.kind == "FOCUS"
    fun goalId(context: Context): Long? = state.value?.goalId
    fun linkedTaskId(context: Context): Long? = state.value?.taskId
    fun comment(context: Context): String = state.value?.comment.orEmpty()
    fun blockedApps(context: Context): Set<String> = state.value?.blockedPackages.orEmpty().split(',').filter { it.isNotBlank() }.toSet()
    fun isPomodoro(context: Context): Boolean = state.value?.pomodoro == true
    fun workMin(context: Context): Int = state.value?.workMin ?: 25
    fun breakMin(context: Context): Int = state.value?.breakMin ?: 5
    fun elapsedMillis(context: Context): Long = state.value?.let(::elapsed) ?: 0
    fun elapsedMillis(session: WorkSession): Long = elapsed(session)
    fun phase(context: Context): String = currentPhase()?.kind ?: PHASE_WORK
    fun phaseEnd(context: Context): Long = currentPhase()?.let { AppTime.nowMillis() + it.remainingMillis } ?: 0
    fun pomodorosDone(context: Context): Int = currentPhase()?.pomodorosDone?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 0
    fun acknowledgeError() { mutableError.value = null }
    private fun elapsed(s: WorkSession): Long = SessionTiming.elapsed(s.elapsedAnchorMillis, s.elapsedAtAnchorMillis, SystemClock.elapsedRealtime())
    private fun currentPhase(): SessionTiming.Phase? = state.value?.let { SessionTiming.phase(elapsed(it), it.workMin, it.breakMin) }
    private fun bootCount(context: Context): Int = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)

    suspend fun initialize(context: Context) {
        initialization.withLock {
            if (initialized) return
            val app = context.applicationContext
            val db = BiboDb.get(app)
            db.withTransaction {
                val legacy = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                if (db.workSessions().active() == null && legacy.getLong(KEY_START, 0) > 0 && db.workSessions().byId("legacy-${legacy.getLong(KEY_START, 0)}") == null) {
                    val start = legacy.getLong(KEY_START, 0)
                    val end = AppTime.nowMillis().coerceAtLeast(start)
                    db.activityBlocks().insert(ActivityBlock(title = legacy.getString(KEY_TITLE, "Recovered activity").orEmpty(), startMillis = start, endMillis = end,
                        source = "LEGACY_TIMER", provenance = "LEGACY", note = "Recovered legacy timer. Review its end time; uninterrupted duration could not be verified."))
                    db.workSessions().insert(WorkSession(id = "legacy-$start", activeSlot = null, status = "INTERRUPTED", title = "Recovered activity", kind = "LEGACY", startMillis = start,
                        elapsedAnchorMillis = 0, elapsedAtAnchorMillis = 0, lastCheckpointMillis = end, bootCount = bootCount(app), endMillis = end))
                    mutableError.value = "Recovered an earlier timer. Review its calendar end time."
                }
                val active = db.workSessions().active()
                val userStopped = if (Build.VERSION.SDK_INT >= 30 && active != null) {
                    val manager = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                    runCatching { manager.getHistoricalProcessExitReasons(app.packageName, 0, 1).firstOrNull()?.let {
                        it.reason == android.app.ApplicationExitInfo.REASON_USER_REQUESTED && it.timestamp >= active.lastCheckpointMillis
                    } == true }.getOrDefault(false)
                } else false
                if (active != null && (active.bootCount != bootCount(app) || SystemClock.elapsedRealtime() < active.elapsedAnchorMillis || userStopped)) {
                    recordEnd(db, active, active.elapsedAtAnchorMillis, null, "INTERRUPTED")
                    mutableError.value = "Previous session was interrupted. Saved its last confirmed work."
                }
                if (db.workSessions().active() == null) {
                    // Preserve contradictory legacy timestamps before clearing their
                    // live flags; their duration is unknown, so create no measured block.
                    for (task in (db.todos().allOnce() + db.todos().deletedOnce()).filter { it.startedAt != null }) {
                        val receiptId = "legacy-task:${task.id}:${task.startedAt}"
                        if (db.workSessions().byId(receiptId) == null) db.workSessions().insert(WorkSession(
                            id = receiptId, activeSlot = null, status = "INTERRUPTED", title = task.title, kind = "LEGACY", taskId = task.id, goalId = task.goalId,
                            startMillis = task.startedAt!!, elapsedAnchorMillis = 0, lastCheckpointMillis = AppTime.nowMillis(), bootCount = bootCount(app),
                            note = "Earlier task timer start preserved. Its elapsed work could not be verified."))
                    }
                    db.workSessions().clearLegacyTaskMarkers()
                }
            }
            mutableState.value = db.workSessions().active()
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
            initialized = true
            observer = AppScope.io.launch { db.workSessions().activeFlow().collect { mutableState.value = it } }
        }
    }

    suspend fun resetForDatabaseReplacement(context: Context) {
        commands.withLock {
            observer?.cancelAndJoin()
            observer = null
            initialized = false
            mutableState.value = null
            withContext(Dispatchers.Main) { FocusDnd.disable(context); TimerService.stop(context) }
        }
    }

    /** Finish confirmed work before the backup gate closes, holding command ordering. */
    suspend fun prepareForBackup(context: Context, beginMaintenance: () -> Unit = {}) {
        initialize(context)
        commands.withLock {
            val db = BiboDb.get(context)
            db.withTransaction { db.workSessions().active()?.let { recordEnd(db, it, elapsed(it), null, "INTERRUPTED") } }
            observer?.cancelAndJoin()
            mutableState.value = null
            observer = null
            initialized = false
            withContext(Dispatchers.Main) { FocusDnd.disable(context); TimerService.stop(context) }
            beginMaintenance()
        }
    }

    fun startTimer(context: Context, title: String) { submit { startTimerAwait(context, title) } }
    fun startTask(context: Context, taskId: Long, title: String, goalId: Long?) { submit { startTaskAwait(context, taskId, title, goalId) } }
    fun startFocus(context: Context, config: FocusConfig) { submit { startFocusAwait(context, config) } }
    fun stopTimer(context: Context, reflection: String? = null) {
        val id = state.value?.id ?: return
        submit { stopSession(context, id, reflection) }
    }
    fun clear(context: Context) = stopTimer(context)
    private fun submit(action: suspend () -> Result<*>) { AppScope.io.launch { action().exceptionOrNull()?.let { mutableError.value = it.message ?: "Session could not be saved. Try again." } } }

    suspend fun startTimerAwait(context: Context, title: String, requireIdle: Boolean = true): Result<String> =
        start(context, title.ifBlank { "Quick timer" }, "TIMER", null, null, null, requireIdle)
    suspend fun startTaskAwait(context: Context, taskId: Long, title: String, goalId: Long?, requireIdle: Boolean = true): Result<String> =
        start(context, title, "TASK", taskId, goalId, null, requireIdle)
    suspend fun startFocusAwait(context: Context, config: FocusConfig, commandId: String? = null, requireIdle: Boolean = true): Result<String> =
        start(context, config.intention.trim().ifBlank { "Focus" }, "FOCUS", config.taskId, config.goalId, config, requireIdle, commandId)

    private suspend fun start(context: Context, title: String, kind: String, taskId: Long?, goalId: Long?, config: FocusConfig?, requireIdle: Boolean, commandId: String? = null): Result<String> = runCatching {
        initialize(context)
        commands.withLock {
            val db = BiboDb.get(context)
            val id = if (commandId == null) UUID.randomUUID().toString() else UUID.nameUUIDFromBytes("bibo-session-command:$commandId".toByteArray(Charsets.UTF_8)).toString()
            if (commandId != null && db.workSessions().byId(id) != null) return@withLock id
            if (!config?.blockedApps.isNullOrEmpty()) check(isWebTrackingEnabled(context) && Capabilities.accessibilityServiceConnected(context)) { "Enable Bibo app blocking in Accessibility settings before starting this preset." }
            if (config?.dnd == true) check(FocusDnd.hasAccess(context)) { "Allow Do Not Disturb access before starting this preset." }
            val wall = AppTime.nowMillis()
            val monotonic = SystemClock.elapsedRealtime()
            db.withTransaction {
                val previous = db.workSessions().active()
                check(!requireIdle || previous == null) { "Another session is running. Stop it before starting this activity." }
                val task = taskId?.let { db.todos().byId(it) }
                if (goalId != null && taskId == null) check(db.goals().allOnce().any { it.id == goalId }) { "This goal is no longer available. Choose another goal." }
                if (taskId != null) check(task != null && task.deletedAt == null && task.completedAt == null && (task.parentId == null || db.todos().byId(task.parentId)?.let { it.completedAt == null && it.deletedAt == null } == true)) { "This task is no longer available to start." }
                previous?.let { recordEnd(db, it, elapsed(it), null) }
                val session = WorkSession(id = id, title = task?.title ?: title, kind = kind, taskId = taskId, goalId = if (task != null) task.goalId else goalId,
                    startMillis = wall, elapsedAnchorMillis = monotonic, lastCheckpointMillis = wall, bootCount = bootCount(context),
                    pomodoro = config?.pomodoro == true, workMin = (config?.workMin ?: 25).coerceIn(1, 180), breakMin = (config?.breakMin ?: 5).coerceIn(1, 60),
                    blockedPackages = config?.blockedApps?.joinToString(",").orEmpty(), dnd = config?.dnd == true)
                db.workSessions().insert(session)
                taskId?.let { db.workSessions().setTaskRunning(it, wall) }
            }
            mutableState.value = db.workSessions().active()
            if (config != null) defaults(context).edit().putStringSet("last_blocked_apps", config.blockedApps).apply()
            try { reconcile(context) } catch (e: Exception) {
                db.withTransaction { db.workSessions().active()?.takeIf { it.id == id }?.let { recordEnd(db, it, elapsed(it), null, "INTERRUPTED") } }
                mutableState.value = db.workSessions().active()
                FocusDnd.disable(context)
                throw IllegalStateException("Session could not start: ${e.message ?: "background startup denied"}", e)
            }
            requestComment(context, id, mutableState.value?.title ?: title, goalId)
            id
        }
    }.onFailure { mutableError.value = it.message }

    suspend fun stopSession(context: Context, sessionId: String, reflection: String? = null): Result<String?> = runCatching {
        initialize(context)
        commands.withLock {
            val db = BiboDb.get(context)
            val stopped = db.withTransaction {
                val session = db.workSessions().active()?.takeIf { it.id == sessionId } ?: return@withTransaction null
                recordEnd(db, session, elapsed(session), reflection)
                session.id
            }
            try { reconcile(context) } catch (e: Exception) { mutableError.value = "Session saved. Phone or widget cleanup needs retry: ${e.message.orEmpty()}" }
            stopped
        }
    }.onFailure { mutableError.value = it.message }

    /** Called inside TaskCommands' Room transaction. No platform side effects or mutex. */
    suspend fun finishSessionInTransaction(context: Context, affectedTaskIds: Set<Long>) {
        val db = BiboDb.get(context)
        db.workSessions().active()?.takeIf { it.taskId in affectedTaskIds }?.let { recordEnd(db, it, elapsed(it), null) }
    }

    private suspend fun recordEnd(db: BiboDb, session: WorkSession, elapsedMillis: Long, note: String?, status: String = "FINISHED") {
        SessionFinalizer.finishInTransaction(db, session.id, elapsedMillis, note, status)
    }

    suspend fun attachReflection(context: Context, sessionId: String, note: String): Result<Unit> = runCatching {
        val db = BiboDb.get(context)
        db.withTransaction {
            val saved = db.workSessions().byId(sessionId)
            check(saved != null && saved.activeSlot == null) { "This session has not ended." }
            val clean = note.trim().takeIf { it.isNotEmpty() }
            db.workSessions().setReflection(sessionId, clean)
            db.sessionSegments().setReflection(sessionId, clean)
        }
    }.onFailure { mutableError.value = it.message }

    suspend fun checkpoint(context: Context) {
        val db = BiboDb.get(context)
        db.withTransaction {
            db.workSessions().active()?.let {
                val updated = it.copy(elapsedAtAnchorMillis = elapsed(it), elapsedAnchorMillis = SystemClock.elapsedRealtime(), lastCheckpointMillis = AppTime.nowMillis())
                db.workSessions().update(updated)
            }
        }
        mutableState.value = db.workSessions().active()
    }

    /** Retryable platform projections. Invoke after the caller's transaction commits. */
    suspend fun reconcile(context: Context) {
        initialize(context)
        effects.withLock {
            mutableState.value = BiboDb.get(context).workSessions().active()
            val session = mutableState.value
            withContext(Dispatchers.Main) {
                if (session == null) { FocusDnd.disable(context); TimerService.stop(context) }
                else { FocusDnd.reconcile(context, session); TimerService.start(context) }
                android.service.quicksettings.TileService.requestListeningState(context, android.content.ComponentName(context, com.bibo.tile.TimerTile::class.java))
            }
            runCatching { BiboWidget().updateAll(context.applicationContext) }
        }
    }

    private fun requestComment(context: Context, sessionId: String, title: String, goalId: Long?) {
        val app = context.applicationContext
        if (!defaults(app).getBoolean("session_comment", false)) return
        AppScope.io.launch {
            val comment = runCatching { com.bibo.ui.Mentor.startComment(app, title, goalId) }.getOrNull()
            if (!comment.isNullOrBlank()) {
                BiboDb.get(app).workSessions().setComment(sessionId, comment)
                mutableState.value = BiboDb.get(app).workSessions().active()
                TimerService.refreshNotification(app)
            }
        }
    }
}

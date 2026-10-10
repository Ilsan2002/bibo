package com.bibo.data

import android.content.Context
import com.bibo.ui.Mentor
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Recover process-owned work on IO before creating any database-backed screens. */
object AppStartup {
    private val started = AtomicBoolean(false)
    private val loaded = MutableStateFlow(false)
    val ready = loaded.asStateFlow()
    private val failure = MutableStateFlow<String?>(null)
    val error = failure.asStateFlow()
    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        failure.value = null
        AppScope.io.launch {
            try {
                TimerController.initialize(context)
                Mentor.recover(context)
                loaded.value = true
                TaskReminders.rescheduleAllAwait(context)
                GoalReminders.schedule(context)
                MentorCheckin.schedule(context)
            } catch (cancelled: CancellationException) {
                if (!loaded.value) started.set(false)
                throw cancelled
            } catch (e: Exception) {
                if (!loaded.value) {
                    started.set(false)
                    failure.value = e.message ?: "Local data could not be opened. Try again or open Recovery."
                } else AppScope.reportError(e.message ?: "Background reminders could not be refreshed.")
            }
        }
    }
}

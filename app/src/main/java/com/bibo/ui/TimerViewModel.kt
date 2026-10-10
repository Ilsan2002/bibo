package com.bibo.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import com.bibo.data.AppScope
import com.bibo.data.FocusConfig
import com.bibo.data.TimerController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** Reflection belongs to an ended session, independently of the current live timer. */
class TimerViewModel(application: Application, private val savedState: SavedStateHandle) : AndroidViewModel(application) {
    val reflectionSessionId = savedState.getStateFlow<String?>("reflection_session", null)
    private val context get() = getApplication<Application>().applicationContext
    private val starting = AtomicBoolean(false)

    init { AppScope.io.launch { TimerController.initialize(context) } }

    fun start(config: FocusConfig) {
        if (!starting.compareAndSet(false, true)) return
        AppScope.io.launch { try { TimerController.startFocusAwait(context, config) } finally { starting.set(false) } }
    }
    fun stopAndReflect(sessionId: String) {
        AppScope.io.launch {
            if (TimerController.stopSession(context, sessionId).getOrNull() != null) withContext(Dispatchers.Main) { reflect(sessionId) }
        }
    }
    fun reflect(sessionId: String) { savedState["reflection_session"] = sessionId }
    fun dismissReflection() { savedState["reflection_session"] = null }
    fun saveReflection(sessionId: String, note: String) {
        AppScope.io.launch {
            if (TimerController.attachReflection(context, sessionId, note).isSuccess) {
                context.getSharedPreferences("bibo_reflection_drafts", Application.MODE_PRIVATE).edit().remove(sessionId).apply()
                withContext(Dispatchers.Main) { if (reflectionSessionId.value == sessionId) dismissReflection() }
            }
        }
    }
}

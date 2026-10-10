package com.bibo.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Application ownership for short commands that survive screen navigation.
 * Process durability comes from Room records and owned background jobs, not this scope.
 *
 * Compose's `rememberCoroutineScope()` is cancelled the moment its composable leaves the
 * composition — switching tabs is enough. Anything durable started there (a database
 * write, a mentor request) is silently killed mid-flight. That cost real user data: a
 * task delete that waited on an Undo snackbar never committed if you switched tabs
 * before the snackbar timed out, so deleted tasks came back.
 *
 * Use this for side effects that own their own lifetime; keep UI state updates on the
 * composition scope where cancellation is the correct behavior.
 */
object AppScope {
    private val failures = MutableStateFlow<String?>(null)
    val error = failures.asStateFlow()
    private val handler = CoroutineExceptionHandler { _, error ->
        android.util.Log.e("BiboCommand", "Application operation failed", error)
        failures.value = error.message ?: "The operation could not finish. Please try again."
    }
    val io = CoroutineScope(SupervisorJob() + Dispatchers.IO + handler)
    fun reportError(message: String) { failures.value = message }
    fun clearError() { failures.value = null }
}

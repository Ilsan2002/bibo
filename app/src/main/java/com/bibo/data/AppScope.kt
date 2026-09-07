package com.bibo.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * A scope for work that must finish even if the screen that started it goes away.
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
    val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

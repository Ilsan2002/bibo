package com.bibo.ui

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.time.LocalDate

/** Every device gets the same bounded local draft; unsupported foods have manual entry. */
class HealthParser(@Suppress("UNUSED_PARAMETER") context: Context) {
    suspend fun parse(text: String, referenceDate: LocalDate = com.bibo.data.AppTime.today()): HealthLog = withTimeout(2_000) {
        withContext(Dispatchers.Default) { parseHealthDraft(text, referenceDate) }
    }
}

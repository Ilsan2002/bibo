package com.bibo.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import androidx.room.withTransaction

data class WebsiteTotal(val domain: String, val totalMillis: Long, val color: Int)

/** History remains readable independently of current recording consent or permission. */
class WebsiteRepo(context: android.content.Context) {
    private val context = context.applicationContext
    private val db get() = BiboDb.get(context)
    private val palette = intArrayOf(0xFF1E88E5.toInt(), 0xFF43A047.toInt(), 0xFFE53935.toInt(), 0xFFFB8C00.toInt(),
        0xFF8E24AA.toInt(), 0xFF00897B.toInt(), 0xFF6D4C41.toInt(), 0xFF3949AB.toInt())
    fun colorFor(domain: String): Int = palette[Math.floorMod(domain.hashCode(), palette.size)]
    private fun aggregate(rows: List<WebsiteSession>, start: Long, end: Long): List<WebsiteTotal> = rows.groupBy { it.domain }.mapNotNull { (domain, sessions) ->
        val millis = UsageAggregation.unionMillis(sessions.map { UsageInterval(it.startMillis, it.endMillis) }, start, end)
        if (millis <= 0L) null else WebsiteTotal(domain, millis, colorFor(domain))
    }.sortedByDescending { it.totalMillis }
    fun observeTotals(start: Long, end: Long): Flow<List<WebsiteTotal>> = db.websites().sessionsInFlow(start, end).map { aggregate(it, start, end) }
    suspend fun totals(start: Long, end: Long): List<WebsiteTotal> = aggregate(db.websites().sessionsIn(start, end), start, end)
    suspend fun clearHistory() {
        WebTrackingService.resetRecordingHistory(context)
        db.withTransaction { db.websites().clear() }
    }
}

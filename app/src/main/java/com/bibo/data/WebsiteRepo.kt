package com.bibo.data

data class WebsiteTotal(val domain: String, val totalMillis: Long, val color: Int)

/** Per-domain browsing time for the stats page. */
class WebsiteRepo(private val context: android.content.Context) {

    private val db by lazy { BiboDb.get(context) }

    private val palette = intArrayOf(
        0xFF1E88E5.toInt(), 0xFF43A047.toInt(), 0xFFE53935.toInt(), 0xFFFB8C00.toInt(),
        0xFF8E24AA.toInt(), 0xFF00897B.toInt(), 0xFF6D4C41.toInt(), 0xFF3949AB.toInt(),
    )

    fun colorFor(domain: String): Int =
        palette[Math.floorMod(domain.hashCode(), palette.size)]

    suspend fun totals(start: Long, end: Long): List<WebsiteTotal> =
        db.websites().sessionsIn(start, end)
            .groupBy { it.domain }
            .map { (domain, sessions) ->
                val total = sessions.sumOf {
                    minOf(it.endMillis, end) - maxOf(it.startMillis, start)
                }
                WebsiteTotal(domain, total, colorFor(domain))
            }
            .filter { it.totalMillis >= 5_000L }
            .sortedByDescending { it.totalMillis }
}

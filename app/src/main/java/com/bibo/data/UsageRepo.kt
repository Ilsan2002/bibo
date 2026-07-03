package com.bibo.data

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings

data class UsageSession(
    val packageName: String,
    val label: String,
    val start: Long,
    val end: Long,
    val color: Int,
)

data class AppUsageTotal(
    val packageName: String,
    val label: String,
    val totalMillis: Long,
    val color: Int,
)

/**
 * Reads per-app foreground sessions from UsageStatsManager and caches them into Room.
 *
 * The system prunes detailed usage events after a few days, so every read first
 * ingests the last 48h of correctly-reconstructed sessions into Room, then serves
 * the requested window from Room — giving both fresh data and durable history.
 */
class UsageRepo(private val context: Context) {

    private val db by lazy { BiboDb.get(context) }

    private val palette = intArrayOf(
        0xFFE53935.toInt(), 0xFFD81B60.toInt(), 0xFF8E24AA.toInt(), 0xFF5E35B1.toInt(),
        0xFF3949AB.toInt(), 0xFF1E88E5.toInt(), 0xFF039BE5.toInt(), 0xFF00ACC1.toInt(),
        0xFF00897B.toInt(), 0xFF43A047.toInt(), 0xFF7CB342.toInt(), 0xFFC0CA33.toInt(),
        0xFFFFB300.toInt(), 0xFFFB8C00.toInt(), 0xFFF4511E.toInt(), 0xFF6D4C41.toInt(),
    )

    private val excludedPackages = setOf(
        context.packageName,
        "com.sec.android.app.launcher",
        "com.google.android.apps.nexuslauncher",
        "com.android.systemui",
        "com.samsung.android.app.aodservice",
    )

    private val labelCache = HashMap<String, String?>()

    fun hasPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun openUsageAccessSettings() {
        context.startActivity(
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun colorFor(packageName: String): Int =
        palette[Math.floorMod(packageName.hashCode(), palette.size)]

    /** Launchable-app label, or null if the package shouldn't be shown. */
    private fun labelFor(packageName: String): String? =
        labelCache.getOrPut(packageName) {
            if (packageName in excludedPackages) return@getOrPut null
            val pm = context.packageManager
            if (pm.getLaunchIntentForPackage(packageName) == null) return@getOrPut null
            runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
            }.getOrNull()
        }

    /**
     * Per-app foreground intervals within [start, end), reconstructed with the
     * correctness fixes available through the public API: sessions end on the first
     * of PAUSED/STOPPED, and every open session is hard-closed when the screen turns
     * off / keyguard shows / the device shuts down (otherwise a lost close event
     * leaves a multi-hour ghost session). Keyed by package — the per-activity
     * instanceId that would separate split-screen instances is a hidden API.
     */
    private fun rawSessions(start: Long, end: Long): List<Triple<String, Long, Long>> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = usm.queryEvents(start, end)
        val open = HashMap<String, Long>() // packageName -> start ts
        val raw = mutableListOf<Triple<String, Long, Long>>()
        val event = UsageEvents.Event()

        fun closeAll(ts: Long) {
            if (open.isEmpty()) return
            open.forEach { (pkg, s) -> if (ts > s) raw.add(Triple(pkg, s, ts)) }
            open.clear()
        }

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED ->
                    open.putIfAbsent(event.packageName, event.timeStamp)
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED -> {
                    val s = open.remove(event.packageName) ?: continue
                    if (event.timeStamp > s) raw.add(Triple(event.packageName, s, event.timeStamp))
                }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE,
                UsageEvents.Event.KEYGUARD_SHOWN,
                UsageEvents.Event.DEVICE_SHUTDOWN ->
                    closeAll(event.timeStamp)
            }
        }
        // Close whatever is still foreground at query end (the current app), with a
        // generous backstop against a genuinely-lost close event.
        val closeAt = minOf(end, System.currentTimeMillis())
        val maxOpen = 6 * 3600_000L
        open.forEach { (pkg, s) ->
            val e = minOf(closeAt, s + maxOpen)
            if (e > s) raw.add(Triple(pkg, s, e))
        }
        return raw
    }

    /** Per-app intervals: raw per-instance sessions collapsed per package so
     *  split-screen overlaps and rapid re-focus don't double-count. */
    private fun mergedPerApp(start: Long, end: Long, mergeGapMillis: Long = 30_000L):
        List<UsageSessionEntity> {
        val byApp = rawSessions(start, end).groupBy { it.first }
        val out = mutableListOf<UsageSessionEntity>()
        for ((pkg, sessions) in byApp) {
            val label = labelFor(pkg) ?: continue
            val sorted = sessions.map { it.second to it.third }.sortedBy { it.first }
            var curStart = sorted.first().first
            var curEnd = sorted.first().second
            for (i in 1 until sorted.size) {
                val (s, e) = sorted[i]
                if (s - curEnd <= mergeGapMillis) {
                    if (e > curEnd) curEnd = e
                } else {
                    out.add(UsageSessionEntity(packageName = pkg, label = label, startMillis = curStart, endMillis = curEnd))
                    curStart = s; curEnd = e
                }
            }
            out.add(UsageSessionEntity(packageName = pkg, label = label, startMillis = curStart, endMillis = curEnd))
        }
        return out
    }

    /** Recompute the last 48h of sessions and refresh them in Room (throttled). */
    private suspend fun ingest() {
        val now = System.currentTimeMillis()
        synchronized(throttleLock) {
            if (now - lastIngest < INGEST_THROTTLE_MS) return
            lastIngest = now
        }
        val windowStart = now - 48 * 3600_000L
        val merged = mergedPerApp(windowStart, now)
        db.usage().pruneBefore(now - RETENTION_MS)
        // Replace the recomputed window wholesale so stale fragments don't linger.
        db.usage().deleteFrom(windowStart)
        if (merged.isNotEmpty()) db.usage().upsertAll(merged)
    }

    /**
     * Foreground sessions for the calendar timeline: merged same-app sessions with
     * small gaps, short ones dropped so the timeline isn't cluttered.
     */
    suspend fun querySessions(
        start: Long,
        end: Long,
        minDurationMillis: Long = 3 * 60_000L,
    ): List<UsageSession> {
        if (!hasPermission()) return emptyList()
        ingest()
        return db.usage().sessionsIn(start, end).mapNotNull { e ->
            val s = maxOf(e.startMillis, start)
            val en = minOf(e.endMillis, end)
            if (en - s < minDurationMillis) return@mapNotNull null
            UsageSession(e.packageName, e.label, s, en, colorFor(e.packageName))
        }
    }

    /** Total foreground millis for each of [days] (for the weekly trend chart). */
    suspend fun dailyTotals(days: List<java.time.LocalDate>, zone: java.time.ZoneId): List<Long> {
        if (days.isEmpty() || !hasPermission()) return List(days.size) { 0L }
        ingest()
        val rangeStart = days.first().atStartOfDay(zone).toInstant().toEpochMilli()
        val rangeEnd = days.last().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val sessions = db.usage().sessionsIn(rangeStart, rangeEnd)
        return days.map { day ->
            val ds = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val de = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            sessions.sumOf { s ->
                val a = maxOf(s.startMillis, ds)
                val b = minOf(s.endMillis, de)
                if (b > a) b - a else 0L
            }
        }
    }

    /** [days.size][24] matrix of foreground millis per (day, hour) for the heatmap. */
    suspend fun hourlyMatrix(
        days: List<java.time.LocalDate>,
        zone: java.time.ZoneId,
    ): Array<LongArray> {
        val matrix = Array(days.size) { LongArray(24) }
        if (days.isEmpty() || !hasPermission()) return matrix
        ingest()
        val rangeStart = days.first().atStartOfDay(zone).toInstant().toEpochMilli()
        val rangeEnd = days.last().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val sessions = db.usage().sessionsIn(rangeStart, rangeEnd)
        for ((dayIdx, day) in days.withIndex()) {
            for (hour in 0 until 24) {
                val hs = day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
                val he = hs + 3600_000L
                matrix[dayIdx][hour] = sessions.sumOf { s ->
                    val a = maxOf(s.startMillis, hs)
                    val b = minOf(s.endMillis, he)
                    if (b > a) b - a else 0L
                }
            }
        }
        return matrix
    }

    /** Per-app total foreground time in [start, end), for the stats page. */
    suspend fun queryTotals(start: Long, end: Long): List<AppUsageTotal> {
        if (!hasPermission()) return emptyList()
        ingest()
        return db.usage().sessionsIn(start, end)
            .groupBy { it.packageName }
            .mapNotNull { (pkg, sessions) ->
                val total = sessions.sumOf {
                    minOf(it.endMillis, end) - maxOf(it.startMillis, start)
                }
                if (total < 60_000L) return@mapNotNull null
                AppUsageTotal(pkg, sessions.first().label, total, colorFor(pkg))
            }
            .sortedByDescending { it.totalMillis }
    }

    companion object {
        private const val INGEST_THROTTLE_MS = 30_000L
        private const val RETENTION_MS = 400L * 24 * 3600_000L
        private val throttleLock = Any()
        @Volatile private var lastIngest = 0L
    }
}

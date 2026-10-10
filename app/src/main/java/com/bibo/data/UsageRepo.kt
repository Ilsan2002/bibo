package com.bibo.data

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import androidx.room.withTransaction
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class UsageSession(val packageName: String, val label: String, val start: Long, val end: Long, val color: Int, val id: Long = 0)
data class AppUsageTotal(val packageName: String, val label: String, val totalMillis: Long, val color: Int)
data class UsageRefreshResult(val collectedAt: Long?, val error: String? = null, val permitted: Boolean = true)
data class UsageSnapshot(
    val days: List<LocalDate>, val totals: List<AppUsageTotal>, val selectedDayMillis: Long,
    val dailyMillis: List<Long>, val hourly: Array<LongArray>, val coverage: List<UsageCoverageState>, val collectedAt: Long?,
    val zone: ZoneId,
)

/** One coalesced collector; readers remain available when collection is disabled. */
class UsageRepo(context: Context) {
    private val context = context.applicationContext
    private val db get() = BiboDb.get(context)
    private val palette = intArrayOf(
        0xFFE53935.toInt(), 0xFFD81B60.toInt(), 0xFF8E24AA.toInt(), 0xFF5E35B1.toInt(),
        0xFF3949AB.toInt(), 0xFF1E88E5.toInt(), 0xFF039BE5.toInt(), 0xFF00ACC1.toInt(),
        0xFF00897B.toInt(), 0xFF43A047.toInt(), 0xFF7CB342.toInt(), 0xFFC0CA33.toInt(),
        0xFFFFB300.toInt(), 0xFFFB8C00.toInt(), 0xFFF4511E.toInt(), 0xFF6D4C41.toInt(),
    )
    private val excludedPackages = setOf(context.packageName, "com.sec.android.app.launcher",
        "com.google.android.apps.nexuslauncher", "com.android.systemui", "com.samsung.android.app.aodservice")
    private val labelCache = HashMap<String, String?>()
    fun hasPermission(): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }
    fun openUsageAccessSettings() { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    fun colorFor(packageName: String): Int = palette[Math.floorMod(packageName.hashCode(), palette.size)]
    private fun labelFor(packageName: String): String? = labelCache.getOrPut(packageName) {
        if (packageName in excludedPackages) return@getOrPut null
        runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0)).toString() }.getOrDefault(packageName)
    }
    private data class Collection(val firstEvent: Long?, val sessions: List<UsageSessionEntity>)
    private fun collect(start: Long, end: Long): Collection {
        val events = (context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager).queryEvents(start, end)
        val open = mutableMapOf<Pair<String, String>, Long>()
        val raw = mutableListOf<UsageSessionEntity>()
        val event = UsageEvents.Event()
        var first: Long? = null
        fun finish(key: Pair<String, String>, at: Long) {
            val began = open.remove(key) ?: return
            val label = labelFor(key.first) ?: return
            if (at > began) raw += UsageSessionEntity(packageName = key.first, label = label, startMillis = began, endMillis = at)
        }
        fun finishAll(at: Long) { open.keys.toList().forEach { finish(it, at) } }
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (first == null) first = event.timeStamp
            val pkg = event.packageName.orEmpty()
            val key = pkg to event.className.orEmpty()
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> if (labelFor(pkg) != null) open.putIfAbsent(key, event.timeStamp)
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> finish(key, event.timeStamp)
                UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.KEYGUARD_SHOWN, UsageEvents.Event.DEVICE_SHUTDOWN -> finishAll(event.timeStamp)
            }
        }
        finishAll(end)
        val accurate = raw.groupBy { it.packageName }.flatMap { (pkg, sessions) ->
            UsageAggregation.union(sessions.map { UsageInterval(it.startMillis, it.endMillis) }, start, end).map {
                UsageSessionEntity(packageName = pkg, label = sessions.first().label, startMillis = it.start, endMillis = it.end)
            }
        }
        return Collection(first, accurate)
    }
    suspend fun refresh(force: Boolean = false): UsageRefreshResult {
        if (!hasPermission()) return UsageRefreshResult(lastSuccessfulAt.takeIf { it > 0 }, permitted = false)
        val job = refreshLock.withLock {
            inFlight?.takeIf { it.isActive } ?: AppScope.io.async {
                val now = AppTime.nowMillis()
                if (!force && now >= lastSuccessfulAt && now - lastSuccessfulAt < THROTTLE_MS) UsageRefreshResult(lastSuccessfulAt)
                else {
                    try { ingest(now); lastSuccessfulAt = now; UsageRefreshResult(now) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { UsageRefreshResult(lastSuccessfulAt.takeIf { it > 0 }, error = error.message ?: "Usage refresh failed") }
                }
            }.also { inFlight = it }
        }
        return job.await()
    }
    private suspend fun ingest(now: Long) {
        // Backfill available retained events; absence of events is not proof of historical zero use.
        val queryFrom = now - BACKFILL_MS
        val collected = collect(queryFrom, now)
        persistUsageCollection(db, collected.firstEvent, now, collected.sessions, RETENTION_MS)
    }

    fun observeSnapshot(days: List<LocalDate>, zone: ZoneId): Flow<UsageSnapshot> {
        require(days.isNotEmpty())
        return db.invalidationTracker.createFlow("usage_sessions", "usage_coverage", emitInitialState = true).map {
            db.withTransaction {
                val start = AppTime.window(days.first(), zone).startMillis
                val end = AppTime.window(days.last(), zone).endMillis
                snapshot(days, zone, db.usage().sessionsIn(start, end), db.usageCoverage().allOnce())
            }
        }
    }
    private fun snapshot(days: List<LocalDate>, zone: ZoneId, rows: List<UsageSessionEntity>, known: List<UsageCoverage>): UsageSnapshot {
        val intervals = rows.map { UsageInterval(it.startMillis, it.endMillis) }
        val coverageIntervals = known.map { UsageInterval(it.startMillis, it.endMillis) }
        val daily = days.map { val w = AppTime.window(it, zone); UsageAggregation.unionMillis(intervals, w.startMillis, w.endMillis) }
        val selected = AppTime.window(days.last(), zone)
        val totals = rows.groupBy { it.packageName }.mapNotNull { (pkg, sessions) ->
            val total = UsageAggregation.unionMillis(sessions.map { UsageInterval(it.startMillis, it.endMillis) }, selected.startMillis, selected.endMillis)
            if (total == 0L) null else AppUsageTotal(pkg, sessions.first().label, total, colorFor(pkg))
        }.sortedWith(compareByDescending<AppUsageTotal> { it.totalMillis }.thenBy { it.packageName })
        return UsageSnapshot(days, totals, daily.last(), daily, days.map { UsageAggregation.hourlyMillis(intervals, it, zone) }.toTypedArray(),
            days.map { val w = AppTime.window(it, zone); UsageAggregation.coverage(coverageIntervals, w.startMillis, minOf(w.endMillis, AppTime.nowMillis())) }, known.maxOfOrNull { it.collectedAt }, zone)
    }
    suspend fun queryTotals(start: Long, end: Long): List<AppUsageTotal> {
        refresh()
        return db.usage().sessionsIn(start, end).groupBy { it.packageName }.mapNotNull { (pkg, rows) ->
            val total = UsageAggregation.unionMillis(rows.map { UsageInterval(it.startMillis, it.endMillis) }, start, end)
            if (total == 0L) null else AppUsageTotal(pkg, rows.first().label, total, colorFor(pkg))
        }.sortedByDescending { it.totalMillis }
    }
    suspend fun dailyTotals(days: List<LocalDate>, zone: ZoneId): List<Long> {
        if (days.isEmpty()) return emptyList()
        refresh()
        val intervals = db.usage().sessionsIn(AppTime.window(days.first(), zone).startMillis, AppTime.window(days.last(), zone).endMillis).map { UsageInterval(it.startMillis, it.endMillis) }
        return days.map { val w = AppTime.window(it, zone); UsageAggregation.unionMillis(intervals, w.startMillis, w.endMillis) }
    }
    suspend fun hourlyMatrix(days: List<LocalDate>, zone: ZoneId): Array<LongArray> {
        if (days.isEmpty()) return emptyArray()
        refresh()
        val intervals = db.usage().sessionsIn(AppTime.window(days.first(), zone).startMillis, AppTime.window(days.last(), zone).endMillis).map { UsageInterval(it.startMillis, it.endMillis) }
        return days.map { UsageAggregation.hourlyMillis(intervals, it, zone) }.toTypedArray()
    }
    fun observeSessions(start: Long, end: Long, minDurationMillis: Long = 3 * 60_000L): Flow<List<UsageSession>> =
        db.usage().sessionsInFlow(start, end).map { displaySessions(it, start, end, minDurationMillis) }
    suspend fun querySessions(start: Long, end: Long, minDurationMillis: Long = 3 * 60_000L): List<UsageSession> {
        refresh()
        return displaySessions(db.usage().sessionsIn(start, end), start, end, minDurationMillis)
    }
    private fun displaySessions(rows: List<UsageSessionEntity>, start: Long, end: Long, minimum: Long): List<UsageSession> = rows.mapNotNull { row ->
        val a = maxOf(start, row.startMillis); val b = minOf(end, row.endMillis)
        if (b - a < minimum) null else UsageSession(row.packageName, row.label, a, b, colorFor(row.packageName), row.id)
    }
    companion object {
        private const val THROTTLE_MS = 30_000L
        private const val BACKFILL_MS = 7 * 24 * 3600_000L
        private const val RETENTION_MS = 400L * 24 * 3600_000L
        private val refreshLock = Mutex()
        private var inFlight: Deferred<UsageRefreshResult>? = null
        @Volatile private var lastSuccessfulAt = 0L
    }
}

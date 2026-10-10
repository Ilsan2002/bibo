package com.bibo.data

import androidx.room.withTransaction

/** Replace measured intervals and coverage together, preserving rows outside the refreshed range. */
internal suspend fun persistUsageCollection(db: BiboDb, firstEvent: Long?, now: Long, sessions: List<UsageSessionEntity>, retentionMillis: Long) {
    db.withTransaction {
        val known = db.usageCoverage().allOnce()
        val from = firstEvent ?: now // Empty system history never establishes coverage or erases saved rows.
        if (from < now) {
            val previous = db.usage().sessionsIn(from, now)
            val ids = previous.associateBy { it.packageName to it.startMillis }
            val edges = previous.flatMap { row -> buildList {
                if (row.startMillis < from) add(row.copy(endMillis = from))
                if (row.endMillis > now) add(row.copy(id = 0, startMillis = now))
            } }
            val replacements = sessions.map { row -> row.copy(id = ids[row.packageName to row.startMillis]?.id ?: 0) }
            db.usage().deleteOverlapping(from, now)
            db.usage().upsertAll(edges + replacements)
            val coverage = UsageAggregation.union(known.map { UsageInterval(it.startMillis, it.endMillis) } + UsageInterval(from, now), now - retentionMillis, maxOf(now, known.maxOfOrNull { it.endMillis } ?: now))
            db.usageCoverage().clear()
            coverage.forEach { db.usageCoverage().insert(UsageCoverage(it.start, it.end, now)) }
        }
        db.usage().pruneBefore(now - retentionMillis)
        db.usageCoverage().pruneBefore(now - retentionMillis)
    }
}

package com.bibo.data

/** Caller owns the Room transaction. Repeated or stale finalization is a no-op. */
object SessionFinalizer {
    suspend fun finishInTransaction(db: BiboDb, sessionId: String, elapsedMillis: Long, note: String? = null, status: String = "FINISHED"): Boolean {
        val session = db.workSessions().active()?.takeIf { it.id == sessionId } ?: return false
        val duration = elapsedMillis.coerceAtLeast(0)
        val cleanNote = note?.trim()?.takeIf { it.isNotEmpty() }
        for (part in SessionTiming.intervals(duration, session.pomodoro, session.workMin, session.breakMin)) {
            val start = session.startMillis + part.startOffset
            val end = session.startMillis + part.endOffset
            val blockId = if (part.kind == "WORK" && end > start) db.activityBlocks().insert(ActivityBlock(
                title = session.title, startMillis = start, endMillis = end,
                source = when { session.taskId != null -> "TASK_TIMER"; session.kind == "FOCUS" -> "FOCUS"; else -> "TIMER" },
                note = cleanNote, goalId = session.goalId, sessionId = session.id, taskId = session.taskId, provenance = "MEASURED")) else null
            db.sessionSegments().insert(SessionSegment(sessionId = session.id, kind = part.kind, startMillis = start, endMillis = end, activityBlockId = blockId))
        }
        db.workSessions().update(session.copy(activeSlot = null, status = status, elapsedAtAnchorMillis = duration, endMillis = session.startMillis + duration, note = cleanNote))
        session.taskId?.let { db.workSessions().setTaskRunning(it, null) }
        return true
    }
}

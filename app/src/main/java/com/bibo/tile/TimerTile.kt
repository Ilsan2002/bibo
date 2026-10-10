package com.bibo.tile

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.bibo.data.TimerController
import com.bibo.data.AppScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Quick Settings tile that toggles the activity timer from the shade without opening
 * the app — a fast path for manual time tracking.
 */
class TimerTile : TileService() {
    private var displayedSessionId: String? = null

    override fun onStartListening() {
        AppScope.io.launch {
            TimerController.initialize(applicationContext)
            withContext(Dispatchers.Main) { refresh() }
        }
    }

    override fun onClick() {
        val ctx = applicationContext
        val expected = displayedSessionId
        AppScope.io.launch {
            if (expected != null) TimerController.stopSession(ctx, expected)
            else TimerController.startTimerAwait(ctx, "Quick timer", requireIdle = true)
            withContext(Dispatchers.Main) { refresh() }
        }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val running = TimerController.isRunning(applicationContext)
        displayedSessionId = TimerController.sessionId(applicationContext)
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (running) "Stop ${TimerController.runningTitle(applicationContext)}" else "Start timer"
        tile.updateTile()
    }
}

package com.bibo.tile

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.bibo.data.TimerController

/**
 * Quick Settings tile that toggles the activity timer from the shade without opening
 * the app — a fast path for manual time tracking.
 */
class TimerTile : TileService() {

    override fun onStartListening() {
        refresh()
    }

    override fun onClick() {
        val ctx = applicationContext
        if (TimerController.isRunning(ctx)) {
            TimerController.stopTimer(ctx)
        } else {
            TimerController.startTimer(ctx, "Quick timer")
        }
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val running = TimerController.isRunning(applicationContext)
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (running) "Timer running" else "Start timer"
        tile.updateTile()
    }
}

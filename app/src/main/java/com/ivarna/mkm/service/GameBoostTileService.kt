package com.ivarna.mkm.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.ivarna.mkm.MainActivity
import com.ivarna.mkm.data.model.GameBoostState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Quick settings tile that toggles Game Boost (same manager/service path as GameBoostScreen). */
class GameBoostTileService : TileService() {
    private var watchJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        val manager = GameBoostRegistry.manager(this)
        watchJob?.cancel()
        watchJob = tileScope.launch(Dispatchers.Main) {
            GameBoostRegistry.state.collectLatest { refreshTile(it) }
        }
        refreshTile(manager.currentState())
    }

    override fun onStopListening() {
        watchJob?.cancel()
        watchJob = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val state = GameBoostRegistry.state.value
        if (state is GameBoostState.Enabling || state is GameBoostState.Disabling) return
        // The heat disclosure and recovery flow live in the app.
        val acknowledged = getSharedPreferences(GameBoostSnapshotStore.PREFS, MODE_PRIVATE)
            .getBoolean(KEY_DISCLOSURE, false)
        if (state is GameBoostState.RecoveryRequired || (state is GameBoostState.Off && !acknowledged)) {
            openGameBoost()
            return
        }
        val app = applicationContext
        val manager = GameBoostRegistry.manager(app)
        tileScope.launch {
            if (state is GameBoostState.Off) {
                GameBoostService.start(app)
                val result = if (GameBoostService.awaitReady()) manager.enable()
                else GameBoostTransitionResult.Failure("service not ready")
                if (result is GameBoostTransitionResult.Failure && !GameBoostRegistry.ownsTuning()) GameBoostService.stop(app)
            } else if (manager.disable() is GameBoostTransitionResult.Success) {
                GameBoostService.stop(app)
            }
        }
    }

    private fun refreshTile(state: GameBoostState) {
        val tile = qsTile ?: return
        tile.state = when (state) {
            GameBoostState.Off -> Tile.STATE_INACTIVE
            is GameBoostState.Enabling, GameBoostState.Disabling -> Tile.STATE_UNAVAILABLE
            else -> Tile.STATE_ACTIVE
        }
        tile.updateTile()
    }

    private fun openGameBoost() {
        val intent = Intent(this, MainActivity::class.java)
            .setAction(GameBoostService.ACTION_OPEN_GAME_BOOST)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        private const val KEY_DISCLOSURE = "heat_disclosure_acknowledged"
        private val tileScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

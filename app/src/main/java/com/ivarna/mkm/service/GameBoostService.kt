package com.ivarna.mkm.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ivarna.mkm.R
import com.ivarna.mkm.MainActivity
import com.ivarna.mkm.data.model.GameBoostState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.PowerManager
import com.ivarna.mkm.shell.ShellManager
import java.util.concurrent.atomic.AtomicBoolean

/** Keeps Game Boost ownership and thermal release monitoring alive outside the UI. */
class GameBoostService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var thermalGuard: GameBoostThermalGuard? = null
    private var watcherJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        ready.set(false)
        createChannel()
        val notification = buildNotification(GameBoostRegistry.state.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else startForeground(NOTIFICATION_ID, notification)

        val guard = GameBoostThermalGuard(
            this, scope,
            onUnavailable = { GameBoostRegistry.manager(this@GameBoostService).onThermalMonitoringUnavailable() }
        ) { status ->
            GameBoostRegistry.manager(this@GameBoostService).onThermalStatus(status)
        }
        val guardResult = guard.start()
        thermalGuard = guard
        scope.launch {
            GameBoostRegistry.state.collectLatest { state ->
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(state))
            }
        }
        if (guardResult.monitoringAvailable) {
            ready.set(true)
        } else {
            // A recovered session may already hold max locks. Restore them
            // before declaring the service ready, and keep the service alive
            // for RecoveryRequired diagnostics if that restore fails.
            scope.launch {
                GameBoostRegistry.manager(this@GameBoostService).onThermalMonitoringUnavailable()
                ready.set(true)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A null intent indicates Android recreated a sticky service. Do not
        // keep a pre-reboot or already-finished Game Boost owner alive.
        if (intent == null) {
            val store = GameBoostSnapshotStore(this)
            val snapshot = store.load()
            if (snapshot == null || !store.isSameBoot(snapshot)) {
                snapshot?.let { store.clear() }
                if (!GameBoostAutoBoost.isEnabled(this)) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
            }
        }
        if (GameBoostAutoBoost.isEnabled(this) && watcherJob?.isActive != true) {
            watcherJob = scope.launch { watchForeground() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Per-app auto boost. Polls the resumed activity every 3s while the screen is on;
     * screen off counts as leaving the game. Thermal safety stays with the guard and
     * [GameBoostManager]: a ThermalLimited session is never relocked here.
     * ponytail: 3s dumpsys poll; switch to a UsageStats/accessibility event source if battery cost shows up.
     */
    private suspend fun watchForeground() {
        val manager = GameBoostRegistry.manager(this)
        val power = getSystemService(PowerManager::class.java)
        var previous: String? = null
        var autoOwned = false
        while (scope.isActive && GameBoostAutoBoost.isEnabled(this)) {
            val foreground = if (power?.isInteractive != false) {
                val result = withContext(Dispatchers.IO) { ShellManager.execElevated(GameBoostAutoBoost.FOREGROUND_COMMAND) }
                GameBoostAutoBoost.parseForegroundPackage(result.stdout)
            } else null
            val state = GameBoostRegistry.state.value
            if (state is GameBoostState.Off) autoOwned = false
            when (GameBoostAutoBoost.decide(foreground, previous, GameBoostAutoBoost.packages(this), state is GameBoostState.Off, autoOwned)) {
                GameBoostAutoBoost.Action.ENABLE ->
                    autoOwned = withContext(Dispatchers.IO) { manager.enable() } is GameBoostTransitionResult.Success
                GameBoostAutoBoost.Action.DISABLE -> {
                    if (withContext(Dispatchers.IO) { manager.disable() } is GameBoostTransitionResult.Success) autoOwned = false
                }
                GameBoostAutoBoost.Action.NONE -> Unit
            }
            previous = foreground
            delay(WATCH_INTERVAL_MS)
        }
    }

    override fun onDestroy() {
        ready.set(false)
        watcherJob?.cancel()
        thermalGuard?.stop()
        thermalGuard = null
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(state: GameBoostState): android.app.Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_OPEN_GAME_BOOST
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val textRes = when (state) {
            is GameBoostState.ThermalLimited -> R.string.game_boost_notification_thermal
            is GameBoostState.RecoveryRequired -> R.string.game_boost_notification_recovery
            GameBoostState.Off -> R.string.game_boost_notification_watching
            else -> R.string.game_boost_notification_active
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_battery)
            .setContentTitle(getString(R.string.game_boost))
            .setContentText(getString(textRes))
            .setContentIntent(PendingIntent.getActivity(this, NOTIFICATION_ID, openIntent, pendingFlags))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Game Boost", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    companion object {
        const val ACTION_START = "com.ivarna.mkm.action.START_GAME_BOOST"
        const val ACTION_STOP = "com.ivarna.mkm.action.STOP_GAME_BOOST"
        const val ACTION_OPEN_GAME_BOOST = "com.ivarna.mkm.action.OPEN_GAME_BOOST"
        const val CHANNEL_ID = "mkm_game_boost_channel"
        const val NOTIFICATION_ID = 3002
        private const val WATCH_INTERVAL_MS = 3_000L
        private val ready = AtomicBoolean(false)

        fun start(context: Context) {
            val intent = Intent(context, GameBoostService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }

        fun ensureRunning(context: Context) {
            if (!ready.get()) start(context)
        }

        suspend fun awaitReady(timeoutMs: Long = 2_000L): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!ready.get() && System.currentTimeMillis() < deadline) kotlinx.coroutines.delay(50L)
            return ready.get()
        }

        /** No-op while auto boost is on: the service hosts the foreground watcher. */
        fun stop(context: Context) {
            if (GameBoostAutoBoost.isEnabled(context)) return
            context.stopService(Intent(context, GameBoostService::class.java).setAction(ACTION_STOP))
        }
    }
}

package com.ivarna.mkm.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import com.ivarna.mkm.data.model.BatterySnapshot
import com.ivarna.mkm.data.model.BatteryStats
import com.ivarna.mkm.data.model.SessionType
import com.ivarna.mkm.data.provider.BatteryCharging
import com.ivarna.mkm.data.provider.BatteryProvider
import com.ivarna.mkm.receiver.BatteryStatsResetReceiver
import com.ivarna.mkm.shell.ShellManager
import com.ivarna.mkm.shell.SysfsTuningExecutor
import com.ivarna.mkm.utils.BatteryHistoryManager
import com.ivarna.mkm.utils.BatteryNotificationManager
import com.ivarna.mkm.utils.BatteryStatsResetPrefs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.max

/**
 * Tracks battery sessions (both discharging and charging).
 *
 * Responsibilities:
 * - Listen to system broadcasts (screen on/off, power connect/disconnect).
 * - Accumulate time spent in screen-on, screen-off, and deep-sleep states.
 * - Accumulate per-screen-state drain (total drop ÷ total hours, open interval included).
 * - When the plug state changes: persist the finished session, start a new one.
 * - Checkpoint the open session to prefs so stop / kill / reboot resumes it.
 * - Fire battery-stats reset on unplug / full, and enforce the optional charge limit.
 *
 * All shell work runs on [scope] (single-threaded IO), never on the main thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BatterySessionTracker(context: Context) {

    companion object {
        const val MAX_HISTORY = 60
        private const val PREFS_NAME = BatteryMonitorService.PREFS_NAME
        private const val PREF_UPDATE_INTERVAL = "battery_update_interval_ms"
        const val DEFAULT_UPDATE_INTERVAL_MS = 30_000L
        const val SCREEN_OFF_INTERVAL_MS = 300_000L

        const val PREF_CHARGE_LIMIT_ENABLED = "charge_limit_enabled"
        const val PREF_CHARGE_LIMIT_PERCENT = "charge_limit_percent"
        const val DEFAULT_CHARGE_LIMIT = 80
        const val CHARGE_LIMIT_HYSTERESIS = 5
        private const val PREF_CHARGE_LIMIT_STOPPED = "charge_limit_stopped_node"

        private const val CHECKPOINT_PREFS = "battery_session_checkpoint"
        private const val CHECKPOINT_INTERVAL_MS = 60_000L
        /** Minimum time in a screen state before its drain rate is trusted. */
        private const val MIN_RATE_MS = 180_000L

        fun ratePerHour(dropPercent: Int, durationMs: Long): Float =
            if (durationMs > 0L) dropPercent * 3_600_000f / durationMs else 0f

        /** Hysteresis: stop at [limit], resume at [limit] − [CHARGE_LIMIT_HYSTERESIS]. */
        fun nextChargeLimitStopped(percent: Int, limit: Int, stopped: Boolean): Boolean = when {
            !stopped && percent >= limit -> true
            stopped && percent <= limit - CHARGE_LIMIT_HYSTERESIS -> false
            else -> stopped
        }

        /**
         * Vendor charge-control nodes in preference order. stopValue null = write the limit itself.
         * charge_control_limit is skipped: it is a current-throttle level, not an on/off switch.
         */
        private val CHARGE_LIMIT_NODES = listOf(
            ChargeLimitNode("/sys/class/power_supply/battery/charge_control_end_threshold", null, "100"),
            ChargeLimitNode("/sys/class/power_supply/battery/input_suspend", "1", "0"),
            ChargeLimitNode("/sys/class/power_supply/battery/battery_charging_enabled", "0", "1"),
            ChargeLimitNode("/sys/class/power_supply/battery/charging_enabled", "0", "1"),
            ChargeLimitNode("/sys/class/power_supply/battery/batt_slate_mode", "1", "0"),
            ChargeLimitNode("/sys/class/power_supply/battery/store_mode", "1", "0")
        )
    }

    private data class ChargeLimitNode(val path: String, val stopValue: String?, val resumeValue: String)

    private data class DrainTotals(val onDrop: Int, val onMs: Long, val offDrop: Int, val offMs: Long)

    private val appContext = context.applicationContext
    private val provider = BatteryProvider(appContext)
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val checkpoint = appContext.getSharedPreferences(CHECKPOINT_PREFS, Context.MODE_PRIVATE)
    private val historyManager = BatteryHistoryManager.get(appContext)
    private val notifier by lazy { BatteryNotificationManager(appContext) }

    // Single-threaded: provider and charge-limit state are touched only from here.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val historyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    // --- Mutable session state (guarded by lock) ---
    private val lock = Any()
    private var sessionStartTimeMs = 0L
    private var sessionStartPercent = 0
    private var isScreenOn = false
    private var lastScreenChangeTimeMs = 0L
    private var accumulatedScreenOnMs = 0L
    private var accumulatedScreenOffMs = 0L
    private var deepSleepCarryMs = 0L
    private var deepSleepBaselineMs = 0L
    private var lastSnapshot: BatterySnapshot? = null
    private var isSessionActive = false
    private var isChargingSession = false
    private var lastCheckpointMs = 0L

    // Interval bookkeeping: closed-interval totals + the open interval start.
    private var pendingIntervalStartMs = 0L
    private var pendingIntervalStartPercent = 0
    private var onDropSum = 0
    private var onDurMs = 0L
    private var offDropSum = 0
    private var offDurMs = 0L
    private var intervalCount = 0

    // Whole-session running sums (no window cap).
    private var currentSumMa = 0L
    private var wattageSumW = 0.0
    private var temperatureSumC = 0.0
    private var sampleCount = 0
    private var chargingCurrentSumMa = 0L
    private var chargingCurrentCount = 0

    // Rolling history for sparklines (normalized 0..1)
    private val wattageHistory = mutableListOf<Float>()
    private val drainHistory = mutableListOf<Float>()
    private var peakWattageW = 1f // floor of 1 W avoids division-by-zero on idle

    // Touched only from scope.
    private var wasFull = false
    @Volatile private var chargeLimitNode: ChargeLimitNode? = null
    private var chargeLimitStopped = false
    private var limitNotified = false

    private val _stats = MutableStateFlow<BatteryStats?>(null)
    val stats: StateFlow<BatteryStats?> = _stats.asStateFlow()

    val history = historyManager.records

    fun clearHistory() {
        historyScope.launch { historyManager.clear() }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> onScreenChanged(true)
                Intent.ACTION_SCREEN_OFF -> onScreenChanged(false)
                Intent.ACTION_POWER_CONNECTED -> onPowerChanged(true)
                Intent.ACTION_POWER_DISCONNECTED -> onPowerChanged(false)
            }
        }
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    fun start() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        appContext.registerReceiver(receiver, filter)

        // Sticky intent only (no shell) on the calling (main) thread.
        val sticky = stickyIntent()
        val percent = stickyPercent(sticky) ?: 0
        val plugged = BatteryCharging.readAndroidCharging(sticky)
        val now = System.currentTimeMillis()
        synchronized(lock) {
            isSessionActive = true
            if (!restoreCheckpointLocked(now, plugged, percent)) {
                isScreenOn = powerManager.isInteractive
                lastScreenChangeTimeMs = now
                isChargingSession = plugged
                startNewSessionLocked(percent, now)
            }
        }
        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        wasFull = status == BatteryManager.BATTERY_STATUS_FULL || (plugged && percent >= 100)

        pollJob = scope.launch {
            resumeStaleChargeStop()
            chargeLimitNode = detectChargeLimitNode()
            while (isActive) {
                tick()
                val interval = if (isScreenOn) {
                    getUpdateInterval()
                } else {
                    maxOf(getUpdateInterval(), SCREEN_OFF_INTERVAL_MS)
                }
                delay(interval)
            }
        }
    }

    fun getUpdateInterval(): Long {
        return prefs.getLong(PREF_UPDATE_INTERVAL, DEFAULT_UPDATE_INTERVAL_MS)
            .coerceIn(1_000L, 600_000L)
    }

    fun setUpdateInterval(intervalMs: Long) {
        prefs.edit().putLong(PREF_UPDATE_INTERVAL, intervalMs).apply()
    }

    fun stop() {
        runCatching { appContext.unregisterReceiver(receiver) }
        pollJob?.cancel()
        synchronized(lock) { saveCheckpointLocked(System.currentTimeMillis(), sync = true) }
        // Never leave charging blocked while nobody enforces the hysteresis.
        val node = chargeLimitNode
        if (chargeLimitStopped && node != null) {
            CoroutineScope(Dispatchers.IO).launch { writeChargeLimit(node, stop = false, limit = 0) }
        }
        scope.cancel()
    }

    // ------------------------------------------------------------------
    // Broadcast handlers
    // ------------------------------------------------------------------

    private fun onScreenChanged(on: Boolean) {
        val now = System.currentTimeMillis()
        val percent = stickyPercent(stickyIntent())
        synchronized(lock) {
            screenTransitionLocked(on, now, percent ?: lastSnapshot?.percent ?: pendingIntervalStartPercent)
        }
        scope.launch { tick() }
    }

    private fun onPowerChanged(connected: Boolean) {
        val now = System.currentTimeMillis()
        val percent = stickyPercent(stickyIntent())
        scope.launch {
            provider.invalidate()
            val snap = provider.getSnapshot(appContext)
            synchronized(lock) {
                lastSnapshot = snap
                if (isChargingSession != connected) {
                    endSessionAndStartNewLocked(now, percent ?: snap.percent, connected)
                }
            }
            if (!connected && BatteryStatsResetPrefs.isOnUnplug(appContext)) {
                BatteryStatsResetReceiver.resetStats(appContext, "unplug")
            }
            tick()
        }
    }

    private fun screenTransitionLocked(on: Boolean, now: Long, percent: Int) {
        if (isScreenOn == on) return
        if (isScreenOn) accumulateScreenOnLocked(now) else accumulateScreenOffLocked(now)
        endIntervalLocked(isScreenOn, now, percent)
        beginIntervalLocked(percent, now)
        isScreenOn = on
        lastScreenChangeTimeMs = now
    }

    private fun endSessionAndStartNewLocked(now: Long, endPercent: Int, nowCharging: Boolean) {
        if (isScreenOn) accumulateScreenOnLocked(now) else accumulateScreenOffLocked(now)
        lastScreenChangeTimeMs = now
        endIntervalLocked(isScreenOn, now, endPercent)
        // Re-open at the end point so the closed interval is not counted again as "open".
        beginIntervalLocked(endPercent, now)
        persistSessionLocked(sessionTypeOf(isChargingSession), now, endPercent)
        isChargingSession = nowCharging
        isScreenOn = powerManager.isInteractive
        lastScreenChangeTimeMs = now
        startNewSessionLocked(endPercent, now)
        saveCheckpointLocked(now)
    }

    // ------------------------------------------------------------------
    // Periodic tick (runs on scope)
    // ------------------------------------------------------------------

    private fun tick() {
        // One shell read per tick: snapshot carries current, voltage, power and capacity.
        val snap = provider.getSnapshot(appContext)
        val now = System.currentTimeMillis()

        synchronized(lock) {
            lastSnapshot = snap
            computeAndEmitLocked(now)
            if (now - lastCheckpointMs >= CHECKPOINT_INTERVAL_MS) saveCheckpointLocked(now)
        }

        // Battery-stats reset on the rising edge of "full" (fires once per full charge).
        if (snap.isFull && !wasFull && BatteryStatsResetPrefs.isOnFull(appContext)) {
            BatteryStatsResetReceiver.resetStats(appContext, "full")
        }
        wasFull = snap.isFull

        applyChargeLimit(snap)
    }

    // ------------------------------------------------------------------
    // Computation
    // ------------------------------------------------------------------

    private fun computeAndEmitLocked(now: Long) {
        val snap = lastSnapshot ?: return
        val currentScreenOn = accumulatedScreenOnMs + if (isScreenOn) (now - lastScreenChangeTimeMs).coerceAtLeast(0L) else 0L
        val currentScreenOff = accumulatedScreenOffMs + if (!isScreenOn) (now - lastScreenChangeTimeMs).coerceAtLeast(0L) else 0L
        val totalSession = (now - sessionStartTimeMs).coerceAtLeast(0L)

        val currentDeepSleepMs = deepSleepMsLocked().coerceAtMost(currentScreenOff)
        val awakeMs = max(0L, totalSession - currentDeepSleepMs)

        val totals = drainTotalsLocked(now, snap.percent)
        val (activeDrain, idleDrain, isDrainReliable) = drainRatesLocked(totals, now, snap.percent)
        val screenOnDrainPercent = totals.onDrop.toFloat()
        val screenOffDrainPercent = totals.offDrop.toFloat()

        // Deep sleep is a subset of screen-off. Estimate its absolute drain proportionally by time.
        val deepSleepDrainPercent = if (currentScreenOff > 0 && screenOffDrainPercent > 0f) {
            screenOffDrainPercent * (currentDeepSleepMs.toFloat() / currentScreenOff)
        } else 0f
        val awakeDrainPercent = (screenOnDrainPercent + screenOffDrainPercent - deepSleepDrainPercent).coerceAtLeast(0f)

        // Whole-session running sums
        if (isChargingSession && snap.currentMa > 0) {
            chargingCurrentSumMa += snap.currentMa
            chargingCurrentCount++
        }
        currentSumMa += abs(snap.currentMa)
        wattageSumW += abs(snap.calibratedWattageW)
        temperatureSumC += snap.temperatureC
        sampleCount++

        // Rolling history — normalise by the dynamic session peak, not a hardcoded cap.
        val lastWattageW = abs(snap.calibratedWattageW)
        if (lastWattageW > peakWattageW) peakWattageW = lastWattageW
        wattageHistory.add((lastWattageW / peakWattageW).coerceIn(0f, 1f))
        drainHistory.add((activeDrain / 20f).coerceIn(0f, 1f))
        if (wattageHistory.size > MAX_HISTORY) wattageHistory.removeAt(0)
        if (drainHistory.size > MAX_HISTORY) drainHistory.removeAt(0)

        val estimatedMinutes = estimateTimeRemainingLocked(
            snap = snap,
            activeDrain = activeDrain,
            idleDrain = idleDrain,
            screenOnRatio = percentOf(currentScreenOn, totalSession) / 100f,
            totalSessionMs = totalSession,
            isDrainReliable = isDrainReliable
        )

        _stats.value = BatteryStats(
            percent = snap.percent,
            temperatureC = snap.temperatureC,
            currentMa = snap.currentMa,
            isCharging = snap.isCharging,
            voltageMv = snap.voltageMv,
            wattageW = snap.wattageW,
            calibratedWattageW = snap.calibratedWattageW,
            ratedCapacityMah = snap.ratedCapacityMah,
            estimatedCapacityMah = snap.estimatedCapacityMah,
            activeDrainPerHr = activeDrain,
            idleDrainPerHr = idleDrain,
            screenOnTimeMs = currentScreenOn,
            screenOffTimeMs = currentScreenOff,
            deepSleepTimeMs = currentDeepSleepMs,
            awakeTimeMs = awakeMs,
            sessionStartTimeMs = sessionStartTimeMs,
            totalSessionTimeMs = totalSession,
            screenOnPercent = percentOf(currentScreenOn, totalSession),
            screenOffPercent = percentOf(currentScreenOff, totalSession),
            deepSleepPercent = percentOf(currentDeepSleepMs, totalSession),
            awakePercent = percentOf(awakeMs, totalSession),
            screenOnDrainPercent = screenOnDrainPercent,
            screenOffDrainPercent = screenOffDrainPercent,
            deepSleepDrainPercent = deepSleepDrainPercent,
            awakeDrainPercent = awakeDrainPercent,
            isSessionActive = isSessionActive,
            intervalCount = intervalCount,
            wattageHistory = wattageHistory.toList(),
            drainHistory = drainHistory.toList(),
            estimatedTimeRemainingMin = estimatedMinutes,
            chargingSessionStartPercent = if (isChargingSession) sessionStartPercent else 0,
            chargingGainedPercent = if (isChargingSession) (snap.percent - sessionStartPercent).coerceAtLeast(0) else 0,
            chargingAvgCurrentMa = if (isChargingSession && chargingCurrentCount > 0)
                (chargingCurrentSumMa / chargingCurrentCount).toInt() else 0,
            cycleCount = snap.cycleCount,
            health = snap.health,
            chargeType = snap.chargeType,
            chargeLimitSupported = chargeLimitNode != null
        )
    }

    private fun estimateTimeRemainingLocked(
        snap: BatterySnapshot,
        activeDrain: Float,
        idleDrain: Float,
        screenOnRatio: Float,
        totalSessionMs: Long,
        isDrainReliable: Boolean
    ): Long {
        return if (snap.isCharging) {
            // Plugged but net discharging: no time-to-full.
            if (snap.currentMa < 0) return 0L
            // Charging: estimate time to full. Need at least 60s of session data.
            if (totalSessionMs < 60_000L) return 0L
            if (snap.estimatedCapacityMah <= 0 || snap.percent >= 100) return 0L

            // Prefer instantaneous current; fall back to wattage-derived or session average.
            val instantMa = snap.currentMa
            val chargeRateMa: Float = when {
                instantMa >= 50 -> instantMa.toFloat()
                // Derive from wattage and voltage (P = V*I => I = P/V)
                snap.calibratedWattageW > 0f && snap.voltageMv > 0 ->
                    (snap.calibratedWattageW * 1000f / snap.voltageMv)
                snap.wattageW > 0f && snap.voltageMv > 0 ->
                    (snap.wattageW * 1000f / snap.voltageMv)
                chargingCurrentCount > 0 -> chargingCurrentSumMa.toFloat() / chargingCurrentCount
                else -> return 0L
            }
            if (chargeRateMa < 50f) return 0L

            val remainingMah = (100 - snap.percent) * snap.estimatedCapacityMah / 100f
            val minutes = (remainingMah / chargeRateMa * 60).toLong()
            // Sanity cap: more than 24h is unrealistic.
            if (minutes in 1..1440) minutes else 0L
        } else {
            // Discharging: need at least 3 minutes of session data before trusting any estimate.
            if (totalSessionMs < 180_000L) return 0L
            if (!isDrainReliable || activeDrain <= 0f) return 0L

            val blended = activeDrain * screenOnRatio + idleDrain * (1f - screenOnRatio)
            if (blended < 0.1f) return 0L

            val minutes = ((snap.percent / blended) * 60).toLong()
            // Sanity cap: more than 7 days is unrealistic.
            if (minutes in 1..10080) minutes else 0L
        }
    }

    // ------------------------------------------------------------------
    // Interval bookkeeping
    // ------------------------------------------------------------------

    private fun beginIntervalLocked(percent: Int, timeMs: Long) {
        pendingIntervalStartMs = timeMs
        pendingIntervalStartPercent = percent
    }

    private fun endIntervalLocked(isScreenOnState: Boolean, endMs: Long, endPercent: Int) {
        val durMs = endMs - pendingIntervalStartMs
        if (durMs <= 0L) return
        val drop = (pendingIntervalStartPercent - endPercent).coerceAtLeast(0)
        if (isScreenOnState) {
            onDropSum += drop
            onDurMs += durMs
        } else {
            offDropSum += drop
            offDurMs += durMs
        }
        intervalCount++
    }

    /** Closed totals plus the still-open interval up to [now]. */
    private fun drainTotalsLocked(now: Long, percent: Int): DrainTotals {
        val openMs = (now - pendingIntervalStartMs).coerceAtLeast(0L)
        val openDrop = (pendingIntervalStartPercent - percent).coerceAtLeast(0)
        return if (isScreenOn) {
            DrainTotals(onDropSum + openDrop, onDurMs + openMs, offDropSum, offDurMs)
        } else {
            DrainTotals(onDropSum, onDurMs, offDropSum + openDrop, offDurMs + openMs)
        }
    }

    /** Active / idle %/h as total drop ÷ total hours; active falls back to whole-session drain. */
    private fun drainRatesLocked(t: DrainTotals, now: Long, percent: Int): Triple<Float, Float, Boolean> {
        val reliable = t.onMs >= MIN_RATE_MS
        val active = if (reliable) ratePerHour(t.onDrop, t.onMs)
                     else ratePerHour(sessionStartPercent - percent, now - sessionStartTimeMs)
        val idle = if (t.offMs >= MIN_RATE_MS) ratePerHour(t.offDrop, t.offMs) else 0f
        return Triple(active.coerceAtLeast(0f), idle.coerceAtLeast(0f), reliable)
    }

    // ------------------------------------------------------------------
    // Deep sleep: (elapsedRealtime − uptime) grows only while the CPU is suspended.
    // ------------------------------------------------------------------

    private fun sleepSinceBootMs(): Long = SystemClock.elapsedRealtime() - SystemClock.uptimeMillis()

    private fun deepSleepMsLocked(): Long =
        (deepSleepCarryMs + sleepSinceBootMs() - deepSleepBaselineMs).coerceAtLeast(0L)

    private fun bootCount(): Int =
        runCatching { Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT, -1) }
            .getOrDefault(-1)

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun stickyIntent(): Intent? =
        runCatching { appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()

    private fun stickyPercent(sticky: Intent?): Int? {
        val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: return null
        if (level < 0) return null
        val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        return (level * 100 / scale).coerceIn(0, 100)
    }

    private fun sessionTypeOf(charging: Boolean) =
        if (charging) SessionType.CHARGING else SessionType.DISCHARGING

    private fun startNewSessionLocked(percent: Int, now: Long) {
        sessionStartTimeMs = now
        sessionStartPercent = percent
        accumulatedScreenOnMs = 0L
        accumulatedScreenOffMs = 0L
        // Baseline only: accumulated deep sleep starts at 0, not "all sleep since boot".
        deepSleepCarryMs = 0L
        deepSleepBaselineMs = sleepSinceBootMs()
        beginIntervalLocked(percent, now)
        onDropSum = 0; onDurMs = 0L; offDropSum = 0; offDurMs = 0L; intervalCount = 0
        currentSumMa = 0L; wattageSumW = 0.0; temperatureSumC = 0.0; sampleCount = 0
        chargingCurrentSumMa = 0L; chargingCurrentCount = 0
        // Reset sparkline peak so the new session scales independently
        peakWattageW = 1f
    }

    private fun accumulateScreenOnLocked(now: Long) {
        accumulatedScreenOnMs += (now - lastScreenChangeTimeMs).coerceAtLeast(0L)
    }

    private fun accumulateScreenOffLocked(now: Long) {
        accumulatedScreenOffMs += (now - lastScreenChangeTimeMs).coerceAtLeast(0L)
    }

    private fun percentOf(part: Long, total: Long): Float {
        return if (total > 0) (part * 100f / total) else 0f
    }

    // ------------------------------------------------------------------
    // Session checkpoint (survives stop / kill / reboot)
    // ------------------------------------------------------------------

    private fun saveCheckpointLocked(now: Long, sync: Boolean = false) {
        if (sessionStartTimeMs == 0L) return
        val open = (now - lastScreenChangeTimeMs).coerceAtLeast(0L)
        val editor = checkpoint.edit()
            .putLong("start", sessionStartTimeMs)
            .putInt("startPct", sessionStartPercent)
            .putBoolean("charging", isChargingSession)
            .putLong("savedAt", now)
            .putInt("lastPct", lastSnapshot?.percent ?: pendingIntervalStartPercent)
            .putBoolean("screenOn", isScreenOn)
            .putLong("onMs", accumulatedScreenOnMs + if (isScreenOn) open else 0L)
            .putLong("offMs", accumulatedScreenOffMs + if (!isScreenOn) open else 0L)
            .putLong("pendStart", pendingIntervalStartMs)
            .putInt("pendPct", pendingIntervalStartPercent)
            .putInt("onDrop", onDropSum).putLong("onDur", onDurMs)
            .putInt("offDrop", offDropSum).putLong("offDur", offDurMs)
            .putInt("intervals", intervalCount)
            .putInt("boot", bootCount())
            .putLong("dsCarry", deepSleepCarryMs)
            .putLong("dsBase", deepSleepBaselineMs)
            .putLong("dsTotal", deepSleepMsLocked())
            .putLong("curSum", currentSumMa)
            .putFloat("wattSum", wattageSumW.toFloat())
            .putFloat("tempSum", temperatureSumC.toFloat())
            .putInt("samples", sampleCount)
            .putLong("chgSum", chargingCurrentSumMa)
            .putInt("chgCount", chargingCurrentCount)
        if (sync) editor.commit() else editor.apply()
        lastCheckpointMs = now
    }

    /** Restores the saved session. Returns false when there is none. */
    private fun restoreCheckpointLocked(now: Long, plugged: Boolean, percent: Int): Boolean {
        val cp = checkpoint
        if (!cp.contains("start")) return false
        val savedAt = cp.getLong("savedAt", now).coerceAtMost(now)
        sessionStartTimeMs = cp.getLong("start", now)
        sessionStartPercent = cp.getInt("startPct", percent)
        isChargingSession = cp.getBoolean("charging", plugged)
        isScreenOn = cp.getBoolean("screenOn", false)
        accumulatedScreenOnMs = cp.getLong("onMs", 0L)
        accumulatedScreenOffMs = cp.getLong("offMs", 0L)
        lastScreenChangeTimeMs = savedAt
        pendingIntervalStartMs = cp.getLong("pendStart", savedAt)
        pendingIntervalStartPercent = cp.getInt("pendPct", percent)
        onDropSum = cp.getInt("onDrop", 0); onDurMs = cp.getLong("onDur", 0L)
        offDropSum = cp.getInt("offDrop", 0); offDurMs = cp.getLong("offDur", 0L)
        intervalCount = cp.getInt("intervals", 0)
        currentSumMa = cp.getLong("curSum", 0L)
        wattageSumW = cp.getFloat("wattSum", 0f).toDouble()
        temperatureSumC = cp.getFloat("tempSum", 0f).toDouble()
        sampleCount = cp.getInt("samples", 0)
        chargingCurrentSumMa = cp.getLong("chgSum", 0L)
        chargingCurrentCount = cp.getInt("chgCount", 0)
        if (cp.getInt("boot", -2) == bootCount()) {
            deepSleepCarryMs = cp.getLong("dsCarry", 0L)
            deepSleepBaselineMs = cp.getLong("dsBase", sleepSinceBootMs())
        } else {
            // Rebooted: the sleep clock restarted; carry the saved total. Gap counts as awake.
            deepSleepCarryMs = cp.getLong("dsTotal", 0L)
            deepSleepBaselineMs = sleepSinceBootMs()
        }
        peakWattageW = 1f

        val lastPct = cp.getInt("lastPct", percent)
        // Plug state changed while not running (or a discharge session gained charge meanwhile):
        // close the old session at the checkpoint.
        if (isChargingSession != plugged || (!plugged && percent > lastPct + 1)) {
            endIntervalLocked(isScreenOn, savedAt, lastPct)
            beginIntervalLocked(lastPct, savedAt)
            persistSessionLocked(sessionTypeOf(isChargingSession), savedAt, lastPct)
            isChargingSession = plugged
            isScreenOn = powerManager.isInteractive
            lastScreenChangeTimeMs = now
            startNewSessionLocked(percent, now)
        } else {
            // Gap is attributed to the saved screen state; switch now if it changed.
            screenTransitionLocked(powerManager.isInteractive, now, percent)
        }
        return true
    }

    // ------------------------------------------------------------------
    // Charge limit (runs on scope)
    // ------------------------------------------------------------------

    private fun detectChargeLimitNode(): ChargeLimitNode? {
        if (!ShellManager.hasRootCapability()) return null
        return CHARGE_LIMIT_NODES.firstOrNull { SysfsTuningExecutor.canWrite(it.path) }
    }

    /** A crash or kill may have left charging stopped; undo it before anything else. */
    private fun resumeStaleChargeStop() {
        val path = prefs.getString(PREF_CHARGE_LIMIT_STOPPED, null) ?: return
        val node = CHARGE_LIMIT_NODES.firstOrNull { it.path == path }
        if (node == null) {
            prefs.edit().remove(PREF_CHARGE_LIMIT_STOPPED).apply()
            return
        }
        chargeLimitStopped = true
        writeChargeLimit(node, stop = false, limit = 0)
    }

    private fun applyChargeLimit(snap: BatterySnapshot) {
        val enabled = prefs.getBoolean(PREF_CHARGE_LIMIT_ENABLED, false)
        val limit = prefs.getInt(PREF_CHARGE_LIMIT_PERCENT, DEFAULT_CHARGE_LIMIT).coerceIn(50, 100)
        val node = chargeLimitNode
        if (node == null) {
            // No writable node: notify once per crossing instead.
            if (!enabled || !snap.isCharging || snap.percent <= limit - CHARGE_LIMIT_HYSTERESIS) {
                limitNotified = false
            } else if (snap.percent >= limit && !limitNotified) {
                notifier.showChargeLimitReached(snap.percent)
                limitNotified = true
            }
            return
        }
        // Only begin stopping while plugged; once stopped, hold until the resume threshold.
        val stop = enabled && (snap.isCharging || chargeLimitStopped) &&
            nextChargeLimitStopped(snap.percent, limit, chargeLimitStopped)
        if (stop != chargeLimitStopped) writeChargeLimit(node, stop, limit)
    }

    private fun writeChargeLimit(node: ChargeLimitNode, stop: Boolean, limit: Int) {
        val value = if (stop) node.stopValue ?: limit.coerceIn(50, 100).toString() else node.resumeValue
        if (!SysfsTuningExecutor.write(node.path, value).isSuccess) return
        chargeLimitStopped = stop
        prefs.edit().apply {
            if (stop) putString(PREF_CHARGE_LIMIT_STOPPED, node.path) else remove(PREF_CHARGE_LIMIT_STOPPED)
        }.apply()
    }

    // ------------------------------------------------------------------
    // History persistence
    // ------------------------------------------------------------------

    /**
     * Writes the finished session as a history record on a background coroutine.
     * Skipped for sessions under 30 s or with no % change in the expected direction.
     */
    private fun persistSessionLocked(endedSessionType: SessionType, endTimeMs: Long, endPercent: Int) {
        val totalMs = endTimeMs - sessionStartTimeMs
        val percentDelta = when (endedSessionType) {
            SessionType.CHARGING -> endPercent - sessionStartPercent
            SessionType.DISCHARGING -> sessionStartPercent - endPercent
        }
        if (totalMs < 30_000L || percentDelta <= 0) return

        val screenOn = accumulatedScreenOnMs
        val screenOff = accumulatedScreenOffMs
        val deepSleep = deepSleepMsLocked().coerceAtMost(screenOff)
        val awake = max(0L, totalMs - deepSleep)
        val totals = drainTotalsLocked(endTimeMs, endPercent)
        val onDrainPct = totals.onDrop.toFloat()
        val offDrainPct = totals.offDrop.toFloat()
        val deepSleepDrainPct = if (screenOff > 0 && offDrainPct > 0f) {
            offDrainPct * (deepSleep.toFloat() / screenOff)
        } else 0f
        val awakeDrainPct = (onDrainPct + offDrainPct - deepSleepDrainPct).coerceAtLeast(0f)
        val (activeDrain, idleDrain, _) = drainRatesLocked(totals, endTimeMs, endPercent)

        val record = historyManager.buildRecord(
            sessionType = endedSessionType,
            startTimeMs = sessionStartTimeMs,
            endTimeMs = endTimeMs,
            startPercent = sessionStartPercent,
            endPercent = endPercent,
            screenOnTimeMs = screenOn,
            screenOffTimeMs = screenOff,
            deepSleepTimeMs = deepSleep,
            awakeTimeMs = awake,
            screenOnDrainPercent = onDrainPct,
            screenOffDrainPercent = offDrainPct,
            deepSleepDrainPercent = deepSleepDrainPct,
            awakeDrainPercent = awakeDrainPct,
            activeDrainPerHr = activeDrain,
            idleDrainPerHr = idleDrain,
            avgCurrentMa = if (sampleCount > 0) (currentSumMa / sampleCount).toInt() else 0,
            avgWattageW = if (sampleCount > 0) (wattageSumW / sampleCount).toFloat() else 0f,
            avgTemperatureC = if (sampleCount > 0) (temperatureSumC / sampleCount).toFloat() else 0f,
            ratedCapacityMah = lastSnapshot?.ratedCapacityMah ?: 0,
            estimatedCapacityMah = lastSnapshot?.estimatedCapacityMah ?: 0
        )
        historyScope.launch { historyManager.add(record) }
    }
}

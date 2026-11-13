package com.ivarna.mkm.service

import android.content.Context
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Owns the Android thermal listener and the low-frequency safety poll for Game Boost.
 * The poll also reads CPU thermal zones directly, because "disable throttling"
 * stops the thermal HAL and leaves PowerManager stuck at NONE.
 */
class GameBoostThermalGuard(
    context: Context,
    private val scope: CoroutineScope,
    private val onUnavailable: suspend () -> Unit = {},
    private val onStatus: suspend (Int) -> Unit
) {
    data class StartResult(val monitoringAvailable: Boolean, val listenerRegistered: Boolean)

    private val appContext = context.applicationContext
    private var powerManager: PowerManager? = null
    private var listener: PowerManager.OnThermalStatusChangedListener? = null

    /** Listener registration is optional; polling is the mandatory safety path. */
    fun start(): StartResult {
        val power = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appContext.getSystemService(PowerManager::class.java)
        } else null
        powerManager = power

        var listenerRegistered = false
        if (power != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val callback = PowerManager.OnThermalStatusChangedListener { status ->
                    scope.launch { onStatus(status) }
                }
                power.addThermalStatusListener(appContext.mainExecutor, callback)
                listener = callback
                listenerRegistered = true
            }
        }

        val initialStatus = pollStatus(power)
        // Pre-Q devices have no HAL signal; keep the old "assume available" contract.
        if (initialStatus == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return StartResult(listenerRegistered, listenerRegistered)
        }
        scope.launch {
            initialStatus?.let { onStatus(it) }
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                val status = pollStatus(power)
                if (status == null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        onUnavailable()
                        break
                    }
                    continue
                }
                onStatus(status)
            }
        }
        return StartResult(true, listenerRegistered)
    }

    /** Max of the HAL status and the CPU-zone status; null when neither source works. */
    private fun pollStatus(power: PowerManager?): Int? {
        val hal = if (power != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { power.currentThermalStatus }.getOrNull()
        } else null
        val headroomBroken = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && power != null &&
            runCatching { power.getThermalHeadroom(HEADROOM_FORECAST_S).isNaN() }.getOrDefault(true)
        val zones = zoneThermalStatus()
        return when {
            zones != null -> maxOf(zones, if (headroomBroken) 0 else hal ?: 0)
            else -> hal
        }
    }

    fun stop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            listener?.let { powerManager?.removeThermalStatusListener(it) }
        }
        listener = null
        powerManager = null
    }

    companion object {
        private const val POLL_INTERVAL_MS = 5_000L
        private const val HEADROOM_FORECAST_S = 0
        const val STATUS_SEVERE = 3 // PowerManager.THERMAL_STATUS_SEVERE
        private const val STATUS_NONE = 0
        /** CPU zone temperature treated as SEVERE (milli-degrees Celsius). */
        const val SEVERE_CPU_MILLI_C = 90_000

        @Volatile private var cpuZones: List<File>? = null

        /** Maps the hottest CPU zone to a thermal status; null when no CPU zone is readable. */
        fun zoneThermalStatus(): Int? = hottestCpuZoneMilliC()?.let { statusForMilliC(it) }

        fun statusForMilliC(milliC: Int): Int = if (milliC >= SEVERE_CPU_MILLI_C) STATUS_SEVERE else STATUS_NONE

        /** Zone types like "cpu-0-0", "cpu_big", "mtktscpu", "cpuss-0"; values in milli-degrees. */
        fun isCpuZoneType(type: String): Boolean = type.trim().lowercase().let { it.startsWith("cpu") || it.contains("tscpu") }

        /** Normalizes zones that report whole degrees; ignores obviously bogus values. */
        fun normalizeMilliC(raw: Int): Int? = when {
            raw in 1..200 -> raw * 1000
            raw in 1_000..200_000 -> raw
            else -> null
        }

        private fun hottestCpuZoneMilliC(): Int? {
            val zones = cpuZones ?: (File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }
                .orEmpty()
                .filter { zone -> runCatching { isCpuZoneType(File(zone, "type").readText()) }.getOrDefault(false) }
                .map { File(it, "temp") }
                .also { cpuZones = it })
            return zones.mapNotNull { runCatching { normalizeMilliC(it.readText().trim().toInt()) }.getOrNull() }.maxOrNull()
        }
    }
}

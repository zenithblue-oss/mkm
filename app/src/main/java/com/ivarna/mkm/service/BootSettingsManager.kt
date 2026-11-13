package com.ivarna.mkm.service

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Manages saving and loading of kernel settings that should be applied on boot.
 * Uses SharedPreferences for persistence — decoupled from UI ViewModels.
 */
object BootSettingsManager {

    private const val PREFS_NAME = "mkm_boot_settings"

    // Global toggle
    private const val PREF_ANY_ENABLED = "boot_any_enabled"

    // CPU
    private const val PREF_CPU_ENABLED = "boot_cpu_enabled"
    private const val PREF_CPU_GOVERNOR_PREFIX = "boot_cpu_governor_"
    private const val PREF_CPU_MAX_FREQ_PREFIX = "boot_cpu_max_freq_"
    private const val PREF_CPU_MIN_FREQ_PREFIX = "boot_cpu_min_freq_"

    // GPU
    private const val PREF_GPU_ENABLED = "boot_gpu_enabled"
    private const val PREF_GPU_GOVERNOR = "boot_gpu_governor"
    private const val PREF_GPU_MAX_FREQ = "boot_gpu_max_freq"
    private const val PREF_GPU_MIN_FREQ = "boot_gpu_min_freq"
    private const val PREF_GPU_TARGET_FREQ = "boot_gpu_target_freq"

    // RAM (DDR devfreq)
    private const val PREF_RAM_ENABLED = "boot_ram_enabled"
    private const val PREF_RAM_DEVFREQ_PATH = "boot_ram_devfreq_path"
    private const val PREF_RAM_DEVFREQ_GOVERNOR = "boot_ram_devfreq_governor"
    private const val PREF_RAM_DEVFREQ_FREQ = "boot_ram_devfreq_freq"

    // Storage (UFS)
    private const val PREF_STORAGE_ENABLED = "boot_storage_enabled"
    private const val PREF_STORAGE_UFS_PATH = "boot_storage_ufs_path"
    private const val PREF_STORAGE_UFS_GOVERNOR = "boot_storage_ufs_governor"
    private const val PREF_STORAGE_UFS_MIN_FREQ = "boot_storage_ufs_min_freq"
    private const val PREF_STORAGE_UFS_MAX_FREQ = "boot_storage_ufs_max_freq"

    // Game Boost
    private const val PREF_GAME_BOOST_ENABLED = "boot_game_boost_enabled"

    // Boot safe mode (bootloop guard)
    private const val PREF_APPLY_IN_PROGRESS = "boot_apply_in_progress"
    private const val PREF_APPLY_ATTEMPTS = "boot_apply_attempts"

    const val PREFS_FILE = PREFS_NAME
    private val ENABLE_KEYS = listOf(
        PREF_CPU_ENABLED, PREF_GPU_ENABLED, PREF_RAM_ENABLED, PREF_STORAGE_ENABLED, PREF_GAME_BOOST_ENABLED
    )

    // ---- helpers --------------------------------------------------------

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---- Boot safe mode -------------------------------------------------

    /** Marks an apply as started. Committed synchronously: a crash/reboot may follow. */
    fun markApplyStarted(context: Context): Int {
        val p = prefs(context)
        val attempts = BootSafeMode.nextAttempts(p.getInt(PREF_APPLY_ATTEMPTS, 0))
        p.edit().putBoolean(PREF_APPLY_IN_PROGRESS, true).putInt(PREF_APPLY_ATTEMPTS, attempts).commit()
        return attempts
    }

    /** Called after the post-apply stability window: the device survived the apply. */
    fun markApplyStable(context: Context) {
        prefs(context).edit().putBoolean(PREF_APPLY_IN_PROGRESS, false).putInt(PREF_APPLY_ATTEMPTS, 0).commit()
    }

    fun isApplyInProgress(context: Context): Boolean = prefs(context).getBoolean(PREF_APPLY_IN_PROGRESS, false)

    fun applyAttempts(context: Context): Int = prefs(context).getInt(PREF_APPLY_ATTEMPTS, 0)

    /** Safe mode trip: turn every apply-on-boot category off and clear the guard. */
    fun disableAllAndReset(context: Context) {
        prefs(context).edit().apply {
            ENABLE_KEYS.forEach { putBoolean(it, false) }
            putBoolean(PREF_APPLY_IN_PROGRESS, false)
            putInt(PREF_APPLY_ATTEMPTS, 0)
        }.commit()
    }

    fun hasAnyEnabled(context: Context): Boolean {
        val p = prefs(context)
        return p.getBoolean(PREF_CPU_ENABLED, false) ||
                p.getBoolean(PREF_GPU_ENABLED, false) ||
                p.getBoolean(PREF_RAM_ENABLED, false) ||
                p.getBoolean(PREF_STORAGE_ENABLED, false) ||
                p.getBoolean(PREF_GAME_BOOST_ENABLED, false)
    }

    // ---- Game Boost -----------------------------------------------------

    fun setGameBoostEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(PREF_GAME_BOOST_ENABLED, enabled) }
    }

    fun isGameBoostEnabled(context: Context): Boolean =
        prefs(context).getBoolean(PREF_GAME_BOOST_ENABLED, false)

    // ---- CPU ------------------------------------------------------------

    fun setCpuEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(PREF_CPU_ENABLED, enabled) }
    }

    fun isCpuEnabled(context: Context): Boolean =
        prefs(context).getBoolean(PREF_CPU_ENABLED, false)

    fun saveCpuPolicy(context: Context, policyId: Int, governor: String, maxFreq: String, minFreq: String) {
        prefs(context).edit {
            putString("$PREF_CPU_GOVERNOR_PREFIX$policyId", governor)
            putString("$PREF_CPU_MAX_FREQ_PREFIX$policyId", maxFreq)
            putString("$PREF_CPU_MIN_FREQ_PREFIX$policyId", minFreq)
        }
    }

    data class CpuPolicySetting(val policyId: Int, val governor: String, val maxFreq: String, val minFreq: String)

    fun loadCpuPolicies(context: Context): List<CpuPolicySetting> {
        val p = prefs(context)
        val result = mutableListOf<CpuPolicySetting>()
        // We don't know how many policies exist; scan keys.
        p.all.keys.filter { it.startsWith(PREF_CPU_GOVERNOR_PREFIX) }.forEach { key ->
            val id = key.removePrefix(PREF_CPU_GOVERNOR_PREFIX).toIntOrNull() ?: return@forEach
            val gov = p.getString(key, "") ?: ""
            val max = p.getString("$PREF_CPU_MAX_FREQ_PREFIX$id", "") ?: ""
            val min = p.getString("$PREF_CPU_MIN_FREQ_PREFIX$id", "") ?: ""
            if (gov.isNotBlank()) {
                result.add(CpuPolicySetting(id, gov, max, min))
            }
        }
        return result
    }

    // ---- GPU ------------------------------------------------------------

    fun setGpuEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(PREF_GPU_ENABLED, enabled) }
    }

    fun isGpuEnabled(context: Context): Boolean =
        prefs(context).getBoolean(PREF_GPU_ENABLED, false)

    fun saveGpuSettings(context: Context, governor: String, maxFreq: String, minFreq: String, targetFreq: String) {
        prefs(context).edit {
            putString(PREF_GPU_GOVERNOR, governor)
            putString(PREF_GPU_MAX_FREQ, maxFreq)
            putString(PREF_GPU_MIN_FREQ, minFreq)
            putString(PREF_GPU_TARGET_FREQ, targetFreq)
        }
    }

    data class GpuSetting(val governor: String, val maxFreq: String, val minFreq: String, val targetFreq: String)

    fun loadGpuSettings(context: Context): GpuSetting? {
        val p = prefs(context)
        val gov = p.getString(PREF_GPU_GOVERNOR, "") ?: ""
        return if (gov.isNotBlank()) {
            GpuSetting(
                governor = gov,
                maxFreq = p.getString(PREF_GPU_MAX_FREQ, "") ?: "",
                minFreq = p.getString(PREF_GPU_MIN_FREQ, "") ?: "",
                targetFreq = p.getString(PREF_GPU_TARGET_FREQ, "") ?: ""
            )
        } else null
    }

    // ---- RAM ------------------------------------------------------------

    fun setRamEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(PREF_RAM_ENABLED, enabled) }
    }

    fun isRamEnabled(context: Context): Boolean =
        prefs(context).getBoolean(PREF_RAM_ENABLED, false)

    fun saveRamSettings(context: Context, controllerPath: String, governor: String, freq: String) {
        prefs(context).edit {
            putString(PREF_RAM_DEVFREQ_PATH, controllerPath)
            putString(PREF_RAM_DEVFREQ_GOVERNOR, governor)
            putString(PREF_RAM_DEVFREQ_FREQ, freq)
        }
    }

    data class RamSetting(val controllerPath: String, val governor: String, val freq: String)

    fun loadRamSettings(context: Context): RamSetting? {
        val p = prefs(context)
        val path = p.getString(PREF_RAM_DEVFREQ_PATH, "") ?: ""
        val gov = p.getString(PREF_RAM_DEVFREQ_GOVERNOR, "") ?: ""
        return if (path.isNotBlank() && gov.isNotBlank()) {
            RamSetting(path, gov, p.getString(PREF_RAM_DEVFREQ_FREQ, "") ?: "")
        } else null
    }

    // ---- Storage --------------------------------------------------------

    fun setStorageEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(PREF_STORAGE_ENABLED, enabled) }
    }

    fun isStorageEnabled(context: Context): Boolean =
        prefs(context).getBoolean(PREF_STORAGE_ENABLED, false)

    fun saveStorageSettings(context: Context, controllerPath: String, governor: String, minFreq: String, maxFreq: String) {
        prefs(context).edit {
            putString(PREF_STORAGE_UFS_PATH, controllerPath)
            putString(PREF_STORAGE_UFS_GOVERNOR, governor)
            putString(PREF_STORAGE_UFS_MIN_FREQ, minFreq)
            putString(PREF_STORAGE_UFS_MAX_FREQ, maxFreq)
        }
    }

    data class StorageSetting(val controllerPath: String, val governor: String, val minFreq: String, val maxFreq: String)

    fun loadStorageSettings(context: Context): StorageSetting? {
        val p = prefs(context)
        val path = p.getString(PREF_STORAGE_UFS_PATH, "") ?: ""
        val gov = p.getString(PREF_STORAGE_UFS_GOVERNOR, "") ?: ""
        return if (path.isNotBlank() && gov.isNotBlank()) {
            StorageSetting(
                path, gov,
                p.getString(PREF_STORAGE_UFS_MIN_FREQ, "") ?: "",
                p.getString(PREF_STORAGE_UFS_MAX_FREQ, "") ?: ""
            )
        } else null
    }
}

/** Pure bootloop-guard rules (unit tested). */
object BootSafeMode {
    /** Stability window after apply completes before the in-progress flag is cleared. */
    const val STABILITY_WINDOW_MS = 60_000L

    fun nextAttempts(previous: Int): Int = (previous.coerceAtLeast(0) + 1).coerceAtMost(99)

    /** A flag still set at boot means the last apply never reached its stability window. */
    fun shouldTrip(applyInProgress: Boolean, attempts: Int): Boolean = applyInProgress && attempts > 0
}

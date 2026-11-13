package com.ivarna.mkm.data.provider

import android.content.Context
import android.content.SharedPreferences

class PowerCalibrationManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("power_calibration_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_MULTIPLIER = "power_multiplier"
        private const val KEY_UPDATE_INTERVAL = "update_interval_ms"
        private const val DEFAULT_UPDATE_INTERVAL = 1000L // 1 second
        const val MIN_MULTIPLIER = 0.1f
        const val MAX_MULTIPLIER = 1000f

        /** Parses user input ("1,5" or "1.5"); null when not a finite number. Result is clamped. */
        fun parseMultiplier(text: String): Float? =
            text.trim().replace(',', '.').toFloatOrNull()
                ?.takeIf { it.isFinite() && it > 0f }
                ?.coerceIn(MIN_MULTIPLIER, MAX_MULTIPLIER)
    }

    fun getMultiplier(): Float {
        val m = prefs.getFloat(KEY_MULTIPLIER, 1.0f)
        return if (m.isFinite()) m.coerceIn(MIN_MULTIPLIER, MAX_MULTIPLIER) else 1.0f
    }

    fun saveMultiplier(multiplier: Float) {
        val safe = if (multiplier.isFinite()) multiplier.coerceIn(MIN_MULTIPLIER, MAX_MULTIPLIER) else 1.0f
        prefs.edit().putFloat(KEY_MULTIPLIER, safe).commit()
    }
    
    fun getUpdateInterval(): Long {
        return prefs.getLong(KEY_UPDATE_INTERVAL, DEFAULT_UPDATE_INTERVAL)
    }
    
    fun saveUpdateInterval(intervalMs: Long) {
        prefs.edit().putLong(KEY_UPDATE_INTERVAL, intervalMs).apply()
    }
}

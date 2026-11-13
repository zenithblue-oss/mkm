package com.ivarna.mkm.utils

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import com.ivarna.mkm.MainActivity
import com.ivarna.mkm.R
import com.ivarna.mkm.data.model.BatteryStats
import com.ivarna.mkm.service.BatteryMonitorService

/**
 * Manages a persistent notification card showing battery statistics.
 *
 * Decoupled from UI and tracker — only knows how to build & post notifications.
 * All text comes from string resources; numbers are formatted with the current locale.
 */
class BatteryNotificationManager(context: Context) {

    private val appContext = context.applicationContext
    private val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val prefs = appContext.getSharedPreferences(BatteryMonitorService.PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        const val CHANNEL_ID = "mkm_battery_channel"
        const val NOTIFICATION_ID = 2001
        const val CHARGE_LIMIT_NOTIFICATION_ID = 2002
        private const val SEP = " · "
    }

    init {
        createChannel()
    }

    private fun s(@StringRes id: Int, vararg args: Any): String = appContext.getString(id, *args)

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                s(R.string.battery_monitor),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = s(R.string.notif_channel_desc)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Builds a [Notification] for the given stats without posting it.
     * Useful for [android.app.Service.startForeground].
     */
    fun buildNotification(stats: BatteryStats): Notification {
        val style = NotificationCompat.BigTextStyle()
            .bigText(buildContentText(stats))
            .setSummaryText(buildSubText(stats))

        return NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_battery)
            .setContentTitle(buildTitle(stats))
            .setContentText(buildOneLine(stats))
            .setSubText(buildSubText(stats))
            .setNumber(stats.percent)
            .setContentIntent(openBatteryPendingIntent())
            .setStyle(style)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
    }

    /**
     * Builds a placeholder notification for use before the first stats are ready.
     */
    fun buildPlaceholderNotification(): Notification {
        return NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_battery)
            .setContentTitle(s(R.string.battery_monitor))
            .setContentText(s(R.string.notif_initializing))
            .setContentIntent(openBatteryPendingIntent())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
    }

    /**
     * Posts or updates the battery notification.
     */
    fun show(stats: BatteryStats) {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(stats))
    }

    /** One-shot alert used when no writable charge-control node exists. */
    fun showChargeLimitReached(percent: Int) {
        val n = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_battery)
            .setContentTitle(s(R.string.charge_limit_reached_title))
            .setContentText(s(R.string.charge_limit_reached_text, percent))
            .setContentIntent(openBatteryPendingIntent())
            .setAutoCancel(true)
            .build()
        runCatching { notificationManager.notify(CHARGE_LIMIT_NOTIFICATION_ID, n) }
    }

    /**
     * Cancels the battery notification.
     */
    fun hide() {
        notificationManager.cancel(NOTIFICATION_ID)
    }

    /** Signed: plugged but discharging shows "−". */
    private fun chargingNow(stats: BatteryStats) = stats.isCharging && stats.currentMa >= 0

    private fun buildTitle(stats: BatteryStats): String {
        val parts = mutableListOf(s(R.string.notif_percent_format, stats.percent))

        if (prefs.getBoolean(BatteryMonitorService.PREF_NOTIF_SHOW_WATTAGE, true) && stats.calibratedWattageW != 0f) {
            parts.add(s(R.string.wattage_signed_format, stats.calibratedWattageW))
        }
        if (prefs.getBoolean(BatteryMonitorService.PREF_NOTIF_SHOW_TEMPERATURE, false)) {
            parts.add(s(R.string.temperature_format, stats.temperatureC))
        }
        if (prefs.getBoolean(BatteryMonitorService.PREF_NOTIF_SHOW_DRAIN, false)) {
            if (stats.isCharging) {
                // Show charge gain rate (%/hr) instead of drain rate during charging
                val sessionHours = stats.totalSessionTimeMs / 3_600_000f
                val gainRatePerHr = if (sessionHours > 0.001f && stats.chargingGainedPercent > 0)
                    stats.chargingGainedPercent / sessionHours else 0f
                if (gainRatePerHr > 0f) parts.add(s(R.string.notif_gain_rate_format, gainRatePerHr))
            } else if (stats.isSessionActive) {
                parts.add(s(R.string.notif_rate_format, stats.activeDrainPerHr))
            }
        }
        if (prefs.getBoolean(BatteryMonitorService.PREF_NOTIF_SHOW_TIME_LEFT, false) && stats.estimatedTimeRemainingMin > 0) {
            parts.add(formatMinutes(stats.estimatedTimeRemainingMin))
        }
        if (prefs.getBoolean(BatteryMonitorService.PREF_NOTIF_SHOW_CURRENT, false)) {
            parts.add(s(R.string.notif_ma_format, currentMagnitudeMa(stats)))
        }
        if (prefs.getBoolean(BatteryMonitorService.PREF_NOTIF_SHOW_VOLTAGE, false)) {
            parts.add(s(R.string.notif_mv_format, stats.voltageMv))
        }

        return parts.joinToString(SEP)
    }

    private fun buildOneLine(stats: BatteryStats): String {
        val temp = s(R.string.temperature_format, stats.temperatureC)
        return when {
            chargingNow(stats) -> {
                val powerW = chargingPowerW(stats)
                val parts = mutableListOf(s(R.string.charging), temp)
                if (powerW > 0f) parts.add(s(R.string.wattage_signed_format, powerW))
                val currentMa = kotlin.math.abs(stats.currentMa)
                if (currentMa > 50) parts.add(s(R.string.notif_ma_format, currentMa))
                if (stats.chargingGainedPercent > 0) parts.add(s(R.string.notif_gain_format, stats.chargingGainedPercent))
                parts.joinToString(SEP)
            }
            stats.isSessionActive -> s(R.string.discharging) + SEP + temp
            else -> s(R.string.on_ac) + SEP + temp
        }
    }

    private fun buildSubText(stats: BatteryStats): String {
        return when {
            chargingNow(stats) -> {
                val gained = if (stats.chargingGainedPercent > 0) s(R.string.notif_gained_format, stats.chargingGainedPercent)
                             else s(R.string.charging)
                if (stats.estimatedTimeRemainingMin > 0) {
                    gained + SEP + s(R.string.notif_to_full_format, formatMinutes(stats.estimatedTimeRemainingMin))
                } else gained
            }
            stats.isSessionActive -> {
                val drain = s(R.string.notif_drain_format, stats.activeDrainPerHr)
                if (stats.estimatedTimeRemainingMin > 0) {
                    drain + SEP + s(R.string.notif_left_format, formatMinutes(stats.estimatedTimeRemainingMin))
                } else drain
            }
            else -> s(R.string.on_ac)
        }
    }

    private fun buildContentText(stats: BatteryStats): String {
        val lines = mutableListOf<String>()
        if (chargingNow(stats)) {
            // Rich charging session content — each line gated by its own pref
            val powerW = chargingPowerW(stats)
            val currentMa = currentMagnitudeMa(stats)

            if (prefs.getBoolean(BatteryMonitorService.PREF_NOTIF_CHG_SHOW_TEMP, true)) {
                lines.add(s(R.string.notif_temp_voltage_format, stats.temperatureC, stats.voltageMv))
            }
            if (prefs.getBoolean(BatteryMonitorService.PREF_NOTIF_CHG_SHOW_POWER, true) && powerW > 0f) {
                lines.add(s(R.string.notif_charging_power_format, powerW))
            }
            if (prefs.getBoolean(BatteryMonitorService.PREF_NOTIF_CHG_SHOW_CURRENT, true)) {
                if (currentMa > 0) lines.add(s(R.string.notif_charge_current_format, currentMa))
                if (stats.chargingAvgCurrentMa > 0) lines.add(s(R.string.notif_avg_current_format, stats.chargingAvgCurrentMa))
            }
            if (prefs.getBoolean(BatteryMonitorService.PREF_NOTIF_CHG_SHOW_GAINED, true) && stats.chargingGainedPercent > 0) {
                lines.add(s(R.string.notif_gained_detail_format, stats.chargingGainedPercent, stats.chargingSessionStartPercent, stats.percent))
            }
            if (prefs.getBoolean(BatteryMonitorService.PREF_NOTIF_CHG_SHOW_DURATION, true) && stats.totalSessionTimeMs > 0) {
                lines.add(s(R.string.notif_charging_for_format, formatDuration(stats.totalSessionTimeMs)))
            }
            if (prefs.getBoolean(BatteryMonitorService.PREF_NOTIF_CHG_SHOW_ETA, true) && stats.estimatedTimeRemainingMin > 0) {
                lines.add(s(R.string.notif_est_full_format, formatMinutes(stats.estimatedTimeRemainingMin)))
            }
        } else if (stats.isSessionActive) {
            // Discharging session content
            val showMah = prefs.getBoolean(BatteryMonitorService.PREF_NOTIF_EXP_SHOW_MAH, false)
            if (prefs.getBoolean(BatteryMonitorService.PREF_NOTIF_EXP_TEMP_VOLTAGE, true)) {
                lines.add(s(R.string.notif_temp_voltage_format, stats.temperatureC, stats.voltageMv))
            }
            if (prefs.getBoolean(BatteryMonitorService.PREF_NOTIF_EXP_POWER, true)) {
                lines.add(s(R.string.notif_power_format, stats.calibratedWattageW))
            }
            if (prefs.getBoolean(BatteryMonitorService.PREF_NOTIF_EXP_DRAIN, true)) {
                lines.add(s(R.string.notif_drain_detail_format, stats.activeDrainPerHr, stats.idleDrainPerHr))
            }
            if (prefs.getBoolean(BatteryMonitorService.PREF_NOTIF_EXP_TIME_LEFT, true) && stats.estimatedTimeRemainingMin > 0) {
                lines.add(s(R.string.notif_time_left_format, formatMinutes(stats.estimatedTimeRemainingMin)))
            }
            if (prefs.getBoolean(BatteryMonitorService.PREF_NOTIF_EXP_SCREEN_ON, true)) {
                lines.add(s(R.string.notif_screen_on_format, formatDuration(stats.screenOnTimeMs), stats.screenOnDrainPercent) +
                    mahStr(stats.screenOnDrainPercent, stats, showMah))
            }
            if (prefs.getBoolean(BatteryMonitorService.PREF_NOTIF_EXP_SCREEN_OFF, true)) {
                lines.add(s(R.string.notif_screen_off_format, formatDuration(stats.screenOffTimeMs), stats.screenOffDrainPercent) +
                    mahStr(stats.screenOffDrainPercent, stats, showMah))
            }
            if (prefs.getBoolean(BatteryMonitorService.PREF_NOTIF_EXP_DEEP_SLEEP, true)) {
                lines.add(s(R.string.notif_deep_sleep_format, formatDuration(stats.deepSleepTimeMs), stats.deepSleepDrainPercent) +
                    mahStr(stats.deepSleepDrainPercent, stats, showMah))
            }
            if (prefs.getBoolean(BatteryMonitorService.PREF_NOTIF_EXP_AWAKE, true)) {
                lines.add(s(R.string.notif_awake_format, formatDuration(stats.awakeTimeMs), stats.awakeDrainPercent) +
                    mahStr(stats.awakeDrainPercent, stats, showMah))
            }
        }
        return lines.joinToString("\n")
    }

    private fun chargingPowerW(stats: BatteryStats): Float =
        if (stats.calibratedWattageW != 0f) stats.calibratedWattageW
        else if (stats.wattageW != 0f) stats.wattageW else 0f

    /** |current|, derived from wattage + voltage when the sensor reports 0. */
    private fun currentMagnitudeMa(stats: BatteryStats): Int {
        val currentMa = kotlin.math.abs(stats.currentMa)
        if (currentMa == 0 && stats.voltageMv > 0 && stats.wattageW != 0f) {
            return ((kotlin.math.abs(stats.wattageW) * 1_000f) / stats.voltageMv).toInt()
        }
        return currentMa
    }

    /**
     * Returns a " · ~X mAh" suffix if showMah is true and capacity is known.
     */
    private fun mahStr(drainPercent: Float, stats: BatteryStats, showMah: Boolean): String {
        if (!showMah || drainPercent <= 0f) return ""
        val capacity = if (stats.estimatedCapacityMah > 0) stats.estimatedCapacityMah
                       else if (stats.ratedCapacityMah > 0) stats.ratedCapacityMah
                       else return ""
        val mah = (drainPercent / 100f * capacity).toInt()
        return SEP + s(R.string.notif_mah_format, mah)
    }

    private fun openBatteryPendingIntent(): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            action = BatteryMonitorService.ACTION_OPEN_BATTERY
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(appContext, 0, intent, flags)
    }

    private fun formatMinutes(totalMinutes: Long): String {
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        return if (h > 0) s(R.string.notif_duration_hm, h, m) else s(R.string.notif_duration_m, m)
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            s(R.string.notif_duration_hms, hours, minutes, seconds)
        } else {
            s(R.string.notif_duration_ms, minutes, seconds)
        }
    }
}

package com.ivarna.mkm.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ivarna.mkm.R
import com.ivarna.mkm.data.model.ApplyResult
import com.ivarna.mkm.data.provider.CpuProvider
import com.ivarna.mkm.data.provider.GpuProvider
import android.app.PendingIntent
import com.ivarna.mkm.shell.SysfsTuningExecutor
import kotlinx.coroutines.*

/**
 * Foreground service that shows a 10-second countdown notification after boot,
 * then applies all kernel settings that the user has marked for "apply on boot".
 *
 * Started by [BootReceiver] when ACTION_BOOT_COMPLETED is received and at least
 * one category is enabled.
 */
class BootApplyService : Service() {

    companion object {
        const val ACTION_START = "com.ivarna.mkm.action.START_BOOT_APPLY"
        const val CHANNEL_ID = "mkm_boot_channel"
        const val NOTIFICATION_ID = 3001
        const val ACTION_CANCEL = "com.ivarna.mkm.action.CANCEL_BOOT_APPLY"
        const val COUNTDOWN_SECONDS = 10
        private const val SAFE_MODE_NOTIFICATION_ID = 3003
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var applyJob: Job? = null
    @Volatile private var applying = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (applyJob?.isActive != true) startCountdownAndApply()
            ACTION_CANCEL -> if (!applying) {
                // Countdown only: nothing has been written yet.
                applyJob?.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        applyJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Countdown + apply logic
    // ------------------------------------------------------------------

    private fun startCountdownAndApply() {
        val notification = buildCountdownNotification(COUNTDOWN_SECONDS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Boot safe mode: the previous apply never reached its stability window
        // (crash or reboot mid-apply). Do not replay it; turn apply-on-boot off.
        val attempts = BootSettingsManager.applyAttempts(this)
        if (BootSafeMode.shouldTrip(BootSettingsManager.isApplyInProgress(this), attempts)) {
            BootSettingsManager.disableAllAndReset(this)
            stopForeground(STOP_FOREGROUND_REMOVE)
            notifySafeMode(attempts)
            stopSelf()
            return
        }

        applyJob = serviceScope.launch {
            for (secondsRemaining in COUNTDOWN_SECONDS downTo 1) {
                updateNotification(secondsRemaining)
                delay(1000L)
            }

            applying = true
            BootSettingsManager.markApplyStarted(this@BootApplyService)
            val summary = applyAllSettings()
            updateNotificationDone(summary)
            delay(BootSafeMode.STABILITY_WINDOW_MS)
            BootSettingsManager.markApplyStable(this@BootApplyService)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private data class ApplySummary(
        val failures: List<String> = emptyList(),
        val adjustments: List<String> = emptyList()
    )

    private suspend fun applyAllSettings(): ApplySummary {
        val failures = mutableListOf<String>()
        val adjustments = mutableListOf<String>()
        withContext(Dispatchers.IO) {
            KernelTuningCoordinator.withMutation {
            // CPU
            if (BootSettingsManager.isCpuEnabled(this@BootApplyService)) {
                val policies = BootSettingsManager.loadCpuPolicies(this@BootApplyService)
                policies.forEach { policy ->
                    if (policy.governor.isNotBlank()) {
                        when (val result = CpuProvider.applyGovernor(policy.policyId, policy.governor)) {
                            is ApplyResult.Failed -> failures += "CPU policy${policy.policyId} governor"
                            is ApplyResult.Adjusted -> adjustments += "CPU policy${policy.policyId} governor (${result.actual})"
                            is ApplyResult.Applied -> Unit
                        }
                    }
                    val min = policy.minFreq.toLongOrNull()
                    val max = policy.maxFreq.toLongOrNull()
                    if (min != null || max != null) {
                        val result = CpuProvider.applyRange(policy.policyId, min, max)
                        when (result) {
                            is ApplyResult.Failed -> failures += "CPU policy${policy.policyId} frequency"
                            is ApplyResult.Adjusted -> adjustments += "CPU policy${policy.policyId} frequency (${result.actual})"
                            is ApplyResult.Applied -> Unit
                        }
                    }
                }
            }

            // GPU
            if (BootSettingsManager.isGpuEnabled(this@BootApplyService)) {
                BootSettingsManager.loadGpuSettings(this@BootApplyService)?.let { gpu ->
                    if (gpu.governor.isNotBlank()) {
                        when (val result = GpuProvider.applyGovernor(gpu.governor)) {
                            is ApplyResult.Failed -> failures += "GPU governor"
                            is ApplyResult.Adjusted -> adjustments += "GPU governor (${result.actual})"
                            is ApplyResult.Applied -> Unit
                        }
                    }
                    val min = gpu.minFreq.toLongOrNull()
                    val max = gpu.maxFreq.toLongOrNull()
                    if (min != null || max != null) {
                        val result = GpuProvider.applyRange(min, max)
                        when (result) {
                            is ApplyResult.Failed -> failures += "GPU frequency"
                            is ApplyResult.Adjusted -> adjustments += "GPU frequency (${result.actual})"
                            is ApplyResult.Applied -> Unit
                        }
                    }
                    gpu.targetFreq.toLongOrNull()?.let { target ->
                        when (val result = GpuProvider.applyTarget(target)) {
                            is ApplyResult.Failed -> failures += "GPU target frequency"
                            is ApplyResult.Adjusted -> adjustments += "GPU target frequency (${result.actual})"
                            is ApplyResult.Applied -> Unit
                        }
                    }
                }
            }

            // RAM (DDR devfreq) and storage (UFS): only validated, read-back
            // writes through SysfsTuningExecutor (root first, never a raw script).
            if (BootSettingsManager.isRamEnabled(this@BootApplyService)) {
                BootSettingsManager.loadRamSettings(this@BootApplyService)?.let { ram ->
                    val path = ram.controllerPath
                    if (ram.governor.isNotBlank()) writeVerified("$path/governor", ram.governor, "RAM governor", failures, adjustments)
                    if (isSavedFreq(ram.freq)) {
                        val setFreq = "$path/userspace/set_freq"
                        if (ram.governor == "userspace" && SysfsTuningExecutor.exists(setFreq)) {
                            if (!SysfsTuningExecutor.write(setFreq, ram.freq).isSuccess) failures += "RAM frequency"
                        } else {
                            writeVerified("$path/max_freq", ram.freq, "RAM maximum frequency", failures, adjustments)
                            writeVerified("$path/min_freq", ram.freq, "RAM minimum frequency", failures, adjustments)
                        }
                    }
                }
            }

            if (BootSettingsManager.isStorageEnabled(this@BootApplyService)) {
                BootSettingsManager.loadStorageSettings(this@BootApplyService)?.let { storage ->
                    val path = storage.controllerPath
                    if (storage.governor.isNotBlank()) writeVerified("$path/governor", storage.governor, "Storage governor", failures, adjustments)
                    if (isSavedFreq(storage.maxFreq)) writeVerified("$path/max_freq", storage.maxFreq, "Storage maximum frequency", failures, adjustments)
                    if (isSavedFreq(storage.minFreq)) writeVerified("$path/min_freq", storage.minFreq, "Storage minimum frequency", failures, adjustments)
                }
            }

            // Memory (swap/zram/VM) boot tuning. MemoryBootTuning does not take
            // the (non-reentrant) coordinator lock, so it runs inside this one.
            failures += runCatching { MemoryBootTuning.apply(this@BootApplyService) }
                .getOrElse { listOf("Memory (${it.message})") }
            }

            // Game Boost (global performance session). enable() takes the
            // coordinator lock itself, so it must run after withMutation returns.
            if (BootSettingsManager.isGameBoostEnabled(this@BootApplyService)) {
                GameBoostService.start(this@BootApplyService)
                val result = if (!GameBoostService.awaitReady()) {
                    GameBoostTransitionResult.Failure("Game Boost foreground service could not start")
                } else GameBoostRegistry.manager(this@BootApplyService).enable()
                if (result is GameBoostTransitionResult.Failure) {
                    failures += "Game Boost (${result.reason})"
                }
            }
        }
        return ApplySummary(failures = failures, adjustments = adjustments)
    }

    /** Saved "0"/blank means "not set" for devfreq frequencies. */
    private fun isSavedFreq(value: String) = value.isNotBlank() && value.trim() != "0"

    private fun writeVerified(
        path: String,
        value: String,
        label: String,
        failures: MutableList<String>,
        adjustments: MutableList<String>
    ) {
        val result = SysfsTuningExecutor.write(path, value)
        if (!result.isSuccess) {
            failures += label
            return
        }
        val actual = SysfsTuningExecutor.read(path)
        when {
            actual.isBlank() -> failures += "$label (read-back unavailable)"
            actual != value -> adjustments += "$label ($actual)"
        }
    }

    // ------------------------------------------------------------------
    // Notification helpers
    // ------------------------------------------------------------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Boot Settings",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Countdown before applying kernel settings on boot"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildCountdownNotification(seconds: Int): Notification {
        val cancelIntent = PendingIntent.getService(
            this, 0,
            Intent(this, BootApplyService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_battery)
            .setContentTitle("MKM Boot Settings")
            .setContentText("Applying settings in ${seconds}s...")
            .setProgress(COUNTDOWN_SECONDS, COUNTDOWN_SECONDS - seconds, false)
            .addAction(0, getString(R.string.boot_apply_cancel), cancelIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
    }

    private fun updateNotification(secondsRemaining: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildCountdownNotification(secondsRemaining))
    }

    private fun notifySafeMode(attempts: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val text = getString(R.string.boot_safe_mode_text, attempts)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_battery)
            .setContentTitle(getString(R.string.boot_safe_mode_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        nm.notify(SAFE_MODE_NOTIFICATION_ID, notification)
    }

    private fun updateNotificationDone(summary: ApplySummary) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val text = when {
            summary.failures.isNotEmpty() -> "Some settings failed: ${summary.failures.joinToString()}"
            summary.adjustments.isNotEmpty() -> "Settings adjusted by kernel: ${summary.adjustments.joinToString()}"
            else -> "Settings applied successfully."
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_battery)
            .setContentTitle("MKM Boot Settings")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(false)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
        nm.notify(NOTIFICATION_ID, notification)
    }
}

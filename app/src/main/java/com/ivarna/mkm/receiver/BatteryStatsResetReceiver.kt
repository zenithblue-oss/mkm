package com.ivarna.mkm.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ivarna.mkm.shell.ShellManager
import com.ivarna.mkm.utils.BatteryStatsResetPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Resets the system battery stats (`dumpsys batterystats --reset`) on user-enabled triggers,
 * each gated by [BatteryStatsResetPrefs]:
 *   - boot: this manifest receiver ([Intent.ACTION_BOOT_COMPLETED]).
 *   - unplug / full: fired by [com.ivarna.mkm.service.BatterySessionTracker] via [resetStats].
 *     Manifest receivers never get ACTION_POWER_DISCONNECTED / BATTERY_CHANGED on Android 8+.
 *
 * Execution uses [ShellManager.exec] (shizuku → root → local). Without either backend the
 * reset silently fails (better than spamming toasts from BOOT_COMPLETED).
 */
class BatteryStatsResetReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!BatteryStatsResetPrefs.isOnBoot(context)) return
        val appContext = context.applicationContext
        val pending = goAsync()
        scope.launch {
            try {
                resetStats(appContext, "boot")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "BatteryStatsReset"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Blocking shell call — run off the main thread. */
        fun resetStats(context: Context, trigger: String): Boolean {
            val result = ShellManager.exec("dumpsys batterystats --reset")
            if (result.isSuccess) {
                Log.i(TAG, "battery stats reset OK (trigger=$trigger)")
                BatteryStatsResetPrefs.recordReset(context, trigger)
            } else {
                Log.w(TAG, "battery stats reset failed (trigger=$trigger, exit=${result.exitCode}, err=${result.stderr})")
            }
            return result.isSuccess
        }
    }
}

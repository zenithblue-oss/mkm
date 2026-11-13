package com.ivarna.mkm

import android.app.Application
import com.ivarna.mkm.shell.ShizukuManager
import com.ivarna.mkm.service.GameBoostRegistry
import com.ivarna.mkm.util.AppVisibilityMonitor
import com.topjohnwu.superuser.Shell
import rikka.shizuku.Shizuku

class MkmApplication : Application() {
    companion object {
        init {
            // Set settings before the main shell can be created
            Shell.enableVerboseLogging = BuildConfig.DEBUG
            Shell.setDefaultBuilder(
                Shell.Builder.create()
                    .setFlags(Shell.FLAG_REDIRECT_STDERR)
                    .setTimeout(10)
            )
        }
    }

    override fun onCreate() {
        super.onCreate()

        ShizukuManager.init(this)
        AppVisibilityMonitor.init(this)
        val gameBoost = GameBoostRegistry.manager(this)
        // The first reconcile can run before the Shizuku binder arrives and
        // report a false RecoveryRequired; re-check once the backend exists.
        ShizukuManager.addBinderReceivedListener(Shizuku.OnBinderReceivedListener { gameBoost.requestReconcile() })
    }
}

package com.ivarna.mkm.data.provider

import android.os.Build
import com.ivarna.mkm.data.model.SystemOverview
import com.ivarna.mkm.shell.ShizukuManager
import com.topjohnwu.superuser.Shell

object DeviceInfoProvider {
    fun getOverview(): SystemOverview {
        // uname release; SELinux denies apps /proc/version (audit spam on every poll)
        val kernelVersion = System.getProperty("os.version")?.split("-")?.getOrNull(0) ?: "Unknown"

        val isRoot = Shell.getShell().isRoot
        val isShizukuActive = ShizukuManager.isAvailable() && ShizukuManager.hasPermission()

        return SystemOverview(
            deviceName = "${Build.MANUFACTURER} ${Build.MODEL}",
            kernelVersion = kernelVersion,
            isShizukuActive = isShizukuActive,
            isRootActive = isRoot
        )
    }
}

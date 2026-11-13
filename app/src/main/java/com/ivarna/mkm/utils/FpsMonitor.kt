package com.ivarna.mkm.utils

import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import com.ivarna.mkm.data.model.FpsSource
import com.mkm.fps.FpsBinder
import com.ivarna.mkm.shell.ShellManager
import java.lang.ref.WeakReference

object FpsMonitor {

    private const val TAG = "FpsMonitor"

    /**
     * fps: >0 rendering, 0 valid idle, -1 no data (show "N/A").
     * presentNs/refreshNs carry the raw frame timestamps for frame-time stats when the source has them.
     */
    data class FpsResult(
        val fps: Float,
        val jankCount: Int = 0,
        val source: FpsSource? = null,
        val presentNs: LongArray = LongArray(0),
        val refreshNs: Long = 0L
    )

    private val NO_DATA = FpsResult(-1f)

    @Volatile private var overlayFps = 0f
    @Volatile private var displayRefreshRate = 60f
    private var frameTimes = ArrayDeque<Long>()
    private var lastDrawTimeMs = 0L
    private var onPreDrawListener: ViewTreeObserver.OnPreDrawListener? = null
    private var listenerView: WeakReference<View>? = null

    private val fpsBinder = FpsBinder()

    /**
     * Passive overlay redraw-rate estimate, used only without elevated access (labelled "est.").
     * No draw pacing: the overlay is never forced to redraw just to be measured.
     */
    fun initOverlayFps(view: View) {
        if (onPreDrawListener != null) return
        lastDrawTimeMs = 0L
        frameTimes.clear()
        listenerView = WeakReference(view)
        val listener = ViewTreeObserver.OnPreDrawListener {
            val now = SystemClock.uptimeMillis()
            if (lastDrawTimeMs > 0) {
                val deltaMs = now - lastDrawTimeMs
                if (deltaMs in 1..500) {
                    frameTimes.addLast(deltaMs)
                    if (frameTimes.size > 30) frameTimes.removeFirst()
                    overlayFps = 1000f / (frameTimes.sum().toFloat() / frameTimes.size)
                }
            }
            lastDrawTimeMs = now
            true
        }
        view.viewTreeObserver.addOnPreDrawListener(listener)
        onPreDrawListener = listener
    }

    fun stopOverlayFps() {
        val view = listenerView?.get()
        onPreDrawListener?.let { view?.viewTreeObserver?.removeOnPreDrawListener(it) }
        onPreDrawListener = null
        listenerView = null
        frameTimes.clear()
        lastDrawTimeMs = 0L
        overlayFps = 0f
    }

    fun updateDisplayRefreshRate(rate: Float) {
        if (rate > 0f) {
            displayRefreshRate = rate
        }
    }

    /** Reads FPS for [pkg] (or the current foreground app). See [FpsResult] for value meanings. */
    fun readFps(pkg: String? = null): FpsResult {
        val maxRate = displayRefreshRate.coerceAtLeast(60f)

        if (ShellManager.hasElevatedAccess()) {
            val target = pkg ?: foregroundPackage() ?: return NO_DATA
            return try {
                val r = fpsBinder.computeFps(target) { cmd -> ShellManager.exec(cmd).stdout }
                if (r.fps < 0.0) NO_DATA else FpsResult(
                    fps = r.fps.toFloat().coerceIn(0f, maxRate),
                    source = if (r.source == "gfxinfo") FpsSource.GFXINFO else FpsSource.SURFACEFLINGER,
                    presentNs = r.presentNs,
                    refreshNs = r.refreshNs
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed FpsBinder", e)
                NO_DATA
            }
        }

        if (lastDrawTimeMs > 0 && SystemClock.uptimeMillis() - lastDrawTimeMs > 1500) overlayFps = 0f
        val est = overlayFps.coerceIn(0f, maxRate)
        return if (est > 0f) FpsResult(est, source = FpsSource.FPS_MONITOR) else NO_DATA
    }

    private val pkgRegex = Regex("""([a-zA-Z0-9._-]+)/""")
    private val safePkg = Regex("""[A-Za-z0-9._]+""")

    fun foregroundPackage(): String? {
        val result = ShellManager.exec("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'")
        if (!result.isSuccess || result.stdout.isBlank()) return null
        return pkgRegex.find(result.stdout)?.groupValues?.get(1)
    }

    fun foregroundApp(): Pair<String, Int>? {
        var pkg: String? = foregroundPackage()
        if (pkg == null || pkg == "null") {
            val actResult = ShellManager.exec("dumpsys activity activities | grep -m1 -E 'mResumedActivity|topResumedActivity'")
            if (actResult.isSuccess && actResult.stdout.isNotBlank()) {
                val match = Regex("""u0\s+([^/\s}]+)""").find(actResult.stdout)
                pkg = match?.groupValues?.get(1)
            }
        }
        // pkg is interpolated into shell commands below: only plain package names pass
        if (pkg.isNullOrBlank() || pkg == "null" || !safePkg.matches(pkg)) return null

        val pidResult = ShellManager.exec("pidof '$pkg'")
        val pid = pidResult.stdout.trim().split(Regex("\\s+")).firstOrNull()?.toIntOrNull() ?: run {
            val psResult = ShellManager.exec("ps -A -o PID,NAME | grep '$pkg'")
            psResult.stdout.trim().split(Regex("\\s+")).firstOrNull()?.toIntOrNull() ?: 0
        }
        return Pair(pkg, pid)
    }

    fun getDisplayRefreshRate(): Float = displayRefreshRate
}

package com.mkm.fps

import android.util.Log

/** Layer names `--list` can legitimately return; anything else is refused before reaching a shell. */
internal val SAFE_LAYER_REGEX =
    Regex("""SurfaceView\[[^\]'"\n]*\](\(BLAST\))?#\d+|[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+#\d+""")

/** POSIX single-quote escaping: the result is always one literal shell word. */
internal fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

/** fps: >0 rendering, 0 valid idle, -1 no data. presentNs: sorted present timestamps (may be empty). */
class FpsReading(val fps: Double, val source: String, val presentNs: LongArray, val refreshNs: Long)

/**
 * JNI bridge to the native FPS parsing library (v2).
 *
 * Package name is exactly `com.mkm.fps` to match the native C++ JNI exports.
 */
class FpsBinder {

    companion object {
        private const val TAG = "MKM-FpsBinder-JNI"

        init {
            try {
                System.loadLibrary("fpsbinder")
                Log.d(TAG, "libfpsbinder.so loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load libfpsbinder.so", e)
            }
        }
    }

    // ── Native declarations ─────────────────────────────────────────────
    external fun nativeInit(): Boolean
    external fun nativeIsAlive(): Boolean
    external fun nativeGetFpsForPackage(packageName: String): Double
    external fun nativeGetLayerFps(layerName: String): Double
    external fun nativeGetLastFpsSource(): String

    // Text-in entry points for shell-driven parsing
    external fun nativeFindLayersForPackage(listDumpText: String, packageName: String): Array<String>
    external fun nativeFpsFromLatencyText(latencyText: String): Double
    external fun nativeFpsFromGfxinfoText(gfxinfoText: String): Double
    external fun nativePresentTimesFromText(text: String, gfxinfo: Boolean): LongArray

    // Legacy/compat exports
    external fun nativeGetActiveLayers(): Array<String>
    external fun nativeGetAllFps(): String
    external fun nativeGetFrameTimestamps(layerName: String): LongArray?
    external fun nativeGetUptimeMillis(): Long
    external fun nativeDump(args: String): String
    external fun nativeSetFpsLimit(fps: Int): Boolean
    external fun nativeInstallHooks(): Boolean
    external fun nativeHooksInstalled(): Boolean
    external fun nativeGetSfBinderHandle(): Long
    external fun nativeResetCounters()

    // ── High-level API ──────────────────────────────────────────────────
    fun computeFps(pkg: String, shellExec: (String) -> String): FpsReading {
        // ── Strategy 1: SurfaceFlinger --latency (real presented frames) ──
        try {
            val listOutput = shellExec("dumpsys SurfaceFlinger --list")
            if (listOutput.isNotBlank()) {
                var idle: FpsReading? = null
                for (layer in nativeFindLayersForPackage(listOutput, pkg)) {
                    // Window titles are app-controlled: only exact layer-name shapes reach the shell.
                    if (!SAFE_LAYER_REGEX.matches(layer)) continue
                    val latency = shellExec("dumpsys SurfaceFlinger --latency ${shellQuote(layer)}")
                    if (latency.isBlank()) continue
                    val fps = nativeFpsFromLatencyText(latency)
                    if (fps < 0) continue
                    val reading = FpsReading(
                        fps, "sf",
                        nativePresentTimesFromText(latency, false),
                        latency.lineSequence().firstOrNull()?.trim()?.toLongOrNull() ?: 0L
                    )
                    if (fps > 0) {
                        Log.d(TAG, "FPS via --latency ($layer): $fps")
                        return reading
                    }
                    idle = idle ?: reading
                }
                // A matched layer with data but no recent frames is a real idle (0), not a failure.
                idle?.let { return it }
            }
        } catch (e: Exception) {
            Log.w(TAG, "SurfaceFlinger paths failed", e)
        }

        // ── Strategy 2: gfxinfo framestats ──────────────────────────────
        try {
            val gfxinfo = shellExec("dumpsys gfxinfo ${shellQuote(pkg)} framestats")
            if (gfxinfo.isNotBlank()) {
                val rawFps = nativeFpsFromGfxinfoText(gfxinfo)
                if (rawFps >= 0) {
                    Log.d(TAG, "FPS via gfxinfo framestats ($pkg): $rawFps")
                    return FpsReading(rawFps, "gfxinfo", nativePresentTimesFromText(gfxinfo, true), 0L)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "gfxinfo framestats failed", e)
        }

        return FpsReading(-1.0, "none", LongArray(0), 0L)
    }
}

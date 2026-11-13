package com.ivarna.mkm.utils

import android.os.SystemClock
import android.util.Log
import com.ivarna.mkm.data.model.FpsSample
import com.ivarna.mkm.data.model.FpsSource
import com.ivarna.mkm.data.model.FrameStats
import com.ivarna.mkm.shell.ShellManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Root-only GPU FPS collector via ftrace kernel tracing with non-root [FpsMonitor] fallback.
 *
 * Implements:
 * - Snapdragon / Adreno: adreno_cmdbatch_submitted inflight drop counting
 * - MediaTek / Mali: dma_fence/dma_fence_signaled timeline event counting
 * - Fallback: FpsMonitor.readFps()
 */
object GpuFpsCollector {

    private const val TAG = "GpuFpsCollector"
    private const val TRACE_PATH = "/sys/kernel/tracing"
    private const val DMA_EVENT = "dma_fence/dma_fence_signaled"
    private const val SUBMIT_EVENT = "kgsl/adreno_cmdbatch_submitted"
    private const val KGSL_PATH = "/sys/class/kgsl/kgsl-3d0"

    enum class Platform {
        SNAPDRAGON,
        MEDIATEK,
        UNKNOWN
    }

    private var cachedPlatform: Platform? = null
    private var tracingProbeResult: Boolean? = null
    private var lastPid = 0
    private var cachedCtxIds = ""

    // Serializes every ftrace sequence (probe, sample, cleanup) so a stop→start or a
    // probe's cleanup can never disable tracing in the middle of another sample.
    private val ftraceMutex = Mutex()
    private var lastPresentNs = 0L

    private val SNAPDRAGON_PATTERN = Regex(
        // Codenames match exactly ("sun" must not catch Allwinner "sun50i..."); SoC ids by prefix.
        "^(msm|sdm|sm[0-9]|(pineapple|kona|lahaina|taro|kalama|cliffs|anorak|pitti|sun|canoe|parrot|crow|volcano|niobe|holi|blair|bengal|lito|atoll)$)",
        RegexOption.IGNORE_CASE
    )
    private val MEDIATEK_PATTERN = Regex(
        "^(mt[0-9]|mt6[0-9]*|mt7[0-9]*|mt8[0-9]*)",
        RegexOption.IGNORE_CASE
    )

    /**
     * Detects device chipset platform from ro.board.platform. Cached for process lifetime.
     */
    suspend fun detectPlatform(): Platform = withContext(Dispatchers.IO) {
        cachedPlatform?.let { return@withContext it }
        val platformStr = ShellManager.exec("getprop ro.board.platform").stdout.trim().lowercase()
        val detected = when {
            SNAPDRAGON_PATTERN.containsMatchIn(platformStr) -> Platform.SNAPDRAGON
            MEDIATEK_PATTERN.containsMatchIn(platformStr) -> Platform.MEDIATEK
            // Unlisted codenames: an Adreno KGSL node means Snapdragon
            java.io.File(KGSL_PATH).exists() || ShellManager.exec("test -e $KGSL_PATH").isSuccess -> Platform.SNAPDRAGON
            else -> Platform.UNKNOWN
        }
        Log.i(TAG, "Detected platform: $detected (prop: $platformStr)")
        cachedPlatform = detected
        detected
    }

    /**
     * Root-only probe to verify ftrace sysfs write availability.
     * Uses root specifically — never non-root or Shizuku uid=2000.
     * Clears any leftover tracing_on / events from previous force-stops.
     */
    suspend fun probeTracing(): Boolean = withContext(Dispatchers.IO) {
        tracingProbeResult?.let { return@withContext it }

        if (!ShellManager.hasRootCapability()) {
            Log.i(TAG, "Root not available; ftrace tracing disabled.")
            tracingProbeResult = false
            return@withContext false
        }

        ftraceMutex.withLock {
            // Clean up leftover kernel tracer state synchronously, under the lock, before probing
            cleanupLocked()
            val probeResult = execRoot("echo 0 > $TRACE_PATH/tracing_on 2>/dev/null && echo ok")
            val isAvailable = probeResult.isSuccess && probeResult.stdout.contains("ok")
            Log.i(TAG, "ftrace probe result: $isAvailable (exit=${probeResult.exitCode})")
            tracingProbeResult = isAvailable
            isAvailable
        }
    }

    /**
     * Collects one FPS sample over the specified sampling window (default 2s).
     * If [fallbackOnly] is true, bypasses ftrace and uses [FpsMonitor] fallback directly.
     */
    suspend fun sample(windowSec: Float = 2f, fallbackOnly: Boolean = false): FpsSample? = withContext(Dispatchers.IO) {
        val appInfo = FpsMonitor.foregroundApp() ?: Pair("Unknown", 0)
        val pkg = appInfo.first
        val pid = appInfo.second

        if (fallbackOnly) {
            return@withContext sampleFallback(pkg, pid, windowSec)
        }

        val platform = detectPlatform()
        val hasTracing = probeTracing()

        if (!hasTracing || platform == Platform.UNKNOWN || pid <= 0) {
            return@withContext sampleFallback(pkg, pid, windowSec)
        }

        try {
            ftraceMutex.withLock {
                when (platform) {
                    Platform.SNAPDRAGON -> sampleSnapdragon(pkg, pid, windowSec)
                    Platform.MEDIATEK -> sampleMediaTek(pkg, pid, windowSec)
                    Platform.UNKNOWN -> null
                }
            } ?: sampleFallback(pkg, pid, windowSec)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Error sampling ftrace on $platform, falling back", e)
            sampleFallback(pkg, pid, windowSec)
        }
    }

    /** Caller holds [ftraceMutex]. Returns null when ftrace cannot attribute frames (caller falls back). */
    private suspend fun sampleSnapdragon(pkg: String, pid: Int, windowSec: Float): FpsSample? {
        if (pid != lastPid || cachedCtxIds.isBlank()) {
            cachedCtxIds = discoverSnapdragonCtxs(pid)
            lastPid = pid
        }
        if (cachedCtxIds.isBlank()) return null

        val ctxGrep = cachedCtxIds.trim().split(Regex("\\s+"))
            .filter { it.all(Char::isDigit) && it.isNotEmpty() }
            .joinToString("|") { "ctx=$it[^0-9]" }

        if (ctxGrep.isBlank()) return null

        val t0: Long
        val tEnd: Long
        val output: String

        try {
            // Setup ftrace buffer & submit event
            execRoot(
                "echo 0 > $TRACE_PATH/tracing_on\n" +
                "echo 0 > $TRACE_PATH/events/$DMA_EVENT/enable 2>/dev/null\n" +
                "echo > $TRACE_PATH/trace\n" +
                "echo 1 > $TRACE_PATH/events/$SUBMIT_EVENT/enable 2>/dev/null\n" +
                "echo 1 > $TRACE_PATH/tracing_on"
            )

            t0 = SystemClock.uptimeMillis()
            delay((windowSec * 1000).toLong().coerceAtLeast(500L))
            tEnd = SystemClock.uptimeMillis()

            output = execRoot(
                "echo 0 > $TRACE_PATH/tracing_on\n" +
                "echo 0 > $TRACE_PATH/events/$SUBMIT_EVENT/enable 2>/dev/null\n" +
                "grep -E '($ctxGrep)' $TRACE_PATH/trace 2>/dev/null | " +
                "awk '{for(i=1;i<=NF;i++) if(\$i ~ /^inflight=/) {sub(/inflight=/,\"\",\$i); print \$i}}' | " +
                "awk 'NR==1 { prev=\$1; f=1; next } { if (\$1 < prev) f++; prev=\$1 } END { print NR, f+0 }'"
            ).stdout.trim()
        } finally {
            // Belt-and-suspenders: ensure tracing_on=0 and events disabled even if cancelled
            execRoot(
                "echo 0 > $TRACE_PATH/tracing_on 2>/dev/null\n" +
                "echo 0 > $TRACE_PATH/events/$SUBMIT_EVENT/enable 2>/dev/null"
            )
        }

        val parts = output.split(Regex("\\s+"))
        val events = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val rawFrames = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val actualSpanSec = ((tEnd - t0) / 1000f).coerceAtLeast(0.1f)

        val nowMs = System.currentTimeMillis()
        val maxRate = FpsMonitor.getDisplayRefreshRate().coerceAtLeast(60f)

        return if (rawFrames < 2 || events < 3) {
            FpsSample(
                tMs = nowMs,
                fps = 0f,
                frameMs = 0f,
                pkg = pkg,
                pid = pid,
                source = FpsSource.ADRENO_INFLIGHT,
                events = events,
                idle = true
            )
        } else {
            val fps = (rawFrames / actualSpanSec).coerceIn(0f, maxRate)
            val frameMs = if (fps > 0f) 1000f / fps else 0f
            FpsSample(
                tMs = nowMs,
                fps = fps,
                frameMs = frameMs,
                pkg = pkg,
                pid = pid,
                source = FpsSource.ADRENO_INFLIGHT,
                events = events,
                idle = false
            )
        }
    }

    private suspend fun discoverSnapdragonCtxs(pid: Int): String {
        val result = execRoot(
            "echo 0 > $TRACE_PATH/tracing_on\n" +
            "echo > $TRACE_PATH/trace\n" +
            "echo 1 > $TRACE_PATH/events/$DMA_EVENT/enable 2>/dev/null\n" +
            "echo 1 > $TRACE_PATH/tracing_on\n" +
            "sleep 0.5\n" +
            "echo 0 > $TRACE_PATH/tracing_on\n" +
            "echo 0 > $TRACE_PATH/events/$DMA_EVENT/enable 2>/dev/null\n" +
            "grep 'driver=kgsl-timeline.*($pid)' $TRACE_PATH/trace 2>/dev/null | " +
            "grep -o 'kgsl-3d0_[0-9]*' | sed 's/kgsl-3d0_//' | sort -nu | tr '\\n' ' '"
        )
        return result.stdout.trim()
    }

    private suspend fun sampleMediaTek(pkg: String, pid: Int, windowSec: Float): FpsSample {
        val t0: Long
        val tEnd: Long
        val output: String

        try {
            execRoot(
                "echo 0 > $TRACE_PATH/tracing_on\n" +
                "echo > $TRACE_PATH/trace\n" +
                "echo 1 > $TRACE_PATH/events/$DMA_EVENT/enable 2>/dev/null\n" +
                "echo 1 > $TRACE_PATH/tracing_on"
            )

            t0 = SystemClock.uptimeMillis()
            delay((windowSec * 1000).toLong().coerceAtLeast(500L))
            tEnd = SystemClock.uptimeMillis()

            output = execRoot(
                "echo 0 > $TRACE_PATH/tracing_on\n" +
                "echo 0 > $TRACE_PATH/events/$DMA_EVENT/enable 2>/dev/null\n" +
                "awk '/dma_fence_signaled.*driver=mali.*timeline=0-$pid" + "_/ { c++ } END { print c+0 }' $TRACE_PATH/trace 2>/dev/null"
            ).stdout.trim()
        } finally {
            // Belt-and-suspenders: ensure tracing_on=0 and events disabled even if cancelled
            execRoot(
                "echo 0 > $TRACE_PATH/tracing_on 2>/dev/null\n" +
                "echo 0 > $TRACE_PATH/events/$DMA_EVENT/enable 2>/dev/null"
            )
        }

        val events = output.toIntOrNull() ?: 0
        val actualSpanSec = ((tEnd - t0) / 1000f).coerceAtLeast(0.1f)
        val nowMs = System.currentTimeMillis()

        val maxRate = FpsMonitor.getDisplayRefreshRate().coerceAtLeast(60f)

        return if (events < 2) {
            FpsSample(
                tMs = nowMs,
                fps = 0f,
                frameMs = 0f,
                pkg = pkg,
                pid = pid,
                source = FpsSource.MALI_DMA_FENCE,
                events = events,
                idle = true
            )
        } else {
            val fps = (events / actualSpanSec).coerceIn(0f, maxRate)
            val frameMs = if (fps > 0f) 1000f / fps else 0f
            FpsSample(
                tMs = nowMs,
                fps = fps,
                frameMs = frameMs,
                pkg = pkg,
                pid = pid,
                source = FpsSource.MALI_DMA_FENCE,
                events = events,
                idle = false
            )
        }
    }

    private suspend fun sampleFallback(pkg: String, pid: Int, windowSec: Float = 2f): FpsSample {
        // Guarantee proper sampling window cadence for fallback path
        delay((windowSec * 1000).toLong().coerceAtLeast(500L))
        val fpsResult = FpsMonitor.readFps(pkg.takeIf { it != "Unknown" })
        val maxRate = FpsMonitor.getDisplayRefreshRate().coerceAtLeast(60f)
        val fps = fpsResult.fps.coerceIn(0f, maxRate)
        // Only frames newer than the previous sample count, so overlapping windows aren't double counted
        val stats = FrameStats.compute(fpsResult.presentNs, fpsResult.refreshNs, lastPresentNs)
        fpsResult.presentNs.lastOrNull()?.let { lastPresentNs = maxOf(lastPresentNs, it) }
        return FpsSample(
            tMs = System.currentTimeMillis(),
            fps = fps,
            frameMs = stats?.avgFrameMs ?: if (fps > 0f) 1000f / fps else 0f,
            pkg = pkg,
            pid = pid,
            source = fpsResult.source ?: FpsSource.FPS_MONITOR,
            events = stats?.frameTimesMs?.size ?: 0,
            // fps < 0 = no data: recorded as idle rather than as a fake number
            idle = fps <= 0f,
            low1Fps = stats?.low1Fps ?: 0f,
            low01Fps = stats?.low01Fps ?: 0f,
            maxFrameMs = stats?.maxFrameMs ?: 0f,
            jankCount = stats?.jankCount ?: 0,
            stutterPct = stats?.stutterPct ?: 0f,
            frameTimesMs = stats?.frameTimesMs
        )
    }

    /** Stops tracing and disables ftrace events. Idempotent; waits for any in-flight sample. */
    suspend fun shutdown() = withContext(Dispatchers.IO + NonCancellable) {
        ftraceMutex.withLock { cleanupLocked() }
    }

    /** Fire-and-forget [shutdown] for callers that cannot suspend (Service.onDestroy). Still serialized. */
    fun shutdownAsync() {
        CoroutineScope(Dispatchers.IO).launch { shutdown() }
    }

    private fun cleanupLocked() {
        if (!ShellManager.hasRootCapability()) return
        execRoot(
            "echo 0 > $TRACE_PATH/tracing_on 2>/dev/null || true; " +
                "echo 0 > $TRACE_PATH/events/$DMA_EVENT/enable 2>/dev/null || true; " +
                "echo 0 > $TRACE_PATH/events/$SUBMIT_EVENT/enable 2>/dev/null || true"
        )
    }

    /** New recording session: forget the previous session's last frame. */
    fun resetFrameCursor() {
        lastPresentNs = 0L
    }

    private fun execRoot(command: String): ShellManager.CommandResult {
        return try {
            // libsu root when present, otherwise Shizuku running as uid 0
            ShellManager.exec(command, ShellManager.PrivilegeRequirement.ELEVATED_SYSFS)
        } catch (e: Exception) {
            ShellManager.CommandResult(-1, "", e.message ?: "Root execution error")
        }
    }
}

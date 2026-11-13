package com.ivarna.mkm.data.model

enum class FpsSource {
    ADRENO_INFLIGHT,
    MALI_DMA_FENCE,
    /** SurfaceFlinger `--latency` present timestamps. */
    SURFACEFLINGER,
    /** `dumpsys gfxinfo <pkg> framestats`. */
    GFXINFO,
    /** Overlay redraw rate; only an estimate of the display cadence. */
    FPS_MONITOR
}

data class FpsSample(
    val tMs: Long,
    val fps: Float,
    val frameMs: Float,
    val pkg: String,
    val pid: Int,
    val source: FpsSource,
    val events: Int = 0,
    val idle: Boolean = false,
    // Frame-time stats over this sample's new frames (0 when the source has no per-frame timestamps)
    val low1Fps: Float = 0f,
    val low01Fps: Float = 0f,
    val maxFrameMs: Float = 0f,
    val jankCount: Int = 0,
    val stutterPct: Float = 0f,
    // Device context at sample time (for export)
    val gpuFreqMhz: Long = 0L,
    val gpuLoadPct: Float = 0f,
    val cpuTemp: Float = 0f,
    val batteryTemp: Float = 0f,
    val powerW: Float = 0f,
    /** Per-frame times; consumed by the recorder's histogram and never stored. */
    val frameTimesMs: FloatArray? = null
)

data class FpsSession(
    val startedAtMs: Long,
    val samples: List<FpsSample>,
    val platform: String,
    val endedAtMs: Long? = null,
    val stats: SessionStats = SessionStats(),
    val targetPkg: String? = null
)

/** Session-wide aggregate, exact even when the sample series is downsampled. */
data class SessionStats(
    val sampleCount: Int = 0,
    val activeCount: Int = 0,
    val avgFps: Float = 0f,
    val minFps: Float = 0f,
    val maxFps: Float = 0f,
    val low1Fps: Float = 0f,
    val low01Fps: Float = 0f,
    val frameCount: Long = 0L,
    val jankCount: Int = 0,
    val stutterPct: Float = 0f
)

class FrameStats(
    val avgFps: Float,
    val low1Fps: Float,
    val low01Fps: Float,
    val avgFrameMs: Float,
    val maxFrameMs: Float,
    val jankCount: Int,
    val stutterPct: Float,
    val frameTimesMs: FloatArray
) {
    companion object {
        /**
         * Stats from sorted present timestamps (ns). Only frames presented after [sinceNs]
         * count, so overlapping `--latency` windows are not double counted.
         * Jank: frame > 2x median and (when vsync is known) > 1.5x vsync, so a steady
         * half-rate game (every frame = 2 vsyncs) is not flagged as all-jank.
         */
        fun compute(presentNs: LongArray, refreshNs: Long = 0L, sinceNs: Long = 0L): FrameStats? {
            val deltas = ArrayList<Long>(presentNs.size)
            for (i in 1 until presentNs.size) {
                if (presentNs[i] <= sinceNs) continue
                val d = presentNs[i] - presentNs[i - 1]
                if (d > 0L) deltas += d
            }
            if (deltas.isEmpty()) return null
            val sorted = deltas.sorted()
            val total = deltas.sum().toDouble()
            val median = sorted[sorted.size / 2].toDouble()
            val threshold = maxOf(2.0 * median, if (refreshNs > 0L) 1.5 * refreshNs else 0.0)
            val janks = deltas.filter { it > threshold }
            return FrameStats(
                avgFps = (deltas.size * 1e9 / total).toFloat(),
                low1Fps = lowFps(sorted, 0.01),
                low01Fps = lowFps(sorted, 0.001),
                avgFrameMs = (total / deltas.size / 1e6).toFloat(),
                maxFrameMs = (sorted.last() / 1e6).toFloat(),
                jankCount = janks.size,
                stutterPct = (janks.sum() * 100.0 / total).toFloat(),
                frameTimesMs = FloatArray(deltas.size) { (deltas[it] / 1e6).toFloat() }
            )
        }

        /** "x% low": average FPS over the slowest x fraction of frames (at least one frame). */
        internal fun lowFps(sortedAsc: List<Long>, fraction: Double): Float {
            val n = kotlin.math.ceil(sortedAsc.size * fraction).toInt().coerceIn(1, sortedAsc.size)
            val worst = sortedAsc.subList(sortedAsc.size - n, sortedAsc.size)
            return (1e9 / worst.average()).toFloat()
        }
    }
}

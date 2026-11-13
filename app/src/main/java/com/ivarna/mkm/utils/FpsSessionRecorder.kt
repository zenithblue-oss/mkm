package com.ivarna.mkm.utils

import com.ivarna.mkm.data.model.FpsSample
import com.ivarna.mkm.data.model.FpsSession
import com.ivarna.mkm.data.model.SessionStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide singleton recording FPS samples for session analysis and graphing.
 *
 * Nothing is silently dropped: [SessionStats] is an exact rolling aggregate over every
 * sample, and the stored series is downsampled (every other point kept, stride doubled)
 * once it reaches [MAX_SERIES] instead of discarding the oldest samples.
 */
object FpsSessionRecorder {

    internal const val MAX_SERIES = 1800 // 1h at the 2s sample window before the first halving

    // Frame-time histogram for session-wide 1% / 0.1% lows: 0.1ms bins, last bin = overflow.
    private const val BIN_MS = 0.1f
    private const val BINS = 2501

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _session = MutableStateFlow<FpsSession?>(null)
    val session: StateFlow<FpsSession?> = _session.asStateFlow()

    private val lock = Any()
    private val samples = mutableListOf<FpsSample>()
    private var startedAtMs = 0L
    private var platformName = "UNKNOWN"
    private var targetPkg: String? = null
    private var stride = 1
    private var seen = 0

    // Rolling aggregate
    private var activeCount = 0
    private var fpsSum = 0.0
    private var minFps = Float.MAX_VALUE
    private var maxFps = 0f
    private var jankCount = 0
    private var frameCount = 0L
    private var frameTimeSumMs = 0.0
    private var jankTimeMs = 0.0
    private val hist = IntArray(BINS)

    fun start(platform: String = "UNKNOWN") {
        synchronized(lock) {
            reset()
            startedAtMs = System.currentTimeMillis()
            platformName = platform
            publish(null)
            _isRecording.value = true
        }
    }

    fun setPlatform(platform: String) {
        synchronized(lock) {
            platformName = platform
            _session.value = _session.value?.copy(platform = platform)
        }
    }

    fun stop(): FpsSession? {
        synchronized(lock) {
            _isRecording.value = false
            if (_session.value == null) return null
            publish(System.currentTimeMillis())
            return _session.value
        }
    }

    /**
     * Records [sample] if it belongs to the session's target app. The target locks onto the first
     * sampled app that is not [ignorePkg] (MKM itself, where recording is started from).
     */
    fun add(sample: FpsSample, ignorePkg: String? = null) {
        synchronized(lock) {
            if (!_isRecording.value) return
            if (sample.pkg == ignorePkg || sample.pkg == "Unknown") return
            if (targetPkg == null) targetPkg = sample.pkg
            if (sample.pkg != targetPkg) return

            if (!sample.idle && sample.fps > 0f) {
                activeCount++
                fpsSum += sample.fps
                minFps = minOf(minFps, sample.fps)
                maxFps = maxOf(maxFps, sample.fps)
            }
            jankCount += sample.jankCount
            sample.frameTimesMs?.let { times ->
                var sum = 0.0
                times.forEach { ms ->
                    sum += ms
                    hist[(ms / BIN_MS).toInt().coerceIn(0, BINS - 1)]++
                }
                frameCount += times.size
                frameTimeSumMs += sum
                jankTimeMs += sum * sample.stutterPct / 100.0 // stutterPct = jank time share of this sample
            }

            if (seen++ % stride == 0) {
                samples.add(sample.copy(frameTimesMs = null))
                if (samples.size >= MAX_SERIES) {
                    val kept = samples.filterIndexed { i, _ -> i % 2 == 0 }
                    samples.clear()
                    samples.addAll(kept)
                    stride *= 2
                }
            }
            publish(null)
        }
    }

    fun clear() {
        synchronized(lock) {
            reset()
            _session.value = null
            _isRecording.value = false
        }
    }

    private fun reset() {
        samples.clear()
        targetPkg = null
        stride = 1
        seen = 0
        activeCount = 0
        fpsSum = 0.0
        minFps = Float.MAX_VALUE
        maxFps = 0f
        jankCount = 0
        frameCount = 0L
        frameTimeSumMs = 0.0
        jankTimeMs = 0.0
        hist.fill(0)
    }

    private fun publish(endedAtMs: Long?) {
        _session.value = FpsSession(
            startedAtMs = startedAtMs,
            samples = samples.toList(),
            platform = platformName,
            endedAtMs = endedAtMs,
            stats = SessionStats(
                sampleCount = seen,
                activeCount = activeCount,
                avgFps = if (activeCount > 0) (fpsSum / activeCount).toFloat() else 0f,
                minFps = if (activeCount > 0) minFps else 0f,
                maxFps = maxFps,
                low1Fps = histLowFps(0.01),
                low01Fps = histLowFps(0.001),
                frameCount = frameCount,
                jankCount = jankCount,
                stutterPct = if (frameTimeSumMs > 0) (jankTimeMs * 100 / frameTimeSumMs).toFloat() else 0f
            ),
            targetPkg = targetPkg
        )
    }

    /** Average FPS of the slowest [fraction] of all recorded frames, from the histogram. */
    private fun histLowFps(fraction: Double): Float {
        if (frameCount == 0L) return 0f
        var need = kotlin.math.ceil(frameCount * fraction).toLong().coerceAtLeast(1L)
        val n = need
        var sumMs = 0.0
        for (bin in BINS - 1 downTo 0) {
            if (need <= 0L) break
            val take = minOf(need, hist[bin].toLong())
            sumMs += take * (bin + 0.5) * BIN_MS
            need -= take
        }
        return if (sumMs > 0) (1000.0 * n / sumMs).toFloat() else 0f
    }
}

/** CSV / JSON export of a recorded session (one row per stored sample). */
object FpsSessionExport {
    private const val HEADER =
        "time_ms,elapsed_s,pkg,source,fps,frame_ms,low1_fps,low01_fps,max_frame_ms,jank,stutter_pct," +
            "gpu_freq_mhz,gpu_load_pct,cpu_temp_c,battery_temp_c,power_w"

    fun toCsv(session: FpsSession): String = buildString {
        appendLine(HEADER)
        session.samples.forEach { s ->
            appendLine(
                listOf(
                    s.tMs, fmt((s.tMs - session.startedAtMs) / 1000f), csvField(s.pkg), s.source.name,
                    fmt(s.fps), fmt(s.frameMs), fmt(s.low1Fps), fmt(s.low01Fps), fmt(s.maxFrameMs),
                    s.jankCount, fmt(s.stutterPct), s.gpuFreqMhz, fmt(s.gpuLoadPct),
                    fmt(s.cpuTemp), fmt(s.batteryTemp), fmt(s.powerW)
                ).joinToString(",")
            )
        }
    }

    fun toJson(session: FpsSession): String = buildString {
        val st = session.stats
        append("{\"platform\":").append(jsonStr(session.platform))
        append(",\"target_pkg\":").append(session.targetPkg?.let(::jsonStr) ?: "null")
        append(",\"started_at_ms\":").append(session.startedAtMs)
        append(",\"ended_at_ms\":").append(session.endedAtMs ?: "null")
        append(",\"stats\":{\"avg_fps\":${fmt(st.avgFps)},\"min_fps\":${fmt(st.minFps)},\"max_fps\":${fmt(st.maxFps)}")
        append(",\"low1_fps\":${fmt(st.low1Fps)},\"low01_fps\":${fmt(st.low01Fps)},\"frames\":${st.frameCount}")
        append(",\"jank\":${st.jankCount},\"stutter_pct\":${fmt(st.stutterPct)},\"samples\":${st.sampleCount}}")
        append(",\"samples\":[")
        session.samples.forEachIndexed { i, s ->
            if (i > 0) append(',')
            append("{\"t_ms\":${s.tMs},\"pkg\":${jsonStr(s.pkg)},\"source\":\"${s.source.name}\"")
            append(",\"fps\":${fmt(s.fps)},\"frame_ms\":${fmt(s.frameMs)},\"low1_fps\":${fmt(s.low1Fps)}")
            append(",\"low01_fps\":${fmt(s.low01Fps)},\"max_frame_ms\":${fmt(s.maxFrameMs)},\"jank\":${s.jankCount}")
            append(",\"stutter_pct\":${fmt(s.stutterPct)},\"gpu_freq_mhz\":${s.gpuFreqMhz},\"gpu_load_pct\":${fmt(s.gpuLoadPct)}")
            append(",\"cpu_temp_c\":${fmt(s.cpuTemp)},\"battery_temp_c\":${fmt(s.batteryTemp)},\"power_w\":${fmt(s.powerW)}}")
        }
        append("]}")
    }

    private fun fmt(v: Float): String = if (v.isFinite()) String.format(java.util.Locale.US, "%.2f", v) else "0"

    private fun csvField(v: String): String =
        if (v.any { it == ',' || it == '"' || it == '\n' }) "\"" + v.replace("\"", "\"\"") + "\"" else v

    private fun jsonStr(v: String): String = buildString {
        append('"')
        v.forEach { c ->
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c < ' ' -> append(String.format("\\u%04x", c.code))
                else -> append(c)
            }
        }
        append('"')
    }
}

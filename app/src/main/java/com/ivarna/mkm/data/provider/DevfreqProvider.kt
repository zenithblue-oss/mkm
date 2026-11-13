package com.ivarna.mkm.data.provider

import com.ivarna.mkm.data.model.DevfreqStatus
import com.ivarna.mkm.shell.DevfreqScripts
import com.ivarna.mkm.shell.ShellManager
import com.ivarna.mkm.shell.SysfsTuningExecutor

object DevfreqProvider {
    private var cachedPath: String? = null

    /** Governor/min/max as first seen before the app touched them, per controller (process lifetime). */
    private val originals = HashMap<String, Triple<String, String, String>>()

    fun clearCache() {
        cachedPath = null
    }

    fun getDevfreqStatus(): DevfreqStatus {
        val (path, pathDebug) = getPath()
        if (path.isEmpty()) {
            return DevfreqStatus(isSupported = false, debugInfo = "Path detection failed:\n$pathDebug")
        }

        val result = ShellManager.exec(DevfreqScripts.getDevfreqInfo(path))
        if (!result.isSuccess) {
            return DevfreqStatus(
                controllerPath = path,
                isSupported = false,
                debugInfo = "Read failed for $path:\nSTDOUT: ${result.stdout}\nSTDERR: ${result.stderr}\nEXIT: ${result.exitCode}\n\nPath Log:\n$pathDebug"
            )
        }

        var gov = "unknown"
        var avail = emptyList<String>()
        var freq = "0"
        var availFreq = emptyList<String>()
        var minFreq = ""
        var maxFreq = ""
        // MediaTek APU governors crash when set manually
        val unsafeGovernors = setOf("apupassive-pe", "apupassive", "apuconstrain", "apuuser")

        result.stdout.lines().forEach { line ->
            when {
                line.startsWith("GOV=") -> gov = line.removePrefix("GOV=")
                line.startsWith("AVAIL=") -> avail = line.removePrefix("AVAIL=").split(WS).filter { it.isNotEmpty() && it !in unsafeGovernors }
                line.startsWith("FREQ=") -> freq = line.removePrefix("FREQ=")
                line.startsWith("AVAIL_FREQ=") -> availFreq = line.removePrefix("AVAIL_FREQ=").split(WS).filter { it.isNotEmpty() }
                line.startsWith("MIN_FREQ=") -> minFreq = line.removePrefix("MIN_FREQ=").trim()
                line.startsWith("MAX_FREQ=") -> maxFreq = line.removePrefix("MAX_FREQ=").trim()
            }
        }

        return DevfreqStatus(
            controllerPath = path,
            currentGovernor = gov,
            availableGovernors = avail,
            currentFreq = freq,
            availableFrequencies = availFreq,
            isSupported = true,
            debugInfo = "Success.\nPath: $path\nSTDOUT: ${result.stdout}\nSTDERR: ${result.stderr}\nEXIT: ${result.exitCode}",
            minFreq = minFreq,
            maxFreq = maxFreq
        )
    }

    private fun fail(msg: String) = ShellManager.CommandResult(-1, "", msg)

    /** Validated against the live available_governors of the detected controller. */
    fun setGovernor(path: String, governor: String): ShellManager.CommandResult {
        val status = getDevfreqStatus()
        if (!status.isSupported || status.controllerPath != path) return fail("Unknown devfreq controller")
        if (governor !in status.availableGovernors) return fail("Governor not available: $governor")
        rememberOriginal(status)
        return SysfsTuningExecutor.write("$path/governor", governor)
    }

    /** Raising writes max first, lowering min first; stops at the first failure. */
    fun setFreq(path: String, freq: String): ShellManager.CommandResult {
        val status = getDevfreqStatus()
        if (!status.isSupported || status.controllerPath != path) return fail("Unknown devfreq controller")
        if (freq !in status.availableFrequencies) return fail("Frequency not available: $freq")
        rememberOriginal(status)
        for (node in listOf("userspace/set_freq", "ondemand/set_freq")) {
            if (SysfsTuningExecutor.canWrite("$path/$node")) return SysfsTuningExecutor.write("$path/$node", freq)
        }
        return writeRange(path, freq, freq, status.maxFreq)
    }

    /** Restores the governor/min/max seen before the first change (or the full range). */
    fun reset(path: String): ShellManager.CommandResult {
        val status = getDevfreqStatus()
        if (!status.isSupported || status.controllerPath != path) return fail("Unknown devfreq controller")
        val nums = status.availableFrequencies.mapNotNull { it.toLongOrNull() }
        val orig = originals[path] ?: Triple(
            status.currentGovernor,
            nums.minOrNull()?.toString().orEmpty(),
            nums.maxOrNull()?.toString().orEmpty()
        )
        if (orig.first.isNotBlank() && orig.first != status.currentGovernor && orig.first in status.availableGovernors) {
            val r = SysfsTuningExecutor.write("$path/governor", orig.first)
            if (!r.isSuccess) return r
        }
        if (orig.second.isBlank() || orig.third.isBlank()) return fail("Original range unknown")
        return writeRange(path, orig.second, orig.third, status.maxFreq).also { if (it.isSuccess) originals.remove(path) }
    }

    private fun writeRange(path: String, min: String, max: String, currentMax: String): ShellManager.CommandResult {
        val raising = (max.toLongOrNull() ?: 0L) >= (currentMax.toLongOrNull() ?: 0L)
        val order = if (raising) listOf("max_freq" to max, "min_freq" to min) else listOf("min_freq" to min, "max_freq" to max)
        var last = fail("No write")
        for ((node, value) in order) {
            last = SysfsTuningExecutor.write("$path/$node", value)
            if (!last.isSuccess) return last
        }
        return last
    }

    private fun rememberOriginal(status: DevfreqStatus) {
        if (status.minFreq.isNotBlank() && status.maxFreq.isNotBlank()) {
            originals.getOrPut(status.controllerPath) { Triple(status.currentGovernor, status.minFreq, status.maxFreq) }
        }
    }

    private val WS = Regex("\\s+")

    private fun getPath(): Pair<String, String> {
        cachedPath?.let { return Pair(it, "Using cached path") }
        val result = ShellManager.exec(DevfreqScripts.findDevfreqDir())
        val debugLog = "CMD: findDevfreqDir\nEXIT: ${result.exitCode}\nSTDOUT: ${result.stdout}\nSTDERR: ${result.stderr}"
        val p = result.stdout.trim().lines().firstOrNull().orEmpty()
        if (p.startsWith("/sys/")) {
            cachedPath = p
            return Pair(p, debugLog)
        }
        return Pair("", debugLog)
    }
}

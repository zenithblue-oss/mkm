package com.ivarna.mkm.data.provider

import com.ivarna.mkm.data.model.UfsStatus
import com.ivarna.mkm.shell.ShellManager
import com.ivarna.mkm.shell.SysfsTuningExecutor
import com.ivarna.mkm.shell.UfsScripts

/** UFS health descriptor. Levels are raw JEDEC codes; null = node missing/unreadable. */
data class UfsHealth(
    val lifeTimeA: Int? = null,
    val lifeTimeB: Int? = null,
    val preEol: Int? = null,
    val sourceDir: String = ""
) {
    val available: Boolean get() = lifeTimeA != null || lifeTimeB != null || preEol != null
}

object UfsProvider {
    private var cachedPath: String? = null
    private var cachedHealth: UfsHealth? = null

    fun clearCache() {
        cachedPath = null
        cachedHealth = null
    }

    fun getUfsStatus(): UfsStatus {
        val (path, pathDebug) = getPath()
        if (path.isEmpty()) {
            return UfsStatus(isSupported = false, debugInfo = "Path detection failed:\n$pathDebug")
        }

        val result = ShellManager.exec(UfsScripts.getUfsInfo(path))
        if (!result.isSuccess) {
            return UfsStatus(
                controllerPath = path,
                isSupported = false,
                debugInfo = "Governor read failed for $path:\nSTDOUT: ${result.stdout}\nSTDERR: ${result.stderr}\nEXIT: ${result.exitCode}\n\nPath Log:\n$pathDebug"
            )
        }

        var gov = "unknown"
        var avail = emptyList<String>()
        var freq = "0"
        var minFreq = "0"
        var maxFreq = "0"
        var availFreq = emptyList<String>()
        // MediaTek APU governors crash when set manually
        val unsafeGovernors = setOf("apupassive-pe", "apupassive", "apuconstrain", "apuuser")
        val ws = Regex("\\s+")

        result.stdout.lines().forEach { line ->
            when {
                line.startsWith("GOV=") -> gov = line.removePrefix("GOV=")
                line.startsWith("AVAIL=") -> avail = line.removePrefix("AVAIL=").split(ws).filter { it.isNotEmpty() && it !in unsafeGovernors }
                line.startsWith("FREQ=") -> freq = line.removePrefix("FREQ=")
                line.startsWith("MIN_FREQ=") -> minFreq = line.removePrefix("MIN_FREQ=")
                line.startsWith("MAX_FREQ=") -> maxFreq = line.removePrefix("MAX_FREQ=")
                line.startsWith("AVAIL_FREQ=") -> availFreq = line.removePrefix("AVAIL_FREQ=").split(ws).filter { it.isNotEmpty() }
            }
        }

        return UfsStatus(
            controllerPath = path,
            currentGovernor = gov,
            availableGovernors = avail,
            currentFreq = freq,
            minFreq = minFreq,
            maxFreq = maxFreq,
            availableFrequencies = availFreq,
            isSupported = true,
            debugInfo = "Success.\nPath: $path\nSTDOUT: ${result.stdout}\nSTDERR: ${result.stderr}\nEXIT: ${result.exitCode}"
        )
    }

    private fun fail(msg: String) = ShellManager.CommandResult(-1, "", msg)

    /** Whitelisted against the live available_governors of the detected controller. */
    fun setGovernor(path: String, governor: String): ShellManager.CommandResult {
        val s = getUfsStatus()
        if (!s.isSupported || s.controllerPath != path) return fail("Unknown UFS controller")
        if (governor !in s.availableGovernors) return fail("Governor not available: $governor")
        return SysfsTuningExecutor.write("$path/governor", governor)
    }

    /** Whitelisted against available_frequencies; refuses min > max. */
    fun setFreq(path: String, freq: String, isMax: Boolean): ShellManager.CommandResult {
        val s = getUfsStatus()
        if (!s.isSupported || s.controllerPath != path) return fail("Unknown UFS controller")
        if (freq !in s.availableFrequencies) return fail("Frequency not available: $freq")
        val f = freq.toLong()
        if (isMax && f < (s.minFreq.trim().toLongOrNull() ?: 0L)) return fail("Max must be ≥ min")
        if (!isMax && f > (s.maxFreq.trim().toLongOrNull() ?: Long.MAX_VALUE)) return fail("Min must be ≤ max")
        return SysfsTuningExecutor.write("$path/${if (isMax) "max_freq" else "min_freq"}", freq)
    }

    fun getHealth(): UfsHealth {
        cachedHealth?.let { return it }
        val out = ShellManager.exec(UfsScripts.getHealthInfo())
        val map = out.stdout.lines().associate { it.substringBefore('=') to it.substringAfter('=', "").trim() }
        return UfsHealth(
            lifeTimeA = parseHexLevel(map["life_time_estimation_a"]),
            lifeTimeB = parseHexLevel(map["life_time_estimation_b"]),
            // Upstream ufs-sysfs names bPreEOLInfo "eol_info" (verified on MTK D8300)
            preEol = parseHexLevel(map["pre_eol_info"] ?: map["eol_info"]),
            sourceDir = map["D"].orEmpty()
        ).also { cachedHealth = it }
    }

    /** "0x01" / "01" / "1" → 1. Null for blank/garbage. */
    fun parseHexLevel(raw: String?): Int? {
        val s = raw?.trim()?.removePrefix("0x")?.removePrefix("0X") ?: return null
        return s.takeIf { it.isNotEmpty() }?.toIntOrNull(16)
    }

    /** JEDEC life time estimation: 0x01..0x0A → "0-10%".."90-100%", 0x0B → ">100%". */
    fun lifeTimeLabel(level: Int?): String? = when {
        level == null -> null
        level in 1..10 -> "${(level - 1) * 10}-${level * 10}%"
        level == 11 -> ">100%"
        else -> null
    }

    private fun getPath(): Pair<String, String> {
        cachedPath?.let { return Pair(it, "Using cached path") }
        val result = ShellManager.exec(UfsScripts.findUfsDir())
        val debugLog = "CMD: findUfsDir\nEXIT: ${result.exitCode}\nSTDOUT: ${result.stdout}\nSTDERR: ${result.stderr}"
        val p = result.stdout.trim().lines().firstOrNull().orEmpty()
        if (p.startsWith("/sys/")) {
            cachedPath = p
            return Pair(p, debugLog)
        }
        return Pair("", debugLog)
    }
}

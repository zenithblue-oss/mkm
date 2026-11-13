package com.ivarna.mkm.data.provider

import com.ivarna.mkm.data.model.MemoryStatus
import com.ivarna.mkm.data.model.SwapDeviceInfo
import com.ivarna.mkm.data.model.SwapStatus
import com.ivarna.mkm.shell.ShellManager
import com.ivarna.mkm.shell.ShellScripts
import com.ivarna.mkm.utils.ShellUtils

/** zram0 + vm tunables snapshot. Sizes in bytes; null = unreadable. */
data class ZramStatus(
    val present: Boolean = false,
    val disksizeBytes: Long = 0,
    val origBytes: Long = 0,
    val comprBytes: Long = 0,
    val memUsedBytes: Long = 0,
    val algorithms: List<String> = emptyList(),
    val currentAlgorithm: String = "",
    val vm: Map<String, Int?> = emptyMap()
) {
    val ratio: Float get() = if (comprBytes > 0) origBytes.toFloat() / comprBytes else 0f
}

/** A swap file created/managed by the app; may be inactive. */
data class ManagedSwapFile(val path: String, val sizeBytes: Long, val active: Boolean)

object MemoryProvider {
    fun getMemoryStatus(): MemoryStatus {
        val memInfo = ShellUtils.readFile("/proc/meminfo")
        val total = parseValue(memInfo, "MemTotal")
        val cached = parseValue(memInfo, "Cached")
        val active = parseValue(memInfo, "Active")
        val inactive = parseValue(memInfo, "Inactive")
        val buffers = parseValue(memInfo, "Buffers")
        val available = availableKb(memInfo)
        val used = total - available

        return MemoryStatus(
            totalUi = ShellUtils.formatSize(total),
            usedUi = ShellUtils.formatSize(used),
            freeUi = ShellUtils.formatSize(available),
            usagePercent = if (total > 0) used.toFloat() / total else 0f,
            rawTotal = total,
            rawUsed = used,
            availableUi = ShellUtils.formatSize(available),
            cachedUi = ShellUtils.formatSize(cached),
            activeUi = ShellUtils.formatSize(active),
            inactiveUi = ShellUtils.formatSize(inactive),
            buffersUi = ShellUtils.formatSize(buffers)
        )
    }

    /** MemAvailable, or MemFree+Cached+Buffers on kernels without it. */
    fun availableKb(memInfo: String): Long =
        if (Regex("(?m)^MemAvailable:").containsMatchIn(memInfo)) parseValue(memInfo, "MemAvailable")
        else parseValue(memInfo, "MemFree") + parseValue(memInfo, "Cached") + parseValue(memInfo, "Buffers")

    // Paths the app could not read directly (SELinux). Skipped for process lifetime so polling
    // stops generating avc denials. ponytail: blank/missing also counts; shell fallback covers it.
    private val deniedPaths: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private fun readDirect(path: String): String {
        if (path in deniedPaths) return ""
        return ShellUtils.readFile(path).also { if (it.isBlank()) deniedPaths += path }
    }

    /** /proc/swaps directly; shell only when the app cannot read it. */
    private fun readProcSwaps(): String =
        readDirect("/proc/swaps").ifBlank { ShellManager.exec("cat /proc/swaps").stdout }

    fun getSwapStatus(): SwapStatus {
        val memInfo = ShellUtils.readFile("/proc/meminfo")
        val total = parseValue(memInfo, "SwapTotal")
        val free = parseValue(memInfo, "SwapFree")
        val used = if (total > 0) total - free else 0L

        val devices = mutableListOf<SwapDeviceInfo>()
        readProcSwaps().lines().drop(1).forEach { raw ->
            val parts = raw.trim().split(Regex("\\s+"))
            if (parts.size < 5) return@forEach
            var path = decodeProcPath(parts[0])
            // Some devices report /local/tmp instead of /data/local/tmp
            if (path.startsWith("/local/")) path = "/data$path"
            else if (LOOP_DEV.matches(path)) path = loopBackingFile(path) ?: path
            devices.add(
                SwapDeviceInfo(
                    path = path,
                    type = parts[1],
                    sizeUi = ShellUtils.formatSize(parts[2].toLongOrNull() ?: 0L),
                    usedUi = ShellUtils.formatSize(parts[3].toLongOrNull() ?: 0L),
                    priority = parts[4].toIntOrNull() ?: -1
                )
            )
        }

        // Main path: a regular swap file under /data only (never zram / block devices)
        val mainPath = devices.firstOrNull { ShellScripts.isValidSwapPath(it.path) }?.path ?: "None"

        return SwapStatus(
            isActive = total > 0,
            totalUi = ShellUtils.formatSize(total),
            usedUi = ShellUtils.formatSize(used),
            usagePercent = if (total > 0) used.toFloat() / total else 0f,
            path = mainPath,
            devices = devices
        )
    }

    private val LOOP_DEV = Regex("^/dev/(block/)?loop[0-9]+$")

    private fun loopBackingFile(dev: String): String? {
        val name = dev.substringAfterLast('/')
        ShellUtils.readFile("/sys/block/$name/loop/backing_file").takeIf { it.isNotBlank() }?.let { return it }
        // Output: /dev/block/loop0: [64768]:52523 (/data/local/tmp/swapfile)
        val out = ShellManager.exec("losetup '$dev'").stdout
        return Regex("\\((.*?)\\)").find(out)?.groupValues?.get(1)
    }

    /** Decodes octal escapes (`\040` = space) used by /proc/swaps and /proc/mounts. */
    fun decodeProcPath(raw: String): String =
        Regex("\\\\([0-7]{3})").replace(raw) { it.groupValues[1].toInt(8).toChar().toString() }

    /** Existing managed swap files (one shell call). [candidates] come from saved prefs. */
    fun getManagedSwapFiles(candidates: Collection<String>, swap: SwapStatus): List<ManagedSwapFile> {
        val activePaths = swap.devices.map { it.path }.toSet()
        val all = (candidates + activePaths.filter(ShellScripts::isValidSwapPath) + ShellScripts.DEFAULT_SWAP_PATH)
        val out = ShellManager.exec(ShellScripts.statSwapFiles(all), ShellManager.PrivilegeRequirement.ELEVATED_SYSFS).stdout
        return out.lines().mapNotNull { line ->
            val path = line.substringBeforeLast(' ', "")
            val size = line.substringAfterLast(' ').toLongOrNull()
            if (path.isEmpty() || size == null || !ShellScripts.isValidSwapPath(path)) null
            else ManagedSwapFile(path, size, path in activePaths)
        }
    }

    // ---- zram / vm ----------------------------------------------------------------

    private const val ZRAM = "/sys/block/zram0"
    private val ZRAM_FILES = listOf("mm_stat", "disksize", "comp_algorithm")

    fun getZramStatus(): ZramStatus {
        val values = HashMap<String, String>()
        ZRAM_FILES.forEach { values[it] = readDirect("$ZRAM/$it") }
        ShellScripts.VM_TUNABLES.keys.forEach { values[it] = readDirect("/proc/sys/vm/$it") }
        if (values.values.any { it.isBlank() }) {
            // One elevated read for whatever SELinux hid from the app
            val cmd = (ZRAM_FILES.map { "$ZRAM/$it" } + ShellScripts.VM_TUNABLES.keys.map { "/proc/sys/vm/$it" })
                .joinToString("; ") { "echo \"${it.substringAfterLast('/')}=\$(cat $it 2>/dev/null)\"" }
            ShellManager.exec(cmd, ShellManager.PrivilegeRequirement.ELEVATED_SYSFS).stdout.lines().forEach { line ->
                val k = line.substringBefore('=', "")
                if (k.isNotEmpty() && values[k].isNullOrBlank()) values[k] = line.substringAfter('=').trim()
            }
        }
        val mm = parseMmStat(values["mm_stat"].orEmpty())
        val (algos, current) = parseBracketList(values["comp_algorithm"].orEmpty())
        return ZramStatus(
            present = values["disksize"].orEmpty().isNotBlank(),
            disksizeBytes = values["disksize"]?.trim()?.toLongOrNull() ?: 0L,
            origBytes = mm?.get(0) ?: 0L,
            comprBytes = mm?.get(1) ?: 0L,
            memUsedBytes = mm?.get(2) ?: 0L,
            algorithms = algos,
            currentAlgorithm = current,
            vm = ShellScripts.VM_TUNABLES.keys.associateWith { values[it]?.trim()?.toIntOrNull() }
        )
    }

    /** mm_stat: orig_data_size compr_data_size mem_used_total ... → first three, or null. */
    fun parseMmStat(text: String): List<Long>? {
        val nums = text.trim().split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }
        return if (nums.size >= 3) nums.take(3) else null
    }

    /** "lzo [lz4] zstd" → (all, selected). Also used for queue/scheduler. */
    fun parseBracketList(text: String): Pair<List<String>, String> {
        val tokens = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val current = tokens.firstOrNull { it.startsWith("[") }?.trim('[', ']').orEmpty()
        return tokens.map { it.trim('[', ']') } to current
    }

    fun parseValue(memInfo: String, key: String): Long =
        Regex("(?m)^$key:\\s+(\\d+)\\s+kB").find(memInfo)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
}

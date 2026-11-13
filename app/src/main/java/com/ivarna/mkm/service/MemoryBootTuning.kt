package com.ivarna.mkm.service

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.ivarna.mkm.data.provider.MemoryProvider
import com.ivarna.mkm.data.provider.StorageProvider
import com.ivarna.mkm.shell.ShellManager
import com.ivarna.mkm.shell.ShellScripts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Saved swap / zram / vm / I/O tuning and its boot-time re-apply.
 * RAM items apply when the RAM boot toggle is on, I/O items when the Storage boot toggle is on.
 * Does NOT take KernelTuningCoordinator's lock (non-reentrant): the caller may already hold it.
 */
object MemoryBootTuning {
    private const val PREFS = "mkm_memory_boot"
    private const val KEY_MANAGED = "managed_swaps"
    private const val KEY_SWAP_PATH = "swap_path"
    private const val KEY_SWAP_PRIO = "swap_prio"
    private const val KEY_ZRAM_ALGO = "zram_algo"
    private const val KEY_ZRAM_SIZE = "zram_size"
    private const val SWAP_TIMEOUT_MS = 180_000L

    private fun prefs(c: Context): SharedPreferences = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun managedSwaps(c: Context): Set<String> = prefs(c).getStringSet(KEY_MANAGED, emptySet()).orEmpty()

    /** Records a created swap file and makes it the one re-enabled at boot. */
    fun saveSwap(c: Context, path: String, priority: Int) {
        if (!ShellScripts.isValidSwapPath(path)) return
        prefs(c).edit {
            putStringSet(KEY_MANAGED, managedSwaps(c) + path)
            putString(KEY_SWAP_PATH, path)
            putInt(KEY_SWAP_PRIO, priority)
        }
    }

    /** Stops re-enabling [path] at boot; [deleted] also forgets it as a managed file. */
    fun clearSwap(c: Context, path: String, deleted: Boolean) {
        prefs(c).edit {
            if (prefs(c).getString(KEY_SWAP_PATH, null) == path) { remove(KEY_SWAP_PATH); remove(KEY_SWAP_PRIO) }
            if (deleted) putStringSet(KEY_MANAGED, managedSwaps(c) - path)
        }
    }

    fun saveVm(c: Context, name: String, value: Int) {
        if (ShellScripts.isValidVmValue(name, value)) prefs(c).edit { putInt("vm_$name", value) }
    }

    fun saveZram(c: Context, algorithm: String, sizeBytes: Long) = prefs(c).edit {
        putString(KEY_ZRAM_ALGO, algorithm)
        putLong(KEY_ZRAM_SIZE, sizeBytes)
    }

    fun saveScheduler(c: Context, device: String, scheduler: String) {
        if (StorageProvider.isValidBlockDevice(device)) prefs(c).edit { putString("io_${device}_sched", scheduler) }
    }

    fun saveReadAhead(c: Context, device: String, kb: Int) {
        if (StorageProvider.isValidBlockDevice(device)) prefs(c).edit { putInt("io_${device}_ra", kb) }
    }

    suspend fun apply(context: Context): List<String> = withContext(Dispatchers.IO) {
        val log = mutableListOf<String>()
        val p = prefs(context)
        fun record(what: String, r: ShellManager.CommandResult) {
            log += if (r.isSuccess) "$what: ok" else "$what: failed (${r.stderr.ifBlank { r.stdout }.ifBlank { "exit ${r.exitCode}" }})"
        }

        if (BootSettingsManager.isRamEnabled(context)) {
            val root = ShellManager.hasRootCapability()
            p.getString(KEY_SWAP_PATH, null)?.takeIf(ShellScripts::isValidSwapPath)?.let { path ->
                if (!root) log += "swap $path: skipped (requires root)"
                else record("swap $path", ShellManager.exec(
                    ShellScripts.enableSwap(path, p.getInt(KEY_SWAP_PRIO, -1)),
                    ShellManager.PrivilegeRequirement.ELEVATED_SYSFS, SWAP_TIMEOUT_MS
                ))
            }

            val algo = p.getString(KEY_ZRAM_ALGO, null)
            val size = p.getLong(KEY_ZRAM_SIZE, 0L)
            if (algo != null && size > 0) {
                val z = MemoryProvider.getZramStatus()
                when {
                    !z.present -> log += "zram: skipped (zram0 missing)"
                    z.currentAlgorithm == algo && z.disksizeBytes == size -> log += "zram: already set"
                    algo !in z.algorithms -> log += "zram: skipped ($algo unsupported)"
                    !root -> log += "zram: skipped (requires root)"
                    else -> record("zram $algo ${size shr 20}MB", runCatching {
                        ShellManager.exec(ShellScripts.zramReconfigure(algo, size), ShellManager.PrivilegeRequirement.ELEVATED_SYSFS, SWAP_TIMEOUT_MS)
                    }.getOrElse { ShellManager.CommandResult(-1, "", it.message.orEmpty()) })
                }
            }

            ShellScripts.VM_TUNABLES.keys.forEach { name ->
                if (!p.contains("vm_$name")) return@forEach
                val v = p.getInt("vm_$name", -1)
                if (ShellScripts.isValidVmValue(name, v)) {
                    record("vm.$name=$v", ShellManager.exec(ShellScripts.setVmTunable(name, v), ShellManager.PrivilegeRequirement.ELEVATED_SYSFS))
                }
            }
        }

        if (BootSettingsManager.isStorageEnabled(context)) {
            p.all.forEach { (key, value) ->
                if (!key.startsWith("io_")) return@forEach
                val dev = key.removePrefix("io_").substringBeforeLast('_')
                when {
                    key.endsWith("_sched") && value is String -> record("$dev scheduler=$value", StorageProvider.setScheduler(dev, value))
                    key.endsWith("_ra") && value is Int -> record("$dev read_ahead_kb=$value", StorageProvider.setReadAhead(dev, value))
                }
            }
        }
        log
    }
}

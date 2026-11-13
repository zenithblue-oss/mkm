package com.ivarna.mkm.shell

import com.ivarna.mkm.utils.ShellUtils
import java.util.concurrent.ConcurrentHashMap

/** Validated sysfs read/write facade for CPU and GPU tuning. */
object SysfsTuningExecutor {
    enum class SysfsAccess {
        READ_WRITE,
        READ_ONLY,
        UNAVAILABLE,
        UNKNOWN
    }

    /** Probe results per path; dropped on a write to that path or when the backend set changes. */
    private val accessCache = ConcurrentHashMap<String, SysfsAccess>()
    @Volatile private var cacheBackendKey: Pair<Boolean, Boolean>? = null

    fun read(path: String): String {
        if (!isSafeSysfsPath(path)) return ""
        ShellUtils.readFile(path).takeIf { it.isNotBlank() }?.let { return it.trim() }
        return ShellManager.exec("cat \"$path\"", ShellManager.PrivilegeRequirement.ELEVATED_SYSFS).stdout.trim()
    }

    fun write(path: String, value: String): ShellManager.CommandResult {
        if (!isSafeSysfsPath(path) || !isSafeValue(value)) {
            return ShellManager.CommandResult(-1, "", "Rejected unsafe sysfs path or value")
        }
        accessCache.remove(path)
        val result = ShellManager.exec(writeScript(path, value), ShellManager.PrivilegeRequirement.ELEVATED_SYSFS)
        if (result.isSuccess) return result
        // Shizuku (uid 2000) cannot write root-owned kernel nodes: say so instead of a bare EACCES.
        val needsRoot = result.stderr.contains("ermission denied") &&
            !ShellManager.hasRootCapability() && ShellManager.hasShizuku()
        return if (needsRoot) {
            result.copy(stderr = listOf(result.stderr, ShellManager.appString(com.ivarna.mkm.R.string.requires_root_hint))
                .filter { it.isNotBlank() }.joinToString(" — "))
        } else result
    }

    /**
     * Writes without touching the mode first. Only when the write fails with
     * EACCES is `u+w` added temporarily; the original mode is restored so
     * group-writable nodes used by vendor HALs keep working.
     */
    internal fun writeScript(path: String, value: String): String {
        val v = "'${quote(value)}'"
        return "e=\$(printf '%s' $v 2>&1 > \"$path\") && exit 0; " +
            "case \"\$e\" in *ermission*) ;; *) echo \"\$e\" >&2; exit 1;; esac; " +
            "m=\$(stat -c %a \"$path\") || { echo \"\$e\" >&2; exit 1; }; " +
            "chmod u+w \"$path\" 2>/dev/null || { echo \"\$e\" >&2; exit 1; }; " +
            "printf '%s' $v > \"$path\"; r=\$?; chmod \"\$m\" \"$path\" 2>/dev/null; exit \$r"
    }

    fun readLong(path: String): Long? = read(path).toLongOrNull()?.takeIf { it > 0L }

    fun exists(path: String): Boolean {
        if (!isSafeSysfsPath(path)) return false
        if (java.io.File(path).exists()) return true
        return ShellManager.exec("test -e \"$path\"", ShellManager.PrivilegeRequirement.ELEVATED_SYSFS).isSuccess
    }

    /** Probes the exact node through the backend that would perform tuning. Never changes its mode. */
    fun access(path: String): SysfsAccess {
        if (!isSafeSysfsPath(path)) return SysfsAccess.UNAVAILABLE
        val hasRoot = ShellManager.hasRoot()
        val hasShizuku = ShellManager.hasShizuku()
        val key = hasRoot to hasShizuku
        if (cacheBackendKey != key) {
            accessCache.clear()
            cacheBackendKey = key
        }
        accessCache[path]?.let { return it }
        val access = if (hasRoot || hasShizuku) {
            val result = ShellManager.exec(
                "if ! test -e \"$path\"; then printf '%s' UNAVAILABLE; " +
                    "elif ! test -r \"$path\"; then printf '%s' UNAVAILABLE; " +
                    "elif test -w \"$path\"; then printf '%s' READ_WRITE; " +
                    "else printf '%s' READ_ONLY; fi",
                ShellManager.PrivilegeRequirement.ELEVATED_SYSFS
            )
            classifyAccessProbe(result.exitCode, result.stdout)
        } else {
            val file = java.io.File(path)
            when {
                !file.exists() || !file.canRead() -> SysfsAccess.UNAVAILABLE
                file.canWrite() -> SysfsAccess.READ_WRITE
                else -> SysfsAccess.READ_ONLY
            }
        }
        if (access != SysfsAccess.UNKNOWN) accessCache[path] = access
        return access
    }

    fun canWrite(path: String): Boolean = access(path) == SysfsAccess.READ_WRITE

    fun accessReason(access: SysfsAccess): String? = when (access) {
        SysfsAccess.READ_WRITE -> null
        // Shizuku/ADB runs as uid 2000, which cannot write kernel tuning nodes owned by system/root.
        SysfsAccess.READ_ONLY -> if (!ShellManager.hasRootCapability() && ShellManager.hasShizuku()) {
            ShellManager.appString(com.ivarna.mkm.R.string.requires_root_hint)
        } else {
            "Kernel exposes this value as read-only"
        }
        SysfsAccess.UNAVAILABLE -> if (ShellManager.hasRoot() || ShellManager.hasShizuku()) {
            "Kernel node is unavailable or unreadable"
        } else {
            "Requires root or Shizuku access"
        }
        SysfsAccess.UNKNOWN -> "Unable to determine node writability"
    }

    fun classifyAccessProbe(exitCode: Int, output: String): SysfsAccess {
        if (exitCode == -1 && output.isBlank()) return SysfsAccess.UNKNOWN
        return when (output.trim()) {
            "READ_WRITE" -> SysfsAccess.READ_WRITE
            "READ_ONLY" -> SysfsAccess.READ_ONLY
            "UNAVAILABLE" -> SysfsAccess.UNAVAILABLE
            else -> SysfsAccess.UNKNOWN
        }
    }

    /** Absolute /sys path with no `.`/`..`/empty segments and no shell metacharacters. */
    fun isSafeSysfsPath(path: String): Boolean =
        path.startsWith("/sys/") &&
            path.removePrefix("/").split('/').none { it.isEmpty() || it == "." || it == ".." } &&
            path.none {
                it == '\n' || it == '\r' || it == ';' || it == '|' || it == '`' ||
                    it == '$' || it == '"' || it == '\\' || it == '\'' || it == '\u0000'
            }

    fun isSafeValue(value: String): Boolean = value.matches(Regex("[A-Za-z0-9_.:+-]+"))

    private fun quote(value: String): String = value.replace("'", "'\\''")
}

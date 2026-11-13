package com.ivarna.mkm.utils

import java.io.File
import java.io.IOException
import java.util.Locale

object ShellUtils {
    fun readFile(path: String): String {
        return try {
            val file = File(path)
            if (!file.exists() || !file.canRead()) return ""
            // Use a small buffer and read until EOF for virtual files
            file.inputStream().use { input ->
                input.bufferedReader().use { it.readText() }
            }.trim()
        } catch (e: Exception) {
            ""
        }
    }

    /** [kilobytes] are kernel KiB (/proc/meminfo); shown in SI units like Android Settings. */
    fun formatSize(kilobytes: Long): String = FormatUtils.formatBytes(kilobytes * 1024)

    fun formatFreq(khz: Long): String {
        return if (khz >= 1000000) {
            String.format(Locale.US, "%.2f GHz", khz / 1000000.0)
        } else {
            "${khz / 1000} MHz"
        }
    }

    /** devfreq Hz string ("273000000") -> "273 MHz"; non-numeric text passes through. */
    fun formatHz(raw: String): String =
        raw.trim().toLongOrNull()?.takeIf { it > 0 }?.let { formatFreq(it / 1000) } ?: raw

    fun writeFile(path: String, content: String): Boolean {
        return try {
            File(path).writeText(content)
            true
        } catch (e: Exception) {
            false
        }
    }
}

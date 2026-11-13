package com.ivarna.mkm.utils

import java.util.Locale

object FormatUtils {
    private val UNITS = arrayOf("kB", "MB", "GB", "TB", "PB", "EB")

    /** SI (1000-based) sizes, matching Android Settings. */
    fun formatBytes(bytes: Long): String {
        if (bytes < 1000) return "$bytes B"
        var value = bytes.toDouble()
        var i = -1
        while (value >= 1000 && i < UNITS.lastIndex) { value /= 1000; i++ }
        return String.format(Locale.US, "%.1f %s", value, UNITS[i])
    }
}

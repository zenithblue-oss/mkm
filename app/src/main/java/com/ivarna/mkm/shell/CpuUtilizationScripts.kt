package com.ivarna.mkm.shell

object CpuUtilizationScripts {
    /** Overall and per-core CPU stats from /proc/stat in one call. */
    fun getCpuStatAll(): String {
        return "grep '^cpu' /proc/stat"
    }
}

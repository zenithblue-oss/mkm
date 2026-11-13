package com.ivarna.mkm.shell

object UfsScripts {
    /** Finds the UFS devfreq directory. Matches both `*ufshci*` (MediaTek) and `*ufshc*` (Qualcomm). */
    fun findUfsDir(): String {
        return $$"""
            (
            for path in /sys/class/devfreq/*ufshc*; do
                [ -e "$path/governor" ] && { echo "$path"; exit 0; }
            done
            for p in $(find /sys/devices/platform -maxdepth 3 -name "*ufshc*" 2>/dev/null); do
                [ -e "$p/governor" ] && { echo "$p"; exit 0; }
                for sub in "$p/devfreq/"*; do
                    [ -e "$sub/governor" ] && { echo "$sub"; exit 0; }
                done
            done
            echo "Not found"
            exit 1
            )
        """.trimIndent()
    }

    fun getUfsInfo(path: String): String {
        if (!SysfsTuningExecutor.isSafeSysfsPath(path)) return "false"
        return $$"""
            echo "GOV=$(cat "$$path/governor")"
            echo "AVAIL=$(cat "$$path/available_governors")"
            echo "FREQ=$(cat "$$path/cur_freq" 2>/dev/null || cat "$$path/target_freq" 2>/dev/null)"
            echo "MIN_FREQ=$(cat "$$path/min_freq" 2>/dev/null)"
            echo "MAX_FREQ=$(cat "$$path/max_freq" 2>/dev/null)"
            echo "AVAIL_FREQ=$(cat "$$path/available_frequencies" 2>/dev/null)"
        """.trimIndent()
    }

    /** Prints D=<dir> and key=value for the first UFS health_descriptor found. */
    fun getHealthInfo(): String {
        return $$"""
            (
            for d in /sys/devices/platform/soc/*ufshc*/health_descriptor /sys/devices/platform/*ufshc*/health_descriptor \
                     /sys/bus/platform/devices/*ufshc*/health_descriptor /sys/class/ufs*/device/health_descriptor \
                     /sys/class/scsi_host/host0/device/health_descriptor; do
                if [ -d "$d" ]; then
                    echo "D=$d"
                    for f in life_time_estimation_a life_time_estimation_b eol_info pre_eol_info; do
                        [ -e "$d/$f" ] && echo "$f=$(cat "$d/$f" 2>/dev/null)"
                    done
                    exit 0
                fi
            done
            exit 1
            )
        """.trimIndent()
    }

    private fun invalid() = "(echo 'Rejected unsafe UFS path or value' >&2; exit 1)"

    private fun write(path: String, node: String, value: String): String {
        if (!SysfsTuningExecutor.isSafeSysfsPath(path) || !SysfsTuningExecutor.isSafeValue(value)) return invalid()
        return "printf '%s' '$value' > \"$path/$node\""
    }

    /** Boot-time script forms (BootApplyService). UI writes go through UfsProvider + SysfsTuningExecutor. */
    fun setGovernor(path: String, governor: String): String = write(path, "governor", governor)

    fun setMinFreq(path: String, freq: String): String = write(path, "min_freq", freq)

    fun setMaxFreq(path: String, freq: String): String = write(path, "max_freq", freq)
}

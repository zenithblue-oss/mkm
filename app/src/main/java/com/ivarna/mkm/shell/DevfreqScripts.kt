package com.ivarna.mkm.shell

object DevfreqScripts {

    /**
     * Finds the RAM/interconnect devfreq directory (bimc, ddr, m4m, dvfsrc, llcc).
     * No "any devfreq node" fallback: a random GPU/UFS node must never be labelled DDR.
     */
    fun findDevfreqDir(): String {
        return $$"""
            (
            for name in mtk-dvfsrc-devfreq soc:qcom,bimc soc:qcom,ddr_bw soc:qcom,m4m soc:qcom,cpu-cpu-llcc-bw; do
                if [ -e "/sys/class/devfreq/$name/governor" ]; then echo "/sys/class/devfreq/$name"; exit 0; fi
            done
            for path in /sys/class/devfreq/*; do
                case "$(basename "$path")" in
                    *bimc*|*ddr*|*m4m*|*dvfsrc*|*llcc*) [ -e "$path/governor" ] && { echo "$path"; exit 0; } ;;
                esac
            done
            echo "Not found"
            exit 1
            )
        """.trimIndent()
    }

    fun getDevfreqInfo(path: String): String {
        if (!SysfsTuningExecutor.isSafeSysfsPath(path)) return "false"
        return $$"""
            echo "GOV=$(cat "$$path/governor" 2>/dev/null)"
            echo "AVAIL=$(cat "$$path/available_governors" 2>/dev/null)"
            echo "FREQ=$(cat "$$path/cur_freq" 2>/dev/null || cat "$$path/target_freq" 2>/dev/null)"
            echo "AVAIL_FREQ=$(cat "$$path/available_frequencies" 2>/dev/null)"
            echo "MIN_FREQ=$(cat "$$path/min_freq" 2>/dev/null)"
            echo "MAX_FREQ=$(cat "$$path/max_freq" 2>/dev/null)"
        """.trimIndent()
    }

    private fun invalid(what: String) = "(echo 'Rejected unsafe $what' >&2; exit 1)"

    /** Boot-time script form (BootApplyService). UI uses [com.ivarna.mkm.data.provider.DevfreqProvider]. */
    fun setGovernor(path: String, governor: String): String {
        if (!SysfsTuningExecutor.isSafeSysfsPath(path) || !SysfsTuningExecutor.isSafeValue(governor)) return invalid("devfreq governor")
        return "printf '%s' '$governor' > \"$path/governor\""
    }

    /**
     * Pins the frequency: userspace/ondemand set_freq if present, else min/max.
     * Raising writes max first, lowering writes min first; writes are chained with && so failure is reported.
     */
    fun setFreq(path: String, freq: String): String {
        if (!SysfsTuningExecutor.isSafeSysfsPath(path) || freq.toLongOrNull()?.takeIf { it > 0 } == null) return invalid("devfreq frequency")
        return $$"""
            (
            P="$$path"; F=$$freq
            if [ -w "$P/userspace/set_freq" ]; then printf '%s' $F > "$P/userspace/set_freq"
            elif [ -w "$P/ondemand/set_freq" ]; then printf '%s' $F > "$P/ondemand/set_freq"
            else
                MAX=$(cat "$P/max_freq" 2>/dev/null); case "$MAX" in ''|*[!0-9]*) MAX=0;; esac
                if [ "$F" -ge "$MAX" ]; then
                    printf '%s' $F > "$P/max_freq" && printf '%s' $F > "$P/min_freq"
                else
                    printf '%s' $F > "$P/min_freq" && printf '%s' $F > "$P/max_freq"
                fi
            fi
            )
        """.trimIndent()
    }
}

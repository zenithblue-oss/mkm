package com.ivarna.mkm.shell

object ThermalScripts {
    /**
     * Get list of thermal zones and their types.
     * Output format: path/to/temp:type
     */
    fun getThermalInfo(): String {
        // Use grep to read all files at once. -H ensures filename is printed.
        return "grep -H . /sys/class/thermal/thermal_zone*/type /sys/class/thermal/thermal_zone*/temp 2>/dev/null"
    }

    /** Known thermal daemons/HALs across vendors (Qualcomm, Xiaomi, MTK, Pixel, AOSP). */
    val THERMAL_SERVICES = listOf(
        "thermal-engine", "thermald", "mi_thermald", "thermal_manager", "vendor.thermal-engine",
        "android.hardware.thermal-service", "android.hardware.thermal-service.pixel",
        "vendor.thermal-hal-2-0", "vendor.thermal-hal-1-0", "thermal-hal", "thermalloadalgod", "thermalservice"
    )

    /**
     * Stops every thermal service that exists and reports the real outcome per service.
     * Output lines: `svc:<name>:<init.svc state after stop>`, `zones:<disabled>/<total>`, `msm:<ok|fail>`.
     */
    fun stopThermalEngine(): String =
        "for s in ${THERMAL_SERVICES.joinToString(" ")}; do " +
            "st=\$(getprop init.svc.\$s); [ -z \"\$st\" ] && continue; " +
            "stop \$s 2>/dev/null; sleep 0.1; echo \"svc:\$s:\$(getprop init.svc.\$s)\"; done; " +
            "ok=0; n=0; for f in /sys/class/thermal/thermal_zone*/mode; do [ -e \"\$f\" ] || continue; n=\$((n+1)); " +
            "echo disabled > \"\$f\" 2>/dev/null && ok=\$((ok+1)); done; echo \"zones:\$ok/\$n\"; " +
            "for p in /sys/module/msm_thermal/parameters/enabled /sys/kernel/msm_thermal/enabled; do " +
            "[ -e \"\$p\" ] || continue; if echo 0 > \"\$p\" 2>/dev/null; then echo msm:ok; else echo msm:fail; fi; done; true"

    /** Restarts stopped thermal services and re-enables zone modes. */
    fun restartThermalEngine(): String =
        "for s in ${THERMAL_SERVICES.joinToString(" ")}; do " +
            "[ \"\$(getprop init.svc.\$s)\" = stopped ] && start \$s; done; " +
            "for f in /sys/class/thermal/thermal_zone*/mode; do [ -e \"\$f\" ] && echo enabled > \"\$f\" 2>/dev/null; done; true"

    /**
     * Read thermal configuration file.
     * Tries common locations.
     */
    fun getThermalConfigContent(): String {
        return "cat /vendor/etc/thermal-engine.conf 2>/dev/null || cat /system/etc/thermal-engine.conf 2>/dev/null"
    }

    /**
     * Rewrites set_point/set_point_clr only inside CPU sections of thermal-engine.conf
     * (sections whose `sensor` is a CPU sensor). Backs up the original once.
     * @param limitTemp Limit in degrees Celsius (validated 40..110)
     */
    fun setThermalLimit(limitTemp: Int): String {
        require(limitTemp in 40..110) { "Thermal limit out of range" }
        val limitMilli = limitTemp * 1000
        val clearMilli = (limitTemp - 5) * 1000 // Clear throttle 5 degrees below limit

        val findFile = "FILE=\$(ls /vendor/etc/thermal-engine.conf 2>/dev/null || ls /system/etc/thermal-engine.conf 2>/dev/null | head -n 1); [ -n \"\$FILE\" ]"
        val backup = "{ [ -f \"\$FILE.bak\" ] || cp \"\$FILE\" \"\$FILE.bak\"; }"
        // Track the current section; only edit set_point lines once its sensor is a CPU sensor.
        val awk = "awk '/^\\[/{cpu=0} /^[ \\t]*sensor[ \\t]/{s=tolower(\$2); cpu=(s ~ /cpu|big|mid|little|cpuss|mtktscpu|soc_therm/ && s !~ /battery|skin|charger|bcl|ibat|vbat|ocp|soc\$|socd|lvl|modem/)} " +
            "cpu && \$1==\"set_point\" && \$2+0>=10000 {sub(/[0-9]+/, \"$limitMilli\")} " +
            "cpu && \$1==\"set_point_clr\" && \$2+0>=10000 {sub(/[0-9]+/, \"$clearMilli\")} {print}' \"\$FILE\" > \"\$FILE.mkm\" && cat \"\$FILE.mkm\" > \"\$FILE\"; rm -f \"\$FILE.mkm\""
        return "$findFile && $backup && $awk"
    }

    /**
     * Zone types plus all trip points.
     * Output format: path:value (grep -H)
     */
    fun getTripPoints(): String =
        "grep -H . /sys/class/thermal/thermal_zone*/type /sys/class/thermal/thermal_zone*/trip_point_*_type /sys/class/thermal/thermal_zone*/trip_point_*_temp 2>/dev/null"

    /** Cooling devices: path:value lines for type/cur_state/max_state. */
    fun getCoolingDevices(): String =
        "grep -H . /sys/class/thermal/cooling_device*/type /sys/class/thermal/cooling_device*/cur_state /sys/class/thermal/cooling_device*/max_state 2>/dev/null"
}

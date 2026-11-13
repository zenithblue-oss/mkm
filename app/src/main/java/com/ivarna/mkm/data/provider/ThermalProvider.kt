package com.ivarna.mkm.data.provider

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.ivarna.mkm.shell.ShellManager
import com.ivarna.mkm.shell.ShizukuManager
import com.ivarna.mkm.shell.SysfsTuningExecutor
import com.ivarna.mkm.shell.ThermalScripts
import com.ivarna.mkm.utils.AppLogger
import kotlin.math.abs

data class ThermalZone(
    val id: String,
    val type: String,
    val temp: Float
)

data class ThermalStatus(
    val zones: List<ThermalZone>,
    val maxTemp: Float,
    val cpuTemp: Float = 0f,
    val batteryTemp: Float = 0f,
    val currentLimit: Int = 0
)

data class CoolingDevice(val type: String, val cur: Int, val max: Int)

/** Throttling telemetry: active cooling devices plus Android thermal status/headroom. */
data class ThrottleInfo(
    val coolingDevices: List<CoolingDevice> = emptyList(),
    /** PowerManager.THERMAL_STATUS_* or -1 when unavailable (API < 29). */
    val androidStatus: Int = -1,
    /** getThermalHeadroom(10s) or null (API < 30 / unsupported). 1.0 = severe throttling. */
    val headroom: Float? = null
)

/** Real per-service outcome of [ThermalProvider.disableThrottling]. */
data class ThermalDisableReport(
    val stopped: List<String> = emptyList(),
    val running: List<String> = emptyList(),
    val zonesDisabled: Int = 0,
    val zonesTotal: Int = 0
) {
    val anyEffect: Boolean get() = stopped.isNotEmpty() || zonesDisabled > 0
}

data class TripPoint(val zone: String, val zoneType: String, val index: Int, val type: String, val tempMilli: Int)

object ThermalProvider {
    // Zones whose "temp" is not a temperature (BCL current/voltage/levels, SoC charge).
    private val NON_TEMP_ZONE = Regex("bcl|ibat|vbat|ocp|soc$|socd|lvl", RegexOption.IGNORE_CASE)
    private val NON_CPU_ZONE = Regex("battery|skin|charger|bcl|ibat|vbat|ocp|soc$|socd|lvl|modem", RegexOption.IGNORE_CASE)
    private val CPU_ZONE = Regex("cpu|soc_therm|(^|[_-])(big|mid|little)([_\\-0-9]|$)", RegexOption.IGNORE_CASE)

    internal fun isTemperatureZone(type: String): Boolean = !NON_TEMP_ZONE.containsMatchIn(type)
    internal fun isCpuZone(type: String): Boolean = CPU_ZONE.containsMatchIn(type) && !NON_CPU_ZONE.containsMatchIn(type)

    /** sysfs thermal zones report milli°C; some vendor zones already report °C. */
    internal fun normalizeZoneTemp(raw: Float): Float = if (abs(raw) > 1000f) raw / 1000f else raw

    /** power_supply/battery/temp is tenths of °C. Values > 1000 can only be milli°C (non-standard drivers). */
    internal fun normalizeBatteryTemp(raw: Float): Float = when {
        abs(raw) > 1000f -> raw / 1000f
        raw % 1f != 0f -> raw // already a parsed °C value
        else -> raw / 10f
    }

    fun getThermalStatus(fetchLimit: Boolean = false): ThermalStatus {
        val result = ShellManager.exec(ThermalScripts.getThermalInfo())
        val zones = mutableListOf<ThermalZone>()
        val zoneMap = mutableMapOf<String, Pair<String, Float>>() // Id -> (Type, Temp)

        // Always run the block: the battery fallbacks below must work even when the zone scan fails (non-root/Shizuku)
        (if (result.isSuccess) result.stdout else "").lines().forEach { line ->
            // Grep format: /sys/class/thermal/thermal_zone0/temp:54000
            val path = line.substringBefore(":", "")
            if (path.isEmpty()) return@forEach
            val value = line.substringAfter(":").trim()
            val filename = path.substringAfterLast("/")
            val zoneId = path.substringBeforeLast("/").substringAfterLast("/")
            var current = zoneMap[zoneId] ?: ("" to 0f)
            if (filename == "type") {
                current = current.copy(first = value)
            } else if (filename == "temp") {
                current = current.copy(second = normalizeZoneTemp(value.toFloatOrNull() ?: 0f))
            }
            zoneMap[zoneId] = current
        }

        var cpuTemp = 0f
        var tsensTemp = 0f
        var batteryTemp = 0f

        zoneMap.forEach { (id, data) ->
            val (type, temp) = data
            if (type.isEmpty() || !isTemperatureZone(type)) return@forEach
            val lowerType = type.lowercase()
            when {
                lowerType.contains("battery") -> batteryTemp = temp
                isCpuZone(type) -> if (temp > cpuTemp) cpuTemp = temp
                lowerType.contains("tsens_tz_sensor") -> if (temp > tsensTemp) tsensTemp = temp
            }
            if (temp > 0 && !lowerType.contains("battery")) {
                zones.add(ThermalZone(id, type, temp))
            }
        }

        if (batteryTemp <= 0f || batteryTemp > 100f) {
            val battOutput = ShellManager.exec("cat /sys/class/power_supply/battery/temp 2>/dev/null").stdout.trim()
            batteryTemp = normalizeBatteryTemp(battOutput.toFloatOrNull() ?: 0f)
        }
        // Last resort: BatteryManager sticky intent (tenths of a degree C), no privileges needed
        if (batteryTemp <= 0f || batteryTemp > 100f) {
            val tenths = ShizukuManager.appContext
                ?.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            batteryTemp = tenths / 10f
        }

        if (cpuTemp <= 0f) cpuTemp = tsensTemp
        if (cpuTemp <= 0f) cpuTemp = zones.maxOfOrNull { it.temp } ?: 0f

        return ThermalStatus(
            zones = zones.sortedByDescending { it.temp },
            maxTemp = zones.maxOfOrNull { it.temp } ?: 0f,
            cpuTemp = cpuTemp,
            batteryTemp = batteryTemp,
            currentLimit = if (fetchLimit) parseThermalLimit() else 0
        )
    }

    fun getThermalLimit(): Int = parseThermalLimit()

    /** Parses `grep -H` output of [ThermalScripts.getTripPoints] into trip points with their zone type. */
    internal fun parseTripPoints(output: String): List<TripPoint> {
        val zoneTypes = mutableMapOf<String, String>()
        val trips = mutableMapOf<Pair<String, Int>, Pair<String?, Int?>>()
        val tripRegex = Regex("trip_point_(\\d+)_(type|temp)")
        output.lines().forEach { line ->
            val path = line.substringBefore(":", "")
            if (path.isEmpty()) return@forEach
            val value = line.substringAfter(":").trim()
            val zone = path.substringBeforeLast("/")
            val file = path.substringAfterLast("/")
            if (file == "type") { zoneTypes[zone] = value; return@forEach }
            val m = tripRegex.matchEntire(file) ?: return@forEach
            val key = zone to m.groupValues[1].toInt()
            val cur = trips[key] ?: (null to null)
            trips[key] = if (m.groupValues[2] == "type") cur.copy(first = value) else cur.copy(second = value.toIntOrNull())
        }
        return trips.mapNotNull { (key, v) ->
            val type = v.first ?: return@mapNotNull null
            val temp = v.second ?: return@mapNotNull null
            TripPoint(key.first, zoneTypes[key.first] ?: "", key.second, type, temp)
        }
    }

    /** CPU-zone passive/active trips in a plausible 40..125 °C range. Shared by read and write. */
    internal fun cpuLimitTrips(trips: List<TripPoint>): List<TripPoint> = trips.filter {
        isCpuZone(it.zoneType) && (it.type == "passive" || it.type == "active") && it.tempMilli in 40001..125000
    }

    /**
     * Moves each CPU zone's lowest trip to [limit] and shifts its other stages by the same delta,
     * keeping the relative spacing of multi-stage trips. Returns trip-temp path -> new milli°C.
     */
    internal fun planTripWrites(trips: List<TripPoint>, limit: Int): Map<String, Int> =
        cpuLimitTrips(trips).groupBy { it.zone }.flatMap { (_, zoneTrips) ->
            val delta = limit * 1000 - zoneTrips.minOf { it.tempMilli }
            zoneTrips.map { "${it.zone}/trip_point_${it.index}_temp" to (it.tempMilli + delta).coerceIn(40000, 125000) }
        }.toMap()

    private fun parseThermalLimit(): Int {
        // 1. Try legacy/Qualcomm config first
        val configContent = ShellManager.exec(ThermalScripts.getThermalConfigContent()).stdout
        val regex = Regex("set_point\\s+(\\d+)")
        val configPoints = regex.findAll(configContent).map { it.groupValues[1].toInt() / 1000 }.filter { it > 60 }.toList()
        if (configPoints.isNotEmpty()) {
            return configPoints.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: 0
        }

        // 2. Fallback to kernel trip points, same CPU-zone filter as the write path
        val tripResult = ShellManager.exec(ThermalScripts.getTripPoints())
        if (!tripResult.isSuccess) return 0
        val limits = cpuLimitTrips(parseTripPoints(tripResult.stdout))
        return limits.minOfOrNull { it.tempMilli / 1000 } ?: 0
    }

    fun setThermalLimit(limit: Int): Boolean {
        if (limit !in 40..110) return false
        var success = false
        AppLogger.log("Setting limit to $limit°C")

        // 1. Try Config (CPU sections only)
        val configResult = ShellManager.exec(ThermalScripts.setThermalLimit(limit), ShellManager.PrivilegeRequirement.ELEVATED_SYSFS)
        if (configResult.isSuccess) {
            AppLogger.log("Config update success. Restarting service...")
            ShellManager.exec(ThermalScripts.restartThermalEngine(), ShellManager.PrivilegeRequirement.ELEVATED_SYSFS)
            success = true
        } else {
            AppLogger.log("Config update failed: ${configResult.stderr}")
        }

        // 2. Kernel trip points of CPU/SoC zones only
        val tripResult = ShellManager.exec(ThermalScripts.getTripPoints(), ShellManager.PrivilegeRequirement.ELEVATED_SYSFS)
        if (!tripResult.isSuccess) {
            AppLogger.log("Failed to scan trip points: ${tripResult.stderr}")
            return success
        }
        val writes = planTripWrites(parseTripPoints(tripResult.stdout), limit)
        if (writes.isEmpty()) AppLogger.log("No CPU passive/active trip points found.")
        writes.forEach { (path, milli) ->
            val res = SysfsTuningExecutor.write(path, milli.toString())
            if (res.isSuccess) {
                success = true
                AppLogger.log("Updated trip point: $path=$milli")
            } else {
                AppLogger.log("Failed to update $path: ${res.stderr}")
            }
        }
        AppLogger.log(if (success) "Limit set successfully (at least partially)." else "Failed to set limit.")
        return success
    }

    internal fun parseDisableReport(output: String): ThermalDisableReport {
        val stopped = mutableListOf<String>()
        val running = mutableListOf<String>()
        var ok = 0
        var total = 0
        output.lines().map { it.trim() }.forEach { line ->
            when {
                line.startsWith("svc:") -> {
                    val name = line.removePrefix("svc:").substringBeforeLast(":")
                    if (line.substringAfterLast(":") == "stopped") stopped += name else running += name
                }
                line.startsWith("zones:") -> {
                    val parts = line.removePrefix("zones:").split("/")
                    ok = parts.getOrNull(0)?.toIntOrNull() ?: 0
                    total = parts.getOrNull(1)?.toIntOrNull() ?: 0
                }
            }
        }
        return ThermalDisableReport(stopped, running, ok, total)
    }

    fun disableThrottling(): ThermalDisableReport {
        AppLogger.log("Disabling throttling services...")
        val res = ShellManager.exec(ThermalScripts.stopThermalEngine(), ShellManager.PrivilegeRequirement.ELEVATED_SYSFS)
        val report = parseDisableReport(res.stdout)
        AppLogger.log("Stopped=${report.stopped} running=${report.running} zones=${report.zonesDisabled}/${report.zonesTotal} stderr=${res.stderr}")
        return report
    }

    fun restoreThermal(): Boolean {
        val res = ShellManager.exec(ThermalScripts.restartThermalEngine(), ShellManager.PrivilegeRequirement.ELEVATED_SYSFS)
        AppLogger.log("Restore thermal: exit=${res.exitCode} ${res.stderr}")
        return res.isSuccess
    }

    fun getThrottleInfo(context: Context): ThrottleInfo {
        val devices = mutableMapOf<String, Triple<String, Int, Int>>()
        ShellManager.exec(ThermalScripts.getCoolingDevices()).stdout.lines().forEach { line ->
            val path = line.substringBefore(":", "")
            if (path.isEmpty()) return@forEach
            val value = line.substringAfter(":").trim()
            val dev = path.substringBeforeLast("/")
            val cur = devices[dev] ?: Triple("", 0, 0)
            devices[dev] = when (path.substringAfterLast("/")) {
                "type" -> cur.copy(first = value)
                "cur_state" -> cur.copy(second = value.toIntOrNull() ?: 0)
                "max_state" -> cur.copy(third = value.toIntOrNull() ?: 0)
                else -> cur
            }
        }
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val status = if (Build.VERSION.SDK_INT >= 29) pm?.currentThermalStatus ?: -1 else -1
        val headroom = if (Build.VERSION.SDK_INT >= 30) pm?.getThermalHeadroom(10)?.takeIf { !it.isNaN() } else null
        return ThrottleInfo(
            coolingDevices = devices.values.filter { it.first.isNotEmpty() }.map { CoolingDevice(it.first, it.second, it.third) },
            androidStatus = status,
            headroom = headroom
        )
    }
}

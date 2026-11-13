package com.ivarna.mkm.data.provider

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import com.ivarna.mkm.R
import com.ivarna.mkm.data.model.BatteryDrainer
import com.ivarna.mkm.data.model.BatterySnapshot
import com.ivarna.mkm.shell.ShellManager

class BatteryProvider(context: Context) {

    companion object {
        private const val PREFS = "battery_provider_prefs"
        private const val KEY_DISCHARGE_SIGN = "discharge_sign"
        private const val KEY_SIGN_FLIP_SEEN = "sign_flip_seen"
        private const val KEY_BM_CURRENT_IS_UA = "bm_current_is_ua"
        /** Below this magnitude current is noise; never learn the sign from it. */
        private const val SIGN_LEARN_MIN_MA = 20

        /** charge_full / design / charge counter: values under 100000 are already mAh, else µAh. */
        fun capacityToMah(raw: Long): Int = when {
            raw <= 0L -> 0
            raw < 100_000L -> raw.toInt()
            else -> (raw / 1000L).toInt()
        }

        /**
         * Signed current in mA, positive = into the battery.
         *
         * [dischargeSign] is the raw kernel sign learned while unplugged (0 = unknown).
         * Until a sign flip has been seen ([signFlipSeen]) the device may report magnitude only,
         * so the plugged state decides direction (keeps the charging sign correct, GH #15).
         */
        fun directedCurrentMa(
            magnitudeMa: Int,
            rawSign: Int,
            dischargeSign: Int,
            signFlipSeen: Boolean,
            isPlugged: Boolean
        ): Int {
            if (magnitudeMa == 0) return 0
            val intoBattery = if (dischargeSign != 0 && signFlipSeen && rawSign != 0) {
                rawSign != dischargeSign
            } else {
                isPlugged
            }
            return if (intoBattery) magnitudeMa else -magnitudeMa
        }

        /**
         * Parses `dumpsys batterystats --checkin` lines:
         *  - `9,0,i,uid,<uid>,<package>` (uid → package map)
         *  - `9,<uid>,l,pwi,uid,<mAh>,...` (since-charged power use per uid)
         * Returns uids summed and sorted by mAh desc; label = package name (or "").
         */
        fun parseDrainers(checkin: String): List<BatteryDrainer> {
            val packages = HashMap<Int, String>()
            val mah = HashMap<Int, Double>()
            checkin.lineSequence().forEach { line ->
                val p = line.trim().split(',')
                if (p.size < 6) return@forEach
                if (p[2] == "i" && p[3] == "uid") {
                    val uid = p[4].toIntOrNull() ?: return@forEach
                    packages.putIfAbsent(uid, p[5])
                } else if (p[2] == "l" && p[3] == "pwi" && p[4] == "uid") {
                    val uid = p[1].toIntOrNull() ?: return@forEach
                    val value = p[5].toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 } ?: return@forEach
                    mah[uid] = (mah[uid] ?: 0.0) + value
                }
            }
            return mah.entries
                .sortedByDescending { it.value }
                .map { (uid, v) -> packages[uid].orEmpty().let { BatteryDrainer(uid, it, it, v) } }
        }

        /** Elevated shell (root/Shizuku). Blocking — call off the main thread. */
        fun readTopDrainers(context: Context, limit: Int = 10): List<BatteryDrainer> {
            val out = ShellManager.exec(
                "dumpsys batterystats --checkin | grep -E ',(i,uid|l,pwi,uid),'",
                ShellManager.PrivilegeRequirement.ELEVATED_SYSFS,
                timeoutMs = 30_000L
            ).stdout
            val pm = context.packageManager
            return parseDrainers(out).take(limit).map { d ->
                val label = when {
                    d.packageName.isNotEmpty() -> runCatching {
                        pm.getApplicationLabel(pm.getApplicationInfo(d.packageName, 0)).toString()
                    }.getOrDefault(d.packageName)
                    else -> pm.getPackagesForUid(d.uid)?.firstOrNull()
                        ?: context.getString(R.string.drainer_uid_format, d.uid)
                }
                d.copy(label = label)
            }
        }
    }

    private val appContext = context.applicationContext
    private val calibrationManager = PowerCalibrationManager(appContext)
    private val signPrefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private var lastCurrentMa = 0
    private var lastVoltageMv = 0
    private var lastZeroReadTime = 0L
    private val ZERO_STALENESS_MS = 5_000L

    private var cachedSnapshot: BatterySnapshot? = null
    private var cacheTimeMs = 0L
    private val CACHE_TTL_MS = 5_000L

    private var lastShellSnapshot: ShellBatteryReadings? = null
    private var shellCacheTimeMs = 0L
    private val SHELL_CACHE_TTL_MS = 2_000L

    fun invalidate() {
        cachedSnapshot = null
        cacheTimeMs = 0L
        lastShellSnapshot = null
        shellCacheTimeMs = 0L
        lastCurrentMa = 0
        lastVoltageMv = 0
        lastZeroReadTime = 0L
    }

    fun getSnapshot(context: Context): BatterySnapshot {
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val currentIsCharging = BatteryCharging.readAndroidCharging(sticky)
        val cached = cachedSnapshot
        if (cached != null && cached.isCharging != currentIsCharging) {
            invalidate()
        }

        val now = System.currentTimeMillis()
        cachedSnapshot?.let {
            if (now - cacheTimeMs < CACHE_TTL_MS) return it
        }
        val snap = computeSnapshot(context, sticky)
        cachedSnapshot = snap
        cacheTimeMs = now
        return snap
    }

    private fun computeSnapshot(context: Context, stickyIntent: Intent? = null): BatterySnapshot {
        val intent = stickyIntent ?: context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return BatterySnapshot(0, 0f, 0, 0, false)

        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val percent = (level * 100) / scale.coerceAtLeast(1)

        val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
        val temperatureC = tempTenths / 10f

        val isCharging = BatteryCharging.readAndroidCharging(intent)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val stickyVoltageMv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)

        val shellReadings = readShellBatteryData()

        // Magnitude + raw kernel sign, units normalised like PowerProvider.
        var magnitudeMa: Int
        var rawSign: Int
        var voltageMvRead: Int
        val sysI = shellReadings.currentRaw
        val sysV = shellReadings.voltageRaw
        if (sysI != null && sysV != null) {
            val (ua, uv) = PowerProvider.normalizeCurrentVoltage(sysI, sysV)
            magnitudeMa = (ua / 1000L).toInt()
            rawSign = java.lang.Long.signum(sysI)
            voltageMvRead = (uv / 1000L).toInt()
        } else {
            val bm = readCurrentFromBatteryManager(context)
            magnitudeMa = kotlin.math.abs(bm)
            rawSign = Integer.signum(bm)
            voltageMvRead = sysV?.let { (PowerProvider.normalizeCurrentVoltage(0, it).second / 1000L).toInt() }
                ?: stickyVoltageMv
        }

        var rawCurrentMa = directedCurrentMa(magnitudeMa, rawSign, isCharging)
        var rawVoltageMv = voltageMvRead

        // Plug changes already invalidate() in getSnapshot, so stale values never cross states.
        val now = System.currentTimeMillis()
        if (rawCurrentMa == 0 && lastCurrentMa != 0 && (now - lastZeroReadTime) < ZERO_STALENESS_MS) {
            rawCurrentMa = lastCurrentMa
        }
        if (rawVoltageMv == 0 && lastVoltageMv != 0 && (now - lastZeroReadTime) < ZERO_STALENESS_MS) {
            rawVoltageMv = lastVoltageMv
        }

        if (rawCurrentMa != 0) lastCurrentMa = rawCurrentMa
        if (rawVoltageMv != 0) lastVoltageMv = rawVoltageMv
        if (rawCurrentMa == 0 || rawVoltageMv == 0) {
            if (lastZeroReadTime == 0L) lastZeroReadTime = now
        } else {
            lastZeroReadTime = 0L
        }

        val currentMa = rawCurrentMa
        val voltageMv = rawVoltageMv

        val powerW = if (currentMa != 0 && voltageMv != 0) {
            PowerProvider.computePowerWatts(kotlin.math.abs(currentMa) * 1000L, voltageMv * 1000L)
                .takeIf { it <= 100f } ?: 0f
        } else 0f

        val multiplier = calibrationManager.getMultiplier()
        // Multiplier on the unsigned magnitude; sign = real current direction
        // (plugged-but-discharging shows negative), plugged state when current is unknown.
        val intoBattery = if (currentMa != 0) currentMa > 0 else isCharging
        val calibratedPowerW = powerW * multiplier
        val wattageW = if (intoBattery) powerW else -powerW
        val calibratedWattageW = if (intoBattery) calibratedPowerW else -calibratedPowerW

        // Rated stays 0 without sysfs design capacity: the charge-counter estimate is not a design value.
        val ratedCapacityMah = shellReadings.chargeFullDesignRaw?.let { capacityToMah(it) } ?: 0
        var estimatedCapacityMah = shellReadings.chargeFullRaw?.let { capacityToMah(it) } ?: 0
        if (estimatedCapacityMah == 0) {
            estimatedCapacityMah = readCapacityFromBatteryManager(context, percent)
        }

        val cycleCount = if (Build.VERSION.SDK_INT >= 34) {
            intent.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1).takeIf { it >= 0 }
        } else null

        return BatterySnapshot(
            percent = percent,
            temperatureC = temperatureC,
            voltageMv = voltageMv,
            currentMa = currentMa,
            isCharging = isCharging,
            wattageW = wattageW,
            calibratedWattageW = calibratedWattageW,
            calibrationMultiplier = multiplier,
            ratedCapacityMah = ratedCapacityMah,
            estimatedCapacityMah = estimatedCapacityMah,
            cycleCount = cycleCount ?: shellReadings.cycleCount,
            health = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, 0),
            chargeType = shellReadings.chargeType,
            isFull = status == BatteryManager.BATTERY_STATUS_FULL || (isCharging && percent >= 100)
        )
    }

    /** Learns the vendor discharge sign while unplugged, then applies [Companion.directedCurrentMa]. */
    private fun directedCurrentMa(magnitudeMa: Int, rawSign: Int, isPlugged: Boolean): Int {
        var dischargeSign = signPrefs.getInt(KEY_DISCHARGE_SIGN, 0)
        var flipSeen = signPrefs.getBoolean(KEY_SIGN_FLIP_SEEN, false)
        if (magnitudeMa >= SIGN_LEARN_MIN_MA && rawSign != 0) {
            if (!isPlugged && rawSign != dischargeSign) {
                dischargeSign = rawSign
                signPrefs.edit().putInt(KEY_DISCHARGE_SIGN, rawSign).apply()
            } else if (isPlugged && dischargeSign != 0 && rawSign != dischargeSign && !flipSeen) {
                flipSeen = true
                signPrefs.edit().putBoolean(KEY_SIGN_FLIP_SEEN, true).apply()
            }
        }
        return directedCurrentMa(magnitudeMa, rawSign, dischargeSign, flipSeen, isPlugged)
    }

    private fun readShellBatteryData(): ShellBatteryReadings {
        val now = System.currentTimeMillis()
        lastShellSnapshot?.let {
            if (now - shellCacheTimeMs < SHELL_CACHE_TTL_MS) return it
        }

        // Battery-first only — never latch onto usb/dc/ac when battery current is 0 (full).
        // Line 1: current voltage charge_full charge_full_design cycle_count. Line 2: charge_type.
        val script = """
            current=0; voltage=0; charge_full=0; charge_full_design=0; found=0; bat=""
            try_read() {
                ps=${'$'}1
                if [ -e "${'$'}ps/current_now" ] && [ -e "${'$'}ps/voltage_now" ]; then
                    c=${'$'}(cat "${'$'}ps/current_now" 2>/dev/null)
                    v=${'$'}(cat "${'$'}ps/voltage_now" 2>/dev/null)
                    if [ -n "${'$'}v" ] && [ "${'$'}v" != "0" ]; then
                        current=${'$'}c
                        voltage=${'$'}v
                        bat=${'$'}ps
                        [ -e "${'$'}ps/charge_full" ] && charge_full=${'$'}(cat "${'$'}ps/charge_full" 2>/dev/null || echo 0)
                        [ -e "${'$'}ps/charge_full_design" ] && charge_full_design=${'$'}(cat "${'$'}ps/charge_full_design" 2>/dev/null || echo 0)
                        return 0
                    fi
                fi
                return 1
            }
            for name in battery bms BAT0 BAT1 BATTERY Battery; do
                ps="/sys/class/power_supply/${'$'}name"
                if [ -d "${'$'}ps" ] && try_read "${'$'}ps"; then found=1; break; fi
            done
            if [ "${'$'}found" -eq 0 ]; then
                for ps in /sys/class/power_supply/*; do
                    [ -d "${'$'}ps" ] || continue
                    [ -e "${'$'}ps/type" ] || continue
                    t=${'$'}(cat "${'$'}ps/type" 2>/dev/null)
                    if [ "${'$'}t" = "Battery" ] && try_read "${'$'}ps"; then found=1; break; fi
                done
            fi
            if [ "${'$'}charge_full" = "0" ] || [ "${'$'}charge_full_design" = "0" ]; then
                for ps in /sys/class/power_supply/*; do
                    if [ "${'$'}charge_full" = "0" ] && [ -e "${'$'}ps/charge_full" ]; then
                        charge_full=${'$'}(cat "${'$'}ps/charge_full" 2>/dev/null || echo 0)
                    fi
                    if [ "${'$'}charge_full_design" = "0" ] && [ -e "${'$'}ps/charge_full_design" ]; then
                        charge_full_design=${'$'}(cat "${'$'}ps/charge_full_design" 2>/dev/null || echo 0)
                    fi
                done
            fi
            cycle=-1; ctype=""
            for ps in "${'$'}bat" /sys/class/power_supply/battery /sys/class/power_supply/bms; do
                [ -n "${'$'}ps" ] || continue
                [ "${'$'}cycle" = "-1" ] && [ -r "${'$'}ps/cycle_count" ] && cycle=${'$'}(cat "${'$'}ps/cycle_count" 2>/dev/null || echo -1)
                [ -z "${'$'}ctype" ] && [ -r "${'$'}ps/charge_type" ] && ctype=${'$'}(cat "${'$'}ps/charge_type" 2>/dev/null)
            done
            echo "${'$'}current ${'$'}voltage ${'$'}charge_full ${'$'}charge_full_design ${'$'}cycle"
            echo "${'$'}ctype"
        """.trimIndent()

        val result = ShellManager.exec(script)
        val lines = result.stdout.trim().lines()
        val output = lines.firstOrNull().orEmpty().trim()

        val readings = if (output.isNotBlank()) {
            val parts = output.split(" ")
            ShellBatteryReadings(
                // Signed current kept for direction; zeros dropped so fallbacks apply.
                currentRaw = parts.getOrNull(0)?.toLongOrNull()?.takeIf { it != 0L },
                voltageRaw = parts.getOrNull(1)?.toLongOrNull()?.takeIf { it != 0L },
                chargeFullRaw = parts.getOrNull(2)?.toLongOrNull()?.takeIf { it > 0L },
                chargeFullDesignRaw = parts.getOrNull(3)?.toLongOrNull()?.takeIf { it > 0L },
                cycleCount = parts.getOrNull(4)?.toIntOrNull()?.takeIf { it >= 0 } ?: -1,
                chargeType = lines.getOrNull(1)?.trim().orEmpty().take(32)
            )
        } else {
            ShellBatteryReadings()
        }

        lastShellSnapshot = readings
        shellCacheTimeMs = now
        return readings
    }

    /** Signed mA. BATTERY_PROPERTY_CURRENT_NOW is µA by spec, mA on some OEMs (e.g. Samsung). */
    private fun readCurrentFromBatteryManager(context: Context): Int {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val currentNow = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0
        if (currentNow == 0 || currentNow == Int.MIN_VALUE) return 0
        // µA devices exceed 20000 within minutes of screen-on; mA devices never reach 20 A.
        // ponytail: learned flag, so an idle µA reading <20 mA before the first large sample reads as mA.
        var isUa = signPrefs.getBoolean(KEY_BM_CURRENT_IS_UA, false)
        if (!isUa && kotlin.math.abs(currentNow) >= 20_000) {
            isUa = true
            signPrefs.edit().putBoolean(KEY_BM_CURRENT_IS_UA, true).apply()
        }
        return if (isUa) currentNow / 1000 else currentNow
    }

    private fun readCapacityFromBatteryManager(context: Context, percent: Int): Int {
        if (percent <= 0) return 0
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            ?: return 0
        val chargeCounter = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        if (chargeCounter == Int.MIN_VALUE || chargeCounter <= 0) return 0
        val remainingMah = capacityToMah(chargeCounter.toLong()).toFloat()
        return (remainingMah / percent * 100).toInt()
    }

    private data class ShellBatteryReadings(
        val currentRaw: Long? = null,
        val voltageRaw: Long? = null,
        val chargeFullRaw: Long? = null,
        val chargeFullDesignRaw: Long? = null,
        val cycleCount: Int = -1,
        val chargeType: String = ""
    )
}

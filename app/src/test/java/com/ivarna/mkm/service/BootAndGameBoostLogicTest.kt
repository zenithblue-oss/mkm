package com.ivarna.mkm.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BootAndGameBoostLogicTest {
    // ---- bootloop guard ----

    @Test
    fun safeModeTripsOnlyWhenPreviousApplyNeverStabilized() {
        assertFalse(BootSafeMode.shouldTrip(applyInProgress = false, attempts = 0))
        assertFalse(BootSafeMode.shouldTrip(applyInProgress = false, attempts = 3))
        assertTrue(BootSafeMode.shouldTrip(applyInProgress = true, attempts = BootSafeMode.nextAttempts(0)))
    }

    @Test
    fun attemptCounterIncrementsAndIsBounded() {
        assertEquals(1, BootSafeMode.nextAttempts(0))
        assertEquals(1, BootSafeMode.nextAttempts(-5))
        assertEquals(3, BootSafeMode.nextAttempts(2))
        assertEquals(99, BootSafeMode.nextAttempts(99))
    }

    // ---- foreground-game matching ----

    @Test
    fun parsesResumedActivityPackageStrictly() {
        val out = """
              topResumedActivity=ActivityRecord{a1b2c3 u0 com.miHoYo.GenshinImpact/com.miHoYo.GetMobileInfo.MainActivity t42}
              mResumedActivity: ActivityRecord{a1b2c3 u0 com.other.app/.Main t42}
        """.trimIndent()
        assertEquals("com.miHoYo.GenshinImpact", GameBoostAutoBoost.parseForegroundPackage(out))
        assertEquals(
            "com.tencent.ig",
            GameBoostAutoBoost.parseForegroundPackage("    mResumedActivity: ActivityRecord{1f u10 com.tencent.ig/.MainActivity t9}")
        )
        assertNull(GameBoostAutoBoost.parseForegroundPackage(""))
        assertNull(GameBoostAutoBoost.parseForegroundPackage("mResumedActivity: ActivityRecord{x u0 ;rm -rf/.A t1}"))
        assertNull(GameBoostAutoBoost.parseForegroundPackage("mFocusedApp=ActivityRecord{x u0 com.a.b/.A t1}"))
    }

    @Test
    fun autoBoostIsEdgeTriggeredAndOnlyRestoresItsOwnSession() {
        val games = setOf("com.game.one")
        // Entering a game while Off boosts.
        assertEquals(GameBoostAutoBoost.Action.ENABLE,
            GameBoostAutoBoost.decide("com.game.one", "com.launcher", games, boostOff = true, autoOwned = false))
        // Staying in the game after a manual disable does not re-boost.
        assertEquals(GameBoostAutoBoost.Action.NONE,
            GameBoostAutoBoost.decide("com.game.one", "com.game.one", games, boostOff = true, autoOwned = false))
        // Leaving (or screen off -> null) restores an auto-started session.
        assertEquals(GameBoostAutoBoost.Action.DISABLE,
            GameBoostAutoBoost.decide("com.launcher", "com.game.one", games, boostOff = false, autoOwned = true))
        assertEquals(GameBoostAutoBoost.Action.DISABLE,
            GameBoostAutoBoost.decide(null, "com.game.one", games, boostOff = false, autoOwned = true))
        // A manual session is never ended by the watcher.
        assertEquals(GameBoostAutoBoost.Action.NONE,
            GameBoostAutoBoost.decide("com.launcher", "com.game.one", games, boostOff = false, autoOwned = false))
    }

    // ---- thermal zone fallback ----

    @Test
    fun cpuZoneFallbackMapsTemperatures() {
        assertTrue(GameBoostThermalGuard.isCpuZoneType("cpu-1-0-usr"))
        assertTrue(GameBoostThermalGuard.isCpuZoneType("mtktscpu\n"))
        assertFalse(GameBoostThermalGuard.isCpuZoneType("battery"))
        assertEquals(45_000, GameBoostThermalGuard.normalizeMilliC(45))
        assertEquals(91_000, GameBoostThermalGuard.normalizeMilliC(91_000))
        assertNull(GameBoostThermalGuard.normalizeMilliC(-273_000))
        assertEquals(GameBoostThermalGuard.STATUS_SEVERE, GameBoostThermalGuard.statusForMilliC(95_000))
        assertEquals(0, GameBoostThermalGuard.statusForMilliC(60_000))
    }

    // ---- profile export/import ----

    @Test
    fun profileRoundTripKeepsWhitelistedEntriesAndDropsUnsafeOnes() {
        val raw: Map<String, Map<String, Any?>> = mapOf(
            BootSettingsManager.PREFS_FILE to mapOf(
                "boot_cpu_enabled" to true,
                "boot_cpu_governor_0" to "schedutil",
                "boot_cpu_max_freq_4" to "2400000",
                "boot_ram_devfreq_path" to "/sys/class/devfreq/mtk-dvfsrc-devfreq",
                "boot_storage_ufs_path" to "/sys/../data/evil",          // traversal
                "boot_gpu_governor" to "performance; reboot",             // shell injection
                "boot_apply_in_progress" to true,                        // guard state never imported
                "boot_gpu_enabled" to "true"                              // wrong type
            ),
            GameBoostAutoBoost.PREFS to mapOf(
                GameBoostAutoBoost.KEY_ENABLED to true,
                GameBoostAutoBoost.KEY_PACKAGES to setOf("com.game.one", "com.game.two")
            ),
            TuningProfile.MEMORY_BOOT_PREFS to mapOf("swappiness" to 60, "zram_algo" to "lz4"),
            "settings_prefs" to mapOf("theme" to "DARK")
        )
        val profile = TuningProfile.validate(raw)
        assertEquals(4, profile.files[BootSettingsManager.PREFS_FILE]!!.size)
        assertEquals(2, profile.files[GameBoostAutoBoost.PREFS]!!.size)
        assertEquals(2, profile.files[TuningProfile.MEMORY_BOOT_PREFS]!!.size)
        assertEquals(5, profile.rejected)
        // Re-validating exported content is lossless.
        assertEquals(profile.files, TuningProfile.validate(profile.files).files)
        assertEquals(0, TuningProfile.validate(profile.files).rejected)
    }

    @Test
    fun profileRejectsInvalidPackagesInSet() {
        val profile = TuningProfile.validate(
            mapOf(GameBoostAutoBoost.PREFS to mapOf(GameBoostAutoBoost.KEY_PACKAGES to setOf("com.ok.app", "bad pkg")))
        )
        assertTrue(profile.files.isEmpty())
        assertEquals(1, profile.rejected)
    }
}

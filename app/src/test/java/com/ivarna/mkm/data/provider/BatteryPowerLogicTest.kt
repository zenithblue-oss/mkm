package com.ivarna.mkm.data.provider

import com.ivarna.mkm.service.BatterySessionTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryPowerLogicTest {

    @Test fun capacityUnitsNormaliseToMah() {
        assertEquals(4500, BatteryProvider.capacityToMah(4_500L))          // mAh
        assertEquals(4500, BatteryProvider.capacityToMah(4_500_000L))      // µAh
        assertEquals(0, BatteryProvider.capacityToMah(0L))
    }

    @Test fun currentVoltageUnitsNormalise() {
        assertEquals(500_000L to 4_000_000L, PowerProvider.normalizeCurrentVoltage(-500_000L, 4_000_000L)) // µA, µV
        assertEquals(500_000L to 4_000_000L, PowerProvider.normalizeCurrentVoltage(500L, 4_000L))          // mA, mV
        assertEquals(2.0f, PowerProvider.computePowerWatts(500_000L, 4_000_000L), 1e-4f)
    }

    @Test fun currentDirectionUsesLearnedSign() {
        // Unknown convention: plugged state decides.
        assertEquals(300, BatteryProvider.directedCurrentMa(300, -1, 0, false, isPlugged = true))
        // Discharge sign learned as +1 and a flip was seen: raw -1 means charging, even unplugged-looking.
        assertEquals(300, BatteryProvider.directedCurrentMa(300, -1, 1, true, isPlugged = true))
        // Plugged but net discharging keeps the minus sign.
        assertEquals(-300, BatteryProvider.directedCurrentMa(300, 1, 1, true, isPlugged = true))
        // Magnitude-only device (no flip seen) keeps the charging sign correct (GH #15).
        assertEquals(300, BatteryProvider.directedCurrentMa(300, 1, 1, false, isPlugged = true))
        assertEquals(-300, BatteryProvider.directedCurrentMa(300, 1, 1, false, isPlugged = false))
    }

    @Test fun drainRateIsDropOverHours() {
        assertEquals(10f, BatterySessionTracker.ratePerHour(5, 30 * 60_000L), 1e-4f)
        assertEquals(0f, BatterySessionTracker.ratePerHour(5, 0L), 0f)
    }

    @Test fun chargeLimitHysteresis() {
        assertTrue(BatterySessionTracker.nextChargeLimitStopped(80, 80, stopped = false))
        assertFalse(BatterySessionTracker.nextChargeLimitStopped(79, 80, stopped = false))
        assertTrue(BatterySessionTracker.nextChargeLimitStopped(76, 80, stopped = true))
        assertFalse(BatterySessionTracker.nextChargeLimitStopped(75, 80, stopped = true))
    }

    @Test fun multiplierParsesCommaAndClamps() {
        assertEquals(1.5f, PowerCalibrationManager.parseMultiplier("1,5")!!, 0f)
        assertEquals(1000f, PowerCalibrationManager.parseMultiplier("5000")!!, 0f)
        assertEquals(0.1f, PowerCalibrationManager.parseMultiplier("0.001")!!, 0f)
        assertNull(PowerCalibrationManager.parseMultiplier("abc"))
        assertNull(PowerCalibrationManager.parseMultiplier("-2"))
    }

    @Test fun drainersParseCheckin() {
        val text = """
            9,0,i,uid,10100,com.a
            9,0,i,uid,10200,com.b
            9,10100,l,pwi,uid,12.5,0,0,0
            9,10200,l,pwi,uid,40.0,0,0,0
            9,10100,l,pwi,uid,2.5,0,0,0
            9,0,l,pwi,scrn,99.0,0,0,0
        """.trimIndent()
        val list = BatteryProvider.parseDrainers(text)
        assertEquals(listOf(10200, 10100), list.map { it.uid })
        assertEquals(15.0, list[1].mah, 1e-9)
        assertEquals("com.b", list[0].packageName)
    }
}

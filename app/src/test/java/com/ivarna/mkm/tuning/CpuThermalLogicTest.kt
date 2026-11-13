package com.ivarna.mkm.tuning

import com.ivarna.mkm.data.model.FrequencyCapability
import com.ivarna.mkm.data.provider.CpuProvider
import com.ivarna.mkm.data.provider.CpuUtilizationProvider
import com.ivarna.mkm.data.provider.ThermalProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CpuThermalLogicTest {
    @Test
    fun cpuZoneFilter() {
        listOf("cpu-1-0-usr", "cpuss-0", "mtktscpu", "soc_therm", "BIG", "MID", "LITTLE", "cpu_big").forEach {
            assertTrue(it, ThermalProvider.isCpuZone(it))
        }
        listOf("battery", "skin-therm", "charger", "pm8350b-bcl-lvl0", "ibat-high", "vbat", "soc", "socd", "modem-cpu", "gpu0").forEach {
            assertFalse(it, ThermalProvider.isCpuZone(it))
        }
        assertFalse(ThermalProvider.isTemperatureZone("pm7250b-ibat-lvl0"))
        assertEquals(45.5f, ThermalProvider.normalizeZoneTemp(45500f), 0.01f)
        assertEquals(45f, ThermalProvider.normalizeZoneTemp(45f), 0.01f)
    }

    @Test
    fun tripWritesOnlyCpuZonesAndKeepSpacing() {
        val out = """
            /sys/class/thermal/thermal_zone0/type:cpu-0-0
            /sys/class/thermal/thermal_zone0/trip_point_0_type:passive
            /sys/class/thermal/thermal_zone0/trip_point_0_temp:85000
            /sys/class/thermal/thermal_zone0/trip_point_1_type:passive
            /sys/class/thermal/thermal_zone0/trip_point_1_temp:95000
            /sys/class/thermal/thermal_zone0/trip_point_2_type:critical
            /sys/class/thermal/thermal_zone0/trip_point_2_temp:115000
            /sys/class/thermal/thermal_zone1/type:battery
            /sys/class/thermal/thermal_zone1/trip_point_0_type:passive
            /sys/class/thermal/thermal_zone1/trip_point_0_temp:45000
        """.trimIndent()
        val writes = ThermalProvider.planTripWrites(ThermalProvider.parseTripPoints(out), 90)
        assertEquals(
            mapOf(
                "/sys/class/thermal/thermal_zone0/trip_point_0_temp" to 90000,
                "/sys/class/thermal/thermal_zone0/trip_point_1_temp" to 100000
            ),
            writes
        )
    }

    @Test
    fun snapAndHotplugAndUsage() {
        val caps = FrequencyCapability.Discrete(listOf(1_100_000L, 1_651_200L, 1_804_800L))
        assertEquals(1_651_200L, CpuProvider.snapFrequency(1_650_000L, caps))
        assertEquals(2_000L, CpuProvider.snapFrequency(5_000L, FrequencyCapability.Range(1_000L, 2_000L)))
        assertNull(CpuProvider.snapFrequency(1L, FrequencyCapability.Unavailable("x")))

        val clusters = listOf(listOf(0, 1, 2, 3), listOf(4, 5))
        assertFalse(CpuProvider.canOffline(0, setOf(0, 1, 4, 5), clusters))
        assertTrue(CpuProvider.canOffline(4, setOf(0, 4, 5), clusters))
        assertFalse(CpuProvider.canOffline(5, setOf(0, 5), clusters))

        assertEquals(0.75f, CpuUtilizationProvider.deltaUsage(100, 50, 200, 75)!!, 0.001f)
        assertNull(CpuUtilizationProvider.deltaUsage(100, 50, 200, 40)) // idle went backwards
        assertNull(CpuUtilizationProvider.deltaUsage(100, 50, 200, 200)) // idleDelta > totalDelta
    }
}

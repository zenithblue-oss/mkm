package com.ivarna.mkm.service

import com.ivarna.mkm.data.provider.ThermalProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayTimingTest {
    @Test
    fun delayIsIntervalMinusElapsed() {
        assertEquals(1500L, OverlayService.nextDelayMs(2000L, 500L))
        assertEquals(0L, OverlayService.nextDelayMs(1000L, 1800L))
        // interval below the minimum is clamped up first
        assertEquals(400L, OverlayService.nextDelayMs(100L, 100L))
    }

    @Test
    fun netSpeedDelta() {
        assertEquals(1000L, OverlayService.netRateBps(0L, 2000L, 2000L))
        assertEquals(0L, OverlayService.netRateBps(5000L, 100L, 1000L)) // counter reset
        assertEquals(0L, OverlayService.netRateBps(100L, 200L, 0L))
        assertEquals(-1L, OverlayService.netRateBps(-1L, -1L, 1000L)) // TrafficStats.UNSUPPORTED
        assertEquals("N/A", OverlayService.formatRate(-1L))
        assertEquals("1K", OverlayService.formatRate(2047L))
        assertEquals("1.5M", OverlayService.formatRate(1536L * 1024L))
    }

    @Test
    fun statusIconMetricsParseLegacyAndMulti() {
        assertEquals(emptyList<String>(), OverlayService.parseStatusIconMetrics("off"))
        assertEquals(emptyList<String>(), OverlayService.parseStatusIconMetrics(null))
        assertEquals(listOf("power"), OverlayService.parseStatusIconMetrics("power"))
        assertEquals(listOf("power", "net_speed"), OverlayService.parseStatusIconMetrics("power,net_speed,cpu_temp"))
    }

    @Test
    fun batteryTempNormalizes() {
        assertEquals(36.5f, ThermalProvider.normalizeBatteryTemp(365f), 0.01f)
        assertEquals(36.5f, ThermalProvider.normalizeBatteryTemp(36500f), 0.01f)
        assertEquals(36.5f, ThermalProvider.normalizeBatteryTemp(36.5f), 0.01f)
    }
}

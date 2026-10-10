package com.ivarna.mkm.tuning

import com.ivarna.mkm.data.provider.GpuProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class GpuProviderUnitsTest {
    @Test fun rawFrequencyUnitsNormaliseToKhz() {
        assertEquals(800_000L, GpuProvider.rawFrequencyToKhz(800_000_000L)) // Hz
        assertEquals(800_000L, GpuProvider.rawFrequencyToKhz(800_000L))     // kHz
        assertEquals(800_000L, GpuProvider.rawFrequencyToKhz(800L))         // MHz
    }

    @Test fun loadParsesVendorFormats() {
        assertEquals(0.01f, GpuProvider.parseLoad("1"), 0.0001f)
        assertEquals(0.45f, GpuProvider.parseLoad("45%"), 0.0001f)
        assertEquals(0.29f, GpuProvider.parseLoad("29 29 0"), 0.0001f)
        assertEquals(0.35f, GpuProvider.parseLoad("35@600000000Hz"), 0.0001f)
        assertEquals(0f, GpuProvider.parseLoad(""), 0f)
    }
}

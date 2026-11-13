package com.ivarna.mkm.utils

import com.ivarna.mkm.data.model.FpsSample
import com.ivarna.mkm.data.model.FpsSource
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class FpsSessionRecorderTest {

    @Before
    fun setUp() {
        FpsSessionRecorder.clear()
    }

    @After
    fun tearDown() {
        FpsSessionRecorder.clear()
    }

    @Test
    fun testStartAndStopLifecycle() {
        assertFalse(FpsSessionRecorder.isRecording.value)
        assertNull(FpsSessionRecorder.session.value)

        FpsSessionRecorder.start("MEDIATEK")
        assertTrue(FpsSessionRecorder.isRecording.value)
        assertNotNull(FpsSessionRecorder.session.value)
        assertEquals("MEDIATEK", FpsSessionRecorder.session.value?.platform)
        assertEquals(0, FpsSessionRecorder.session.value?.samples?.size)
        assertNull(FpsSessionRecorder.session.value?.endedAtMs)

        val sample = FpsSample(
            tMs = 1000L,
            fps = 60.0f,
            frameMs = 16.6f,
            pkg = "com.test.app",
            pid = 1234,
            source = FpsSource.MALI_DMA_FENCE,
            events = 120,
            idle = false
        )
        FpsSessionRecorder.add(sample)

        assertEquals(1, FpsSessionRecorder.session.value?.samples?.size)
        assertEquals(60.0f, FpsSessionRecorder.session.value?.samples?.first()?.fps)

        val stoppedSession = FpsSessionRecorder.stop()
        assertFalse(FpsSessionRecorder.isRecording.value)
        assertNotNull(stoppedSession)
        assertEquals(1, stoppedSession?.samples?.size)
        assertNotNull(stoppedSession?.endedAtMs)
    }

    @Test
    fun longSessionDownsamplesInsteadOfDroppingAndKeepsExactStats() {
        FpsSessionRecorder.start("SNAPDRAGON")
        val n = FpsSessionRecorder.MAX_SERIES * 2 + 10

        for (i in 1..n) {
            val sample = FpsSample(
                tMs = i * 1000L,
                fps = (i % 2 + 1) * 30f, // alternating 60 / 30
                frameMs = 16.6f,
                pkg = "com.test.app",
                pid = 100,
                source = FpsSource.ADRENO_INFLIGHT,
                events = i,
                idle = false
            )
            FpsSessionRecorder.add(sample)
        }

        val session = FpsSessionRecorder.session.value!!
        assertTrue(session.samples.size < FpsSessionRecorder.MAX_SERIES)
        // Oldest sample is still present: nothing dropped from the start
        assertEquals(1000L, session.samples.first().tMs)
        assertEquals(n, session.stats.sampleCount)
        assertEquals(n, session.stats.activeCount)
        assertEquals(30f, session.stats.minFps)
        assertEquals(60f, session.stats.maxFps)
        assertEquals(45f, session.stats.avgFps, 0.1f)
    }

    @Test
    fun recordsOnlyTheTargetPackageAndIgnoresOwnApp() {
        FpsSessionRecorder.start()
        fun s(pkg: String, fps: Float) = FpsSample(0L, fps, 16f, pkg, 1, FpsSource.SURFACEFLINGER)
        FpsSessionRecorder.add(s("com.ivarna.mkm", 60f), ignorePkg = "com.ivarna.mkm")
        FpsSessionRecorder.add(s("com.game", 50f), ignorePkg = "com.ivarna.mkm")
        FpsSessionRecorder.add(s("com.launcher", 120f), ignorePkg = "com.ivarna.mkm")
        FpsSessionRecorder.add(s("com.game", 40f), ignorePkg = "com.ivarna.mkm")

        val session = FpsSessionRecorder.session.value!!
        assertEquals("com.game", session.targetPkg)
        assertEquals(listOf(50f, 40f), session.samples.map { it.fps })
        assertEquals(45f, session.stats.avgFps, 0.01f)
    }

    @Test
    fun histogramLowsAndFrameTimesAreAggregated() {
        FpsSessionRecorder.start()
        // 99 frames of 10ms and one of 100ms
        val times = FloatArray(100) { if (it == 0) 100f else 10f }
        FpsSessionRecorder.add(
            FpsSample(0L, 90f, 10.9f, "com.game", 1, FpsSource.SURFACEFLINGER, jankCount = 1, frameTimesMs = times)
        )
        val st = FpsSessionRecorder.session.value!!.stats
        assertEquals(100L, st.frameCount)
        assertEquals(1, st.jankCount)
        assertEquals(10f, st.low1Fps, 0.1f) // slowest 1% = the 100ms frame
        // frameTimesMs is never kept in the stored series
        assertNull(FpsSessionRecorder.session.value!!.samples.first().frameTimesMs)
    }

    @Test
    fun csvExportHasHeaderAndOneRowPerSample() {
        FpsSessionRecorder.start()
        FpsSessionRecorder.add(FpsSample(1000L, 60f, 16.67f, "com.game", 1, FpsSource.SURFACEFLINGER, gpuFreqMhz = 850, powerW = -3.5f))
        val session = FpsSessionRecorder.stop()!!
        val lines = FpsSessionExport.toCsv(session).trim().lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("time_ms,elapsed_s,pkg,source,fps"))
        val row = lines[1].split(",")
        assertEquals(lines[0].split(",").size, row.size)
        assertEquals("60.00", row[4])
        assertEquals("850", row[11])
        assertEquals("-3.50", row.last())
        val json = FpsSessionExport.toJson(session)
        assertTrue(json.startsWith("{") && json.endsWith("}"))
        assertTrue(json.contains("\"target_pkg\":\"com.game\""))
    }

    @Test
    fun testSetPlatformUpdatesSession() {
        FpsSessionRecorder.start("UNKNOWN")
        assertEquals("UNKNOWN", FpsSessionRecorder.session.value?.platform)

        FpsSessionRecorder.setPlatform("MEDIATEK")
        assertEquals("MEDIATEK", FpsSessionRecorder.session.value?.platform)

        val sample = FpsSample(
            tMs = 2000L,
            fps = 120.0f,
            frameMs = 8.3f,
            pkg = "com.game",
            pid = 999,
            source = FpsSource.MALI_DMA_FENCE,
            events = 240,
            idle = false
        )
        FpsSessionRecorder.add(sample)
        assertEquals("MEDIATEK", FpsSessionRecorder.session.value?.platform)
    }

    @Test
    fun testClearResetsAllState() {
        FpsSessionRecorder.start("MEDIATEK")
        FpsSessionRecorder.add(
            FpsSample(
                tMs = 1000L,
                fps = 60f,
                frameMs = 16.6f,
                pkg = "com.test",
                pid = 1,
                source = FpsSource.FPS_MONITOR
            )
        )
        assertNotNull(FpsSessionRecorder.session.value)
        assertTrue(FpsSessionRecorder.isRecording.value)

        FpsSessionRecorder.clear()
        assertNull(FpsSessionRecorder.session.value)
        assertFalse(FpsSessionRecorder.isRecording.value)
    }
}

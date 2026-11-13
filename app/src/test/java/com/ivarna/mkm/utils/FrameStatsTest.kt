package com.ivarna.mkm.utils

import com.ivarna.mkm.data.model.FrameStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameStatsTest {
    private val ms = 1_000_000L

    private fun timeline(vararg frameMs: Long): LongArray {
        val out = LongArray(frameMs.size + 1)
        for (i in frameMs.indices) out[i + 1] = out[i] + frameMs[i] * ms
        return out
    }

    @Test fun steadyFramesHaveNoJankAndLowsEqualAverage() {
        val st = FrameStats.compute(timeline(*LongArray(120) { 10L }), refreshNs = 10 * ms)!!
        assertEquals(100f, st.avgFps, 0.01f)
        assertEquals(100f, st.low1Fps, 0.01f)
        assertEquals(100f, st.low01Fps, 0.01f)
        assertEquals(0, st.jankCount)
        assertEquals(0f, st.stutterPct, 0.001f)
    }

    @Test fun oneLongFrameIsJankAndDrivesLows() {
        // 99 x 10ms + 1 x 50ms = 1040ms for 100 frames
        val st = FrameStats.compute(timeline(*LongArray(99) { 10L }, 50L), refreshNs = 10 * ms)!!
        assertEquals(1, st.jankCount)
        assertEquals(20f, st.low1Fps, 0.01f) // slowest 1% = the 50ms frame
        assertEquals(50f, st.maxFrameMs, 0.01f)
        assertEquals(100f * 50f / 1040f, st.stutterPct, 0.01f)
        assertEquals(100 * 1000f / 1040f, st.avgFps, 0.01f)
    }

    @Test fun steadyHalfRateIsNotJank() {
        // 60 fps game on a 120 Hz panel: every frame spans 2 vsyncs
        val st = FrameStats.compute(timeline(*LongArray(60) { 16L }), refreshNs = 8 * ms)!!
        assertEquals(0, st.jankCount)
    }

    @Test fun framesBeforeCursorAreSkipped() {
        val t = timeline(10, 10, 10, 30)
        val st = FrameStats.compute(t, sinceNs = t[3])!!
        assertEquals(1, st.frameTimesMs.size)
        assertEquals(30f, st.frameTimesMs[0], 0.01f)
        assertNull(FrameStats.compute(t, sinceNs = t.last()))
        assertNull(FrameStats.compute(LongArray(1)))
    }
}

package com.ivarna.mkm.shell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SysfsPathSafetyTest {
    @Test
    fun rejectsTraversalAndEmptySegments() {
        assertTrue(SysfsTuningExecutor.isSafeSysfsPath("/sys/class/devfreq/mtk-dvfsrc-devfreq/governor"))
        assertFalse(SysfsTuningExecutor.isSafeSysfsPath("/sys/../data/local/tmp/x"))
        assertFalse(SysfsTuningExecutor.isSafeSysfsPath("/sys/devices/./x"))
        assertFalse(SysfsTuningExecutor.isSafeSysfsPath("/sys//devices/x"))
        assertFalse(SysfsTuningExecutor.isSafeSysfsPath("/sys/devices/x'y"))
    }

    @Test
    fun writeScriptOnlyChmodsAfterPermissionDeniedAndRestoresMode() {
        val script = SysfsTuningExecutor.writeScript("/sys/devices/x/max_freq", "1000")
        val firstWrite = script.indexOf("printf '%s' '1000'")
        assertTrue(firstWrite in 0 until script.indexOf("chmod u+w"))
        assertTrue(script.contains("stat -c %a"))
        assertTrue(script.contains("chmod \"\$m\""))
        assertFalse(script.contains("chmod 644"))
    }
}

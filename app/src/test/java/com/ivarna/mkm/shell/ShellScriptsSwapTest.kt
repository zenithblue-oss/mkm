package com.ivarna.mkm.shell

import com.ivarna.mkm.data.provider.MemoryProvider
import com.ivarna.mkm.data.provider.UfsProvider
import com.ivarna.mkm.utils.FormatUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellScriptsSwapTest {
    @Test
    fun createSwapTriesFallocateBeforeDdAndKeepsLoopFallback() {
        val script = ShellScripts.createSwap("/data/local/tmp/swapfile", 512)
        val fallocate = script.indexOf("fallocate -l")
        val dd = script.indexOf("dd if=/dev/zero")
        assertTrue(fallocate in 0 until dd)
        assertTrue(script.contains("bs=1M count=512"))
        assertTrue(script.contains("chmod 600"))
        assertTrue(script.contains("losetup"))
        assertTrue(script.contains("df -k /data"))
        assertTrue(script.contains("trap "))
        assertFalse(script.contains("swappiness"))
    }

    @Test
    fun scriptsRunInSubshellWithoutSetE() {
        listOf(
            ShellScripts.createSwap("/data/local/tmp/swapfile", 512),
            ShellScripts.removeSwap("/data/local/tmp/swapfile"),
            ShellScripts.disableSwap("/data/local/tmp/swapfile"),
            ShellScripts.enableSwap("/data/local/tmp/swapfile"),
            ShellScripts.zramReconfigure("lz4", 1L shl 30),
            DevfreqScripts.findDevfreqDir(),
            DevfreqScripts.setFreq("/sys/class/devfreq/x", "1000"),
            UfsScripts.findUfsDir(),
            UfsScripts.getHealthInfo()
        ).forEach { s ->
            assertTrue(s, s.trim().startsWith("(") && s.trim().endsWith(")"))
            assertFalse(s, s.contains("set -e"))
        }
    }

    @Test
    fun swapPathValidation() {
        assertTrue(ShellScripts.isValidSwapPath("/data/local/tmp/swapfile"))
        assertTrue(ShellScripts.isValidSwapPath("/data/swap_1.img"))
        listOf(
            "/dev/block/zram0", "/data/../system/x", "/data/a b", "/data/x;rm -rf /", "/data/\$(id)",
            "/data/x'y", "/data/", "/sdcard/swap", "data/swap", "/data/x\nrm"
        ).forEach { assertFalse(it, ShellScripts.isValidSwapPath(it)) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun removeSwapRefusesDev() {
        ShellScripts.removeSwap("/dev/block/zram0")
    }

    @Test
    fun swapSizeClamp() {
        assertNull(ShellScripts.clampSwapSizeMb(null, 10_000))
        assertNull(ShellScripts.clampSwapSizeMb(0, 10_000))
        assertNull(ShellScripts.clampSwapSizeMb(-5, 10_000))
        assertNull(ShellScripts.clampSwapSizeMb(512, 100)) // 50% of free < 64MB
        assertEquals(64, ShellScripts.clampSwapSizeMb(10, 10_000))
        assertEquals(5_000, ShellScripts.clampSwapSizeMb(8_000, 10_000))
        assertEquals(2048, ShellScripts.clampSwapSizeMb(2048, 10_000))
    }

    @Test
    fun vmValidation() {
        assertTrue(ShellScripts.isValidVmValue("swappiness", 100))
        assertFalse(ShellScripts.isValidVmValue("swappiness", 201))
        assertFalse(ShellScripts.isValidVmValue("vfs_cache_pressure", 0))
        assertFalse(ShellScripts.isValidVmValue("dirty_ratio", 10))
        assertFalse(ShellScripts.isValidVmValue("swappiness", null))
    }

    @Test
    fun zramParsing() {
        assertEquals(listOf(4096L, 1024L, 2048L), MemoryProvider.parseMmStat("4096 1024 2048 0 3000 10 0 0 0"))
        assertNull(MemoryProvider.parseMmStat("garbage"))
        val (list, cur) = MemoryProvider.parseBracketList("lzo lzo-rle [lz4] zstd")
        assertEquals(listOf("lzo", "lzo-rle", "lz4", "zstd"), list)
        assertEquals("lz4", cur)
    }

    @Test
    fun procParsing() {
        assertEquals("/data/my swap", MemoryProvider.decodeProcPath("/data/my\\040swap"))
        val noAvail = "MemTotal: 1000 kB\nMemFree: 100 kB\nBuffers: 10 kB\nCached: 200 kB\nSwapCached: 5 kB\n"
        assertEquals(310L, MemoryProvider.availableKb(noAvail))
        assertEquals(200L, MemoryProvider.parseValue(noAvail, "Cached")) // not SwapCached
        assertEquals(500L, MemoryProvider.availableKb("MemAvailable: 500 kB\nMemFree: 1 kB"))
    }

    @Test
    fun ufsLifeTimeDecode() {
        assertEquals(1, UfsProvider.parseHexLevel("0x01"))
        assertEquals(11, UfsProvider.parseHexLevel("0x0B"))
        assertNull(UfsProvider.parseHexLevel(""))
        assertEquals("0-10%", UfsProvider.lifeTimeLabel(1))
        assertEquals("90-100%", UfsProvider.lifeTimeLabel(10))
        assertEquals(">100%", UfsProvider.lifeTimeLabel(11))
        assertNull(UfsProvider.lifeTimeLabel(0))
    }

    @Test
    fun siSizes() {
        assertEquals("999 B", FormatUtils.formatBytes(999))
        assertEquals("1.5 GB", FormatUtils.formatBytes(1_500_000_000))
    }

    @Test
    fun boothelpersRejectUnsafe() {
        assertTrue(UfsScripts.setGovernor("/sys/x", "a;b").contains("exit 1"))
        assertTrue(DevfreqScripts.setFreq("/sys/x", "1;reboot").contains("exit 1"))
    }
}

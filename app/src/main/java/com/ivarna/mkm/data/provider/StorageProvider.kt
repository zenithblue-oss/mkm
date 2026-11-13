package com.ivarna.mkm.data.provider

import android.os.Environment
import android.os.StatFs
import com.ivarna.mkm.data.model.StoragePartition
import com.ivarna.mkm.data.model.StorageStatus
import com.ivarna.mkm.shell.ShellManager
import com.ivarna.mkm.shell.SysfsTuningExecutor
import com.ivarna.mkm.utils.FormatUtils
import java.io.File

/** Per block device I/O tuning. */
data class BlockDeviceTuning(
    val name: String,
    val schedulers: List<String>,
    val scheduler: String,
    val readAheadKb: Int?
)

object StorageProvider {

    fun getStorageStatus(): StorageStatus {
        val partitions = mutableListOf<StoragePartition>()
        partitions.add(getPartitionInfo("/data", Environment.getDataDirectory().absolutePath))
        partitions.add(getPartitionInfo("/system", Environment.getRootDirectory().absolutePath))

        val ufsStatus = UfsProvider.getUfsStatus()
        // sdX = SCSI/UFS, mmcblkN = eMMC; /sys/class/ufs* covers kernels exposing the UFS class
        val type = when {
            ufsStatus.isSupported || File("/sys/block/sda").exists() ||
                File("/sys/class").list()?.any { it.startsWith("ufs") } == true -> "UFS"
            File("/sys/block/mmcblk0").exists() -> "eMMC"
            else -> "Unknown"
        }

        return StorageStatus(partitions = partitions, type = type, ufsStatus = ufsStatus)
    }

    /** Free bytes on /data, 0 if unknown. */
    fun dataFreeBytes(): Long = runCatching {
        StatFs(Environment.getDataDirectory().absolutePath).availableBytes
    }.getOrDefault(0L)

    private val BLOCK_DEV = Regex("^(sd[a-z]+|mmcblk[0-9]+)$")
    val READ_AHEAD_RANGE = 0..16384

    fun isValidBlockDevice(name: String): Boolean = BLOCK_DEV.matches(name)

    fun getBlockDevices(): List<BlockDeviceTuning> {
        val names = File("/sys/block").list()?.toList()?.takeIf { l -> l.any(::isValidBlockDevice) }
            ?:ShellManager.exec("ls /sys/block", ShellManager.PrivilegeRequirement.ELEVATED_SYSFS).stdout.split(Regex("\\s+"))
        return names.filter(::isValidBlockDevice).sorted().map { name ->
            val (list, current) = MemoryProvider.parseBracketList(SysfsTuningExecutor.read("/sys/block/$name/queue/scheduler"))
            BlockDeviceTuning(
                name = name,
                schedulers = list,
                scheduler = current,
                readAheadKb = SysfsTuningExecutor.read("/sys/block/$name/queue/read_ahead_kb").toIntOrNull()
            )
        }
    }

    private fun fail(msg: String) = ShellManager.CommandResult(-1, "", msg)

    fun setScheduler(device: String, scheduler: String): ShellManager.CommandResult {
        if (!isValidBlockDevice(device)) return fail("Invalid block device")
        val path = "/sys/block/$device/queue/scheduler"
        val (available, _) = MemoryProvider.parseBracketList(SysfsTuningExecutor.read(path))
        if (scheduler !in available) return fail("Scheduler not available: $scheduler")
        return SysfsTuningExecutor.write(path, scheduler)
    }

    fun setReadAhead(device: String, kb: Int): ShellManager.CommandResult {
        if (!isValidBlockDevice(device) || kb !in READ_AHEAD_RANGE) return fail("Invalid read_ahead_kb")
        return SysfsTuningExecutor.write("/sys/block/$device/queue/read_ahead_kb", kb.toString())
    }

    private fun getPartitionInfo(label: String, path: String): StoragePartition {
        try {
            val stats = StatFs(path)
            val blockSize = stats.blockSizeLong
            val totalBlocks = stats.blockCountLong
            val availableBlocks = stats.availableBlocksLong
            val usedBlocks = totalBlocks - availableBlocks

            val total = totalBlocks * blockSize
            val used = usedBlocks * blockSize
            val free = availableBlocks * blockSize

            val percent = if (total > 0) (used.toFloat() / total.toFloat()) * 100f else 0f

            return StoragePartition(
                mountPoint = label,
                total = FormatUtils.formatBytes(total),
                used = FormatUtils.formatBytes(used),
                free = FormatUtils.formatBytes(free),
                usagePercent = percent,
                rawTotal = total,
                rawUsed = used,
                blockSize = blockSize
            )
        } catch (e: Exception) {
            return StoragePartition(
                mountPoint = label,
                total = "0 B",
                used = "0 B",
                free = "0 B",
                usagePercent = 0f,
                rawTotal = 0,
                rawUsed = 0,
                blockSize = 0
            )
        }
    }
}

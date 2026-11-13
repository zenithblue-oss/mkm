package com.ivarna.mkm.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ivarna.mkm.R
import com.ivarna.mkm.data.RamData
import com.ivarna.mkm.data.SystemRepository
import com.ivarna.mkm.data.provider.DevfreqProvider
import com.ivarna.mkm.data.provider.ManagedSwapFile
import com.ivarna.mkm.data.provider.MemoryProvider
import com.ivarna.mkm.data.provider.StorageProvider
import com.ivarna.mkm.data.provider.ZramStatus
import com.ivarna.mkm.service.BootSettingsManager
import com.ivarna.mkm.service.GameBoostRegistry
import com.ivarna.mkm.service.KernelTuningCoordinator
import com.ivarna.mkm.service.MemoryBootTuning
import com.ivarna.mkm.shell.ShellManager
import com.ivarna.mkm.shell.ShellScripts
import com.ivarna.mkm.util.AppVisibilityMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class RamViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SystemRepository()
    private val _uiState = MutableStateFlow<RamData?>(null)
    val uiState: StateFlow<RamData?> = _uiState.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _bootEnabled = MutableStateFlow(BootSettingsManager.isRamEnabled(application))
    val bootEnabled: StateFlow<Boolean> = _bootEnabled.asStateFlow()

    private val _zram = MutableStateFlow<ZramStatus?>(null)
    val zram: StateFlow<ZramStatus?> = _zram.asStateFlow()

    private val _swapFiles = MutableStateFlow<List<ManagedSwapFile>>(emptyList())
    val swapFiles: StateFlow<List<ManagedSwapFile>> = _swapFiles.asStateFlow()

    private fun str(id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)

    init {
        startMonitoring()
    }

    private fun startMonitoring() {
        viewModelScope.launch {
            try {
                val hasElevatedAccess = withContext(Dispatchers.IO) { ShellManager.hasElevatedAccess() }
                if (!hasElevatedAccess) {
                    _errorMessage.value = "No elevated access. Please enable Shizuku or grant root access."
                }
            } catch (e: Exception) {
                Log.e("RamViewModel", "Failed to check access", e)
                _errorMessage.value = "Access check failed: ${e.message}"
            }
            reload(withSwapFiles = true)
            while (true) {
                delay(2000)
                if (AppVisibilityMonitor.isForeground.value) reload(withSwapFiles = false)
            }
        }
    }

    private suspend fun reload(withSwapFiles: Boolean) {
        val data = repository.getRamData()
        _uiState.value = data
        withContext(Dispatchers.IO) {
            _zram.value = MemoryProvider.getZramStatus()
            if (withSwapFiles) {
                _swapFiles.value = MemoryProvider.getManagedSwapFiles(MemoryBootTuning.managedSwaps(getApplication()), data.swap)
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            DevfreqProvider.clearCache()
            reload(withSwapFiles = true)
            delay(500)
            _isRefreshing.value = false
        }
    }

    /**
     * Runs one shell mutation with a double-tap guard and a hard timeout.
     * [tuning] ops are refused while Game Boost owns tuning and run under KernelTuningCoordinator.
     */
    private fun runOp(
        tuning: Boolean = false,
        timeoutMs: Long = OP_TIMEOUT_MS,
        op: () -> ShellManager.CommandResult,
        onSuccess: () -> Unit = {}
    ) {
        if (_isProcessing.value) return
        if (tuning && GameBoostRegistry.ownsTuning()) {
            _errorMessage.value = str(R.string.game_boost_managed_reason)
            return
        }
        _isProcessing.value = true
        _errorMessage.value = null
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val call: suspend () -> ShellManager.CommandResult? = {
                        withTimeoutOrNull(timeoutMs) { runInterruptible { op() } }
                    }
                    if (tuning) KernelTuningCoordinator.withMutation { call() } else call()
                }
                when {
                    result == null -> _errorMessage.value = str(R.string.mem_op_timed_out)
                    result.isSuccess -> onSuccess()
                    else -> _errorMessage.value = str(
                        R.string.mem_op_failed_format,
                        result.stderr.ifEmpty { result.stdout.ifEmpty { "exit ${result.exitCode}" } }
                    )
                }
                reload(withSwapFiles = true)
            } catch (e: Exception) {
                Log.e("RamViewModel", "Operation failed", e)
                _errorMessage.value = str(R.string.mem_op_failed_format, e.message.orEmpty())
            } finally {
                _isProcessing.value = false
            }
        }
    }

    private fun rootRequired() = ShellManager.CommandResult(-1, "", str(R.string.swap_requires_root))

    private val _showSwapDialog = MutableStateFlow(false)
    val showSwapDialog: StateFlow<Boolean> = _showSwapDialog.asStateFlow()

    fun dismissSwapDialog() {
        _showSwapDialog.value = false
    }

    /** Swap creation (mkswap/swapon) needs real root; Shizuku (adb shell) cannot do it. Checked off the main thread. */
    fun openSwapDialog() {
        if (_isProcessing.value) return
        viewModelScope.launch {
            _isProcessing.value = true
            try {
                if (withContext(Dispatchers.IO) { ShellManager.hasRootCapability() }) _showSwapDialog.value = true
                else _errorMessage.value = str(R.string.swap_requires_root)
            } finally {
                _isProcessing.value = false
            }
        }
    }

    fun applySwap(path: String, sizeMb: Int?) {
        if (!ShellScripts.isValidSwapPath(path)) {
            _errorMessage.value = str(R.string.mem_swap_invalid_path)
            return
        }
        runOp(timeoutMs = SWAP_TIMEOUT_MS, op = {
            if (!ShellManager.hasRootCapability()) return@runOp rootRequired()
            val size = ShellScripts.clampSwapSizeMb(sizeMb, StorageProvider.dataFreeBytes() / (1024 * 1024))
                ?: return@runOp ShellManager.CommandResult(-1, "", str(R.string.mem_swap_invalid_size))
            ShellManager.exec(ShellScripts.createSwap(path, size), ShellManager.PrivilegeRequirement.ELEVATED_SYSFS, SWAP_TIMEOUT_MS)
        }, onSuccess = { saveSwap(path) })
    }

    fun enableSwap(path: String) {
        if (!ShellScripts.isValidSwapPath(path)) return
        runOp(timeoutMs = SWAP_TIMEOUT_MS, op = {
            if (!ShellManager.hasRootCapability()) rootRequired()
            else ShellManager.exec(ShellScripts.enableSwap(path), ShellManager.PrivilegeRequirement.ELEVATED_SYSFS, SWAP_TIMEOUT_MS)
        }, onSuccess = { saveSwap(path) })
    }

    private fun saveSwap(path: String) {
        viewModelScope.launch {
            val prio = withContext(Dispatchers.IO) {
                MemoryProvider.getSwapStatus().devices.firstOrNull { it.path == path }?.priority ?: -1
            }
            // Kernel-assigned priorities are negative and cannot be passed to swapon -p
            MemoryBootTuning.saveSwap(getApplication(), path, prio.takeIf { it >= 0 } ?: -1)
        }
    }

    fun disableSwap(path: String) {
        if (!ShellScripts.isValidSwapPath(path)) return
        runOp(timeoutMs = SWAP_TIMEOUT_MS, op = {
            ShellManager.exec(ShellScripts.disableSwap(path), ShellManager.PrivilegeRequirement.ELEVATED_SYSFS, SWAP_TIMEOUT_MS)
        }, onSuccess = { MemoryBootTuning.clearSwap(getApplication(), path, deleted = false) })
    }

    /** Only regular swap files under /data; /dev/ * (zram, block devices) is refused. */
    fun removeSwap(path: String) {
        if (!ShellScripts.isValidSwapPath(path)) {
            _errorMessage.value = str(R.string.mem_swap_invalid_path)
            return
        }
        runOp(timeoutMs = SWAP_TIMEOUT_MS, op = {
            ShellManager.exec(ShellScripts.removeSwap(path), ShellManager.PrivilegeRequirement.ELEVATED_SYSFS, SWAP_TIMEOUT_MS)
        }, onSuccess = { MemoryBootTuning.clearSwap(getApplication(), path, deleted = true) })
    }

    // ---- zram / vm -----------------------------------------------------------------

    fun setVmTunable(name: String, valueText: String) {
        val value = valueText.trim().toIntOrNull()
        val range = ShellScripts.VM_TUNABLES[name] ?: return
        if (!ShellScripts.isValidVmValue(name, value)) {
            _errorMessage.value = str(R.string.mem_vm_invalid_format, name, range.first, range.last)
            return
        }
        runOp(tuning = true, op = {
            ShellManager.exec(ShellScripts.setVmTunable(name, value!!), ShellManager.PrivilegeRequirement.ELEVATED_SYSFS)
        }, onSuccess = { MemoryBootTuning.saveVm(getApplication(), name, value!!) })
    }

    fun reconfigureZram(algorithm: String, sizeMb: Int?) {
        val z = _zram.value ?: return
        val totalKb = _uiState.value?.memory?.rawTotal ?: 0L
        val maxMb = (totalKb * 2 / 1024).toInt()
        if (algorithm !in z.algorithms || sizeMb == null || sizeMb < ShellScripts.MIN_SWAP_MB || sizeMb > maxMb) {
            _errorMessage.value = str(R.string.mem_zram_invalid_format, ShellScripts.MIN_SWAP_MB, maxMb)
            return
        }
        val bytes = sizeMb.toLong() * 1024 * 1024
        runOp(timeoutMs = SWAP_TIMEOUT_MS, op = {
            if (!ShellManager.hasRootCapability()) rootRequired()
            else ShellManager.exec(ShellScripts.zramReconfigure(algorithm, bytes), ShellManager.PrivilegeRequirement.ELEVATED_SYSFS, SWAP_TIMEOUT_MS)
        }, onSuccess = { MemoryBootTuning.saveZram(getApplication(), algorithm, bytes) })
    }

    // ---- DDR devfreq ---------------------------------------------------------------

    fun setDevfreqGovernor(path: String, governor: String) {
        runOp(tuning = true, op = { DevfreqProvider.setGovernor(path, governor) }, onSuccess = {
            if (_bootEnabled.value) saveRamSettings(path, governor, savedFreq(path))
        })
    }

    fun setDevfreqFreq(path: String, freq: String) {
        runOp(tuning = true, op = { DevfreqProvider.setFreq(path, freq) }, onSuccess = {
            // Only an explicitly picked frequency is persisted for boot
            if (_bootEnabled.value) saveRamSettings(path, _uiState.value?.devfreq?.currentGovernor.orEmpty(), freq)
        })
    }

    fun resetDevfreq(path: String) {
        runOp(tuning = true, op = { DevfreqProvider.reset(path) }, onSuccess = {
            if (_bootEnabled.value) saveRamSettings(path, _uiState.value?.devfreq?.currentGovernor.orEmpty(), "")
        })
    }

    fun toggleBootEnabled(enabled: Boolean) {
        BootSettingsManager.setRamEnabled(getApplication(), enabled)
        _bootEnabled.value = enabled
        val devfreq = _uiState.value?.devfreq ?: return
        // Never snapshot the live cur_freq: it would lock DDR low after reboot
        if (enabled && devfreq.isSupported) saveRamSettings(devfreq.controllerPath, devfreq.currentGovernor, savedFreq(devfreq.controllerPath))
    }

    private fun savedFreq(path: String): String =
        BootSettingsManager.loadRamSettings(getApplication())?.takeIf { it.controllerPath == path }?.freq.orEmpty()

    private fun saveRamSettings(path: String, governor: String, freq: String) {
        if (path.isBlank() || governor.isBlank() || governor == "unknown") return
        BootSettingsManager.saveRamSettings(getApplication(), path, governor, freq)
    }

    fun clearError() {
        _errorMessage.value = null
    }

    private companion object {
        const val SWAP_TIMEOUT_MS = 180_000L
        const val OP_TIMEOUT_MS = 30_000L
    }
}

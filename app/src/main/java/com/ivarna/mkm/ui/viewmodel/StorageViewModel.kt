package com.ivarna.mkm.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ivarna.mkm.R
import com.ivarna.mkm.data.SystemRepository
import com.ivarna.mkm.data.model.StorageStatus
import com.ivarna.mkm.data.provider.BlockDeviceTuning
import com.ivarna.mkm.data.provider.StorageProvider
import com.ivarna.mkm.data.provider.UfsHealth
import com.ivarna.mkm.data.provider.UfsProvider
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

class StorageViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SystemRepository()
    private val _uiState = MutableStateFlow<StorageStatus?>(null)
    val uiState: StateFlow<StorageStatus?> = _uiState.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _infoMessage = MutableStateFlow<String?>(null)
    val infoMessage: StateFlow<String?> = _infoMessage.asStateFlow()

    private val _bootEnabled = MutableStateFlow(BootSettingsManager.isStorageEnabled(application))
    val bootEnabled: StateFlow<Boolean> = _bootEnabled.asStateFlow()

    private val _health = MutableStateFlow<UfsHealth?>(null)
    val health: StateFlow<UfsHealth?> = _health.asStateFlow()

    private val _blockDevices = MutableStateFlow<List<BlockDeviceTuning>>(emptyList())
    val blockDevices: StateFlow<List<BlockDeviceTuning>> = _blockDevices.asStateFlow()

    private fun str(id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)

    init {
        startMonitoring()
    }

    private fun startMonitoring() {
        viewModelScope.launch {
            loadDetails()
            while (true) {
                if (AppVisibilityMonitor.isForeground.value) {
                    _uiState.value = repository.getStorageStatus()
                }
                delay(5000)
            }
        }
    }

    /** Health and block queues change rarely: loaded on open, refresh and after edits only. */
    private suspend fun loadDetails() = withContext(Dispatchers.IO) {
        _health.value = UfsProvider.getHealth()
        _blockDevices.value = StorageProvider.getBlockDevices()
    }

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            UfsProvider.clearCache()
            _uiState.value = repository.getStorageStatus()
            loadDetails()
            delay(500)
            _isRefreshing.value = false
        }
    }

    fun toggleBootEnabled(enabled: Boolean) {
        BootSettingsManager.setStorageEnabled(getApplication(), enabled)
        _bootEnabled.value = enabled
        if (enabled) saveCurrentStorageSettings()
    }

    private fun saveCurrentStorageSettings() {
        val ufs = _uiState.value?.ufsStatus ?: return
        if (!ufs.isSupported) return
        BootSettingsManager.saveStorageSettings(getApplication(), ufs.controllerPath, ufs.currentGovernor, ufs.minFreq, ufs.maxFreq)
    }

    /** Double-tap guard, Game Boost check and global kernel-tuning lock for every write. */
    private fun runOp(
        tuning: Boolean = true,
        timeoutMs: Long = 30_000L,
        op: () -> ShellManager.CommandResult,
        onSuccess: (ShellManager.CommandResult) -> Unit = {}
    ) {
        if (_isProcessing.value) return
        if (tuning && GameBoostRegistry.ownsTuning()) {
            _errorMessage.value = str(R.string.game_boost_managed_reason)
            return
        }
        _isProcessing.value = true
        _errorMessage.value = null
        _infoMessage.value = null
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
                    result.isSuccess -> onSuccess(result)
                    else -> _errorMessage.value = str(
                        R.string.mem_op_failed_format,
                        result.stderr.ifEmpty { result.stdout.ifEmpty { "exit ${result.exitCode}" } }
                    )
                }
                _uiState.value = repository.getStorageStatus()
                if (tuning) _blockDevices.value = withContext(Dispatchers.IO) { StorageProvider.getBlockDevices() }
            } catch (e: Exception) {
                _errorMessage.value = str(R.string.mem_op_failed_format, e.message.orEmpty())
            } finally {
                _isProcessing.value = false
            }
        }
    }

    private fun afterUfsChange() {
        viewModelScope.launch {
            _uiState.value = repository.getStorageStatus()
            if (_bootEnabled.value) saveCurrentStorageSettings()
        }
    }

    fun setUfsGovernor(path: String, governor: String) =
        runOp(op = { UfsProvider.setGovernor(path, governor) }, onSuccess = { afterUfsChange() })

    fun setUfsMinFreq(path: String, freq: String) =
        runOp(op = { UfsProvider.setFreq(path, freq, isMax = false) }, onSuccess = { afterUfsChange() })

    fun setUfsMaxFreq(path: String, freq: String) =
        runOp(op = { UfsProvider.setFreq(path, freq, isMax = true) }, onSuccess = { afterUfsChange() })

    fun setScheduler(device: String, scheduler: String) =
        runOp(op = { StorageProvider.setScheduler(device, scheduler) }, onSuccess = {
            MemoryBootTuning.saveScheduler(getApplication(), device, scheduler)
        })

    fun setReadAhead(device: String, kb: Int) =
        runOp(op = { StorageProvider.setReadAhead(device, kb) }, onSuccess = {
            MemoryBootTuning.saveReadAhead(getApplication(), device, kb)
        })

    /** Root only: Shizuku's ADB uid cannot trim filesystems. */
    fun runFstrim() =
        runOp(tuning = false, timeoutMs = 300_000L, op = {
            if (!ShellManager.hasRootCapability()) ShellManager.CommandResult(-1, "", str(R.string.mem_requires_root))
            else ShellManager.exec(ShellScripts.fstrim(), ShellManager.PrivilegeRequirement.ELEVATED_SYSFS, 300_000L)
        }, onSuccess = { r -> _infoMessage.value = str(R.string.mem_fstrim_done_format, r.stdout.ifBlank { "ok" }) })

    fun clearError() {
        _errorMessage.value = null
        _infoMessage.value = null
    }
}

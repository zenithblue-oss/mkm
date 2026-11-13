package com.ivarna.mkm.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ivarna.mkm.data.model.ApplyResult
import com.ivarna.mkm.data.model.CpuStatus
import com.ivarna.mkm.data.model.TuningMutationCoordinator
import com.ivarna.mkm.data.model.TuningPersistencePolicy
import com.ivarna.mkm.data.provider.CoreOnlineState
import com.ivarna.mkm.data.provider.CpuProvider
import com.ivarna.mkm.data.provider.CpuUtilizationProvider
import com.ivarna.mkm.data.provider.GovernorTunable
import com.ivarna.mkm.data.provider.ThermalDisableReport
import com.ivarna.mkm.data.provider.ThermalProvider
import com.ivarna.mkm.data.provider.ThermalStatus
import com.ivarna.mkm.data.provider.ThrottleInfo
import com.ivarna.mkm.service.BootSettingsManager
import com.ivarna.mkm.service.GameBoostRegistry
import com.ivarna.mkm.service.KernelTuningCoordinator
import com.ivarna.mkm.util.AppVisibilityMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CpuViewModel(application: Application) : AndroidViewModel(application) {
    private val _cpuStatus = MutableStateFlow(CpuStatus())
    val cpuStatus: StateFlow<CpuStatus> = _cpuStatus.asStateFlow()
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()
    private val _thermalStatus = MutableStateFlow(ThermalStatus(emptyList(), 0f))
    val thermalStatus: StateFlow<ThermalStatus> = _thermalStatus.asStateFlow()
    private val _bootEnabled = MutableStateFlow(BootSettingsManager.isCpuEnabled(application))
    val bootEnabled: StateFlow<Boolean> = _bootEnabled.asStateFlow()
    private val _pendingControlId = MutableStateFlow<String?>(null)
    val pendingControlId: StateFlow<String?> = _pendingControlId.asStateFlow()
    private val _lastApplyResult = MutableStateFlow<ApplyResult?>(null)
    val lastApplyResult: StateFlow<ApplyResult?> = _lastApplyResult.asStateFlow()
    private val _usageEstimated = MutableStateFlow(false)
    val usageEstimated: StateFlow<Boolean> = _usageEstimated.asStateFlow()
    private val _throttleInfo = MutableStateFlow(ThrottleInfo())
    val throttleInfo: StateFlow<ThrottleInfo> = _throttleInfo.asStateFlow()
    private val _coreStates = MutableStateFlow<List<CoreOnlineState>>(emptyList())
    val coreStates: StateFlow<List<CoreOnlineState>> = _coreStates.asStateFlow()
    private val _thermalReport = MutableStateFlow<ThermalDisableReport?>(null)
    val thermalReport: StateFlow<ThermalDisableReport?> = _thermalReport.asStateFlow()
    private val _thermalRestored = MutableStateFlow<Boolean?>(null)
    val thermalRestored: StateFlow<Boolean?> = _thermalRestored.asStateFlow()

    private val tuningCoordinator = TuningMutationCoordinator()
    @Volatile private var cachedLimit = 0
    private var limitLoaded = false
    private var monitorJob: Job? = null

    /** Values the user explicitly picked this session, per policy (null = not requested). */
    private data class Requested(val governor: String? = null, val max: String? = null, val min: String? = null)
    private val requested = mutableMapOf<Int, Requested>()

    /** Called by CpuScreen while it is on screen; nav saveState keeps this VM alive off-screen. */
    fun startMonitoring() {
        if (monitorJob?.isActive == true) return
        if (!limitLoaded) {
            limitLoaded = true
            // Separate job: the config/trip scan must not delay the first paint.
            viewModelScope.launch {
                cachedLimit = withContext(Dispatchers.IO) { ThermalProvider.getThermalLimit() }
                _thermalStatus.update { it.copy(currentLimit = cachedLimit) }
            }
        }
        refreshCoreStates()
        monitorJob = viewModelScope.launch {
            while (true) {
                if (AppVisibilityMonitor.isForeground.value) {
                    tuningCoordinator.withObservation { publishState() }
                }
                delay(2000L)
            }
        }
    }

    fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
    }

    private fun lockedOut(onResult: (ApplyResult) -> Unit): Boolean {
        if (!GameBoostRegistry.ownsTuning()) return false
        onResult(ApplyResult.Failed("Managed by Game Boost. Disable Game Boost to edit."))
        return true
    }

    fun setGovernor(policyId: Int, governor: String, onResult: (ApplyResult) -> Unit = {}) {
        if (lockedOut(onResult)) return
        mutate("cpu-policy-$policyId-governor", { CpuProvider.applyGovernor(policyId, governor) }, onResult) {
            record(policyId) { it.copy(governor = governor) }
        }
    }

    fun setFrequency(policyId: Int, freqKhz: String, isMax: Boolean, onResult: (ApplyResult) -> Unit = {}) {
        if (lockedOut(onResult)) return
        val value = freqKhz.toLongOrNull()
        mutate("cpu-policy-$policyId-${if (isMax) "max" else "min"}", {
            if (value == null) ApplyResult.Failed("Invalid CPU frequency")
            else if (isMax) CpuProvider.applyRange(policyId, desiredMax = value)
            else CpuProvider.applyRange(policyId, desiredMin = value)
        }, onResult) {
            record(policyId) { if (isMax) it.copy(max = value?.toString()) else it.copy(min = value?.toString()) }
        }
    }

    // Kept for benchmark/backward-compatible callers; all writes resolve to the owning policy.
    fun setGovernorForCore(coreId: Int, governor: String, onResult: (ApplyResult) -> Unit = {}) {
        val policyId = policyForCore(coreId)
        if (policyId == null) onResult(ApplyResult.Failed("No cpufreq policy owns CPU $coreId"))
        else setGovernor(policyId, governor, onResult)
    }

    fun setFrequencyForCore(coreId: Int, freqKhz: String, isMax: Boolean, onResult: (ApplyResult) -> Unit = {}) {
        val policyId = policyForCore(coreId)
        if (policyId == null) onResult(ApplyResult.Failed("No cpufreq policy owns CPU $coreId"))
        else setFrequency(policyId, freqKhz, isMax, onResult)
    }

    private fun policyForCore(coreId: Int): Int? = _cpuStatus.value.clusters.firstOrNull { cluster ->
        cluster.affectedCpus.contains(coreId) || cluster.relatedCpus.contains(coreId) ||
            cluster.cores.any { it.id == coreId && it.policyId != null }
    }?.id

    fun setCoreOnline(coreId: Int, online: Boolean, onResult: (ApplyResult) -> Unit = {}) {
        if (lockedOut(onResult)) return
        mutate("cpu-core-$coreId-online", { CpuProvider.setCoreOnline(coreId, online) }, { result ->
            refreshCoreStates()
            onResult(result)
        })
    }

    fun loadGovernorTunables(policyId: Int, onLoaded: (List<GovernorTunable>) -> Unit) {
        viewModelScope.launch {
            onLoaded(withContext(Dispatchers.IO) { CpuProvider.getGovernorTunables(policyId) })
        }
    }

    fun setGovernorTunable(policyId: Int, name: String, value: String, onResult: (ApplyResult) -> Unit = {}) {
        if (lockedOut(onResult)) return
        mutate("cpu-policy-$policyId-tunable-$name", { CpuProvider.setGovernorTunable(policyId, name, value) }, onResult)
    }

    private fun mutate(
        controlId: String,
        operation: () -> ApplyResult,
        onResult: (ApplyResult) -> Unit,
        onApplied: (() -> Unit)? = null
    ) {
        viewModelScope.launch {
            val result = tuningCoordinator.withMutation(controlId, { _pendingControlId.value = it }) {
                    val applied = try {
                        withContext(Dispatchers.IO) { KernelTuningCoordinator.withMutation { operation() } }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        ApplyResult.Failed(error.message ?: "CPU tuning operation failed")
                    }
                    var stateRefreshed = false
                    try {
                        publishState()
                        stateRefreshed = true
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        android.util.Log.e("CpuViewModel", "Failed to refresh after CPU tuning", error)
                    }
                    if (onApplied != null && applied !is ApplyResult.Failed) {
                        onApplied()
                        if (TuningPersistencePolicy.shouldPersist(applied, _bootEnabled.value, stateRefreshed)) {
                            saveRequestedCpuSettings()
                        }
                    }
                    applied
            }
            _lastApplyResult.value = result
            onResult(result)
        }
    }

    fun setThermalLimit(limit: Int) {
        if (GameBoostRegistry.ownsTuning()) return
        viewModelScope.launch {
            tuningCoordinator.withObservation {
                withContext(Dispatchers.IO) {
                    KernelTuningCoordinator.withMutation {
                        if (ThermalProvider.setThermalLimit(limit)) cachedLimit = ThermalProvider.getThermalLimit()
                    }
                }
                publishState()
            }
        }
    }

    fun disableThrottling() {
        if (GameBoostRegistry.ownsTuning()) return
        viewModelScope.launch {
            tuningCoordinator.withObservation {
                _thermalRestored.value = null
                _thermalReport.value = withContext(Dispatchers.IO) {
                    KernelTuningCoordinator.withMutation { ThermalProvider.disableThrottling() }
                }
                publishState()
            }
        }
    }

    fun restoreThermal() {
        if (GameBoostRegistry.ownsTuning()) return
        viewModelScope.launch {
            tuningCoordinator.withObservation {
                _thermalReport.value = null
                _thermalRestored.value = withContext(Dispatchers.IO) {
                    KernelTuningCoordinator.withMutation { ThermalProvider.restoreThermal() }
                }
                publishState()
            }
        }
    }

    fun toggleBootEnabled(enabled: Boolean) {
        if (GameBoostRegistry.ownsTuning()) return
        BootSettingsManager.setCpuEnabled(getApplication(), enabled)
        _bootEnabled.value = enabled
        if (enabled) saveRequestedCpuSettings()
    }

    private fun record(policyId: Int, change: (Requested) -> Requested) {
        synchronized(requested) { requested[policyId] = change(requested[policyId] ?: Requested()) }
    }

    /** Persists only explicitly requested values, merged per policy with what was saved before. */
    private fun saveRequestedCpuSettings() {
        val app = getApplication<Application>()
        val snapshot = synchronized(requested) { requested.toMap() }
        if (snapshot.isEmpty()) return
        val stored = BootSettingsManager.loadCpuPolicies(app).associateBy { it.policyId }
        snapshot.forEach { (policyId, req) ->
            val old = stored[policyId]
            // Boot apply needs a governor entry; the live governor is not altered by thermal caps.
            val governor = req.governor ?: old?.governor
                ?: _cpuStatus.value.clusters.firstOrNull { it.id == policyId }?.governor ?: return@forEach
            BootSettingsManager.saveCpuPolicy(app, policyId, governor, req.max ?: old?.maxFreq ?: "", req.min ?: old?.minFreq ?: "")
        }
    }

    private fun refreshCoreStates() {
        viewModelScope.launch {
            _coreStates.value = withContext(Dispatchers.IO) { CpuProvider.getCoreOnlineStates() }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try { tuningCoordinator.withObservation { publishState() } }
            finally { _isRefreshing.value = false }
        }
        refreshCoreStates()
    }

    private suspend fun publishState() {
        val status = withContext(Dispatchers.IO) { CpuProvider.getCpuStatus() }
        _cpuStatus.value = status
        _usageEstimated.value = CpuUtilizationProvider.isEstimate
        val thermal = withContext(Dispatchers.IO) { ThermalProvider.getThermalStatus(fetchLimit = false) }
        _thermalStatus.value = thermal.copy(currentLimit = cachedLimit)
        _throttleInfo.value = withContext(Dispatchers.IO) { ThermalProvider.getThrottleInfo(getApplication()) }
    }
}

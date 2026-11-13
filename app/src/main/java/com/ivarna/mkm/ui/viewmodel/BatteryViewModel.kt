package com.ivarna.mkm.ui.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ivarna.mkm.data.model.BatteryDrainer
import com.ivarna.mkm.data.model.BatterySessionRecord
import com.ivarna.mkm.data.model.BatteryStats
import com.ivarna.mkm.data.provider.BatteryProvider
import com.ivarna.mkm.service.BatteryMonitorService
import com.ivarna.mkm.service.BatterySessionTracker
import com.ivarna.mkm.util.AppVisibilityMonitor
import com.ivarna.mkm.utils.BatteryHistoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for the Battery screen.
 *
 * Binds to [BatteryMonitorService] only while the app is in the foreground, so the
 * tracker does not keep polling for a backgrounded UI. With the persistent notification
 * enabled the service is started and keeps running on its own.
 */
class BatteryViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val PREFS_NAME = BatteryMonitorService.PREFS_NAME
        private const val PREF_NOTIFICATION_ENABLED = BatteryMonitorService.PREF_NOTIFICATION_ENABLED
    }

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val historyManager = BatteryHistoryManager.get(application)

    private val _batteryStats = MutableStateFlow<BatteryStats?>(null)
    val batteryStats: StateFlow<BatteryStats?> = _batteryStats.asStateFlow()

    private val _showNotification = MutableStateFlow(false)
    val showNotification: StateFlow<Boolean> = _showNotification.asStateFlow()

    /** Shared with the tracker; available before the service binds. */
    val history: StateFlow<List<BatterySessionRecord>> = historyManager.records

    private val _chargeLimitEnabled = MutableStateFlow(prefs.getBoolean(BatterySessionTracker.PREF_CHARGE_LIMIT_ENABLED, false))
    val chargeLimitEnabled: StateFlow<Boolean> = _chargeLimitEnabled.asStateFlow()

    private val _chargeLimitPercent = MutableStateFlow(
        prefs.getInt(BatterySessionTracker.PREF_CHARGE_LIMIT_PERCENT, BatterySessionTracker.DEFAULT_CHARGE_LIMIT).coerceIn(50, 100)
    )
    val chargeLimitPercent: StateFlow<Int> = _chargeLimitPercent.asStateFlow()

    private val _drainers = MutableStateFlow<List<BatteryDrainer>>(emptyList())
    val drainers: StateFlow<List<BatteryDrainer>> = _drainers.asStateFlow()

    private val _drainersLoading = MutableStateFlow(false)
    val drainersLoading: StateFlow<Boolean> = _drainersLoading.asStateFlow()

    private var serviceBound = false
    private var statsCollectionJob: Job? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as BatteryMonitorService.LocalBinder
            statsCollectionJob?.cancel()
            statsCollectionJob = viewModelScope.launch {
                binder.stats.collect { stats ->
                    _batteryStats.value = stats
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            statsCollectionJob?.cancel()
        }
    }

    init {
        _showNotification.value = prefs.getBoolean(PREF_NOTIFICATION_ENABLED, false)

        if (_showNotification.value) {
            val intent = Intent(application, BatteryMonitorService::class.java).apply {
                action = BatteryMonitorService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                application.startForegroundService(intent)
            } else {
                application.startService(intent)
            }
        }

        viewModelScope.launch {
            AppVisibilityMonitor.isForeground.collect { foreground ->
                if (foreground) bindToService() else unbindFromService()
            }
        }
    }

    fun setNotificationEnabled(enabled: Boolean) {
        _showNotification.value = enabled
        prefs.edit()
            .putBoolean(PREF_NOTIFICATION_ENABLED, enabled)
            .apply()

        val intent = Intent(getApplication(), BatteryMonitorService::class.java).apply {
            action = if (enabled) BatteryMonitorService.ACTION_START else BatteryMonitorService.ACTION_STOP
        }
        if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getApplication<Application>().startForegroundService(intent)
        } else {
            getApplication<Application>().startService(intent)
        }
    }

    fun setChargeLimitEnabled(enabled: Boolean) {
        _chargeLimitEnabled.value = enabled
        prefs.edit().putBoolean(BatterySessionTracker.PREF_CHARGE_LIMIT_ENABLED, enabled).apply()
    }

    fun setChargeLimitPercent(percent: Int) {
        val safe = percent.coerceIn(50, 100)
        _chargeLimitPercent.value = safe
        prefs.edit().putInt(BatterySessionTracker.PREF_CHARGE_LIMIT_PERCENT, safe).apply()
    }

    /** Manual only: dumpsys batterystats is expensive, never polled. */
    fun refreshDrainers() {
        if (_drainersLoading.value) return
        _drainersLoading.value = true
        viewModelScope.launch {
            _drainers.value = withContext(Dispatchers.IO) {
                runCatching { BatteryProvider.readTopDrainers(getApplication<Application>()) }.getOrDefault(emptyList())
            }
            _drainersLoading.value = false
        }
    }

    fun clearHistory() {
        // Same instance the tracker writes to, so this clears the file whether or not we are bound.
        viewModelScope.launch(Dispatchers.IO) { historyManager.clear() }
    }

    override fun onCleared() {
        super.onCleared()
        unbindFromService()
    }

    private fun bindToService() {
        if (serviceBound) return
        val intent = Intent(getApplication(), BatteryMonitorService::class.java)
        serviceBound = getApplication<Application>().bindService(
            intent,
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    private fun unbindFromService() {
        statsCollectionJob?.cancel()
        if (!serviceBound) return
        runCatching { getApplication<Application>().unbindService(serviceConnection) }
        serviceBound = false
    }
}

package com.ivarna.mkm.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.net.TrafficStats
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.ivarna.mkm.shell.ShellManager
import kotlinx.coroutines.flow.first
import com.ivarna.mkm.utils.StatusIconRenderer
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.content.pm.ServiceInfo
import com.ivarna.mkm.R
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.Canvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.*
import androidx.savedstate.*
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.ivarna.mkm.data.SystemRepository
import com.ivarna.mkm.data.HomeData
import com.ivarna.mkm.data.model.FpsSource
import com.ivarna.mkm.data.provider.PowerCalibrationManager
import com.ivarna.mkm.utils.FpsMonitor
import com.ivarna.mkm.utils.FpsSessionRecorder
import com.ivarna.mkm.utils.GpuFpsCollector
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var composeView: ComposeView
    private lateinit var viewModelStore: ViewModelStore
    private lateinit var lifecycleOwner: LifecycleOwner
    private lateinit var savedStateRegistryOwner: SavedStateRegistryOwner
    private val repository = SystemRepository(this)
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val uiState = MutableStateFlow<HomeData?>(null)
    private var isMovableState by mutableStateOf(true)
    private var attachPositionState by mutableStateOf("top_center")
    private var showCpuUsageState by mutableStateOf(true)
    private var showCpuFreqState by mutableStateOf(true)
    private var showGpuUsageState by mutableStateOf(true)
    private var showRamUsageState by mutableStateOf(true)
    private var showSwapUsageState by mutableStateOf(true)
    private var showPowerUsageState by mutableStateOf(true)
    private var showCpuTempState by mutableStateOf(false)
    private var showBatteryTempState by mutableStateOf(false)
    private var showProgressBarsState by mutableStateOf(true)
    private var showBatteryPercentState by mutableStateOf(false)
    private var updateIntervalState by mutableStateOf(2000L)
    private var showIconsOnlyState by mutableStateOf(false)
    private var isGridViewState by mutableStateOf(false)
    private var gridColumnsState by mutableStateOf(2)
    private var isHorizontalState by mutableStateOf(false)
    private var componentOrderState by mutableStateOf(listOf<String>())
    private var overlayOpacityState by mutableStateOf(0.9f)
    private var accentColorIndexState by mutableStateOf(0)
    private var showSparklinesState by mutableStateOf(false)
    private var showAbsoluteValuesState by mutableStateOf(false)
    private var cpuFreqDisplayState by mutableStateOf("all_cores")
    private var accentTintBackgroundState by mutableStateOf(false)
    private var accentBgColorIndexState by mutableStateOf(-1)
    private var absCpuState by mutableStateOf(true)
    private var absGpuState by mutableStateOf(true)
    private var absRamState by mutableStateOf(true)
    private var absSwapState by mutableStateOf(true)
    private var showFpsState by mutableStateOf(false)
    private val _fpsState = MutableStateFlow(FpsMonitor.FpsResult(-1f))
    private val fpsState = _fpsState.asStateFlow()
    private var recordingJob: Job? = null

    // History for Sparklines: Map of metric key to list of values
    private val metricHistory = mutableStateMapOf<String, List<Float>>()
    private val MAX_HISTORY = 15

    private val CHANNEL_ID = "overlay_service"
    private val STATUS_ICON_CHANNEL_ID = "overlay_status_icon"
    private val NOTIFICATION_ID = 1001

    companion object {
        const val PREFS_NAME = "overlay_prefs"
        const val KEY_UPDATE_INTERVAL = "update_interval"
        const val DEFAULT_UPDATE_INTERVAL_MS = 2000L
        const val MIN_UPDATE_INTERVAL_MS = 500L
        const val MAX_UPDATE_INTERVAL_MS = 5000L

        /** Comma-separated metric keys (max 2, shown stacked in one icon); "off" or empty = no icon. */
        const val KEY_STATUS_ICON_METRIC = "status_icon_metric"
        const val MAX_STATUS_ICON_METRICS = 2

        @Volatile
        var isRunning: Boolean = false
            private set

        internal fun nextDelayMs(intervalMs: Long, elapsedMs: Long): Long =
            (intervalMs.coerceAtLeast(MIN_UPDATE_INTERVAL_MS) - elapsedMs).coerceAtLeast(0L)

        /** Parses the status icon pref; a legacy single value ("power") stays valid. */
        internal fun parseStatusIconMetrics(pref: String?): List<String> =
            pref.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() && it != "off" }
                .distinct().take(MAX_STATUS_ICON_METRICS)

        /** Short text for one status bar icon metric, or null for an unknown key. */
        internal fun statusIconText(metric: String, h: HomeData, netRxBps: Long = -1L): String? = when (metric) {
            "power" -> String.format("%.1f", kotlin.math.abs(h.power.calibratedPowerW))
            "battery_temp" -> String.format("%.0f", h.batteryTemp)
            "cpu_temp" -> String.format("%.0f", h.cpuTemp)
            "battery_percent" -> h.power.batteryPercent.toString()
            "net_speed" -> formatRate(netRxBps)
            else -> null
        }

        /** Bytes/s from two TrafficStats readings; -1 = unsupported, 0 on counter reset. */
        internal fun netRateBps(prevBytes: Long, curBytes: Long, elapsedMs: Long): Long = when {
            prevBytes < 0L || curBytes < 0L -> -1L
            elapsedMs <= 0L || curBytes < prevBytes -> 0L
            else -> (curBytes - prevBytes) * 1000L / elapsedMs
        }

        internal fun formatRate(bps: Long): String = when {
            bps < 0L -> "N/A"
            bps >= 1024L * 1024L -> String.format(java.util.Locale.US, "%.1fM", bps / (1024.0 * 1024.0))
            else -> "${bps / 1024L}K"
        }
    }

    private var statusIconMetricsState = emptyList<String>()
    private var lastStatusIconText: List<String>? = null

    private fun updateStatusIcon(h: HomeData) {
        val texts = statusIconMetricsState.mapNotNull { statusIconText(it, h, netRxBpsState) }
        if (texts == lastStatusIconText) return
        lastStatusIconText = texts
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, createNotification(texts))
    }

    // Net speed (bytes/s); -1 until two readings exist or when TrafficStats is unsupported
    private var netRxBpsState by mutableStateOf(-1L)
    private var netTxBpsState by mutableStateOf(-1L)
    private var prevRxBytes = -1L
    private var prevTxBytes = -1L
    private var prevNetTimeMs = 0L
    private var showNetSpeedState by mutableStateOf(false)

    private fun updateNetSpeed() {
        val now = SystemClock.elapsedRealtime()
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        if (prevNetTimeMs > 0L) {
            netRxBpsState = netRateBps(prevRxBytes, rx, now - prevNetTimeMs)
            netTxBpsState = netRateBps(prevTxBytes, tx, now - prevNetTimeMs)
        }
        prevRxBytes = rx
        prevTxBytes = tx
        prevNetTimeMs = now
    }

    // Polling stops while the screen is off and resumes on SCREEN_ON
    private val interactive = MutableStateFlow(true)
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            interactive.value = intent.action != Intent.ACTION_SCREEN_OFF
        }
    }
    private var overlayAdded = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        val owner = object : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
            private val lifecycleRegistry = LifecycleRegistry(this)
            private val savedStateController = SavedStateRegistryController.create(this)
            
            override val lifecycle: Lifecycle = lifecycleRegistry
            override val viewModelStore: ViewModelStore = ViewModelStore()
            override val savedStateRegistry: SavedStateRegistry = savedStateController.savedStateRegistry
            
            init {
                savedStateController.performRestore(null)
                lifecycleRegistry.currentState = Lifecycle.State.RESUMED
            }
        }
        
        this.lifecycleOwner = owner
        this.savedStateRegistryOwner = owner
        this.viewModelStore = owner.viewModelStore

        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
        isRunning = true
        interactive.value = getSystemService(PowerManager::class.java)?.isInteractive ?: true
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        showOverlay()
        startMonitoring()
        observeFpsRecording()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "UPDATE_SETTINGS") {
            updateSettings()
        }
        return START_STICKY
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("overlay_prefs", Context.MODE_PRIVATE)
        showCpuUsageState = prefs.getBoolean("show_cpu_usage", true)
        showCpuFreqState = prefs.getBoolean("show_cpu_freq", true)
        showGpuUsageState = prefs.getBoolean("show_gpu_usage", true)
        showRamUsageState = prefs.getBoolean("show_ram_usage", true)
        showSwapUsageState = prefs.getBoolean("show_swap_usage", true)
        showPowerUsageState = prefs.getBoolean("show_power", true)
        showCpuTempState = prefs.getBoolean("show_cpu_temp", false)
        showBatteryTempState = prefs.getBoolean("show_battery_temp", false)
        showProgressBarsState = prefs.getBoolean("show_progress_bars", true)
        showBatteryPercentState = prefs.getBoolean("show_battery_percent", false)
        updateIntervalState = prefs.getLong(KEY_UPDATE_INTERVAL, DEFAULT_UPDATE_INTERVAL_MS)
            .coerceIn(MIN_UPDATE_INTERVAL_MS, MAX_UPDATE_INTERVAL_MS)
        showIconsOnlyState = prefs.getBoolean("show_icons_only", false)
        isGridViewState = prefs.getBoolean("is_grid_view", false)
        gridColumnsState = prefs.getInt("grid_columns", 2)
        isHorizontalState = prefs.getBoolean("is_horizontal", false)
        val defaultOrder = "cpu_usage,cpu_freq,gpu_usage,ram_usage,swap_usage,power_usage,cpu_temp,battery_temp,battery_percent,fps,net_speed"
        val savedOrder = (prefs.getString("component_order", defaultOrder) ?: defaultOrder).split(",")
        // Ensure any newly added default items (e.g. "fps") are included
        val defaultItems = defaultOrder.split(",")
        componentOrderState = (savedOrder + defaultItems.filter { it !in savedOrder }).distinct()
        Log.d("OverlayService", "Loaded component order: ${componentOrderState.joinToString(",")}")
        isMovableState = prefs.getBoolean("movable", true)
        attachPositionState = prefs.getString("attach_position", "top_center") ?: "top_center"
        overlayOpacityState = prefs.getFloat("overlay_opacity", 0.9f)
        accentColorIndexState = prefs.getInt("accent_color_index", 0)
        showSparklinesState = prefs.getBoolean("show_sparklines", false)
        showAbsoluteValuesState = prefs.getBoolean("show_absolute_values", false)
        accentTintBackgroundState = prefs.getBoolean("accent_tint_background", false)
        accentBgColorIndexState = prefs.getInt("accent_bg_color_index", -1)
        cpuFreqDisplayState = prefs.getString("cpu_freq_display", "all_cores") ?: "all_cores"
        absCpuState = prefs.getBoolean("abs_cpu", true)
        absGpuState = prefs.getBoolean("abs_gpu", true)
        absRamState = prefs.getBoolean("abs_ram", true)
        absSwapState = prefs.getBoolean("abs_swap", true)
        showFpsState = prefs.getBoolean("show_fps", false)
        showNetSpeedState = prefs.getBoolean("show_net_speed", false)
        statusIconMetricsState = parseStatusIconMetrics(prefs.getString(KEY_STATUS_ICON_METRIC, "off"))
    }

    private fun updateSettings() {
        loadSettings()
        if (!overlayAdded || !composeView.isAttachedToWindow) return

        val flags = if (isMovableState) {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            // Avoid status bar overlap by removing FLAG_LAYOUT_IN_SCREEN
        }

        // preferredRefreshRate stays 0: pinning max Hz for the overlay would force the whole panel there
        val params = (composeView.layoutParams as WindowManager.LayoutParams).apply {
            this.flags = flags
            if (isMovableState) {
                // If switching back to movable from a fixed position, reset to a default point
                if (x == 0 && (gravity == (Gravity.TOP or Gravity.CENTER_HORIZONTAL) || 
                              gravity == (Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL) ||
                              gravity == (Gravity.START or Gravity.CENTER_VERTICAL) ||
                              gravity == (Gravity.END or Gravity.CENTER_VERTICAL))) {
                    gravity = Gravity.TOP or Gravity.START
                    x = 100
                    y = 100
                }
            } else {
                gravity = when (attachPositionState) {
                    "top_left" -> Gravity.TOP or Gravity.START
                    "top_right" -> Gravity.TOP or Gravity.END
                    "bottom_left" -> Gravity.BOTTOM or Gravity.START
                    "bottom_right" -> Gravity.BOTTOM or Gravity.END
                    "bottom_center" -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    "left_center" -> Gravity.START or Gravity.CENTER_VERTICAL
                    "right_center" -> Gravity.END or Gravity.CENTER_VERTICAL
                    else -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
                }
                x = 0
                y = 0
            }
        }
        
        windowManager.updateViewLayout(composeView, params)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Overlay Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
            // Android hides status bar icons of low-importance (silent) notifications, so the
            // optional status icon uses its own default-importance channel with no sound/vibration.
            manager.createNotificationChannel(
                NotificationChannel(
                    STATUS_ICON_CHANNEL_ID,
                    "Overlay Status Icon",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                }
            )
        }
    }

    private fun createNotification(iconTexts: List<String> = emptyList()): Notification {
        val showIcon = iconTexts.isNotEmpty()
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, if (showIcon) STATUS_ICON_CHANNEL_ID else CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }

        builder
            .setContentTitle("MKM Overlay Active")
            .setContentText("Status monitor is running")
            .setOnlyAlertOnce(true)
        if (showIcon) {
            builder.setSmallIcon(android.graphics.drawable.Icon.createWithBitmap(StatusIconRenderer.render(iconTexts)))
        } else {
            builder.setSmallIcon(R.drawable.ic_stat_battery)
        }
        return builder.build()
    }

    private fun startMonitoring() {
        val calibrationManager = PowerCalibrationManager(this)
        serviceScope.launch {
            // One sequential loop: a slow tick delays the next one instead of stacking
            while (isActive) {
                if (!interactive.value) {
                    interactive.first { it }
                    prevNetTimeMs = 0L // don't average net speed across the screen-off gap
                }
                val tickStart = SystemClock.elapsedRealtime()
                if (showNetSpeedState || "net_speed" in statusIconMetricsState) updateNetSpeed()
                val data = repository.getHomeData(calibrationManager.getMultiplier())
                uiState.value = data
                
                data?.let { h ->
                    updateHistory("cpu_usage", h.cpu.overallUsage)
                    // Average cur/max across clusters (hwMaxFreq is kHz; currentFreq is formatted text).
                    val freqRatios = h.cpu.clusters.mapNotNull { c ->
                        val maxKhz = c.hwMaxFreq.toLongOrNull()?.takeIf { it > 0L } ?: c.rawMaxFreq.toLongOrNull()?.takeIf { it > 0L }
                        maxKhz?.let { (parseFreqMHz(c.currentFreq) * 1000f / it).coerceIn(0f, 1f) }
                    }
                    updateHistory("cpu_freq", if (freqRatios.isEmpty()) 0f else freqRatios.average().toFloat())
                    updateHistory("gpu_usage", h.gpu.loadPercent)
                    updateHistory("ram_usage", h.memory.usagePercent)
                    updateHistory("swap_usage", h.swap.usagePercent)
                    updateHistory("power_usage", (kotlin.math.abs(h.power.calibratedPowerW) / 5f).coerceIn(0f, 1f))
                    updateHistory("cpu_temp", (h.cpuTemp / 100f).coerceIn(0f, 1f))
                    updateHistory("battery_temp", (h.batteryTemp / 100f).coerceIn(0f, 1f))
                    updateHistory("battery_percent", h.power.batteryPercent / 100f)
                }
                
                // Read FPS (gfxinfo for per-app, OnPreDrawListener fallback)
                if (showFpsState) {
                    val maxRate = getActiveRefreshRate().coerceAtLeast(60f)
                    FpsMonitor.updateDisplayRefreshRate(maxRate)
                    // The passive overlay-redraw estimate is only a fallback when no elevated source exists
                    val elevated = withContext(Dispatchers.IO) { ShellManager.hasElevatedAccess() } // libsu may block
                    if (!elevated && overlayAdded) {
                        FpsMonitor.initOverlayFps(composeView)
                    } else {
                        FpsMonitor.stopOverlayFps()
                    }
                    val fpsResult = withContext(Dispatchers.IO) { FpsMonitor.readFps() }
                    _fpsState.value = fpsResult
                    updateHistory("fps", (fpsResult.fps / maxRate).coerceIn(0f, 1f))
                } else {
                    FpsMonitor.stopOverlayFps()
                }
                
                data?.let { updateStatusIcon(it) }

                // Interval is the loop period, not idle time after the work
                delay(nextDelayMs(updateIntervalState, SystemClock.elapsedRealtime() - tickStart))
            }
        }
    }

    private fun updateHistory(key: String, value: Float) {
        val current = metricHistory[key] ?: emptyList()
        val next = (current + value).takeLast(MAX_HISTORY)
        metricHistory[key] = next
    }

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    private fun showOverlay() {
        if (::composeView.isInitialized && composeView.isAttachedToWindow) return
        
        loadSettings()
        
        val flags = if (isMovableState) {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            // Avoid status bar overlap by removing FLAG_LAYOUT_IN_SCREEN
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            if (isMovableState) {
                gravity = Gravity.TOP or Gravity.START
                x = 100
                y = 100
            } else {
                gravity = when (attachPositionState) {
                    "top_left" -> Gravity.TOP or Gravity.START
                    "top_right" -> Gravity.TOP or Gravity.END
                    "bottom_left" -> Gravity.BOTTOM or Gravity.START
                    "bottom_right" -> Gravity.BOTTOM or Gravity.END
                    "bottom_center" -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    "left_center" -> Gravity.START or Gravity.CENTER_VERTICAL
                    "right_center" -> Gravity.END or Gravity.CENTER_VERTICAL
                    else -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
                }
                x = 0
                y = 0
            }
        }

        composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@OverlayService.lifecycleOwner)
            setViewTreeViewModelStoreOwner(this@OverlayService.lifecycleOwner as ViewModelStoreOwner)
            setViewTreeSavedStateRegistryOwner(this@OverlayService.savedStateRegistryOwner)
            
            setContent {
                val accentColors = listOf(
                    Color(0xFFD0BCFF),
                    Color(0xFF4CAF50),
                    Color(0xFF2196F3),
                    Color(0xFFF44336),
                    Color(0xFFFFEB3B),
                    Color(0xFFE91E63),
                    Color(0xFF9C27B0),
                    Color(0xFF00BCD4)
                )
                val selectedColor = accentColors.getOrElse(accentColorIndexState) { accentColors[0] }
                val baseSurface = Color(0xFF49454F)
                val backgroundColor = if (accentTintBackgroundState) {
                    val bgIndex = if (accentBgColorIndexState == -1) accentColorIndexState else accentBgColorIndexState
                    val bgColor = accentColors.getOrElse(bgIndex) { selectedColor }
                    bgColor.copy(alpha = 0.25f)
                } else {
                    baseSurface
                }

                MaterialTheme(colorScheme = darkColorScheme(primary = selectedColor, surfaceVariant = backgroundColor)) {
                    val data by uiState.collectAsState()

                    Card(
                        modifier = Modifier
                            .let {
                                if (isMovableState) {
                                    it.pointerInput(Unit) {
                                        detectDragGestures { change, dragAmount ->
                                            change.consume()
                                            params.x += dragAmount.x.toInt()
                                            params.y += dragAmount.y.toInt()
                                            windowManager.updateViewLayout(this@apply, params)
                                        }
                                    }
                                } else it
                            }
                            .padding(2.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = backgroundColor.copy(alpha = overlayOpacityState)
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                    ) {
                        data?.let { homeData ->
                            val metricsMap = mutableMapOf<String, @Composable () -> Unit>()

                            if (showCpuUsageState) metricsMap["cpu_usage"] = {
                                if (showAbsoluteValuesState && absCpuState) {
                                    val freqStr = formatFreqCompact(parseFreqMHz(homeData.cpu.clusters.firstOrNull()?.currentFreq ?: "0 MHz"))
                                    CompactMetric("CPU", freqStr, 0f, false, Icons.Default.DeveloperBoard, showIconsOnlyState, null)
                                } else {
                                    CompactMetric("CPU", "${(homeData.cpu.overallUsage * 100).toInt()}%", homeData.cpu.overallUsage, showProgressBarsState, Icons.Default.DeveloperBoard, showIconsOnlyState, metricHistory["cpu_usage"])
                                }
                            }
                            if (showCpuFreqState) metricsMap["cpu_freq"] = {
                                val freqStr = when (cpuFreqDisplayState) {
                                    "avg" -> {
                                        val freqs = homeData.cpu.clusters.mapNotNull { cluster ->
                                            val mhz = parseFreqMHz(cluster.currentFreq)
                                            if (mhz > 0L) mhz else null
                                        }
                                        if (freqs.isNotEmpty()) formatFreqCompact(freqs.average().toLong()) else "0"
                                    }
                                    "max" -> {
                                        val maxMhz = homeData.cpu.clusters.mapNotNull { cluster ->
                                            val mhz = parseFreqMHz(cluster.currentFreq)
                                            if (mhz > 0L) mhz else null
                                        }.maxOrNull() ?: 0L
                                        formatFreqCompact(maxMhz)
                                    }
                                    else -> {
                                        homeData.cpu.clusters.sortedBy { it.id }.joinToString(" ") { cluster ->
                                            formatFreqCompact(parseFreqMHz(cluster.currentFreq))
                                        }
                                    }
                                }
                                CompactMetric("FREQ", freqStr, 0f, false, Icons.Default.Speed, showIconsOnlyState, metricHistory["cpu_freq"])
                            }
                            if (showGpuUsageState) metricsMap["gpu_usage"] = {
                                if (showAbsoluteValuesState && absGpuState) {
                                    val freqStr = if (homeData.gpu.frequencyAvailable) {
                                        formatFreqCompact(parseFreqMHz(homeData.gpu.currentFreq))
                                    } else "N/A"
                                    CompactMetric("GPU", freqStr, 0f, false, Icons.Default.VideogameAsset, showIconsOnlyState, null)
                                } else {
                                    CompactMetric("GPU", "${(homeData.gpu.loadPercent * 100).toInt()}%", homeData.gpu.loadPercent, showProgressBarsState, Icons.Default.VideogameAsset, showIconsOnlyState, metricHistory["gpu_usage"])
                                }
                            }
                            if (showRamUsageState) metricsMap["ram_usage"] = {
                                if (showAbsoluteValuesState && absRamState) {
                                    CompactMetric("RAM", homeData.memory.usedUi, 0f, false, Icons.Default.Memory, showIconsOnlyState, null)
                                } else {
                                    CompactMetric("RAM", "${(homeData.memory.usagePercent * 100).toInt()}%", homeData.memory.usagePercent, showProgressBarsState, Icons.Default.Memory, showIconsOnlyState, metricHistory["ram_usage"])
                                }
                            }
                            if (showSwapUsageState && homeData.swap.isActive) metricsMap["swap_usage"] = {
                                if (showAbsoluteValuesState && absSwapState) {
                                    CompactMetric("SWAP", homeData.swap.usedUi, 0f, false, Icons.Default.SwapCalls, showIconsOnlyState, null)
                                } else {
                                    CompactMetric("SWAP", "${(homeData.swap.usagePercent * 100).toInt()}%", homeData.swap.usagePercent, showProgressBarsState, Icons.Default.SwapCalls, showIconsOnlyState, metricHistory["swap_usage"])
                                }
                            }
                            if (showPowerUsageState) metricsMap["power_usage"] = {
                                // Polarity from Android charging state (PowerProvider.isCharging);
                                // magnitude is always |calibratedPowerW| so sign never doubles.
                                val sign = if (homeData.power.isCharging) "+" else "-"
                                val powerStr = String.format(
                                    "%s%.2f W",
                                    sign,
                                    kotlin.math.abs(homeData.power.calibratedPowerW)
                                )
                                CompactMetric("PWR", powerStr, 0f, false, Icons.Default.FlashOn, showIconsOnlyState, metricHistory["power_usage"])
                            }
                            if (showCpuTempState) metricsMap["cpu_temp"] = {
                                CompactMetric("CTMP", String.format("%.1f°C", homeData.cpuTemp), homeData.cpuTemp / 100f, showProgressBarsState, Icons.Default.Thermostat, showIconsOnlyState, metricHistory["cpu_temp"])
                            }
                            if (showBatteryTempState) metricsMap["battery_temp"] = {
                                CompactMetric("BTMP", String.format("%.1f°C", homeData.batteryTemp), homeData.batteryTemp / 100f, showProgressBarsState, Icons.Default.BatteryChargingFull, showIconsOnlyState, metricHistory["battery_temp"])
                            }
                            if (showBatteryPercentState) metricsMap["battery_percent"] = {
                                CompactMetric("BAT", "${homeData.power.batteryPercent}%", homeData.power.batteryPercent / 100f, showProgressBarsState, Icons.Default.BatteryStd, showIconsOnlyState, metricHistory["battery_percent"])
                            }
                            metricsMap["fps"] = {
                                if (showFpsState) {
                                                    val currentFps by fpsState.collectAsState()
                                    val maxRate = getActiveRefreshRate().coerceAtLeast(60f)
                                    val clampedFps = currentFps.fps.coerceIn(0f, maxRate)
                                    // -1 = no data ("N/A"); 0 = app idle. Badge names the source (SF/gfx/ftrace/est.).
                                    val badge = when (currentFps.source) {
                                        FpsSource.SURFACEFLINGER -> stringResource(R.string.fps_badge_sf)
                                        FpsSource.GFXINFO -> stringResource(R.string.fps_badge_gfx)
                                        FpsSource.ADRENO_INFLIGHT, FpsSource.MALI_DMA_FENCE -> stringResource(R.string.fps_badge_ftrace)
                                        FpsSource.FPS_MONITOR -> stringResource(R.string.fps_badge_est)
                                        null -> null
                                    }
                                    val fpsDisplay = if (currentFps.fps < 0f) "N/A"
                                        else String.format("%.0f", clampedFps) + (badge?.let { " $it" } ?: "")
                                    val progress = (clampedFps / maxRate).coerceIn(0f, 1f)
                                    CompactMetric("FPS", fpsDisplay, progress, true, Icons.Default.SlowMotionVideo, showIconsOnlyState, metricHistory["fps"])
                                }
                            }
                            if (showNetSpeedState) metricsMap["net_speed"] = {
                                CompactMetric("NET", "↓${formatRate(netRxBpsState)} ↑${formatRate(netTxBpsState)}", 0f, false, Icons.Default.NetworkCheck, showIconsOnlyState, null)
                            }

                            val metrics = componentOrderState.mapNotNull { metricsMap[it] }

                            if (isHorizontalState) {
                                Row(
                                    modifier = Modifier.padding(8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    metrics.forEach { it() }
                                }
                            } else if (isGridViewState) {
                                Row(
                                    modifier = Modifier.padding(8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    for (i in 0 until gridColumnsState) {
                                        val colMetrics = metrics.filterIndexed { index, _ -> index % gridColumnsState == i }
                                        if (colMetrics.isNotEmpty()) {
                                            Column(
                                                verticalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                colMetrics.forEach { it() }
                                            }
                                        }
                                    }
                                }
                            } else {
                                Column(
                                    modifier = Modifier.padding(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    metrics.forEach { it() }
                                }
                            }
                        } ?: Text("Loading...", modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        try {
            windowManager.addView(composeView, params)
            overlayAdded = true
        } catch (e: WindowManager.BadTokenException) {
            failOverlay(e)
        } catch (e: SecurityException) {
            failOverlay(e)
        }
    }

    /** Overlay permission revoked or window token invalid: stop instead of crashing, and reflect it in prefs. */
    private fun failOverlay(e: Exception) {
        Log.e("OverlayService", "Cannot add overlay window", e)
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean("enabled", false).apply()
        stopSelf()
    }

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    @Composable
    private fun CompactMetric(
        label: String,
        value: String,
        progress: Float,
        showProgress: Boolean = true,
        icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
        showIconsOnly: Boolean = false,
        history: List<Float>? = null
    ) {
        val animatedProgress by animateFloatAsState(
            targetValue = progress,
            animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec,
            label = "progress"
        )

        val metricColor = when {
            animatedProgress > 0.9f -> MaterialTheme.colorScheme.error
            animatedProgress > 0.7f -> Color(0xFFFFA000) // Warning Amber
            else -> MaterialTheme.colorScheme.primary
        }

        Row(
            modifier = Modifier.padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(if (showProgress) 4.dp else 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showIconsOnly && icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = metricColor,
                    modifier = Modifier.size(18.dp)
                )
            } else {
                Text(
                    text = "${label}:",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = metricColor,
                    modifier = Modifier.widthIn(min = 32.dp)
                )
            }
            
            if (showProgress) {
                if (showSparklinesState && history != null) {
                    Sparkline(
                        history = history,
                        color = metricColor,
                        modifier = Modifier.width(68.dp).height(16.dp).padding(horizontal = 4.dp)
                    )
                } else {
                    Box(modifier = Modifier.width(68.dp), contentAlignment = Alignment.Center) {
                        LinearWavyProgressIndicator(
                            progress = { animatedProgress },
                            modifier = Modifier.fillMaxWidth().height(10.dp),
                            color = metricColor,
                            trackColor = metricColor.copy(alpha = 0.15f)
                        )
                    }
                }
                
                Text(
                    text = value,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.widthIn(min = 42.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            } else {
                Text(
                    text = value,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Start,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }
    }

    @Composable
    private fun Sparkline(history: List<Float>, color: Color, modifier: Modifier = Modifier) {
        Canvas(modifier = modifier) {
            if (history.isEmpty()) return@Canvas
            val width = size.width
            val height = size.height
            val step = width / (MAX_HISTORY - 1).coerceAtLeast(1)
            
            // Draw border
            drawRect(
                color = color.copy(alpha = 0.3f),
                topLeft = Offset(0f, 0f),
                size = Size(width, height),
                style = Stroke(width = 1.dp.toPx())
            )
            
            val path = Path().apply {
                history.forEachIndexed { index, value ->
                    val x = index * step
                    val y = height - (value.coerceIn(0f, 1f) * height)
                    if (index == 0) moveTo(x, y) else lineTo(x, y)
                }
            }
            drawPath(path, color, style = Stroke(width = 1.5.dp.toPx()))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun observeFpsRecording() {
        serviceScope.launch {
            FpsSessionRecorder.isRecording.collect { recording ->
                if (recording) {
                    startFpsRecording()
                } else {
                    stopFpsRecording()
                }
            }
        }
    }

    private fun startFpsRecording() {
        val previous = recordingJob
        previous?.cancel()
        recordingJob = serviceScope.launch(Dispatchers.IO) {
            // stop→start: wait until the previous job's ftrace cleanup has finished
            previous?.join()
            GpuFpsCollector.resetFrameCursor()
            try {
                val maxRate = getActiveRefreshRate().coerceAtLeast(60f)
                FpsMonitor.updateDisplayRefreshRate(maxRate)
                val platform = GpuFpsCollector.detectPlatform()
                FpsSessionRecorder.setPlatform(platform.name)
                var consecutiveFtraceFailures = 0
                while (isActive && FpsSessionRecorder.isRecording.value) {
                    interactive.first { it }
                    val currentMaxRate = getActiveRefreshRate().coerceAtLeast(60f)
                    FpsMonitor.updateDisplayRefreshRate(currentMaxRate)
                    val fallbackOnly = consecutiveFtraceFailures >= 2
                    val sample = GpuFpsCollector.sample(windowSec = 2f, fallbackOnly = fallbackOnly)
                    if (sample != null) {
                        val clampedSample = if (sample.fps > currentMaxRate) {
                            val frameMs = if (currentMaxRate > 0f) 1000f / currentMaxRate else 0f
                            sample.copy(fps = currentMaxRate, frameMs = frameMs)
                        } else {
                            sample
                        }
                        val isFtrace = clampedSample.source == FpsSource.ADRENO_INFLIGHT ||
                            clampedSample.source == FpsSource.MALI_DMA_FENCE
                        if (!isFtrace && GpuFpsCollector.probeTracing() && !fallbackOnly) {
                            consecutiveFtraceFailures++
                        } else if (isFtrace) {
                            consecutiveFtraceFailures = 0
                        }
                        FpsSessionRecorder.add(withDeviceMetrics(clampedSample), ignorePkg = packageName)
                    }
                }
            } finally {
                // Synchronous (NonCancellable) and serialized with any other ftrace user
                GpuFpsCollector.shutdown()
            }
        }
    }

    private fun withDeviceMetrics(sample: com.ivarna.mkm.data.model.FpsSample) = uiState.value?.let { h ->
        val watts = kotlin.math.abs(h.power.calibratedPowerW)
        sample.copy(
            gpuFreqMhz = parseFreqMHz(h.gpu.currentFreq),
            gpuLoadPct = h.gpu.loadPercent * 100f,
            cpuTemp = h.cpuTemp,
            batteryTemp = h.batteryTemp,
            powerW = if (h.power.isCharging) watts else -watts
        )
    } ?: sample

    private fun stopFpsRecording() {
        // The job's finally block performs the ftrace cleanup; startFpsRecording joins it
        recordingJob?.cancel()
    }

    override fun onDestroy() {
        recordingJob?.cancel()
        GpuFpsCollector.shutdownAsync()
        FpsSessionRecorder.stop()
        FpsMonitor.stopOverlayFps()
        try { unregisterReceiver(screenReceiver) } catch (_: IllegalArgumentException) { }
        if (::lifecycleOwner.isInitialized) {
            (lifecycleOwner.lifecycle as LifecycleRegistry).currentState = Lifecycle.State.DESTROYED
        }
        if (::savedStateRegistryOwner.isInitialized) {
            (savedStateRegistryOwner.lifecycle as LifecycleRegistry).currentState = Lifecycle.State.DESTROYED
        }
        serviceScope.cancel()
        if (::viewModelStore.isInitialized) {
            viewModelStore.clear()
        }
        if (overlayAdded) {
            try { windowManager.removeView(composeView) } catch (e: IllegalArgumentException) {
                Log.w("OverlayService", "Overlay view already detached", e)
            }
            overlayAdded = false
        }
        isRunning = false
        super.onDestroy()
    }

    private fun getActiveRefreshRate(): Float {
        try {
            val displayManager = getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
            val defaultDisplay = displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY)
            val activeRate = defaultDisplay?.mode?.refreshRate
            if (activeRate != null && activeRate > 0f) {
                return activeRate
            }
        } catch (e: Exception) {
            Log.w("OverlayService", "Failed to query active refresh rate", e)
        }
        return 60f
    }
}

private fun parseFreqMHz(s: String): Long {
    val parts = s.trim().split(" ", limit = 2)
    val num = parts.getOrNull(0)?.toDoubleOrNull() ?: return 0L
    val unit = parts.getOrNull(1)?.lowercase() ?: ""
    return when {
        unit.startsWith("ghz") -> (num * 1000.0).toLong()
        unit.startsWith("mhz") -> num.toLong()
        unit.startsWith("khz") -> (num / 1000.0).toLong()
        else -> num.toLong()
    }
}

private fun formatFreqCompact(mhz: Long): String {
    return when {
        mhz <= 0L -> "0"
        mhz >= 1000L -> String.format("%.2fG", mhz / 1000.0)
        else -> "${mhz}M"
    }
}

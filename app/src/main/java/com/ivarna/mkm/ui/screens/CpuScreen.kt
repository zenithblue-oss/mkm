package com.ivarna.mkm.ui.screens
import androidx.compose.ui.res.stringResource

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.ivarna.mkm.ui.components.PullToRefreshWrapper
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ivarna.mkm.data.model.CpuCluster
import com.ivarna.mkm.data.model.CpuCore
import com.ivarna.mkm.data.provider.CoreOnlineState
import com.ivarna.mkm.data.provider.GovernorTunable
import com.ivarna.mkm.data.provider.ThrottleInfo
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.res.stringArrayResource
import com.ivarna.mkm.ui.components.*
import com.ivarna.mkm.ui.components.ThermalCard
import com.ivarna.mkm.ui.viewmodel.CpuViewModel
import com.ivarna.mkm.utils.ShellUtils
import com.ivarna.mkm.ui.components.BootToggleCard
import com.ivarna.mkm.service.GameBoostRegistry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CpuScreen(viewModel: CpuViewModel = viewModel(), onOpenDrawer: () -> Unit = {}) {
    val cpuStatus by viewModel.cpuStatus.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val bootEnabled by viewModel.bootEnabled.collectAsState()
    val gameBoostOwned by GameBoostRegistry.state.collectAsState()
    val tuningOwned = gameBoostOwned !is com.ivarna.mkm.data.model.GameBoostState.Off
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val usageEstimated by viewModel.usageEstimated.collectAsState()
    val throttleInfo by viewModel.throttleInfo.collectAsState()
    val coreStates by viewModel.coreStates.collectAsState()
    val thermalReport by viewModel.thermalReport.collectAsState()
    val thermalRestored by viewModel.thermalRestored.collectAsState()
    var tunablesPolicy by remember { mutableStateOf<Int?>(null) }

    // Poll only while this screen is composed; the VM outlives it through nav saveState.
    DisposableEffect(viewModel) {
        viewModel.startMonitoring()
        onDispose { viewModel.stopMonitoring() }
    }

    var selectedPolicyForGovernor by remember { mutableStateOf<Int?>(null) }
    var selectedPolicyForMaxFreq by remember { mutableStateOf<Int?>(null) }
    var selectedPolicyForMinFreq by remember { mutableStateOf<Int?>(null) }
    var applyingControl by remember { mutableStateOf<String?>(null) }
    var sheetError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            MediumTopAppBar(
                navigationIcon = {
                     IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Filled.Menu, contentDescription = stringResource(com.ivarna.mkm.R.string.menu))
                    }
                },
                title = {
                    Column {
                        Text(
                            cpuStatus.cpuName,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            stringResource(com.ivarna.mkm.R.string.cpu_management),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { padding ->
        PullToRefreshWrapper(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.padding(padding)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                contentPadding = PaddingValues(
                    top = 8.dp, // padding consumed by wrapper, just need top spacing
                    bottom = 32.dp,
                    start = 16.dp,
                    end = 16.dp
                )
            ) {
            item {
                HeroUsageCard(
                    title = stringResource(com.ivarna.mkm.R.string.overall_utilization),
                    usage = cpuStatus.overallUsage,
                    mainValue = "${(cpuStatus.overallUsage * 100).toInt()}%" +
                        if (usageEstimated) " " + stringResource(com.ivarna.mkm.R.string.cpu_usage_estimate_suffix) else "",
                    subValue = stringResource(com.ivarna.mkm.R.string.processors_active_format, cpuStatus.totalCores)
                )
            }

            // Thermal Status
            item {
                val thermalStatus by viewModel.thermalStatus.collectAsState()
                // Show card if we have data OR if we are refreshing (to show loading state)
                if (thermalStatus.zones.isNotEmpty() || isRefreshing) {
                    ThermalCard(
                        status = thermalStatus,
                        isLoading = isRefreshing && thermalStatus.zones.isEmpty(), // Only show specific loading if empty
                        onSetLimit = { limit -> viewModel.setThermalLimit(limit) },
                        onDisableThrottling = { viewModel.disableThrottling() },
                        controlsEnabled = !tuningOwned,
                        onRestoreThermal = { viewModel.restoreThermal() },
                        report = thermalReport,
                        restored = thermalRestored
                    )
                }
            }

            item {
                ThrottlingCard(clusters = cpuStatus.clusters, info = throttleInfo)
            }


            item {
                Spacer(modifier = Modifier.height(16.dp))
                BootToggleCard(
                    enabled = bootEnabled,
                    onToggle = { viewModel.toggleBootEnabled(it) },
                    interactive = !tuningOwned,
                    subtitle = if (tuningOwned) stringResource(com.ivarna.mkm.R.string.game_boost_managed_reason) else stringResource(com.ivarna.mkm.R.string.apply_on_boot_desc)
                )
            }

            item {
                SectionHeader(stringResource(com.ivarna.mkm.R.string.cpu_clusters))
            }

            items(cpuStatus.clusters) { cluster ->
                CpuClusterCard(
                    cluster = cluster,
                    controlsEnabled = !tuningOwned,
                    onGovernorClick = { selectedPolicyForGovernor = cluster.id },
                    onMaxFreqClick = { selectedPolicyForMaxFreq = cluster.id },
                    onMinFreqClick = { selectedPolicyForMinFreq = cluster.id },
                    onTunablesClick = { tunablesPolicy = cluster.id }
                )
            }

            item {
                SectionHeader(stringResource(com.ivarna.mkm.R.string.core_status_monitoring))
            }

            item {
                CoreStatusGrid(
                    cores = cpuStatus.clusters.flatMap { it.cores }
                )
            }

            if (coreStates.isNotEmpty()) item {
                CoreOnlineCard(
                    states = coreStates,
                    controlsEnabled = !tuningOwned,
                    onToggle = { id, online ->
                        viewModel.setCoreOnline(id, online) { result ->
                            if (result is com.ivarna.mkm.data.model.ApplyResult.Failed) {
                                android.widget.Toast.makeText(context, result.message(), android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                )
            }
            }
        }

        tunablesPolicy?.let { policyId ->
            GovernorTunablesDialog(
                policyId = policyId,
                viewModel = viewModel,
                controlsEnabled = !tuningOwned,
                onDismiss = { tunablesPolicy = null }
            )
        }

        // Selection sheets
        selectedPolicyForGovernor?.let { policyId ->
            cpuStatus.clusters.firstOrNull { it.id == policyId }?.let { cluster ->
            SelectionBottomSheet(
                title = stringResource(com.ivarna.mkm.R.string.select_governor),
                items = cluster.availableGovernors,
                selectedItem = cluster.governor,
                onDismiss = { sheetError = null; selectedPolicyForGovernor = null },
                onItemSelected = {
                    sheetError = null
                    applyingControl = "cpu-${cluster.id}-governor"
                    viewModel.setGovernor(cluster.id, it) { result ->
                        applyingControl = null
                        if (result is com.ivarna.mkm.data.model.ApplyResult.Failed) sheetError = result.message()
                        else {
                            selectedPolicyForGovernor = null
                            if (result is com.ivarna.mkm.data.model.ApplyResult.Adjusted) android.widget.Toast.makeText(context, result.message(), android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                isApplying = applyingControl == "cpu-${cluster.id}-governor",
                errorMessage = sheetError
            )
            }
        }

        selectedPolicyForMaxFreq?.let { policyId ->
            cpuStatus.clusters.firstOrNull { it.id == policyId }?.let { cluster ->
            SelectionBottomSheet(
                title = stringResource(com.ivarna.mkm.R.string.select_max_frequency),
                items = cluster.availableFrequencies,
                selectedItem = cluster.rawMaxFreq,
                onDismiss = { sheetError = null; selectedPolicyForMaxFreq = null },
                onItemSelected = {
                    sheetError = null
                    applyingControl = "cpu-${cluster.id}-max"
                    viewModel.setFrequency(cluster.id, it, true) { result ->
                        applyingControl = null
                        if (result is com.ivarna.mkm.data.model.ApplyResult.Failed) sheetError = result.message()
                        else {
                            selectedPolicyForMaxFreq = null
                            if (result is com.ivarna.mkm.data.model.ApplyResult.Adjusted) android.widget.Toast.makeText(context, result.message(), android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                itemLabel = { ShellUtils.formatFreq(it.toLongOrNull() ?: 0L) },
                isApplying = applyingControl == "cpu-${cluster.id}-max",
                errorMessage = sheetError
            )
            }
        }

        selectedPolicyForMinFreq?.let { policyId ->
            cpuStatus.clusters.firstOrNull { it.id == policyId }?.let { cluster ->
            SelectionBottomSheet(
                title = stringResource(com.ivarna.mkm.R.string.select_min_frequency),
                items = cluster.availableFrequencies,
                selectedItem = cluster.rawMinFreq,
                onDismiss = { sheetError = null; selectedPolicyForMinFreq = null },
                onItemSelected = {
                    sheetError = null
                    applyingControl = "cpu-${cluster.id}-min"
                    viewModel.setFrequency(cluster.id, it, false) { result ->
                        applyingControl = null
                        if (result is com.ivarna.mkm.data.model.ApplyResult.Failed) sheetError = result.message()
                        else {
                            selectedPolicyForMinFreq = null
                            if (result is com.ivarna.mkm.data.model.ApplyResult.Adjusted) android.widget.Toast.makeText(context, result.message(), android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                itemLabel = { ShellUtils.formatFreq(it.toLongOrNull() ?: 0L) },
                isApplying = applyingControl == "cpu-${cluster.id}-min",
                errorMessage = sheetError
            )
            }
        }
    }
}

@Composable
fun CpuClusterCard(
    cluster: CpuCluster,
    controlsEnabled: Boolean = true,
    onGovernorClick: () -> Unit,
    onMaxFreqClick: () -> Unit,
    onMinFreqClick: () -> Unit,
    onTunablesClick: () -> Unit = {}
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(com.ivarna.mkm.R.string.cluster_id_format, cluster.id),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                ) {
                    val policyCpus = cluster.affectedCpus.ifEmpty { cluster.relatedCpus }
                    Text(
                        text = if (policyCpus.isNotEmpty()) {
                            stringResource(com.ivarna.mkm.R.string.policy_cpus_format, policyCpus.joinToString(","))
                        } else {
                            stringResource(com.ivarna.mkm.R.string.policy_id_format, cluster.id)
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingRow(
                    label = stringResource(com.ivarna.mkm.R.string.governor),
                    value = cluster.governor,
                    onClick = onGovernorClick,
                    enabled = controlsEnabled && cluster.governorWritable && cluster.availableGovernors.isNotEmpty(),
                    disabledReason = if (!controlsEnabled) "Managed by Game Boost. Disable Game Boost to edit." else cluster.governorReason
                )
                SettingRow(
                    label = stringResource(com.ivarna.mkm.R.string.max_frequency),
                    value = cluster.maxFreq,
                    onClick = onMaxFreqClick,
                    enabled = controlsEnabled && cluster.maxWritable && cluster.availableFrequencies.isNotEmpty(),
                    disabledReason = if (!controlsEnabled) "Managed by Game Boost. Disable Game Boost to edit." else cluster.maxReason
                )
                SettingRow(
                    label = stringResource(com.ivarna.mkm.R.string.min_frequency),
                    value = cluster.minFreq,
                    onClick = onMinFreqClick,
                    enabled = controlsEnabled && cluster.minWritable && cluster.availableFrequencies.isNotEmpty(),
                    disabledReason = if (!controlsEnabled) "Managed by Game Boost. Disable Game Boost to edit." else cluster.minReason
                )
                SettingRow(
                    label = stringResource(com.ivarna.mkm.R.string.governor_tunables),
                    value = cluster.governor,
                    onClick = onTunablesClick,
                    enabled = cluster.governor.isNotBlank() && cluster.governor != "unknown"
                )
                InfoRow(
                    label = stringResource(com.ivarna.mkm.R.string.current_clock_speed),
                    value = cluster.currentFreq
                )
                if (cluster.availableGovernors.isEmpty() || cluster.availableFrequencies.isEmpty()) {
                    Text(
                        text = stringResource(com.ivarna.mkm.R.string.tuning_unavailable_kernel),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** Per-cluster effective max vs hardware max, cooling devices and Android thermal status/headroom. */
@Composable
fun ThrottlingCard(clusters: List<CpuCluster>, info: ThrottleInfo) {
    val statusNames = stringArrayResource(com.ivarna.mkm.R.array.android_thermal_status_names)
    val caps = clusters.mapNotNull { c ->
        val cap = c.rawMaxFreq.toLongOrNull()?.takeIf { it > 0 }
        val hw = c.hwMaxFreq.toLongOrNull()?.takeIf { it > 0 }
        if (cap == null || hw == null) null else Triple(c.id, cap, hw)
    }
    val active = info.coolingDevices.filter { it.cur > 0 }
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(com.ivarna.mkm.R.string.cpu_throttling), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            caps.forEach { (id, cap, hw) ->
                Text(
                    stringResource(com.ivarna.mkm.R.string.cpu_cluster_cap_format, id, ShellUtils.formatFreq(cap), ShellUtils.formatFreq(hw), (cap * 100 / hw).toInt()),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (cap < hw) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (info.coolingDevices.isNotEmpty()) {
                Text(
                    stringResource(com.ivarna.mkm.R.string.cooling_devices_active_format, active.size, info.coolingDevices.size),
                    style = MaterialTheme.typography.bodySmall
                )
                active.take(8).forEach {
                    Text("${it.type}: ${it.cur}/${it.max}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (info.androidStatus >= 0) {
                Text(
                    stringResource(com.ivarna.mkm.R.string.android_thermal_status_format, statusNames.getOrElse(info.androidStatus) { info.androidStatus.toString() }),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (info.androidStatus >= 3) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            info.headroom?.let {
                Text(stringResource(com.ivarna.mkm.R.string.thermal_headroom_format, it), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun CoreOnlineCard(states: List<CoreOnlineState>, controlsEnabled: Boolean, onToggle: (Int, Boolean) -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(com.ivarna.mkm.R.string.cpu_core_control), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (states.none { it.writable }) {
                Text(
                    stringResource(com.ivarna.mkm.R.string.cpu_hotplug_unsupported),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            states.forEach { s ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(com.ivarna.mkm.R.string.cpu_core_label_format, s.id))
                    Switch(
                        checked = s.online,
                        onCheckedChange = { onToggle(s.id, it) },
                        // cpu0 is never writable; the last online core of a cluster cannot be turned off.
                        enabled = controlsEnabled && s.writable && (!s.online || s.canOffline)
                    )
                }
            }
        }
    }
}

@Composable
fun GovernorTunablesDialog(policyId: Int, viewModel: CpuViewModel, controlsEnabled: Boolean, onDismiss: () -> Unit) {
    var tunables by remember(policyId) { mutableStateOf<List<GovernorTunable>?>(null) }
    var message by remember(policyId) { mutableStateOf<String?>(null) }
    LaunchedEffect(policyId) { viewModel.loadGovernorTunables(policyId) { tunables = it } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(com.ivarna.mkm.R.string.governor_tunables)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val list = tunables
                when {
                    list == null -> CircularProgressIndicator()
                    list.isEmpty() -> Text(stringResource(com.ivarna.mkm.R.string.no_governor_tunables))
                    else -> list.forEach { t ->
                        TunableRow(t, controlsEnabled) { value ->
                            viewModel.setGovernorTunable(policyId, t.name, value) { result ->
                                message = result.message()
                                viewModel.loadGovernorTunables(policyId) { tunables = it }
                            }
                        }
                    }
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(com.ivarna.mkm.R.string.close)) }
        }
    )
}

@Composable
private fun TunableRow(tunable: GovernorTunable, enabled: Boolean, onApply: (String) -> Unit) {
    if (!tunable.editable) {
        InfoRow(label = tunable.name, value = tunable.value)
    } else {
        var text by remember(tunable.name, tunable.value) { mutableStateOf(tunable.value) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { v -> text = v.filter(Char::isDigit).take(10) },
                label = { Text(tunable.name) },
                singleLine = true,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { onApply(text) }, enabled = enabled && text.isNotEmpty() && text != tunable.value) {
                Text(stringResource(com.ivarna.mkm.R.string.apply))
            }
        }
    }
}

@Composable
fun CoreStatusGrid(cores: List<CpuCore>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        cores.chunked(2).forEach { rowCores ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowCores.forEach { core ->
                    CoreMiniCard(
                        core = core,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (rowCores.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

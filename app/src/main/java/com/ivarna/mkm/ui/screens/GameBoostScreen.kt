package com.ivarna.mkm.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.semantics.Role
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ivarna.mkm.R
import com.ivarna.mkm.data.model.GameBoostComponent
import com.ivarna.mkm.data.model.GameBoostState
import com.ivarna.mkm.ui.components.BootToggleCard
import com.ivarna.mkm.ui.viewmodel.GameBoostViewModel
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.ExperimentalMaterial3Api

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameBoostScreen(viewModel: GameBoostViewModel = viewModel(), onOpenDrawer: () -> Unit = {}) {
    val state by viewModel.state.collectAsState()
    val capabilities by viewModel.capabilities.collectAsState()
    val message by viewModel.message.collectAsState()
    val bootEnabled by viewModel.bootEnabled.collectAsState()
    var showDisclosure by remember { mutableStateOf(false) }
    var showAutoDisclosure by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    val autoEnabled by viewModel.autoEnabled.collectAsState()
    val autoPackages by viewModel.autoPackages.collectAsState()
    val apps by viewModel.apps.collectAsState()
    val owns = state !is GameBoostState.Off
    val transitioning = state is GameBoostState.Enabling || state is GameBoostState.Disabling

    Scaffold(
        topBar = {
            MediumTopAppBar(
                navigationIcon = { IconButton(onClick = onOpenDrawer) { Icon(Icons.Default.Menu, stringResource(R.string.menu)) } },
                title = { Text(stringResource(R.string.game_boost), fontWeight = FontWeight.Black) }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.game_boost), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.game_boost_desc), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(12.dp))
                        GameBoostToggleRow(
                            checked = owns,
                            enabled = !transitioning && state !is GameBoostState.RecoveryRequired,
                            onCheckedChange = { enabled ->
                                if (enabled && !viewModel.disclosureAcknowledged) showDisclosure = true
                                else viewModel.toggle(enabled)
                            }
                        )
                        if (transitioning) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                    }
                }
            }
            item {
                BootToggleCard(
                    enabled = bootEnabled,
                    onToggle = { viewModel.toggleBootEnabled(it) }
                )
            }
            item {
                AutoBoostCard(
                    enabled = autoEnabled,
                    selectedCount = autoPackages.size,
                    onToggle = { enabled ->
                        if (enabled && !viewModel.disclosureAcknowledged) showAutoDisclosure = true
                        else viewModel.setAutoEnabled(enabled)
                    },
                    onChoose = { viewModel.loadApps(); showPicker = true }
                )
            }
            item {
                GameBoostStatusCard(state, capabilities)
            }
            message?.let { msg -> item { Text(msg, color = MaterialTheme.colorScheme.error) } }
            if (state is GameBoostState.RecoveryRequired) {
                item { Button(onClick = viewModel::retryRecovery, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.game_boost_retry_recovery)) } }
            }
        }
    }

    if (showDisclosure) {
        AlertDialog(
            onDismissRequest = { showDisclosure = false },
            title = { Text(stringResource(R.string.game_boost_disclosure_title)) },
            text = { Text(stringResource(R.string.game_boost_disclosure)) },
            confirmButton = { TextButton(onClick = { showDisclosure = false; viewModel.acknowledgeDisclosure(); viewModel.toggle(true) }) { Text(stringResource(R.string.continue_text)) } },
            dismissButton = { TextButton(onClick = { showDisclosure = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    if (showAutoDisclosure) {
        AlertDialog(
            onDismissRequest = { showAutoDisclosure = false },
            title = { Text(stringResource(R.string.game_boost_disclosure_title)) },
            text = { Text(stringResource(R.string.game_boost_disclosure)) },
            confirmButton = { TextButton(onClick = { showAutoDisclosure = false; viewModel.acknowledgeDisclosure(); viewModel.setAutoEnabled(true) }) { Text(stringResource(R.string.continue_text)) } },
            dismissButton = { TextButton(onClick = { showAutoDisclosure = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    if (showPicker) {
        var selection by remember { mutableStateOf(autoPackages) }
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text(stringResource(R.string.game_boost_auto_pick_title)) },
            text = {
                val list = apps
                if (list == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(list, key = { it.packageName }) { app ->
                            val checked = app.packageName in selection
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .toggleable(value = checked, role = Role.Checkbox) {
                                        selection = if (it) selection + app.packageName else selection - app.packageName
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(checked = checked, onCheckedChange = null)
                                Column(Modifier.padding(start = 12.dp)) {
                                    Text(app.label)
                                    Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.setAutoPackages(selection); showPicker = false }) { Text(stringResource(R.string.game_boost_auto_done)) } },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

@Composable
private fun AutoBoostCard(enabled: Boolean, selectedCount: Int, onToggle: (Boolean) -> Unit, onChoose: () -> Unit) {
    ElevatedCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val title = stringResource(R.string.game_boost_auto_title)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = onToggle, modifier = Modifier.semantics { contentDescription = title })
            }
            Text(stringResource(R.string.game_boost_auto_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onChoose, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.game_boost_auto_choose, selectedCount))
            }
        }
    }
}

@Composable
internal fun GameBoostToggleRow(checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val toggleDescription = stringResource(R.string.game_boost_toggle)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.game_boost_toggle), fontWeight = FontWeight.SemiBold)
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.semantics { contentDescription = toggleDescription }
        )
    }
}

@Composable
internal fun GameBoostStatusCard(state: GameBoostState, capabilities: com.ivarna.mkm.data.model.GameBoostCapabilities?) {
    val applied = when (state) {
        is GameBoostState.Active -> state.applied
        is GameBoostState.ThermalLimited -> state.stillApplied
        else -> emptySet()
    }
    val released = (state as? GameBoostState.ThermalLimited)?.released.orEmpty()
    ElevatedCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.game_boost_status), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            listOf(
                GameBoostComponent.CPU_GOVERNOR to R.string.game_boost_cpu_governor,
                GameBoostComponent.CPU_MAX_LOCK to R.string.game_boost_cpu_max,
                GameBoostComponent.GPU_GOVERNOR to R.string.game_boost_gpu_governor,
                GameBoostComponent.GPU_MAX_LOCK to R.string.game_boost_gpu_max,
                GameBoostComponent.STORAGE_GOVERNOR to R.string.game_boost_storage_governor,
                GameBoostComponent.STORAGE_MAX_LOCK to R.string.game_boost_storage_max
            ).forEach { (component, label) ->
                val capability = capabilities?.components?.get(component)
                val value = when {
                    component in released -> stringResource(R.string.game_boost_thermal_released)
                    component in applied -> stringResource(R.string.game_boost_applied)
                    capability?.supported == false -> stringResource(R.string.game_boost_unsupported)
                    else -> stringResource(R.string.game_boost_not_applied)
                }
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(label)); Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val detail = capability?.reason ?: capability?.target?.let { raw ->
                        // CPU: kHz list; GPU: unit-inferred raw; storage: devfreq Hz; governors pass through.
                        val target = when (component) {
                            GameBoostComponent.CPU_MAX_LOCK -> raw.split(",").joinToString(", ") { khz ->
                                khz.trim().toLongOrNull()?.let(com.ivarna.mkm.utils.ShellUtils::formatFreq) ?: khz
                            }
                            GameBoostComponent.GPU_MAX_LOCK -> raw.toLongOrNull()?.let {
                                com.ivarna.mkm.utils.ShellUtils.formatFreq(com.ivarna.mkm.data.provider.GpuProvider.rawFrequencyToKhz(it))
                            } ?: raw
                            GameBoostComponent.STORAGE_MAX_LOCK -> com.ivarna.mkm.utils.ShellUtils.formatHz(raw)
                            else -> raw
                        }
                        capability.source?.takeIf { it.isNotBlank() }?.let { source -> "Target $target ($source)" }
                            ?: "Target $target"
                    }
                    if (!detail.isNullOrBlank()) {
                        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            when (state) {
                is GameBoostState.Enabling -> Text(stringResource(R.string.game_boost_enabling, state.step))
                is GameBoostState.ThermalLimited -> Text(stringResource(R.string.game_boost_thermal_limited))
                is GameBoostState.RecoveryRequired -> Text(stringResource(R.string.game_boost_recovery_required), color = MaterialTheme.colorScheme.error)
                else -> Unit
            }
        }
    }
}

package com.ivarna.mkm.ui.screens
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
// import androidx.compose.material.icons.filled.Storage // Might not exist
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ivarna.mkm.ui.components.InfoRow
import com.ivarna.mkm.ui.components.PullToRefreshWrapper
import com.ivarna.mkm.ui.components.StatCard
import com.ivarna.mkm.ui.components.HeroUsageCard
import com.ivarna.mkm.ui.components.UfsTuningCard
import com.ivarna.mkm.ui.components.UfsHealthCard
import com.ivarna.mkm.ui.components.IoTuningCard
import androidx.compose.ui.Alignment
import com.ivarna.mkm.ui.viewmodel.StorageViewModel
import com.ivarna.mkm.R
import com.ivarna.mkm.ui.components.BootToggleCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(viewModel: StorageViewModel = viewModel(), onOpenDrawer: () -> Unit = {}) {
    val uiState by viewModel.uiState.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val bootEnabled by viewModel.bootEnabled.collectAsState()
    val isProcessing by viewModel.isProcessing.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val infoMessage by viewModel.infoMessage.collectAsState()
    val health by viewModel.health.collectAsState()
    val blockDevices by viewModel.blockDevices.collectAsState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Box(modifier = Modifier.fillMaxSize()) {
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
                    Text(
                        stringResource(com.ivarna.mkm.R.string.storage_info),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Black
                    )
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { innerPadding ->
        PullToRefreshWrapper(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                (errorMessage ?: infoMessage)?.let { msg ->
                    val isError = errorMessage != null
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = msg,
                                color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { viewModel.clearError() }) {
                                Text(stringResource(com.ivarna.mkm.R.string.dismiss))
                            }
                        }
                    }
                }

                uiState?.let { state ->
                    BootToggleCard(
                        enabled = bootEnabled,
                        onToggle = { viewModel.toggleBootEnabled(it) }
                    )

                    val internalStorage = state.partitions.find { it.mountPoint == "/data" }
                    val systemStorage = state.partitions.find { it.mountPoint == "/system" }

                    if (internalStorage != null) {
                        HeroUsageCard(
                            title = stringResource(com.ivarna.mkm.R.string.internal_storage),
                            usage = internalStorage.usagePercent / 100f,
                            mainValue = "${internalStorage.usagePercent.toInt()}%",
                            subValue = stringResource(com.ivarna.mkm.R.string.storage_used_of_format, internalStorage.used, internalStorage.total)
                        )
                    }

                    if (systemStorage != null) {
                         StatCard(
                            title = stringResource(com.ivarna.mkm.R.string.system_partition),
                            value = systemStorage.used,
                            subValue = stringResource(com.ivarna.mkm.R.string.storage_of_used_format, systemStorage.total),
                            progress = systemStorage.usagePercent / 100f,
                            icon = Icons.Default.SdStorage,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    UfsTuningCard(
                        ufs = state.ufsStatus,
                        onGovernorSelected = { path, gov -> viewModel.setUfsGovernor(path, gov) },
                        onMinFreqSelected = { path, freq -> viewModel.setUfsMinFreq(path, freq) },
                        onMaxFreqSelected = { path, freq -> viewModel.setUfsMaxFreq(path, freq) }
                    )

                    UfsHealthCard(health)

                    IoTuningCard(
                        devices = blockDevices,
                        onScheduler = { dev, s -> viewModel.setScheduler(dev, s) },
                        onReadAhead = { dev, kb -> viewModel.setReadAhead(dev, kb) },
                        onFstrim = { viewModel.runFstrim() }
                    )

                    Card(
                        shape = RoundedCornerShape(32.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(24.dp)) {
                            Text(
                                text = stringResource(com.ivarna.mkm.R.string.storage_info),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            InfoRow(label = stringResource(com.ivarna.mkm.R.string.type), value = state.type)
                            InfoRow(label = stringResource(com.ivarna.mkm.R.string.partition_count), value = state.partitions.size.toString())
                            
                            internalStorage?.let {
                                InfoRow(label = stringResource(com.ivarna.mkm.R.string.internal_total), value = it.total)
                                InfoRow(label = stringResource(com.ivarna.mkm.R.string.internal_free), value = it.free)
                                InfoRow(label = stringResource(com.ivarna.mkm.R.string.block_size), value = stringResource(com.ivarna.mkm.R.string.block_size_bytes_format, it.blockSize.toString()))
                            }
                        }
                    }
                }
            }
        }
    }
        if (isProcessing) {
            LoadingOverlay(message = stringResource(com.ivarna.mkm.R.string.processing_ellipsis))
        }
    }
}
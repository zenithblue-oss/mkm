package com.ivarna.mkm.ui.screens
import androidx.compose.ui.res.stringResource

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ivarna.mkm.shell.ShizukuManager
import com.ivarna.mkm.shell.SHIZUKU_REQUEST_CODE
import rikka.shizuku.Shizuku

/**
 * Permission status enum for UI state
 */
enum class PermissionStatus {
    Checking,
    NotInstalled,
    Hidden,          // Not visible in PackageManager but binder is alive
    NotRunning,      // Installed but service not running
    NotGranted,
    Granted,
    Denied
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionRequestScreen(
    onNavigateBack: () -> Unit,
    onPermissionGranted: () -> Unit
) {
    val context = LocalContext.current
    val permissionStatus = remember { mutableStateOf<PermissionStatus>(PermissionStatus.Checking) }
    
    val permissionListener = remember {
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_REQUEST_CODE) {
                permissionStatus.value = if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    PermissionStatus.Granted
                } else {
                    PermissionStatus.Denied
                }
            }
        }
    }
    
    LaunchedEffect(Unit) {
        Shizuku.addRequestPermissionResultListener(permissionListener)
        
        // Check current status with granular states
        when {
            ShizukuManager.hasPermission() -> {
                permissionStatus.value = PermissionStatus.Granted
                kotlinx.coroutines.delay(500)
                onPermissionGranted()
            }
            ShizukuManager.isHidden() -> {
                // Binder alive but package hidden (Issue #6)
                permissionStatus.value = PermissionStatus.Hidden
            }
            !ShizukuManager.isInstalled() -> {
                permissionStatus.value = PermissionStatus.NotInstalled
            }
            !ShizukuManager.isRunning() -> {
                permissionStatus.value = PermissionStatus.NotRunning
            }
            else -> {
                permissionStatus.value = PermissionStatus.NotGranted
            }
        }
    }
    
    DisposableEffect(Unit) {
        onDispose {
            Shizuku.removeRequestPermissionResultListener(permissionListener)
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(com.ivarna.mkm.R.string.shizuku_permission)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(com.ivarna.mkm.R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when (permissionStatus.value) {
                PermissionStatus.NotInstalled -> {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        stringResource(com.ivarna.mkm.R.string.shizuku_not_installed),
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(com.ivarna.mkm.R.string.shizuku_not_installed_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                data = Uri.parse("https://github.com/RikkaApps/Shizuku/releases")
                            }
                            context.startActivity(intent)
                        }
                    ) {
                        Text(stringResource(com.ivarna.mkm.R.string.download_shizuku))
                    }
                }

                PermissionStatus.Hidden -> {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        stringResource(com.ivarna.mkm.R.string.shizuku_is_hidden),
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(com.ivarna.mkm.R.string.shizuku_is_hidden_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = { ShizukuManager.requestPermission() }
                    ) {
                        Text(stringResource(com.ivarna.mkm.R.string.grant_permission))
                    }
                }
                
                PermissionStatus.NotRunning -> {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        stringResource(com.ivarna.mkm.R.string.shizuku_not_running),
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(com.ivarna.mkm.R.string.shizuku_not_running_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = {
                            // Try to open Shizuku app
                            val intent = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                            if (intent != null) {
                                context.startActivity(intent)
                            } else {
                                // Fallback to Play Store
                                val playStoreIntent = Intent(Intent.ACTION_VIEW).apply {
                                    data = Uri.parse("market://details?id=moe.shizuku.privileged.api")
                                }
                                context.startActivity(playStoreIntent)
                            }
                        }
                    ) {
                        Text(stringResource(com.ivarna.mkm.R.string.open_shizuku))
                    }
                }
                
                PermissionStatus.NotGranted -> {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        stringResource(com.ivarna.mkm.R.string.permission_required),
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(com.ivarna.mkm.R.string.permission_required_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = { ShizukuManager.requestPermission() }
                    ) {
                        Text(stringResource(com.ivarna.mkm.R.string.grant_permission))
                    }
                }
                
                PermissionStatus.Granted -> {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        stringResource(com.ivarna.mkm.R.string.permission_granted),
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(com.ivarna.mkm.R.string.setting_up_ellipsis),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                    CircularProgressIndicator(modifier = Modifier.padding(top = 24.dp))
                }
                
                PermissionStatus.Denied -> {
                    Icon(
                        imageVector = Icons.Default.Error,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        stringResource(com.ivarna.mkm.R.string.permission_denied),
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(com.ivarna.mkm.R.string.permission_denied_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onNavigateBack) {
                            Text(stringResource(com.ivarna.mkm.R.string.cancel))
                        }
                        Button(onClick = { ShizukuManager.requestPermission() }) {
                            Text(stringResource(com.ivarna.mkm.R.string.try_again))
                        }
                    }
                }
                
                PermissionStatus.Checking -> {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(stringResource(com.ivarna.mkm.R.string.checking_status))
                }
            }
        }
    }
}

/**
 * Card component for showing access method status in settings
 */
@Composable
fun AccessMethodCard(
    onRequestShizukuPermission: () -> Unit
) {
    // Shell.getShell() can block while the root shell starts: resolve it off the main thread.
    // null = still checking (no LOCAL/"None" flash before the probe finishes).
    val access = rememberAccessSnapshot()
    val accessMethod = access?.method
    val isRoot = accessMethod == com.ivarna.mkm.shell.ShellManager.AccessMethod.ROOT
    val requestShizuku: () -> Unit = {
        ShizukuManager.requestPermission()
        onRequestShizukuPermission()
    }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                stringResource(com.ivarna.mkm.R.string.access_method),
                style = MaterialTheme.typography.titleLarge
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Current method
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(com.ivarna.mkm.R.string.current_method),
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    when (accessMethod) {
                        null -> stringResource(com.ivarna.mkm.R.string.checking_status)
                        com.ivarna.mkm.shell.ShellManager.AccessMethod.ROOT -> stringResource(com.ivarna.mkm.R.string.root)
                        com.ivarna.mkm.shell.ShellManager.AccessMethod.LOCAL ->
                            if (access?.shizukuGranted == true) stringResource(com.ivarna.mkm.R.string.shizuku)
                            else stringResource(com.ivarna.mkm.R.string.none)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = when {
                        accessMethod == null -> MaterialTheme.colorScheme.onSurfaceVariant
                        isRoot || access?.shizukuGranted == true -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.error
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Shizuku status
            val shizukuHidden = access?.shizukuHidden == true
            AccessMethodItem(
                icon = Icons.Default.Security,
                title = if (shizukuHidden) stringResource(com.ivarna.mkm.R.string.shizuku_hidden) else stringResource(com.ivarna.mkm.R.string.shizuku),
                status = when {
                    access == null -> stringResource(com.ivarna.mkm.R.string.checking_status)
                    access.shizukuGranted -> stringResource(com.ivarna.mkm.R.string.active)
                    shizukuHidden -> stringResource(com.ivarna.mkm.R.string.shizuku_hidden_grant)
                    !access.shizukuInstalled -> stringResource(com.ivarna.mkm.R.string.not_installed)
                    !access.shizukuRunning -> stringResource(com.ivarna.mkm.R.string.not_running)
                    else -> stringResource(com.ivarna.mkm.R.string.grant_permission)
                },
                statusColor = when {
                    access == null -> MaterialTheme.colorScheme.onSurfaceVariant
                    access.shizukuGranted -> MaterialTheme.colorScheme.primary
                    !access.shizukuInstalled && !shizukuHidden -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.tertiary
                },
                onClick = when {
                    access == null || access.shizukuGranted -> null
                    shizukuHidden -> requestShizuku
                    !access.shizukuRunning -> null
                    else -> requestShizuku
                }
            )
            // Granted but commands fail: show the backend error so users can report it.
            access?.shizukuError?.let { error ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(com.ivarna.mkm.R.string.shizuku_backend_error, error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            
            // Root status
            AccessMethodItem(
                icon = Icons.Default.Security,
                title = stringResource(com.ivarna.mkm.R.string.root),
                status = if (isRoot) stringResource(com.ivarna.mkm.R.string.active) else stringResource(com.ivarna.mkm.R.string.not_available),
                statusColor = if (isRoot) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
                onClick = null
            )
        }
    }
}

private data class AccessSnapshot(
    val method: com.ivarna.mkm.shell.ShellManager.AccessMethod,
    val shizukuInstalled: Boolean,
    val shizukuRunning: Boolean,
    val shizukuHidden: Boolean,
    val shizukuGranted: Boolean,
    val shizukuError: String?
)

/** Probes access off the main thread; re-probes on resume and on Shizuku binder received/dead. */
@Composable
private fun rememberAccessSnapshot(): AccessSnapshot? {
    var refreshKey by remember { mutableStateOf(0) }
    var snapshot by remember { mutableStateOf<AccessSnapshot?>(null) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        refreshKey++
        onPauseOrDispose { }
    }
    DisposableEffect(Unit) {
        val received = Shizuku.OnBinderReceivedListener { refreshKey++ }
        val dead = Shizuku.OnBinderDeadListener { refreshKey++ }
        val result = Shizuku.OnRequestPermissionResultListener { _, _ -> refreshKey++ }
        ShizukuManager.addBinderReceivedListener(received)
        ShizukuManager.addBinderDeadListener(dead)
        Shizuku.addRequestPermissionResultListener(result)
        onDispose {
            ShizukuManager.removeBinderReceivedListener(received)
            ShizukuManager.removeBinderDeadListener(dead)
            Shizuku.removeRequestPermissionResultListener(result)
        }
    }
    LaunchedEffect(refreshKey) {
        snapshot = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val granted = ShizukuManager.hasPermission()
            val error = if (granted) {
                val probe = com.ivarna.mkm.shell.ShellManager.probeShizuku()
                if (probe.isSuccess) null
                else probe.stderr.ifBlank { com.ivarna.mkm.shell.ShellManager.lastShizukuError ?: "exit ${probe.exitCode}" }
            } else null
            AccessSnapshot(
                method = com.ivarna.mkm.shell.ShellManager.getAvailableMethod(),
                shizukuInstalled = ShizukuManager.isInstalled(),
                shizukuRunning = ShizukuManager.isRunning(),
                shizukuHidden = ShizukuManager.isHidden(),
                shizukuGranted = granted,
                shizukuError = error
            )
        }
    }
    return snapshot
}

@Composable
fun AccessMethodItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    status: String,
    statusColor: androidx.compose.ui.graphics.Color,
    onClick: (() -> Unit)?
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        onClick = onClick ?: {}
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = statusColor
                )
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            
            Text(
                status,
                style = MaterialTheme.typography.bodyMedium,
                color = statusColor
            )
        }
    }
}
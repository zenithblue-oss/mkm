package com.ivarna.mkm.ui.components
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ivarna.mkm.data.provider.ManagedSwapFile
import com.ivarna.mkm.data.provider.ZramStatus
import com.ivarna.mkm.shell.ShellScripts
import com.ivarna.mkm.utils.FormatUtils

@Composable
fun SwapConfigDialog(
    initialSize: Int = 1024,
    initialPath: String = ShellScripts.DEFAULT_SWAP_PATH,
    onDismiss: () -> Unit,
    onConfirm: (path: String, size: Int) -> Unit
) {
    var sizeText by remember { mutableStateOf(initialSize.toString()) }
    var pathText by remember { mutableStateOf(initialPath) }
    // Upper bound (50% of free /data) is enforced in the ViewModel, which knows free space
    val size = sizeText.trim().toIntOrNull()?.takeIf { it >= ShellScripts.MIN_SWAP_MB }
    val pathOk = ShellScripts.isValidSwapPath(pathText.trim())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(com.ivarna.mkm.R.string.configure_swap)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = sizeText,
                    onValueChange = { sizeText = it.filter(Char::isDigit).take(7) },
                    label = { Text(stringResource(com.ivarna.mkm.R.string.size_mb)) },
                    isError = size == null,
                    supportingText = if (size == null) { { Text(stringResource(com.ivarna.mkm.R.string.mem_swap_invalid_size)) } } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = pathText,
                    onValueChange = { pathText = it },
                    label = { Text(stringResource(com.ivarna.mkm.R.string.path)) },
                    isError = !pathOk,
                    supportingText = if (!pathOk) { { Text(stringResource(com.ivarna.mkm.R.string.mem_swap_invalid_path)) } } else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                enabled = size != null && pathOk,
                onClick = {
                    if (size != null && pathOk) {
                        onConfirm(pathText.trim(), size)
                        onDismiss()
                    }
                }
            ) {
                Text(stringResource(com.ivarna.mkm.R.string.apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(com.ivarna.mkm.R.string.cancel))
            }
        }
    )
}

@Composable
fun ConfirmDialog(title: String, message: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            Button(
                onClick = { onConfirm(); onDismiss() },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) { Text(stringResource(com.ivarna.mkm.R.string.mem_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(com.ivarna.mkm.R.string.cancel)) } }
    )
}

/** App-managed swap files, active or not, so a disabled file can still be re-enabled or deleted. */
@Composable
fun ManagedSwapFilesCard(
    files: List<ManagedSwapFile>,
    onEnable: (String) -> Unit,
    onDisable: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(com.ivarna.mkm.R.string.mem_swap_files),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            files.forEach { f ->
                Text(f.path, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "${FormatUtils.formatBytes(f.sizeBytes)} · " +
                        stringResource(if (f.active) com.ivarna.mkm.R.string.active else com.ivarna.mkm.R.string.inactive),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (f.active) {
                        OutlinedButton(onClick = { onDisable(f.path) }) { Text(stringResource(com.ivarna.mkm.R.string.disable)) }
                    } else {
                        OutlinedButton(onClick = { onEnable(f.path) }) { Text(stringResource(com.ivarna.mkm.R.string.mem_enable)) }
                    }
                    TextButton(onClick = { onDelete(f.path) }) {
                        Text(stringResource(com.ivarna.mkm.R.string.mem_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZramVmCard(
    zram: ZramStatus,
    onVmApply: (name: String, value: String) -> Unit,
    onZramApply: (algorithm: String, sizeMb: Int?) -> Unit
) {
    var confirmZram by remember { mutableStateOf(false) }
    var algo by remember(zram.currentAlgorithm) { mutableStateOf(zram.currentAlgorithm) }
    var sizeText by remember(zram.disksizeBytes) { mutableStateOf((zram.disksizeBytes / (1024 * 1024)).toString()) }

    if (confirmZram) {
        ConfirmDialog(
            title = stringResource(com.ivarna.mkm.R.string.mem_zram_confirm_title),
            message = stringResource(com.ivarna.mkm.R.string.mem_zram_confirm_msg),
            onConfirm = { onZramApply(algo, sizeText.toIntOrNull()) },
            onDismiss = { confirmZram = false }
        )
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!zram.present) {
                Text(stringResource(com.ivarna.mkm.R.string.mem_zram_not_present), style = MaterialTheme.typography.bodySmall)
            } else {
                Text(
                    stringResource(com.ivarna.mkm.R.string.mem_zram_ratio_format, zram.ratio),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    stringResource(
                        com.ivarna.mkm.R.string.mem_zram_stats_format,
                        FormatUtils.formatBytes(zram.origBytes),
                        FormatUtils.formatBytes(zram.comprBytes),
                        FormatUtils.formatBytes(zram.memUsedBytes),
                        FormatUtils.formatBytes(zram.disksizeBytes)
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        readOnly = true,
                        value = algo,
                        onValueChange = {},
                        label = { Text(stringResource(com.ivarna.mkm.R.string.mem_zram_algorithm)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        zram.algorithms.forEach { a ->
                            DropdownMenuItem(text = { Text(a) }, onClick = { algo = a; expanded = false })
                        }
                    }
                }
                OutlinedTextField(
                    value = sizeText,
                    onValueChange = { sizeText = it.filter(Char::isDigit).take(6) },
                    label = { Text(stringResource(com.ivarna.mkm.R.string.mem_zram_size_mb)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { confirmZram = true },
                    enabled = algo.isNotBlank() && sizeText.toIntOrNull() != null,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(com.ivarna.mkm.R.string.mem_zram_apply)) }
                HorizontalDivider()
            }

            ShellScripts.VM_TUNABLES.forEach { (name, range) ->
                val current = zram.vm[name]
                var text by remember(name, current) { mutableStateOf(current?.toString().orEmpty()) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it.filter(Char::isDigit).take(7) },
                        label = { Text(name) },
                        supportingText = { Text("${range.first}–${range.last}") },
                        isError = text.isNotEmpty() && !ShellScripts.isValidVmValue(name, text.toIntOrNull()),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(
                        onClick = { onVmApply(name, text) },
                        enabled = ShellScripts.isValidVmValue(name, text.toIntOrNull()) && text.toIntOrNull() != current
                    ) { Text(stringResource(com.ivarna.mkm.R.string.apply)) }
                }
            }
            Text(
                stringResource(com.ivarna.mkm.R.string.mem_boot_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

package com.waizusora.wdb

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.waizusora.wdb.adb.WirelessAdbManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException

@Composable
fun AdbToolsPanel(manager: WirelessAdbManager, connected: Boolean, transferDirectoryUri: Uri?) {
    val context = LocalContext.current
    var directoryFiles by remember(transferDirectoryUri) { mutableStateOf<List<DocumentFile>>(emptyList()) }
    LaunchedEffect(transferDirectoryUri) {
        directoryFiles = transferDirectoryUri?.let { uri -> runCatching { DocumentFile.fromTreeUri(context, uri)?.listFiles()?.filter { it.isFile }?.toList().orEmpty() }.getOrDefault(emptyList()) }.orEmpty()
    }
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    var operationJob by remember { mutableStateOf<Job?>(null) }
    var transferBytes by remember { mutableStateOf(0L) }
    var transferTotal by remember { mutableStateOf(0L) }
    var transferStartedAt by remember { mutableStateOf(0L) }
    val portRules by manager.portRules.collectAsState()
    var status by remember { mutableStateOf("Connect to a device to enable these tools.") }
    var uninstallTarget by remember { mutableStateOf("") }
    var showUninstallConfirmation by remember { mutableStateOf(false) }
    var packageName by remember { mutableStateOf("") }
    var pushPath by remember { mutableStateOf("/sdcard/Download/") }
    var pullPath by remember { mutableStateOf("/sdcard/Download/") }
    var localForwardPort by remember { mutableStateOf("27183") }
    var deviceForwardPort by remember { mutableStateOf("27183") }
    var deviceReversePort by remember { mutableStateOf("8080") }
    var hostReversePort by remember { mutableStateOf("8080") }

    fun runOperation(block: suspend () -> String) {
        if (!connected || working) return
        working = true
        transferBytes = 0L
        transferTotal = 0L
        transferStartedAt = android.os.SystemClock.elapsedRealtime()
        operationJob = scope.launch {
            try {
                status = block()
            } catch (cancelled: CancellationException) {
                status = "Operation cancelled. A partial destination file may need cleanup."
                throw cancelled
            } catch (error: Throwable) {
                status = error.message ?: "ADB operation failed."
            } finally {
                working = false
                operationJob = null
            }
        }
    }
    fun updateTransfer(bytes: Long, total: Long = 0L) {
        transferBytes = bytes
        if (total > 0L) transferTotal = total
        val elapsedSeconds = ((android.os.SystemClock.elapsedRealtime() - transferStartedAt).coerceAtLeast(1L)) / 1000.0
        val speed = (bytes / elapsedSeconds).toLong()
        val eta = if (transferTotal > bytes && speed > 0L) (transferTotal - bytes) / speed else -1L
        status = "Transferred ${android.text.format.Formatter.formatShortFileSize(context, bytes)}" +
            (if (transferTotal > 0L) " / ${android.text.format.Formatter.formatShortFileSize(context, transferTotal)}" else "") +
            " · ${android.text.format.Formatter.formatShortFileSize(context, speed)}/s" +
            (if (eta >= 0L) " · ETA ${eta}s" else "")
    }

    val installPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) runOperation { manager.installApks(uris) { status = it } }
    }
    val pushPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) { val total = context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L; runOperation { manager.pushFile(uri, pushPath.trim()) { updateTransfer(it, total) } } }
    }
    val pullPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        if (uri != null) runOperation { manager.pullFile(pullPath.trim(), uri) { updateTransfer(it) } }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Native ADB tools", style = MaterialTheme.typography.titleLarge)
            Text(
                "APK management, ADB Sync file transfer, and TCP port mapping over the authenticated ADB transport.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                enabled = connected && !working,
                onClick = { installPicker.launch(arrayOf("application/vnd.android.package-archive", "application/zip", "application/octet-stream")) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Install APK / Split APKs") }
            OutlinedTextField(
                value = packageName,
                onValueChange = { packageName = it.trim().take(255) },
                label = { Text("Package name to uninstall") },
                placeholder = { Text("com.example.app") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(
                enabled = connected && !working && packageName.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")),
                onClick = {
                    uninstallTarget = packageName
                    showUninstallConfirmation = true
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Uninstall package…") }

            HorizontalDivider()
            Text("File transfer · ADB Sync", style = MaterialTheme.typography.titleMedium)
            Text(transferDirectoryUri?.let { "Local folder: selected in Settings" } ?: "No default local folder selected. Choose one in Settings to browse files and save pulled files there.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = pushPath,
                onValueChange = { pushPath = it.take(1024) },
                label = { Text("Device destination path") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(
                enabled = connected && !working && pushPath.startsWith("/"),
                onClick = { pushPicker.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Choose file to push") }
            if (directoryFiles.isNotEmpty()) {
                Text("Files in selected folder", style = MaterialTheme.typography.labelLarge)
                directoryFiles.take(20).forEach { file ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(file.name ?: "Unnamed file", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(enabled = connected && !working && pushPath.startsWith("/"), onClick = {
                            runOperation { manager.pushFile(file.uri, pushPath.trim().trimEnd('/') + "/" + (file.name ?: "upload.bin")) { updateTransfer(it, file.length()) } }
                        }) { Text("Push") }
                    }
                }
            }
            OutlinedTextField(
                value = pullPath,
                onValueChange = { pullPath = it.take(1024) },
                label = { Text("Device source path") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(
                enabled = connected && !working && pullPath.startsWith("/"),
                onClick = {
                    val tree = transferDirectoryUri
                    if (tree != null) runOperation { manager.pullFileToDirectory(pullPath.trim(), tree) { updateTransfer(it) } }
                    else {
                        val name = pullPath.substringAfterLast('/').ifBlank { "adb-download.bin" }
                        pullPicker.launch(name)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (transferDirectoryUri != null) "Pull into selected folder" else "Pull file to this device") }

            HorizontalDivider()
            Text("Port forwarding", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = localForwardPort,
                onValueChange = { localForwardPort = it.filter(Char::isDigit).take(5) },
                label = { Text("Local TCP port") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = deviceForwardPort,
                onValueChange = { deviceForwardPort = it.filter(Char::isDigit).take(5) },
                label = { Text("Device TCP port") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = connected && !working && validPort(localForwardPort) && validPort(deviceForwardPort),
                    onClick = { runOperation { manager.addForward(localForwardPort.toInt(), deviceForwardPort.toInt()) } },
                    modifier = Modifier.weight(1f)
                ) { Text("Add forward") }
                OutlinedButton(
                    enabled = connected && !working && validPort(localForwardPort),
                    onClick = { runOperation { manager.removeForward(localForwardPort.toInt()) } },
                    modifier = Modifier.weight(1f)
                ) { Text("Remove") }
            }

            HorizontalDivider()
            Text("Reverse forwarding", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = deviceReversePort,
                onValueChange = { deviceReversePort = it.filter(Char::isDigit).take(5) },
                label = { Text("Device TCP port") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = hostReversePort,
                onValueChange = { hostReversePort = it.filter(Char::isDigit).take(5) },
                label = { Text("WDB host TCP port") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = connected && !working && validPort(deviceReversePort) && validPort(hostReversePort),
                    onClick = { runOperation { manager.addReverse(deviceReversePort.toInt(), hostReversePort.toInt()) } },
                    modifier = Modifier.weight(1f)
                ) { Text("Add reverse") }
                OutlinedButton(
                    enabled = connected && !working && validPort(deviceReversePort),
                    onClick = { runOperation { manager.removeReverse(deviceReversePort.toInt()) } },
                    modifier = Modifier.weight(1f)
                ) { Text("Remove") }
            }
            if (working) {
                if (transferTotal > 0L) LinearProgressIndicator(progress = { (transferBytes.toFloat() / transferTotal).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Working…", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                    TextButton(onClick = { operationJob?.cancel() }) { Text("Cancel") }
                }
            }
            if (portRules.isNotEmpty()) {
                HorizontalDivider()
                Text("Active rules created in this WDB session", style = MaterialTheme.typography.labelLarge)
                portRules.forEach { rule ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (rule.reverse) "Reverse · device:${rule.sourcePort} → host:${rule.destinationPort}" else "Forward · localhost:${rule.sourcePort} → device:${rule.destinationPort}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(enabled = connected && !working, onClick = { runOperation { if (rule.reverse) manager.removeReverse(rule.sourcePort) else manager.removeForward(rule.sourcePort) } }) { Text("Remove") }
                    }
                }
            }
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (showUninstallConfirmation) {
        AlertDialog(
            onDismissRequest = { showUninstallConfirmation = false },
            title = { Text("Uninstall package?") },
            text = { Text("This removes $uninstallTarget from the connected Android device. This action cannot be undone from WDB.") },
            confirmButton = {
                TextButton(onClick = {
                    showUninstallConfirmation = false
                    runOperation { manager.uninstallPackage(uninstallTarget) }
                }) { Text("Uninstall") }
            },
            dismissButton = {
                TextButton(onClick = { showUninstallConfirmation = false }) { Text("Cancel") }
            }
        )
    }
}

private fun validPort(value: String): Boolean = value.toIntOrNull()?.let { it in 1..65535 } == true

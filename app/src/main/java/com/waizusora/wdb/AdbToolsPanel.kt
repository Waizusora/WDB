package com.waizusora.wdb

import android.net.Uri
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.waizusora.wdb.adb.WirelessAdbManager
import kotlinx.coroutines.launch

@Composable
fun AdbToolsPanel(manager: WirelessAdbManager, connected: Boolean) {
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
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
        scope.launch {
            status = runCatching { block() }.fold(
                onSuccess = { it },
                onFailure = { it.message ?: "ADB operation failed." }
            )
            working = false
        }
    }

    val installPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) runOperation { manager.installApk(uri) }
    }
    val pushPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) runOperation { manager.pushFile(uri, pushPath.trim()) }
    }
    val pullPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        if (uri != null) runOperation { manager.pullFile(pullPath.trim(), uri) }
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
                onClick = { installPicker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream")) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Install APK") }
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
                    val name = pullPath.substringAfterLast('/').ifBlank { "adb-download.bin" }
                    pullPicker.launch(name)
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Pull file to this device") }

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
            if (working) Text("Working…", style = MaterialTheme.typography.labelMedium)
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

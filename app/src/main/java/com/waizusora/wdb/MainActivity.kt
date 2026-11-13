package com.waizusora.wdb

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.documentfile.provider.DocumentFile
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.waizusora.wdb.session.AdbSessionService
import com.waizusora.wdb.ui.theme.WdbTheme
import com.waizusora.wdb.session.FloatingPairService
import com.waizusora.wdb.session.AdbSessionState
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import io.github.rhythmcache.adb.ShellChunk

private data class AdbCommandOption(
    val id: String,
    val label: String,
    val command: String
)

private val commandCatalog = listOf(
    AdbCommandOption("model", "Device model", "getprop ro.product.model"),
    AdbCommandOption("fingerprint", "Build fingerprint", "getprop ro.build.fingerprint"),
    AdbCommandOption("sdk", "Android SDK level", "getprop ro.build.version.sdk"),
    AdbCommandOption("android", "Android version", "getprop ro.build.version.release"),
    AdbCommandOption("serial", "Device serial", "getprop ro.serialno"),
    AdbCommandOption("packages", "Installed packages", "pm list packages"),
    AdbCommandOption("third-party", "Third-party packages", "pm list packages -3"),
    AdbCommandOption("processes", "Process list", "ps -A"),
    AdbCommandOption("cpu", "CPU information", "cat /proc/cpuinfo"),
    AdbCommandOption("cpu-usage", "CPU usage", "dumpsys cpuinfo"),
    AdbCommandOption("battery", "Battery diagnostics", "dumpsys battery"),
    AdbCommandOption("memory", "Memory summary", "dumpsys meminfo"),
    AdbCommandOption("storage", "Storage usage", "df -h"),
    AdbCommandOption("mounts", "Mounted filesystems", "mount"),
    AdbCommandOption("network", "Network interfaces", "ip addr"),
    AdbCommandOption("routes", "Network routes", "ip route"),
    AdbCommandOption("display-size", "Display size", "wm size"),
    AdbCommandOption("display-density", "Display density", "wm density"),
    AdbCommandOption("services", "System services", "service list"),
    AdbCommandOption("activity-services", "Activity services", "dumpsys activity services"),
    AdbCommandOption("notifications", "Notification state", "dumpsys notification"),
    AdbCommandOption("alarms", "Alarm state", "dumpsys alarm"),
    AdbCommandOption("properties", "System properties", "getprop"),
    AdbCommandOption("uptime", "Device uptime", "uptime")
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WdbApp() }
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WdbApp() {
    val context = LocalContext.current
    val manager = remember(context) { (context.applicationContext as WdbApplication).adbManager }
    val discovery by manager.discoveryState.collectAsState()
    val connectedModel by manager.connectedModel.collectAsState()
    val sessionRunning by AdbSessionState.running.collectAsState()
    val scope = rememberCoroutineScope()
    var statusMessage by remember { mutableStateOf("Scanning for Wireless debugging services…") }
    val startSession: () -> Unit = {
        if (!manager.isConnected()) {
            statusMessage = "ADB transport is no longer connected. Reconnect before starting the session."
        } else {
            ContextCompat.startForegroundService(
                context, Intent(context, AdbSessionService::class.java)
                    .setAction(AdbSessionService.ACTION_START)
            )
            statusMessage = "Foreground ADB session started."
        }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startSession()
        else statusMessage = "Notification permission is required to show the active session notification."
    }

    var transferDirectoryUri by remember(context) {
        mutableStateOf(context.getSharedPreferences("wdb_settings", android.content.Context.MODE_PRIVATE)
            .getString("transfer_directory_uri", null)?.let(Uri::parse))
    }
    val transferDirectoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                context.getSharedPreferences("wdb_settings", android.content.Context.MODE_PRIVATE)
                    .edit().putString("transfer_directory_uri", uri.toString()).apply()
                transferDirectoryUri = uri
                statusMessage = "Transfer directory saved. Push and Pull can use this folder."
            }.onFailure { statusMessage = "Unable to save directory access: ${it.message}" }
        }
    }
    var showSettings by remember { mutableStateOf(false) }
    var showOpenSourceLicenses by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var commandOutput by remember { mutableStateOf("") }
    var customCommand by remember { mutableStateOf("") }
    var selectedCommands by remember { mutableStateOf(setOf("model", "android")) }
    var packageName by remember { mutableStateOf("") }
    var pidOutput by remember { mutableStateOf("") }
    var logcatFilter by remember { mutableStateOf("") }
    var logcatOutput by remember { mutableStateOf("") }
    var streamingLogcat by remember { mutableStateOf(false) }
    var logcatJob by remember { mutableStateOf<Job?>(null) }

    fun createLogcatFile(): DocumentFile {
        val treeUri = transferDirectoryUri ?: throw IllegalStateException("Select a transfer directory in Settings before saving Logcat.")
        val directory = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalStateException("The selected transfer directory is unavailable.")
        if (!directory.isDirectory || !directory.canWrite()) throw IllegalStateException("The selected transfer directory is not writable.")
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.US).format(java.util.Date())
        val name = "WDB_Logcat_$stamp.txt"
        val file = directory.createFile("text/plain", name) ?: throw IllegalStateException("Unable to create the Logcat file.")
        return file
    }

    LaunchedEffect(manager) { manager.startDiscovery() }
    val filteredLogcat = remember(logcatOutput, logcatFilter) {
        if (logcatFilter.isBlank()) logcatOutput
        else logcatOutput.lineSequence()
            .filter { it.contains(logcatFilter, ignoreCase = true) }
            .take(250)
            .joinToString("\n")
    }

    WdbTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("WDB", fontWeight = FontWeight.Bold)
                            Text("Wireless Debug Bridge", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            showSettings = true
                        }) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Bolt, contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary)
                            Spacer(Modifier.width(8.dp))
                            Text("Device session", style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold)
                        }
                        Text(
                            connectedModel?.let { "Connected · $it" } ?: "No authenticated ADB transport",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(statusMessage, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                        ContextCompat.checkSelfPermission(
                                            context, Manifest.permission.POST_NOTIFICATIONS
                                        ) != PackageManager.PERMISSION_GRANTED
                                    ) {
                                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    } else {
                                        startSession()
                                    }
                                },
                                enabled = connectedModel != null && !sessionRunning && !busy,
                                modifier = Modifier.weight(1f)
                            ) { Text("Start") }
                            OutlinedButton(
                                onClick = {
                                    busy = true
                                    scope.launch {
                                        logcatJob?.cancel()
                                        logcatJob = null
                                        streamingLogcat = false
                                        runCatching { manager.disconnect() }
                                        context.startService(
                                            Intent(context, AdbSessionService::class.java)
                                                .setAction(AdbSessionService.ACTION_STOP)
                                        )
                                        busy = false
                                        statusMessage = "ADB transport stopped."
                                    }
                                },
                                enabled = (sessionRunning || connectedModel != null) && !busy,
                                modifier = Modifier.weight(1f)
                            ) { Text("Stop") }
                        }
                    }
                }

                AdbToolsPanel(manager = manager, connected = connectedModel != null, transferDirectoryUri = transferDirectoryUri)

                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Wireless debugging", style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = { manager.refreshDiscovery() }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Refresh discovery")
                            }
                        }
                        Text("Pairing endpoints", style = MaterialTheme.typography.titleSmall)
                        if (discovery.pairDevices.isEmpty()) {
                            Text("No pairing endpoint discovered. Open Pair device with pairing code on the target device.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            discovery.pairDevices.forEach { endpoint ->
                                ListItem(
                                    headlineContent = { Text(endpoint.name) },
                                    supportingContent = { Text("${endpoint.host}:${endpoint.port} · pairing") },
                                    trailingContent = {
                                        TextButton(onClick = {
                                            if (Settings.canDrawOverlays(context)) {
                                                val floatingIntent = Intent(context, FloatingPairService::class.java)
                                                    .putExtra("com.waizusora.wdb.extra.PAIR_HOST", endpoint.host)
                                                    .putExtra("com.waizusora.wdb.extra.PAIR_PORT", endpoint.port)
                                                runCatching {
                                                    ContextCompat.startForegroundService(context, floatingIntent)
                                                    statusMessage = "Floating Pair opened for ${endpoint.host}:${endpoint.port}."
                                                }.onFailure {
                                                    statusMessage = "Unable to open floating Pair: ${it.message}"
                                                }
                                            } else {
                                                statusMessage = "Grant overlay permission in Settings to use floating Pair."
                                                showSettings = true
                                            }
                                        }) { Text("Pair") }
                                    }
                                )
                            }
                        }

                        HorizontalDivider()
                        Text("Connect endpoints", style = MaterialTheme.typography.titleSmall)
                        if (discovery.connectDevices.isEmpty()) {
                            Text("Waiting for a paired Wireless debugging device…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            discovery.connectDevices.forEach { endpoint ->
                                ListItem(
                                    headlineContent = { Text(endpoint.name) },
                                    supportingContent = { Text("${endpoint.host}:${endpoint.port} · ADB TLS") },
                                    trailingContent = {
                                        TextButton(
                                            enabled = !busy,
                                            onClick = {
                                                busy = true
                                                statusMessage = "Connecting to ${endpoint.host}…"
                                                scope.launch {
                                                    runCatching {
                                                        manager.connect(endpoint.host, endpoint.port)
                                                    }.onSuccess { model ->
                                                        statusMessage = "Authenticated ADB transport ready."
                                                    }.onFailure { error ->
                                                        statusMessage = error.message ?: "ADB connection failed."
                                                    }
                                                    busy = false
                                                }
                                            }
                                        ) { Text("Connect") }
                                    }
                                )
                            }
                        }
                        Button(onClick = {
                            if (Settings.canDrawOverlays(context)) {
                                val floatingIntent = Intent(context, FloatingPairService::class.java)
                                discovery.pairDevices.singleOrNull()?.let { endpoint ->
                                    floatingIntent.putExtra("com.waizusora.wdb.extra.PAIR_HOST", endpoint.host)
                                    floatingIntent.putExtra("com.waizusora.wdb.extra.PAIR_PORT", endpoint.port)
                                }
                                runCatching {
                                    ContextCompat.startForegroundService(context, floatingIntent)
                                    statusMessage = "Floating Pair shortcut is ready. Tap Pair to enter the pairing details."
                                }.onFailure {
                                    statusMessage = "Unable to start floating Pair: ${it.message}"
                                }
                            } else {
                                showSettings = true
                                statusMessage = "Grant overlay permission in Settings to use floating Pair."
                            }
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                            }
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text("Pair device")
                        }
                        Text("mDNS: ${discovery.status}", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Code, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("ADB command catalog", style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold)
                        }
                        Text("Select the shell operations to execute on the connected device.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        commandCatalog.forEach { item ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = item.id in selectedCommands,
                                    onCheckedChange = { checked ->
                                        selectedCommands = if (checked) selectedCommands + item.id
                                        else selectedCommands - item.id
                                    }
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(item.label, style = MaterialTheme.typography.bodyMedium)
                                    Text(item.command, style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        Button(
                            enabled = connectedModel != null && selectedCommands.isNotEmpty() && !busy,
                            onClick = {
                                busy = true
                                scope.launch {
                                    val output = buildString {
                                        commandCatalog.filter { it.id in selectedCommands }.forEach { item ->
                                            append("$ ").append(item.command).append("\n")
                                            val result = runCatching { manager.execute(item.command) }
                                            append(result.getOrElse { it.message ?: "Command failed." })
                                            append("\n\n")
                                        }
                                    }
                                    commandOutput = output.takeLast(24_000)
                                    busy = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Run selected commands") }
                        if (commandOutput.isNotBlank()) {
                            SelectionContainer {
                                Text(commandOutput, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        HorizontalDivider()
                        OutlinedTextField(
                            value = customCommand,
                            onValueChange = { customCommand = it.take(2_000) },
                            label = { Text("Custom device shell command") },
                            placeholder = { Text("dumpsys activity") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedButton(
                            enabled = connectedModel != null && customCommand.isNotBlank() && !busy,
                            onClick = {
                                busy = true
                                val command = customCommand
                                scope.launch {
                                    val result = runCatching { manager.execute(command) }
                                        .getOrElse { it.message ?: "Command failed." }
                                    commandOutput = "$ " + command + "\n" + result
                                    busy = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Run custom command") }
                    }
                }

                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("PID lookup", style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold)
                        OutlinedTextField(
                            value = packageName,
                            onValueChange = { packageName = it.trim().take(255) },
                            label = { Text("Package / process name") },
                            placeholder = { Text("com.example.app") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedButton(
                            enabled = connectedModel != null && packageName.matches(Regex("[A-Za-z0-9._]+")) && !busy,
                            onClick = {
                                busy = true
                                scope.launch {
                                    pidOutput = runCatching {
                                        manager.execute("pidof $packageName").ifBlank { "No process found." }
                                    }.getOrElse { it.message ?: "PID lookup failed." }
                                    busy = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Run pidof") }
                        if (pidOutput.isNotBlank()) Text(pidOutput, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.AutoMirrored.Outlined.Article, contentDescription = "Logcat", modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Logcat", style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.weight(1f))
                            TextButton(enabled = !busy, onClick = {
                                if (!manager.isConnected()) {
                                    statusMessage = "Clear Logcat: device is not connected."
                                } else {
                                    busy = true
                                    scope.launch {
                                        val result = runCatching { manager.execute("logcat -c") }
                                        if (result.isSuccess && !result.getOrThrow().contains("[exit code")) {
                                            logcatOutput = ""
                                            statusMessage = "Logcat buffer cleared successfully."
                                        } else {
                                            statusMessage = "Clear Logcat failed: " +
                                                (result.getOrNull() ?: result.exceptionOrNull()?.message ?: "ADB command failed.")
                                        }
                                        busy = false
                                    }
                                }
                            }) { Text("Clear Logcat") }
                        }
                        OutlinedTextField(
                            value = logcatFilter,
                            onValueChange = { logcatFilter = it },
                            label = { Text("Filter text (optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                enabled = connectedModel != null && !busy && !streamingLogcat && transferDirectoryUri != null,
                                onClick = {
                                    busy = true
                                    scope.launch {
                                        var snapshotFile: DocumentFile? = null
                                        try {
                                            val snapshot = manager.execute("logcat -b all -d -t 500 -v threadtime")
                                            val file = createLogcatFile()
                                            snapshotFile = file
                                            withContext(Dispatchers.IO) {
                                                context.contentResolver.openOutputStream(file.uri, "wt")?.use {
                                                    it.write(snapshot.toByteArray(Charsets.UTF_8))
                                                } ?: throw IllegalStateException("Unable to open the Logcat output file.")
                                            }
                                            logcatOutput = snapshot.takeLast(60_000)
                                            statusMessage = "Logcat snapshot saved: ${file.name}"
                                        } catch (cancelled: CancellationException) {
                                            snapshotFile?.delete()
                                            throw cancelled
                                        } catch (error: Exception) {
                                            snapshotFile?.delete()
                                            statusMessage = "Logcat capture failed: ${error.message}"
                                        } finally { busy = false }
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("Capture & save") }
                            OutlinedButton(
                                enabled = connectedModel != null && !busy && (streamingLogcat || transferDirectoryUri != null),
                                onClick = {
                                    if (streamingLogcat) {
                                        logcatJob?.cancel()
                                        logcatJob = null
                                        streamingLogcat = false
                                    } else {
                                        logcatOutput = ""
                                        streamingLogcat = true
                                        logcatJob = scope.launch {
                                            var output: java.io.OutputStream? = null
                                            var logcatFile: DocumentFile? = null
                                            var savedBytes = 0L
                                            try {
                                                val file = createLogcatFile()
                                                logcatFile = file
                                                output = withContext(Dispatchers.IO) {
                                                    context.contentResolver.openOutputStream(file.uri, "wt")
                                                        ?: throw IllegalStateException("Unable to open the Logcat output file.")
                                                }
                                                manager.logcatFlow().collect { chunk ->
                                                    val text = when (chunk) {
                                                        is ShellChunk.Stdout -> chunk.text
                                                        is ShellChunk.Stderr -> chunk.text
                                                        is ShellChunk.Exit -> ""
                                                    }
                                                    if (text.isNotEmpty()) {
                                                        logcatOutput = (logcatOutput + text).takeLast(60_000)
                                                        withContext(Dispatchers.IO) { output.write(text.toByteArray(Charsets.UTF_8)); output.flush() }
                                                        savedBytes += text.toByteArray(Charsets.UTF_8).size
                                                    }
                                                    if (chunk is ShellChunk.Exit) streamingLogcat = false
                                                }
                                                statusMessage = "Logcat stream saved: ${requireNotNull(logcatFile).name}"
                                            } catch (cancelled: CancellationException) {
                                                if (savedBytes == 0L) logcatFile?.delete()
                                                else statusMessage = "Partial Logcat saved: ${requireNotNull(logcatFile).name}"
                                                throw cancelled
                                            } catch (error: Exception) {
                                                if (savedBytes == 0L) logcatFile?.delete()
                                                logcatOutput = (logcatOutput + "\\n" + (error.message ?: "Logcat stream failed.")).takeLast(60_000)
                                                statusMessage = if (savedBytes > 0L) "Partial Logcat saved: ${logcatFile?.name}" else "Logcat save/stream failed: ${error.message}"
                                            } finally {
                                                withContext(NonCancellable + Dispatchers.IO) { runCatching { output?.close() } }
                                                streamingLogcat = false
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text(if (streamingLogcat) "Stop stream" else "Live stream") }
                        }
                        if (logcatOutput.isNotBlank()) {
                            Text("${filteredLogcat.lineSequence().count()} visible lines",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            SelectionContainer {
                                Text(filteredLogcat.takeLast(24_000),
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }

        if (showSettings) {
            AlertDialog(
                onDismissRequest = { showSettings = false },
                title = { Text("Settings") },
                text = {
                    Column(
                        modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("File transfer directory", style = MaterialTheme.typography.titleSmall)
                        Text(
                            transferDirectoryUri?.let { "Selected: ${androidx.documentfile.provider.DocumentFile.fromTreeUri(context, it)?.name ?: it.lastPathSegment}" }
                                ?: "Not selected · Pull uses the system save dialog; Push uses the system file picker.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(onClick = { transferDirectoryPicker.launch(transferDirectoryUri) }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (transferDirectoryUri == null) "Choose transfer directory" else "Change transfer directory")
                        }
                        if (transferDirectoryUri != null) {
                            TextButton(onClick = {
                                runCatching {
                                    context.contentResolver.releasePersistableUriPermission(
                                        transferDirectoryUri!!,
                                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                    )
                                }
                                context.getSharedPreferences("wdb_settings", android.content.Context.MODE_PRIVATE)
                                    .edit().remove("transfer_directory_uri").apply()
                                transferDirectoryUri = null
                            }) { Text("Clear selected directory") }
                        }
                        HorizontalDivider()
                        Text("Overlay access")
                        OutlinedButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")))
                        }, modifier = Modifier.fillMaxWidth()) { Text("Grant overlay permission") }
                        Text("Battery optimization")
                        OutlinedButton(onClick = {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${context.packageName}"))
                            runCatching { context.startActivity(intent) }
                        }, modifier = Modifier.fillMaxWidth()) { Text("Request exemption") }
                        OutlinedButton(
                            onClick = {
                                showSettings = false
                                showOpenSourceLicenses = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Open Source Licenses") }
                        Text("WDB uses a full wake lock while an active session is running. This keeps the display awake and increases battery consumption. Android may still terminate a process or honor a user force-stop.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSettings = false }) { Text("Done") }
                }
            )
        }
        if (showOpenSourceLicenses) {
            AlertDialog(
                onDismissRequest = { showOpenSourceLicenses = false },
                title = { Text("Open Source Licenses") },
                text = {
                    Column(
                        modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text("Direct and resolved runtime dependencies", style = MaterialTheme.typography.bodySmall)
                        LicenseEntry("adb-kt 1.0.13", "Copyright holders: adb-kt contributors", "Apache License 2.0", "https://github.com/rhythmcache/adb-kt")
                        LicenseEntry("spake2-kt v1.0.2", "Copyright holders: spake2-kt contributors", "Apache License 2.0", "https://github.com/rhythmcache/spake2-kt")
                        LicenseEntry("Bouncy Castle 1.85", "Copyright (c) 2000-2026 The Legion of the Bouncy Castle Inc.", "MIT License", "https://www.bouncycastle.org/")
                        LicenseEntry("Okio 3.18.1", "Copyright Square, Inc. and Okio contributors", "Apache License 2.0", "https://github.com/square/okio")
                        LicenseEntry("Kotlinx Coroutines 1.11.0", "Copyright JetBrains s.r.o. and Kotlinx Coroutines contributors", "Apache License 2.0", "https://github.com/Kotlin/kotlinx.coroutines")
                        LicenseEntry("AndroidX and Jetpack Compose", "Copyright The Android Open Source Project", "Apache License 2.0", "https://developer.android.com/jetpack")
                        Text(
                            "WDB does not relicense these components. Full license texts and notices are available in THIRD_PARTY_NOTICES.md and the upstream projects.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showOpenSourceLicenses = false }) { Text("Close") }
                }
            )
        }
    }
}

@Composable
private fun LicenseEntry(name: String, copyright: String, license: String, url: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(copyright, style = MaterialTheme.typography.bodySmall)
        Text(license, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(url, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.waizusora.wdb.session.AdbSessionService
import com.waizusora.wdb.session.FloatingPairService
import com.waizusora.wdb.session.AdbSessionState
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import io.github.rhythmcache.adb.ShellChunk

private data class AdbCommandOption(
    val id: String,
    val label: String,
    val command: String
)

private val commandCatalog = listOf(
    AdbCommandOption("model", "Device model", "getprop ro.product.model"),
    AdbCommandOption("android", "Android version", "getprop ro.build.version.release"),
    AdbCommandOption("serial", "Device serial", "getprop ro.serialno"),
    AdbCommandOption("packages", "Installed packages", "pm list packages"),
    AdbCommandOption("processes", "Process list", "ps -A"),
    AdbCommandOption("battery", "Battery diagnostics", "dumpsys battery"),
    AdbCommandOption("memory", "Memory summary", "dumpsys meminfo"),
    AdbCommandOption("storage", "Storage usage", "df -h"),
    AdbCommandOption("network", "Network interfaces", "ip addr"),
    AdbCommandOption("uptime", "Device uptime", "uptime")
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WdbApp(showPairInitially = intent.getBooleanExtra(EXTRA_SHOW_PAIR, false)) }
    }

    companion object {
        const val EXTRA_SHOW_PAIR = "com.waizusora.wdb.extra.SHOW_PAIR"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WdbApp(showPairInitially: Boolean) {
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

    var showSettings by remember { mutableStateOf(false) }
    var showPair by remember { mutableStateOf(showPairInitially) }
    var address by remember { mutableStateOf("") }
    var pairPort by remember { mutableStateOf("") }
    var pairingCode by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var commandOutput by remember { mutableStateOf("") }
    var selectedCommands by remember { mutableStateOf(setOf("model", "android")) }
    var packageName by remember { mutableStateOf("") }
    var pidOutput by remember { mutableStateOf("") }
    var logcatFilter by remember { mutableStateOf("") }
    var logcatOutput by remember { mutableStateOf("") }
    var streamingLogcat by remember { mutableStateOf(false) }
    var logcatJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(manager) { manager.startDiscovery() }
    val filteredLogcat = remember(logcatOutput, logcatFilter) {
        if (logcatFilter.isBlank()) logcatOutput
        else logcatOutput.lineSequence()
            .filter { it.contains(logcatFilter, ignoreCase = true) }
            .take(250)
            .joinToString("\n")
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = androidx.compose.ui.graphics.Color(0xFFB7C8FF),
            secondary = androidx.compose.ui.graphics.Color(0xFF8DD8C2),
            surface = androidx.compose.ui.graphics.Color(0xFF171A20),
            background = androidx.compose.ui.graphics.Color(0xFF101318)
        )
    ) {
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
                                enabled = sessionRunning && !busy,
                                modifier = Modifier.weight(1f)
                            ) { Text("Stop") }
                        }
                    }
                }

                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Wireless debugging", style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = { manager.startDiscovery() }) {
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
                                            address = endpoint.host
                                            pairPort = endpoint.port.toString()
                                            showPair = true
                                        }) { Text("Use") }
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
                            showPair = true
                            if (Settings.canDrawOverlays(context)) {
                                runCatching {
                                    ContextCompat.startForegroundService(context, Intent(context, FloatingPairService::class.java))
                                }
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
                        Text("Logcat capture", style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold)
                        OutlinedTextField(
                            value = logcatFilter,
                            onValueChange = { logcatFilter = it },
                            label = { Text("Filter text (optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                enabled = connectedModel != null && !busy && !streamingLogcat,
                                onClick = {
                                    busy = true
                                    scope.launch {
                                        logcatOutput = runCatching {
                                            manager.execute("logcat -d -t 500 -v threadtime")
                                        }.getOrElse { it.message ?: "Logcat capture failed." }.takeLast(60_000)
                                        busy = false
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("Capture") }
                            OutlinedButton(
                                enabled = connectedModel != null && !busy,
                                onClick = {
                                    if (streamingLogcat) {
                                        logcatJob?.cancel()
                                        logcatJob = null
                                        streamingLogcat = false
                                    } else {
                                        logcatOutput = ""
                                        streamingLogcat = true
                                        logcatJob = scope.launch {
                                            try {
                                                manager.logcatFlow().collect { chunk ->
                                                    when (chunk) {
                                                        is ShellChunk.Stdout ->
                                                            logcatOutput = (logcatOutput + chunk.text).takeLast(60_000)
                                                        is ShellChunk.Stderr ->
                                                            logcatOutput = (logcatOutput + chunk.text).takeLast(60_000)
                                                        is ShellChunk.Exit -> streamingLogcat = false
                                                    }
                                                }
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (error: Throwable) {
                                                logcatOutput = (logcatOutput + "\n" +
                                                    (error.message ?: "Logcat stream failed.")).takeLast(60_000)
                                                streamingLogcat = false
                                            } finally {
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

        if (showPair) {
            AlertDialog(
                onDismissRequest = { showPair = false },
                title = { Text("Pair with device") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(address, { address = it }, label = { Text("IP address") },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(pairPort, { pairPort = it.filter(Char::isDigit).take(5) },
                            label = { Text("Pairing port") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(pairingCode, { pairingCode = it.filter(Char::isDigit).take(6) },
                            label = { Text("6-digit pairing code") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth())
                        Text("Keep the system pairing dialog open while pairing completes.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = !busy && address.isNotBlank() && pairPort.toIntOrNull()?.let { it in 1..65535 } == true &&
                            pairingCode.matches(Regex("[0-9]{6}")),
                        onClick = {
                            busy = true
                            statusMessage = "Performing secure Wireless debugging pairing…"
                            val host = address
                            val port = pairPort.toInt()
                            val code = pairingCode
                            scope.launch {
                                runCatching { manager.pair(host, port, code) }
                                    .onSuccess { statusMessage = "Pairing handshake succeeded. Waiting for connect endpoint." }
                                    .onFailure { statusMessage = it.message ?: "Pairing failed." }
                                pairingCode = ""
                                busy = false
                                showPair = false
                            }
                        }
                    ) { Text("Pair") }
                },
                dismissButton = {
                    TextButton(onClick = { showPair = false; pairingCode = "" }) { Text("Cancel") }
                }
            )
        }

        if (showSettings) {
            AlertDialog(
                onDismissRequest = { showSettings = false },
                title = { Text("Settings") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                        Text("A foreground service and partial wake lock improve session continuity but cannot prevent force-stop or guarantee process survival.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSettings = false }) { Text("Done") }
                }
            )
        }
    }
}

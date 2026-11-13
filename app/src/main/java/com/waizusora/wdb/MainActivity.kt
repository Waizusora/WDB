package com.waizusora.wdb

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Code
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
    var showSettings by remember { mutableStateOf(false) }
    var showPair by remember { mutableStateOf(showPairInitially) }
    var address by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var pairingCode by remember { mutableStateOf("") }
    var sessionRunning by remember { mutableStateOf(false) }

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
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
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
                            if (sessionRunning) "Session service active · ADB transport not connected"
                            else "No active session",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = {
                                    ContextCompat.startForegroundService(
                                        context, Intent(context, AdbSessionService::class.java)
                                            .setAction(AdbSessionService.ACTION_START)
                                    )
                                    sessionRunning = true
                                },
                                enabled = !sessionRunning,
                                modifier = Modifier.weight(1f)
                            ) { Text("Start") }
                            OutlinedButton(
                                onClick = {
                                    context.startService(
                                        Intent(context, AdbSessionService::class.java)
                                            .setAction(AdbSessionService.ACTION_STOP)
                                    )
                                    sessionRunning = false
                                },
                                enabled = sessionRunning,
                                modifier = Modifier.weight(1f)
                            ) { Text("Stop") }
                        }
                    }
                }

                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Wireless pairing", style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold)
                        Text(
                            "Open Android Developer Options, choose Wireless debugging, then select " +
                                "“Pair device with pairing code”. A floating WDB shortcut is available " +
                                "when overlay access has been granted.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(onClick = {
                            showPair = true
                            if (Settings.canDrawOverlays(context)) {
                                runCatching {
                                    context.startService(Intent(context, FloatingPairService::class.java))
                                }
                            }
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                            }
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text("Pair device")
                        }
                        TextButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                        }) { Text("Open wireless settings") }
                    }
                }

                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Code, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("ADB tools", style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold)
                        }
                        Text("Logcat, shell commands and PID lookup require a verified ADB transport. They are not available in this foundation build.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        AssistChip(onClick = {}, enabled = false, label = { Text("Transport not configured") })
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
                        OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) },
                            label = { Text("Pairing port") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(pairingCode, { pairingCode = it.filter(Char::isDigit).take(12) },
                            label = { Text("Pairing code") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth())
                        Text("The secure ADB pairing transport is not implemented yet. This form does not send or validate the code.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showPair = false }) { Text("Close") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showPair = false
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                    }) { Text("Developer options") }
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
                        Text("These capabilities require explicit approval in Android Settings. They do not prevent force-stop or guarantee background survival.",
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

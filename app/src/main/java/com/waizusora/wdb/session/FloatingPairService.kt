package com.waizusora.wdb.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.waizusora.wdb.WdbApplication
import com.waizusora.wdb.ui.theme.WdbTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Material 3 floating Wireless Debugging pairing panel.
 * Pairing runs through the process-wide ADB manager.
 */
class FloatingPairService : Service() {
    private var windowManager: WindowManager? = null
    private var overlay: ComposeView? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var keyboardLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    private var expanded by mutableStateOf(false)
    private var host by mutableStateOf("127.0.0.1")
    private var port by mutableStateOf("")
    private var pairingCode by mutableStateOf("")
    private var status by mutableStateOf("This phone is detected automatically. Remote devices keep their Wi-Fi address.")
    private var pairing by mutableStateOf(false)

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        buildOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_HOST)?.takeIf { it.isNotBlank() }?.let { host = it }
        intent?.getIntExtra(EXTRA_PORT, 0)?.takeIf { it in 1..65535 }?.let { port = it.toString() }
        return START_NOT_STICKY
    }

    private fun buildOverlay() {
        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                PairOverlayContent(
                    expanded = expanded,
                    host = host,
                    onHostChange = { host = it },
                    port = port,
                    onPortChange = { port = it.filter(Char::isDigit).take(5) },
                    code = pairingCode,
                    onCodeChange = { pairingCode = it.filter(Char::isDigit).take(6) },
                    status = status,
                    pairing = pairing,
                    onExpand = { setExpanded(true) },
                    onCollapse = { setExpanded(false) },
                    onClose = { stopSelf() },
                    onPair = { performPairing() }
                )
            }
        }
        composeView.isFocusableInTouchMode = true

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }
        windowParams = WindowManager.LayoutParams(
            dp(150),
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(14)
            y = dp(180)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
        }
        overlay = composeView
        runCatching {
            windowManager?.addView(composeView, windowParams)
            installKeyboardPositioning(composeView)
        }.onFailure {
            status = "Unable to show floating Pair: ${it.message ?: "unknown error"}"
            stopSelf()
        }
    }

    private fun setExpanded(value: Boolean) {
        expanded = value
        val view = overlay ?: return
        windowParams?.let { params ->
            params.width = dp(if (value) 336 else 150)
            params.flags = if (value) {
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            } else {
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            }
            params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
            runCatching { windowManager?.updateViewLayout(view, params) }
        }
        if (!value) {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }

    /**
     * Keep the expanded form in the upper safe area while the IME is visible,
     * leaving Android's Wireless Debugging pairing details unobstructed below.
     */
    private fun installKeyboardPositioning(view: ComposeView) {
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            if (!expanded || !view.isAttachedToWindow) return@OnGlobalLayoutListener
            val visibleFrame = Rect()
            view.getWindowVisibleDisplayFrame(visibleFrame)
            val screenHeight = resources.displayMetrics.heightPixels
            val keyboardVisible = screenHeight - visibleFrame.bottom > dp(160)
            val targetY = if (keyboardVisible) dp(12) else dp(180)
            windowParams?.let { params ->
                if (params.y != targetY) {
                    params.y = targetY
                    runCatching { windowManager?.updateViewLayout(view, params) }
                }
            }
        }
        keyboardLayoutListener = listener
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
    }

    private fun performPairing() {
        val targetHost = host.trim()
        val targetPort = port.toIntOrNull()
        val code = pairingCode.trim()
        if (targetHost.isBlank() || targetPort == null || targetPort !in 1..65535 ||
            !code.matches(Regex("[0-9]{6}"))
        ) {
            status = "Check the IP address, port, and 6-digit pairing code."
            return
        }
        if (pairing) return

        pairing = true
        status = "Pairing securely…"
        serviceScope.launch {
            try {
                (application as WdbApplication).adbManager.pair(targetHost, targetPort, code)
                pairingCode = ""
                status = "Pairing successful. Waiting for connect endpoint."
                Toast.makeText(this@FloatingPairService, "WDB pairing successful", Toast.LENGTH_SHORT).show()
            } catch (error: Exception) {
                status = error.message?.take(160) ?: "Pairing failed."
            } finally {
                pairing = false
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "WDB pairing shortcut",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the user-requested floating pairing shortcut visible."
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder.setContentTitle("WDB pairing shortcut")
            .setContentText("Tap Pair on the floating panel to enter pairing details.")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        overlay?.let { view ->
            keyboardLayoutListener?.let { listener ->
                if (view.viewTreeObserver.isAlive) {
                    view.viewTreeObserver.removeOnGlobalLayoutListener(listener)
                }
            }
            runCatching { windowManager?.removeView(view) }
        }
        keyboardLayoutListener = null
        overlay = null
        windowManager = null
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val CHANNEL_ID = "wdb_pairing_shortcut"
        const val NOTIFICATION_ID = 4102
        const val EXTRA_HOST = "com.waizusora.wdb.extra.PAIR_HOST"
        const val EXTRA_PORT = "com.waizusora.wdb.extra.PAIR_PORT"
    }
}

@Composable
private fun PairOverlayContent(
    expanded: Boolean,
    host: String,
    onHostChange: (String) -> Unit,
    port: String,
    onPortChange: (String) -> Unit,
    code: String,
    onCodeChange: (String) -> Unit,
    status: String,
    pairing: Boolean,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    onClose: () -> Unit,
    onPair: () -> Unit
) {
    WdbTheme {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            shadowElevation = 12.dp
        ) {
            if (!expanded) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(onClick = onExpand) { Text("Pair") }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = onClose) { Text("×") }
                }
            } else {
                val focusRequester = androidx.compose.runtime.remember { FocusRequester() }
                val keyboard = LocalSoftwareKeyboardController.current
                LaunchedEffect(Unit) {
                    focusRequester.requestFocus()
                    keyboard?.show()
                }
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Pair device", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            Text("Enter the details shown by Android.", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        OutlinedButton(onClick = onClose) { Text("×") }
                    }
                    OutlinedTextField(
                        value = host,
                        onValueChange = onHostChange,
                        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                        label = { Text("IP address") },
                        placeholder = { Text("127.0.0.1") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                    )
                    OutlinedTextField(
                        value = port,
                        onValueChange = onPortChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Pairing port") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    OutlinedTextField(
                        value = code,
                        onValueChange = onCodeChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("6-digit pairing code") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                    Text(status, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = onCollapse, modifier = Modifier.weight(1f)) { Text("Back") }
                        Button(onClick = onPair, enabled = !pairing, modifier = Modifier.weight(1f)) {
                            Text(if (pairing) "Pairing…" else "Pair now")
                        }
                    }
                }
            }
        }
    }
}

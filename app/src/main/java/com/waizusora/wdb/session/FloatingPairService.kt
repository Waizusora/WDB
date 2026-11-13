package com.waizusora.wdb.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.waizusora.wdb.MainActivity
import com.waizusora.wdb.WdbApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Compact floating Wireless debugging shortcut.
 * Pair expands an editable overlay form; pairing is performed by the process-wide ADB manager.
 */
class FloatingPairService : Service() {
    private var windowManager: WindowManager? = null
    private var overlay: LinearLayout? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var expanded = false
    private lateinit var addressInput: EditText
    private lateinit var portInput: EditText
    private lateinit var codeInput: EditText
    private lateinit var statusText: TextView
    private lateinit var pairButton: TextView
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
        intent?.getStringExtra(EXTRA_HOST)?.takeIf { it.isNotBlank() }?.let { addressInput.setText(it) }
        intent?.getIntExtra(EXTRA_PORT, 0)?.takeIf { it in 1..65535 }?.let { portInput.setText(it.toString()) }
        return START_NOT_STICKY
    }

    private fun buildOverlay() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(12))
            background = roundedBackground(Color.rgb(24, 29, 38), 22f, Color.rgb(76, 91, 116))
            elevation = dp(10).toFloat()
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        pairButton = TextView(this).apply {
            text = "Pair"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(18, 25, 38))
            setPadding(dp(24), dp(10), dp(24), dp(10))
            background = roundedBackground(Color.rgb(183, 200, 255), 14f)
            setOnClickListener { setExpanded(!expanded) }
        }
        val spacer = android.view.View(this)
        val close = TextView(this).apply {
            text = "×"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(190, 199, 214))
            setPadding(dp(12), 0, dp(2), 0)
            setOnClickListener { stopSelf() }
        }
        header.addView(pairButton)
        header.addView(spacer, LinearLayout.LayoutParams(0, dp(42), 1f))
        header.addView(close)
        panel.addView(header)

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = android.view.View.GONE
        }
        form.addView(label("Pair device"))
        form.addView(label("IP address"))
        addressInput = input("192.168.1.10", android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI)
        form.addView(addressInput, fieldParams())
        form.addView(label("Port"))
        portInput = input("Pairing port", android.text.InputType.TYPE_CLASS_NUMBER)
        form.addView(portInput, fieldParams())
        form.addView(label("Pairing code"))
        codeInput = input("6-digit code", android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD).apply {
            filters = arrayOf(android.text.InputFilter.LengthFilter(6))
        }
        form.addView(codeInput, fieldParams())

        statusText = TextView(this).apply {
            text = "Enter the pairing details shown on your device."
            textSize = 11f
            setTextColor(Color.rgb(174, 185, 203))
            setPadding(0, dp(8), 0, dp(4))
        }
        form.addView(statusText)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = TextView(this).apply {
            text = "Back"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(210, 218, 231))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener { setExpanded(false) }
        }
        val submit = TextView(this).apply {
            text = "Pair now"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(18, 25, 38))
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = roundedBackground(Color.rgb(141, 216, 194), 12f)
            setOnClickListener { performPairing(this) }
        }
        actions.addView(back)
        actions.addView(android.view.View(this), LinearLayout.LayoutParams(0, dp(40), 1f))
        actions.addView(submit)
        form.addView(actions)
        panel.addView(form)

        // Keep the form reference on the panel so expansion can toggle it without rebuilding views.
        panel.tag = form
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }
        windowParams = WindowManager.LayoutParams(
            dp(150), WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(14)
            y = dp(180)
        }
        overlay = panel
        runCatching { windowManager?.addView(panel, windowParams) }
            .onFailure { stopSelf() }
    }

    private fun setExpanded(value: Boolean) {
        expanded = value
        val panel = overlay ?: return
        val form = panel.tag as? LinearLayout ?: return
        form.visibility = if (value) android.view.View.VISIBLE else android.view.View.GONE
        pairButton.visibility = if (value) android.view.View.GONE else android.view.View.VISIBLE
        windowParams?.let { params ->
            params.width = dp(if (value) 300 else 150)
            params.flags = if (value) {
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            } else {
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            }
            runCatching { windowManager?.updateViewLayout(panel, params) }
        }
        if (value) {
            addressInput.requestFocus()
            addressInput.post {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(addressInput, InputMethodManager.SHOW_IMPLICIT)
            }
        } else {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(addressInput.windowToken, 0)
        }
    }

    private fun performPairing(button: TextView) {
        val host = addressInput.text.toString().trim()
        val port = portInput.text.toString().toIntOrNull()
        val code = codeInput.text.toString().trim()
        if (host.isBlank() || port == null || port !in 1..65535 || !code.matches(Regex("[0-9]{6}"))) {
            statusText.text = "Check the IP address, port, and 6-digit code."
            return
        }
        button.isEnabled = false
        button.alpha = 0.55f
        statusText.text = "Pairing securely…"
        serviceScope.launch {
            try {
                (application as WdbApplication).adbManager.pair(host, port, code)
                codeInput.text.clear()
                statusText.text = "Pairing successful. Waiting for connect endpoint."
                Toast.makeText(this@FloatingPairService, "WDB pairing successful", Toast.LENGTH_SHORT).show()
            } catch (error: Exception) {
                statusText.text = error.message?.take(120) ?: "Pairing failed."
            } finally {
                button.isEnabled = true
                button.alpha = 1f
            }
        }
    }

    private fun label(value: String) = TextView(this).apply {
        text = value
        textSize = 11f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.rgb(190, 201, 219))
        setPadding(0, dp(9), 0, dp(3))
    }

    private fun input(hintText: String, type: Int) = EditText(this).apply {
        hint = hintText
        textSize = 13f
        inputType = type
        setSingleLine(true)
        setTextColor(Color.WHITE)
        setHintTextColor(Color.rgb(125, 138, 158))
        setPadding(dp(10), 0, dp(10), 0)
        background = roundedBackground(Color.rgb(39, 47, 61), 10f)
    }

    private fun fieldParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, dp(42)
    )

    private fun roundedBackground(color: Int, radius: Float, stroke: Int? = null) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius.toInt()).toFloat()
            stroke?.let { setStroke(dp(1), it) }
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "WDB pairing shortcut", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Keeps the user-requested floating pairing shortcut visible." }
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
        overlay?.let { view -> runCatching { windowManager?.removeView(view) } }
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

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
import android.widget.LinearLayout
import android.widget.TextView
import com.waizusora.wdb.MainActivity

/**
 * Displays a small, user-dismissable entry point over Android system settings.
 *
 * The foreground notification keeps the explicitly requested overlay service
 * visible to the user while Developer Options is in the foreground. The overlay
 * never captures pairing codes; tapping it opens WDB's pairing form.
 */
class FloatingPairService : Service() {
    private var windowManager: WindowManager? = null
    private var overlay: android.view.View? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(18, 12, 10, 12)
            background = GradientDrawable().apply {
                setColor(Color.rgb(31, 39, 50))
                cornerRadius = 48f
                setStroke(1, Color.rgb(93, 117, 145))
            }
        }
        val open = TextView(this).apply {
            text = "WDB  ·  Pair code"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(4, 4, 16, 4)
            setOnClickListener {
                val intent = Intent(this@FloatingPairService, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(MainActivity.EXTRA_SHOW_PAIR, true)
                startActivity(intent)
            }
        }
        val close = TextView(this).apply {
            text = "×"
            setTextColor(Color.LTGRAY)
            textSize = 20f
            setPadding(10, 2, 6, 2)
            setOnClickListener { stopSelf() }
        }
        panel.addView(open)
        panel.addView(close)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 180
        }
        overlay = panel
        runCatching { windowManager?.addView(panel, params) }
            .onFailure { stopSelf() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "WDB pairing shortcut",
                NotificationManager.IMPORTANCE_LOW
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
        return builder
            .setContentTitle("WDB pairing shortcut")
            .setContentText("Tap the floating WDB panel to enter the pairing code.")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        overlay?.let { view -> runCatching { windowManager?.removeView(view) } }
        overlay = null
        windowManager = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val CHANNEL_ID = "wdb_pairing_shortcut"
        const val NOTIFICATION_ID = 4102
    }
}

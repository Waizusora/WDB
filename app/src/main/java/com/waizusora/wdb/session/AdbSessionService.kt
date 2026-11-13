package com.waizusora.wdb.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the user-visible lifetime of an explicitly started WDB session.
 *
 * This service deliberately does not report an ADB connection. A connected state
 * may only be published by a verified ADB transport implementation after protocol
 * negotiation and device authentication have completed.
 */
class AdbSessionService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSession()
                return START_NOT_STICKY
            }
            ACTION_START -> startSession()
            else -> return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    // FULL_WAKE_LOCK is intentional: WDB's active-session contract keeps the display awake.
    // Retain the behavior while suppressing the platform deprecation warning explicitly.
    @Suppress("DEPRECATION")
    private fun startSession() {
        startForeground(NOTIFICATION_ID, buildNotification())
        AdbSessionState.setRunning(true)
        if (wakeLock?.isHeld != true) {
            val manager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = manager.newWakeLock(PowerManager.FULL_WAKE_LOCK, "$packageName:WdbSession")
                .apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun stopSession() {
        AdbSessionState.setRunning(false)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "WDB session",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Keeps an active WDB session visible to the user." }
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
            .setContentTitle("WDB session active")
            .setContentText("WDB foreground session is running.")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        AdbSessionState.setRunning(false)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.waizusora.wdb.action.START"
        const val ACTION_STOP = "com.waizusora.wdb.action.STOP"
        private const val CHANNEL_ID = "wdb_session"
        private const val NOTIFICATION_ID = 4101
    }
}

/**
 * Process-wide observable state for the foreground ADB session.
 */
object AdbSessionState {
    private val mutableRunning = MutableStateFlow(false)
    val running = mutableRunning.asStateFlow()

    internal fun setRunning(value: Boolean) {
        mutableRunning.value = value
    }
}

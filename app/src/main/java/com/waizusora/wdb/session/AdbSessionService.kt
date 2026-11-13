package com.waizusora.wdb.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

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

    private fun startSession() {
        startForeground(NOTIFICATION_ID, buildNotification())
        if (wakeLock?.isHeld != true) {
            val manager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:WdbSession")
                .apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun stopSession() {
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
            .setContentText("Authenticated ADB session is active.")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
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

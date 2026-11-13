package com.waizusora.wdb

import android.app.Application
import com.waizusora.wdb.adb.WirelessAdbManager
import com.waizusora.wdb.security.EncryptedAdbKeyProvider

/**
 * Owns the process-wide ADB manager and encrypted host identity.
 * The manager outlives Activity recreation so a foreground session is not UI-owned.
 */
class WdbApplication : Application() {
    lateinit var adbManager: WirelessAdbManager
        private set

    override fun onCreate() {
        super.onCreate()
        val keyProvider = EncryptedAdbKeyProvider(this)
        adbManager = WirelessAdbManager(this, keyProvider)
    }
}

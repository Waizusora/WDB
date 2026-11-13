package com.waizusora.wdb

import android.app.Application
import com.flyfishxu.kadb.cert.KadbCert
import com.waizusora.wdb.adb.WirelessAdbManager
import com.waizusora.wdb.security.EncryptedAdbPrivateKeyStore

/**
 * Owns the process-wide ADB manager and configures encrypted host identity before use.
 * The manager outlives Activity recreation so a foreground session is not UI-owned.
 */
class WdbApplication : Application() {
    lateinit var adbManager: WirelessAdbManager
        private set

    override fun onCreate() {
        super.onCreate()
        KadbCert.configure(store = EncryptedAdbPrivateKeyStore(this))
        adbManager = WirelessAdbManager(this)
    }
}

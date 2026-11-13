package com.waizusora.wdb

import android.app.Application
import com.flyfishxu.kadb.cert.KadbCert
import com.waizusora.wdb.security.EncryptedAdbPrivateKeyStore

/**
 * Configures persistent, encrypted host identity before any ADB operation starts.
 */
class WdbApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        KadbCert.configure(store = EncryptedAdbPrivateKeyStore(this))
    }
}

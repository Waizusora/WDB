package com.waizusora.wdb.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.flyfishxu.kadb.cert.KadbPrivateKeyStore
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the ADB host private key encrypted with an Android Keystore AES-GCM key.
 *
 * The encryption key is non-exportable. Only ciphertext is written to app-private
 * storage. Pairing credentials and plaintext private-key material are never logged.
 */
class EncryptedAdbPrivateKeyStore(context: Context) : KadbPrivateKeyStore {
    private val appContext = context.applicationContext
    private val keyFile = File(appContext.filesDir, KEY_FILE_NAME)

    @Synchronized
    override fun readPrivateKeyPem(): ByteArray? {
        if (!keyFile.exists()) return null
        val stored = keyFile.readBytes()
        require(stored.size > IV_LENGTH + GCM_TAG_LENGTH_BYTES) {
            "Stored ADB identity is truncated."
        }
        val iv = stored.copyOfRange(0, IV_LENGTH)
        val ciphertext = stored.copyOfRange(IV_LENGTH, stored.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    @Synchronized
    override fun writePrivateKeyPemAtomic(privateKeyPem: ByteArray) {
        require(privateKeyPem.isNotEmpty()) { "ADB private key must not be empty." }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(privateKeyPem)
        val output = iv + ciphertext

        val temporary = File(appContext.filesDir, "$KEY_FILE_NAME.tmp")
        FileOutputStream(temporary).use { stream ->
            stream.write(output)
            stream.fd.sync()
        }
        try {
            Files.move(
                temporary.toPath(),
                keyFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), keyFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }

    @Synchronized
    override fun clear() {
        keyFile.delete()
        val keyStore = loadKeyStore()
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = loadKeyStore()
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun loadKeyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "com.waizusora.wdb.adb.identity.v1"
        const val KEY_FILE_NAME = "adb-host-identity.enc"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val GCM_TAG_LENGTH_BITS = 128
        const val GCM_TAG_LENGTH_BYTES = GCM_TAG_LENGTH_BITS / 8
    }
}

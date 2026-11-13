package com.waizusora.wdb.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.rhythmcache.adb.AdbKeyProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Persistent ADB RSA identity protected by an Android Keystore AES-GCM key.
 *
 * The RSA private key is encrypted before it reaches app-private storage. The
 * encryption key is non-exportable and pairing credentials are never persisted.
 */
class EncryptedAdbKeyProvider(context: Context) : AdbKeyProvider {
    private val appContext = context.applicationContext
    private val keyFile = File(appContext.filesDir, KEY_FILE_NAME)
    private val lock = Any()

    @Volatile
    private var cachedKeyPair: KeyPair? = null

    override suspend fun getKeyPair(): KeyPair = withContext(Dispatchers.IO) {
        cachedKeyPair ?: synchronized(lock) {
            cachedKeyPair ?: loadOrCreateKeyPair().also { cachedKeyPair = it }
        }
    }

    private fun loadOrCreateKeyPair(): KeyPair {
        val encoded = readEncryptedPrivateKey()
        if (encoded != null) return decodeKeyPair(encoded)

        val generated = KeyPairGenerator.getInstance("RSA").apply {
            initialize(RSA_KEY_SIZE, SecureRandom())
        }.generateKeyPair()
        val privateBytes = requireNotNull(generated.private.encoded) {
            "The generated RSA private key is not encodable."
        }
        writeEncryptedPrivateKey(privateBytes)
        return generated
    }

    private fun decodeKeyPair(encodedPrivateKey: ByteArray): KeyPair {
        val privateKey = KeyFactory.getInstance("RSA")
            .generatePrivate(PKCS8EncodedKeySpec(encodedPrivateKey))
        val rsaPrivateKey = privateKey as? RSAPrivateCrtKey
            ?: throw IllegalStateException("Stored ADB identity is not an RSA CRT private key.")
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(
            RSAPublicKeySpec(rsaPrivateKey.modulus, rsaPrivateKey.publicExponent)
        )
        return KeyPair(publicKey, privateKey)
    }

    private fun readEncryptedPrivateKey(): ByteArray? {
        if (!keyFile.exists()) return null
        val stored = keyFile.readBytes()
        require(stored.size > IV_LENGTH + GCM_TAG_LENGTH_BYTES) {
            "Encrypted ADB identity is truncated."
        }
        val iv = stored.copyOfRange(0, IV_LENGTH)
        val ciphertext = stored.copyOfRange(IV_LENGTH, stored.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateEncryptionKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun writeEncryptedPrivateKey(privateKey: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateEncryptionKey())
        val encrypted = cipher.iv + cipher.doFinal(privateKey)
        val temporary = File(appContext.filesDir, "$KEY_FILE_NAME.tmp")

        FileOutputStream(temporary).use { stream ->
            stream.write(encrypted)
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

    private fun getOrCreateEncryptionKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "com.waizusora.wdb.adb.identity.v1"
        const val KEY_FILE_NAME = "adb-host-private-key.enc"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val RSA_KEY_SIZE = 2048
        const val IV_LENGTH = 12
        const val GCM_TAG_LENGTH_BITS = 128
        const val GCM_TAG_LENGTH_BYTES = GCM_TAG_LENGTH_BITS / 8
    }
}

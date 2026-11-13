package com.waizusora.wdb.adb

import android.content.Context
import io.github.rhythmcache.adb.AdbClient
import io.github.rhythmcache.adb.AdbKeyProvider
import io.github.rhythmcache.adb.ShellChunk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException

/**
 * Coordinates secure Wireless Debugging pairing, mDNS discovery, and ADB TLS sessions.
 *
 * The client is retained at application scope so Activity recreation does not close
 * an active session. A transport is considered connected only after a Shell V2
 * handshake succeeds.
 */
class WirelessAdbManager(
    context: Context,
    private val keyProvider: AdbKeyProvider
) : Closeable {
    private val discovery = WirelessAdbDiscovery(context.applicationContext)
    val discoveryState = discovery.state

    @Volatile
    private var activeClient: AdbClient? = null

    private val _connectedModel = MutableStateFlow<String?>(null)
    val connectedModel = _connectedModel.asStateFlow()

    fun startDiscovery() = discovery.start()

    fun refreshDiscovery() = discovery.restart()

    suspend fun pair(host: String, port: Int, pairingCode: String) = withContext(Dispatchers.IO) {
        AdbEndpointValidator.validate(host, port)
        require(pairingCode.matches(Regex("[0-9]{6}"))) {
            "The Wireless debugging pairing code must contain exactly six digits."
        }
        AdbClient.pairTls(
            host = host,
            port = port,
            pairingCode = pairingCode,
            keyProvider = keyProvider
        ).getOrThrow()
        Unit
    }

    suspend fun connect(host: String, port: Int): String = withContext(Dispatchers.IO) {
        validateEndpoint(host, port)
        val candidate = AdbClient.connectTls(
            host = host,
            port = port,
            keyProvider = keyProvider,
            handshakeTimeoutMs = CONNECT_TIMEOUT_MS
        )
        try {
            val handshake = candidate.shell("echo WDB_TRANSPORT_READY")
            if (!handshake.isSuccess || handshake.stdoutText.trim() != "WDB_TRANSPORT_READY") {
                throw IOException("ADB TLS transport handshake returned an unexpected response.")
            }
            val model = candidate.shell("getprop ro.product.model").stdoutText.trim()
                .ifBlank { "Android device" }
            val previous = activeClient
            activeClient = candidate
            _connectedModel.value = model
            runCatching { previous?.close() }
            model
        } catch (error: Throwable) {
            runCatching { candidate.close() }
            throw error
        }
    }

    suspend fun execute(command: String): String = withContext(Dispatchers.IO) {
        require(command.isNotBlank()) { "Command must not be empty." }
        val client = activeClient ?: throw IllegalStateException("No authenticated ADB transport.")
        val result = client.shell(command)
        buildString {
            append(result.stdoutText)
            if (result.stderrText.isNotBlank()) {
                append("\n[stderr]\n")
                append(result.stderrText)
            }
            if (!result.isSuccess) {
                append("\n[exit code ")
                append(result.exitCode)
                append(']')
            }
        }
    }

    fun logcatFlow(): Flow<ShellChunk> {
        val client = activeClient ?: throw IllegalStateException("No authenticated ADB transport.")
        return client.shellFlow("logcat -b all -v threadtime").flowOn(Dispatchers.IO)
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        val client = activeClient
        activeClient = null
        _connectedModel.value = null
        runCatching { client?.close() }
    }

    fun isConnected(): Boolean {
        val connected = activeClient?.isClosed == false
        if (!connected) _connectedModel.value = null
        return connected
    }

    override fun close() {
        runCatching { activeClient?.close() }
        activeClient = null
        _connectedModel.value = null
        discovery.close()
    }


    private companion object {
        const val CONNECT_TIMEOUT_MS = 30_000L
    }
}

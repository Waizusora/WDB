package com.waizusora.wdb.adb

import android.content.Context
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.mdns.KadbMdnsAndroid
import com.flyfishxu.kadb.mdns.MdnsDiscoveryState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow
import java.io.Closeable
import java.io.IOException

/**
 * Coordinates pairing, mDNS discovery, and one authenticated ADB transport.
 *
 * All blocking protocol operations run on Dispatchers.IO. The active client is
 * replaced only after a successful shell handshake and is always closed on failure.
 */
class WirelessAdbManager(context: Context) : Closeable {
    private val mdns = KadbMdnsAndroid(context.applicationContext)
    val discoveryState: StateFlow<MdnsDiscoveryState> = mdns.state

    @Volatile
    private var activeClient: Kadb? = null

    fun startDiscovery() = mdns.start()

    suspend fun pair(host: String, port: Int, pairingCode: String) = withContext(Dispatchers.IO) {
        validateEndpoint(host, port)
        require(pairingCode.matches(Regex("[0-9]{6}"))) {
            "The Wireless debugging pairing code must contain exactly six digits."
        }
        Kadb.pair(host, port, pairingCode)
    }

    suspend fun connect(host: String, port: Int): String = withContext(Dispatchers.IO) {
        validateEndpoint(host, port)
        val candidate = Kadb.create(
            host = host,
            port = port,
            connectTimeout = CONNECT_TIMEOUT_MS,
            socketTimeout = SOCKET_TIMEOUT_MS
        )
        try {
            val handshake = candidate.shell("echo WDB_TRANSPORT_READY")
            if (handshake.exitCode != 0 || handshake.allOutput.trim() != "WDB_TRANSPORT_READY") {
                throw IOException("ADB transport handshake returned an unexpected response.")
            }
            val model = candidate.shell("getprop ro.product.model").allOutput.trim()
                .ifBlank { "Android device" }
            val previous = activeClient
            activeClient = candidate
            previous?.close()
            model
        } catch (error: Throwable) {
            candidate.close()
            throw error
        }
    }

    suspend fun execute(command: String): String = withContext(Dispatchers.IO) {
        require(command.isNotBlank()) { "Command must not be empty." }
        val client = activeClient ?: throw IllegalStateException("No authenticated ADB transport.")
        client.shell(command).allOutput
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        activeClient?.close()
        activeClient = null
    }

    fun isConnected(): Boolean = activeClient?.connectionCheck() == true

    override fun close() {
        runCatching { activeClient?.close() }
        activeClient = null
        mdns.close()
    }

    private fun validateEndpoint(host: String, port: Int) {
        require(host.isNotBlank() && host.length <= 253) { "A valid device IP address is required." }
        require(port in 1..65535) { "Port must be between 1 and 65535." }
        val ipv4 = host.split(".").let { parts ->
            parts.size == 4 && parts.all { part ->
                part.isNotEmpty() && part.all(Char::isDigit) && part.toIntOrNull() in 0..255
            }
        }
        val ipv6 = host.contains(':') && host.matches(Regex("[0-9a-fA-F:.%]+"))
        require(ipv4 || ipv6) { "Enter a numeric IPv4 or IPv6 address." }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val SOCKET_TIMEOUT_MS = 30_000
    }
}

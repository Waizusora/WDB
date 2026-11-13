package com.waizusora.wdb.adb

import android.content.Context
import android.net.Uri
import io.github.rhythmcache.adb.io.RandomAccessSource
import kotlinx.coroutines.flow.collect
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
    private val appContext = context.applicationContext
    private val discovery = WirelessAdbDiscovery(appContext)
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
        AdbEndpointValidator.validate(host, port)
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


    suspend fun installApk(uri: Uri): String = withContext(Dispatchers.IO) {
        val client = requireClient()
        val descriptor = appContext.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IOException("Unable to open the selected APK.")
        try {
            val source = RandomAccessSource.of(descriptor.fileDescriptor, descriptor.statSize)
            var latest = "Preparing APK installation…"
            client.install.install(source, flags = listOf("-r")).collect { progress ->
                latest = "${progress.statusText} (${progress.percentage.toInt()}%)"
            }
            latest
        } finally {
            descriptor.close()
        }
    }

    suspend fun uninstallPackage(packageName: String): String = withContext(Dispatchers.IO) {
        require(packageName.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))) {
            "Enter a valid Android package name."
        }
        val result = requireClient().shell("pm uninstall $packageName")
        if (!result.isSuccess || !result.stdoutText.trim().startsWith("Success")) {
            throw IOException(result.stderrText.ifBlank { result.stdoutText }.ifBlank { "Package uninstall failed." })
        }
        result.stdoutText.trim()
    }

    suspend fun pushFile(uri: Uri, remotePath: String): String = withContext(Dispatchers.IO) {
        require(remotePath.startsWith("/") && remotePath.none { it.code == 0 }) {
            "Remote destination must be an absolute device path."
        }
        val input = appContext.contentResolver.openInputStream(uri)
            ?: throw IOException("Unable to open the selected source file.")
        input.use { stream ->
            var transferred = 0L
            requireClient().sync.push(stream, remotePath, onProgress = { transferred = it })
            "Pushed $transferred bytes to $remotePath"
        }
    }

    suspend fun pullFile(remotePath: String, destination: Uri): String = withContext(Dispatchers.IO) {
        require(remotePath.startsWith("/") && remotePath.none { it.code == 0 }) {
            "Remote source must be an absolute device path."
        }
        val output = appContext.contentResolver.openOutputStream(destination, "wt")
            ?: throw IOException("Unable to open the selected destination.")
        output.use { stream ->
            var transferred = 0L
            requireClient().sync.pull(remotePath, stream, onProgress = { transferred = it })
            "Pulled $transferred bytes from $remotePath"
        }
    }

    suspend fun addForward(localPort: Int, devicePort: Int): String = withContext(Dispatchers.IO) {
        validatePort(localPort)
        validatePort(devicePort)
        requireClient().forward.add("tcp:$localPort", "tcp:$devicePort")
        "Forward active: 127.0.0.1:$localPort → device:$devicePort"
    }

    suspend fun removeForward(localPort: Int): String = withContext(Dispatchers.IO) {
        validatePort(localPort)
        requireClient().forward.remove("tcp:$localPort")
        "Forward removed: 127.0.0.1:$localPort"
    }

    suspend fun addReverse(devicePort: Int, hostPort: Int): String = withContext(Dispatchers.IO) {
        validatePort(devicePort)
        validatePort(hostPort)
        requireClient().reverse.add("tcp:$devicePort", "tcp:$hostPort")
        "Reverse active: device:$devicePort → 127.0.0.1:$hostPort"
    }

    suspend fun removeReverse(devicePort: Int): String = withContext(Dispatchers.IO) {
        validatePort(devicePort)
        requireClient().reverse.remove("tcp:$devicePort")
        "Reverse removed: device:$devicePort"
    }

    private fun requireClient(): AdbClient =
        activeClient?.takeIf { !it.isClosed }
            ?: throw IllegalStateException("No authenticated ADB transport.")

    private fun validatePort(port: Int) {
        require(port in 1..65535) { "TCP port must be between 1 and 65535." }
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

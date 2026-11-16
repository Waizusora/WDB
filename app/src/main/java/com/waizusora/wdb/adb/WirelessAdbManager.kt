package com.waizusora.wdb.adb

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import java.io.Closeable
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import java.io.IOException
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Coordinates secure Wireless Debugging pairing, mDNS discovery, and ADB TLS sessions.
 *
 * The client is retained at application scope so Activity recreation does not close
 * an active session. A transport is considered connected only after a Shell V2
 * handshake succeeds.
 */
data class PortForwardRule(val reverse: Boolean, val sourcePort: Int, val destinationPort: Int)

class WirelessAdbManager(
    context: Context,
    private val keyProvider: AdbKeyProvider
) : Closeable {
    private val appContext = context.applicationContext
    private val discovery = WirelessAdbDiscovery(appContext)
    val discoveryState = discovery.state

    @Volatile
    private var activeClient: AdbClient? = null

    /**
     * Serializes connection publication and teardown without holding the lock during
     * network I/O. A generation invalidates handshakes that finish after a newer
     * connect, disconnect, or manager close has taken ownership of the lifecycle.
     */
    private val connectionLock = Any()
    private var connectionGeneration = 0L
    private var managerClosed = false

    private val _connectedModel = MutableStateFlow<String?>(null)
    val connectedModel = _connectedModel.asStateFlow()
    private val _portRules = MutableStateFlow<List<PortForwardRule>>(emptyList())
    val portRules = _portRules.asStateFlow()

    fun startDiscovery() = discovery.start()

    fun refreshDiscovery() = discovery.restart()

    suspend fun pair(host: String, port: Int, pairingCode: String) = withContext(Dispatchers.IO) {
        AdbEndpointValidator.validate(host, port)
        require(pairingCode.matches(Regex("[0-9]{6}"))) {
            "The Wireless debugging pairing code must contain exactly six digits."
        }
        val endpointHost = resolveLocalEndpoint(host)
        AdbClient.pairTls(
            host = endpointHost,
            port = port,
            pairingCode = pairingCode,
            keyProvider = keyProvider
        ).getOrThrow()
        Unit
    }

    /**
     * Android Wireless Debugging runs on this device. When the entered address belongs
     * to one of this device's network interfaces, route the pairing socket through
     * loopback instead of the Wi-Fi interface (which may reject self-connections).
     * Remote device addresses are left unchanged.
     */
    private fun resolveLocalEndpoint(host: String): String {
        return runCatching {
            val target = InetAddress.getByName(host)
            val localAddresses = Collections.list(NetworkInterface.getNetworkInterfaces())
                .flatMap { network -> Collections.list(network.inetAddresses) }
            if (localAddresses.any { it.address.contentEquals(target.address) }) {
                if (target.address.size == 16) "::1" else "127.0.0.1"
            } else {
                host
            }
        }.getOrDefault(host)
    }

    suspend fun connect(host: String, port: Int): String = withContext(Dispatchers.IO) {
        AdbEndpointValidator.validate(host, port)
        val attemptGeneration = synchronized(connectionLock) {
            check(!managerClosed) { "ADB manager is already closed." }
            ++connectionGeneration
        }
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

            val previous = synchronized(connectionLock) {
                check(!managerClosed && connectionGeneration == attemptGeneration) {
                    "ADB connection attempt was superseded by a newer lifecycle operation."
                }
                activeClient.also {
                    activeClient = candidate
                    _connectedModel.value = model
                    _portRules.value = emptyList()
                }
            }
            runCatching { previous?.close() }
            model
        } catch (error: Throwable) {
            runCatching { candidate.close() }
            throw error
        }
    }

    suspend fun execute(command: String): String = withContext(Dispatchers.IO) {
        require(command.isNotBlank()) { "Command must not be empty." }
        val client = requireClient()
        withActiveClient(client) { active ->
            val result = active.shell(command)
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
    }


    suspend fun installApks(uris: List<Uri>, onProgress: (String) -> Unit = {}): String = withContext(Dispatchers.IO) {
        require(uris.isNotEmpty()) { "Select at least one APK file." }
        val client = requireClient()
        withActiveClient(client) { active ->
        val selectedName = displayName(uris.singleOrNull())
        val archiveUri = uris.singleOrNull()?.takeIf { selectedName?.substringAfterLast(".", "")?.lowercase() in setOf("apks", "xapk", "apkm", "zip") }
        if (archiveUri != null) {
            val extracted = extractSplitApks(archiveUri)
            try {
                val sources = extracted.map { file -> file.name to RandomAccessSource.of(file) }
                var latest = "Preparing split APK installation…"
                client.install.installMultiple(sources, flags = listOf("-r")).collect { progress ->
                    latest = "${progress.statusText} (${progress.percentage.toInt()}%)"
                    ensureClientActive(active)
                    onProgress(latest)
                }
                latest
            } finally { extracted.forEach { runCatching { it.delete() } } }
        } else {
            val descriptors = mutableListOf<android.os.ParcelFileDescriptor>()
            try {
                val sources = uris.mapIndexed { index, uri ->
                    val descriptor = appContext.contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("Unable to open selected APK file.")
                    descriptors += descriptor
                    if (descriptor.statSize <= 0L) throw IOException("Selected APK has no readable file size.")
                    val name = displayName(uri) ?: "split_$index.apk"
                    if (!name.endsWith(".apk", ignoreCase = true)) throw IOException("Select APK files, or one .apks/.xapk/.apkm archive.")
                    name to RandomAccessSource.of(descriptor.fileDescriptor, descriptor.statSize)
                }.sortedBy { if (it.first.contains("base", ignoreCase = true)) 0 else 1 }
                var latest = "Preparing APK installation…"
                val flow = if (sources.size == 1) client.install.install(sources.single().second, flags = listOf("-r")) else client.install.installMultiple(sources, flags = listOf("-r"))
                flow.collect { progress -> latest = "${progress.statusText} (${progress.percentage.toInt()}%)"; ensureClientActive(active); onProgress(latest) }
                latest
            } finally { descriptors.forEach { runCatching { it.close() } } }
        }
        }
    }

    private fun displayName(uri: Uri?): String? {
        if (uri == null) return null
        appContext.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) { val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME); if (index >= 0) return cursor.getString(index) }
        }
        return uri.lastPathSegment
    }

    private fun extractSplitApks(uri: Uri): List<File> {
        val directory = File(appContext.cacheDir, "wdb-split-apks")
        directory.mkdirs()
        directory.listFiles()?.forEach { it.delete() }
        val extracted = mutableListOf<File>()
        var totalBytes = 0L
        val input = appContext.contentResolver.openInputStream(uri) ?: throw IOException("Unable to open the split APK archive.")
        input.use { raw -> ZipInputStream(raw).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                val lower = name.lowercase()
                if (!entry.isDirectory && lower.endsWith(".apk") && !lower.startsWith("standalones/") && !lower.contains("/standalones/")) {
                    if (extracted.size >= 32) throw IOException("Split archive contains more than 32 APK files.")
                    val safeName = name.substringAfterLast("/").replace(Regex("[^A-Za-z0-9._-]"), "_")
                    val target = File(directory, "${extracted.size}_$safeName")
                    target.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) { val count = zip.read(buffer); if (count < 0) break; totalBytes += count; if (totalBytes > 1_500_000_000L) throw IOException("Split APK archive exceeds the 1.5 GB safety limit."); output.write(buffer, 0, count) }
                    }
                    if (target.length() > 0) extracted += target else target.delete()
                }
                zip.closeEntry()
            }
        } }
        if (extracted.isEmpty()) throw IOException("No installable APK files were found in the selected archive.")
        return extracted.sortedBy { if (it.name.contains("base", ignoreCase = true)) 0 else 1 }
    }
    suspend fun uninstallPackage(packageName: String): String = withContext(Dispatchers.IO) {
        require(packageName.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))) {
            "Enter a valid Android package name."
        }
        val client = requireClient()
        withActiveClient(client) { active ->
            val result = active.shell("pm uninstall $packageName")
            if (!result.isSuccess || !result.stdoutText.trim().startsWith("Success")) {
                throw IllegalStateException(result.stderrText.ifBlank { result.stdoutText }.ifBlank { "Package uninstall failed." })
            }
            result.stdoutText.trim()
        }
    }

    suspend fun pushFile(uri: Uri, remotePath: String, onProgress: (Long) -> Unit = {}): String = withContext(Dispatchers.IO) {
        require(remotePath.startsWith("/") && remotePath.none { it.code == 0 }) {
            "Remote destination must be an absolute device path."
        }
        val input = appContext.contentResolver.openInputStream(uri)
            ?: throw IOException("Unable to open the selected source file.")
        input.use { stream ->
            val client = requireClient()
            withActiveClient(client) { active ->
                var transferred = 0L
                val temporaryPath = remotePath + ".wdb-" + java.util.UUID.randomUUID() + ".part"
                try {
                    active.sync.push(stream, temporaryPath, onProgress = { transferred = it; ensureClientActive(active); onProgress(it) })
                    ensureClientActive(active)
                    val move = active.shell("mv -f -- " + shellQuote(temporaryPath) + " " + shellQuote(remotePath))
                    if (!move.isSuccess) {
                        throw IllegalStateException(move.stderrText.ifBlank { move.stdoutText }.ifBlank { "Unable to finalize the uploaded file." })
                    }
                    "Pushed $transferred bytes to $remotePath"
                } catch (error: Throwable) {
                    runCatching { active.shell("rm -f -- " + shellQuote(temporaryPath)) }
                    throw error
                }
            }
        }
    }
    suspend fun pullFile(remotePath: String, destination: Uri, onProgress: (Long) -> Unit = {}): String = withContext(Dispatchers.IO) {
        require(remotePath.startsWith("/") && remotePath.none { it.code == 0 }) {
            "Remote source must be an absolute device path."
        }
        try {
            val output = appContext.contentResolver.openOutputStream(destination, "wt")
                ?: throw IOException("Unable to open the selected destination.")
            output.use { stream ->
                val client = requireClient()
                withActiveClient(client) { active ->
                    var transferred = 0L
                    active.sync.pull(remotePath, stream, onProgress = { transferred = it; ensureClientActive(active); onProgress(it) })
                    "Pulled $transferred bytes from $remotePath"
                }
            }
        } catch (error: Throwable) {
            // A cancelled or failed sync must not leave a truncated file that looks complete.
            runCatching { DocumentsContract.deleteDocument(appContext.contentResolver, destination) }
            throw error
        }
    }

    suspend fun pullFileToDirectory(remotePath: String, treeUri: Uri, onProgress: (Long) -> Unit = {}): String = withContext(Dispatchers.IO) {
        require(remotePath.startsWith("/") && remotePath.none { it.code == 0 }) { "Remote source must be an absolute device path." }
        val directory = DocumentFile.fromTreeUri(appContext, treeUri) ?: throw IOException("The saved transfer directory is no longer accessible.")
        if (!directory.isDirectory || !directory.canWrite()) throw IOException("The selected transfer directory is not writable.")
        val fileName = remotePath.substringAfterLast("/")
            .ifBlank { "adb-download.bin" }
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .takeUnless { it == "." || it == ".." }
            ?: "adb-download.bin"
        val temporaryName = ".wdb-" + java.util.UUID.randomUUID() + ".part"
        val destination = directory.createFile("application/octet-stream", temporaryName)
            ?: throw IOException("Unable to create a temporary file in the selected transfer directory.")
        try {
            val output = appContext.contentResolver.openOutputStream(destination.uri, "wt")
                ?: throw IOException("Unable to open the temporary destination file.")
            val client = requireClient()
            var transferred = 0L
            output.use { stream ->
                withActiveClient(client) { active ->
                    active.sync.pull(remotePath, stream, onProgress = { transferred = it; ensureClientActive(active); onProgress(it) })
                }
            }
            // Publish the final name only after the stream has closed successfully.
            ensureClientActive(client)
            if (!destination.renameTo(fileName)) {
                throw IllegalStateException("Transfer completed, but the temporary file could not be renamed to $fileName.")
            }
            "Pulled $transferred bytes to $fileName"
        } catch (error: Throwable) {
            // A failed or cancelled transfer never remains under its final filename.
            runCatching { destination.delete() }
            throw error
        }
    }
    suspend fun addForward(localPort: Int, devicePort: Int): String = withContext(Dispatchers.IO) {
        validatePort(localPort)
        validatePort(devicePort)
        val client = requireClient()
        withActiveClient(client) { active ->
            active.forward.add("tcp:$localPort", "tcp:$devicePort")
            updatePortRules(active) { rules ->
                rules.filterNot { !it.reverse && it.sourcePort == localPort } + PortForwardRule(false, localPort, devicePort)
            }
            "Forward active: 127.0.0.1:$localPort → device:$devicePort"
        }
    }

    suspend fun removeForward(localPort: Int): String = withContext(Dispatchers.IO) {
        validatePort(localPort)
        val client = requireClient()
        withActiveClient(client) { active ->
            active.forward.remove("tcp:$localPort")
            updatePortRules(active) { rules -> rules.filterNot { !it.reverse && it.sourcePort == localPort } }
            "Forward removed: 127.0.0.1:$localPort"
        }
    }

    suspend fun addReverse(devicePort: Int, hostPort: Int): String = withContext(Dispatchers.IO) {
        validatePort(devicePort)
        validatePort(hostPort)
        val client = requireClient()
        withActiveClient(client) { active ->
            active.reverse.add("tcp:$devicePort", "tcp:$hostPort")
            updatePortRules(active) { rules ->
                rules.filterNot { it.reverse && it.sourcePort == devicePort } + PortForwardRule(true, devicePort, hostPort)
            }
            "Reverse active: device:$devicePort → 127.0.0.1:$hostPort"
        }
    }

    suspend fun removeReverse(devicePort: Int): String = withContext(Dispatchers.IO) {
        validatePort(devicePort)
        val client = requireClient()
        withActiveClient(client) { active ->
            active.reverse.remove("tcp:$devicePort")
            updatePortRules(active) { rules -> rules.filterNot { it.reverse && it.sourcePort == devicePort } }
            "Reverse removed: device:$devicePort"
        }
    }

    /**
     * Runs an operation against the exact client captured at its start. Late
     * completions cannot be reported as successful after disconnect/reconnect.
     * Cancellation or transport I/O failure retires only that client identity.
     */
    private suspend fun <T> withActiveClient(
        client: AdbClient,
        operation: suspend (AdbClient) -> T
    ): T {
        try {
            val result = operation(client)
            currentCoroutineContext().ensureActive()
            synchronized(connectionLock) {
                if (activeClient !== client || client.isClosed) {
                    throw CancellationException("ADB operation was superseded by a connection lifecycle change.")
                }
            }
            return result
        } catch (error: Throwable) {
            if (error is CancellationException || error is IOException || client.isClosed) {
                retireClient(client)
            }
            throw error
        }
    }

    private fun retireClient(client: AdbClient) {
        val retired = synchronized(connectionLock) {
            if (activeClient === client) {
                activeClient = null
                _connectedModel.value = null
                _portRules.value = emptyList()
                true
            } else {
                false
            }
        }
        if (retired) runCatching { client.close() }
    }

    private fun ensureClientActive(client: AdbClient) {
        synchronized(connectionLock) {
            if (activeClient !== client || client.isClosed) {
                throw CancellationException("ADB operation was superseded by a connection lifecycle change.")
            }
        }
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun requireClient(): AdbClient = synchronized(connectionLock) {
        activeClient?.takeIf { !it.isClosed }
            ?: throw IllegalStateException("No authenticated ADB transport.")
    }

    /**
     * A command may finish after disconnect or reconnect. Only the client that is
     * still active may publish connection-scoped state such as port-forward rules.
     */
    private inline fun updatePortRules(
        client: AdbClient,
        transform: (List<PortForwardRule>) -> List<PortForwardRule>
    ) {
        synchronized(connectionLock) {
            if (activeClient === client && !client.isClosed) {
                _portRules.value = transform(_portRules.value)
            }
        }
    }

    private fun validatePort(port: Int) {
        require(port in 1..65535) { "TCP port must be between 1 and 65535." }
    }

    fun logcatFlow(): Flow<ShellChunk> {
        // Capture under the lifecycle lock so a concurrent disconnect cannot expose
        // a partially published or already-detached client to a new stream.
        val client = requireClient()
        return client.shellFlow("logcat -b all -v threadtime").flowOn(Dispatchers.IO)
    }

    suspend fun disconnect() {
        // Invalidate in-flight TLS handshakes before dispatching socket teardown.
        val client = synchronized(connectionLock) {
            ++connectionGeneration
            activeClient.also {
                activeClient = null
                _connectedModel.value = null
                _portRules.value = emptyList()
            }
        }
        withContext(Dispatchers.IO) {
            runCatching { client?.close() }
        }
    }

    fun isConnected(): Boolean = synchronized(connectionLock) {
        val connected = activeClient?.isClosed == false
        if (!connected) {
            activeClient = null
            _connectedModel.value = null
            _portRules.value = emptyList()
        }
        connected
    }

    override fun close() {
        val client = synchronized(connectionLock) {
            managerClosed = true
            ++connectionGeneration
            activeClient.also {
                activeClient = null
                _connectedModel.value = null
                _portRules.value = emptyList()
            }
        }
        runCatching { client?.close() }
        discovery.close()
    }


    private companion object {
        const val CONNECT_TIMEOUT_MS = 30_000L
    }
}

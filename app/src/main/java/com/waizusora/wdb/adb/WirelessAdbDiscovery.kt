package com.waizusora.wdb.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable

data class AdbEndpoint(
    val name: String,
    val host: String,
    val port: Int
)

data class WirelessDiscoveryState(
    val pairDevices: List<AdbEndpoint> = emptyList(),
    val connectDevices: List<AdbEndpoint> = emptyList(),
    val status: String = "Not started"
)

/**
 * Discovers Android Wireless Debugging pairing and TLS connection endpoints.
 *
 * Each discovery run owns distinct listeners and a generation token. Delayed
 * callbacks from a stopped run are ignored so they cannot repopulate stale
 * endpoints after refresh or shutdown.
 */
class WirelessAdbDiscovery(context: Context) : Closeable {
    private val nsdManager = context.applicationContext.getSystemService(NsdManager::class.java)
    private val lock = Any()
    private val endpoints = linkedMapOf<String, AdbEndpoint>()
    private val _state = MutableStateFlow(WirelessDiscoveryState())
    val state = _state.asStateFlow()

    private var started = false
    private var generation = 0L
    private var pairingListener: NsdManager.DiscoveryListener? = null
    private var connectListener: NsdManager.DiscoveryListener? = null

    fun start() {
        val token = synchronized(lock) {
            if (started) return
            started = true
            generation += 1
            endpoints.clear()
            _state.value = WirelessDiscoveryState(
                status = "Scanning for Wireless debugging services…"
            )
            generation
        }

        val pairing = createListener(pairing = true, token = token)
        val connect = createListener(pairing = false, token = token)
        synchronized(lock) {
            if (!isCurrentLocked(token)) return
            pairingListener = pairing
            connectListener = connect
        }

        try {
            nsdManager.discoverServices(
                PAIRING_SERVICE,
                NsdManager.PROTOCOL_DNS_SD,
                pairing
            )
        } catch (error: Exception) {
            updateStatus(token, "Pairing discovery failed: ${error.message}")
        }

        try {
            nsdManager.discoverServices(
                CONNECT_SERVICE,
                NsdManager.PROTOCOL_DNS_SD,
                connect
            )
        } catch (error: Exception) {
            updateStatus(token, "Connection discovery failed: ${error.message}")
        }
    }

    private fun createListener(
        pairing: Boolean,
        token: Long
    ) = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            updateStatus(token, "Discovering pairing and ADB TLS endpoints")
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            updateStatus(
                token,
                "mDNS discovery failed ($serviceType, code $errorCode)"
            )
            runCatching { nsdManager.stopServiceDiscovery(this) }
        }

        override fun onDiscoveryStopped(serviceType: String) = Unit

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            updateStatus(token, "Could not stop mDNS discovery ($errorCode)")
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            if (isCurrent(token)) resolve(serviceInfo, pairing, token)
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            if (isCurrent(token)) removeEndpoint(pairing, serviceInfo.serviceName, token)
        }
    }

    @Suppress("DEPRECATION")
    private fun resolve(
        serviceInfo: NsdServiceInfo,
        pairing: Boolean,
        token: Long
    ) {
        try {
            nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    updateStatus(
                        token,
                        "Could not resolve ${serviceInfo.serviceName} ($errorCode)"
                    )
                }

                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    val host = resolved.host?.hostAddress ?: return
                    val port = resolved.port
                    if (port !in 1..65535) return

                    val endpoint = AdbEndpoint(resolved.serviceName, host, port)
                    synchronized(lock) {
                        if (!isCurrentLocked(token)) return
                        endpoints[key(pairing, endpoint.name)] = endpoint
                        publishStateLocked()
                    }
                }
            })
        } catch (error: Exception) {
            updateStatus(token, "Could not resolve ${serviceInfo.serviceName}: ${error.message}")
        }
    }

    private fun removeEndpoint(pairing: Boolean, serviceName: String, token: Long) {
        synchronized(lock) {
            if (!isCurrentLocked(token)) return
            endpoints.remove(key(pairing, serviceName))
            publishStateLocked()
        }
    }

    private fun key(pairing: Boolean, serviceName: String) =
        (if (pairing) "pair:" else "connect:") + serviceName

    private fun isCurrent(token: Long): Boolean =
        synchronized(lock) { isCurrentLocked(token) }

    private fun isCurrentLocked(token: Long): Boolean =
        started && generation == token

    private fun updateStatus(token: Long, status: String) {
        synchronized(lock) {
            if (!isCurrentLocked(token)) return
            _state.value = _state.value.copy(status = status)
        }
    }

    private fun publishStateLocked() {
        _state.value = WirelessDiscoveryState(
            pairDevices = endpoints.filterKeys { it.startsWith("pair:") }.values.toList(),
            connectDevices = endpoints.filterKeys { it.startsWith("connect:") }.values.toList(),
            status = _state.value.status
        )
    }

    fun restart() {
        close()
        start()
    }

    override fun close() {
        val listeners = synchronized(lock) {
            if (!started) return
            started = false
            generation += 1
            val active = listOfNotNull(pairingListener, connectListener)
            pairingListener = null
            connectListener = null
            endpoints.clear()
            _state.value = WirelessDiscoveryState(status = "Stopped")
            active
        }
        listeners.forEach { listener ->
            runCatching { nsdManager.stopServiceDiscovery(listener) }
        }
    }

    private companion object {
        const val PAIRING_SERVICE = "_adb-tls-pairing._tcp."
        const val CONNECT_SERVICE = "_adb-tls-connect._tcp."
    }
}

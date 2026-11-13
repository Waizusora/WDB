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
 * Android's NsdManager is used directly so endpoint discovery does not depend
 * on an additional native daemon or a proprietary discovery service.
 */
class WirelessAdbDiscovery(context: Context) : Closeable {
    private val nsdManager = context.applicationContext.getSystemService(NsdManager::class.java)
    private val lock = Any()
    private val endpoints = linkedMapOf<String, AdbEndpoint>()
    private val _state = MutableStateFlow(WirelessDiscoveryState())
    val state = _state.asStateFlow()

    @Volatile
    private var started = false

    private val pairingListener = createListener(true)
    private val connectListener = createListener(false)

    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
            _state.value = _state.value.copy(status = "Scanning for Wireless debugging services…")
        }
        runCatching {
            nsdManager.discoverServices(PAIRING_SERVICE, NsdManager.PROTOCOL_DNS_SD, pairingListener)
        }.onFailure { updateStatus("Pairing discovery failed: " + it.message) }
        runCatching {
            nsdManager.discoverServices(CONNECT_SERVICE, NsdManager.PROTOCOL_DNS_SD, connectListener)
        }.onFailure { updateStatus("Connection discovery failed: " + it.message) }
    }

    private fun createListener(pairing: Boolean) =
        object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                updateStatus("Discovering pairing and ADB TLS endpoints")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                updateStatus("mDNS discovery failed (" + serviceType + ", code " + errorCode + ")")
                runCatching { nsdManager.stopServiceDiscovery(this) }
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                updateStatus("Could not stop mDNS discovery (" + errorCode + ")")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                resolve(serviceInfo, pairing)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                removeEndpoint(pairing, serviceInfo.serviceName)
            }
        }

    @Suppress("DEPRECATION")
    private fun resolve(serviceInfo: NsdServiceInfo, pairing: Boolean) {
        nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                updateStatus("Could not resolve " + serviceInfo.serviceName + " (" + errorCode + ")")
            }

            override fun onServiceResolved(resolved: NsdServiceInfo) {
                val host = resolved.host?.hostAddress ?: return
                val port = resolved.port
                if (port !in 1..65535) return
                val endpoint = AdbEndpoint(resolved.serviceName, host, port)
                synchronized(lock) {
                    endpoints[key(pairing, endpoint.name)] = endpoint
                    publishStateLocked()
                }
            }
        })
    }

    private fun removeEndpoint(pairing: Boolean, serviceName: String) {
        synchronized(lock) {
            endpoints.remove(key(pairing, serviceName))
            publishStateLocked()
        }
    }

    private fun key(pairing: Boolean, serviceName: String) =
        (if (pairing) "pair:" else "connect:") + serviceName

    private fun updateStatus(status: String) {
        synchronized(lock) {
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
        synchronized(lock) {
            if (!started) return
            started = false
        }
        runCatching { nsdManager.stopServiceDiscovery(pairingListener) }
        runCatching { nsdManager.stopServiceDiscovery(connectListener) }
        synchronized(lock) {
            endpoints.clear()
            _state.value = WirelessDiscoveryState(status = "Stopped")
        }
    }

    private companion object {
        const val PAIRING_SERVICE = "_adb-tls-pairing._tcp."
        const val CONNECT_SERVICE = "_adb-tls-connect._tcp."
    }
}

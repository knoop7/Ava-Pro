package com.example.ava.homeassistant

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * On-demand mDNS browse for local Home Assistant instances via `_home-assistant._tcp`.
 * Settings-page only: does not start until [start]; [stop] on leave.
 */
class HaDiscovery(context: Context) {
    data class HaInstance(
        val name: String,
        val host: String,
        val port: Int = DEFAULT_PORT,
    ) {
        val baseUrl: String get() = "http://$host:$port"
    }

    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _instances = MutableStateFlow<List<HaInstance>>(emptyList())
    val instances = _instances.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning = _scanning.asStateFlow()

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var isResolving = false
    private val resolved = ConcurrentHashMap<String, HaInstance>()
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start() {
        runOnMain {
            if (discoveryListener != null) return@runOnMain
            Log.i(TAG, "Starting HA discovery ($SERVICE_TYPE)")
            acquireMulticastLock()
            resolved.clear()
            _instances.value = emptyList()
            resolveQueue.clear()
            isResolving = false
            _scanning.value = true

            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) {
                    Log.i(TAG, "Discovery started: $regType")
                }

                override fun onServiceFound(service: NsdServiceInfo) {
                    synchronized(resolveQueue) {
                        resolveQueue.add(service)
                        if (!isResolving) resolveNextLocked()
                    }
                }

                override fun onServiceLost(service: NsdServiceInfo) {
                    resolved.remove(service.serviceName)
                    _instances.value = resolved.values.sortedBy { it.name.lowercase() }
                }

                override fun onDiscoveryStopped(regType: String) {
                    _scanning.value = false
                    discoveryListener = null
                    releaseMulticastLock()
                }

                override fun onStartDiscoveryFailed(regType: String, errorCode: Int) {
                    Log.e(TAG, "Start discovery failed: $errorCode")
                    _scanning.value = false
                    discoveryListener = null
                    releaseMulticastLock()
                }

                override fun onStopDiscoveryFailed(regType: String, errorCode: Int) {
                    Log.e(TAG, "Stop discovery failed: $errorCode")
                }
            }
            discoveryListener = listener
            try {
                nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (e: Exception) {
                Log.e(TAG, "discoverServices failed: ${e.message}")
                discoveryListener = null
                _scanning.value = false
                releaseMulticastLock()
            }
        }
    }

    fun stop() {
        runOnMain {
            val listener = discoveryListener ?: return@runOnMain
            try {
                nsdManager.stopServiceDiscovery(listener)
            } catch (e: Exception) {
                Log.w(TAG, "stopServiceDiscovery: ${e.message}")
                discoveryListener = null
                _scanning.value = false
                releaseMulticastLock()
            }
        }
    }

    private fun resolveNextLocked() {
        val next = resolveQueue.removeFirstOrNull()
        if (next == null) {
            isResolving = false
            return
        }
        isResolving = true
        try {
            nsdManager.resolveService(
                next,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "Resolve failed ${serviceInfo.serviceName}: $errorCode")
                        synchronized(resolveQueue) { resolveNextLocked() }
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val host = serviceInfo.host?.hostAddress
                        if (!host.isNullOrBlank()) {
                            val instance = HaInstance(
                                name = serviceInfo.serviceName.ifBlank { host },
                                host = host,
                                port = serviceInfo.port.takeIf { it > 0 } ?: DEFAULT_PORT,
                            )
                            resolved[serviceInfo.serviceName] = instance
                            _instances.value = resolved.values.sortedBy { it.name.lowercase() }
                        }
                        synchronized(resolveQueue) { resolveNextLocked() }
                    }
                },
            )
        } catch (e: Exception) {
            Log.w(TAG, "resolveService: ${e.message}")
            resolveNextLocked()
        }
    }

    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        try {
            val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("Ava::HaDiscovery").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "multicast lock: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.takeIf { it.isHeld }?.release()
        } catch (_: Exception) {
        } finally {
            multicastLock = null
        }
    }

    private inline fun runOnMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post { block() }
    }

    companion object {
        private const val TAG = "HaDiscovery"
        private const val SERVICE_TYPE = "_home-assistant._tcp."
        const val DEFAULT_PORT = 8123
    }
}

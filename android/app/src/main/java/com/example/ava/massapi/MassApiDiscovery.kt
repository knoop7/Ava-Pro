package com.example.ava.massapi

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
 * On-demand browse for local MA **API** hosts via `_sendspin-server._tcp` → `http://host:8095`.
 * Settings-page only: does not start until [start]; [stop] on leave.
 *
 * Does not talk to [com.example.ava.sendspin.SendspinClient] / calibrate / audio.
 * Keep scans short so NSD/multicast does not contend with Sendspin discovery.
 */
class MassApiDiscovery(context: Context) {
    data class MassServer(
        val name: String,
        val host: String,
        val port: Int = DEFAULT_API_PORT,
    ) {
        val baseUrl: String get() = "http://$host:$port"
    }

    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _servers = MutableStateFlow<List<MassServer>>(emptyList())
    val servers = _servers.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning = _scanning.asStateFlow()

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var isResolving = false
    private val resolved = ConcurrentHashMap<String, MassServer>()
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start() {
        runOnMain {
            if (discoveryListener != null) return@runOnMain
            Log.i(TAG, "Starting MA discovery ($SERVICE_TYPE)")
            acquireMulticastLock()
            resolved.clear()
            _servers.value = emptyList()
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
                    _servers.value = resolved.values.sortedBy { it.name.lowercase() }
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
                            val server = MassServer(
                                name = serviceInfo.serviceName.ifBlank { host },
                                host = host,
                                port = DEFAULT_API_PORT,
                            )
                            resolved[serviceInfo.serviceName] = server
                            _servers.value = resolved.values.sortedBy { it.name.lowercase() }
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
            multicastLock = wifi.createMulticastLock("Ava::MassApiDiscovery").apply {
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
        private const val TAG = "MassApiDiscovery"
        private const val SERVICE_TYPE = "_sendspin-server._tcp."
        const val DEFAULT_API_PORT = 8095
    }
}

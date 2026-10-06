package com.example.ava.sendspin

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
 * mDNS helper for Sendspin:
 * - Advertise this device as `_sendspin._tcp` (MA browses and connects in).
 * - Optionally discover `_sendspin-server._tcp` for silent outbound fallback.
 *
 * Holds a [WifiManager.MulticastLock] while advertising or discovering so OEM
 * Wi-Fi stacks do not filter mDNS (aligned with [com.example.ava.nsd.NsdRegistration]).
 */
class SendspinDiscovery(private val context: Context) {
    data class SendspinServer(
        val name: String,
        val host: String,
        val port: Int,
        val path: String = "/sendspin"
    ) {
        val wsUrl: String get() = "ws://$host:$port$path"
    }

    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _discoveredServers = MutableStateFlow<List<SendspinServer>>(emptyList())
    val discoveredServers = _discoveredServers.asStateFlow()

    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising = _isAdvertising.asStateFlow()

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var advertisedPort: Int? = null
    private var advertisedName: String? = null
    /** Last requested advertise identity (survives failed registration / stop). */
    private var desiredPort: Int? = null
    private var desiredName: String? = null

    private var advertiseRetryAttempt = 0
    private var advertiseRetryRunnable: Runnable? = null
    private var closed = false

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var discovering = false
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var isResolving = false
    private val resolvedServers = ConcurrentHashMap<String, SendspinServer>()

    private var multicastLock: WifiManager.MulticastLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var networkRefreshRunnable: Runnable? = null

    fun startDiscovery() {
        runOnMain {
            if (closed || discovering) return@runOnMain
            Log.i(TAG, "Starting mDNS discovery for $SERVICE_TYPE_SERVER")
            acquireMulticastLock()
            resolvedServers.clear()
            _discoveredServers.value = emptyList()
            resolveQueue.clear()
            isResolving = false

            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) {
                    discovering = true
                    Log.i(TAG, "Discovery started for $regType")
                }

                override fun onServiceFound(service: NsdServiceInfo) {
                    Log.d(TAG, "Service found: ${service.serviceName} type=${service.serviceType}")
                    synchronized(resolveQueue) {
                        resolveQueue.add(service)
                        if (!isResolving) resolveNextLocked()
                    }
                }

                override fun onServiceLost(service: NsdServiceInfo) {
                    resolvedServers.remove(service.serviceName)
                    _discoveredServers.value = resolvedServers.values.toList()
                }

                override fun onDiscoveryStopped(regType: String) {
                    discovering = false
                    Log.i(TAG, "Discovery stopped for $regType")
                    releaseMulticastLockIfIdle()
                }

                override fun onStartDiscoveryFailed(regType: String, errorCode: Int) {
                    Log.e(TAG, "Start discovery failed: error=$errorCode")
                    discovering = false
                    discoveryListener = null
                    releaseMulticastLockIfIdle()
                }

                override fun onStopDiscoveryFailed(regType: String, errorCode: Int) {
                    Log.e(TAG, "Stop discovery failed: error=$errorCode")
                }
            }
            discoveryListener = listener
            try {
                nsdManager.discoverServices(
                    SERVICE_TYPE_SERVER,
                    NsdManager.PROTOCOL_DNS_SD,
                    listener
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start sendspin-server discovery", e)
                discoveryListener = null
                discovering = false
                releaseMulticastLockIfIdle()
            }
        }
    }

    fun stopDiscovery() {
        runOnMain {
            stopDiscoveryInternal()
        }
    }

    /**
     * Re-register the current advertise record (e.g. after network change).
     * Uses [desiredPort]/[desiredName] so a failed prior register can still refresh.
     */
    fun resetDiscovery() {
        runOnMain {
            val port = desiredPort ?: advertisedPort ?: return@runOnMain
            val name = desiredName ?: advertisedName ?: return@runOnMain
            Log.i(TAG, "Refreshing sendspin advertise after network change ($name:$port)")
            stopAdvertisingInternal(clearDesired = false)
            startAdvertising(port, name)
        }
    }

    fun getServerUrl(name: String): String? =
        _discoveredServers.value.find { it.name == name }?.wsUrl

    fun startAdvertising(port: Int, name: String) {
        runOnMain {
            if (closed) return@runOnMain
            desiredPort = port
            desiredName = name
            if (registrationListener != null &&
                advertisedPort == port &&
                advertisedName == name &&
                _isAdvertising.value
            ) {
                return@runOnMain
            }

            cancelAdvertiseRetry()
            stopAdvertisingInternal(clearDesired = false)
            acquireMulticastLock()
            ensureNetworkCallback()
            advertiseRetryAttempt = 0
            doRegisterAdvertise(port, name)
        }
    }

    fun stopAdvertising() {
        runOnMain {
            cancelAdvertiseRetry()
            stopAdvertisingInternal(clearDesired = true)
            releaseMulticastLockIfIdle()
            // Keep network callback only while we still intend to advertise.
            if (desiredPort == null) {
                unregisterNetworkCallback()
            }
        }
    }

    fun close() {
        runOnMain {
            closed = true
            cancelAdvertiseRetry()
            cancelNetworkRefresh()
            stopDiscoveryInternal()
            stopAdvertisingInternal(clearDesired = true)
            unregisterNetworkCallback()
            releaseMulticastLock()
        }
    }

    private fun doRegisterAdvertise(port: Int, name: String) {
        if (closed) return
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = name
            serviceType = SERVICE_TYPE_CLIENT
            this.port = port
            setAttribute("path", DEFAULT_PATH)
            setAttribute("name", name)
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(nsdServiceInfo: NsdServiceInfo) {
                advertisedPort = port
                advertisedName = nsdServiceInfo.serviceName
                advertiseRetryAttempt = 0
                cancelAdvertiseRetry()
                _isAdvertising.value = true
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                _isAdvertising.value = false
                Log.e(TAG, "Sendspin client advertise failed: error=$errorCode attempt=$advertiseRetryAttempt")
                registrationListener = null
                scheduleAdvertiseRetry()
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                _isAdvertising.value = false
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                _isAdvertising.value = false
                Log.e(TAG, "Sendspin client unregistration failed: error=$errorCode")
                registrationListener = null
            }
        }

        try {
            nsdManager.registerService(
                serviceInfo,
                NsdManager.PROTOCOL_DNS_SD,
                registrationListener
            )
        } catch (e: Exception) {
            _isAdvertising.value = false
            registrationListener = null
            Log.e(TAG, "Failed to advertise sendspin client", e)
            scheduleAdvertiseRetry()
        }
    }

    private fun scheduleAdvertiseRetry() {
        if (closed) return
        val port = desiredPort ?: return
        val name = desiredName ?: return
        if (advertiseRetryAttempt >= MAX_ADVERTISE_RETRIES) {
            Log.e(TAG, "Sendspin advertise gave up after $MAX_ADVERTISE_RETRIES attempts")
            releaseMulticastLockIfIdle()
            return
        }
        cancelAdvertiseRetry()
        val delayMs = minOf(
            INITIAL_RETRY_DELAY_MS * (1L shl advertiseRetryAttempt),
            MAX_RETRY_DELAY_MS
        )
        advertiseRetryAttempt++
        val runnable = Runnable {
            advertiseRetryRunnable = null
            if (closed || desiredPort != port || desiredName != name) return@Runnable
            tryUnregisterAdvertise()
            acquireMulticastLock()
            doRegisterAdvertise(port, name)
        }
        advertiseRetryRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
        Log.w(TAG, "Scheduling sendspin advertise retry #$advertiseRetryAttempt in ${delayMs}ms")
    }

    private fun cancelAdvertiseRetry() {
        advertiseRetryRunnable?.let { mainHandler.removeCallbacks(it) }
        advertiseRetryRunnable = null
    }

    private fun tryUnregisterAdvertise() {
        registrationListener?.let { listener ->
            try {
                nsdManager.unregisterService(listener)
            } catch (_: Exception) {
            }
        }
        registrationListener = null
    }

    private fun stopAdvertisingInternal(clearDesired: Boolean) {
        tryUnregisterAdvertise()
        advertisedPort = null
        advertisedName = null
        _isAdvertising.value = false
        if (clearDesired) {
            desiredPort = null
            desiredName = null
        }
    }

    private fun stopDiscoveryInternal() {
        discoveryListener?.let { listener ->
            try {
                nsdManager.stopServiceDiscovery(listener)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop sendspin-server discovery", e)
            }
        }
        discoveryListener = null
        discovering = false
        synchronized(resolveQueue) {
            resolveQueue.clear()
            isResolving = false
        }
        releaseMulticastLockIfIdle()
    }

    @Suppress("DEPRECATION")
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
                        Log.w(TAG, "Resolve failed for ${serviceInfo.serviceName}: $errorCode")
                        synchronized(resolveQueue) { resolveNextLocked() }
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        try {
                            val host = serviceInfo.host?.hostAddress
                            val port = serviceInfo.port
                            if (host.isNullOrBlank() || port <= 0) {
                                Log.w(TAG, "Resolved ${serviceInfo.serviceName} without host/port")
                                return
                            }
                            val attrs = try {
                                serviceInfo.attributes
                            } catch (_: Exception) {
                                emptyMap()
                            }
                            var path = attrs?.get("path")?.let { String(it, Charsets.UTF_8) }
                                ?: DEFAULT_PATH
                            if (path.isBlank()) path = DEFAULT_PATH
                            // aiosendspin requires path to start with '/'.
                            if (!path.startsWith("/")) path = "/$path"

                            val friendly = attrs?.get("name")?.let { String(it, Charsets.UTF_8) }
                                ?.takeIf { it.isNotBlank() }
                                ?: serviceInfo.serviceName
                            val server = SendspinServer(
                                name = friendly,
                                host = host,
                                port = port,
                                path = path
                            )
                            resolvedServers[serviceInfo.serviceName] = server
                            _discoveredServers.value = resolvedServers.values.toList()
                            Log.i(TAG, "Discovered Sendspin server: ${server.name} → ${server.wsUrl}")
                        } finally {
                            synchronized(resolveQueue) { resolveNextLocked() }
                        }
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "resolveService failed", e)
            resolveNextLocked()
        }
    }

    private fun ensureNetworkCallback() {
        if (networkCallback != null) return
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scheduleNetworkRefresh()
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                ) {
                    scheduleNetworkRefresh()
                }
            }

            override fun onLost(network: Network) {
                // Wait for a replacement network via onAvailable.
            }
        }
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, callback)
            networkCallback = callback
        } catch (e: Exception) {
            Log.w(TAG, "registerNetworkCallback failed: ${e.message}")
            networkCallback = null
        }
    }

    private fun scheduleNetworkRefresh() {
        if (desiredPort == null) return
        cancelNetworkRefresh()
        val runnable = Runnable {
            networkRefreshRunnable = null
            if (!closed && desiredPort != null) {
                resetDiscovery()
            }
        }
        networkRefreshRunnable = runnable
        // Debounce DHCP / dual-stack flaps.
        mainHandler.postDelayed(runnable, NETWORK_REFRESH_DEBOUNCE_MS)
    }

    private fun cancelNetworkRefresh() {
        networkRefreshRunnable?.let { mainHandler.removeCallbacks(it) }
        networkRefreshRunnable = null
    }

    private fun unregisterNetworkCallback() {
        cancelNetworkRefresh()
        val callback = networkCallback ?: return
        networkCallback = null
        try {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            Log.w(TAG, "unregisterNetworkCallback failed: ${e.message}")
        }
    }

    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        try {
            val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return
            multicastLock = wifiManager.createMulticastLock("$TAG::MulticastLock").apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.d(TAG, "multicast lock acquired")
        } catch (e: Exception) {
            Log.w(TAG, "multicast lock acquire failed: ${e.message}")
        }
    }

    private fun releaseMulticastLockIfIdle() {
        // Keep the lock only while an NSD operation is actually in flight.
        // desiredPort alone must not pin the lock forever after advertise give-up.
        if (_isAdvertising.value || discovering || registrationListener != null ||
            advertiseRetryRunnable != null
        ) {
            return
        }
        releaseMulticastLock()
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            Log.w(TAG, "multicast lock release failed: ${e.message}")
        } finally {
            multicastLock = null
        }
    }

    private inline fun runOnMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post { block() }
        }
    }

    companion object {
        private const val TAG = "SendspinDiscovery"
        private const val SERVICE_TYPE_CLIENT = "_sendspin._tcp."
        private const val SERVICE_TYPE_SERVER = "_sendspin-server._tcp."
        private const val DEFAULT_PATH = "/sendspin"
        private const val MAX_ADVERTISE_RETRIES = 6
        private const val INITIAL_RETRY_DELAY_MS = 1_000L
        private const val MAX_RETRY_DELAY_MS = 30_000L
        private const val NETWORK_REFRESH_DEBOUNCE_MS = 2_500L
    }
}

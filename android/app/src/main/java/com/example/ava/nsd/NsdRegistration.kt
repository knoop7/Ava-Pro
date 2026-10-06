package com.example.ava.nsd

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat

class NsdRegistration(
    /** Requested service name — the permanent identity. Never mutated by OS conflict renames. */
    val name: String,
    val port: Int,
    type: String,
    attributes: Map<String, String> = emptyMap(),
) {
    private val serviceInfo = run {
        // Capture constructor port before apply — bare `port` inside NsdServiceInfo.apply
        // resolves to NsdServiceInfo.port (default 0), which caused Invalid port number.
        val listenPort = port
        NsdServiceInfo().apply {
            serviceName = name
            serviceType = type
            this.port = listenPort
            for ((key, value) in attributes) {
                setAttribute(key, value)
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var retryAttempt = 0
    private var retryRunnable: Runnable? = null
    private var reclaimAttempt = 0
    private var reclaimRunnable: Runnable? = null
    @Volatile
    private var reclaimUnregisterPending = false
    private var closed = false
    @Volatile
    private var registered = false
    @Volatile
    private var registerInFlight = false
    /**
     * NsdManager holds [registrationListener] from a successful `registerService` call until
     * the unregister callback or a registration failure. Calling `unregisterService` outside
     * that window throws "listener not registered".
     */
    @Volatile
    private var listenerHeld = false
    private var multicastLock: WifiManager.MulticastLock? = null

    private val registrationListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(nsdServiceInfo: NsdServiceInfo) {
            registered = true
            registerInFlight = false
            retryAttempt = 0
            cancelPendingRetry()

            val actualName = nsdServiceInfo.serviceName
            if (actualName != name) {
                // The OS auto-renames ("name (2)") when registration probing collides with
                // a stale advertisement of our own name (async unregister from a previous
                // run, Wi-Fi mDNS offload, reflectors). The rename is transient transport
                // state and must never become the ESPHome node identity — persisting it
                // stacked one more suffix per service restart (issue #201). Instead,
                // reclaim the requested name once the stale record has died off.
                Log.w(TAG, "NSD conflict-renamed '$name' to '$actualName'; scheduling reclaim")
                scheduleNameReclaim()
            } else {
                reclaimAttempt = 0
            }
        }

        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            registered = false
            listenerHeld = false
            Log.e(TAG, "Service registration failed: error=$errorCode attempt=$retryAttempt")
            scheduleRetry()
        }

        override fun onServiceUnregistered(arg0: NsdServiceInfo) {
            registered = false
            registerInFlight = false
            listenerHeld = false
            if (reclaimUnregisterPending) {
                reclaimUnregisterPending = false
                mainHandler.post {
                    if (!closed) doRegister()
                }
            }
        }

        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.e(TAG, "Service unregistration failed: $errorCode")
        }
    }

    fun register(context: Context) {
        closed = false
        appContext = context.applicationContext
        acquireMulticastLock(context)
        cancelPendingRetry()
        cancelPendingReclaim()
        retryAttempt = 0
        reclaimAttempt = 0
        doRegister()
    }

    fun unregister(context: Context) {
        closed = true
        appContext = context.applicationContext
        cancelPendingRetry()
        cancelPendingReclaim()
        registered = false
        registerInFlight = false
        try {
            if (listenerHeld) {
                ContextCompat.getSystemService(context, NsdManager::class.java)?.apply {
                    unregisterService(registrationListener)
                }
            }
        } catch (e: Exception) {
            listenerHeld = false
            Log.e(TAG, "Service unregistration failed", e)
        } finally {
            releaseMulticastLock()
        }
    }

    /**
     * Some OEM WiFi stacks throttle/drop inbound multicast (mDNS) packets unless a
     * multicast lock is held, which can make Ava intermittently invisible to Home
     * Assistant's discovery scan even though registration succeeded locally.
     */
    private fun acquireMulticastLock(context: Context) {
        if (multicastLock?.isHeld == true) return
        try {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            multicastLock = wifiManager.createMulticastLock("$TAG::MulticastLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "multicast lock acquire failed: ${e.message}")
        }
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

    /**
     * Cycle the advertisement (goodbye + fresh probe/announce burst) while keeping
     * the requested name. Home Assistant's reconnect logic retries the moment any
     * mDNS record for this device arrives, but between attempts it backs off (up
     * to 60s) and Android only transmits records on (re)registration — so a peer
     * that missed the original burst has nothing on the wire to wake it earlier.
     * No-op unless the advertisement is currently healthy and idle.
     */
    fun reannounce() {
        if (closed || !registered || isRegistrationInFlight()) return
        val context = appContext ?: return
        // Same sequencing as the name reclaim: unregister and let
        // onServiceUnregistered re-register, so the goodbye is fully
        // processed before the new probe starts.
        reclaimUnregisterPending = true
        try {
            ContextCompat.getSystemService(context, NsdManager::class.java)?.apply {
                unregisterService(registrationListener)
            }
        } catch (e: Exception) {
            reclaimUnregisterPending = false
            Log.w(TAG, "NSD re-announce unregister failed: ${e.message}")
        }
    }

    fun matches(name: String, port: Int): Boolean =
        this.name == name && this.port == port

    fun isRegistered(): Boolean = registered

    fun isRegistrationInFlight(): Boolean =
        registerInFlight || retryRunnable != null ||
            reclaimRunnable != null || reclaimUnregisterPending

    private fun doRegister() {
        val context = appContext ?: return
        if (closed) return
        if (port !in 1..65535) {
            // NsdManager throws IllegalArgumentException("Invalid port number") for port <= 0.
            // Retrying forever cannot help; refuse and stay idle until re-created with a valid port.
            Log.e(TAG, "Refusing NSD registration with invalid port=$port")
            registerInFlight = false
            return
        }
        registerInFlight = true
        try {
            ContextCompat.getSystemService(context, NsdManager::class.java)?.apply {
                registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
                listenerHeld = true
            }
        } catch (e: Exception) {
            registered = false
            listenerHeld = false
            Log.e(TAG, "Service registration failed", e)
            val infoPort = serviceInfo.port
            if (port !in 1..65535 || infoPort !in 1..65535 ||
                (e is IllegalArgumentException && e.message?.contains("port", ignoreCase = true) == true)
            ) {
                registerInFlight = false
                Log.e(TAG, "NSD registration not retryable (port=$port serviceInfo.port=$infoPort)")
                return
            }
            scheduleRetry()
        }
    }

    private fun scheduleRetry() {
        if (closed || retryAttempt >= MAX_RETRY_ATTEMPTS) {
            registerInFlight = false
            if (!closed && retryAttempt >= MAX_RETRY_ATTEMPTS) {
                Log.e(TAG, "NSD registration gave up after $MAX_RETRY_ATTEMPTS attempts")
            }
            return
        }

        cancelPendingRetry()
        val delayMs = minOf(
            INITIAL_RETRY_DELAY_MS * (1L shl retryAttempt),
            MAX_RETRY_DELAY_MS
        )
        retryAttempt++

        val runnable = Runnable {
            retryRunnable = null
            if (closed) return@Runnable
            tryUnregisterBeforeRetry()
            doRegister()
        }
        retryRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
        Log.w(TAG, "Scheduling NSD retry #$retryAttempt in ${delayMs}ms")
    }

    /**
     * After a conflict rename, retry the requested name a few times: the colliding
     * record is almost always this device's own stale advertisement, gone once its
     * goodbye packet (or record TTL) is processed. Gives up after
     * [MAX_RECLAIM_ATTEMPTS] and keeps the renamed advertisement for this session
     * only — nothing is persisted, so the next service start begins again from the
     * clean requested name.
     */
    private fun scheduleNameReclaim() {
        if (closed) return
        if (reclaimAttempt >= MAX_RECLAIM_ATTEMPTS) {
            Log.e(
                TAG,
                "NSD name reclaim gave up after $MAX_RECLAIM_ATTEMPTS attempts; " +
                    "keeping renamed advertisement for this session",
            )
            return
        }
        cancelPendingReclaim()
        val delayMs = RECLAIM_INITIAL_DELAY_MS shl reclaimAttempt
        reclaimAttempt++
        val runnable = Runnable {
            reclaimRunnable = null
            if (closed || !registered) return@Runnable
            val context = appContext ?: return@Runnable
            reclaimUnregisterPending = true
            try {
                ContextCompat.getSystemService(context, NsdManager::class.java)?.apply {
                    unregisterService(registrationListener)
                }
                // Re-register with the requested name happens in onServiceUnregistered.
            } catch (e: Exception) {
                reclaimUnregisterPending = false
                Log.w(TAG, "NSD name reclaim unregister failed: ${e.message}")
            }
        }
        reclaimRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
        Log.w(TAG, "Scheduling NSD name reclaim #$reclaimAttempt in ${delayMs}ms")
    }

    private fun cancelPendingReclaim() {
        reclaimRunnable?.let { mainHandler.removeCallbacks(it) }
        reclaimRunnable = null
        reclaimUnregisterPending = false
    }

    private fun tryUnregisterBeforeRetry() {
        val context = appContext ?: return
        if (!listenerHeld) return
        try {
            ContextCompat.getSystemService(context, NsdManager::class.java)?.apply {
                unregisterService(registrationListener)
            }
        } catch (_: Exception) {
            // Best-effort cleanup before retry; listener may not be registered yet.
        }
    }

    private fun cancelPendingRetry() {
        retryRunnable?.let { mainHandler.removeCallbacks(it) }
        retryRunnable = null
    }

    companion object {
        const val TAG = "NsdRegistration"
        private const val MAX_RETRY_ATTEMPTS = 6
        private const val INITIAL_RETRY_DELAY_MS = 1_000L
        private const val MAX_RETRY_DELAY_MS = 30_000L
        private const val MAX_RECLAIM_ATTEMPTS = 3
        /** Doubles per attempt: 4s, 8s, 16s — enough for a stale record's goodbye/TTL. */
        private const val RECLAIM_INITIAL_DELAY_MS = 4_000L
    }
}

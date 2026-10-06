package com.example.ava.fleet

import android.content.Context
import android.util.Log
import com.example.ava.settings.VoiceSatelliteSettingsStore
import com.example.ava.settings.voiceSatelliteSettingsStore
import com.example.ava.voice.AvaVoiceDiscovery
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

object FleetManager {
    private const val TAG = "FleetManager"
    const val DEFAULT_PORT = 8888

    private val applyMutex = Mutex()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val watchdogStarted = AtomicBoolean(false)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var server: FleetHttpServer? = null

    @Volatile
    private var serveWebConsole: Boolean = false

    /** Latest desired flags — HTTP stack catches up asynchronously. */
    @Volatile
    private var desiredAgent: Boolean = false

    @Volatile
    private var desiredWebConsole: Boolean = false

    /** Last known voice satellite display name; refreshed off the apply mutex. */
    @Volatile
    private var cachedDisplayName: String = ""

    fun isRunning(): Boolean = server?.isHealthy() == true

    fun isServingWebConsole(): Boolean {
        val s = server
        return s != null && s.isHealthy() && s.isServingWebConsole()
    }

    /**
     * Apply cluster agent / website console from settings.
     *
     * Updates [desiredAgent]/[desiredWebConsole] immediately, then schedules IO work.
     * Must NOT block the settings Flow collector — a hung stop previously froze all
     * later toggles until process death.
     *
     * When :8888 is already healthy, the console flag is flipped **synchronously** so
     * agent→console does not wait on [applyMutex] / presence I/O.
     */
    suspend fun apply(context: Context, agentEnabled: Boolean, webConsoleEnabled: Boolean) {
        val app = context.applicationContext
        appContext = app
        desiredAgent = agentEnabled
        desiredWebConsole = webConsoleEnabled
        ensureWatchdog()
        fastFlipConsoleIfHealthy(app, agentEnabled, webConsoleEnabled)
        ioScope.launch { syncToDesired(app) }
    }

    /** Fire-and-forget apply for legacy call sites. Prefer suspend [apply]. */
    fun applyAsync(context: Context, agentEnabled: Boolean, webConsoleEnabled: Boolean) {
        val app = context.applicationContext
        appContext = app
        desiredAgent = agentEnabled
        desiredWebConsole = webConsoleEnabled
        ensureWatchdog()
        fastFlipConsoleIfHealthy(app, agentEnabled, webConsoleEnabled)
        ioScope.launch { syncToDesired(app) }
    }

    /**
     * Immediate in-process flip for the common path: agent already bound, user enables
     * website console. Avoids depending on a queued sync that may sit behind presence I/O.
     */
    private fun fastFlipConsoleIfHealthy(
        app: Context,
        agentEnabled: Boolean,
        webConsoleEnabled: Boolean,
    ) {
        if (!agentEnabled && !webConsoleEnabled) return
        val existing = server
        if (existing == null || !existing.isHealthy()) return
        serveWebConsole = webConsoleEnabled
        existing.setServeWebConsole(webConsoleEnabled)
        AvaVoiceDiscovery.setAdvertisedWebConsole(webConsoleEnabled)
        if (webConsoleEnabled) {
            FleetAdbHost.ensureBinaryAsync(app)
        }
        Log.i(TAG, "fleet HTTP fast-flip — webConsole=$webConsoleEnabled")
    }

    private suspend fun syncToDesired(app: Context) {
        withContext(Dispatchers.IO) {
            applyMutex.withLock {
                withContext(NonCancellable) {
                    syncLocked(app)
                }
            }
        }
    }

    fun start(context: Context, serveWebConsole: Boolean = false) {
        applyAsync(context, agentEnabled = true, webConsoleEnabled = serveWebConsole)
    }

    /** @deprecated Prefer [apply]. */
    fun start(context: Context) = start(context, serveWebConsole = isServingWebConsole())

    fun stop() {
        desiredAgent = false
        desiredWebConsole = false
        ioScope.launch {
            applyMutex.withLock {
                withContext(NonCancellable) {
                    stopLocked()
                }
            }
        }
    }

    fun getAccessUrl(context: Context, includeToken: Boolean = false): String {
        val ip = FleetNetwork.getLocalIpAddress(context)
        val base = FleetNetwork.buildAccessUrl(ip, DEFAULT_PORT)
        if (!includeToken) return base
        val plain = FleetAuth.configuredPlain(context)
        return "$base/?password=${java.net.URLEncoder.encode(plain, Charsets.UTF_8.name())}"
    }

    /**
     * Apply whatever [desiredAgent]/[desiredWebConsole] currently say.
     * Re-reads desire after start so a console toggle mid-bind is not lost.
     */
    private suspend fun syncLocked(app: Context) {
        // Use a real for-loop (not repeat{}) so continue/return work as expected.
        for (attempt in 0 until 4) {
            val agent = desiredAgent
            val web = desiredWebConsole
            if (!agent && !web) {
                stopLocked()
                return
            }
            startLocked(app, serveWebConsole = web)
            // If bind lost the race with a lingering socket, retry while desire stays on.
            if (!isRunning() && (desiredAgent || desiredWebConsole)) {
                Log.w(TAG, "fleet HTTP not running after start — retrying")
                stopLocked()
                delay(200)
                if (desiredAgent || desiredWebConsole) {
                    startLocked(app, serveWebConsole = desiredWebConsole)
                }
            }
            val matched =
                isRunning() &&
                    serveWebConsole == desiredWebConsole &&
                    server?.isServingWebConsole() == desiredWebConsole
            if (matched && desiredAgent == agent && desiredWebConsole == web) {
                return
            }
            if (desiredAgent != agent || desiredWebConsole != web) {
                Log.i(
                    TAG,
                    "fleet desire changed during sync (attempt ${attempt + 1}) — " +
                        "was agent=$agent web=$web now agent=$desiredAgent web=$desiredWebConsole",
                )
                continue
            }
            if (!matched) {
                Log.w(
                    TAG,
                    "fleet sync mismatch (attempt ${attempt + 1}): " +
                        "running=${isRunning()} serveWeb=$serveWebConsole " +
                        "serverWeb=${server?.isServingWebConsole()} desiredWeb=$desiredWebConsole",
                )
            }
        }
    }

    private fun startLocked(context: Context, serveWebConsole: Boolean) {
        val app = context.applicationContext
        this.serveWebConsole = serveWebConsole
        val existing = server
        // Healthy accept loop: just flip console flag (no re-bind).
        if (existing != null && existing.isHealthy()) {
            existing.setServeWebConsole(serveWebConsole)
            ensurePresence(app)
            if (serveWebConsole) {
                FleetAdbHost.ensureBinaryAsync(app)
            }
            Log.i(TAG, "fleet HTTP already healthy — webConsole=$serveWebConsole")
            return
        }
        // Dead or half-open instance — tear down before re-bind.
        if (existing != null) {
            Log.i(TAG, "replacing unhealthy fleet server instance")
            runCatching { existing.stop() }
                .onFailure { Log.w(TAG, "replace stop: ${it.message}") }
            server = null
        }
        ensurePresence(app)
        val next = FleetHttpServer(app, DEFAULT_PORT, serveWebConsole)
        if (!next.start()) {
            Log.e(TAG, "fleet HTTP failed to bind port $DEFAULT_PORT")
            runCatching { next.stop() }
            server = null
            AvaVoiceDiscovery.setAdvertisedClusterPort(0)
            AvaVoiceDiscovery.setAdvertisedWebConsole(false)
            AvaVoiceDiscovery.release(AvaVoiceDiscovery.HOLDER_FLEET)
            return
        }
        server = next
        // Desire may have flipped while we were binding — honor latest console flag.
        val webNow = desiredWebConsole
        this.serveWebConsole = webNow
        next.setServeWebConsole(webNow)
        if (webNow) {
            FleetAdbHost.ensureBinaryAsync(app)
        }
        AvaVoiceDiscovery.setAdvertisedWebConsole(webNow)
        Log.i(
            TAG,
            "fleet HTTP started on $DEFAULT_PORT (webConsole=$webNow); UDP discovery via AvaVoiceDiscovery",
        )
    }

    private fun stopLocked() {
        // Close HTTP first so the port is released even if scrcpy teardown is slow.
        val existing = server
        server = null
        serveWebConsole = false
        runCatching { existing?.stop() }
            .onFailure { Log.w(TAG, "fleet http stop: ${it.message}") }
        AvaVoiceDiscovery.setAdvertisedClusterPort(0)
        AvaVoiceDiscovery.setAdvertisedWebConsole(false)
        AvaVoiceDiscovery.release(AvaVoiceDiscovery.HOLDER_FLEET)
        // Scrcpy stop is timed; never let it block re-apply forever.
        runCatching { FleetScrcpyServer.stop() }
            .onFailure { Log.w(TAG, "scrcpy stop during fleet stop: ${it.message}") }
        FleetScreenShot.clear()
        Log.i(TAG, "fleet HTTP stopped; released fleet discovery holder (UDP presence may remain)")
    }

    private fun ensurePresence(context: Context) {
        // Refresh display name off the apply mutex — never runBlocking here.
        ioScope.launch {
            val name = runCatching {
                withTimeoutOrNull(800) {
                    VoiceSatelliteSettingsStore(context.voiceSatelliteSettingsStore).get().name
                }.orEmpty()
            }.getOrNull().orEmpty()
            if (name.isNotEmpty() && name != cachedDisplayName) {
                cachedDisplayName = name
                if (desiredAgent || desiredWebConsole) {
                    AvaVoiceDiscovery.acquire(
                        context,
                        AvaVoiceDiscovery.HOLDER_FLEET,
                        cachedDisplayName,
                    )
                }
            }
        }
        AvaVoiceDiscovery.setAdvertisedClusterPort(DEFAULT_PORT)
        AvaVoiceDiscovery.setAdvertisedWebConsole(serveWebConsole)
        AvaVoiceDiscovery.acquire(context, AvaVoiceDiscovery.HOLDER_FLEET, cachedDisplayName)
    }

    /**
     * If settings say ON but the accept loop died (or console flag drifted),
     * keep trying to catch up without requiring a process kill.
     */
    private fun ensureWatchdog() {
        if (!watchdogStarted.compareAndSet(false, true)) return
        ioScope.launch {
            while (true) {
                delay(2_500)
                val app = appContext ?: continue
                val want = desiredAgent || desiredWebConsole
                if (!want) continue
                val actualConsole = server?.takeIf { it.isHealthy() }?.isServingWebConsole() == true
                val matched =
                    isRunning() &&
                        serveWebConsole == desiredWebConsole &&
                        actualConsole == desiredWebConsole
                if (matched) continue
                Log.w(
                    TAG,
                    "watchdog: desired agent=$desiredAgent web=$desiredWebConsole " +
                        "running=${isRunning()} serveWeb=$serveWebConsole " +
                        "serverWeb=$actualConsole — resync",
                )
                syncToDesired(app)
            }
        }
    }
}

package com.example.ava.bluetooth

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Serializes BLE operations that cannot safely overlap on Android (proxy scan,
 * detect scan, presence advertising, raw ADV transmit).
 *
 * When a legacy integrated ble-adv-proxy mod is active ([isBleAdvModActive]), transmit bursts are
 * batched into a single exclusive window (queue coalescing), scan restarts are
 * rate-limited, and a watchdog monitors scan health.
 */
object BleOperationCoordinator {
    private const val TAG = "BleOpCoordinator"
    private const val SETTLE_MS = 80L
    /** After pausing scans/advertising, wait for the stack to release MGMT/HCI. */
    private const val ENTER_SETTLE_MS = 350L
    private const val ENTER_SETTLE_BLE_ADV_MS = 1000L
    /** Hold exclusive after the last queued job before resuming scans. */
    private const val BURST_IDLE_MS = 160L
    /** Android rejects scan register/start when called too frequently. */
    private const val MIN_SCAN_RESTART_INTERVAL_MS = 2_000L
    private const val SCAN_START_BACKOFF_MAX_MS = 8_000L
    private const val WATCHDOG_INTERVAL_MS = 30_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<ExclusiveJob>(Channel.UNLIMITED)
    private val workerStarted = AtomicBoolean(false)
    private val watchdogStarted = AtomicBoolean(false)
    private val scanStartMutex = Mutex()

    @Volatile
    private var pauseProxyScan: (() -> Unit)? = null

    @Volatile
    private var resumeProxyScan: (() -> Unit)? = null

    @Volatile
    private var pausePresenceAdvertise: (() -> Unit)? = null

    @Volatile
    private var resumePresenceAdvertise: (() -> Unit)? = null

    @Volatile
    private var pauseDetectScan: (() -> Unit)? = null

    @Volatile
    private var resumeDetectScan: (() -> Unit)? = null

    /** True while legacy integrated ble-adv-proxy coordinator hook is registered. */
    @Volatile
    var isBleAdvModActive: Boolean = false
        private set

    @Volatile
    var isExclusiveActive: Boolean = false
        private set

    /** While legacy integrated ble-adv-proxy mod is registered: never resume presence ADV after exclusive. */
    @Volatile
    var suppressPresenceResume: Boolean = false
        private set

    fun setSuppressPresenceResume(suppress: Boolean) {
        suppressPresenceResume = suppress
        Log.i(TAG, "suppressPresenceResume=$suppress")
    }

    @Volatile
    private var lastScanStartMs = 0L

    @Volatile
    private var scanStartBackoffMs = MIN_SCAN_RESTART_INTERVAL_MS

    private val consecutiveScanThrottle = AtomicInteger(0)
    private val exclusiveSessionCount = AtomicLong(0L)

    fun register(
        pauseProxyScan: () -> Unit,
        resumeProxyScan: () -> Unit,
        pausePresenceAdvertise: () -> Unit,
        resumePresenceAdvertise: () -> Unit,
        pauseDetectScan: () -> Unit = {},
        resumeDetectScan: () -> Unit = {},
        bleAdvModActive: Boolean = true,
    ) {
        this.pauseProxyScan = pauseProxyScan
        this.resumeProxyScan = resumeProxyScan
        this.pausePresenceAdvertise = pausePresenceAdvertise
        this.resumePresenceAdvertise = resumePresenceAdvertise
        this.pauseDetectScan = pauseDetectScan
        this.resumeDetectScan = resumeDetectScan
        isBleAdvModActive = bleAdvModActive
        ensureWorker()
        ensureWatchdog()
        Log.i(TAG, "registered (bleAdvMod=$bleAdvModActive)")
    }

    fun unregister() {
        pauseProxyScan = null
        resumeProxyScan = null
        pausePresenceAdvertise = null
        resumePresenceAdvertise = null
        pauseDetectScan = null
        resumeDetectScan = null
        isBleAdvModActive = false
        suppressPresenceResume = false
    }

    /**
     * Runs [block] with all registered scans and presence advertising paused.
     * Used by [com.example.ava.mods.BleAdvHostApi] from the ble-adv-proxy mod.
     */
    fun runExclusiveBlocking(block: () -> Unit) {
        if (pauseProxyScan == null) {
            block()
            return
        }
        runBlocking {
            runExclusiveSuspend {
                block()
            }
        }
    }

    suspend fun runExclusiveSuspend(block: suspend () -> Unit) {
        if (pauseProxyScan == null) {
            block()
            return
        }
        ensureWorker()
        val done = Channel<Unit>(1)
        queue.send(
            ExclusiveJob(
                block = block,
                onComplete = { done.trySend(Unit) },
            ),
        )
        done.receive()
    }

    fun enqueueExclusiveAsync(block: () -> Unit, onComplete: (() -> Unit)? = null) {
        if (pauseProxyScan == null) {
            block()
            onComplete?.invoke()
            return
        }
        ensureWorker()
        scope.launch {
            queue.send(
                ExclusiveJob(
                    block = { block() },
                    onComplete = onComplete,
                ),
            )
        }
    }

    /**
     * Blocks until Android scan-start rate limits allow another [BluetoothLeScanner.startScan].
     * Call before every hardware scan start when ble-adv mod is active.
     */
    suspend fun awaitScanStartPermitted() {
        if (!isBleAdvModActive) return
        scanStartMutex.withLock {
            while (true) {
                if (isExclusiveActive) {
                    delay(100)
                    continue
                }
                val now = System.currentTimeMillis()
                val elapsed = now - lastScanStartMs
                val waitMs = scanStartBackoffMs - elapsed
                if (waitMs <= 0L) {
                    lastScanStartMs = now
                    return
                }
                delay(waitMs.coerceAtMost(500L))
            }
        }
    }

    fun recordScanStartThrottled() {
        val failures = consecutiveScanThrottle.incrementAndGet()
        scanStartBackoffMs = (MIN_SCAN_RESTART_INTERVAL_MS * (1 shl failures.coerceAtMost(3)))
            .coerceAtMost(SCAN_START_BACKOFF_MAX_MS)
        Log.w(TAG, "scan start throttled by system, backoff=${scanStartBackoffMs}ms")
    }

    fun recordScanStartSuccess() {
        consecutiveScanThrottle.set(0)
        scanStartBackoffMs = MIN_SCAN_RESTART_INTERVAL_MS
    }

    private fun ensureWorker() {
        if (!workerStarted.compareAndSet(false, true)) return
        scope.launch {
            for (first in queue) {
                runExclusiveSession(first)
            }
        }
    }

    private suspend fun runExclusiveSession(firstJob: ExclusiveJob) {
        enterExclusive()
        try {
            firstJob.block()
            invokeComplete(firstJob)
            // Coalesce back-to-back transmit bursts without resuming scans between them.
            while (true) {
                delay(BURST_IDLE_MS)
                val next = queue.tryReceive().getOrNull() ?: break
                next.block()
                invokeComplete(next)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exclusive BLE job failed", e)
        } finally {
            exitExclusive()
        }
    }

    private suspend fun enterExclusive() {
        isExclusiveActive = true
        exclusiveSessionCount.incrementAndGet()
        pauseProxyScan?.invoke()
        pauseDetectScan?.invoke()
        pausePresenceAdvertise?.invoke()
        val settleMs = if (isBleAdvModActive) ENTER_SETTLE_BLE_ADV_MS else ENTER_SETTLE_MS
        if (settleMs > 0L) {
            delay(settleMs)
        }
    }

    private suspend fun exitExclusive() {
        delay(SETTLE_MS)
        if (!suppressPresenceResume) {
            try {
                resumePresenceAdvertise?.invoke()
            } catch (e: Exception) {
                Log.w(TAG, "resumePresenceAdvertise failed", e)
            }
        }
        try {
            resumeDetectScan?.invoke()
        } catch (e: Exception) {
            Log.w(TAG, "resumeDetectScan failed", e)
        }
        try {
            resumeProxyScan?.invoke()
        } catch (e: Exception) {
            Log.w(TAG, "resumeProxyScan failed", e)
        }
        isExclusiveActive = false
    }

    private fun invokeComplete(job: ExclusiveJob) {
        try {
            job.onComplete?.invoke()
        } catch (e: Exception) {
            Log.w(TAG, "Exclusive job onComplete failed", e)
        }
    }

    private fun ensureWatchdog() {
        if (!watchdogStarted.compareAndSet(false, true)) return
        scope.launch {
            while (true) {
                delay(WATCHDOG_INTERVAL_MS)
                if (!isBleAdvModActive || pauseProxyScan == null) continue
                if (isExclusiveActive) {
                    Log.d(TAG, "watchdog: exclusive active (sessions=${exclusiveSessionCount.get()})")
                }
                if (consecutiveScanThrottle.get() > 0) {
                    Log.d(
                        TAG,
                        "watchdog: scan throttle backoff=${scanStartBackoffMs}ms " +
                            "failures=${consecutiveScanThrottle.get()}",
                    )
                }
            }
        }
    }

    private class ExclusiveJob(
        val block: suspend () -> Unit,
        val onComplete: (() -> Unit)? = null,
    )
}

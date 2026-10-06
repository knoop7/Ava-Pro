package com.example.ava.esphome.voicesatellite

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.example.ava.R
import com.example.ava.bluetooth.BleHciLeScanHelper
import com.example.ava.bluetooth.BleOperationCoordinator
import com.example.ava.bluetooth.BluetoothCompatPresenceController
import com.example.ava.bluetooth.BluetoothLowLevelHooks
import com.example.ava.bluetooth.BluetoothPresenceManager
import com.example.ava.bluetooth.BluetoothProxyManager
import com.example.ava.bluetooth.BluetoothRadioHelper
import com.example.ava.bluetooth.DarkWakePulse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.ava.utils.RootUtils
import com.example.ava.utils.ScreenControlUtils
import com.example.ava.utils.ShizukuUtils
import com.example.esphomeproto.api.BluetoothDeviceRequest
import com.example.esphomeproto.api.BluetoothGATTGetServicesRequest
import com.example.esphomeproto.api.BluetoothGATTReadRequest
import com.example.esphomeproto.api.BluetoothGATTWriteRequest
import com.example.esphomeproto.api.BluetoothGATTReadDescriptorRequest
import com.example.esphomeproto.api.BluetoothGATTWriteDescriptorRequest
import com.example.esphomeproto.api.BluetoothGATTNotifyRequest
import com.example.esphomeproto.api.BluetoothSetConnectionParamsRequest
import com.example.esphomeproto.api.SubscribeBluetoothConnectionsFreeRequest
import com.example.ava.esphome.EspHomeDevice
import com.example.ava.esphome.entities.BinarySensorEntity
import com.example.ava.esphome.entities.NumberEntity
import com.example.ava.esphome.entities.SelectEntity
import com.example.ava.mods.ModBleAdvProxyBridge
import com.example.ava.mods.ModDeviceSupport
import com.example.esphomeproto.api.EntityCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray

class VoiceSatelliteBluetooth(
    private val context: Context,
    private val scope: CoroutineScope,
    private val device: EspHomeDevice,
    private val bluetoothManager: BluetoothPresenceManager
) {
    private val bluetoothEntities = mutableMapOf<String, BinarySensorEntity>()
    private val staticBluetoothEntities = mutableListOf<com.example.ava.esphome.entities.Entity>()
    private var proxyManager: BluetoothProxyManager? = null
    private var sendMessageFunc: (suspend (com.google.protobuf.MessageLite) -> Unit)? = null
    private val _bluetoothScanMode = MutableStateFlow(scanModeKeyToLabel(bluetoothManager.proxyScanMode))
    private val _bluetoothScanPower = MutableStateFlow(context.getString(R.string.bluetooth_scan_power_low))
    private val scannedDevices = mutableSetOf<Long>()
    private var scanLoopJob: kotlinx.coroutines.Job? = null
    private var trackedDevicesJob: kotlinx.coroutines.Job? = null
    private var screenStateJob: kotlinx.coroutines.Job? = null
    private val detectPrefs = context.getSharedPreferences("bluetooth_settings", Context.MODE_PRIVATE)
    private var detectEnabledListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null
    @Volatile private var lastProxyScreenOn = true

    private val screenOffFilterPrefs = context.getSharedPreferences(
        SCREEN_OFF_FILTER_PREFS,
        Context.MODE_PRIVATE,
    )
    private val screenOffFilterLock = Any()
    private val presenceManufacturerIds = loadPersistedFilterValues(KEY_PRESENCE_MANUFACTURERS)
        .takeLast(MAX_LEARNED_FILTER_VALUES)
        .mapNotNullTo(linkedSetOf<Int>()) { it.toIntOrNull()?.takeIf { id -> id >= 0 } }
    private val presenceServiceUuids = loadPersistedFilterValues(KEY_PRESENCE_SERVICE_UUIDS)
        .takeLast(MAX_LEARNED_FILTER_VALUES)
        .mapNotNullTo(linkedSetOf<android.os.ParcelUuid>()) { value ->
            runCatching { android.os.ParcelUuid.fromString(value) }.getOrNull()
        }
    private val presenceServiceDataUuids = loadPersistedFilterValues(KEY_PRESENCE_SERVICE_DATA_UUIDS)
        .takeLast(MAX_LEARNED_FILTER_VALUES)
        .mapNotNullTo(linkedSetOf<android.os.ParcelUuid>()) { value ->
            runCatching { android.os.ParcelUuid.fromString(value) }.getOrNull()
        }
    private val presenceDeviceAddresses = loadPersistedFilterValues(KEY_PRESENCE_ADDRESSES)
        .takeLast(MAX_LEARNED_FILTER_VALUES)
        .mapTo(linkedSetOf<String>()) { it.uppercase() }
    private val gatewayManufacturerIds = loadPersistedFilterValues(KEY_GATEWAY_MANUFACTURERS)
        .takeLast(MAX_LEARNED_FILTER_VALUES)
        .mapNotNullTo(linkedSetOf<Int>()) { it.toIntOrNull()?.takeIf { id -> id >= 0 } }
    private val gatewayServiceUuids = loadPersistedFilterValues(KEY_GATEWAY_SERVICE_UUIDS)
        .takeLast(MAX_LEARNED_FILTER_VALUES)
        .mapNotNullTo(linkedSetOf<android.os.ParcelUuid>()) { value ->
            runCatching { android.os.ParcelUuid.fromString(value) }.getOrNull()
        }
    private val gatewayServiceDataUuids = loadPersistedFilterValues(KEY_GATEWAY_SERVICE_DATA_UUIDS)
        .takeLast(MAX_LEARNED_FILTER_VALUES)
        .mapNotNullTo(linkedSetOf<android.os.ParcelUuid>()) { value ->
            runCatching { android.os.ParcelUuid.fromString(value) }.getOrNull()
        }
    private val gatewayDeviceAddresses = loadPersistedFilterValues(KEY_GATEWAY_ADDRESSES)
        .takeLast(MAX_LEARNED_FILTER_VALUES)
        .mapTo(linkedSetOf<String>()) { it.uppercase() }
    @Volatile private var persistGatewayFiltersJob: Job? = null
    
    /** Legacy integrated ble-adv only: hardware scan may pause/resume inside one coroutine session. */
    @Volatile private var proxyScanHardwareRunning = false
    private var stableProxyScanCallback: android.bluetooth.le.ScanCallback? = null
    private var proxyScanRecoveryJob: Job? = null
    @Volatile private var detectScanSuppressedByExclusive = false
    @Volatile private var deviceProxySuppressedHostAdvertising = false
    
    private var scanStartTime = 0L
    private var currentScanCallback: android.bluetooth.le.ScanCallback? = null
    /**
     * Identity MACs of the current full-tier presence scan's address filters.
     * Only that callback reads this; the unfiltered proxy must never see it.
     */
    @Volatile private var presenceAddressFilterIdentities: Set<String>? = null
    /**
     * Screen-off proxy address filters actually submitted to startScan.
     * [proxyScreenOffAddressFiltersOnly] is true only when every submitted filter
     * is an address filter — mixed manufacturer/service sessions must not steal RPAs.
     */
    @Volatile private var proxyScreenOffAddressIdentities: Set<String>? = null
    @Volatile private var proxyScreenOffAddressFiltersOnly = false
    
    private fun getResetIntervalMs(): Long {
        return when (_bluetoothScanPower.value) {
            context.getString(R.string.bluetooth_scan_power_high) -> 5 * 60 * 1000L
            context.getString(R.string.bluetooth_scan_power_balanced) -> 15 * 60 * 1000L
            else -> Long.MAX_VALUE
        }
    }
    
    private fun shouldResetCallback(): Boolean {
        val interval = getResetIntervalMs()
        if (interval == Long.MAX_VALUE) return false
        return System.currentTimeMillis() - scanStartTime > interval
    }
    
    private fun getOrCreateScanCallback(): android.bluetooth.le.ScanCallback {
        if (currentScanCallback == null || shouldResetCallback()) {
            currentScanCallback = createScanCallback()
            scanStartTime = System.currentTimeMillis()
        }
        return currentScanCallback!!
    }
    
    private val bluetoothScanModeOptions by lazy { 
        listOf(
            context.getString(R.string.bluetooth_scan_mode_auto),
            context.getString(R.string.bluetooth_scan_mode_active),
            context.getString(R.string.bluetooth_scan_mode_passive)
        )
    }

    private fun scanModeKeyToLabel(key: String): String = when (key) {
        "active" -> context.getString(R.string.bluetooth_scan_mode_active)
        "passive" -> context.getString(R.string.bluetooth_scan_mode_passive)
        else -> context.getString(R.string.bluetooth_scan_mode_auto)
    }

    private fun scanModeLabelToKey(label: String): String = when (label) {
        context.getString(R.string.bluetooth_scan_mode_active) -> "active"
        context.getString(R.string.bluetooth_scan_mode_passive) -> "passive"
        else -> "auto"
    }
    private val bluetoothScanPowerOptions by lazy {
        listOf(
            context.getString(R.string.bluetooth_scan_power_low),
            context.getString(R.string.bluetooth_scan_power_balanced),
            context.getString(R.string.bluetooth_scan_power_high)
        )
    }
    
    @SuppressLint("MissingPermission")
    private fun createScanCallback(): android.bluetooth.le.ScanCallback {
        return object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
                bluetoothManager.onDeviceScanned(
                    result.device.address,
                    result.rssi,
                    device = result.device,
                    addressFilterIdentities = presenceAddressFilterIdentities,
                    addressFiltersOnly = presenceAddressFilterIdentities != null,
                )
            }
            
            override fun onBatchScanResults(results: MutableList<android.bluetooth.le.ScanResult>) {
                results.forEach {
                    bluetoothManager.onDeviceScanned(
                        it.device.address,
                        it.rssi,
                        device = it.device,
                        addressFilterIdentities = presenceAddressFilterIdentities,
                        addressFiltersOnly = presenceAddressFilterIdentities != null,
                    )
                }
            }
            
            override fun onScanFailed(errorCode: Int) {
                val errorMsg = when (errorCode) {
                    SCAN_FAILED_ALREADY_STARTED -> "SCAN_FAILED_ALREADY_STARTED"
                    SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "SCAN_FAILED_APPLICATION_REGISTRATION_FAILED"
                    SCAN_FAILED_INTERNAL_ERROR -> "SCAN_FAILED_INTERNAL_ERROR"
                    SCAN_FAILED_FEATURE_UNSUPPORTED -> "SCAN_FAILED_FEATURE_UNSUPPORTED"
                    else -> "UNKNOWN_ERROR($errorCode)"
                }
                Log.e(TAG, "BLE scan failed: $errorMsg")
            }
        }
    }

    private fun isBleAdvModActive(): Boolean = ModBleAdvProxyBridge.isActive(context)

    private fun isBleAdvLegacyIntegrated(): Boolean = ModBleAdvProxyBridge.isLegacyIntegrated(context)

    /** Legacy integrated only — suppress host presence ADV so MGMT is not BUSY. */
    private fun applyBleAdvModPresencePolicy() {
        bluetoothManager.setPresenceAdvertisingSuppressed(true)
        BleOperationCoordinator.setSuppressPresenceResume(true)
        bluetoothManager.stopAdvertising()
        Log.i(TAG, "ble-adv mod active: presence advertising stopped")
    }

    private fun maybeForwardBleAdvScan(result: android.bluetooth.le.ScanResult) {
        if (!isBleAdvLegacyIntegrated()) return
        ModBleAdvProxyBridge.onScanResult(context, result)
    }

    @Volatile private var proxyScanWasActiveBeforeExclusive = false

    private fun registerLegacyBleAdvCoordinator() {
        BleOperationCoordinator.register(
            pauseProxyScan = { pauseProxyScanForExclusive() },
            resumeProxyScan = { resumeProxyScanAfterExclusive() },
            pausePresenceAdvertise = {
                if (bluetoothManager.isDetectEnabled) {
                    bluetoothManager.stopAdvertising()
                }
            },
            resumePresenceAdvertise = {
                // Legacy integrated coordinator: presence stays off while mod is registered.
                if (BleOperationCoordinator.suppressPresenceResume) return@register
                if (bluetoothManager.isPresenceAdvertisingSuppressed()) return@register
                if (bluetoothManager.isDetectEnabled && !BleOperationCoordinator.isExclusiveActive) {
                    bluetoothManager.startAdvertising()
                }
            },
            pauseDetectScan = { detectScanSuppressedByExclusive = true },
            resumeDetectScan = { detectScanSuppressedByExclusive = false },
            bleAdvModActive = true,
        )
    }

    private fun pauseProxyScanForExclusive() {
        proxyScanWasActiveBeforeExclusive = bluetoothProxyScanJob?.isActive == true
        softStopProxyScanHardware()
        Log.d(TAG, "exclusive: proxy scan hardware stopped")
    }

    private fun resumeProxyScanAfterExclusive() {
        if (!proxyScanWasActiveBeforeExclusive) return
        // Exclusive paused callbacks without killing the session; reset the stale clock so
        // the watchdog does not treat the pause as a dead scanner the moment exclusive ends.
        lastProxyAdvertTime = System.currentTimeMillis()
        // Unified session loop restarts hardware scan when exclusive clears.
    }

    /** Serialises quiet windows; two concurrent connects must not double-resume the scanner. */
    private val gattConnectWindowMutex = kotlinx.coroutines.sync.Mutex()
    @Volatile private var gattConnectWindowActive = false
    private var lastGattConnectWindowClosedAt = 0L

    /**
     * Radio quiet window for a proxy GATT connect (see [BluetoothProxyManager.connectWindow]).
     *
     * Small LE controllers cannot hold scanning and connection-initiating at once; the
     * stack's topology check then refuses the connect locally, which HA sees as an instant
     * status=133. Stop our own scanner and presence advertising, let the controller settle,
     * run the connect, and hand the radio back afterwards. Legacy ble-adv integration already
     * routes radio-exclusive work through [BleOperationCoordinator], so reuse that path there.
     */
    @SuppressLint("MissingPermission")
    private suspend fun runGattConnectWindow(block: suspend () -> Unit) {
        if (isBleAdvLegacyIntegrated()) {
            BleOperationCoordinator.runExclusiveSuspend { block() }
            return
        }
        gattConnectWindowMutex.withLock {
            // Every window costs one scanner restart; Android silently throttles an app that
            // starts scanning more than 5 times in 30s. A retry storm from HA therefore gets
            // at most one window per interval and otherwise takes the plain path.
            val sinceLast = System.currentTimeMillis() - lastGattConnectWindowClosedAt
            if (lastGattConnectWindowClosedAt != 0L && sinceLast < GATT_CONNECT_WINDOW_MIN_INTERVAL_MS) {
                Log.d(TAG, "GATT connect quiet window skipped (last closed ${sinceLast}ms ago)")
                block()
                return
            }
            val scanWasRunning = proxyScanHardwareRunning
            val pauseAdvertising = bluetoothManager.isDetectEnabled &&
                !bluetoothManager.isPresenceAdvertisingSuppressed()
            gattConnectWindowActive = true
            try {
                // Same grace the scan rotation uses: a paused scanner is not an absent device.
                holdPresenceAcrossScanHandover()
                if (scanWasRunning) softStopProxyScanHardware()
                if (pauseAdvertising) runCatching { bluetoothManager.stopAdvertising() }
                Log.i(
                    TAG,
                    "GATT connect quiet window: scan paused=$scanWasRunning, " +
                        "presence adv paused=$pauseAdvertising",
                )
                delay(GATT_CONNECT_WINDOW_SETTLE_MS)
                block()
            } finally {
                gattConnectWindowActive = false
                lastGattConnectWindowClosedAt = System.currentTimeMillis()
                // The pause is not a dead scanner; do not let the watchdog count it.
                lastProxyAdvertTime = System.currentTimeMillis()
                if (pauseAdvertising && bluetoothManager.isDetectEnabled &&
                    !bluetoothManager.isPresenceAdvertisingSuppressed()
                ) {
                    runCatching { bluetoothManager.startAdvertising() }
                }
                if (scanWasRunning && bluetoothProxyScanJob?.isActive == true && !proxyScanHardwareRunning) {
                    Log.i(TAG, "GATT connect quiet window closed; restarting proxy scan")
                    restartProxyScan()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun softStopProxyScanHardware() {
        if (!proxyScanHardwareRunning) return
        val scanner = bluetoothManager.getScanner() ?: return
        val callback = stableProxyScanCallback ?: return
        try {
            scanner.stopScan(callback)
        } catch (e: Exception) {
            Log.w(TAG, "Soft stop proxy scan failed: ${e.message}")
        }
        proxyScanHardwareRunning = false
    }

    companion object {
        private const val TAG = "VoiceSatelliteBluetooth"
        private const val MAX_SIMULATED_SLOTS_NORMAL = 5
        private const val MAX_SIMULATED_SLOTS_LOWEND = 3
        private const val MAX_SCREEN_OFF_FILTERS = 16
        private const val MAX_LEARNED_FILTER_VALUES = 64
        private const val SCREEN_OFF_FILTER_PREFS = "bluetooth_proxy_screen_off_filters"
        private const val KEY_GATEWAY_MANUFACTURERS = "gateway_manufacturer_ids"
        private const val KEY_GATEWAY_SERVICE_UUIDS = "gateway_service_uuids"
        private const val KEY_GATEWAY_SERVICE_DATA_UUIDS = "gateway_service_data_uuids"
        private const val KEY_GATEWAY_ADDRESSES = "gateway_device_addresses"
        private const val KEY_PRESENCE_MANUFACTURERS = "presence_manufacturer_ids"
        private const val KEY_PRESENCE_SERVICE_UUIDS = "presence_service_uuids"
        private const val KEY_PRESENCE_SERVICE_DATA_UUIDS = "presence_service_data_uuids"
        private const val KEY_PRESENCE_ADDRESSES = "presence_device_addresses"
        private const val GATEWAY_FILTER_PERSIST_DELAY_MS = 2_000L
        private const val PROXY_SCAN_SESSION_ROTATE_MS = 10 * 60_000L
        /** Controller settle time between stopping our scanner/advertiser and issuing connectGatt. */
        private const val GATT_CONNECT_WINDOW_SETTLE_MS = 300L
        /** Keeps window-induced scanner restarts under Android's 5-per-30s scan-start budget. */
        private const val GATT_CONNECT_WINDOW_MIN_INTERVAL_MS = 8_000L
        /** First kill cooldown after a proven stale scan. */
        private const val PROXY_SCAN_KILL_COOLDOWN_MIN_MS = 5 * 60_000L
        /** Cooldown after a recent kill (escalates from the 5-minute floor). */
        private const val PROXY_SCAN_KILL_COOLDOWN_MAX_MS = 10 * 60_000L
        /** Soft recoveries required on Android 8+ before killing the Bluetooth process. */
        private const val PROXY_SCAN_SOFT_RECOVERIES_BEFORE_KILL = 3
        /**
         * Android 8+: after first quiet crossing, keep watching this long before soft/kill.
         * Brief advert gaps and controller sleep are normal — do not treat them as dead.
         */
        private const val PROXY_SCAN_STALE_OBSERVE_GRACE_MS = 45_000L
        /** Android's default per-UID scan quota window is 30 seconds. */
        private const val PROXY_SCAN_QUOTA_RETRY_MS = 31_000L
        private const val PROXY_SCAN_START_RETRY_MS = 5_000L
        /**
         * Cap for the escalating retry delay. A scan that fails for a structural reason
         * (registration rejected, policy-blocked) never recovers by retrying every 5s — it
         * only burns CPU and floods the log ring buffer.
         */
        private const val PROXY_SCAN_RETRY_BACKOFF_MAX_MS = 60_000L
        // ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY, added as a public constant in API 26.
        private const val PROXY_SCAN_FAILED_TOO_FREQUENTLY = 6
        /**
         * Hold away decisions across a scan-strategy handover (screen on or off).
         * Observed hit latency is under 1s; this is a generous margin for slow chips
         * so the away timer cannot expire in the same instant the scanner is down.
         */
        private const val SCAN_HANDOVER_PRESENCE_GRACE_MS = 12_000L
        /** Stay below the five-minute long-scan downgrade used by older Android Bluetooth stacks. */
        private const val SCREEN_OFF_PROXY_SESSION_ROTATE_MS = 4 * 60_000L
        private const val SCREEN_OFF_PROXY_MAINTENANCE_RETRY_MS = 30_000L
        /**
         * Active stack-liveness probe window before any Bluetooth process kill. A quiet
         * environment (rural / night / empty room) produces zero adverts indefinitely, so
         * silence alone must never justify killing the stack.
         */
        private const val STACK_PROBE_WINDOW_MS = 3_000L
        /** How often to poll system Bluetooth while Device Detection is on. */
        private const val ADAPTER_KEEPALIVE_POLL_MS = 30_000L
        /** Minimum gap between enable attempts when the radio is off. */
        private const val ADAPTER_ENABLE_COOLDOWN_MIN_MS = 15_000L
        /** Cap for enable backoff while the radio keeps failing to stay on. */
        private const val ADAPTER_ENABLE_COOLDOWN_MAX_MS = 30_000L
    }

    /**
     * Android 7.x and below force long-running scans to opportunistic. Killing the Bluetooth
     * process is an acceptable recovery there. Android 8+ exempts filtered scans, so prefer
     * soft restart and only kill after repeated proven silence.
     */
    private fun allowsAggressiveBluetoothProcessKill(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O

    private fun proxyScanKillCooldownMs(): Long = proxyKillCooldownMs
    
    private val maxSimulatedSlots: Int
        get() = if (bluetoothManager.isLowEndBleChip()) MAX_SIMULATED_SLOTS_LOWEND else MAX_SIMULATED_SLOTS_NORMAL
    
    private fun ensureProxyManager(): BluetoothProxyManager? {
        if (proxyManager == null && sendMessageFunc != null) {
            proxyManager = newProxyManager(sendMessageFunc!!)
            Log.d(TAG, "ProxyManager initialized on demand")
        }
        return proxyManager
    }

    private fun newProxyManager(sendMessage: suspend (com.google.protobuf.MessageLite) -> Unit): BluetoothProxyManager =
        BluetoothProxyManager(context, scope, sendMessage, bluetoothManager, device.hashCode().toString()).also {
            // Simulated (claimed BTHome) slots ride on the same report as real GATT
            // connections so HA never sees a list that omits a live connection.
            it.simulatedAllocationsProvider = { getScannedDeviceAddresses() }
            it.connectWindow = { block -> runGattConnectWindow(block) }
        }
    
    fun setSendMessage(sendMessage: suspend (com.google.protobuf.MessageLite) -> Unit) {
        sendMessageFunc = sendMessage
    }

    fun init(sendMessage: suspend (com.google.protobuf.MessageLite) -> Unit) {
        sendMessageFunc = sendMessage
        _bluetoothScanPower.value = powerKeyToLabel(bluetoothManager.proxyScanPower)
        bluetoothManager.proxyScanPowerFlow
            .onEach { key -> _bluetoothScanPower.value = powerKeyToLabel(key) }
            .launchIn(scope)
        // Keep the HA select entity in sync with the pref no matter who wrote it:
        // Ava's settings screen, fleet, or HA's own SetModeRequest pin.
        _bluetoothScanMode.value = scanModeKeyToLabel(bluetoothManager.proxyScanMode)
        bluetoothManager.proxyScanModeFlow
            .onEach { key -> _bluetoothScanMode.value = scanModeKeyToLabel(key) }
            .launchIn(scope)
        proxyManager = newProxyManager(sendMessage)
        bluetoothManager.onClaimsChanged = { syncScannedDevicesWithClaims() }
        bindScreenState()

        if (ModBleAdvProxyBridge.isLegacyIntegrated(context)) {
            applyBleAdvModPresencePolicy()
            registerLegacyBleAdvCoordinator()
        } else if (ModBleAdvProxyBridge.isStandalone(context)) {
            ModBleAdvProxyBridge.applyStandaloneHostPolicy(context)
        }
        bindDetectEnabled()
        applyDetectEnabledState(bluetoothManager.isDetectEnabled)
        // Only armed here — the away pipeline decides when to fire it. The suppression this
        // counters survives the session rotation above, because it is keyed on display-off
        // duration rather than scan age, and only making the platform interactive clears it.
        DarkWakePulse.start(
            context = context,
            pulseAllowed = {
                bluetoothManager.isDetectEnabled &&
                    bluetoothManager.isScreenOffPersistenceEnabled &&
                    bluetoothManager.trackedDevices.value.isNotEmpty()
            },
            suppressAway = bluetoothManager::suppressAwayTimeouts,
        )
    }
    
    fun stop() {
        DarkWakePulse.stop()
        persistGatewayFiltersJob?.cancel()
        persistGatewayFiltersJob = null
        disconnectAllProxyConnections()
        detectEnabledListener?.let { detectPrefs.unregisterOnSharedPreferenceChangeListener(it) }
        detectEnabledListener = null
        screenStateJob?.cancel()
        screenStateJob = null
        stopAdapterKeepAlive()
        scanLoopJob?.cancel()
        scanLoopJob = null
        trackedDevicesJob?.cancel()
        trackedDevicesJob = null
        stopProxyScan()
        unregisterBluetoothStateReceiver()
        persistGatewayFilters()
        removeAllBluetoothEntities()
        proxyManager = null
        BleOperationCoordinator.unregister()
        if (!isBleAdvModActive()) {
            bluetoothManager.setPresenceAdvertisingSuppressed(false)
        }
        Log.d(TAG, "VoiceSatelliteBluetooth stopped")
    }

    private fun bindScreenState() {
        ScreenControlUtils.syncScreenState(context)
        lastProxyScreenOn = ScreenControlUtils.deviceAwakeState.value
        screenStateJob?.cancel()
        screenStateJob = ScreenControlUtils.deviceAwakeState
            .onEach { screenOn ->
                if (screenOn == lastProxyScreenOn) return@onEach
                lastProxyScreenOn = screenOn
                val hasProxySession = bluetoothProxyScanJob?.isActive == true ||
                    proxyScanSuspendedForScreenOff
                if (screenOn) {
                    holdPresenceAcrossScanHandover()
                    // Keep the running scanner. A forceRebuild here is what flashed the
                    // MediaTek adapter (and Wi-Fi) and dropped the HA socket. Filtered
                    // results still arrive while the display is on; maintenance rebuilds
                    // to unfiltered once wakefulness is stable.
                    lastProxyAdvertTime = System.currentTimeMillis()
                    if (hasProxySession && bluetoothManager.isDetectEnabled) {
                        if (proxyScanSuspendedForScreenOff) {
                            val func = sendMessageFunc
                            if (func != null) {
                                Log.i(TAG, "Screen on; resuming suspended proxy scan")
                                startProxyScan(func)
                            }
                        } else {
                            Log.i(TAG, "Screen on; keeping proxy scan, not rebuilding")
                            startProxyScanWatchdog()
                        }
                    }
                } else {
                    persistGatewayFilters()
                    proxyScanWatchdogJob?.cancel()
                    proxyScanWatchdogJob = null
                    // Unfiltered scans are suppressed while the display is off.
                    if (hasProxySession && bluetoothManager.isDetectEnabled) {
                        Log.i(TAG, "Screen off; switching proxy scan to filtered")
                        restartProxyScan(forceRebuild = true)
                    }
                }
            }
            .launchIn(scope)
    }

    private fun bindDetectEnabled() {
        detectEnabledListener?.let { detectPrefs.unregisterOnSharedPreferenceChangeListener(it) }
        detectEnabledListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "detect_enabled") {
                applyDetectEnabledState(bluetoothManager.isDetectEnabled)
            }
        }
        detectPrefs.registerOnSharedPreferenceChangeListener(detectEnabledListener)
    }

    private fun applyDetectEnabledState(enabled: Boolean) {
        if (enabled) {
            registerBluetoothEntities()
            bluetoothManager.refreshAllDevicePresence()
            startAdapterKeepAlive()
            startScanLoop()
        } else {
            stopAdapterKeepAlive()
            stopProxyScan()
            disconnectAllProxyConnections()
            scanLoopJob?.cancel()
            scanLoopJob = null
            trackedDevicesJob?.cancel()
            trackedDevicesJob = null
            bluetoothManager.stopAdvertising()
            bluetoothManager.stopClaimSync()
            bluetoothManager.clearAllPresence()
            removeAllBluetoothEntities()
        }
        Log.d(TAG, "Bluetooth detect enabled: $enabled")
    }

    private fun registerBluetoothEntities() {
        if (!bluetoothManager.isLowEndBleChip()) {
            trackedDevicesJob?.cancel()
            trackedDevicesJob = bluetoothManager.trackedDevices
                .onEach { trackedDevices ->
                    val removedAddresses = bluetoothEntities.keys - trackedDevices.keys
                    removedAddresses.forEach { address ->
                        bluetoothEntities.remove(address)?.let { entity ->
                            device.removeEntity(entity)
                            Log.d(TAG, "Removed bluetooth entity for: $address")
                        }
                    }

                    trackedDevices.forEach { (address, trackedDevice) ->
                        val deviceName = trackedDevice.name.ifBlank { address.takeLast(8) }
                        if (!bluetoothEntities.containsKey(address) && deviceName.isNotBlank()) {
                            val stateFlow = bluetoothManager.devicePresence.map { it[address] ?: false }
                            val entity = BinarySensorEntity(
                                key = address.hashCode(),
                                name = context.getString(R.string.bluetooth_device_presence, deviceName),
                                objectId = "bluetooth_${address.replace(":", "").lowercase()}_motion",
                                deviceClass = "motion",
                                getState = stateFlow
                            )
                            bluetoothEntities[address] = entity
                            device.addEntity(entity)
                            Log.d(TAG, "Dynamic registered bluetooth entity for: $deviceName")
                        }
                    }
                }
                .launchIn(scope)
        }

        if (staticBluetoothEntities.isNotEmpty()) return

        if (!bluetoothManager.isLowEndBleChip()) {
            staticBluetoothEntities += NumberEntity(
                key = "bluetooth_rssi_threshold".hashCode(),
                name = context.getString(R.string.entity_bluetooth_rssi_threshold),
                objectId = "bluetooth_rssi_threshold",
                minValue = -120f,
                maxValue = 0f,
                step = 1f,
                unitOfMeasurement = "dBm",
                icon = "mdi:signal",
                getState = bluetoothManager.rssiThresholdFlow.map { it.toFloat() },
                setState = { bluetoothManager.rssiThreshold = it.toInt() },
                entityCategory = EntityCategory.ENTITY_CATEGORY_CONFIG
            )

            staticBluetoothEntities += NumberEntity(
                key = "bluetooth_away_delay".hashCode(),
                name = context.getString(R.string.entity_bluetooth_away_delay),
                objectId = "bluetooth_away_delay",
                minValue = 5f,
                maxValue = 3600f,
                step = 5f,
                unitOfMeasurement = "s",
                icon = "mdi:timer-outline",
                getState = bluetoothManager.awayDelaySecondsFlow.map { it.toFloat() },
                setState = { bluetoothManager.awayDelaySeconds = it.toInt() },
                entityCategory = EntityCategory.ENTITY_CATEGORY_CONFIG
            )
        }

        staticBluetoothEntities += SelectEntity(
            key = "bluetooth_proxy_scan_mode".hashCode(),
            name = context.getString(R.string.entity_bluetooth_scan_mode),
            objectId = "bluetooth_proxy_scan_mode",
            options = bluetoothScanModeOptions,
            icon = "mdi:bluetooth-settings",
            getState = _bluetoothScanMode,
            setState = { mode ->
                _bluetoothScanMode.value = mode
                // Writing the pref is enough: VoiceSatellite watches proxyScanModeFlow
                // and pushes configured_mode for every writer, keeping the runtime
                // mode HA pinned intact.
                bluetoothManager.proxyScanMode = scanModeLabelToKey(mode)
            },
            entityCategory = EntityCategory.ENTITY_CATEGORY_CONFIG
        )

        staticBluetoothEntities += SelectEntity(
            key = "bluetooth_proxy_scan_power".hashCode(),
            name = context.getString(R.string.entity_bluetooth_scan_power),
            objectId = "bluetooth_proxy_scan_power",
            options = bluetoothScanPowerOptions,
            icon = "mdi:bluetooth-transfer",
            getState = _bluetoothScanPower,
            setState = { power ->
                _bluetoothScanPower.value = power
                bluetoothManager.proxyScanPower = powerLabelToKey(power)
                Log.d(TAG, "Bluetooth scan power: $power")
                restartProxyScan()
            },
            entityCategory = EntityCategory.ENTITY_CATEGORY_CONFIG
        )

        staticBluetoothEntities.forEach(device::addEntity)
    }

    private fun removeAllBluetoothEntities() {
        bluetoothEntities.values.forEach(device::removeEntity)
        bluetoothEntities.clear()
        staticBluetoothEntities.forEach(device::removeEntity)
        staticBluetoothEntities.clear()
    }

    @SuppressLint("MissingPermission")
    private fun startScanLoop() {
        scanLoopJob?.cancel()
        scanLoopJob = scope.launch {
            if (!bluetoothManager.hasBluetoothPermissions()) {
                Log.w(TAG, "Missing bluetooth permissions for scanning")
                return@launch
            }
            
            while (true) {
                // A registered proxy scan listens continuously, so it is coverage on its
                // own — every branch below may skip its own scan and still let the away
                // timer run against real observation.
                if (proxyScanHardwareRunning && !BleOperationCoordinator.isExclusiveActive) {
                    bluetoothManager.noteScanCoverage()
                }
                val tracked = bluetoothManager.trackedDevices.value
                val detectEnabled = bluetoothManager.isDetectEnabled
                
                if (!detectEnabled) {
                    bluetoothManager.stopAdvertising()
                    bluetoothManager.checkTimeouts()
                    delay(bluetoothManager.scanIntervalSeconds * 1000L)
                    continue
                }

                if (bluetoothManager.isBluetoothStrictlyOff()) {
                    // Do not enable here — a brief Off flash would toggle the radio.
                    // Adapter keep-alive confirms a stable Off before privileged enable.
                    bluetoothManager.checkTimeouts()
                    delay(bluetoothManager.scanIntervalSeconds * 1000L)
                    continue
                }

                if (!isBleAdvLegacyIntegrated() && !bluetoothManager.isPresenceAdvertisingSuppressed()) {
                    bluetoothManager.startAdvertising()
                }
                
                val isLowEndChip = bluetoothManager.isLowEndBleChip()
                // Proxy already settles presence from its own callbacks. A second API
                // scanner on the same radio is what pinned MediaTek at ~45% bluetooth CPU.
                // Keep the independent 6s cycle only when this process is not already scanning.
                val proxyOwnsScanner = bluetoothProxyScanJob?.isActive == true
                val runDetectScanner = !isBleAdvLegacyIntegrated() && !isLowEndChip &&
                    !proxyOwnsScanner &&
                    tracked.isNotEmpty() && bluetoothManager.isBluetoothEnabled()
                // Compatibility-only: duty-cycle API scan and optional root HCI helper.
                val runCompatDetectScanner = !isBleAdvLegacyIntegrated() && isLowEndChip &&
                    tracked.isNotEmpty() && bluetoothManager.isBluetoothEnabled() &&
                    !detectScanSuppressedByExclusive
                if (runDetectScanner) {
                    runFullPresenceScanCycle(tracked)
                } else if (runCompatDetectScanner) {
                    runCompatPresenceScanCycle()
                } else {
                    bluetoothManager.checkTimeouts()
                    delay(bluetoothManager.scanIntervalSeconds * 1000L)
                }
            }
        }
    }

    /**
     * Full/balanced presence scan. Android suppresses unfiltered results while the display
     * is off, so this path never starts a screen-off session with an empty filter list —
     * presence then rides the filtered proxy scan (or waits until the screen is interactive
     * again / address filters become available).
     */
    @SuppressLint("MissingPermission")
    private suspend fun runFullPresenceScanCycle(
        tracked: Map<String, BluetoothPresenceManager.TrackedDevice>,
    ) {
        val scanner = bluetoothManager.getScanner()
        if (scanner == null) {
            bluetoothManager.checkTimeouts()
            delay(bluetoothManager.scanIntervalSeconds * 1000L)
            return
        }

        val filters = if (bluetoothManager.shouldUseScanFilters()) {
            val filterAddresses = bluetoothManager.presenceResolvingFilterAddresses()
                .ifEmpty { tracked.keys }
            filterAddresses.mapNotNull { address ->
                try {
                    android.bluetooth.le.ScanFilter.Builder()
                        .setDeviceAddress(address)
                        .build()
                } catch (_: Exception) {
                    null
                }
            }
        } else {
            emptyList()
        }
        if (!isProxyScanScreenOn() && filters.isEmpty()) {
            Log.d(
                TAG,
                "Skipping unfiltered presence scan while display is off " +
                    "(platform would suppress results)",
            )
            bluetoothManager.checkTimeouts()
            delay(bluetoothManager.scanIntervalSeconds * 1000L)
            return
        }

        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val scanWakeLock = pm.newWakeLock(
            android.os.PowerManager.PARTIAL_WAKE_LOCK,
            "Ava::BluetoothScan",
        )
        try {
            scanWakeLock.acquire(60_000)
            val scanIntervalMs = bluetoothManager.scanIntervalSeconds * 1000L
            val settings = android.bluetooth.le.ScanSettings.Builder()
                .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_BALANCED)
                .setReportDelay(0)
                .build()

            bluetoothManager.resetScanCycle()
            val scanCallback = getOrCreateScanCallback()
            presenceAddressFilterIdentities = filters
                .mapNotNull { it.deviceAddress }
                .toSet()
                .takeIf { it.isNotEmpty() }
            try {
                scanner.startScan(filters.ifEmpty { null }, settings, scanCallback)

                delay(scanIntervalMs)
                try {
                    scanner.stopScan(scanCallback)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to stop scan: ${e.message}")
                }
                bluetoothManager.noteScanCoverage()
                bluetoothManager.finalizeScanCycle()
                bluetoothManager.checkTimeouts()
            } finally {
                presenceAddressFilterIdentities = null
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            currentScanCallback?.let { callback ->
                try {
                    scanner.stopScan(callback)
                } catch (stopError: Exception) {
                    Log.w(TAG, "Failed to stop scan on cancellation: ${stopError.message}")
                }
            }
            throw e
        } catch (e: Exception) {
            bluetoothManager.resetScanCycle()
            Log.e(TAG, "BLE scan error", e)
            delay(bluetoothManager.scanIntervalSeconds * 1000L)
        } finally {
            if (scanWakeLock.isHeld) {
                scanWakeLock.release()
            }
        }
    }

    /**
     * Compatibility-mode presence only ([BluetoothPresenceManager.isLowEndBleChip]).
     * Prefers root HCI helper when available; otherwise Android API duty-cycle scan.
     * Yields entirely while proxy scan owns the radio (single scanner slot on low-end chips).
     * Full/balanced devices never enter here.
     */
    @SuppressLint("MissingPermission")
    private suspend fun runCompatPresenceScanCycle() {
        val windowMs = BluetoothCompatPresenceController.SCAN_WINDOW_MS
        val restMs = BluetoothCompatPresenceController.SCAN_REST_MS

        // Single-scanner chips (capability COMPATIBILITY, or mod override): while HA proxy
        // owns the API scanner, skip HCI + duty-cycle. Presence is settled from proxy
        // callbacks via settleImmediately; checkTimeouts still runs the away timer.
        val proxyScanBusy = bluetoothProxyScanJob?.isActive == true
        if (proxyScanBusy) {
            bluetoothManager.checkTimeouts()
            delay(restMs)
            return
        }

        // Path A: root HCI/MGMT takeover scan.
        if (BleHciLeScanHelper.isRunnable(context)) {
            bluetoothManager.resetScanCycle()
            val detailed = withContext(Dispatchers.IO) {
                BleHciLeScanHelper.scanDetailed(context, windowMs)
            }
            if (detailed == null) {
                Log.i(TAG, "compat: HCI helper unavailable, using API duty-cycle")
            } else {
                detailed.hits.forEach { bluetoothManager.onDeviceScanned(it.address, it.rssi) }
                bluetoothManager.noteScanCoverage()
                bluetoothManager.finalizeScanCycle()
                bluetoothManager.checkTimeouts()
                BluetoothCompatPresenceController.noteScanSuccess()
                Log.d(
                    TAG,
                    "compat HCI mode=${detailed.mode} transport=${detailed.transport} hits=${detailed.hits.size}",
                )
                // own mode restarts Android BT — give it time before next cycle / API use
                val settle = if (detailed.mode == "own") restMs + 8_000L else restMs
                delay(settle)
                return
            }
        }

        // Path B: Android API short scan window + long rest + medic.
        // Unfiltered API scans are suppressed while the display is off (Android 8+). Starting
        // one here would only feed the medic with false positives — the same trap that
        // previously reset the adapter on Echo Show satellites. Root HCI (Path A) bypasses
        // the platform scanner and is still allowed.
        if (!isProxyScanScreenOn()) {
            Log.d(
                TAG,
                "Skipping unfiltered compat API presence scan while display is off",
            )
            bluetoothManager.checkTimeouts()
            delay(restMs)
            return
        }
        if (!bluetoothManager.hasBluetoothPermissions()) {
            bluetoothManager.checkTimeouts()
            delay(restMs)
            return
        }
        val scanner = bluetoothManager.getScanner()
        if (scanner == null) {
            val medic = BluetoothCompatPresenceController.noteScanFailure(
                bluetoothManager,
                "scanner_null",
            )
            bluetoothManager.checkTimeouts()
            delay(if (medic) restMs + 5_000L else restMs)
            return
        }

        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val wakeLock = pm.newWakeLock(
            android.os.PowerManager.PARTIAL_WAKE_LOCK,
            "Ava::BluetoothCompatScan",
        )
        var scanFailed = false
        var scanFailureCode = -1
        val failLock = Any()
        val callback = object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
                bluetoothManager.onDeviceScanned(
                    result.device.address,
                    result.rssi,
                    device = result.device,
                )
            }

            override fun onBatchScanResults(results: MutableList<android.bluetooth.le.ScanResult>) {
                results.forEach {
                    bluetoothManager.onDeviceScanned(
                        it.device.address,
                        it.rssi,
                        device = it.device,
                    )
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "compat API scan failed: $errorCode")
                synchronized(failLock) {
                    scanFailed = true
                    scanFailureCode = errorCode
                }
            }
        }

        try {
            wakeLock.acquire(windowMs + 10_000L)
            bluetoothManager.resetScanCycle()
            val settings = android.bluetooth.le.ScanSettings.Builder()
                .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_POWER)
                .setReportDelay(0)
                .build()
            // No address filters: rotating RPAs + fragile offload on low-end chips.
            scanner.startScan(null, settings, callback)
            delay(windowMs)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "compat API scan error", e)
            synchronized(failLock) {
                scanFailed = true
                scanFailureCode = -2
            }
        } finally {
            try {
                scanner.stopScan(callback)
            } catch (e: Exception) {
                Log.w(TAG, "compat stopScan: ${e.message}")
            }
            if (wakeLock.isHeld) {
                wakeLock.release()
            }
        }

        val (failed, failureCode) = synchronized(failLock) { scanFailed to scanFailureCode }
        if (failed) {
            // Registration refused / internal error is about this app's scanner slots, not a
            // dead adapter — resetting Bluetooth would clear our leak and the loop restarts.
            val allowMedic = failureCode != android.bluetooth.le.ScanCallback.SCAN_FAILED_INTERNAL_ERROR &&
                failureCode != android.bluetooth.le.ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED &&
                failureCode != -2
            val medic = BluetoothCompatPresenceController.noteScanFailure(
                bluetoothManager,
                if (allowMedic) "api_scan_failed:$failureCode" else "api_scan_registration:$failureCode",
                allowMedic = allowMedic,
            )
            bluetoothManager.resetScanCycle()
            bluetoothManager.checkTimeouts()
            delay(if (medic) restMs + 5_000L else restMs)
            return
        }

        bluetoothManager.noteScanCoverage()
        bluetoothManager.finalizeScanCycle()
        bluetoothManager.checkTimeouts()
        BluetoothCompatPresenceController.noteScanSuccess()
        delay(restMs)
    }

    private fun acquireDeviceProxyBlePolicy() {
        if (isBleAdvLegacyIntegrated() || deviceProxySuppressedHostAdvertising) return
        if (!ModDeviceSupport.suppressHostBleAdvertisingDuringProxy(context)) return

        deviceProxySuppressedHostAdvertising = true
        bluetoothManager.setPresenceAdvertisingSuppressed(true)
        Log.i(TAG, "Device BLE policy: host advertising paused for proxy scan")
    }

    private suspend fun awaitDeviceProxyBleHandover() {
        if (!deviceProxySuppressedHostAdvertising) return
        val settleMs = ModDeviceSupport.getBleProxyHandoverDelayMs(context).toLong()
        if (settleMs > 0) delay(settleMs)
    }

    private fun scheduleProxyScanRecovery(errorCode: Int) {
        if (proxyScanRecoveryJob?.isActive == true) return

        proxyScanRecoveryJob = scope.launch {
            if (errorCode == PROXY_SCAN_FAILED_TOO_FREQUENTLY) {
                Log.w(
                    TAG,
                    "Proxy scan rate-limited; waiting for scan quota before retry",
                )
                delay(PROXY_SCAN_QUOTA_RETRY_MS)
                if (!isActive || !bluetoothManager.isDetectEnabled) return@launch
                proxyScanRecoveryJob = null
                restartProxyScan(forceRebuild = true)
                return@launch
            }

            val connectionSlots = proxyManager?.getConnectionsFree()
            // Only skip hard recovery when every GATT slot is busy.
            val fullyBooked = connectionSlots != null && connectionSlots.first == 0

            val recoveredByMod = if (fullyBooked) {
                Log.w(TAG, "Skipping device Bluetooth recovery while proxy GATT is fully booked")
                false
            } else {
                withContext(Dispatchers.IO) {
                    ModDeviceSupport.recoverBluetoothProxyScanFailure(context, errorCode)
                }
            }

            val retryDelayMs = if (recoveredByMod) 1_500L else nextProxyScanRetryDelayMs()
            delay(retryDelayMs)
            if (!isActive || !bluetoothManager.isDetectEnabled) return@launch

            Log.i(
                TAG,
                if (recoveredByMod) {
                    "Retrying proxy scan after device mod Bluetooth recovery"
                } else {
                    "Retrying proxy scan after failure (waited ${retryDelayMs}ms)"
                },
            )
            proxyScanRecoveryJob = null
            restartProxyScan(forceRebuild = true)
        }
    }

    /** Retry an explicit scanner acquisition/start failure without touching the Bluetooth process. */
    private fun scheduleProxyScanStartRetry(reason: String) {
        if (proxyScanRecoveryJob?.isActive == true) return

        proxyScanRecoveryJob = scope.launch {
            val retryDelayMs = nextProxyScanRetryDelayMs()
            Log.w(TAG, "$reason; retrying proxy scan in ${retryDelayMs}ms")
            delay(retryDelayMs)
            if (!isActive || !bluetoothManager.isDetectEnabled) return@launch
            proxyScanRecoveryJob = null
            // A newer request may already have established a healthy session.
            if (bluetoothProxyScanJob?.isActive == true) return@launch
            restartProxyScan(forceRebuild = true)
        }
    }

    /**
     * Delay before the next scan-start attempt, doubling while failures persist. A scan that
     * fails structurally never recovers on a fixed 5s retry; it just churns the scanner and
     * the log. Reset by [markProxyAdvertReceived] once adverts flow again.
     */
    private fun nextProxyScanRetryDelayMs(): Long {
        val delayMs = proxyScanRetryBackoffMs
        proxyScanRetryBackoffMs = (delayMs * 2).coerceAtMost(PROXY_SCAN_RETRY_BACKOFF_MAX_MS)
        return delayMs
    }

    private fun releaseDeviceProxyBlePolicy() {
        if (!deviceProxySuppressedHostAdvertising) return
        deviceProxySuppressedHostAdvertising = false
        if (!isBleAdvModActive()) {
            bluetoothManager.setPresenceAdvertisingSuppressed(false)
            Log.i(TAG, "Device BLE policy: host advertising released")
        }
    }
    
    private var bluetoothProxyScanJob: Job? = null
    private var proxyScanWatchdogJob: Job? = null
    private var proxyScreenOffMaintenanceJob: Job? = null
    @Volatile private var proxyScanStartedAt = 0L
    @Volatile private var lastProxyAdvertTime = 0L
    @Volatile private var proxyAdvertCount = 0L
    @Volatile private var pendingProxyScanRestart = false
    @Volatile private var lastProxyKillAt = 0L
    /** 5 min after a healthy scan; escalates to 10 min after each kill. */
    @Volatile private var proxyKillCooldownMs = PROXY_SCAN_KILL_COOLDOWN_MIN_MS
    /** Consecutive soft recoveries while still stale; reset when adverts resume. */
    @Volatile private var consecutiveStaleSoftRecoveries = 0
    /** Android 8+: when quiet first crossed staleThreshold; 0 = not observing. */
    @Volatile private var proxyStaleObserveStartedAt = 0L
    /** Escalating delay before retrying a failed scan start; reset when adverts resume. */
    @Volatile private var proxyScanRetryBackoffMs = PROXY_SCAN_START_RETRY_MS
    /** Screen-off session skipped because no filter targets are known yet. */
    @Volatile private var proxyScanSuspendedForScreenOff = false
    /** Display state the live session picked its scan strategy for. */
    @Volatile private var proxyScanBuiltForScreenOn = true
    private var proxyScreenOffSuspendedRecheckJob: Job? = null
    @Volatile private var lastAdapterEnableAt = 0L
    @Volatile private var adapterEnableCooldownMs = ADAPTER_ENABLE_COOLDOWN_MIN_MS
    @Volatile private var pendingAdapterEnable = false
    private var bluetoothStateReceiver: BroadcastReceiver? = null
    private var adapterKeepAliveJob: Job? = null
    private var proxyScanWasActiveBeforeRadioOff = false
    
    private data class AdvertDedup(val payloadHash: Int, val sentAt: Long)
    private val advertDedupCache = mutableMapOf<Long, AdvertDedup>()
    private val advertDedupWindowMs: Long
        get() = if (bluetoothManager.isLowEndBleChip()) 5000L else 2000L

    /**
     * Screen state as the *platform scanner* sees it, which is the only one that decides
     * whether an unfiltered scan survives.
     *
     * [ScreenControlUtils.deviceAwakeState] is PowerManager wakefulness, not panel power.
     * Blanking the panel leaves the device awake, so Android still delivers unfiltered
     * scans; sleeping it does not. Trusting panel state here would treat a blanked-but-awake
     * device as screen-off and drop the scanner.
     *
     * Either signal reporting "off" is treated as off: a filtered scan still delivers while
     * the screen is on, an unfiltered one does not survive screen-off.
     */
    private fun isProxyScanScreenOn(): Boolean {
        if (!lastProxyScreenOn) return false
        val powerManager = context.getSystemService(Context.POWER_SERVICE)
            as? android.os.PowerManager ?: return true
        return powerManager.isInteractive
    }

    private sealed interface ProxyScanFilterPlan {
        /** Screen on: the platform delivers unfiltered results. */
        object Unfiltered : ProxyScanFilterPlan

        class Filtered(
            val filters: List<android.bluetooth.le.ScanFilter>,
            val addressIdentities: Set<String>,
            val addressFiltersOnly: Boolean,
        ) : ProxyScanFilterPlan

        /**
         * Screen off with nothing to filter on. Android suppresses unfiltered screen-off
         * scans, so a session started here can never deliver a result — it would only look
         * like a wedged scanner to the watchdog.
         */
        object SuspendedScreenOff : ProxyScanFilterPlan
    }

    /** Android suspends unfiltered BLE scans while the display is off. */
    @SuppressLint("MissingPermission")
    private fun buildProxyScanFilters(): ProxyScanFilterPlan {
        if (isProxyScanScreenOn()) return ProxyScanFilterPlan.Unfiltered

        val presenceFilters = linkedMapOf<String, android.bluetooth.le.ScanFilter>()
        val identityAddresses = linkedSetOf<String>()
        bluetoothManager.trackedDevices.value.keys.forEach { identityAddresses.add(it.uppercase()) }
        bluetoothManager.presenceResolvingFilterAddresses().forEach {
            identityAddresses.add(it.uppercase())
        }
        synchronized(screenOffFilterLock) {
            identityAddresses.addAll(presenceDeviceAddresses)
        }
        identityAddresses.forEach { address ->
            runCatching {
                android.bluetooth.le.ScanFilter.Builder()
                    .setDeviceAddress(address)
                    .build()
            }.onSuccess { presenceFilters["address:$address"] = it }
        }

        val learned = synchronized(screenOffFilterLock) {
            ScreenOffFilterSnapshot(
                presenceManufacturerIds = presenceManufacturerIds.toList().asReversed(),
                presenceServiceUuids = presenceServiceUuids.toList().asReversed(),
                presenceServiceDataUuids = presenceServiceDataUuids.toList().asReversed(),
                gatewayManufacturerIds = gatewayManufacturerIds.toList().asReversed(),
                gatewayServiceUuids = gatewayServiceUuids.toList().asReversed(),
                gatewayServiceDataUuids = gatewayServiceDataUuids.toList().asReversed(),
                gatewayDeviceAddresses = gatewayDeviceAddresses.toList().asReversed(),
            )
        }
        learned.presenceManufacturerIds.forEach { manufacturerId ->
            runCatching {
                android.bluetooth.le.ScanFilter.Builder()
                    .setManufacturerData(manufacturerId, byteArrayOf())
                    .build()
            }.onSuccess { presenceFilters["manufacturer:$manufacturerId"] = it }
        }
        learned.presenceServiceUuids.forEach { serviceUuid ->
            runCatching {
                android.bluetooth.le.ScanFilter.Builder()
                    .setServiceUuid(serviceUuid)
                    .build()
            }.onSuccess { presenceFilters["service:$serviceUuid"] = it }
        }
        learned.presenceServiceDataUuids.forEach { serviceUuid ->
            runCatching {
                android.bluetooth.le.ScanFilter.Builder()
                    .setServiceData(serviceUuid, byteArrayOf())
                    .build()
            }.onSuccess { presenceFilters["service-data:$serviceUuid"] = it }
        }

        val gatewayFilters = linkedMapOf<String, android.bluetooth.le.ScanFilter>()
        learned.gatewayManufacturerIds.forEach { manufacturerId ->
            runCatching {
                android.bluetooth.le.ScanFilter.Builder()
                    .setManufacturerData(manufacturerId, byteArrayOf())
                    .build()
            }.onSuccess { gatewayFilters["manufacturer:$manufacturerId"] = it }
        }
        learned.gatewayServiceDataUuids.forEach { serviceUuid ->
            runCatching {
                android.bluetooth.le.ScanFilter.Builder()
                    .setServiceData(serviceUuid, byteArrayOf())
                    .build()
            }.onSuccess { gatewayFilters["service-data:$serviceUuid"] = it }
        }
        learned.gatewayServiceUuids.forEach { serviceUuid ->
            runCatching {
                android.bluetooth.le.ScanFilter.Builder()
                    .setServiceUuid(serviceUuid)
                    .build()
            }.onSuccess { gatewayFilters["service:$serviceUuid"] = it }
        }
        learned.gatewayDeviceAddresses.forEach { address ->
            runCatching {
                android.bluetooth.le.ScanFilter.Builder()
                    .setDeviceAddress(address)
                    .build()
            }.onSuccess { gatewayFilters["address:$address"] = it }
        }

        val prioritizedPresenceFilters = prioritizeScreenOffFilters(
            presenceFilters,
            listOf("address:", "manufacturer:", "service-data:", "service:"),
        )
        val prioritizedGatewayFilters = prioritizeScreenOffFilters(
            gatewayFilters,
            listOf("manufacturer:", "service-data:", "service:", "address:"),
        )
        val filters = mergeScreenOffFilters(prioritizedPresenceFilters, prioritizedGatewayFilters)
        if (filters.isEmpty()) {
            Log.w(
                TAG,
                "Screen-off proxy scan has no known targets; suspending until the screen turns " +
                    "on — an unfiltered screen-off scan cannot return results",
            )
            return ProxyScanFilterPlan.SuspendedScreenOff
        }
        val addressIdentities = filters.mapNotNull { it.deviceAddress?.uppercase() }.toSet()
        val addressFiltersOnly = filters.isNotEmpty() && filters.all { isAddressOnlyScanFilter(it) }
        Log.i(
            TAG,
            "Using ${filters.size} screen-off proxy filters " +
                "(presence=${presenceFilters.size}, gateway=${gatewayFilters.size}, " +
                "pairedAddresses=${addressIdentities.size}, addressOnly=$addressFiltersOnly)",
        )
        return ProxyScanFilterPlan.Filtered(filters, addressIdentities, addressFiltersOnly)
    }

    private fun isAddressOnlyScanFilter(filter: android.bluetooth.le.ScanFilter): Boolean {
        if (filter.deviceAddress.isNullOrBlank()) return false
        if (!filter.deviceName.isNullOrBlank()) return false
        if (filter.serviceUuid != null) return false
        if (filter.serviceDataUuid != null) return false
        return filter.manufacturerId < 0
    }

    @SuppressLint("MissingPermission")
    private fun rememberProxyScanFilters(result: android.bluetooth.le.ScanResult) {
        val record = result.scanRecord ?: return
        var gatewayChanged = false
        var presenceChanged = false
        synchronized(screenOffFilterLock) {
            val manufacturerData = record.manufacturerSpecificData
            val manufacturerIds = buildList {
                for (index in 0 until manufacturerData.size()) {
                    add(manufacturerData.keyAt(index))
                }
            }
            val serviceUuids = record.serviceUuids.orEmpty()
            val serviceDataUuids = record.serviceData?.keys.orEmpty()

            manufacturerIds.forEach { manufacturerId ->
                gatewayChanged = gatewayManufacturerIds.touchCapped(
                    manufacturerId,
                    MAX_LEARNED_FILTER_VALUES,
                ) || gatewayChanged
            }
            serviceUuids.forEach { serviceUuid ->
                gatewayChanged = gatewayServiceUuids.touchCapped(
                    serviceUuid,
                    MAX_LEARNED_FILTER_VALUES,
                ) || gatewayChanged
            }
            serviceDataUuids.forEach { serviceUuid ->
                gatewayChanged = gatewayServiceDataUuids.touchCapped(
                    serviceUuid,
                    MAX_LEARNED_FILTER_VALUES,
                ) || gatewayChanged
            }
            // IRK-aware attribution: iPhones advertise with a rotating RPA that never
            // equals the stored tracked address. A raw containsKey() here never learns
            // their manufacturer/service attributes, leaving screen-off presence filters
            // with only stale MAC filters — presence then decays to away while the
            // screen is off and snaps back on screen-on (observed on MI 9).
            if (!bluetoothManager.isTrackedAdvert(
                    result.device.address,
                    result.device,
                    proxyScreenOffAddressIdentities,
                    proxyScreenOffAddressFiltersOnly,
                )
            ) {
                return@synchronized
            }
            for (index in 0 until manufacturerData.size()) {
                presenceChanged = presenceManufacturerIds.addCapped(
                    manufacturerData.keyAt(index),
                    MAX_LEARNED_FILTER_VALUES,
                ) || presenceChanged
            }
            serviceUuids.forEach { uuid ->
                presenceChanged =
                    presenceServiceUuids.addCapped(uuid, MAX_LEARNED_FILTER_VALUES) || presenceChanged
            }
            serviceDataUuids.forEach { uuid ->
                presenceChanged =
                    presenceServiceDataUuids.addCapped(uuid, MAX_LEARNED_FILTER_VALUES) || presenceChanged
            }
        }
        if (gatewayChanged || presenceChanged) scheduleGatewayFilterPersist()
    }

    private fun <T> LinkedHashSet<T>.addCapped(value: T, maxSize: Int): Boolean {
        if (!add(value)) return false
        while (size > maxSize) remove(first())
        return true
    }

    private fun <T> LinkedHashSet<T>.touchCapped(value: T, maxSize: Int): Boolean {
        val isNew = remove(value).not()
        add(value)
        while (size > maxSize) remove(first())
        return isNew
    }

    private data class ScreenOffFilterSnapshot(
        val presenceManufacturerIds: List<Int>,
        val presenceServiceUuids: List<android.os.ParcelUuid>,
        val presenceServiceDataUuids: List<android.os.ParcelUuid>,
        val gatewayManufacturerIds: List<Int>,
        val gatewayServiceUuids: List<android.os.ParcelUuid>,
        val gatewayServiceDataUuids: List<android.os.ParcelUuid>,
        val gatewayDeviceAddresses: List<String>,
    )

    private data class GatewayFilterSnapshot(
        val manufacturerIds: List<String>,
        val serviceUuids: List<String>,
        val serviceDataUuids: List<String>,
        val deviceAddresses: List<String>,
    )

    private fun mergeScreenOffFilters(
        presence: LinkedHashMap<String, android.bluetooth.le.ScanFilter>,
        gateway: LinkedHashMap<String, android.bluetooth.le.ScanFilter>,
    ): List<android.bluetooth.le.ScanFilter> {
        val selected = linkedMapOf<String, android.bluetooth.le.ScanFilter>()
        // Paired / tracked identity MACs must survive the 16-slot cap. Gateway
        // manufacturer filters used to take the first 8 and could crowd them out.
        presence.entries.filter { it.key.startsWith("address:") }.forEach { entry ->
            if (selected.size >= MAX_SCREEN_OFF_FILTERS) return@forEach
            selected[entry.key] = entry.value
        }
        val reservedPerOwner = ((MAX_SCREEN_OFF_FILTERS - selected.size) / 2).coerceAtLeast(0)

        gateway.entries.take(reservedPerOwner).forEach { selected[it.key] = it.value }
        presence.entries.take(reservedPerOwner).forEach { selected.putIfAbsent(it.key, it.value) }
        (gateway.entries + presence.entries).forEach { entry ->
            if (selected.size >= MAX_SCREEN_OFF_FILTERS) return@forEach
            selected.putIfAbsent(entry.key, entry.value)
        }
        return selected.values.toList()
    }

    private fun prioritizeScreenOffFilters(
        filters: LinkedHashMap<String, android.bluetooth.le.ScanFilter>,
        kindPrefixes: List<String>,
    ): LinkedHashMap<String, android.bluetooth.le.ScanFilter> {
        val iterators = kindPrefixes.map { prefix ->
            filters.entries.filter { it.key.startsWith(prefix) }.iterator()
        }
        val prioritized = linkedMapOf<String, android.bluetooth.le.ScanFilter>()
        while (iterators.any { it.hasNext() }) {
            iterators.forEach { iterator ->
                if (iterator.hasNext()) {
                    val entry = iterator.next()
                    prioritized[entry.key] = entry.value
                }
            }
        }
        return prioritized
    }

    private fun loadPersistedFilterValues(key: String): List<String> {
        val raw = screenOffFilterPrefs.getString(key, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val value = array.optString(index)
                    if (value.isNotBlank()) add(value)
                }
            }
        }.onFailure {
            Log.w(TAG, "Ignoring invalid persisted screen-off filter list: $key", it)
        }.getOrDefault(emptyList())
    }

    private fun scheduleGatewayFilterPersist() {
        if (persistGatewayFiltersJob?.isActive == true) return
        persistGatewayFiltersJob = scope.launch {
            delay(GATEWAY_FILTER_PERSIST_DELAY_MS)
            persistGatewayFilters()
        }
    }

    private fun persistGatewayFilters() {
        data class FilterPersistSnapshot(
            val gateway: GatewayFilterSnapshot,
            val presenceManufacturerIds: List<String>,
            val presenceServiceUuids: List<String>,
            val presenceServiceDataUuids: List<String>,
            val presenceDeviceAddresses: List<String>,
        )

        val livePresenceAddresses = linkedSetOf<String>()
        bluetoothManager.trackedDevices.value.keys.forEach {
            livePresenceAddresses.add(it.uppercase())
        }
        bluetoothManager.presenceResolvingFilterAddresses().forEach {
            livePresenceAddresses.add(it.uppercase())
        }

        val snapshot = synchronized(screenOffFilterLock) {
            presenceDeviceAddresses.addAll(livePresenceAddresses)
            while (presenceDeviceAddresses.size > MAX_LEARNED_FILTER_VALUES) {
                presenceDeviceAddresses.remove(presenceDeviceAddresses.first())
            }
            FilterPersistSnapshot(
                gateway = GatewayFilterSnapshot(
                    manufacturerIds = gatewayManufacturerIds.map { it.toString() },
                    serviceUuids = gatewayServiceUuids.map { it.toString() },
                    serviceDataUuids = gatewayServiceDataUuids.map { it.toString() },
                    deviceAddresses = gatewayDeviceAddresses.toList(),
                ),
                presenceManufacturerIds = presenceManufacturerIds.map { it.toString() },
                presenceServiceUuids = presenceServiceUuids.map { it.toString() },
                presenceServiceDataUuids = presenceServiceDataUuids.map { it.toString() },
                presenceDeviceAddresses = presenceDeviceAddresses.toList(),
            )
        }
        screenOffFilterPrefs.edit()
            .putString(KEY_GATEWAY_MANUFACTURERS, JSONArray(snapshot.gateway.manufacturerIds).toString())
            .putString(KEY_GATEWAY_SERVICE_UUIDS, JSONArray(snapshot.gateway.serviceUuids).toString())
            .putString(KEY_GATEWAY_SERVICE_DATA_UUIDS, JSONArray(snapshot.gateway.serviceDataUuids).toString())
            .putString(KEY_GATEWAY_ADDRESSES, JSONArray(snapshot.gateway.deviceAddresses).toString())
            .putString(KEY_PRESENCE_MANUFACTURERS, JSONArray(snapshot.presenceManufacturerIds).toString())
            .putString(KEY_PRESENCE_SERVICE_UUIDS, JSONArray(snapshot.presenceServiceUuids).toString())
            .putString(KEY_PRESENCE_SERVICE_DATA_UUIDS, JSONArray(snapshot.presenceServiceDataUuids).toString())
            .putString(KEY_PRESENCE_ADDRESSES, JSONArray(snapshot.presenceDeviceAddresses).toString())
            .apply()
    }
    
    
    fun startProxyScan(sendMessage: suspend (com.google.protobuf.MessageLite) -> Unit) {
        if (!bluetoothManager.isDetectEnabled) {
            Log.i(TAG, "Bluetooth detect disabled, skip proxy scan start")
            stopProxyScan()
            return
        }
        if (bluetoothProxyScanJob?.isActive == true) {
            return
        }
        val filterPlan = buildProxyScanFilters()
        if (filterPlan is ProxyScanFilterPlan.SuspendedScreenOff) {
            holdPresenceAcrossScanHandover()
            suspendProxyScanUntilTargetsKnown()
            return
        }
        proxyScanSuspendedForScreenOff = false
        proxyScanBuiltForScreenOn = filterPlan is ProxyScanFilterPlan.Unfiltered
        val filtered = filterPlan as? ProxyScanFilterPlan.Filtered
        proxyScreenOffAddressIdentities = filtered?.addressIdentities?.takeIf { it.isNotEmpty() }
        proxyScreenOffAddressFiltersOnly = filtered?.addressFiltersOnly == true
        if (filtered != null) {
            scheduleGatewayFilterPersist()
        }
        proxyScreenOffSuspendedRecheckJob?.cancel()
        proxyScreenOffSuspendedRecheckJob = null
        acquireDeviceProxyBlePolicy()
        if (isBleAdvLegacyIntegrated()) {
            startBleAdvIntegratedProxyScanSession(sendMessage, filterPlan)
        } else {
            startDefaultProxyScanSession(sendMessage, filterPlan)
        }
        startScreenOffProxyScanMaintenance()
    }

    /**
     * Hold the proxy scan instead of starting a screen-off session that the platform would
     * suppress. Screen-on rebuilds the session and repopulates the learned filters; a slow
     * re-check covers targets added (e.g. a newly tracked device) while still screen-off.
     */
    private fun suspendProxyScanUntilTargetsKnown() {
        proxyScanSuspendedForScreenOff = true
        if (proxyScreenOffSuspendedRecheckJob?.isActive == true) return
        proxyScreenOffSuspendedRecheckJob = scope.launch {
            while (isActive) {
                delay(SCREEN_OFF_PROXY_MAINTENANCE_RETRY_MS)
                if (!bluetoothManager.isDetectEnabled) return@launch
                // Resumes either when targets become known or when the display comes back —
                // the latter may arrive without a screen broadcast, so poll rather than wait.
                if (buildProxyScanFilters() is ProxyScanFilterPlan.SuspendedScreenOff) continue
                val func = sendMessageFunc ?: return@launch
                Log.i(TAG, "Proxy scan targets available again; resuming")
                startProxyScan(func)
                return@launch
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBleAdvIntegratedProxyScanSession(
        sendMessage: suspend (com.google.protobuf.MessageLite) -> Unit,
        filterPlan: ProxyScanFilterPlan,
    ) {
        Log.d(
            TAG,
            "Starting ble-adv integrated proxy scan " +
                "(power=${_bluetoothScanPower.value}, mode=${effectiveProxyScanModeName()})",
        )
        proxyScanStartedAt = System.currentTimeMillis()
        lastProxyAdvertTime = System.currentTimeMillis()
        proxyAdvertCount = 0
        proxyScanWasActiveBeforeExclusive = true

        val callback = buildBleAdvIntegratedProxyScanCallback(sendMessage)
        stableProxyScanCallback = callback
        val settings = buildProxyScanSettings()
        val filters = (filterPlan as? ProxyScanFilterPlan.Filtered)?.filters

        bluetoothProxyScanJob = scope.launch {
            val scanner = bluetoothManager.getScanner()
            if (scanner == null) {
                Log.e(TAG, "Ble-adv integrated proxy scan failed: scanner is null")
                scheduleProxyScanStartRetry("Ble-adv integrated scanner unavailable")
                return@launch
            }
            try {
                while (isActive && bluetoothManager.isDetectEnabled) {
                    while (isActive && BleOperationCoordinator.isExclusiveActive) {
                        softStopProxyScanHardware()
                        delay(100)
                    }
                    while (isActive && proxyScanRecoveryJob?.isActive == true) {
                        softStopProxyScanHardware()
                        delay(100)
                    }
                    if (!isActive || !bluetoothManager.isDetectEnabled) break

                    if (!proxyScanHardwareRunning) {
                        BleOperationCoordinator.awaitScanStartPermitted()
                        try {
                            scanner.startScan(filters, settings, callback)
                            proxyScanHardwareRunning = true
                            BleOperationCoordinator.recordScanStartSuccess()
                            Log.d(TAG, "Ble-adv integrated proxy scan hardware started")
                        } catch (e: Exception) {
                            Log.e(TAG, "Ble-adv integrated proxy scan start failed", e)
                            BleOperationCoordinator.recordScanStartThrottled()
                            delay(2_000)
                            continue
                        }
                    }
                    delay(400)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                softStopProxyScanHardware()
                throw e
            } finally {
                softStopProxyScanHardware()
            }
        }
        startProxyScanWatchdog()
    }

    @SuppressLint("MissingPermission")
    private fun startDefaultProxyScanSession(
        sendMessage: suspend (com.google.protobuf.MessageLite) -> Unit,
        filterPlan: ProxyScanFilterPlan,
    ) {
        Log.d(
            TAG,
            "Starting proxy scan (power=${_bluetoothScanPower.value}, " +
                "mode=${effectiveProxyScanModeName()})",
        )
        proxyScanStartedAt = System.currentTimeMillis()
        lastProxyAdvertTime = System.currentTimeMillis()
        proxyAdvertCount = 0
        bluetoothProxyScanJob = scope.launch {
            awaitDeviceProxyBleHandover()
            if (!isActive || !bluetoothManager.isDetectEnabled) return@launch
            val scanner = bluetoothManager.getScanner()
            if (scanner == null) {
                Log.e(TAG, "Proxy scan failed: scanner is null")
                scheduleProxyScanStartRetry("Bluetooth scanner unavailable")
                return@launch
            }
            val settings = buildProxyScanSettings()
            val filters = (filterPlan as? ProxyScanFilterPlan.Filtered)?.filters
            val callback = buildDefaultProxyScanCallback(sendMessage)
            val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
            val stopScanOnce = {
                val ownsHardware = stableProxyScanCallback === callback && proxyScanHardwareRunning
                if (ownsHardware && stopped.compareAndSet(false, true)) {
                    stableProxyScanCallback = null
                    proxyScanHardwareRunning = false
                    try {
                        scanner.stopScan(callback)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to stop proxy scan: ${e.message}")
                    }
                }
            }

            try {
                scanner.startScan(filters, settings, callback)
                // Publish the live callback so a restart tears this scanner down before
                // registering the next one. Overlapping registrations exhaust the per-app
                // scanner slots on small chipsets and surface as SCAN_FAILED_INTERNAL_ERROR.
                stableProxyScanCallback = callback
                proxyScanHardwareRunning = true

                kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
                    cont.invokeOnCancellation { stopScanOnce() }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                stopScanOnce()
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Bluetooth proxy scan failed", e)
                stopScanOnce()
                scheduleProxyScanStartRetry("Bluetooth proxy scan start failed")
            }
        }
        startProxyScanWatchdog()
    }

    /**
     * The scan mode actually handed to the platform. A low-end chipset is pinned to
     * BALANCED regardless of the user-facing power setting, so anything that reasons about
     * expected advert cadence must read this rather than the power label.
     */
    private fun effectiveProxyScanMode(): Int {
        if (bluetoothManager.isLowEndBleChip()) {
            return android.bluetooth.le.ScanSettings.SCAN_MODE_BALANCED
        }
        val requested = when (_bluetoothScanPower.value) {
            context.getString(R.string.bluetooth_scan_power_low) ->
                android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_POWER
            context.getString(R.string.bluetooth_scan_power_balanced) ->
                android.bluetooth.le.ScanSettings.SCAN_MODE_BALANCED
            else -> android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY
        }
        // Combo radios share the antenna with Wi-Fi. LOW_LATENCY never sleeps and
        // is what showed up as 45% com.android.bluetooth on device 34.
        if (requested == android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY &&
            BluetoothLowLevelHooks.killingBluetoothWouldDropWifi(context)
        ) {
            return android.bluetooth.le.ScanSettings.SCAN_MODE_BALANCED
        }
        return requested
    }

    private fun effectiveProxyScanModeName(): String = when (effectiveProxyScanMode()) {
        android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_POWER -> "LOW_POWER"
        android.bluetooth.le.ScanSettings.SCAN_MODE_BALANCED -> "BALANCED"
        else -> "LOW_LATENCY"
    }

    private fun buildProxyScanSettings(): android.bluetooth.le.ScanSettings {
        val scanMode = effectiveProxyScanMode()
        val settingsBuilder = android.bluetooth.le.ScanSettings.Builder()
            .setScanMode(scanMode)
            .setReportDelay(0)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            settingsBuilder.setCallbackType(android.bluetooth.le.ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
        }
        return settingsBuilder.build()
    }

    @SuppressLint("MissingPermission")
    private fun buildBleAdvIntegratedProxyScanCallback(
        sendMessage: suspend (com.google.protobuf.MessageLite) -> Unit,
    ): android.bluetooth.le.ScanCallback {
        return object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
                if (BleOperationCoordinator.isExclusiveActive) return
                rememberProxyScanFilters(result)
                markProxyAdvertReceived()
                proxyAdvertCount++
                maybeForwardBleAdvScan(result)
                bluetoothManager.onDeviceScanned(
                    result.device.address,
                    result.rssi,
                    settleImmediately = true,
                    device = result.device,
                    addressFilterIdentities = proxyScreenOffAddressIdentities,
                    addressFiltersOnly = proxyScreenOffAddressFiltersOnly,
                )
                scope.launch { sendBluetoothAdvertisement(result, sendMessage) }
            }

            override fun onBatchScanResults(results: MutableList<android.bluetooth.le.ScanResult>) {
                if (results.isNotEmpty()) markProxyAdvertReceived()
                results.forEach { result ->
                    if (BleOperationCoordinator.isExclusiveActive) return@forEach
                    rememberProxyScanFilters(result)
                    maybeForwardBleAdvScan(result)
                    bluetoothManager.onDeviceScanned(
                        result.device.address,
                        result.rssi,
                        settleImmediately = true,
                        device = result.device,
                        addressFilterIdentities = proxyScreenOffAddressIdentities,
                        addressFiltersOnly = proxyScreenOffAddressFiltersOnly,
                    )
                    scope.launch { sendBluetoothAdvertisement(result, sendMessage) }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                proxyScanHardwareRunning = false
                BleOperationCoordinator.recordScanStartThrottled()
                Log.e(TAG, "Ble-adv integrated proxy scan failed with error code: $errorCode")
                scheduleProxyScanRecovery(errorCode)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun buildDefaultProxyScanCallback(
        sendMessage: suspend (com.google.protobuf.MessageLite) -> Unit,
    ): android.bluetooth.le.ScanCallback {
        return object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
                rememberProxyScanFilters(result)
                markProxyAdvertReceived()
                proxyAdvertCount++
                bluetoothManager.onDeviceScanned(
                    result.device.address,
                    result.rssi,
                    settleImmediately = true,
                    device = result.device,
                    addressFilterIdentities = proxyScreenOffAddressIdentities,
                    addressFiltersOnly = proxyScreenOffAddressFiltersOnly,
                )
                scope.launch { sendBluetoothAdvertisement(result, sendMessage) }
            }

            override fun onBatchScanResults(results: MutableList<android.bluetooth.le.ScanResult>) {
                if (results.isNotEmpty()) markProxyAdvertReceived()
                results.forEach { result ->
                    rememberProxyScanFilters(result)
                    bluetoothManager.onDeviceScanned(
                        result.device.address,
                        result.rssi,
                        settleImmediately = true,
                        device = result.device,
                        addressFilterIdentities = proxyScreenOffAddressIdentities,
                        addressFiltersOnly = proxyScreenOffAddressFiltersOnly,
                    )
                    scope.launch { sendBluetoothAdvertisement(result, sendMessage) }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Proxy scan failed with error code: $errorCode")
                scheduleProxyScanRecovery(errorCode)
            }
        }
    }
    
    private fun proxyWatchdogTiming(): Pair<Long, Long> {
        // Keyed off the scan mode the platform actually got, not the user-facing power
        // label: a low-end chipset runs BALANCED even when the label says High Performance,
        // and judging its advert cadence by the 8s High Performance threshold reports a
        // healthy scanner as stale.
        return when (effectiveProxyScanMode()) {
            android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY -> 2_000L to 8_000L
            android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_POWER -> 10_000L to 25_000L
            // Balanced: 5s poll / 15s stale (was 15–20s with jitter).
            else -> 5_000L to 15_000L
        }
    }

    private fun startProxyScanWatchdog() {
        proxyScanWatchdogJob?.cancel()
        proxyScanWatchdogJob = null
        if (!isProxyScanScreenOn()) {
            Log.d(TAG, "Screen off; foreground proxy watchdog disabled")
            return
        }
        proxyScanWatchdogJob = scope.launch {
            while (true) {
                val (watchdogInterval, staleThreshold) = proxyWatchdogTiming()
                delay(watchdogInterval)
                if (bluetoothProxyScanJob?.isActive != true) continue

                // This watchdog only repairs the foreground scanner. Screen-off scanning has
                // its own filtered strategy and must never be restarted or otherwise touched
                // because sparse/batched delivery is normal while the display is off.
                if (!isProxyScanScreenOn()) {
                    continue
                }

                if (BleOperationCoordinator.isExclusiveActive || gattConnectWindowActive) {
                    // Freeze the stale clock while exclusive / a connect window owns the radio.
                    lastProxyAdvertTime = System.currentTimeMillis()
                    continue
                }
                if (pendingProxyScanRestart) continue

                // System radio fully off: enable it — never treat silence as a stuck stack.
                if (bluetoothManager.isBluetoothStrictlyOff()) {
                    Log.w(TAG, "Proxy scan stale while system Bluetooth is off — requesting enable")
                    lastProxyAdvertTime = System.currentTimeMillis()
                    requestSystemBluetoothEnable(reason = "proxy-watchdog")
                    continue
                }

                val sessionAge = System.currentTimeMillis() - proxyScanStartedAt
                val elapsed = System.currentTimeMillis() - lastProxyAdvertTime

                if (!isBleAdvLegacyIntegrated() &&
                    sessionAge > PROXY_SCAN_SESSION_ROTATE_MS &&
                    elapsed <= staleThreshold
                ) {
                    Log.d(TAG, "Rotating proxy scan session (${sessionAge}ms)")
                    restartProxyScan()
                    return@launch
                }

                if (elapsed <= staleThreshold) {
                    proxyStaleObserveStartedAt = 0L
                    continue
                }

                val now = System.currentTimeMillis()

                // Android 8+: quiet ≠ dead. Watch a while before any soft restart / kill.
                if (!allowsAggressiveBluetoothProcessKill()) {
                    if (proxyStaleObserveStartedAt == 0L) {
                        proxyStaleObserveStartedAt = now
                        Log.d(
                            TAG,
                            "Proxy scan quiet (${elapsed}ms) — observing " +
                                "${PROXY_SCAN_STALE_OBSERVE_GRACE_MS}ms before recovery " +
                                "(API ${Build.VERSION.SDK_INT})",
                        )
                        continue
                    }
                    val observedFor = now - proxyStaleObserveStartedAt
                    if (observedFor < PROXY_SCAN_STALE_OBSERVE_GRACE_MS) {
                        continue
                    }
                }

                // Only defer kill when every proxy GATT slot is busy. A single lingering
                // connection must not pin a dead scanner forever.
                val connectionSlots = proxyManager?.getConnectionsFree()
                val fullyBooked = connectionSlots != null && connectionSlots.first == 0
                if (fullyBooked) {
                    Log.w(
                        TAG,
                        "Proxy scan stale (${elapsed}ms) but GATT fully booked — soft restart",
                    )
                    noteStaleSoftRecovery()
                    softRecoverStaleProxyScan()
                    continue
                }

                val killCooldown = proxyScanKillCooldownMs()
                if (now - lastProxyKillAt < killCooldown) {
                    if (!allowsAggressiveBluetoothProcessKill()) {
                        // Already killed recently — keep watching; do not churn soft restarts.
                        continue
                    }
                    Log.d(
                        TAG,
                        "Proxy scan stale (${elapsed}ms) — kill cooldown active, soft restart",
                    )
                    noteStaleSoftRecovery()
                    softRecoverStaleProxyScan()
                    continue
                }

                // Android 8+: require repeated soft-recovery failures before killing the stack.
                if (!allowsAggressiveBluetoothProcessKill() &&
                    consecutiveStaleSoftRecoveries < PROXY_SCAN_SOFT_RECOVERIES_BEFORE_KILL
                ) {
                    noteStaleSoftRecovery()
                    Log.w(
                        TAG,
                        "Proxy scan quiet confirmed (${elapsed}ms, observed " +
                            "${now - proxyStaleObserveStartedAt}ms) — soft recovery " +
                            "$consecutiveStaleSoftRecoveries/$PROXY_SCAN_SOFT_RECOVERIES_BEFORE_KILL " +
                            "(API ${Build.VERSION.SDK_INT}, kill deferred)",
                    )
                    softRecoverStaleProxyScan()
                    continue
                }

                if (BluetoothLowLevelHooks.killingBluetoothWouldDropWifi(context)) {
                    Log.w(
                        TAG,
                        "Proxy scan stale (${elapsed}ms) — skip bluetooth process kill " +
                            "(shared Wi-Fi/BT radio or Wi-Fi is on)",
                    )
                    noteStaleSoftRecovery()
                    softRecoverStaleProxyScan()
                    continue
                }

                val canKill = RootUtils.isRootAvailable() ||
                    ShizukuUtils.isShizukuPermissionGranted()
                if (!canKill) {
                    Log.w(
                        TAG,
                        "Proxy scan stale (${elapsed}ms) — no root/Shizuku, soft restart only",
                    )
                    noteStaleSoftRecovery()
                    softRecoverStaleProxyScan()
                    continue
                }

                // Final gate: prove the stack is actually broken before killing it. Silence
                // alone (quiet room, night, rural) must never take down the user's classic
                // connections (headphones / watch / car).
                when (probeBluetoothStackAlive()) {
                    StackProbeResult.ALIVE_QUIET -> {
                        Log.i(
                            TAG,
                            "Proxy scan quiet (${elapsed}ms) but stack probe healthy — " +
                                "environment is just quiet, kill skipped",
                        )
                        // Treat the successful probe as a heartbeat so the chain restarts
                        // from scratch instead of re-probing every watchdog tick.
                        markProxyAdvertReceived()
                        continue
                    }
                    StackProbeResult.ALIVE_ADVERTS -> {
                        Log.w(
                            TAG,
                            "Stack probe saw adverts the main scan missed — main scan session " +
                                "wedged, soft restart instead of kill",
                        )
                        noteStaleSoftRecovery()
                        softRecoverStaleProxyScan()
                        continue
                    }
                    StackProbeResult.REGISTRATION_REJECTED -> {
                        Log.w(
                            TAG,
                            "Proxy scan quiet (${elapsed}ms) and the stack refuses to register " +
                                "our scanner — releasing this app's scanners and backing off " +
                                "instead of killing bluetooth",
                        )
                        noteStaleSoftRecovery()
                        stopProxyScanInternal()
                        scheduleProxyScanStartRetry("Scanner registration refused")
                        return@launch
                    }
                    StackProbeResult.DEAD -> {
                        // Proven broken — fall through to kill recovery below.
                    }
                }

                val killMode =
                    if (allowsAggressiveBluetoothProcessKill()) {
                        "legacy aggressive"
                    } else {
                        "cautious after observe + soft failures"
                    }
                Log.w(
                    TAG,
                    "Proxy scan stale (${elapsed}ms since last advert) — killing bluetooth process " +
                        "(API ${Build.VERSION.SDK_INT}, $killMode, stack probe confirmed dead)",
                )
                consecutiveStaleSoftRecoveries = 0
                proxyStaleObserveStartedAt = 0L
                if (!recoverStaleProxyScanByKillingBluetooth()) {
                    softRecoverStaleProxyScan()
                    continue
                }
                return@launch
            }
        }
    }

    private fun noteStaleSoftRecovery() {
        consecutiveStaleSoftRecoveries =
            (consecutiveStaleSoftRecoveries + 1).coerceAtMost(PROXY_SCAN_SOFT_RECOVERIES_BEFORE_KILL)
    }

    private enum class StackProbeResult {
        /** No scanner at all while the adapter claims to be on — the stack is unhealthy. */
        DEAD,
        /**
         * The stack answered but refused to register our scanner. That is a statement about
         * this app's scanner slots (leaked/overlapping registrations, policy), not about the
         * health of the stack — killing the Bluetooth process would clear our own leak and
         * the loop would come straight back.
         */
        REGISTRATION_REJECTED,
        /** Registration succeeded, no adverts — environment is genuinely quiet. */
        ALIVE_QUIET,
        /** Probe received adverts the main scanner missed — main callback is wedged. */
        ALIVE_ADVERTS,
    }

    /**
     * Actively probe whether the Bluetooth stack is alive by registering a short throwaway
     * scan. "No adverts for a while" cannot distinguish a dead stack from an empty RF
     * environment, so a kill is only justified when this probe proves the stack broken.
     */
    @SuppressLint("MissingPermission")
    private suspend fun probeBluetoothStackAlive(): StackProbeResult {
        val scanner = bluetoothManager.getScanner()
        if (scanner == null) {
            // Scanner-null is often a bounce, not a wedged stack. On combo chips a
            // follow-up kill takes Wi-Fi down with Bluetooth.
            return if (BluetoothLowLevelHooks.killingBluetoothWouldDropWifi(context)) {
                StackProbeResult.REGISTRATION_REJECTED
            } else {
                StackProbeResult.DEAD
            }
        }
        if (BluetoothLowLevelHooks.killingBluetoothWouldDropWifi(context)) {
            // A third unfiltered LOW_LATENCY scan beside proxy + presence can crash
            // MediaTek conninfra. Quiet is not dead — do not probe-scan.
            return StackProbeResult.ALIVE_QUIET
        }
        val sawAdvert = java.util.concurrent.atomic.AtomicBoolean(false)
        val failureCode = java.util.concurrent.atomic.AtomicInteger(-1)
        val callback = object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
                sawAdvert.set(true)
            }

            override fun onBatchScanResults(results: MutableList<android.bluetooth.le.ScanResult>) {
                if (results.isNotEmpty()) sawAdvert.set(true)
            }

            override fun onScanFailed(errorCode: Int) {
                failureCode.set(errorCode)
            }
        }
        val settings = android.bluetooth.le.ScanSettings.Builder()
            .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()
        try {
            scanner.startScan(null, settings, callback)
        } catch (e: Exception) {
            Log.w(TAG, "Stack probe: startScan threw — scanner registration refused", e)
            return StackProbeResult.REGISTRATION_REJECTED
        }
        try {
            delay(STACK_PROBE_WINDOW_MS)
        } finally {
            runCatching { scanner.stopScan(callback) }
        }
        val code = failureCode.get()
        return when {
            code == android.bluetooth.le.ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED ||
                code == android.bluetooth.le.ScanCallback.SCAN_FAILED_INTERNAL_ERROR ->
                StackProbeResult.REGISTRATION_REJECTED
            // Resource-busy / throttled / already-started: stack is responsive, do not kill.
            sawAdvert.get() -> StackProbeResult.ALIVE_ADVERTS
            else -> StackProbeResult.ALIVE_QUIET
        }
    }

    /** Real advert received — clear the soft-recovery streak used for cautious kill gating. */
    private fun markProxyAdvertReceived() {
        lastProxyAdvertTime = System.currentTimeMillis()
        consecutiveStaleSoftRecoveries = 0
        proxyStaleObserveStartedAt = 0L
        proxyKillCooldownMs = PROXY_SCAN_KILL_COOLDOWN_MIN_MS
        proxyScanRetryBackoffMs = PROXY_SCAN_START_RETRY_MS
    }

    /**
     * Older Android stacks downgrade long-running scans after about five minutes. Keep the
     * filtered screen-off session fresh without using advert silence as a health signal.
     */
    private fun startScreenOffProxyScanMaintenance() {
        proxyScreenOffMaintenanceJob?.cancel()
        val builtForScreenOn = proxyScanBuiltForScreenOn
        proxyScreenOffMaintenanceJob = scope.launch {
            var sessionAgeMs = 0L
            while (isActive) {
                delay(SCREEN_OFF_PROXY_MAINTENANCE_RETRY_MS)
                sessionAgeMs += SCREEN_OFF_PROXY_MAINTENANCE_RETRY_MS
                if (bluetoothProxyScanJob?.isActive != true ||
                    pendingProxyScanRestart ||
                    proxyScanRecoveryJob?.isActive == true ||
                    BleOperationCoordinator.isExclusiveActive
                ) {
                    continue
                }

                // Wakefulness can change without an ACTION_SCREEN_* broadcast ever reaching
                // us — a root/Shizuku backlight write moves Ava's screen flow but not the
                // platform's, and vice versa — leaving the session on a scan strategy the
                // platform no longer honours with no event to react to.
                if (isProxyScanScreenOn() != builtForScreenOn) {
                    Log.i(
                        TAG,
                        "Proxy scan strategy no longer matches the display state; rebuilding",
                    )
                    restartProxyScan(forceRebuild = true)
                    return@launch
                }

                // The away pipeline sees something this loop cannot: every tracked device
                // falling silent at once, which is the receiver rather than the room. A
                // session rotation is what recovers a wedged scanner, so bring the next one
                // forward rather than leaving presence wrong until the timer comes round.
                if (bluetoothManager.isReceiverSuspect()) {
                    Log.i(
                        TAG,
                        "Every tracked device went silent together; rotating proxy scan session now",
                    )
                    restartProxyScan(forceRebuild = true)
                    return@launch
                }

                if (builtForScreenOn) continue
                if (sessionAgeMs < SCREEN_OFF_PROXY_SESSION_ROTATE_MS) continue

                Log.d(TAG, "Rotating long-running filtered screen-off proxy scan session")
                restartProxyScan(forceRebuild = true)
                return@launch
            }
        }
    }

    /** Soft-restart a stale proxy session without killing the Bluetooth process. */
    private fun softRecoverStaleProxyScan() {
        if (!isProxyScanScreenOn()) return
        lastProxyAdvertTime = System.currentTimeMillis()
        proxyStaleObserveStartedAt = 0L
        if (isBleAdvLegacyIntegrated()) {
            softStopProxyScanHardware()
            proxyScanStartedAt = System.currentTimeMillis()
            return
        }
        // restartProxyScan cancels this watchdog and starts a new one.
        restartProxyScan(forceRebuild = true)
    }

    private fun stopProxyScanInternal() {
        proxyScreenOffMaintenanceJob?.cancel()
        proxyScreenOffMaintenanceJob = null
        proxyScreenOffAddressIdentities = null
        proxyScreenOffAddressFiltersOnly = false
        softStopProxyScanHardware()
        bluetoothProxyScanJob?.cancel()
        bluetoothProxyScanJob = null
    }

    /**
     * @return true if kill recovery was started (watchdog should exit); false to keep looping.
     */
    private fun recoverStaleProxyScanByKillingBluetooth(): Boolean {
        if (!isProxyScanScreenOn()) return false
        if (BluetoothLowLevelHooks.killingBluetoothWouldDropWifi(context)) {
            Log.w(TAG, "Stale proxy kill skipped: Bluetooth shares the Wi-Fi radio")
            return false
        }
        val func = sendMessageFunc
        if (func == null) {
            Log.w(TAG, "Stale proxy kill skipped: sendMessage not set")
            return false
        }
        stopProxyScanInternal()
        proxyScanWatchdogJob?.cancel()
        proxyScanWatchdogJob = null
        registerBluetoothStateReceiver()
        pendingProxyScanRestart = true
        lastProxyKillAt = System.currentTimeMillis()
        lastProxyAdvertTime = System.currentTimeMillis()
        proxyKillCooldownMs = PROXY_SCAN_KILL_COOLDOWN_MAX_MS

        val onBluetoothKilled = {
            scope.launch {
                // Shizuku/Root helpers already wait ~3s; extra settle before fallback restart.
                delay(5_000L)
                if (!pendingProxyScanRestart) return@launch
                if (!bluetoothManager.isDetectEnabled) {
                    pendingProxyScanRestart = false
                    return@launch
                }
                if (bluetoothManager.isBluetoothEnabled()) {
                    Log.i(TAG, "Bluetooth restored (fallback), restarting proxy scan")
                    pendingProxyScanRestart = false
                    startProxyScan(func)
                } else {
                    Log.w(TAG, "Bluetooth still off after kill; waiting for STATE_ON")
                }
            }
            Unit
        }

        if (RootUtils.isRootAvailable()) {
            RootUtils.killBluetoothProcessAsync(onBluetoothKilled)
        } else {
            ShizukuUtils.killBluetoothProcessAsync(onBluetoothKilled)
        }
        return true
    }

    private fun registerBluetoothStateReceiver() {
        if (bluetoothStateReceiver != null) return
        bluetoothStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                when (state) {
                    BluetoothAdapter.STATE_TURNING_OFF -> {
                        if (!bluetoothManager.isDetectEnabled) return
                        if (bluetoothProxyScanJob?.isActive == true) {
                            proxyScanWasActiveBeforeRadioOff = true
                        }
                        // Freeze stale clock so kill recovery does not fire while radio is going down.
                        lastProxyAdvertTime = System.currentTimeMillis()
                    }
                    BluetoothAdapter.STATE_OFF -> {
                        if (!bluetoothManager.isDetectEnabled) return
                        if (bluetoothProxyScanJob?.isActive == true) {
                            proxyScanWasActiveBeforeRadioOff = true
                        }
                        Log.w(
                            TAG,
                            "System Bluetooth off while detect is enabled — " +
                                "waiting for keep-alive to confirm before enable",
                        )
                        lastProxyAdvertTime = System.currentTimeMillis()
                    }
                    BluetoothAdapter.STATE_ON -> {
                        onSystemBluetoothOn()
                    }
                }
            }
        }
        runCatching {
            val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                    bluetoothStateReceiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED,
                )
            } else {
                context.registerReceiver(bluetoothStateReceiver, filter)
            }
        }.onFailure {
            Log.w(TAG, "Failed to register bluetooth state receiver", it)
            bluetoothStateReceiver = null
        }
    }

    private fun startAdapterKeepAlive() {
        registerBluetoothStateReceiver()
        // Profile-disable workaround for broken ROMs is applied inside
        // BluetoothRadioHelper.tryEnablePrivileged, and only after an enable attempt
        // has actually failed — no preemptive global settings write here. This health
        // check only clears stale bits (services present again) left by old versions.
        runCatching { BluetoothRadioHelper.reconcileStaleProfileDisable(context) }
        if (bluetoothManager.isBluetoothStrictlyOff()) {
            requestSystemBluetoothEnable(reason = "detect-start")
        } else {
            // Warm the capability cache while LE access is available.
            runCatching { bluetoothManager.getProxyCapabilityReport() }
        }
        adapterKeepAliveJob?.cancel()
        adapterKeepAliveJob = scope.launch {
            while (isActive && bluetoothManager.isDetectEnabled) {
                delay(ADAPTER_KEEPALIVE_POLL_MS)
                if (!bluetoothManager.isDetectEnabled) break
                if (bluetoothManager.isBluetoothEnabled()) {
                    pendingAdapterEnable = false
                    adapterEnableCooldownMs = ADAPTER_ENABLE_COOLDOWN_MIN_MS
                    continue
                }
                if (!bluetoothManager.isBluetoothStrictlyOff()) continue
                Log.w(TAG, "Adapter keep-alive: system Bluetooth still off")
                requestSystemBluetoothEnable(reason = "keepalive-poll")
            }
        }
    }

    private fun stopAdapterKeepAlive() {
        adapterKeepAliveJob?.cancel()
        adapterKeepAliveJob = null
        pendingAdapterEnable = false
        adapterEnableCooldownMs = ADAPTER_ENABLE_COOLDOWN_MIN_MS
        proxyScanWasActiveBeforeRadioOff = false
    }

    /**
     * Re-open the system Bluetooth radio while Device Detection is on.
     * Root / Shizuku / Device Owner first; otherwise log only (settings page shows the system dialog).
     */
    private fun requestSystemBluetoothEnable(reason: String) {
        if (!bluetoothManager.isDetectEnabled) return
        // Only re-open when the radio is strictly OFF. TURNING_ON / TURNING_OFF / BLE_ON are
        // not "user turned it off" — forcing enable there churns the stack and MIUI re-connects.
        if (!bluetoothManager.isBluetoothStrictlyOff()) {
            if (bluetoothManager.isBluetoothEnabled()) {
                adapterEnableCooldownMs = ADAPTER_ENABLE_COOLDOWN_MIN_MS
            }
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastAdapterEnableAt < adapterEnableCooldownMs) return
        lastAdapterEnableAt = now
        adapterEnableCooldownMs = minOf(
            ADAPTER_ENABLE_COOLDOWN_MAX_MS,
            maxOf(ADAPTER_ENABLE_COOLDOWN_MIN_MS, adapterEnableCooldownMs * 2),
        )
        pendingAdapterEnable = true
        registerBluetoothStateReceiver()

        scope.launch(Dispatchers.IO) {
            val ok = BluetoothRadioHelper.ensureEnabledOrNeedsUserPrompt(context)
            if (!ok) {
                Log.w(TAG, "Privileged Bluetooth enable unavailable ($reason)")
            }
        }
    }

    private fun onSystemBluetoothOn() {
        Log.i(TAG, "System Bluetooth is ON")
        pendingAdapterEnable = false
        adapterEnableCooldownMs = ADAPTER_ENABLE_COOLDOWN_MIN_MS
        // Refresh chipset capability while LE access is valid.
        runCatching { bluetoothManager.getProxyCapabilityReport() }

        // Only resurrect a scan we ourselves tore down (kill recovery) or that
        // died with the radio. A screen-on handover already starts a scan; the
        // old "job not active" clause stacked a second startScan 1s later.
        val shouldRestartProxy = pendingProxyScanRestart || proxyScanWasActiveBeforeRadioOff
        pendingProxyScanRestart = false
        proxyScanWasActiveBeforeRadioOff = false

        if (!bluetoothManager.isDetectEnabled) return
        if (!shouldRestartProxy) return
        if (bluetoothProxyScanJob?.isActive == true) return
        val func = sendMessageFunc ?: return
        scope.launch {
            delay(1_000L)
            if (!bluetoothManager.isDetectEnabled) return@launch
            if (bluetoothProxyScanJob?.isActive == true) return@launch
            Log.i(TAG, "Restarting proxy scan after Bluetooth ON")
            startProxyScan(func)
        }
    }

    private fun unregisterBluetoothStateReceiver() {
        bluetoothStateReceiver?.let { receiver ->
            runCatching { context.unregisterReceiver(receiver) }
            bluetoothStateReceiver = null
        }
        pendingProxyScanRestart = false
        pendingAdapterEnable = false
        adapterEnableCooldownMs = ADAPTER_ENABLE_COOLDOWN_MIN_MS
    }

    fun stopProxyScan(releaseDevicePolicy: Boolean = true) {
        proxyScanWatchdogJob?.cancel()
        proxyScanWatchdogJob = null
        proxyScreenOffMaintenanceJob?.cancel()
        proxyScreenOffMaintenanceJob = null
        proxyScreenOffSuspendedRecheckJob?.cancel()
        proxyScreenOffSuspendedRecheckJob = null
        proxyScanRecoveryJob?.cancel()
        proxyScanRecoveryJob = null
        // Drop any in-flight kill→restart so a later STATE_ON cannot resurrect the scan.
        if (releaseDevicePolicy) {
            pendingProxyScanRestart = false
            proxyScanSuspendedForScreenOff = false
        }
        softStopProxyScanHardware()
        stableProxyScanCallback = null
        proxyScanWasActiveBeforeExclusive = false
        bluetoothProxyScanJob?.cancel()
        bluetoothProxyScanJob = null
        if (releaseDevicePolicy) {
            releaseDeviceProxyBlePolicy()
        }
    }
    
    private fun holdPresenceAcrossScanHandover() {
        bluetoothManager.suppressAwayTimeouts(SCAN_HANDOVER_PRESENCE_GRACE_MS)
    }

    private fun restartProxyScan(forceRebuild: Boolean = false) {
        val func = sendMessageFunc ?: return
        if (!bluetoothManager.isDetectEnabled) {
            stopProxyScan()
            return
        }
        holdPresenceAcrossScanHandover()
        if (isBleAdvLegacyIntegrated() && !forceRebuild) {
            // Integrated session: soft-restart hardware only, keep stable callback.
            softStopProxyScanHardware()
            proxyScanStartedAt = System.currentTimeMillis()
            return
        }
        stopProxyScan(releaseDevicePolicy = false)
        startProxyScan(func)
    }
    
    private fun powerLabelToKey(label: String): String = when (label) {
        context.getString(R.string.bluetooth_scan_power_balanced) -> "balanced"
        context.getString(R.string.bluetooth_scan_power_low) -> "low"
        else -> "high"
    }
    
    private fun powerKeyToLabel(key: String): String = when (key) {
        "balanced" -> context.getString(R.string.bluetooth_scan_power_balanced)
        "low" -> context.getString(R.string.bluetooth_scan_power_low)
        else -> context.getString(R.string.bluetooth_scan_power_high)
    }
    
    /**
     * Real GATT free/limit for recovery and pre-proxy fallback.
     * Home Assistant's allocated list is sent only via [sendConnectionsFree].
     */
    fun getConnectionsFree(): Pair<Int, Int> {
        ensureProxyManager()?.let { return it.getConnectionsFree() }
        val limit = BluetoothProxyManager.maxConnectionsFor(bluetoothManager)
        return Pair(limit, limit)
    }

    /** Single exit for BluetoothConnectionsFreeResponse; HA must never see two accountings. */
    suspend fun sendConnectionsFree() {
        val manager = ensureProxyManager()
        if (manager != null) {
            manager.sendConnectionsFree()
            return
        }
        val send = sendMessageFunc ?: return
        val (free, limit) = getConnectionsFree()
        send(com.example.esphomeproto.api.bluetoothConnectionsFreeResponse {
            this.free = free
            this.limit = limit
            allocated.addAll(getScannedDeviceAddresses().take(limit - free))
        })
    }
    
    fun getScannedDeviceAddresses(): List<Long> {
        return synchronized(scannedDevices) { scannedDevices.toList().take(maxSimulatedSlots) }
    }
    
    fun addScannedDevice(address: Long, rssi: Int = 0) {
        // Do not hold [scannedDevices] across [claimDevice]: that path can notify
        // [onClaimsChanged] -> [syncScannedDevicesWithClaims] which needs the same lock.
        val blocked = synchronized(scannedDevices) {
            scannedDevices.contains(address) || scannedDevices.size >= maxSimulatedSlots
        }
        if (blocked) return
        if (!bluetoothManager.claimDevice(address, rssi)) return
        synchronized(scannedDevices) {
            if (scannedDevices.size < maxSimulatedSlots) {
                scannedDevices.add(address)
            }
        }
    }
    
    fun clearScannedDevices() {
        synchronized(scannedDevices) { scannedDevices.clear() }
        bluetoothManager.clearClaimedDevices()
    }

    /**
     * Claims expire, get released, or move to a closer Ava over UDP; mirror the
     * authoritative claim set back into [scannedDevices] and re-report slots.
     */
    private fun syncScannedDevicesWithClaims() {
        val claimed = bluetoothManager.getClaimedDevices().toSet()
        val changed = synchronized(scannedDevices) {
            if (scannedDevices == claimed) {
                false
            } else {
                scannedDevices.clear()
                scannedDevices.addAll(claimed.take(maxSimulatedSlots))
                true
            }
        }
        if (changed) {
            Log.i(TAG, "Simulated slots resynced with claims: ${scannedDevices.size}")
            ensureProxyManager()?.scheduleConnectionsFree()
        }
    }
    
    suspend fun handleConnectionsFreeRequest() {
        ensureProxyManager()?.sendConnectionsFree()
    }
    
    suspend fun handleDeviceRequest(request: BluetoothDeviceRequest) {
        rememberGatewayDeviceAddress(request.address)
        ensureProxyManager()?.handleDeviceRequest(request.address, request.requestType, request.addressType)
    }

    private fun rememberGatewayDeviceAddress(address: Long) {
        if (address !in 1L..0xFFFFFFFFFFFFL) return
        val macAddress = String.format(
            "%02X:%02X:%02X:%02X:%02X:%02X",
            (address shr 40) and 0xFF,
            (address shr 32) and 0xFF,
            (address shr 24) and 0xFF,
            (address shr 16) and 0xFF,
            (address shr 8) and 0xFF,
            address and 0xFF,
        )
        val changed = synchronized(screenOffFilterLock) {
            gatewayDeviceAddresses.touchCapped(macAddress, MAX_LEARNED_FILTER_VALUES)
        }
        if (changed) scheduleGatewayFilterPersist()
    }
    
    suspend fun handleGetServicesRequest(request: BluetoothGATTGetServicesRequest) {
        ensureProxyManager()?.getServices(request.address)
    }
    
    suspend fun handleReadRequest(request: BluetoothGATTReadRequest) {
        ensureProxyManager()?.readCharacteristic(request.address, request.handle)
    }
    
    suspend fun handleWriteRequest(request: BluetoothGATTWriteRequest) {
        ensureProxyManager()?.writeCharacteristic(request.address, request.handle, request.data.toByteArray(), request.response)
    }
    
    suspend fun handleReadDescriptorRequest(request: BluetoothGATTReadDescriptorRequest) {
        ensureProxyManager()?.readDescriptor(request.address, request.handle)
    }
    
    suspend fun handleWriteDescriptorRequest(request: BluetoothGATTWriteDescriptorRequest) {
        ensureProxyManager()?.writeDescriptor(request.address, request.handle, request.data.toByteArray())
    }
    
    suspend fun handleNotifyRequest(request: BluetoothGATTNotifyRequest) {
        ensureProxyManager()?.setNotify(request.address, request.handle, request.enable)
    }

    suspend fun handleSetConnectionParamsRequest(request: BluetoothSetConnectionParamsRequest) {
        ensureProxyManager()?.setConnectionParams(
            request.address, request.minInterval, request.maxInterval, request.latency, request.timeout
        )
    }
    
    suspend fun disconnectAllProxy() {
        ensureProxyManager()?.disconnectAll()
    }

    private fun disconnectAllProxyConnections() {
        val manager = proxyManager ?: return
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                manager.disconnectAll()
            }
        }
    }
    
    @SuppressLint("MissingPermission")
    private suspend fun sendBluetoothAdvertisement(
        result: android.bluetooth.le.ScanResult,
        sendMessage: suspend (com.google.protobuf.MessageLite) -> Unit,
    ) {
        if (BleOperationCoordinator.isExclusiveActive) {
            return
        }

        val record = result.scanRecord ?: return
        val rawBytes = record.bytes ?: return
        
        val macParts = result.device.address.split(":")
        if (macParts.size != 6) return
        var address = 0L
        for (i in 0 until 6) {
            address = (address shl 8) or (macParts[i].toIntOrNull(16)?.toLong() ?: return)
        }
        // Cheap when the manager already exists; lets a later connect log how fresh our
        // own sighting of the peer was.
        proxyManager?.noteAdvertisementSeen(address)
        
        val serviceData = record.serviceData
        val hasBTHome = serviceData?.keys?.any {
            it.uuid.toString().lowercase().contains("fcd2")
        } == true
        if (hasBTHome) {
            // Refresh before advert dedup: BTHome often repeats the same payload, and
            // local claims expire if lastHeard is only updated when HA is sent a packet.
            bluetoothManager.noteClaimObservation(address, result.rssi)
            val wasInSet = synchronized(scannedDevices) { scannedDevices.contains(address) }
            addScannedDevice(address, result.rssi)
            val nowInSet = synchronized(scannedDevices) { scannedDevices.contains(address) }
            if (!wasInSet && nowInSet) {
                Log.i(TAG, "New BTHome device: ${result.device.address} (total: ${scannedDevices.size})")
                ensureProxyManager()?.scheduleConnectionsFree()
            }
        }

        val now = System.currentTimeMillis()
        val payloadHash = rawBytes.contentHashCode()
        val last = advertDedupCache[address]
        if (last != null && last.payloadHash == payloadHash && now - last.sentAt < advertDedupWindowMs) {
            return
        }
        advertDedupCache[address] = AdvertDedup(payloadHash, now)
        if (advertDedupCache.size > 500) {
            advertDedupCache.entries.removeIf { now - it.value.sentAt > 30_000 }
        }
        
        val firstByte = macParts[0].toIntOrNull(16) ?: 0
        val bleAddressType = if ((firstByte and 0xC0) != 0) 1 else 0
        
        sendMessage(com.example.esphomeproto.api.bluetoothLERawAdvertisementsResponse {
            advertisements += com.example.esphomeproto.api.bluetoothLERawAdvertisement {
                this.address = address
                this.rssi = result.rssi
                this.addressType = bleAddressType
                this.data = com.google.protobuf.ByteString.copyFrom(rawBytes)
            }
        })
    }
}

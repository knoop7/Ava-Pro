package com.example.ava.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.ava.R
import com.example.ava.utils.DeviceFeatureManager
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BluetoothPresenceManager private constructor(private val context: Context) {

    enum class ProxyCapabilityTier {
        FULL,
        BALANCED,
        COMPATIBILITY
    }

    data class ProxyCapabilityReport(
        val tier: ProxyCapabilityTier,
        val compatibilityMode: Boolean,
        val offloadedFilteringSupported: Boolean,
        val multipleAdvertisementSupported: Boolean,
        val leExtendedAdvertisingSupported: Boolean,
        val leCodedPhySupported: Boolean,
        val le2MPhySupported: Boolean
    )

    fun interface PresenceAlertCallback {
        fun onPresenceChanged(address: String, wasPresent: Boolean, isPresent: Boolean, deviceName: String)
    }

    var presenceAlertCallback: PresenceAlertCallback? = null
    
    companion object {
        private const val TAG = "BluetoothPresence"
        private const val PREFS_NAME = "bluetooth_presence_prefs"
        private const val KEY_TRACKED_DEVICES = "tracked_devices"
        private const val KEY_RSSI_THRESHOLD = "rssi_threshold"
        private const val KEY_AWAY_DELAY = "away_delay_seconds"
        private const val KEY_SCAN_INTERVAL = "scan_interval_seconds"
        private const val KEY_PRESENCE_ALERT_ADDRESS = "presence_alert_address"
        private const val KEY_CACHED_CAPABILITY = "cached_proxy_capability_json"
        /** In `bluetooth_settings` alongside `detect_enabled`, since it is a child of that switch. */
        const val KEY_SCREEN_OFF_PERSISTENCE = "screen_off_persistence_enabled"
        const val SCREEN_OFF_PERSISTENCE_DEFAULT = true
        /** Compatibility duty cycle is 5s scan + 25s rest; anything longer means blind. */
        private const val SCAN_COVERAGE_STALE_MS = 45_000L
        /** Caps the flap penalty so a device that really left is still reported. */
        private const val MAX_FLAP_STRIKES = 3
        /** Uninterrupted presence that earns one strike back. */
        private const val FLAP_STRIKE_DECAY_MS = 10 * 60_000L
        /** Ambient silence that still counts as a working receiver; one duty cycle plus margin. */
        private const val AMBIENT_STALE_MS = 45_000L
        /** Away delays of ambient silence before the liveness gate concedes and fails open. */
        private const val AMBIENT_HOLD_AWAY_DELAYS = 3L
        /**
         * How long simultaneous silence across every tracked device may hold a departure back.
         * The remedy is a scan session rotation, and the scan layer polls for one on a 30s
         * maintenance tick, so this leaves room for a few of those plus the handover grace and
         * a peer's own away bar. Past it the gate concedes: devices really can leave together.
         */
        private const val PEER_DEAF_HOLD_CAP_MS = 2 * 60_000L
        /**
         * Pulse windows a verdict may span when the first one heard nothing at all. One extra is
         * enough for the scan layer to rotate the session and hear again; past it the verdict is
         * taken as it stands, because a quiet room reads the same as a deaf radio and waiting
         * indefinitely for a distinction that may never come is worse than a late departure.
         */
        private const val DARK_WAKE_DEAF_WINDOW_GRACE = 2L

        /** Beyond this a gap is an absence rather than advertising rhythm, so it is not learned. */
        private const val GAP_SAMPLE_CAP_MS = 60_000L
        /** Headroom over the RSSI threshold that marks a hit as "well inside range". */
        private const val GAP_LEARN_RSSI_MARGIN_DB = 10
        /** Recent gaps kept per device, for the p50/p95/max diagnostic only. */
        private const val GAP_HISTORY_SIZE = 128
        /** How long the worst observed gap takes to fade to nothing. */
        private const val WORST_GAP_DECAY_MS = 30 * 60_000L
        /**
         * Stall assumed of a device that has not been watched long enough to rule one out.
         * With [GAP_MARGIN_PERCENT] this is the away bar a freshly seen device starts from.
         */
        private const val UNPROVEN_GAP_ALLOWANCE_MS = 20_000L
        /** Headroom over the measured worst gap, as a percentage, so the bar clears the floor. */
        private const val GAP_MARGIN_PERCENT = 150L
        /**
         * Scan cycles a weak streak must span before an *observed* departure — heard, but
         * below the threshold the whole time — is believed. The silence budgets do not
         * apply to that case (there is nothing to disambiguate: the radio works and the
         * device fails the user's rule on every advert), but RSSI flutters, so one weak
         * advert proves nothing. Two cycles plus the usual confirmation keeps a transient
         * dip from publishing a departure while making a threshold change land in tens of
         * seconds instead of the full away delay.
         */
        private const val OBSERVED_AWAY_STREAK_CYCLES = 2
        /** A device in range answers a connect well inside this; the cost of a real departure. */
        private const val REACHABILITY_PROBE_TIMEOUT_MS = 4_000L
        
        @Volatile
        private var instance: BluetoothPresenceManager? = null
        
        fun getInstance(context: Context): BluetoothPresenceManager {
            return instance ?: synchronized(this) {
                instance ?: BluetoothPresenceManager(context.applicationContext).also { 
                    instance = it
                    it.autoGrantLocationPermissionIfNeeded()
                    com.example.ava.utils.ShizukuUtils.setOnShellReady {
                        if (BleIrkBondStore.canReadBondStore()) {
                            it.refreshMissingIrksFromBondStoreAsync()
                        }
                    }
                }
            }
        }
    }
    
    private fun autoGrantLocationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S && isLowEndBleChip() && com.example.ava.utils.RootUtils.isRootAvailable()) {
            val needsLocation = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED
            if (needsLocation) {
                Thread {
                    val success = com.example.ava.utils.RootUtils.grantBluetoothLocationPermission(context.packageName)
                    Log.i(TAG, "Auto grant location permission for low-end BLE chip via root: success=$success")
                }.start()
            }
        }
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val bleScanner = bluetoothAdapter?.bluetoothLeScanner
    private val bleAdvertiser: BluetoothLeAdvertiser? = bluetoothAdapter?.bluetoothLeAdvertiser
    
    /**
     * "auto" | "active" | "passive". Auto (default) hands runtime mode control to
     * Home Assistant's Auto scanning mode. Reset stored values to auto exactly once:
     * older builds let HA's SetModeRequest overwrite this pref, and HA's Auto mode
     * pins PASSIVE on every connect, so an existing "passive"/"active" here is
     * usually HA's pin echoed back — not a choice the user made.
     */
    private val _proxyScanMode = MutableStateFlow(loadProxyScanModeWithAutoMigration())
    val proxyScanModeFlow = _proxyScanMode.asStateFlow()
    var proxyScanMode: String
        get() = _proxyScanMode.value
        set(value) {
            val next = when (value) {
                "auto", "active", "passive" -> value
                else -> "auto"
            }
            _proxyScanMode.value = next
            prefs.edit().putString("proxy_scan_mode", next).apply()
        }

    private fun loadProxyScanModeWithAutoMigration(): String {
        if (!prefs.getBoolean("proxy_scan_mode_auto_migrated", false)) {
            prefs.edit()
                .putString("proxy_scan_mode", "auto")
                .putBoolean("proxy_scan_mode_auto_migrated", true)
                .apply()
        }
        return prefs.getString("proxy_scan_mode", "auto") ?: "auto"
    }
    
    private val _proxyScanPower = MutableStateFlow(prefs.getString("proxy_scan_power", "low") ?: "low")
    val proxyScanPowerFlow = _proxyScanPower.asStateFlow()
    var proxyScanPower: String
        get() = _proxyScanPower.value
        set(value) { 
            val next = when (value) {
                "balanced", "high", "low" -> value
                else -> "low"
            }
            val changed = next != _proxyScanPower.value
            _proxyScanPower.value = next
            prefs.edit().putString("proxy_scan_power", next).apply()
            if (changed && (isAdvertising || isAdvertisingStarting)) {
                stopAdvertising()
                startAdvertising()
            }
        }
    

    val localDeviceId: String by lazy {
        try {
            com.example.ava.voice.AvaVoiceDiscovery.resolveLocalDeviceId(context)
        } catch (_: Exception) {
            "ava_default"
        }
    }
    

    private val udpPort = 19847
    private val claimEchoTag: String = UUID.randomUUID().toString().take(8)
    private var claimLedger: BluetoothClaimLedger? = null
    private var udpSocket: DatagramSocket? = null
    private var udpListenerJob: Job? = null
    private var claimHeartbeatJob: Job? = null
    private var broadcastScope: CoroutineScope? = null
    private var claimMulticastLock: WifiManager.MulticastLock? = null

    /** Fired on a coroutine (never under [scannedDevices] locks) when local claims change. */
    @Volatile
    var onClaimsChanged: (() -> Unit)? = null

    private val maxClaims: Int
        get() = if (isLowEndBleChip()) 3 else 5

    fun startClaimSync(scope: CoroutineScope) {
        if (udpListenerJob?.isActive == true && claimHeartbeatJob?.isActive == true) return
        udpSocket?.close()
        udpListenerJob?.cancel()
        claimHeartbeatJob?.cancel()
        udpListenerJob = null
        claimHeartbeatJob = null
        broadcastScope = scope
        if (claimLedger == null) {
            claimLedger = BluetoothClaimLedger(localDeviceId, claimEchoTag, maxClaims)
        }
        acquireClaimMulticastLock()
        udpListenerJob = scope.launch(Dispatchers.IO) {
            try {
                val socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(udpPort))
                }
                udpSocket = socket
                val buffer = ByteArray(256)
                while (isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val data = String(packet.data, 0, packet.length)
                    val msg = BluetoothClaimProtocol.parse(data) ?: continue
                    val yielded = claimLedger?.applyRemote(msg) == true
                    if (yielded) {
                        Log.i(TAG, "Yielded claim ${msg.address} to ${msg.senderId}")
                        notifyClaimsChanged()
                    }
                }
            } catch (e: java.net.SocketException) {
                Log.d(TAG, "UDP socket closed")
            } catch (e: Exception) {
                Log.w(TAG, "UDP listener error", e)
            }
        }
        claimHeartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(BluetoothClaimLedger.HEARTBEAT_MS)
                val now = System.currentTimeMillis()
                claimLedger?.localRecords()?.forEach { rec ->
                    broadcastClaim(rec.address, rec.rssi, now - rec.claimedAtMs)
                }
                val released = claimLedger?.expire().orEmpty()
                if (released.isNotEmpty()) {
                    released.forEach { broadcastRelease(it) }
                    Log.i(TAG, "Expired ${released.size} local BTHome claim(s)")
                    notifyClaimsChanged()
                }
            }
        }
    }

    fun stopClaimSync() {
        val released = claimLedger?.clearLocal().orEmpty()
        released.forEach { broadcastRelease(it) }
        if (released.isNotEmpty()) notifyClaimsChanged()
        udpSocket?.close()
        udpSocket = null
        udpListenerJob?.cancel()
        udpListenerJob = null
        claimHeartbeatJob?.cancel()
        claimHeartbeatJob = null
        broadcastScope = null
        claimLedger = null
        releaseClaimMulticastLock()
    }

    private fun notifyClaimsChanged() {
        val cb = onClaimsChanged ?: return
        val scope = broadcastScope
        if (scope != null) {
            scope.launch { cb() }
        } else {
            cb()
        }
    }

    private fun broadcastTargets(): List<InetAddress> {
        val targets = linkedSetOf(InetAddress.getByName("255.255.255.255"))
        try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.interfaceAddresses.asSequence() }
                .mapNotNull { it.broadcast }
                .forEach { targets.add(it) }
        } catch (e: Exception) {
            Log.w(TAG, "claim broadcast target resolve failed: ${e.message}")
        }
        return targets.toList()
    }

    private fun broadcastPacket(payload: String) {
        val scope = broadcastScope ?: return
        val data = payload.toByteArray()
        scope.launch(Dispatchers.IO) {
            try {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    for (target in broadcastTargets()) {
                        socket.send(DatagramPacket(data, data.size, target, udpPort))
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to broadcast claim packet", e)
            }
        }
    }

    private fun broadcastClaim(address: Long, rssi: Int = 0, heldMs: Long = 0L) {
        broadcastPacket(
            BluetoothClaimProtocol.encodeClaim(localDeviceId, address, rssi, heldMs, claimEchoTag),
        )
    }

    private fun broadcastRelease(address: Long) {
        broadcastPacket(BluetoothClaimProtocol.encodeRelease(localDeviceId, address, claimEchoTag))
    }

    private fun acquireClaimMulticastLock() {
        if (claimMulticastLock?.isHeld == true) return
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            claimMulticastLock = wifiManager.createMulticastLock("$TAG::ClaimMulticast").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "claim multicast lock acquire failed: ${e.message}")
        }
    }

    private fun releaseClaimMulticastLock() {
        try {
            claimMulticastLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            Log.w(TAG, "claim multicast lock release failed: ${e.message}")
        } finally {
            claimMulticastLock = null
        }
    }

    fun claimDevice(address: Long, rssi: Int = 0): Boolean {
        val ledger = claimLedger ?: BluetoothClaimLedger(localDeviceId, claimEchoTag, maxClaims).also {
            claimLedger = it
        }
        return when (ledger.claim(address, rssi)) {
            ClaimOutcome.NEW -> {
                broadcastClaim(address, rssi, 0L)
                notifyClaimsChanged()
                true
            }
            ClaimOutcome.ALREADY -> true
            ClaimOutcome.REJECTED -> false
        }
    }

    fun noteClaimObservation(address: Long, rssi: Int) {
        val ledger = claimLedger ?: return
        if (ledger.noteObservation(address, rssi)) {
            val rec = ledger.localRecords().firstOrNull { it.address == address }
            broadcastClaim(address, rec?.rssi ?: rssi, 0L)
            Log.i(TAG, "Took over BTHome claim $address rssi=$rssi")
            notifyClaimsChanged()
        }
    }

    fun isDeviceClaimed(address: Long): Boolean {
        return claimLedger?.isClaimed(address) == true
    }

    fun getClaimedDevices(): List<Long> {
        return claimLedger?.localAddresses().orEmpty()
    }

    fun clearClaimedDevices() {
        val released = claimLedger?.clearLocal().orEmpty()
        released.forEach { broadcastRelease(it) }
        if (released.isNotEmpty()) notifyClaimsChanged()
    }
    
    private val AVA_SERVICE_UUID = UUID.fromString("0000180F-0000-1000-8000-00805F9B34FB")
    
    @Volatile private var isAdvertising = false
    @Volatile private var isAdvertisingStarting = false

    @Volatile
    private var presenceAdvertisingSuppressed = false

    /** Blocks [startAdvertising] and stops active ADV while another BLE owner needs the radio. */
    fun setPresenceAdvertisingSuppressed(suppressed: Boolean) {
        presenceAdvertisingSuppressed = suppressed
        if (suppressed) {
            stopAdvertising()
            Log.i(TAG, "presence advertising suppressed")
        } else {
            Log.i(TAG, "presence advertising unsuppressed")
        }
    }

    fun isPresenceAdvertisingSuppressed(): Boolean = presenceAdvertisingSuppressed
    
    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            isAdvertisingStarting = false
            isAdvertising = true
            if (presenceAdvertisingSuppressed) {
                stopAdvertising()
                Log.i(TAG, "BLE advertising stopped after a late start callback")
                return
            }
            Log.i(TAG, "BLE advertising started successfully")
        }
        
        override fun onStartFailure(errorCode: Int) {
            isAdvertisingStarting = false
            // Android reports ALREADY_STARTED for the same callback that is still registered.
            // Keep it stoppable instead of retrying forever with a false local state.
            isAdvertising = errorCode == AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED
            Log.e(TAG, "BLE advertising failed with error code: $errorCode")
            if (presenceAdvertisingSuppressed && isAdvertising) {
                stopAdvertising()
            }
        }
    }

    
    private val _rssiThreshold = MutableStateFlow(prefs.getInt(KEY_RSSI_THRESHOLD, -80))
    val rssiThresholdFlow = _rssiThreshold.asStateFlow()
    var rssiThreshold: Int
        get() = _rssiThreshold.value
        set(value) {
            val v = value.coerceIn(-120, 0)
            _rssiThreshold.value = v
            prefs.edit().putInt(KEY_RSSI_THRESHOLD, v).apply()
            reevaluatePresence(v, awayDelaySeconds)
        }
    
    val scanIntervalSeconds: Int = 6
    
    private val _awayDelaySeconds = MutableStateFlow(
        prefs.getInt(KEY_AWAY_DELAY, 120).coerceIn(maxOf(5, scanIntervalSeconds + 1), 3600)
    )
    val awayDelaySecondsFlow = _awayDelaySeconds.asStateFlow()

    private val _presenceAlertAddress = MutableStateFlow(prefs.getString(KEY_PRESENCE_ALERT_ADDRESS, null))
    val presenceAlertAddressFlow = _presenceAlertAddress.asStateFlow()
    val presenceAlertAddress: String?
        get() = _presenceAlertAddress.value

    fun setPresenceAlertAddress(address: String) {
        if (address.isBlank()) return
        _presenceAlertAddress.value = address
        prefs.edit().putString(KEY_PRESENCE_ALERT_ADDRESS, address).apply()
        Log.d(TAG, "Presence alert device: $address")
    }

    fun togglePresenceAlertAddress(address: String) {
        if (address.isBlank()) return
        val next = if (_presenceAlertAddress.value == address) null else address
        _presenceAlertAddress.value = next
        prefs.edit().putString(KEY_PRESENCE_ALERT_ADDRESS, next).apply()
        Log.d(TAG, "Presence alert device: ${next ?: "none"}")
    }

    fun clearPresenceAlertAddressIfMatches(address: String) {
        if (_presenceAlertAddress.value == address) {
            _presenceAlertAddress.value = null
            prefs.edit().remove(KEY_PRESENCE_ALERT_ADDRESS).apply()
        }
    }
    var awayDelaySeconds: Int
        get() = _awayDelaySeconds.value
        set(value) {
            val v = value.coerceIn(maxOf(5, scanIntervalSeconds + 1), 3600)
            _awayDelaySeconds.value = v
            prefs.edit().putInt(KEY_AWAY_DELAY, v).apply()
            reevaluatePresence(rssiThreshold, v)
        }

    
    private val lastSeenTimestamp = mutableMapOf<String, Long>()
    
    /** When the away delay first ran out, per device; cleared by any hit. See [checkTimeouts]. */
    private val awayCandidateSince = mutableMapOf<String, Long>()

    /**
     * Per-device flap penalty. Hardware that goes deaf for a while is indistinguishable
     * from an empty room at the moment of the decision, but not afterwards: a departure
     * disproved sooner than the delay that justified it was never a departure. Each of
     * those raises the confirmation bar for that device via [awayConfirmGapMillis], and
     * [decayFlapStrikes] gives it back once the device stays put. In-memory on purpose —
     * radio behaviour is a property of the current session, not something to persist.
     */
    private val flapStrikes = mutableMapOf<String, Int>()
    private val lastAwayPublishedAt = mutableMapOf<String, Long>()
    private val lastFlapStrikeAt = mutableMapOf<String, Long>()

    /** Last published transition per device; gates departures via [hasHeldPresenceLongEnough]. */
    private val lastPresencePublishAt = mutableMapOf<String, Long>()

    /**
     * How this device actually advertises, measured instead of assumed. A departure cannot
     * be detected faster than the device's own worst normal silence, so any fixed delay
     * shorter than that is guaranteed to report departures that never happened — no amount
     * of extra confirmation rounds can fix a bar set below the noise floor. These feed
     * [awayDelayMillisFor], which raises the bar to clear the measured floor and otherwise
     * leaves the user's setting alone.
     */
    private val lastScanHitAtMs = mutableMapOf<String, Long>()
    private val lastScanHitRssi = mutableMapOf<String, Int>()
    private val hitGapSamples = mutableMapOf<String, ArrayDeque<Long>>()
    private val worstGapMs = mutableMapOf<String, Long>()
    private val worstGapAtMs = mutableMapOf<String, Long>()

    /** Concurrent: the away pipeline starts these, a GATT callback thread resolves them. */
    private val reachabilityProbeStartedAt = ConcurrentHashMap<String, Long>()
    private val probeGatt = ConcurrentHashMap<String, BluetoothGatt>()

    /**
     * When a dark wake pulse was last asked for on this device's behalf. Deliberately *not*
     * cleared when the departure is published — only [markPresent] clears it, which is what
     * limits a device to one pulse per absence rather than one per away decision.
     */
    private val darkWakeAskedAt = ConcurrentHashMap<String, Long>()
    
    private val currentScanMaxRssi = mutableMapOf<String, Int>()
    
    private val lastKnownRssi = mutableMapOf<String, Int>()

    /**
     * When [lastKnownRssi] was written. Unlike [lastSeenTimestamp] this covers hits at any
     * strength, which is what lets [heardBelowThresholdRecently] tell "heard but out of
     * range by the user's rule" apart from "not heard at all" — the distinction the whole
     * deaf-receiver escalation exists to guess at when all it has is silence.
     */
    private val lastKnownRssiAtMs = mutableMapOf<String, Long>()

    /**
     * First below-threshold hit since the last presence-grade evidence, per device.
     * Cleared by [markPresent], so an entry standing means every advert since then failed
     * the user's rule — the observed counterpart of the silence the away delay waits out.
     * Judged only at decision time (against the *current* threshold, with a freshness
     * bound), so a threshold change mid-streak cannot smuggle in stale conclusions.
     */
    private val weakStreakSince = mutableMapOf<String, Long>()

    /** Scanned RPA → stable tracked address. Presence-only; never rewrite proxy MACs. */
    private val irkMacCacheLock = Any()
    private val macToTrackedAddressCache = mutableMapOf<String, String>()
    private val identityAddressMethodLock = Any()
    @Volatile private var identityAddressMethodResolved = false
    @Volatile private var identityAddressMethod: java.lang.reflect.Method? = null
    private val irkAttachInFlight = Collections.synchronizedSet(mutableSetOf<String>())
    /** Recent RPAs from the normal presence scan (avoids fragile parallel probe scans). */
    private val recentRpaLock = Any()
    private val recentRpaSeenAtMs = linkedMapOf<String, Long>()
    /** Bond-store IRK candidates waiting for an RPA that resolves them. */
    private val pendingIrkCandidates = ConcurrentHashMap<String, List<String>>()
    private val irkProbeScanLock = Any()
    @Volatile private var irkProbeScanActive = false

    
    private val detectPrefs = context.getSharedPreferences("bluetooth_settings", Context.MODE_PRIVATE)
    val isDetectEnabled: Boolean
        get() = detectPrefs.getBoolean("detect_enabled", false)

    /**
     * Whether [DarkWakePulse] may briefly wake the display to win the scanner back from ROMs that
     * suppress it after a long screen-off. On by default: the ROMs that need it report a departure
     * that never happened, which is worse than a black screen nobody sees. Read live rather than
     * cached so toggling it takes effect without restarting the satellite.
     */
    val isScreenOffPersistenceEnabled: Boolean
        get() = detectPrefs.getBoolean(KEY_SCREEN_OFF_PERSISTENCE, SCREEN_OFF_PERSISTENCE_DEFAULT)
    
    
    fun clearAllPresence() {

        val tracked = _trackedDevices.value
        if (tracked.isNotEmpty()) {
            _devicePresence.value = tracked.mapValues { false }
        } else {
            _devicePresence.value = emptyMap()
        }
        lastSeenTimestamp.clear()
        awayCandidateSince.clear()
        currentScanMaxRssi.clear()
        // Not a departure anyone observed, so it must neither earn a flap strike when the
        // devices come back nor let a stale publish time gate the next real one. The
        // learned penalties and the measured advert rhythm survive: both describe the
        // hardware, which is unchanged by whatever cleared presence.
        lastAwayPublishedAt.clear()
        lastPresencePublishAt.clear()
        // Would otherwise read as one enormous gap across the outage and teach every device
        // that vanishing for that long is normal.
        lastScanHitAtMs.clear()
        lastScanHitRssi.clear()
        // Same reasoning in the other direction: a streak spanning the outage would read
        // as long-observed weakness and fast-track a departure nobody watched happen.
        weakStreakSince.clear()
        reachabilityProbeStartedAt.keys.forEach { closeProbeGatt(it) }
        reachabilityProbeStartedAt.clear()
        darkWakeAskedAt.clear()
        peerDeafSince = 0L
        peerDeafConceded = false
        Log.d(TAG, "Cleared all presence, ${tracked.size} devices set to false")
    }
    
    /**
     * A new threshold or away delay re-reads the last hit under the new rule, and only
     * ever restores On. A config write is not an observation: it carries no evidence
     * about the room, so it must not be able to report a departure. [checkTimeouts] stays
     * the single Off path — a stricter threshold stops [settlePresenceHit] refreshing the
     * device, so it still ages out, through its weak-hit streak when the device is still
     * being heard or the away delay when it is not. Reporting Off from here also skipped
     * the confirmation entirely and fired on the last advert's RSSI, so one weak advert
     * from a device sitting right there published a departure that the next advert took
     * straight back.
     *
     * On, though, may stand on a hit the old rule rejected: a device heard seconds ago at
     * a strength the *new* threshold accepts was genuinely observed in range, and waiting
     * for the next advert to repeat that observation is pure latency. That path bypasses
     * [markPresent] because its arrival side effects assume the radio disproved a
     * departure — a flap strike earned by a slider drag would punish the device for the
     * user's experiment. The last-seen clock is set to the hit's own time, not "now", so
     * the away pipeline continues from what was actually observed.
     */
    private fun reevaluatePresence(threshold: Int, delaySeconds: Int) {
        val tracked = _trackedDevices.value
        if (tracked.isEmpty()) return

        val now = System.currentTimeMillis()
        val delayMillis = delaySeconds * 1000L
        tracked.keys.forEach { address ->
            val lastRssi = lastKnownRssi[address] ?: return@forEach
            if (lastRssi < threshold) return@forEach

            val lastSeen = lastSeenTimestamp[address]?.takeIf { it > 0L }
            if (lastSeen != null && (now - lastSeen) < delayMillis) {
                updateDevicePresence(address, true)
                return@forEach
            }

            val heardAt = lastKnownRssiAtMs[address] ?: return@forEach
            if (now - heardAt > AMBIENT_STALE_MS) return@forEach
            lastSeenTimestamp[address] = heardAt
            weakStreakSince.remove(address)
            awayCandidateSince.remove(address)
            darkWakeAskedAt.remove(address)
            updateDevicePresence(address, true)
        }
    }
    
    /**
     * Detection was just (re)enabled. Keep the presence we already hold and give every
     * present device one full away delay to be re-detected: blanking the map here
     * published an Off that the very next advert flipped back On, a flicker no timer
     * ever asked for.
     */
    fun refreshAllDevicePresence() {
        val tracked = _trackedDevices.value
        if (tracked.isEmpty()) return
        val now = System.currentTimeMillis()
        val presence = _devicePresence.value
        tracked.keys.forEach { address ->
            if (presence[address] == true) {
                lastSeenTimestamp[address] = now
            }
        }
        awayCandidateSince.clear()
        currentScanMaxRssi.clear()
        Log.d(TAG, "Refreshed presence timers for ${tracked.size} devices, waiting for scan")
    }
    
    
    private val _trackedDevices = MutableStateFlow<Map<String, TrackedDevice>>(emptyMap())
    val trackedDevices: StateFlow<Map<String, TrackedDevice>> = _trackedDevices.asStateFlow()
    
    
    private val _devicePresence = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val devicePresence: StateFlow<Map<String, Boolean>> = _devicePresence.asStateFlow()
    
    
    // ACTION_UUID receiver removed: it only logged and ignored results (presence is
    // BLE-scan based), and nothing calls fetchUuidsWithSdp anymore.

    @SuppressLint("MissingPermission")
    fun resetBluetoothAdapter(): Boolean {
        if (!isLowEndBleChip()) return false
        val adapter = bluetoothManager?.adapter ?: return false
        if (!adapter.isEnabled) return false
        
        Log.i(TAG, "Resetting Bluetooth adapter for low-end BLE chip")
        return try {
            adapter.disable()
            Thread.sleep(2000)
            adapter.enable()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reset Bluetooth adapter", e)
            false
        }
    }
    
    private var presenceLinkReceiver: BroadcastReceiver? = null

    init {
        loadTrackedDevices()
        startPresenceLinkWatch()
    }

    @SuppressLint("MissingPermission")
    private fun loadTrackedDevices() {
        val json = prefs.getString(KEY_TRACKED_DEVICES, null)
        Log.d(TAG, "Loading tracked devices, raw JSON: $json")
        if (json != null) {
            try {
                val type = object : TypeToken<Map<String, TrackedDevice>>() {}.type
                val saved: Map<String, TrackedDevice> = gson.fromJson(json, type)
                Log.d(TAG, "Parsed ${saved.size} devices from JSON")
                
                val bondedDevices = try {
                    bluetoothAdapter?.bondedDevices?.associateBy { it.address } ?: emptyMap()
                } catch (e: Exception) {
                    emptyMap()
                }
                
                val fixedDevices = saved.mapValues { (address, device) ->
                    if (device.name.isBlank() && bondedDevices.containsKey(address)) {
                        val systemName = bondedDevices[address]?.name ?: ""
                        if (systemName.isNotBlank()) {
                            Log.d(TAG, "Fixed empty name for $address -> $systemName")
                            device.copy(name = systemName, bonded = true)
                        } else {
                            device
                        }
                    } else {
                        device
                    }
                }
                
                fixedDevices.forEach { (address, device) ->
                    Log.d(TAG, "  Device: address='$address', name='${device.name}', bonded=${device.bonded}")
                }
                

                val validDevices = fixedDevices
                    .filter { (address, _) -> address.isNotBlank() }
                    .mapValues { (address, device) ->
                        if (device.name.isBlank()) {
                            device.copy(name = address.takeLast(8))
                        } else {
                            device
                        }
                    }
                _trackedDevices.value = validDevices
                _devicePresence.value = validDevices.mapValues { false }
                Log.d(TAG, "Loaded ${validDevices.size} valid tracked devices")
                if (saved.size != validDevices.size) {
                    Log.w(TAG, "Filtered out ${saved.size - validDevices.size} invalid devices")
                    saveTrackedDevices()
                }
                refreshMissingIrksFromBondStoreAsync()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load tracked devices", e)
            }
        }
    }

    private fun saveTrackedDevices() {
        val json = gson.toJson(_trackedDevices.value)
        prefs.edit().putString(KEY_TRACKED_DEVICES, json).apply()
        Log.d(TAG, "Saved tracked devices: $json")
    }

    data class TrackedDevice(
        @SerializedName("address") val address: String = "",
        @SerializedName("name") val name: String = "",
        @SerializedName("bonded") val bonded: Boolean = false,
        /** Empty = system default notification sound. */
        @SerializedName("alert_sound_uri") val alertSoundUri: String = "",
        @SerializedName("alert_trigger") val alertTrigger: String = com.example.ava.settings.BluetoothPresenceAlertTrigger.NEARBY,
        /**
         * Optional Identity Resolving Key (normalized uppercase hex, 32 chars).
         * Auxiliary: when set, rotating RPAs that resolve with this IRK still count
         * as this device. Empty is normal for users without root/Shizuku — presence
         * then uses exact MAC, the system resolver, or a single-identity address filter.
         */
        @SerializedName("irk") val irk: String = "",
    )
    
    
    fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    /** Permissions required to start BLE / classic discovery in settings UI. */
    fun hasScanPermissions(): Boolean = hasBluetoothPermissions()
    
    
    fun hasBasicBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            
            true
        }
    }
    
    
    fun isBluetoothAvailable(): Boolean = bluetoothAdapter != null
    
    
    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true
    
    
    fun adapterState(): Int = bluetoothAdapter?.state ?: android.bluetooth.BluetoothAdapter.STATE_OFF
    
    
    /** True only when the radio is fully OFF — not while TURNING_ON / TURNING_OFF / BLE_ON. */
    fun isBluetoothStrictlyOff(): Boolean = adapterState() == android.bluetooth.BluetoothAdapter.STATE_OFF
    
    
    fun isBle5Supported(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val adapter = bluetoothAdapter ?: return false
        return adapter.isLe2MPhySupported || adapter.isLeCodedPhySupported || adapter.isLeExtendedAdvertisingSupported
    }
    
    
    fun getBluetoothFeatures(): Map<String, Boolean> {
        val features = mutableMapOf<String, Boolean>()
        val adapter = bluetoothAdapter ?: return features
        
        features["ble_available"] = true
        features["enabled"] = adapter.isEnabled
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            features["le_2m_phy"] = adapter.isLe2MPhySupported
            features["le_coded_phy"] = adapter.isLeCodedPhySupported
            features["le_extended_advertising"] = adapter.isLeExtendedAdvertisingSupported
            features["le_periodic_advertising"] = adapter.isLePeriodicAdvertisingSupported
            features["ble5"] = adapter.isLe2MPhySupported || adapter.isLeCodedPhySupported
        } else {
            features["ble5"] = false
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            features["multiple_advertisement"] = adapter.isMultipleAdvertisementSupported
            features["offloaded_filtering"] = adapter.isOffloadedFilteringSupported
            features["offloaded_scan_batching"] = adapter.isOffloadedScanBatchingSupported
        }
        
        return features
    }

    /**
     * Live chipset probes require LE access. When the system Bluetooth radio is off,
     * Android returns false for every capability flag — which previously collapsed into a
     * false COMPATIBILITY / low-end classification. Prefer a cached ON-state measurement.
     */
    @Volatile
    private var cachedCapabilityReport: ProxyCapabilityReport? = loadCachedCapabilityReport()

    fun getProxyCapabilityReport(): ProxyCapabilityReport {
        if (!isBluetoothEnabled()) {
            cachedCapabilityReport?.let { return it }
            Log.w(TAG, "Bluetooth off with no cached capability; assuming BALANCED (not COMPAT)")
            return fallbackCapabilityWhenUnknown()
        }

        val adapter = bluetoothAdapter
        val offloadedFiltering = isOffloadedFilteringSupported()
        val multipleAdvertisement = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            adapter?.isMultipleAdvertisementSupported == true
        } else false
        val extendedAdvertising = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            adapter?.isLeExtendedAdvertisingSupported == true
        } else false
        val codedPhy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            adapter?.isLeCodedPhySupported == true
        } else false
        val le2MPhy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            adapter?.isLe2MPhySupported == true
        } else false
        val basicFeatureCount = listOf(
            offloadedFiltering,
            multipleAdvertisement,
            extendedAdvertising,
            le2MPhy
        ).count { it }
        val hasAdvancedBleSignal = extendedAdvertising || codedPhy || le2MPhy

        val tier = when {
            offloadedFiltering && multipleAdvertisement && hasAdvancedBleSignal -> ProxyCapabilityTier.FULL
            hasAdvancedBleSignal || basicFeatureCount >= 2 -> ProxyCapabilityTier.BALANCED
            else -> ProxyCapabilityTier.COMPATIBILITY
        }

        val report = ProxyCapabilityReport(
            tier = tier,
            compatibilityMode = tier == ProxyCapabilityTier.COMPATIBILITY,
            offloadedFilteringSupported = offloadedFiltering,
            multipleAdvertisementSupported = multipleAdvertisement,
            leExtendedAdvertisingSupported = extendedAdvertising,
            leCodedPhySupported = codedPhy,
            le2MPhySupported = le2MPhy
        )
        val upgraded = upgradeSamsungS10LiteCapability(report)
        rememberCapabilityReport(upgraded)
        return upgraded
    }

    private fun fallbackCapabilityWhenUnknown(): ProxyCapabilityReport {
        // Prefer not entering the aggressive low-end compat path from a BT-off probe.
        return ProxyCapabilityReport(
            tier = ProxyCapabilityTier.BALANCED,
            compatibilityMode = false,
            offloadedFilteringSupported = false,
            multipleAdvertisementSupported = false,
            leExtendedAdvertisingSupported = false,
            leCodedPhySupported = false,
            le2MPhySupported = false,
        )
    }

    private fun rememberCapabilityReport(report: ProxyCapabilityReport) {
        cachedCapabilityReport = report
        runCatching {
            prefs.edit().putString(KEY_CACHED_CAPABILITY, gson.toJson(report)).apply()
        }
    }

    private fun loadCachedCapabilityReport(): ProxyCapabilityReport? {
        val json = prefs.getString(KEY_CACHED_CAPABILITY, null) ?: return null
        return runCatching {
            gson.fromJson(json, ProxyCapabilityReport::class.java)
        }.getOrNull()
    }

    private fun upgradeSamsungS10LiteCapability(report: ProxyCapabilityReport): ProxyCapabilityReport {
        if (!DeviceFeatureManager.isSamsungS10LiteDevice() || !report.compatibilityMode) {
            return report
        }
        Log.i(TAG, "Upgrading S10 Lite BLE proxy capability from COMPATIBILITY to BALANCED")
        return report.copy(
            tier = ProxyCapabilityTier.BALANCED,
            compatibilityMode = false,
            le2MPhySupported = report.le2MPhySupported || true,
            leExtendedAdvertisingSupported = report.leExtendedAdvertisingSupported || true,
        )
    }
    
    
    fun killingBluetoothWouldDropWifi(): Boolean =
        BluetoothLowLevelHooks.killingBluetoothWouldDropWifi(context)

    fun isLowEndBleChip(): Boolean {
        com.example.ava.mods.ModDeviceSupport.isLowEndBleChip(context)?.let { return it }
        if (DeviceFeatureManager.isSamsungS10LiteDevice()) return false
        if (com.example.ava.utils.EchoShowSupport.isEchoShowDevice()) return true
        // Never classify from a live BT-off probe — capability APIs all return false then.
        if (!isBluetoothEnabled()) {
            return cachedCapabilityReport?.compatibilityMode == true
        }
        return getProxyCapabilityReport().compatibilityMode
    }
    
    
    fun isOffloadedFilteringSupported(): Boolean {
        if (!isBluetoothEnabled()) {
            return cachedCapabilityReport?.offloadedFilteringSupported == true
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            bluetoothAdapter?.isOffloadedFilteringSupported == true
        } else false
    }
    
    
    fun getRecommendedScanInterval(): Long {
        return if (isLowEndBleChip()) 30_000L else 6_000L
    }
    
    
    fun getRecommendedScanDuration(): Long {
        return if (isLowEndBleChip()) 5_000L else 3_000L
    }
    
    
    fun shouldUseScanFilters(): Boolean {
        // App-side IRK can follow RPAs on an unfiltered scan. Without it, address
        // filters are the user-facing path: the stack resolving list matches the
        // bonded identity MAC even when the advert is an RPA.
        if (hasAnyTrackedIrk()) return false
        return isOffloadedFilteringSupported()
    }

    fun hasAnyTrackedIrk(): Boolean {
        return _trackedDevices.value.values.any { it.irk.isNotBlank() }
    }
    
    
    @SuppressLint("MissingPermission")
    fun getBondedDevices(): List<TrackedDevice> {
        if (!hasBasicBluetoothPermissions() || !isBluetoothEnabled()) {
            return emptyList()
        }

        val unknownLabel = context.getString(R.string.settings_bluetooth_unknown_device)
        val bonded = try {
            bluetoothAdapter?.bondedDevices
        } catch (e: SecurityException) {
            Log.w(TAG, "getBondedDevices: bondedDevices denied", e)
            return emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "getBondedDevices: bondedDevices failed", e)
            return emptyList()
        } ?: return emptyList()

        return bonded.mapNotNull { device ->
            try {
                val address = BluetoothDeviceNames.safeAddress(device) ?: return@mapNotNull null
                TrackedDevice(
                    address = address,
                    name = BluetoothDeviceNames.safeName(device, unknownLabel),
                    bonded = true,
                )
            } catch (e: Exception) {
                Log.w(TAG, "getBondedDevices: skip device", e)
                null
            }
        }
    }

    /** Live bond check (do not rely on stale [TrackedDevice.bonded]). */
    @SuppressLint("MissingPermission")
    fun isAddressBonded(address: String): Boolean {
        if (address.isBlank() || !hasBasicBluetoothPermissions() || !isBluetoothEnabled()) return false
        return try {
            bluetoothAdapter?.getRemoteDevice(address)?.bondState == BluetoothDevice.BOND_BONDED
        } catch (e: Exception) {
            Log.w(TAG, "isAddressBonded failed for $address", e)
            false
        }
    }

    /**
     * Start system pairing for [address] so the stack resolving list can match
     * rotating RPAs to this identity MAC. IRK file-read is separate and optional.
     * @return true if [BluetoothDevice.createBond] was accepted by the stack.
     */
    @SuppressLint("MissingPermission")
    fun requestBond(address: String): Boolean {
        if (address.isBlank() || !hasBasicBluetoothPermissions() || !isBluetoothEnabled()) return false
        if (isAddressBonded(address)) return true
        return try {
            val device = bluetoothAdapter?.getRemoteDevice(address) ?: return false
            val started = device.createBond()
            Log.d(TAG, "requestBond($address) started=$started state=${device.bondState}")
            started
        } catch (e: Exception) {
            Log.w(TAG, "requestBond failed for $address", e)
            false
        }
    }
    
    
    @SuppressLint("MissingPermission")
    fun startClassicDiscovery(): Boolean {
        if (!hasBasicBluetoothPermissions() || !isBluetoothEnabled()) {
            return false
        }
        return try {
            bluetoothAdapter?.startDiscovery() ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start classic discovery", e)
            false
        }
    }
    
    
    @SuppressLint("MissingPermission")
    fun cancelClassicDiscovery() {
        try {
            bluetoothAdapter?.cancelDiscovery()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel classic discovery", e)
        }
    }
    
    
    // Legacy classic-presence probes (fetchUuidsWithSdp) removed: presence is decided by
    // BLE scanning only, and each SDP fetch paged the bonded peer over BR/EDR (real ACL
    // link), making system UIs refresh profiles and MIUI re-suggest connections
    // ("Already link is up") every cycle.

    fun addTrackedDevice(device: TrackedDevice) {
        val bonded = device.bonded || isAddressBonded(device.address)
        val toSave = device.copy(bonded = bonded)
        val currentDevices = _trackedDevices.value.toMutableMap()
        currentDevices[toSave.address] = toSave
        _trackedDevices.value = currentDevices
        saveTrackedDevices()

        val currentPresence = _devicePresence.value.toMutableMap()
        currentPresence[toSave.address] = false
        _devicePresence.value = currentPresence

        Log.d(TAG, "Added tracked device: ${toSave.name} (${toSave.address}), bonded=$bonded")
        // Privileged bond-store read (root / Shizuku) right after add.
        tryAttachIrkFromBondStoreAsync(toSave.address)
    }

    /** Root or Shizuku started as root — ADB Shizuku cannot read the bond store. */
    fun canAutoReadIrkFromBondStore(): Boolean = BleIrkBondStore.canReadBondStore()

    /**
     * Best-effort IRK attach from system bond store (root / root-Shizuku).
     * For paired devices, persist as soon as privilege read succeeds; refine endianness
     * later if a nearby RPA proves the alternate candidate.
     */
    fun tryAttachIrkFromBondStoreAsync(address: String) {
        if (address.isBlank()) return
        if (!BleIrkBondStore.canReadBondStore()) {
            Log.d(TAG, "Skip bond-store IRK for $address (need root or root-Shizuku)")
            return
        }
        if (!irkAttachInFlight.add(address)) return
        Thread({
            try {
                tryAttachIrkFromBondStoreOnce(address)
            } catch (e: Exception) {
                Log.w(TAG, "tryAttachIrkFromBondStoreAsync failed for $address", e)
            } finally {
                irkAttachInFlight.remove(address)
            }
        }, "ava-irk-attach").start()
    }

    /**
     * Read IRK via privilege, prefer RPA-validated endianness, otherwise trust bond-store
     * preferred candidate when the peer is paired (add-time / retry path).
     */
    private fun tryAttachIrkFromBondStoreOnce(address: String): Boolean {
        val existing = _trackedDevices.value[address] ?: return true
        if (existing.irk.isNotBlank()) {
            pendingIrkCandidates.remove(address)
            return true
        }

        val candidates = BleIrkBondStore.tryReadRemoteIrkCandidates(context, address)
        if (candidates.isEmpty()) {
            Log.d(TAG, "No LE_KEY_PID for $address in bond store")
            return false
        }

        var rpas = snapshotRecentRpas()
        var resolved = selectValidatedIrk(candidates, rpas)
        if (resolved == null) {
            rpas = collectNearbyRpaAddresses(timeoutMs = 2_000L)
            resolved = selectValidatedIrk(candidates, rpas)
        }
        if (resolved != null) {
            return commitValidatedIrk(address, resolved.irk, resolved.rpa)
        }

        // Paired + privilege read succeeded: save preferred (HA reversed) immediately.
        // Keep all candidates pending so a later RPA can correct endianness if needed.
        if (isAddressBonded(address) || existing.bonded) {
            val preferred = candidates.first()
            val ok = updateTrackedDeviceIrk(address, preferred)
            if (ok) {
                if (candidates.size > 1) {
                    // updateTrackedDeviceIrk clears pending; restore for endianness refine.
                    pendingIrkCandidates[address] = candidates
                }
                Log.i(
                    TAG,
                    "IRK attached from bond store for paired $address " +
                        "(fp=${BleIrkResolver.irkFingerprint(preferred)}, pendingEndian=${candidates.size > 1})",
                )
            }
            return ok
        }

        pendingIrkCandidates[address] = candidates
        Log.d(
            TAG,
            "IRK candidates (${candidates.size}) parked for unpaired $address; waiting for RPA " +
                "(rpas=${rpas.size})",
        )
        return false
    }

    private data class ValidatedIrk(val irk: String, val rpa: String)

    private fun selectValidatedIrk(
        candidates: List<String>,
        rpas: Collection<String>,
    ): ValidatedIrk? {
        if (candidates.isEmpty() || rpas.isEmpty()) return null
        for (irkHex in candidates) {
            val bytes = BleIrkResolver.parseIrk(irkHex) ?: continue
            val matched = rpas.firstOrNull { BleIrkResolver.resolve(bytes, it) } ?: continue
            return ValidatedIrk(irkHex, matched)
        }
        return null
    }

    private fun commitValidatedIrk(address: String, irk: String, rpa: String): Boolean {
        val ok = updateTrackedDeviceIrk(address, irk)
        if (ok) {
            pendingIrkCandidates.remove(address)
            Log.i(
                TAG,
                "IRK validated against RPA $rpa for $address " +
                    "(fp=${BleIrkResolver.irkFingerprint(irk)})",
            )
        }
        return ok
    }

    private fun noteRecentRpa(address: String) {
        if (!BleIrkResolver.isResolvablePrivateAddress(address)) return
        val now = System.currentTimeMillis()
        synchronized(recentRpaLock) {
            recentRpaSeenAtMs[address] = now
            // Bound memory: drop entries older than 2 minutes, keep at most 64.
            val cutoff = now - 120_000L
            val stale = recentRpaSeenAtMs.entries.filter { it.value < cutoff }.map { it.key }
            stale.forEach { recentRpaSeenAtMs.remove(it) }
            while (recentRpaSeenAtMs.size > 64) {
                val oldest = recentRpaSeenAtMs.entries.firstOrNull()?.key ?: break
                recentRpaSeenAtMs.remove(oldest)
            }
        }
    }

    private fun snapshotRecentRpas(): Set<String> {
        val now = System.currentTimeMillis()
        val cutoff = now - 120_000L
        synchronized(recentRpaLock) {
            val stale = recentRpaSeenAtMs.entries.filter { it.value < cutoff }.map { it.key }
            stale.forEach { recentRpaSeenAtMs.remove(it) }
            return recentRpaSeenAtMs.keys.toSet()
        }
    }

    /** Try pending bond-store IRKs against a freshly seen RPA (also refines endianness). */
    private fun tryResolvePendingIrkWithRpa(rpa: String) {
        if (!BleIrkResolver.isResolvablePrivateAddress(rpa)) return
        if (pendingIrkCandidates.isEmpty()) return
        val pendingSnapshot = pendingIrkCandidates.entries.toList()
        for ((trackedAddress, candidates) in pendingSnapshot) {
            val device = _trackedDevices.value[trackedAddress]
            if (device == null) {
                pendingIrkCandidates.remove(trackedAddress)
                continue
            }
            val validated = selectValidatedIrk(candidates, listOf(rpa)) ?: continue
            if (device.irk.equals(validated.irk, ignoreCase = true)) {
                pendingIrkCandidates.remove(trackedAddress)
                continue
            }
            commitValidatedIrk(trackedAddress, validated.irk, validated.rpa)
        }
    }

    /**
     * Short presence-only probe scan to collect Resolvable Private Addresses.
     * Skips when another probe is already running (avoids SCAN_FAILED_ALREADY_STARTED).
     * Does not touch ESPHome proxy forwarding.
     */
    @SuppressLint("MissingPermission")
    private fun collectNearbyRpaAddresses(timeoutMs: Long): Set<String> {
        if (!hasScanPermissions() || !isBluetoothEnabled()) return emptySet()
        synchronized(irkProbeScanLock) {
            if (irkProbeScanActive) {
                Log.d(TAG, "IRK probe scan skipped (already active); using recent RPA cache")
                return snapshotRecentRpas()
            }
            irkProbeScanActive = true
        }
        val scanner = getScanner() ?: run {
            synchronized(irkProbeScanLock) { irkProbeScanActive = false }
            return emptySet()
        }
        val found = Collections.synchronizedSet(mutableSetOf<String>())
        found.addAll(snapshotRecentRpas())
        val done = CountDownLatch(1)
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val addr = result.device?.address ?: return
                if (BleIrkResolver.isResolvablePrivateAddress(addr)) {
                    found.add(addr)
                    noteRecentRpa(addr)
                }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "IRK probe scan failed: $errorCode")
                done.countDown()
            }
        }
        return try {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0)
                .build()
            scanner.startScan(null, settings, callback)
            done.await(timeoutMs, TimeUnit.MILLISECONDS)
            found.toSet()
        } catch (e: Exception) {
            Log.w(TAG, "collectNearbyRpaAddresses failed", e)
            found.toSet().ifEmpty { snapshotRecentRpas() }
        } finally {
            try {
                scanner.stopScan(callback)
            } catch (_: Exception) {
            }
            synchronized(irkProbeScanLock) { irkProbeScanActive = false }
        }
    }

    /** Retry IRK attach for tracked devices that still lack an IRK. */
    fun refreshMissingIrksFromBondStoreAsync() {
        if (!BleIrkBondStore.canReadBondStore()) {
            Log.d(TAG, "Skip IRK refresh (need root or root-Shizuku)")
            return
        }
        val missing = _trackedDevices.value.filter { (_, d) -> d.irk.isBlank() }.keys
        if (missing.isEmpty()) return
        missing.forEach { tryAttachIrkFromBondStoreAsync(it) }
    }
    
    
    fun updateDevicePresenceAlertSettings(address: String, alertSoundUri: String, alertTrigger: String) {
        if (address.isBlank()) return
        val current = _trackedDevices.value.toMutableMap()
        val device = current[address] ?: return
        current[address] = device.copy(
            alertSoundUri = alertSoundUri,
            alertTrigger = BluetoothPresenceAlertSound.normalizeTrigger(alertTrigger),
        )
        _trackedDevices.value = current
        saveTrackedDevices()
        Log.d(TAG, "Updated presence alert for $address: trigger=$alertTrigger uri=${alertSoundUri.take(48)}")
    }

    /**
     * Set or clear IRK for a tracked device (presence-only).
     * @return false if address unknown or [irkInput] is non-blank but invalid.
     */
    fun updateTrackedDeviceIrk(address: String, irkInput: String): Boolean {
        if (address.isBlank()) return false
        val current = _trackedDevices.value.toMutableMap()
        val device = current[address] ?: return false
        val trimmed = irkInput.trim()
        val normalized = if (trimmed.isEmpty()) {
            ""
        } else {
            val bytes = BleIrkResolver.parseIrk(trimmed) ?: return false
            BleIrkResolver.toNormalizedHex(bytes)
        }
        current[address] = device.copy(irk = normalized)
        _trackedDevices.value = current
        saveTrackedDevices()
        clearIrkResolutionCacheForTracked(address)
        if (normalized.isNotEmpty()) {
            pendingIrkCandidates.remove(address)
        }
        Log.d(
            TAG,
            "Updated IRK for $address: " +
                if (normalized.isEmpty()) "cleared"
                else "fp=${BleIrkResolver.irkFingerprint(normalized)}"
        )
        return true
    }

    fun removeTrackedDevice(address: String) {
        val currentDevices = _trackedDevices.value.toMutableMap()
        val removedDevice = currentDevices.remove(address)
        _trackedDevices.value = currentDevices
        saveTrackedDevices()
        clearPresenceAlertAddressIfMatches(address)
        clearIrkResolutionCacheForTracked(address)
        pendingIrkCandidates.remove(address)
        
        val currentPresence = _devicePresence.value.toMutableMap()
        currentPresence.remove(address)
        _devicePresence.value = currentPresence
        
        lastSeenTimestamp.remove(address)
        awayCandidateSince.remove(address)
        currentScanMaxRssi.remove(address)
        lastKnownRssi.remove(address)
        lastKnownRssiAtMs.remove(address)
        weakStreakSince.remove(address)
        flapStrikes.remove(address)
        lastAwayPublishedAt.remove(address)
        lastFlapStrikeAt.remove(address)
        lastPresencePublishAt.remove(address)
        lastScanHitAtMs.remove(address)
        lastScanHitRssi.remove(address)
        darkWakeAskedAt.remove(address)
        hitGapSamples.remove(address)
        worstGapMs.remove(address)
        worstGapAtMs.remove(address)
        cancelReachabilityProbe(address)
        
        Log.d(TAG, "Removed tracked device: ${removedDevice?.name ?: address}")
    }

    /**
     * Presence scan hit. Branch order is MAC-first; IRK is auxiliary:
     * 1) Exact MAC
     * 2) System-resolved identity on [device] (stack already has the bond IRK)
     * 3) Single-identity address-filter session (only the presence scanner)
     * 4) App-side IRK for rotating RPAs
     * Proxy forwarding must keep using the raw scan MAC; do not call this for rewrite.
     *
     * @param settleImmediately True for continuous proxy scans, which have no cycle end.
     * Every hit settles presence immediately either way; this only says whether the hit
     * also belongs to the duty-cycle max kept for [finalizeScanCycle]. Away still only
     * via [checkTimeouts] + [awayDelaySeconds].
     * @param addressFilterIdentities Identity MACs of *this* scan's address filters.
     * Never pass this from the unfiltered proxy — a lone tracked iPhone would steal
     * every nearby RPA.
     * @param addressFiltersOnly True only when every filter in this scan is an
     * address filter. Mixed manufacturer/service sessions must not steal RPAs.
     */
    fun onDeviceScanned(
        address: String,
        rssi: Int,
        settleImmediately: Boolean = false,
        device: BluetoothDevice? = null,
        addressFilterIdentities: Collection<String>? = null,
        addressFiltersOnly: Boolean = false,
    ) {
        if (address.isBlank()) return
        noteRecentRpa(address)
        tryResolvePendingIrkWithRpa(address)
        val trackedKey = resolveTrackedKey(
            address,
            device,
            addressFilterIdentities,
            addressFiltersOnly,
        ) ?: run {
            lastAmbientAdvertMs = System.currentTimeMillis()
            return
        }
        // On is immediate from every source. Holding a duty-cycle hit back until the cycle
        // ends delayed On by up to a full scan window — long enough for the away timer to
        // expire in between and report a departure that had already been disproved.
        settlePresenceHit(trackedKey, rssi)
        if (settleImmediately) return
        val currentMax = currentScanMaxRssi[trackedKey]
        if (currentMax == null || rssi > currentMax) {
            currentScanMaxRssi[trackedKey] = rssi
        }
    }

    /** Single write path for every presence source, so all of them age out alike. */
    private fun markPresent(trackedKey: String, now: Long) {
        if (_devicePresence.value[trackedKey] != true) {
            noteAwayDisprovedIfFast(trackedKey, now)
            seedUnprovenGapAllowance(trackedKey, now)
        }
        lastSeenTimestamp[trackedKey] = now
        weakStreakSince.remove(trackedKey)
        awayCandidateSince.remove(trackedKey)
        // Being heard is what re-arms the dark wake. Until then this device has already had its
        // one pulse for this absence.
        darkWakeAskedAt.remove(trackedKey)
        updateDevicePresence(trackedKey, true)
    }

    /**
     * The device answered sooner than the silence that condemned it, so the silence was
     * the radio and not the room — nobody walks out and returns inside the away delay.
     * Raise this device's bar so the next quiet spell has to outlast the one that just
     * lied. Capped by [MAX_FLAP_STRIKES] so a real departure is still reported.
     */
    private fun noteAwayDisprovedIfFast(trackedKey: String, now: Long) {
        val publishedAt = lastAwayPublishedAt[trackedKey] ?: return
        val gap = now - publishedAt
        if (gap >= awayDelayMillisFor(trackedKey, now)) return
        val strikes = flapStrikes[trackedKey] ?: 0
        if (strikes >= MAX_FLAP_STRIKES) return
        flapStrikes[trackedKey] = strikes + 1
        lastFlapStrikeAt[trackedKey] = now
        Log.i(
            TAG,
            "Away for ${nameFor(trackedKey)} disproved after ${gap}ms; " +
                "confirm gap now ${awayConfirmGapMillis(trackedKey)}ms",
        )
    }

    /**
     * A device that has not stalled yet has not proven it never will, and the measured bar
     * cannot protect against a stall it has never seen — the first one would still be
     * reported as a departure, and only afterwards would the bar rise to cover it. So the
     * bar starts wide and narrows: an arrival seeds the worst-gap envelope with an assumed
     * stall, which the existing decay walks back down to the user's setting over
     * [WORST_GAP_DECAY_MS] of uninterrupted presence.
     *
     * This is where the tolerance the user actually asked for lives. Right after a device is
     * added nothing is known about it, so it gets the benefit of the doubt; a device that has
     * then sat there behaving for half an hour has earned a departure reported as fast as the
     * setting allows. Ignorance costs latency, evidence buys it back, and a real stall
     * observed at any point re-widens the envelope on its own.
     */
    private fun seedUnprovenGapAllowance(trackedKey: String, now: Long) {
        if (decayedWorstGapMs(trackedKey, now) >= UNPROVEN_GAP_ALLOWANCE_MS) return
        worstGapMs[trackedKey] = UNPROVEN_GAP_ALLOWANCE_MS
        worstGapAtMs[trackedKey] = now
    }

    /** One strike back per uninterrupted [FLAP_STRIKE_DECAY_MS] of presence. */
    private fun decayFlapStrikes(address: String, now: Long) {
        val strikes = flapStrikes[address] ?: return
        val since = lastFlapStrikeAt[address] ?: return
        if (now - since < FLAP_STRIKE_DECAY_MS) return
        if (strikes <= 1) {
            flapStrikes.remove(address)
            lastFlapStrikeAt.remove(address)
        } else {
            flapStrikes[address] = strikes - 1
            lastFlapStrikeAt[address] = now
        }
        Log.d(TAG, "Flap penalty for ${nameFor(address)} decayed to ${flapStrikes[address] ?: 0}")
    }

    /**
     * An ACL with a tracked peer is proximity evidence in its own right: both BR/EDR and
     * LE top out around ten metres, so a live link outranks any advert we could have
     * missed. It feeds [markPresent] alongside the scan path rather than replacing it —
     * peers such as iPhones cycle the link on their own, and the away delay bridges the
     * gaps. A link carries no advert RSSI, so on its own it cannot be measured against
     * the threshold.
     *
     * But it must not outrank an advert we did *not* miss. This runs on every
     * [checkTimeouts] pass, and unconditionally it re-marked a linked device present
     * every few seconds — resetting the last-seen clock and the weak-hit streak — so for
     * any peer holding a link (a bonded phone, or a device HA keeps connected through
     * the BLE proxy) the threshold was simply inert: no silence could accumulate and no
     * streak could span its window. When a fresh advert has measured the device below
     * the user's boundary, that measurement wins; the link resumes carrying presence as
     * soon as the adverts stop saying otherwise, which keeps the original iPhone case —
     * linked but not advertising — exactly as it was.
     */
    private fun applyPresenceLinkSignal() {
        val devices = _trackedDevices.value.takeIf { it.isNotEmpty() } ?: return
        if (!hasBasicBluetoothPermissions() || !isBluetoothEnabled()) return
        val now = System.currentTimeMillis()
        devices.keys.forEach { trackedKey ->
            if (trackedKey.isNotBlank() &&
                !heardBelowThresholdRecently(trackedKey, now) &&
                isPresenceLinkUp(trackedKey)
            ) {
                markPresent(trackedKey, now)
            }
        }
    }

    /** Same rules as one device in [finalizeScanCycle]; does not touch other pending hits. */
    private fun settlePresenceHit(trackedKey: String, rssi: Int) {
        val now = System.currentTimeMillis()
        lastKnownRssi[trackedKey] = rssi
        lastKnownRssiAtMs[trackedKey] = now
        // Any tracked advert proves the receiver, including one below the threshold: weak
        // is a statement about the device's distance, deaf would be one about the radio,
        // and the deaf-receiver gates must not engage on a radio that is demonstrably
        // hearing. That is exactly what a tightened threshold used to do — every hit
        // dropped below the bar, none of them counted as liveness, and the gates read a
        // working receiver as a dead one. Presence itself is still only refreshed by hits
        // inside the threshold.
        noteReceiverHeard()
        if (rssi >= rssiThreshold) {
            noteScanHitGap(trackedKey, rssi, now)
            markPresent(trackedKey, now)
        } else {
            weakStreakSince.getOrPut(trackedKey) { now }
        }
    }

    /**
     * Measures how irregularly this device actually advertises, which is the number the
     * away delay has to clear. Sampled here rather than in [markPresent] on purpose: an
     * ACL-derived mark repeats on the watchdog's cadence and a presence refresh writes
     * "now", so both would report the tick interval instead of the device's own rhythm.
     *
     * Gaps beyond [GAP_SAMPLE_CAP_MS] are absences, not rhythm, and are dropped — that
     * boundary is what keeps a genuine departure from teaching the device it is allowed to
     * vanish for that long. Shorter real absences would slip under that boundary, so signal
     * strength settles those: someone who walks out and back crosses the edge of range in
     * both directions and is heard faintly on the way through, while a radio that stalls
     * resumes at the strength it stopped at. Only gaps bracketed by strong hits are learned,
     * which also keeps the bar from being taught by the marginal adverts that a device at
     * the far edge of range produces all day.
     */
    private fun noteScanHitGap(trackedKey: String, rssi: Int, now: Long) {
        val previousRssi = lastScanHitRssi.put(trackedKey, rssi)
        val previous = lastScanHitAtMs.put(trackedKey, now) ?: return
        val gap = now - previous
        if (gap <= 0L || gap > GAP_SAMPLE_CAP_MS) return

        val strongFloor = rssiThreshold + GAP_LEARN_RSSI_MARGIN_DB
        if (rssi < strongFloor || (previousRssi ?: Int.MIN_VALUE) < strongFloor) return

        val samples = hitGapSamples.getOrPut(trackedKey) { ArrayDeque() }
        samples.addLast(gap)
        while (samples.size > GAP_HISTORY_SIZE) samples.removeFirst()

        if (gap > decayedWorstGapMs(trackedKey, now)) {
            worstGapMs[trackedKey] = gap
            worstGapAtMs[trackedKey] = now
            Log.i(
                TAG,
                "Advert rhythm for ${nameFor(trackedKey)}: worst gap now ${gap}ms, " +
                    "away bar ${awayDelayMillisFor(trackedKey, now)}ms " +
                    "(${gapDistributionSummary(trackedKey)})",
            )
        }
    }

    /** p50/p95/max over the recent sample ring; the noise floor an away bar must clear. */
    private fun gapDistributionSummary(trackedKey: String): String {
        val samples = hitGapSamples[trackedKey]?.sorted() ?: return "no samples"
        if (samples.isEmpty()) return "no samples"
        fun at(fraction: Double): Long =
            samples[((samples.size - 1) * fraction).toInt().coerceIn(0, samples.size - 1)]
        return "n=${samples.size} p50=${at(0.5)}ms p95=${at(0.95)}ms max=${samples.last()}ms"
    }

    /**
     * The worst gap fades so one outlier cannot hold the bar up for the rest of the
     * session, while still outliving the sample ring — a device that stalls once every few
     * minutes has to stay covered between stalls, which a fixed-size ring of gaps arriving
     * every second or two cannot do.
     */
    private fun decayedWorstGapMs(trackedKey: String, now: Long): Long {
        val worst = worstGapMs[trackedKey] ?: return 0L
        val recordedAt = worstGapAtMs[trackedKey] ?: return worst
        val age = now - recordedAt
        if (age <= 0L) return worst
        if (age >= WORST_GAP_DECAY_MS) return 0L
        return worst - worst * age / WORST_GAP_DECAY_MS
    }

    /**
     * True when a scanned advert attributes to a tracked device. Use this (never a
     * raw address compare) whenever "is this advert from a tracked device?" must
     * match presence attribution. Proxy callers must not pass [addressFilterIdentities].
     */
    fun isTrackedAdvert(
        scanAddress: String,
        device: BluetoothDevice? = null,
        addressFilterIdentities: Collection<String>? = null,
        addressFiltersOnly: Boolean = false,
    ): Boolean = resolveTrackedKey(
        scanAddress,
        device,
        addressFilterIdentities,
        addressFiltersOnly,
    ) != null

    /**
     * Map a scanned address to a stable tracked-device key.
     * Exact / system identity always win; app-side IRK is the last fallback.
     */
    private fun resolveTrackedKey(
        scanAddress: String,
        scanDevice: BluetoothDevice? = null,
        addressFilterIdentities: Collection<String>? = null,
        addressFiltersOnly: Boolean = false,
    ): String? {
        val tracked = _trackedDevices.value
        if (tracked.isEmpty()) return null

        findTrackedKey(tracked, scanAddress)?.let { return it }

        findTrackedKey(tracked, systemIdentityAddress(scanDevice))?.let { identity ->
            rememberScanMapping(scanAddress, identity)
            Log.d(TAG, "System identity $scanAddress → $identity")
            return identity
        }

        synchronized(irkMacCacheLock) {
            macToTrackedAddressCache[scanAddress]?.let { cached ->
                findTrackedKey(tracked, cached)?.let { return it }
                macToTrackedAddressCache.remove(scanAddress)
            }
        }

        // Count only paired, no-IRK identities. A fixed-MAC tracker (AInice) in the
        // same filter list must not block attributing an RPA to the one iPhone.
        val rotatingInSession = addressFilterIdentities
            ?.mapNotNull { findTrackedKey(tracked, it) }
            ?.distinct()
            ?.filter { isRotatingBondedIdentity(tracked, it) }
            .orEmpty()
        if (addressFiltersOnly &&
            rotatingInSession.size == 1 &&
            BleIrkResolver.isResolvablePrivateAddress(scanAddress)
        ) {
            val identity = rotatingInSession.single()
            rememberScanMapping(scanAddress, identity)
            Log.i(TAG, "Address-filter session $scanAddress → $identity")
            return identity
        }

        if (!hasAnyTrackedIrk()) return null
        if (!BleIrkResolver.isResolvablePrivateAddress(scanAddress)) return null

        for ((trackedAddress, device) in tracked) {
            if (device.irk.isBlank()) continue
            val irkBytes = BleIrkResolver.parseIrk(device.irk) ?: continue
            if (!BleIrkResolver.resolve(irkBytes, scanAddress)) continue
            rememberScanMapping(scanAddress, trackedAddress)
            Log.d(TAG, "IRK resolved $scanAddress → $trackedAddress")
            return trackedAddress
        }
        return null
    }

    /**
     * Bonded peers without an app-side IRK advertise rotating RPAs. Fixed-MAC
     * trackers (unbonded beacons) are excluded so they do not inflate the
     * address-filter session.
     */
    private fun isRotatingBondedIdentity(
        tracked: Map<String, TrackedDevice>,
        key: String,
    ): Boolean {
        val device = tracked[key] ?: return false
        if (device.irk.isNotBlank()) return false
        return device.bonded || isAddressBonded(key)
    }

    /**
     * Address filters that can use the stack resolving list. Empty means "all
     * tracked keys" so AInice-only installs keep the old presence scan.
     */
    fun presenceResolvingFilterAddresses(): Set<String> {
        val tracked = _trackedDevices.value
        return tracked.keys.filter { isRotatingBondedIdentity(tracked, it) }.toSet()
    }

    private fun findTrackedKey(tracked: Map<String, TrackedDevice>, address: String?): String? {
        if (address.isNullOrBlank()) return null
        if (tracked.containsKey(address)) return address
        return tracked.keys.firstOrNull { it.equals(address, ignoreCase = true) }
    }

    private fun rememberScanMapping(scanAddress: String, trackedAddress: String) {
        if (scanAddress.equals(trackedAddress, ignoreCase = true)) return
        synchronized(irkMacCacheLock) {
            macToTrackedAddressCache.entries.removeAll {
                it.value.equals(trackedAddress, ignoreCase = true)
            }
            macToTrackedAddressCache[scanAddress] = trackedAddress
        }
    }

    /** Stack-resolved identity for a bonded peer. Missing on many ROMs; never required. */
    private fun systemIdentityAddress(device: BluetoothDevice?): String? {
        if (device == null) return null
        val method = synchronized(identityAddressMethodLock) {
            if (identityAddressMethodResolved) {
                identityAddressMethod
            } else {
                identityAddressMethodResolved = true
                identityAddressMethod = runCatching {
                    BluetoothDevice::class.java.getMethod("getIdentityAddress")
                }.getOrNull()
                identityAddressMethod
            }
        } ?: return null
        return runCatching {
            (method.invoke(device) as? String)
                ?.takeIf { it.isNotBlank() && !it.equals("00:00:00:00:00:00", ignoreCase = true) }
        }.onFailure {
            synchronized(identityAddressMethodLock) {
                identityAddressMethod = null
            }
        }.getOrNull()
    }

    private fun clearIrkResolutionCacheForTracked(trackedAddress: String) {
        synchronized(irkMacCacheLock) {
            macToTrackedAddressCache.entries.removeAll {
                it.value.equals(trackedAddress, ignoreCase = true)
            }
        }
    }

    fun finalizeScanCycle() {
        if (currentScanMaxRssi.isEmpty()) return
        val now = System.currentTimeMillis()
        currentScanMaxRssi.forEach { (address, maxRssi) ->

            lastKnownRssi[address] = maxRssi
            lastKnownRssiAtMs[address] = now
            
            if (maxRssi >= rssiThreshold) {
                markPresent(address, now)
            }
        }
        currentScanMaxRssi.clear()

    }
    
    fun resetScanCycle() {
        currentScanMaxRssi.clear()
    }
    
    @Volatile
    private var awayTimeoutsSuppressedUntil = 0L

    /**
     * Hold off away decisions briefly, e.g. right after screen-on: the away timer may
     * expire in the exact instant the scan strategy switches, flipping Off one second
     * before the fresh scan lands a hit and flips back On (visible flicker in HA).
     * Present→present hits are unaffected; a real departure is reported after the grace.
     */
    fun suppressAwayTimeouts(durationMs: Long) {
        val until = System.currentTimeMillis() + durationMs
        if (until > awayTimeoutsSuppressedUntil) {
            awayTimeoutsSuppressedUntil = until
        }
    }

    @Volatile
    private var lastScanCoverageMs = 0L

    /**
     * Last advert from a device we do not track — one of the two reference signals for
     * [hasAmbientLiveness], alongside [lastTrackedScanHitMs]. Kept separate because it is
     * the only one that survives a tracked device leaving: the untracked room noise keeps
     * proving the receiver after every tracked device has gone quiet for real.
     */
    @Volatile
    private var lastAmbientAdvertMs = 0L

    /** Edge-trigger for the hold log, so the gate does not narrate every scan cycle. */
    @Volatile
    private var ambientHoldActive = false

    /**
     * Last tracked advert through the scanner, at any strength — unlike [lastScanHitAtMs],
     * which only records hits inside the threshold, because it feeds the away bar. A single
     * volatile because the away pipeline needs "did the receiver hear anything" from a
     * different thread than the scan callback that writes it.
     */
    @Volatile
    private var lastTrackedScanHitMs = 0L

    /**
     * Anything at all through the scanner since [since]. An untracked advert counts: a pulse opens
     * an unfiltered window, and in one of those a working radio hears the room whether or not the
     * device we care about is in it. Total silence across such a window is a receiver that was
     * down, not an empty room.
     */
    private fun receiverHeardAnythingSince(since: Long): Boolean =
        lastAmbientAdvertMs > since || lastTrackedScanHitMs > since

    /**
     * A scan that really listened just finished: a duty cycle, an HCI window, or a proxy
     * session with the hardware scan registered. Only the scan layer knows this, and away
     * decisions depend on it.
     */
    fun noteScanCoverage() {
        lastScanCoverageMs = System.currentTimeMillis()
    }

    /**
     * Silence is a departure only if someone was listening for it. A skipped duty cycle,
     * a screen-off scan the platform suppresses, a null scanner or a stalled proxy all
     * produce the same "no hits" as leaving the room, and the away timer used to fire on
     * those blind windows — then the next advert flipped presence straight back On, which
     * is the Off/On flicker in HA. Presence is held while blind; a device that really left
     * is reported one away delay after coverage returns.
     *
     * The window spans a whole compatibility duty cycle (5s scan + 25s rest) plus margin.
     */
    private fun canReportAway(now: Long): Boolean {
        if (lastScanCoverageMs <= 0L) return false
        if (now - lastScanCoverageMs > SCAN_COVERAGE_STALE_MS) return false
        return hasAmbientLiveness(now)
    }

    /**
     * A window that closed proves someone listened. It does not prove the receiver heard
     * anything, and that is the gap [canReportAway] alone could not see: on a combo chip
     * the radio can be starved by Wi-Fi and hand back an empty window that is byte for
     * byte what an empty room looks like. An advert from any device we do not track is
     * independent proof the receiver still works, so silence about a tracked device is
     * about the device rather than the hardware.
     *
     * Tracked adverts count too, via [lastTrackedScanHitMs], which since it records hits at
     * any strength is what keeps this gate honest on a screen-off session filtered down to
     * tracked addresses: there ambient is silenced by the filter itself, but a tracked
     * device still being heard — even below the threshold — is the same proof of a working
     * receiver. The old worry about self-proof does not apply: a hit inside the threshold
     * refreshes presence directly so no away decision is pending on it, and a hit below the
     * threshold is precisely the evidence that the silence is about range, not the radio.
     *
     * Fails open, deliberately, because the reference signal can go quiet innocently: a
     * tracked device may be the only advertiser in range, and then its departure silences
     * ambient too. Gating on that forever would pin it "present" permanently, which in an
     * automation is worse than a late departure. After [ambientHoldCapMs] the gate stops
     * arguing and lets the away pipeline decide on its own evidence.
     */
    private fun hasAmbientLiveness(now: Long): Boolean {
        val lastHeardMs = maxOf(lastAmbientAdvertMs, lastTrackedScanHitMs)
        val silenceMs = now - lastHeardMs
        val hearing = lastHeardMs > 0L && silenceMs <= AMBIENT_STALE_MS
        val conceded = silenceMs > ambientHoldCapMs()
        val holding = !hearing && !conceded
        if (holding != ambientHoldActive) {
            ambientHoldActive = holding
            Log.i(
                TAG,
                if (holding) "Holding away decisions: nothing heard for ${silenceMs}ms, " +
                    "receiver may be deaf"
                else "Away decisions resumed (ambient silence ${silenceMs}ms)",
            )
        }
        return !holding
    }

    /** Bounded hold: three away delays of apparent deafness, then the gate concedes. */
    private fun ambientHoldCapMs(): Long = awayDelaySeconds * 1000L * AMBIENT_HOLD_AWAY_DELAYS

    /**
     * Start of the current episode in which no tracked device is being heard, or 0 when at
     * least one still is. Receiver-wide rather than per-device, because it describes the radio.
     */
    @Volatile
    private var peerDeafSince = 0L

    /** Set once [PEER_DEAF_HOLD_CAP_MS] has elapsed, so the episode stops asking for rotations. */
    @Volatile
    private var peerDeafConceded = false

    /**
     * True while a departure is being held back because every tracked device went silent at
     * once. The scan layer owns the only remedy — rotating the scan session — and drives this
     * class rather than being called back into, so it polls this instead.
     */
    fun isReceiverSuspect(): Boolean = peerDeafSince > 0L && !peerDeafConceded

    /**
     * A tracked device just came through the scanner — at any strength, since a weak hit
     * proves the radio exactly as well as a strong one. Ends the episode — and has to,
     * because the away pipeline is the only other place that touches this state and it does
     * not run while everything is present: an episode that concedes and is never re-armed
     * would leave the gate disarmed for the rest of the run.
     */
    private fun noteReceiverHeard() {
        lastTrackedScanHitMs = System.currentTimeMillis()
        if (peerDeafSince == 0L) return
        Log.i(TAG, "Receiver proven working again; away decisions resumed")
        peerDeafSince = 0L
        peerDeafConceded = false
    }

    /**
     * The check [hasAmbientLiveness] cannot make while the display is off. Its reference signal
     * is untracked adverts, and the screen-off session is filtered down to tracked addresses, so
     * ambient goes quiet for an entirely innocent reason and the gate fails open by design —
     * precisely when a suppressed or wedged scanner is most likely.
     *
     * Another tracked device is the one reference that survives that filter, because the same
     * filtered session is what carries it. So when a device's silence is about to be believed,
     * ask what happened to the others: if one of them is still being heard, the receiver
     * demonstrably works and this silence is about the device. If every device that was being
     * heard alongside it stopped at the same moment, the common factor is the radio, and the
     * departure is held while [isReceiverSuspect] asks the scan layer to rebuild the session.
     *
     * Only a device the scanner was still hearing when this one went quiet counts as a witness.
     * One that had already been silent for a while — present over an ACL link, or on its way
     * out — corroborates nothing, and counting it would hold every departure for the full cap.
     *
     * Bounded, like the ambient gate: everyone can genuinely leave together, and a permanent
     * hold is worse in an automation than a late departure.
     */
    private fun receiverProvenByPeer(address: String, now: Long): Boolean {
        // A tracked advert this fresh — from any device, at any strength — proves the
        // receiver outright, so the witness arithmetic below is moot. It also must not
        // run: that arithmetic reasons from above-threshold hit times, which weak hits
        // never refresh, so a weakly heard peer would keep reopening an episode that its
        // own hits keep clearing through noteReceiverHeard, resetting the hold cap
        // forever and pinning this device present.
        if (lastTrackedScanHitMs > 0L && now - lastTrackedScanHitMs <= AMBIENT_STALE_MS) {
            return true
        }
        val subjectHeardAt = lastScanHitAtMs[address] ?: lastSeenTimestamp[address] ?: 0L
        val presence = _devicePresence.value
        var witnesses = 0
        var vouched = false
        _trackedDevices.value.keys.forEach { peer ->
            if (peer == address || presence[peer] != true) return@forEach
            val heardAt = lastScanHitAtMs[peer] ?: return@forEach
            val peerBar = awayDelayMillisFor(peer, now)
            if (heardAt < subjectHeardAt - peerBar) return@forEach
            witnesses++
            if (now - heardAt <= peerBar) vouched = true
        }

        if (vouched) return true
        // No witness for this device, which is an absence of evidence rather than evidence the
        // receiver works. It must not clear an episode another device opened on better evidence:
        // devices stop being heard at slightly different moments, and a witness that qualifies
        // for one and not the other would otherwise reset the clock every pass and hold forever.
        if (witnesses == 0) return true

        if (peerDeafSince == 0L) {
            peerDeafSince = now
            peerDeafConceded = false
            Log.i(
                TAG,
                "Holding ${nameFor(address)} away: $witnesses other tracked device(s) went " +
                    "silent alongside it, so the receiver is the suspect",
            )
            cancelReachabilityProbe(address)
            return false
        }
        val heldMs = now - peerDeafSince
        if (heldMs <= PEER_DEAF_HOLD_CAP_MS) {
            cancelReachabilityProbe(address)
            return false
        }
        if (!peerDeafConceded) {
            peerDeafConceded = true
            Log.i(
                TAG,
                "Nothing heard from any tracked device in ${heldMs}ms of rebuilt scan sessions; " +
                    "treating the silence as real",
            )
        }
        return true
    }

    /**
     * A hit this fresh, at a strength the user's threshold rejects, is positive evidence:
     * the receiver works and the device is out of range by the configured rule. Silence
     * could mean an empty room or a dead radio; this is neither. Bounded by
     * [AMBIENT_STALE_MS] so a weak advert from the edge of a genuine departure cannot
     * masquerade as a live observation once the device is truly gone.
     */
    private fun heardBelowThresholdRecently(address: String, now: Long): Boolean {
        val at = lastKnownRssiAtMs[address] ?: return false
        if (now - at > AMBIENT_STALE_MS) return false
        val rssi = lastKnownRssi[address] ?: return false
        return rssi < rssiThreshold
    }

    /**
     * An *observed* departure: at least two hits below the current threshold spanning
     * [observedAwayStreakMs], the newest one fresh, and no qualifying hit in between —
     * one would have gone through [markPresent] and cleared the streak. This is the case
     * the silence budgets were never written for: they wait out ambiguity between a quiet
     * radio and an empty room, and a device the scanner keeps hearing, just too faintly
     * for the user's rule, carries no such ambiguity. Freshness and the RSSI comparison
     * are both judged now, against the current threshold, so neither an old streak nor an
     * old threshold can decide anything on its own.
     */
    private fun isObservedAway(address: String, now: Long): Boolean {
        if (!heardBelowThresholdRecently(address, now)) return false
        val since = weakStreakSince[address] ?: return false
        val newestAt = lastKnownRssiAtMs[address] ?: return false
        return newestAt > since && now - since >= observedAwayStreakMs()
    }

    private fun observedAwayStreakMs(): Long =
        scanIntervalSeconds * 1000L * OBSERVED_AWAY_STREAK_CYCLES

    fun checkTimeouts() {
        applyPresenceLinkSignal()
        val now = System.currentTimeMillis()
        if (now < awayTimeoutsSuppressedUntil) return
        if (!canReportAway(now)) return
        val devices = _trackedDevices.value.takeIf { it.isNotEmpty() } ?: return
        val presence = _devicePresence.value
        
        devices.keys
            .filter { it.isNotBlank() && presence[it] == true }
            .forEach { address ->
                val lastSeen = lastSeenTimestamp[address]
                    ?.takeIf { it > 0 } ?: return@forEach
                
                val elapsed = now - lastSeen

                // The away bar is a silence budget, and an observed departure spends no
                // silence: the device is being heard on every cycle, below the user's
                // rule, so the streak plus the confirmation below is the whole wait.
                // Genuine silence still pays the full bar.
                val observedAway = isObservedAway(address, now)

                if (!observedAway && elapsed <= awayDelayMillisFor(address, now)) {
                    awayCandidateSince.remove(address)
                    decayFlapStrikes(address, now)
                    cancelReachabilityProbe(address)
                    return@forEach
                }

                // Two independent looks before reporting a departure. One expired timer can
                // be a single lost duty cycle or an RPA this scan failed to resolve, and the
                // device is usually back within the next cycle — flipping on the first look
                // is what made HA show Off/On pairs. Any hit in between clears the candidate
                // via markPresent, and the second look re-runs the ACL check as well.
                val candidateSince = awayCandidateSince[address]
                if (candidateSince == null) {
                    awayCandidateSince[address] = now
                    Log.d(TAG, "Away candidate ${nameFor(address)} (${elapsed}ms), confirming")
                    return@forEach
                }
                if (now - candidateSince < awayConfirmGapMillis(address)) return@forEach

                // A device heard below the threshold is not a silent device. The advert is
                // the observation itself: the receiver demonstrably works, and the user's
                // rule says this strength is "not present". Every step below exists to
                // disprove a silence that could equally be a dead radio — a question a
                // fresh weak hit has already answered — and the probe would answer with
                // GATT reach, which extends far beyond any RSSI boundary the user could
                // set, flipping the device straight back On. The away delay and the
                // two-look confirmation above still stand, so a single weak advert from a
                // device sitting right there can never publish a departure on its own.
                if (!heardBelowThresholdRecently(address, now)) {
                    // Last steps are questions, not more waiting. First the cheapest one,
                    // and the only one that stays available with the display off: did
                    // anything else we were hearing go quiet at the same moment? Asked
                    // before the probe and the pulse because both of those spend radio
                    // time, and on a suspect radio both answer "gone" for the same wrong
                    // reason.
                    if (!receiverProvenByPeer(address, now)) return@forEach

                    // Then ask the device directly, and failing that, check we are still
                    // able to hear at all before reading any more meaning into the same
                    // silence.
                    if (!reachabilityProbeSettled(address, now)) return@forEach
                    if (!darkWakeSettled(address, now)) return@forEach
                }

                awayCandidateSince.remove(address)
                // Close, not just forget: a probe from an earlier pass may still be in
                // flight when the weak-hit path publishes, and left open it would answer
                // later and overturn a departure the adverts justified.
                cancelReachabilityProbe(address)
                // The publish floor is sized to the evidence this Off actually stands on,
                // so an observed departure is not withheld by a silence bar it never used.
                val offEvidenceMs = if (observedAway) {
                    observedAwayStreakMs() + awayConfirmGapMillis(address)
                } else {
                    awayDelayMillisFor(address, now) + awayConfirmGapMillis(address)
                }
                updateDevicePresence(address, false, offEvidenceMs)
            }
    }

    /**
     * The confirmation has to land in a later scan cycle, not the same one, and every
     * flap strike buys one more cycle of silence before a departure is believed.
     */
    private fun awayConfirmGapMillis(address: String): Long =
        scanIntervalSeconds * 1000L * (1 + (flapStrikes[address] ?: 0))

    /**
     * The silence this device has to produce before a departure is even considered: the
     * user's away delay, or the measured worst gap plus [GAP_MARGIN_PERCENT] headroom,
     * whichever is longer.
     *
     * This is the difference between tuning and arithmetic. A device seen going quiet for
     * 17s while sitting still cannot be judged on a 13s budget by any amount of extra
     * confirmation — the bar is simply below its own noise floor. Raising the bar to clear
     * the measured floor costs a well-behaved device nothing, because a beacon that
     * advertises on a steady rhythm never learns a large gap and stays on the user's
     * setting, so its departures are still reported as fast as the setting allows. Only a
     * device that has demonstrated it cannot be measured faster pays for it, and only up to
     * [GAP_SAMPLE_CAP_MS] of learned delay.
     */
    private fun awayDelayMillisFor(address: String, now: Long): Long {
        val configured = awayDelaySeconds * 1000L
        val worst = decayedWorstGapMs(address, now)
        if (worst <= 0L) return configured
        val learned = (worst * GAP_MARGIN_PERCENT / 100L).coerceAtMost(GAP_SAMPLE_CAP_MS)
        return maxOf(configured, learned)
    }

    private fun nameFor(address: String): String =
        _trackedDevices.value[address]?.name?.takeIf { it.isNotBlank() } ?: address

    /**
     * Turns the last step of the away pipeline from "we still hear nothing" into "we asked
     * and nobody answered". Every layer before this reasons about absent evidence, which is
     * exactly what a stalled radio and an empty room have in common; a connection attempt is
     * evidence in its own right, and a device in the room answers one in about a second
     * regardless of whether it happened to be advertising.
     *
     * @return true when the departure may be published: either the device was asked and
     * stayed silent, or no probe was available and silence is all there is to go on. A
     * successful probe does not return here — it refreshes presence through [markPresent],
     * which clears the candidate and ends the pipeline.
     */
    private fun reachabilityProbeSettled(address: String, now: Long): Boolean {
        if (!canProbeReachability(address)) return true
        val startedAt = reachabilityProbeStartedAt[address]
        if (startedAt == null) {
            startReachabilityProbe(address, now)
            return false
        }
        if (now - startedAt < REACHABILITY_PROBE_TIMEOUT_MS) return false
        closeProbeGatt(address)
        Log.i(TAG, "Reachability probe for ${nameFor(address)} went unanswered")
        return true
    }

    /**
     * Only bonded devices, because the probe needs a connectable address: an unbonded
     * tracker may not accept connections at all, and a rotating RPA is not reachable at the
     * identity MAC presence is keyed on.
     *
     * Skipped on shared-radio chips. There a GATT connect contends with the single scanner
     * the proxy owns and with Wi-Fi on the same antenna, so the probe would risk the
     * satellite's link to buy confidence about one departure. Those devices rely on the
     * measured away bar instead, which needs no radio time.
     */
    @SuppressLint("MissingPermission")
    private fun canProbeReachability(address: String): Boolean {
        if (!hasBasicBluetoothPermissions()) return false
        if (!isBluetoothEnabled()) return false
        if (isLowEndBleChip()) return false
        val device = runCatching { bluetoothAdapter?.getRemoteDevice(address) }.getOrNull()
            ?: return false
        return runCatching { device.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    private fun startReachabilityProbe(address: String, now: Long) {
        reachabilityProbeStartedAt[address] = now
        val device = runCatching { bluetoothAdapter?.getRemoteDevice(address) }.getOrNull() ?: return
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.i(TAG, "Reachability probe answered by ${nameFor(address)}")
                    markPresent(address, System.currentTimeMillis())
                    closeProbeGatt(address)
                }
            }
        }
        val gatt = runCatching { device.connectGatt(context, false, callback) }.getOrNull()
        if (gatt == null) {
            Log.w(TAG, "Reachability probe for ${nameFor(address)} could not start")
            return
        }
        probeGatt.put(address, gatt)?.let { stale ->
            runCatching { stale.close() }
        }
        Log.d(TAG, "Probing ${nameFor(address)} before publishing away")
    }

    private fun cancelReachabilityProbe(address: String) {
        if (reachabilityProbeStartedAt.remove(address) == null) return
        closeProbeGatt(address)
    }

    private fun closeProbeGatt(address: String) {
        val gatt = probeGatt.remove(address) ?: return
        runCatching {
            gatt.disconnect()
            gatt.close()
        }
    }

    /**
     * Silence has now outlasted the device's own measured rhythm, its confirmation gap and, where
     * one was available, a direct question to the device. Everything left says it is gone — but
     * every one of those steps read the same source, and if that source stopped working they all
     * agree for the same wrong reason.
     *
     * Which is a real possibility here rather than a hypothetical. Some ROMs power the scanner
     * down after a long screen-off, and while the display is off most of the checks that would
     * notice are unavailable: [hasAmbientLiveness] is fed only by untracked adverts and the
     * screen-off scan is filtered down to tracked ones, so it goes quiet for an entirely innocent
     * reason and fails open by design; the proxy scan watchdog stands down whenever the display
     * is off, because sparse delivery is normal there; and [canProbeReachability] declines on the
     * shared-radio chips these ROMs ship on. [receiverProvenByPeer] covers the case where another
     * tracked device is being heard, and runs before this so a pulse is never spent on a radio
     * already known to be deaf — but with one device tracked, or with all of them silent, a
     * suppressed scanner and an empty room are byte for byte identical.
     *
     * So the last step is to briefly get the scanner back — behind a black overlay, at a moment
     * chosen by the symptom rather than a clock — and see whether the device was there all along.
     * If it reappears, [markPresent] clears the candidate and this never returns. If it stays
     * silent through a scanner known to be working, the silence has finally been earned.
     *
     * At most one pulse per absence: [darkWakeAskedAt] survives the departure being published and
     * is cleared only by [markPresent], so a device that really left is not pulsed for again
     * until it comes back.
     *
     * A window in which nothing whatsoever came through tested nothing, and on these chips the
     * rebuilds either side of a pulse can themselves wedge the scanner — so that is not a
     * hypothetical either. Rather than spend a second pulse on it, which would only repeat the
     * cause, the verdict waits one more window for the scan layer's own session rotation to
     * restore hearing. Whatever that window says is then taken, so the wait stays bounded.
     *
     * @return true when the departure may be published — either the pulse ran and changed
     * nothing, or no pulse was available and silence is all there is to go on.
     */
    private fun darkWakeSettled(address: String, now: Long): Boolean {
        val askedAt = darkWakeAskedAt[address]
        if (askedAt == null) {
            if (!DarkWakePulse.requestPulse()) return true
            darkWakeAskedAt[address] = now
            Log.i(TAG, "Waking the display dark before reporting ${nameFor(address)} away")
            return false
        }
        val elapsed = now - askedAt
        if (elapsed < DarkWakePulse.SETTLE_MS) return false
        if (!receiverHeardAnythingSince(askedAt)) {
            if (elapsed < DarkWakePulse.SETTLE_MS * DARK_WAKE_DEAF_WINDOW_GRACE) {
                Log.d(
                    TAG,
                    "Dark wake pulse for ${nameFor(address)} heard nothing at all; " +
                        "waiting for the scanner before believing it",
                )
                return false
            }
            Log.i(
                TAG,
                "Scanner still silent ${elapsed}ms after the pulse for ${nameFor(address)}; " +
                    "taking the silence at face value",
            )
        }
        Log.i(TAG, "${nameFor(address)} stayed silent through a dark wake pulse; away confirmed")
        return true
    }

    /**
     * An arrival is published the moment it is observed, but a departure has to sit on top
     * of presence that stood at least as long as the evidence needed to overturn it —
     * one away bar plus its confirmation gap. Subscribers therefore never receive an On/Off
     * pair closer together than that, which is the shape Home Assistant showed.
     *
     * Deriving the floor rather than fixing it is what makes this safe at any setting. A
     * fixed floor is either useless at a long away delay or, at the 7s minimum, longer than
     * the whole away pipeline, which would delay departures that were correctly measured.
     * Expressed this way it can only ever reject an Off that arrived sooner than its own
     * evidence allows, so a legitimate departure is never held: the silence that justifies
     * it already outlasts the floor by construction.
     *
     * Rate-limiting only this direction is the point: holding an arrival back would instead
     * stretch a wrong departure, so On stays immediate and keeps correcting Off as fast as
     * the radio allows. Nothing is lost when it holds — arrivals re-arrive with the next
     * advert, and [checkTimeouts] re-runs the confirmation for a departure still due.
     *
     * [requiredMs] is the bar the pending Off actually stood on — the silence bar plus its
     * confirmation, or the much shorter weak-hit streak for an observed departure — passed
     * in rather than derived here so the floor always matches the evidence.
     */
    private fun hasHeldPresenceLongEnough(address: String, now: Long, requiredMs: Long): Boolean {
        val lastPublish = lastPresencePublishAt[address] ?: return true
        val held = now - lastPublish
        if (held >= requiredMs) return true
        Log.d(
            TAG,
            "Withholding away for ${nameFor(address)}: presence held ${held}ms of ${requiredMs}ms",
        )
        return false
    }

    
    /**
     * Private on purpose. On may be published from any evidence, but [checkTimeouts] is
     * the only caller allowed to publish Off, because it is the only one that weighs the
     * away delay, scan coverage and the confirmation gap. Config writes and UI edits used
     * to reach a departure through here without any of that.
     *
     * @param offEvidenceMs How long the evidence behind an Off had to accumulate; sizes
     * the publish floor in [hasHeldPresenceLongEnough]. Null falls back to the full
     * silence bar, so a caller that forgets it can only ever be too conservative.
     */
    private fun updateDevicePresence(
        address: String,
        isPresent: Boolean,
        offEvidenceMs: Long? = null,
    ) {
        if (address.isBlank()) return
        if (!_trackedDevices.value.containsKey(address)) return
        
        val currentState = _devicePresence.value[address]
        if (currentState == isPresent) return

        val now = System.currentTimeMillis()
        if (!isPresent) {
            val requiredMs = offEvidenceMs
                ?: (awayDelayMillisFor(address, now) + awayConfirmGapMillis(address))
            if (!hasHeldPresenceLongEnough(address, now, requiredMs)) return
        }

        lastPresencePublishAt[address] = now
        if (!isPresent) lastAwayPublishedAt[address] = now

        val newMap = _devicePresence.value.toMutableMap()
        newMap[address] = isPresent
        _devicePresence.value = newMap
        if (!isPresent) awayCandidateSince.remove(address)
        
        val deviceName = nameFor(address)
        Log.i(TAG, "Device $deviceName: $currentState -> $isPresent")

        val alertAddress = _presenceAlertAddress.value
        if (alertAddress != null && alertAddress == address) {
            presenceAlertCallback?.onPresenceChanged(address, currentState ?: false, isPresent, deviceName)
        }
    }
    
    
    fun getScanner(): android.bluetooth.le.BluetoothLeScanner? {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            Log.w(TAG, "getScanner: bluetoothAdapter is null")
            return null
        }
        if (!adapter.isEnabled) {
            Log.w(TAG, "getScanner: bluetooth is disabled")
            return null
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            Log.w(TAG, "getScanner: bluetoothLeScanner is null (adapter state=${adapter.state})")
        }
        return scanner
    }
    
    
    /**
     * One notch below the old always-on LOW_LATENCY / TX_HIGH. Presence advertising
     * only needs other satellites to find this device; it is not the local On/Off path.
     */
    private fun presenceAdvertiseMode(): Int = when (proxyScanPower) {
        "low" -> AdvertiseSettings.ADVERTISE_MODE_LOW_POWER
        "balanced" -> AdvertiseSettings.ADVERTISE_MODE_BALANCED
        else -> AdvertiseSettings.ADVERTISE_MODE_BALANCED
    }

    private fun presenceAdvertiseTxPower(): Int = when (proxyScanPower) {
        "low" -> AdvertiseSettings.ADVERTISE_TX_POWER_LOW
        "balanced" -> AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM
        else -> AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM
    }

    @SuppressLint("MissingPermission")
    fun startAdvertising() {
        if (presenceAdvertisingSuppressed || isAdvertising || isAdvertisingStarting || !hasBluetoothPermissions()) {
            return
        }
        val advertiser = bleAdvertiser ?: return
        isAdvertisingStarting = true
        runCatching {
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(presenceAdvertiseMode())
                .setTxPowerLevel(presenceAdvertiseTxPower())
                .setConnectable(false)
                .setTimeout(0)
                .build()

            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .addServiceUuid(ParcelUuid(AVA_SERVICE_UUID))
                .build()

            advertiser.startAdvertising(settings, data, advertiseCallback)
        }.onFailure {
            isAdvertisingStarting = false
            isAdvertising = false
            Log.e(TAG, "Failed to start BLE advertising", it)
        }
    }
    
    
    /**
     * Bonded peers (especially iPhones) reconnect on their own for ANCS and battery,
     * and no app-level API can refuse them. Rather than fight the link, watch for it:
     * a connect is reported straight to presence, ahead of the next scan cycle.
     */
    private fun startPresenceLinkWatch() {
        if (presenceLinkReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                } ?: return
                val address = device.address ?: return
                val trackedKey = findTrackedKey(_trackedDevices.value, address) ?: return
                when (action) {
                    BluetoothDevice.ACTION_ACL_CONNECTED -> {
                        // Same rule as applyPresenceLinkSignal: a connect event carries no
                        // RSSI, so it yields to a fresh advert that measured the device
                        // below the user's boundary.
                        val now = System.currentTimeMillis()
                        if (heardBelowThresholdRecently(trackedKey, now)) {
                            Log.i(
                                TAG,
                                "ACL up for $trackedKey, but fresh adverts sit below the " +
                                    "threshold; not counting the link as presence",
                            )
                        } else {
                            Log.i(TAG, "ACL up for $trackedKey — counting as present")
                            markPresent(trackedKey, now)
                        }
                    }
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        val state = intent.getIntExtra(
                            BluetoothDevice.EXTRA_BOND_STATE,
                            BluetoothDevice.BOND_NONE,
                        )
                        if (state == BluetoothDevice.BOND_BONDED) {
                            tryAttachIrkFromBondStoreAsync(address)
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
            presenceLinkReceiver = receiver
            Log.i(TAG, "Presence link watch registered")
        }.onFailure {
            Log.w(TAG, "Failed to register presence link watch", it)
        }
    }

    /** @return true while the peer holds an ACL with us, on any transport. */
    @SuppressLint("MissingPermission")
    private fun isPresenceLinkUp(address: String): Boolean {
        if (address.isBlank()) return false
        val device = runCatching { bluetoothAdapter?.getRemoteDevice(address) }.getOrNull()
            ?: return false
        runCatching {
            val method = device.javaClass.methods.firstOrNull {
                it.name == "isConnected" && it.parameterTypes.isEmpty()
            }
            if (method?.invoke(device) as? Boolean == true) return true
        }
        val manager = bluetoothManager ?: return false
        val profiles = intArrayOf(
            BluetoothProfile.GATT,
            BluetoothProfile.GATT_SERVER,
            BluetoothProfile.HEADSET,
            BluetoothProfile.A2DP,
        )
        return profiles.any { profile ->
            runCatching {
                manager.getConnectionState(device, profile) == BluetoothProfile.STATE_CONNECTED
            }.getOrDefault(false)
        }
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        if (!isAdvertising && !isAdvertisingStarting) return
        bleAdvertiser?.let { advertiser ->
            runCatching {
                advertiser.stopAdvertising(advertiseCallback)
            }.onFailure {
                Log.e(TAG, "Failed to stop BLE advertising", it)
            }
        }
        isAdvertising = false
        isAdvertisingStarting = false
    }
}

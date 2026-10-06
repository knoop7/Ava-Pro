package com.example.ava.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import com.example.esphomeproto.api.BluetoothDeviceRequestType
import com.example.esphomeproto.api.bluetoothConnectionsFreeResponse
import com.example.esphomeproto.api.bluetoothDeviceConnectionResponse
import com.example.esphomeproto.api.bluetoothDevicePairingResponse
import com.example.esphomeproto.api.bluetoothDeviceUnpairingResponse
import com.example.esphomeproto.api.bluetoothDeviceClearCacheResponse
import com.example.esphomeproto.api.bluetoothGATTGetServicesResponse
import com.example.esphomeproto.api.bluetoothGATTGetServicesDoneResponse
import com.example.esphomeproto.api.bluetoothGATTReadResponse
import com.example.esphomeproto.api.bluetoothGATTWriteResponse
import com.example.esphomeproto.api.bluetoothGATTNotifyResponse
import com.example.esphomeproto.api.bluetoothGATTNotifyDataResponse
import com.example.esphomeproto.api.bluetoothGATTErrorResponse
import com.example.esphomeproto.api.bluetoothSetConnectionParamsResponse
import com.example.esphomeproto.api.BluetoothGATTService
import com.example.esphomeproto.api.BluetoothGATTCharacteristic
import com.example.esphomeproto.api.BluetoothGATTDescriptor
import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.reflect.Method
import java.util.UUID

/**
 * Host-provided radio quiet window for a GATT connect attempt. The host pauses whatever
 * else it is doing on the LE radio (proxy scan, presence advertising), runs [block], then
 * resumes. See [BluetoothProxyManager.connectWindow].
 */
typealias GattConnectWindow = suspend (block: suspend () -> Unit) -> Unit

class BluetoothProxyManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val sendMessage: suspend (MessageLite) -> Unit,
    private val presenceManager: BluetoothPresenceManager? = null,
    private val deviceId: String = ""
) {
    companion object {
        private const val TAG = "BluetoothProxy"
        private const val MAX_CONNECTIONS_BLE5 = 5
        private const val MAX_CONNECTIONS_BLE4 = 3
        private const val MTU_BLE5 = 517
        private const val MTU_BLE4 = 185
        private const val RECONNECT_DELAY_MS = 1_000L
        private const val RECONNECT_STALE_MS = 30_000L
        /**
         * A first connect that dies faster than this never reached the air: the local stack
         * refused it (topology check, stale link, resource exhaustion). Remembered per
         * address so the next attempt runs inside a radio quiet window.
         */
        private const val FAST_FAIL_MS = 1_500L
        private const val FAST_FAIL_MEMORY_MS = 10 * 60_000L
        /** Android direct-connect gives up at ~30s; anything older is a lost callback. */
        private const val CONNECT_ATTEMPT_STUCK_MS = 45_000L
        /**
         * Longest the quiet window holds the radio for one connect. The HCI create-connection
         * is already in flight by then; resuming the scanner cannot fail it at the topology
         * check, which only runs when the connection is initiated.
         */
        private const val CONNECT_WINDOW_HOLD_MS = 10_000L
        private const val LAST_SEEN_CAPACITY = 256
        private const val ANDROID_ADDRESS_TYPE_PUBLIC = 0
        private const val ANDROID_ADDRESS_TYPE_RANDOM = 1
        /**
         * Slots never handed to simulated (claimed BTHome) allocations so `free`
         * stays > 0 while real GATT capacity remains. bleak-esphome gates
         * `can_connect` on `free`; a proxy that looks full is skipped for real
         * connections and HA routes them to a farther proxy.
         */
        private const val RESERVED_SLOTS_BLE5 = 2
        private const val RESERVED_SLOTS_BLE4 = 1
        private const val CONNECTIONS_FREE_DEBOUNCE_MS = 200L
        private val CLIENT_CHARACTERISTIC_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        fun maxConnectionsFor(presenceManager: BluetoothPresenceManager?): Int =
            if (presenceManager?.isBle5Supported() == true) MAX_CONNECTIONS_BLE5 else MAX_CONNECTIONS_BLE4
    }

    /**
     * Simulated allocations (claimed BTHome devices) merged into the slot report.
     * Real GATT connections always come first; simulated entries fill what is
     * left above the reserve. Set by [com.example.ava.esphome.voicesatellite.VoiceSatelliteBluetooth].
     */
    @Volatile var simulatedAllocationsProvider: (() -> List<Long>)? = null

    data class SlotSnapshot(val free: Int, val limit: Int, val allocated: List<Long>)

    @Volatile
    private var lastSlotSnapshot: SlotSnapshot? = null

    private val reservedSlots: Int
        get() = if (presenceManager?.isBle5Supported() == true) RESERVED_SLOTS_BLE5 else RESERVED_SLOTS_BLE4

    /**
     * One consistent slot report: `len(allocated) == limit - free` always holds,
     * every real connection is listed, and simulated entries never consume the
     * reserve. bleak-esphome trusts the list only when the lengths match and
     * tears down any tracked client missing from it, so the real set must be
     * complete on every message.
     */
    private fun buildSlotSnapshotLocked(): SlotSnapshot {
        val limit = maxConnections
        val real = connections.keys.toList()
        val simulatedRoom = (limit - real.size - reservedSlots).coerceAtLeast(0)
        val simulated = if (simulatedRoom == 0) {
            emptyList()
        } else {
            (simulatedAllocationsProvider?.invoke() ?: emptyList())
                .filter { it !in connections }
                .distinct()
                .take(simulatedRoom)
        }
        val allocated = real + simulated
        return SlotSnapshot(free = limit - allocated.size, limit = limit, allocated = allocated)
            .also { lastSlotSnapshot = it }
    }

    suspend fun slotSnapshot(): SlotSnapshot = connectionsMutex.withLock { buildSlotSnapshotLocked() }

    private var connectionsFreeDebounceJob: kotlinx.coroutines.Job? = null

    /** Debounced [sendConnectionsFree] for bursty simulated-allocation changes. */
    fun scheduleConnectionsFree() {
        connectionsFreeDebounceJob?.cancel()
        connectionsFreeDebounceJob = scope.launch {
            delay(CONNECTIONS_FREE_DEBOUNCE_MS)
            sendConnectionsFree()
        }
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    
    private val maxConnections: Int
        get() = if (presenceManager?.isBle5Supported() == true) MAX_CONNECTIONS_BLE5 else MAX_CONNECTIONS_BLE4
    
    private val preferredMtu: Int
        get() = if (presenceManager?.isBle5Supported() == true) MTU_BLE5 else MTU_BLE4
    
    private val connections = mutableMapOf<Long, GattConnection>()
    private val connectionsMutex = Mutex()

    /**
     * Optional radio quiet window supplied by the host. When set, a connect attempt that is
     * likely to collide with the host's own scanning/advertising runs inside it. Null means
     * connect directly, as before.
     */
    @Volatile var connectWindow: GattConnectWindow? = null

    /** Address → wall time of the last advert our own scanner delivered. Diagnostics only. */
    private val lastSeenMs = object : LinkedHashMap<Long, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Long>?): Boolean =
            size > LAST_SEEN_CAPACITY
    }

    /** Address → wall time of the last connect that failed inside [FAST_FAIL_MS]. Guarded by [connectionsMutex]. */
    private val fastFailAtMs = mutableMapOf<Long, Long>()
    
    private data class GattConnection(
        val address: Long,
        val device: BluetoothDevice,
        var gatt: BluetoothGatt?,
        var connected: Boolean = false,
        /** True once STATE_CONNECTED was ever observed; distinguishes a failed connect from a dropped link. */
        var everConnected: Boolean = false,
        var reconnecting: Boolean = false,
        var reconnectStartedAtMs: Long = 0L,
        var mtu: Int = 23,
        val notifyHandles: MutableSet<Int> = mutableSetOf(),
        val connectStartedAtMs: Long = System.currentTimeMillis(),
        /** Completed on the first onConnectionStateChange (either way) or on teardown. */
        val firstStateChange: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    /** Called by the host for every advert its proxy scanner delivers. */
    fun noteAdvertisementSeen(address: Long) {
        synchronized(lastSeenMs) { lastSeenMs[address] = System.currentTimeMillis() }
    }

    private fun lastSeenAgoMs(address: Long): Long? {
        val seen = synchronized(lastSeenMs) { lastSeenMs[address] } ?: return null
        return System.currentTimeMillis() - seen
    }

    /**
     * Must hold [connectionsMutex]. The quiet window is a detour taken only on evidence:
     * the first attempt to any address goes down the plain path exactly as before, and only
     * an observed fast failure (< [FAST_FAIL_MS]) switches that address onto the window.
     */
    private fun shouldUseConnectWindowLocked(address: Long, now: Long): Boolean {
        if (connectWindow == null) return false
        fastFailAtMs.entries.removeIf { now - it.value > FAST_FAIL_MEMORY_MS }
        return fastFailAtMs.containsKey(address)
    }

    /**
     * ESPHome address_type: 0 public, 1 random, 2 RPA/public identity, 3 RPA/random identity.
     * The address HA hands us is the one on the air, so anything but 0 is connected as random.
     * Values are BluetoothDevice.ADDRESS_TYPE_PUBLIC / ADDRESS_TYPE_RANDOM (API 33 constants).
     */
    private fun toAndroidAddressType(espAddressType: Int): Int =
        if (espAddressType == 0) ANDROID_ADDRESS_TYPE_PUBLIC else ANDROID_ADDRESS_TYPE_RANDOM

    @SuppressLint("MissingPermission")
    private fun resolveDevice(macAddress: String, addressType: Int): BluetoothDevice? {
        val adapter = bluetoothAdapter ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                return adapter.getRemoteLeDevice(macAddress, toAndroidAddressType(addressType))
            } catch (e: Exception) {
                Log.w(TAG, "getRemoteLeDevice($macAddress, $addressType) failed; falling back", e)
            }
        }
        return try {
            adapter.getRemoteDevice(macAddress)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get remote device: $macAddress", e)
            null
        }
    }

    private fun longToMacAddress(address: Long): String {
        return String.format(
            "%02X:%02X:%02X:%02X:%02X:%02X",
            (address shr 40) and 0xFF,
            (address shr 32) and 0xFF,
            (address shr 24) and 0xFF,
            (address shr 16) and 0xFF,
            (address shr 8) and 0xFF,
            (address and 0xFF)
        )
    }

    private fun macAddressToLong(mac: String): Long {
        val parts = mac.split(":")
        if (parts.size != 6) return 0L
        var address = 0L
        for (i in 0 until 6) {
            address = (address shl 8) or (parts[i].toIntOrNull(16)?.toLong() ?: 0L)
        }
        return address
    }

    /**
     * Real GATT occupancy only. Scan recovery uses this to decide "fully booked";
     * simulated BTHome slots must not suppress a Bluetooth kill/restart, and a
     * stale merged snapshot must not look full after those connections dropped.
     * HA still gets the merged view from [sendConnectionsFree].
     */
    fun getConnectionsFree(): Pair<Int, Int> {
        val limit = maxConnections
        return Pair((limit - connections.size).coerceAtLeast(0), limit)
    }
    
    suspend fun sendConnectionsFree() {
        connectionsMutex.withLock {
            val snapshot = buildSlotSnapshotLocked()
            val allocatedMacs = snapshot.allocated.map { longToMacAddress(it) }
            Log.d(
                TAG,
                "Sending connections free: free=${snapshot.free}, limit=${snapshot.limit}, " +
                    "real=${connections.size}, allocated=$allocatedMacs",
            )
            sendMessage(bluetoothConnectionsFreeResponse {
                this.free = snapshot.free
                this.limit = snapshot.limit
                this.allocated.addAll(snapshot.allocated)
            })
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun handleDeviceRequest(address: Long, requestType: BluetoothDeviceRequestType, addressType: Int) {
        val macAddress = longToMacAddress(address)
        Log.d(TAG, "Device request: $macAddress, type=$requestType, addressType=$addressType")

        when (requestType) {
            BluetoothDeviceRequestType.BLUETOOTH_DEVICE_REQUEST_TYPE_CONNECT,
            BluetoothDeviceRequestType.BLUETOOTH_DEVICE_REQUEST_TYPE_CONNECT_V3_WITH_CACHE,
            BluetoothDeviceRequestType.BLUETOOTH_DEVICE_REQUEST_TYPE_CONNECT_V3_WITHOUT_CACHE -> {
                connectDevice(address, macAddress, addressType)
            }
            BluetoothDeviceRequestType.BLUETOOTH_DEVICE_REQUEST_TYPE_DISCONNECT -> {
                disconnectDevice(address)
            }
            BluetoothDeviceRequestType.BLUETOOTH_DEVICE_REQUEST_TYPE_PAIR -> {
                pairDevice(address, macAddress)
            }
            BluetoothDeviceRequestType.BLUETOOTH_DEVICE_REQUEST_TYPE_UNPAIR -> {
                unpairDevice(address, macAddress)
            }
            BluetoothDeviceRequestType.BLUETOOTH_DEVICE_REQUEST_TYPE_CLEAR_CACHE -> {
                clearCache(address)
            }
            else -> {
                Log.w(TAG, "Unknown request type: $requestType")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectDevice(address: Long, macAddress: String, addressType: Int) {
        var reconnectGatt: BluetoothGatt? = null
        var newConnection: GattConnection? = null
        var useWindow = false
        var stuckGatt: BluetoothGatt? = null
        connectionsMutex.withLock {
            val now = System.currentTimeMillis()
            val existing = connections[address]
            if (existing != null) {
                if (existing.connected) {
                    sendMessage(bluetoothDeviceConnectionResponse {
                        this.address = address
                        this.connected = true
                        this.mtu = existing.mtu
                        this.error = 0
                    })
                    return
                }

                if (!existing.everConnected) {
                    val age = now - existing.connectStartedAtMs
                    if (age < CONNECT_ATTEMPT_STUCK_MS) {
                        // First connect still in flight. Stacking gatt.connect() on top of a
                        // pending direct connect only adds a background whitelist entry; the
                        // pending attempt will answer HA itself.
                        Log.d(TAG, "Connect to $macAddress already in flight (${age}ms); waiting")
                        return
                    }
                    Log.w(TAG, "Connect to $macAddress stuck for ${age}ms without a callback; discarding it")
                    stuckGatt = existing.gatt
                    existing.firstStateChange.complete(Unit)
                    connections.remove(address)
                    // fall through to a fresh attempt below
                } else {
                    if (!existing.reconnecting || now - existing.reconnectStartedAtMs >= RECONNECT_STALE_MS) {
                        existing.reconnecting = true
                        existing.reconnectStartedAtMs = now
                        reconnectGatt = existing.gatt
                    }
                    return@withLock
                }
            }

            if (connections.size >= maxConnections) {
                Log.w(TAG, "Max connections reached")
                sendMessage(bluetoothDeviceConnectionResponse {
                    this.address = address
                    this.connected = false
                    this.error = -1
                })
                return
            }

            val device = resolveDevice(macAddress, addressType)
            if (device == null) {
                sendMessage(bluetoothDeviceConnectionResponse {
                    this.address = address
                    this.connected = false
                    this.error = -2
                })
                return
            }

            val connection = GattConnection(address, device, null, connectStartedAtMs = now)
            connections[address] = connection
            newConnection = connection
            useWindow = shouldUseConnectWindowLocked(address, now)
            val seenAgo = lastSeenAgoMs(address)?.let { "${it}ms ago" } ?: "never by our scanner"
            Log.d(
                TAG,
                "Connection slot allocated for $macAddress, total=${connections.size}, " +
                    "addressType=$addressType, lastAdvert=$seenAgo, quietWindow=$useWindow",
            )
        }
        stuckGatt?.let { gatt ->
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
        }
        newConnection?.let { connection ->
            val window = connectWindow
            if (useWindow && window != null) {
                // Detached: the HA message loop calls us serially, and a window may hold for
                // seconds. connectGatt itself is asynchronous, so the plain path below stays
                // inline exactly as before.
                scope.launch {
                    try {
                        window { startGattConnect(connection, macAddress, holdForFirstState = true) }
                    } catch (e: Exception) {
                        Log.w(TAG, "Quiet-window connect for $macAddress aborted", e)
                    }
                }
            } else {
                startGattConnect(connection, macAddress, holdForFirstState = false)
            }
        }
        reconnectGatt?.let { gatt ->
            val started = runCatching { gatt.connect() }.getOrElse {
                Log.w(TAG, "Failed to request reconnect for $macAddress", it)
                false
            }
            if (!started) {
                connectionsMutex.withLock {
                    connections[address]?.takeIf { it.gatt === gatt }?.reconnecting = false
                }
            } else {
                Log.i(TAG, "Reconnect requested for $macAddress")
            }
        }
        sendConnectionsFree()
    }

    /**
     * Issues the direct connect for a freshly allocated slot. Runs outside [connectionsMutex]
     * so the GATT callback can take the lock. With [holdForFirstState] the caller (a quiet
     * window) is kept open until the stack answers or [CONNECT_WINDOW_HOLD_MS] passes.
     */
    @SuppressLint("MissingPermission")
    private suspend fun startGattConnect(
        connection: GattConnection,
        macAddress: String,
        holdForFirstState: Boolean,
    ) {
        val address = connection.address
        val callback = createGattCallback(address)
        val gatt = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                connection.device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            } else {
                connection.device.connectGatt(context, false, callback)
            }
        } catch (e: Exception) {
            Log.e(TAG, "connectGatt threw for $macAddress", e)
            null
        }

        val stillOwned = connectionsMutex.withLock {
            if (connections[address] === connection) {
                connection.gatt = gatt
                if (gatt == null) connections.remove(address)
                true
            } else {
                false
            }
        }
        if (!stillOwned) {
            // Disconnected (or torn down) while connectGatt was running.
            gatt?.let { runCatching { it.close() } }
            return
        }
        if (gatt == null) {
            connection.firstStateChange.complete(Unit)
            sendMessage(bluetoothDeviceConnectionResponse {
                this.address = address
                this.connected = false
                this.error = -2
            })
            sendConnectionsFree()
            return
        }
        Log.d(TAG, "Connecting to $macAddress")
        if (holdForFirstState) {
            val answered = withTimeoutOrNull(CONNECT_WINDOW_HOLD_MS) { connection.firstStateChange.await() }
            if (answered == null) {
                Log.d(TAG, "Quiet window for $macAddress released after ${CONNECT_WINDOW_HOLD_MS}ms; connect still pending")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun disconnectDevice(address: Long) {
        connectionsMutex.withLock {
            val connection = connections.remove(address)
            connection?.firstStateChange?.complete(Unit)
            connection?.gatt?.let { gatt ->
                gatt.disconnect()
                gatt.close()
            }
            sendMessage(bluetoothDeviceConnectionResponse {
                this.address = address
                this.connected = false
                this.error = 0
            })
            Log.d(TAG, "Disconnected ${longToMacAddress(address)}")
        }
        sendConnectionsFree()
    }

    @SuppressLint("MissingPermission")
    private suspend fun pairDevice(address: Long, macAddress: String) {
        val device = bluetoothAdapter?.getRemoteDevice(macAddress)
        val success = device?.createBond() ?: false
        sendMessage(bluetoothDevicePairingResponse {
            this.address = address
            this.paired = success
            this.error = if (success) 0 else -1
        })
    }

    @SuppressLint("MissingPermission")
    private suspend fun unpairDevice(address: Long, macAddress: String) {
        val device = bluetoothAdapter?.getRemoteDevice(macAddress)
        val success = try {
            val method: Method = device?.javaClass?.getMethod("removeBond") ?: throw Exception("No removeBond method")
            method.invoke(device) as? Boolean ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unpair", e)
            false
        }
        sendMessage(bluetoothDeviceUnpairingResponse {
            this.address = address
            this.success = success
            this.error = if (success) 0 else -1
        })
    }

    @SuppressLint("MissingPermission")
    private suspend fun clearCache(address: Long) {
        val connection = connectionsMutex.withLock { connections[address] }
        val success = try {
            val gatt = connection?.gatt ?: throw Exception("No GATT connection")
            val method: Method = gatt.javaClass.getMethod("refresh")
            method.invoke(gatt) as? Boolean ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear cache", e)
            false
        }
        sendMessage(bluetoothDeviceClearCacheResponse {
            this.address = address
            this.success = success
            this.error = if (success) 0 else -1
        })
    }

    @SuppressLint("MissingPermission")
    suspend fun getServices(address: Long) {
        val connection = connectionsMutex.withLock { connections[address] }
        val gatt = connection?.gatt
        if (gatt == null || !connection.connected) {
            sendMessage(bluetoothGATTErrorResponse {
                this.address = address
                this.handle = 0
                this.error = -1
            })
            return
        }

        val services = gatt.services ?: emptyList()
        for (service in services) {
            sendMessage(bluetoothGATTGetServicesResponse {
                this.address = address
                this.services.add(convertService(service))
            })
        }
        sendMessage(bluetoothGATTGetServicesDoneResponse {
            this.address = address
        })
    }

    private fun convertService(service: BluetoothGattService): BluetoothGATTService {
        return BluetoothGATTService.newBuilder().apply {
            addAllUuid(uuidToProtoList(service.uuid))
            handle = service.instanceId
            service.characteristics.forEach { char ->
                addCharacteristics(convertCharacteristic(char))
            }
        }.build()
    }

    private fun convertCharacteristic(char: BluetoothGattCharacteristic): BluetoothGATTCharacteristic {
        return BluetoothGATTCharacteristic.newBuilder().apply {
            addAllUuid(uuidToProtoList(char.uuid))
            handle = char.instanceId
            properties = char.properties
            char.descriptors.forEach { desc ->
                addDescriptors(convertDescriptor(desc))
            }
        }.build()
    }

    private fun convertDescriptor(desc: BluetoothGattDescriptor): BluetoothGATTDescriptor {
        return BluetoothGATTDescriptor.newBuilder().apply {
            addAllUuid(uuidToProtoList(desc.uuid))
            handle = desc.hashCode() and 0xFFFF
        }.build()
    }

    private fun uuidToProtoList(uuid: UUID): List<Long> {
        return listOf(uuid.mostSignificantBits, uuid.leastSignificantBits)
    }

    @SuppressLint("MissingPermission")
    suspend fun readCharacteristic(address: Long, handle: Int) {
        val connection = connectionsMutex.withLock { connections[address] }
        val gatt = connection?.gatt
        if (gatt == null || !connection.connected) {
            sendGattError(address, handle, -1)
            return
        }

        val characteristic = findCharacteristicByHandle(gatt, handle)
        if (characteristic == null) {
            sendGattError(address, handle, -2)
            return
        }

        if (!gatt.readCharacteristic(characteristic)) {
            sendGattError(address, handle, -3)
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun writeCharacteristic(address: Long, handle: Int, data: ByteArray, response: Boolean) {
        val connection = connectionsMutex.withLock { connections[address] }
        val gatt = connection?.gatt
        if (gatt == null || !connection.connected) {
            sendGattError(address, handle, -1)
            return
        }

        val characteristic = findCharacteristicByHandle(gatt, handle)
        if (characteristic == null) {
            sendGattError(address, handle, -2)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val writeType = if (response) 
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT 
            else 
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            val result = gatt.writeCharacteristic(characteristic, data, writeType)
            if (result != BluetoothGatt.GATT_SUCCESS) {
                sendGattError(address, handle, result)
            }
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = data
            characteristic.writeType = if (response) 
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT 
            else 
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            if (!gatt.writeCharacteristic(characteristic)) {
                sendGattError(address, handle, -3)
            }
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun readDescriptor(address: Long, handle: Int) {
        val connection = connectionsMutex.withLock { connections[address] }
        val gatt = connection?.gatt
        if (gatt == null || !connection.connected) {
            sendGattError(address, handle, -1)
            return
        }

        val descriptor = findDescriptorByHandle(gatt, handle)
        if (descriptor == null) {
            sendGattError(address, handle, -2)
            return
        }

        if (!gatt.readDescriptor(descriptor)) {
            sendGattError(address, handle, -3)
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun writeDescriptor(address: Long, handle: Int, data: ByteArray) {
        val connection = connectionsMutex.withLock { connections[address] }
        val gatt = connection?.gatt
        if (gatt == null || !connection.connected) {
            sendGattError(address, handle, -1)
            return
        }

        val descriptor = findDescriptorByHandle(gatt, handle)
        if (descriptor == null) {
            sendGattError(address, handle, -2)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val result = gatt.writeDescriptor(descriptor, data)
            if (result != BluetoothGatt.GATT_SUCCESS) {
                sendGattError(address, handle, result)
            }
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = data
            if (!gatt.writeDescriptor(descriptor)) {
                sendGattError(address, handle, -3)
            }
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun setNotify(address: Long, handle: Int, enable: Boolean) {
        val connection = connectionsMutex.withLock { connections[address] }
        val gatt = connection?.gatt
        if (gatt == null || !connection.connected) {
            sendGattError(address, handle, -1)
            return
        }

        val characteristic = findCharacteristicByHandle(gatt, handle)
        if (characteristic == null) {
            sendGattError(address, handle, -2)
            return
        }

        if (!gatt.setCharacteristicNotification(characteristic, enable)) {
            sendGattError(address, handle, -3)
            return
        }

        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)
        if (descriptor != null) {
            val value = if (enable) {
                if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                } else {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                }
            } else {
                BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, value)
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = value
                gatt.writeDescriptor(descriptor)
            }
        }

        connectionsMutex.withLock {
            if (enable) {
                connection.notifyHandles.add(handle)
            } else {
                connection.notifyHandles.remove(handle)
            }
        }

        sendMessage(bluetoothGATTNotifyResponse {
            this.address = address
            this.handle = handle
        })
    }

    private fun findCharacteristicByHandle(gatt: BluetoothGatt, handle: Int): BluetoothGattCharacteristic? {
        for (service in gatt.services ?: emptyList()) {
            for (char in service.characteristics) {
                if (char.instanceId == handle) {
                    return char
                }
            }
        }
        return null
    }

    private fun findDescriptorByHandle(gatt: BluetoothGatt, handle: Int): BluetoothGattDescriptor? {
        for (service in gatt.services ?: emptyList()) {
            for (char in service.characteristics) {
                for (desc in char.descriptors) {
                    if ((desc.hashCode() and 0xFFFF) == handle) {
                        return desc
                    }
                }
            }
        }
        return null
    }

    @SuppressLint("MissingPermission")
    suspend fun setConnectionParams(address: Long, minInterval: Int, maxInterval: Int, latency: Int, timeout: Int) {
        val connection = connectionsMutex.withLock { connections[address] }
        val gatt = connection?.gatt
        if (gatt == null || connection?.connected != true) {
            sendMessage(bluetoothSetConnectionParamsResponse {
                this.address = address
                this.error = -1
            })
            return
        }

        val priority = when {
            maxInterval <= 12 -> BluetoothGatt.CONNECTION_PRIORITY_HIGH
            minInterval >= 80 -> BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER
            else -> BluetoothGatt.CONNECTION_PRIORITY_BALANCED
        }

        val success = gatt.requestConnectionPriority(priority)
        sendMessage(bluetoothSetConnectionParamsResponse {
            this.address = address
            this.error = if (success) 0 else -2
        })
    }

    private suspend fun sendGattError(address: Long, handle: Int, error: Int) {
        sendMessage(bluetoothGATTErrorResponse {
            this.address = address
            this.handle = handle
            this.error = error
        })
    }

    @SuppressLint("MissingPermission")
    private fun createGattCallback(address: Long): BluetoothGattCallback {
        return object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                scope.launch {
                    var neverConnectedFailure = false
                    connectionsMutex.withLock {
                        val connection = connections[address] ?: return@launch
                        // A slot can be re-issued (stuck attempt discarded, fresh connect made).
                        // A late callback from the discarded GATT must not touch the new one.
                        // gatt == null only while connectGatt is still returning; then this
                        // callback can only belong to the GATT being created.
                        if (connection.gatt != null && connection.gatt !== gatt) {
                            Log.d(TAG, "Ignoring state change from superseded GATT for ${longToMacAddress(address)}")
                            return@launch
                        }
                        when (newState) {
                            BluetoothProfile.STATE_CONNECTED -> {
                                connection.connected = true
                                connection.everConnected = true
                                connection.reconnecting = false
                                connection.reconnectStartedAtMs = 0L
                                fastFailAtMs.remove(address)
                                Log.d(TAG, "Connected to ${longToMacAddress(address)}, requesting MTU=$preferredMtu")
                                gatt.requestMtu(preferredMtu)
                            }
                            BluetoothProfile.STATE_DISCONNECTED -> {
                                connection.connected = false
                                connection.reconnecting = false
                                connection.reconnectStartedAtMs = 0L
                                if (!connection.everConnected) {
                                    // Never reached the peer. This is a failed connect, not a
                                    // dropped link: free the slot and report the real status
                                    // instead of parking a dead GATT in a background reconnect.
                                    neverConnectedFailure = true
                                    connections.remove(address)
                                    val now = System.currentTimeMillis()
                                    val elapsed = now - connection.connectStartedAtMs
                                    val fast = elapsed < FAST_FAIL_MS
                                    if (fast) fastFailAtMs[address] = now
                                    Log.w(
                                        TAG,
                                        "Connect to ${longToMacAddress(address)} failed status=$status " +
                                            "after ${elapsed}ms" +
                                            (if (fast) " (local stack refused; next attempt gets a quiet window)" else "") +
                                            "; slot released",
                                    )
                                } else {
                                    Log.w(
                                        TAG,
                                        "Unexpected disconnect from ${longToMacAddress(address)} " +
                                            "status=$status; retaining GATT for reconnect",
                                    )
                                }
                            }
                        }
                        connection.firstStateChange.complete(Unit)
                    }

                    if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        if (neverConnectedFailure) {
                            runCatching { gatt.close() }
                            sendMessage(bluetoothDeviceConnectionResponse {
                                this.address = address
                                this.connected = false
                                this.error = if (status != BluetoothGatt.GATT_SUCCESS) status else BluetoothGatt.GATT_FAILURE
                            })
                            sendConnectionsFree()
                            return@launch
                        }
                        sendMessage(bluetoothDeviceConnectionResponse {
                            this.address = address
                            this.connected = false
                            this.error = 0
                        })
                        sendConnectionsFree()
                        delay(RECONNECT_DELAY_MS)
                        requestReconnect(address, gatt)
                    }
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                scope.launch {
                    connectionsMutex.withLock {
                        val connection = connections[address] ?: return@launch
                        connection.mtu = mtu
                        Log.d(TAG, "MTU changed to $mtu for ${longToMacAddress(address)}")
                    }
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        gatt.discoverServices()
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                scope.launch {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        val connection = connectionsMutex.withLock { connections[address] }
                        Log.d(TAG, "Services discovered for ${longToMacAddress(address)}")
                        sendMessage(bluetoothDeviceConnectionResponse {
                            this.address = address
                            this.connected = true
                            this.mtu = connection?.mtu ?: 23
                            this.error = 0
                        })
                        sendConnectionsFree()
                    } else {
                        // HA is told the connect failed, so the slot must not stay booked as a
                        // live connection or the next request would be answered "connected"
                        // against a GATT with no service table.
                        val owned = connectionsMutex.withLock {
                            val connection = connections[address]
                            if (connection != null && connection.gatt === gatt) {
                                connections.remove(address)
                                true
                            } else {
                                false
                            }
                        }
                        Log.w(TAG, "Service discovery failed for ${longToMacAddress(address)} status=$status; slot released")
                        if (owned) {
                            runCatching { gatt.disconnect() }
                            runCatching { gatt.close() }
                        }
                        sendMessage(bluetoothDeviceConnectionResponse {
                            this.address = address
                            this.connected = false
                            this.error = status
                        })
                        if (owned) sendConnectionsFree()
                    }
                }
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int
            ) {
                scope.launch {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        sendMessage(bluetoothGATTReadResponse {
                            this.address = address
                            this.handle = characteristic.instanceId
                            this.data = ByteString.copyFrom(value)
                        })
                    } else {
                        sendGattError(address, characteristic.instanceId, status)
                    }
                }
            }

            @Deprecated("Deprecated in API 33")
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                scope.launch {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        @Suppress("DEPRECATION")
                        sendMessage(bluetoothGATTReadResponse {
                            this.address = address
                            this.handle = characteristic.instanceId
                            this.data = ByteString.copyFrom(characteristic.value ?: byteArrayOf())
                        })
                    } else {
                        sendGattError(address, characteristic.instanceId, status)
                    }
                }
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                scope.launch {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        sendMessage(bluetoothGATTWriteResponse {
                            this.address = address
                            this.handle = characteristic.instanceId
                        })
                    } else {
                        sendGattError(address, characteristic.instanceId, status)
                    }
                }
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                scope.launch {
                    sendMessage(bluetoothGATTNotifyDataResponse {
                        this.address = address
                        this.handle = characteristic.instanceId
                        this.data = ByteString.copyFrom(value)
                    })
                }
            }

            @Deprecated("Deprecated in API 33")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                scope.launch {
                    @Suppress("DEPRECATION")
                    sendMessage(bluetoothGATTNotifyDataResponse {
                        this.address = address
                        this.handle = characteristic.instanceId
                        this.data = ByteString.copyFrom(characteristic.value ?: byteArrayOf())
                    })
                }
            }

            override fun onDescriptorRead(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
                value: ByteArray
            ) {
                scope.launch {
                    val handle = descriptor.hashCode() and 0xFFFF
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        sendMessage(bluetoothGATTReadResponse {
                            this.address = address
                            this.handle = handle
                            this.data = ByteString.copyFrom(value)
                        })
                    } else {
                        sendGattError(address, handle, status)
                    }
                }
            }

            @Deprecated("Deprecated in API 33")
            override fun onDescriptorRead(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int
            ) {
                scope.launch {
                    val handle = descriptor.hashCode() and 0xFFFF
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        @Suppress("DEPRECATION")
                        sendMessage(bluetoothGATTReadResponse {
                            this.address = address
                            this.handle = handle
                            this.data = ByteString.copyFrom(descriptor.value ?: byteArrayOf())
                        })
                    } else {
                        sendGattError(address, handle, status)
                    }
                }
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int
            ) {
                scope.launch {
                    val handle = descriptor.hashCode() and 0xFFFF
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        sendMessage(bluetoothGATTWriteResponse {
                            this.address = address
                            this.handle = handle
                        })
                    } else {
                        sendGattError(address, handle, status)
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestReconnect(address: Long, gatt: BluetoothGatt) {
        val shouldReconnect = connectionsMutex.withLock {
            val connection = connections[address]
            if (connection == null || connection.gatt !== gatt || connection.connected || connection.reconnecting) {
                false
            } else {
                connection.reconnecting = true
                connection.reconnectStartedAtMs = System.currentTimeMillis()
                true
            }
        }
        if (!shouldReconnect) return

        val started = runCatching { gatt.connect() }.getOrElse {
            Log.w(TAG, "Reconnect call failed for ${longToMacAddress(address)}", it)
            false
        }
        if (!started) {
            connectionsMutex.withLock {
                connections[address]?.takeIf { it.gatt === gatt }?.reconnecting = false
            }
            Log.w(TAG, "Reconnect was rejected for ${longToMacAddress(address)}")
        } else {
            Log.i(TAG, "Waiting for background reconnect to ${longToMacAddress(address)}")
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun disconnectAll() {
        connectionsMutex.withLock {
            connections.values.forEach { conn ->
                conn.firstStateChange.complete(Unit)
                conn.gatt?.disconnect()
                conn.gatt?.close()
            }
            connections.clear()
        }
        Log.d(TAG, "Disconnected all connections")
    }
}

package com.example.ava.mods

import android.content.Context
import com.example.ava.bluetooth.BleOperationCoordinator
import com.example.ava.bluetooth.BluetoothPresenceManager
import com.example.esphomeproto.api.homeassistantServiceMap
import com.example.esphomeproto.api.homeassistantServiceResponse
import com.google.protobuf.MessageLite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Host callbacks passed into the ble-adv-proxy mod at runtime (via parent ClassLoader).
 * The mod invokes these methods by reflection or direct call when compiled against the APK.
 */
class BleAdvHostApi(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val sendMessage: suspend (MessageLite) -> Unit,
) {
    /** Stop Ava presence LE advertising before raw MGMT inject (mod cannot load host classes). */
    fun pausePresenceForRawAdvertise() {
        setPresenceAdvertisingSuppressed(true)
    }

    /**
     * Legacy integrated only: suppress host presence ADV so MGMT TX is not BUSY.
     * Standalone mod does not touch presence flags here (mod owns BLE).
     */
    fun setPresenceAdvertisingSuppressed(suppressed: Boolean) {
        BluetoothPresenceManager.getInstance(appContext).setPresenceAdvertisingSuppressed(suppressed)
        BleOperationCoordinator.setSuppressPresenceResume(suppressed)
    }

    /** Block until the Bluetooth stack releases the advertiser after [pausePresenceForRawAdvertise]. */
    fun awaitRawAdvertiseSettle() {
        Thread.sleep(RAW_ADV_SETTLE_MS)
    }

    fun fireHomeassistantEvent(service: String, data: Map<String, String>) {
        if (service.isBlank()) return
        scope.launch {
            sendMessage(
                homeassistantServiceResponse {
                    this.service = service
                    isEvent = true
                    data.forEach { (key, value) ->
                        this.data += homeassistantServiceMap {
                            this.key = key
                            this.value = value
                        }
                    }
                },
            )
        }
    }

    /** Blocking exclusive BLE window — used for one advertise burst. */
    fun runExclusiveTransmit(task: Runnable) {
        BleOperationCoordinator.runExclusiveBlocking {
            task.run()
        }
    }

    /** Queued exclusive BLE work — used for multi-packet ADV sequences. */
    fun enqueueExclusiveTransmit(task: Runnable, onComplete: Runnable?) {
        BleOperationCoordinator.enqueueExclusiveAsync(
            block = { task.run() },
            onComplete = { onComplete?.run() },
        )
    }

    fun isExclusiveActive(): Boolean = BleOperationCoordinator.isExclusiveActive

    companion object {
        private const val RAW_ADV_SETTLE_MS = 1000L
    }
}

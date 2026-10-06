package com.example.ava.settings

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.dataStore
import com.example.ava.utils.getLegacyBluetoothMacAddressString
import com.example.ava.utils.getStableBluetoothMacAddressString
import com.example.ava.utils.getStableEspHomeApiPort
import com.example.ava.utils.getStableEspHomeMacAddressString
import com.example.ava.utils.randomLocallyAdministeredMac
import kotlinx.serialization.Serializable

@Serializable
data class VoiceSatelliteSettings(
    val name: String,
    val serverPort: Int,
    val macAddress: String,
    /** True after the user saves a port in settings; first-run identity must not replace it. */
    val serverPortUserConfigured: Boolean = false,
    /**
     * ESPHome Noise PSK as standard base64 (32 raw bytes). Empty = plaintext API,
     * the same as current installs and ESPHome without `api.encryption`.
     */
    val encryptionKey: String = "",
    val haRemoteUrl: String = "",
    /** Right-pane URL when browser split view is enabled; left pane keeps [haRemoteUrl]. */
    val haRemoteUrlRight: String = "",
    val haMediaPlayerEntity: String = "",
    val haMediaPlayerDuckEnabled: Boolean = false,
    val haMediaPlayerDuckVolume: Float = 0.1f,
    /**
     * Synthetic Bluetooth adapter MAC reported to Home Assistant as
     * `bluetooth_mac_address`. Empty until [VoiceSatelliteSettingsStore.ensureMacAddressIsSet]
     * freezes a value. Once set it is never rewritten (upgrade-safe).
     */
    val bluetoothMacAddress: String = "",
)

/** Android NsdManager requires port > 0; TCP also needs a concrete 1..65535 bind port. */
fun isValidVoiceSatellitePort(port: Int): Boolean = port in 1..65535

/** Uninitialized DataStore default — not a port Home Assistant should already know. */
internal const val FACTORY_DEFAULT_SERVER_PORT = 6053

/**
 * Decide MAC / name / port for [VoiceSatelliteSettingsStore.ensureMacAddressIsSet].
 *
 * Existing installs (MAC already set, port valid — including 6053) are frozen so
 * a Home Assistant entry that already works is not rewritten on upgrade.
 * A new port is assigned only when identity is still unset and the stored port
 * is the factory default or unusable, or when a stored port is corrupt.
 */
internal fun nextVoiceSatelliteIdentity(
    current: VoiceSatelliteSettings,
    newMac: String,
    derivedPort: Int,
    defaultDeviceName: String,
    nextDefaultName: String,
): VoiceSatelliteSettings {
    val nextName = when {
        current.macAddress != DEFAULT_MAC_ADDRESS -> current.name
        current.name.isBlank() || current.name == defaultDeviceName -> nextDefaultName
        else -> current.name
    }

    return when {
        current.macAddress == DEFAULT_MAC_ADDRESS -> {
            val keepStoredPort = isValidVoiceSatellitePort(current.serverPort) &&
                (current.serverPortUserConfigured || current.serverPort != FACTORY_DEFAULT_SERVER_PORT)
            current.copy(
                macAddress = newMac,
                name = nextName,
                serverPort = if (keepStoredPort) current.serverPort else derivedPort,
            )
        }
        !isValidVoiceSatellitePort(current.serverPort) ->
            current.copy(serverPort = derivedPort)
        else -> current
    }
}

/**
 * Bluetooth scanner identity for [VoiceSatelliteSettingsStore.ensureMacAddressIsSet].
 *
 * - Already stored: freeze (HA Bluetooth config entries key on this MAC).
 * - Upgrade (node MAC already set, Bluetooth MAC empty): keep the legacy
 *   `hashCode()` value so existing HA scanner entries stay attached.
 * - New / clear-data (node MAC still the sentinel): SHA-256 adapter MAC.
 */
internal fun nextBluetoothMacAddress(
    currentNodeMac: String,
    currentBluetoothMac: String,
    legacyMac: String,
    derivedMac: String,
): String {
    if (currentBluetoothMac.isNotBlank()) return currentBluetoothMac
    return if (currentNodeMac != DEFAULT_MAC_ADDRESS) legacyMac else derivedMac
}

/** Stock auto name for a node MAC: `<model>_<last two MAC bytes>_voice_assistant`. */
internal fun defaultNameForMac(model: String?, mac: String): String {
    val macSuffix = mac.takeLast(5).replace(":", "")
    return normalizeEspNodeName("${(model ?: "Android").replace(" ", "_")}_${macSuffix}_voice_assistant")
}

/**
 * Manual repair for a node whose identity collides with another device (Ava-Pro#221).
 *
 * Replaces node MAC and Bluetooth scanner MAC. The name follows only when it is still a
 * stock auto name (bare default or the MAC-suffixed default for the OLD MAC): two units
 * that shared a MAC also shared that name, and identical mDNS names re-create the
 * "name (2)" conflict churn (issue #201). A user-typed name is theirs and stays. Port is
 * untouched — two hosts on the same port never collide.
 */
internal fun regeneratedVoiceSatelliteIdentity(
    current: VoiceSatelliteSettings,
    newMac: String,
    newBluetoothMac: String,
    model: String?,
): VoiceSatelliteSettings {
    val stockNames = setOf(
        normalizeEspNodeName("${(model ?: "Android").replace(" ", "_")}_voice_assistant"),
        defaultNameForMac(model, current.macAddress),
    )
    val nextName = if (current.name.isBlank() || normalizeEspNodeName(current.name) in stockNames) {
        defaultNameForMac(model, newMac)
    } else {
        current.name
    }
    return current.copy(
        macAddress = newMac,
        bluetoothMacAddress = newBluetoothMac,
        name = nextName,
    )
}

/**
 * ESPHome node names must be a lowercase slug of [a-z0-9_]. Home Assistant lowercases every
 * registered service name, while ha-ble-adv builds its ESPHome service-lookup key from the
 * reported device name case-sensitively; an uppercase name (e.g. "MI_9") therefore makes
 * ble_adv fail to match <name>_setup_svc_v0 / <name>_adv_svc_v1 and report "Invalid adapter".
 * Normalize thoroughly to a safe lowercase slug.
 */
fun normalizeEspNodeName(raw: String?): String {
    if (raw == null) return ""
    return raw.lowercase(java.util.Locale.ROOT)
        .replace(Regex("[^a-z0-9_]"), "_")
        .replace(Regex("_+"), "_")
        .trim('_')
}

/**
 * Heal device names polluted by Android's mDNS conflict rename.
 *
 * When NSD registration probing collided with this device's own stale
 * advertisement, the OS renamed the service ("name (2)") and older builds
 * persisted that transient name as permanent identity — stacking one more
 * suffix per service restart, which HA saw as `..._2_2_2` node names with a
 * stale `voice_action` service left behind each time (issue #201).
 *
 * Only a trailing chain of TWO OR MORE " (N)" groups is stripped: that shape
 * is the unambiguous signature of the old persist-on-rename bug, while a
 * single " (2)" is indistinguishable from a deliberate user naming scheme
 * ("Tablet (1)" / "Tablet (2)" for sibling devices) and must never be
 * second-guessed. Legacy installs stuck at exactly one suffix stay as-is —
 * the name no longer grows under current code and a manual rename fixes it.
 */
internal fun stripNsdConflictRename(name: String): String {
    val stripped = name.replace(Regex("(\\s*\\(\\d+\\)){2,}\\s*$"), "")
    return if (stripped.isBlank()) name else stripped
}







val DEFAULT_MAC_ADDRESS = "00:00:00:00:00:00"


val DEFAULT_DEVICE_NAME: String
    get() = normalizeEspNodeName("${(Build.MODEL ?: "Android").replace(" ", "_")}_voice_assistant")

private val DEFAULT = VoiceSatelliteSettings(
    name = DEFAULT_DEVICE_NAME,
    serverPort = FACTORY_DEFAULT_SERVER_PORT,
    macAddress = DEFAULT_MAC_ADDRESS
)

val Context.voiceSatelliteSettingsStore: DataStore<VoiceSatelliteSettings> by dataStore(
    fileName = "voice_satellite_settings.json",
    serializer = SettingsSerializer(VoiceSatelliteSettings.serializer(), DEFAULT),
    corruptionHandler = defaultCorruptionHandler(DEFAULT)
)

class VoiceSatelliteSettingsStore(dataStore: DataStore<VoiceSatelliteSettings>) :
    SettingsStoreImpl<VoiceSatelliteSettings>(dataStore, DEFAULT) {
    suspend fun saveName(name: String) =
        update { it.copy(name = name) }

    suspend fun saveServerPort(serverPort: Int) =
        update { it.copy(serverPort = serverPort, serverPortUserConfigured = true) }

    suspend fun saveEncryptionKey(encryptionKey: String) =
        update { it.copy(encryptionKey = encryptionKey.trim()) }

    suspend fun saveHaRemoteUrl(url: String) =
        update { it.copy(haRemoteUrl = url) }

    suspend fun saveHaRemoteUrlRight(url: String) =
        update { it.copy(haRemoteUrlRight = url) }

    suspend fun saveHaMediaPlayerEntity(entity: String) =
        update { it.copy(haMediaPlayerEntity = entity) }
    
    suspend fun saveHaMediaPlayerDuckEnabled(enabled: Boolean) =
        update { it.copy(haMediaPlayerDuckEnabled = enabled) }
    
    suspend fun saveHaMediaPlayerDuckVolume(volume: Float) =
        update { it.copy(haMediaPlayerDuckVolume = volume) }
    
    /**
     * First-time / cleared-data identity only.
     *
     * - If [VoiceSatelliteSettings.macAddress] is already set (legacy random or prior stable):
     *   **never** rewrite MAC, name, or a valid port (including 6053) — old HA entries stay valid.
     * - If still [DEFAULT_MAC_ADDRESS]: assign a device-stable MAC from ANDROID_ID (new users
     *   / clear-data). Name gets a MAC suffix only when it is still the stock default name.
     *   Port becomes the device-stable ANDROID_ID port unless a non-default or user-saved
     *   port is already stored (backup restore / manual setting).
     */
    suspend fun ensureMacAddressIsSet(context: Context) {
        update {
            // One-off repair for installs whose stored name accumulated mDNS
            // conflict-rename suffixes before they stopped being persisted (issue #201).
            val repairedName = stripNsdConflictRename(it.name)
            val current = if (repairedName != it.name) {
                Log.w(TAG, "Repaired NSD conflict-renamed device name '${it.name}' -> '$repairedName'")
                it.copy(name = repairedName)
            } else {
                it
            }
            val newMac = getStableEspHomeMacAddressString(context)
            val next = nextVoiceSatelliteIdentity(
                current = current,
                newMac = newMac,
                derivedPort = getStableEspHomeApiPort(context),
                defaultDeviceName = DEFAULT_DEVICE_NAME,
                nextDefaultName = defaultNameForMac(Build.MODEL, newMac),
            )
            if (next.serverPort != it.serverPort && !isValidVoiceSatellitePort(it.serverPort)) {
                Log.w(TAG, "Invalid serverPort=${it.serverPort}; using ${next.serverPort}")
            }
            val bluetoothMac = nextBluetoothMacAddress(
                currentNodeMac = current.macAddress,
                currentBluetoothMac = current.bluetoothMacAddress,
                legacyMac = getLegacyBluetoothMacAddressString(context),
                derivedMac = getStableBluetoothMacAddressString(context),
            )
            next.copy(bluetoothMacAddress = bluetoothMac)
        }
    }

    /**
     * User-triggered identity repair (Ava-Pro#221). Home Assistant will treat this node
     * as a brand-new device; the caller restarts the satellite so mDNS, the Noise server
     * hello and DeviceInfo all advertise the new MAC together.
     */
    suspend fun regenerateEspHomeIdentity() {
        update { current ->
            val newMac = randomLocallyAdministeredMac()
            var newBluetoothMac = randomLocallyAdministeredMac()
            while (newBluetoothMac == newMac) newBluetoothMac = randomLocallyAdministeredMac()
            val next = regeneratedVoiceSatelliteIdentity(
                current = current,
                newMac = newMac,
                newBluetoothMac = newBluetoothMac,
                model = Build.MODEL,
            )
            Log.w(
                TAG,
                "Regenerated ESPHome identity: mac ${current.macAddress} -> ${next.macAddress}, " +
                    "name '${current.name}' -> '${next.name}'",
            )
            next
        }
    }

    companion object {
        private const val TAG = "VoiceSatelliteSettings"
    }
}
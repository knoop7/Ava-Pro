package com.example.ava.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.dataStore
import com.example.ava.sendspin.SendspinFormatCatalog
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

@Serializable
data class SendspinSettings(
    val enabled: Boolean = true,
    val serverUrl: String = "",
    val autoConnect: Boolean = true,
    val advertiseAsPlayer: Boolean = true,
    val lastPlayedServerId: String = "",
    /** Legacy flag; always migrated to true. Kept for one-time software→device volume migration. */
    val useDeviceVolume: Boolean = true,
    val lowMemoryMode: Boolean = true,
    val syncOffsetMs: Int = 0,
    val volume: Int = 100,
    val muted: Boolean = false,
    // 已配对的设备列表 (设备ID -> 设备名称)
    val pairedDevices: Map<String, String> = emptyMap(),
    /**
     * Legacy one-way flag (device → HA). Prefer [volumeFollowRule]; kept so existing installs
     * migrate when [volumeFollowRule] is still blank.
     */
    val syncDeviceVolumeWithHa: Boolean = false,
    /**
     * [VolumeFollowRule.storageKey]. Blank means not migrated yet — see [VolumeFollowRule.fromSettings].
     * New installs resolve to [VolumeFollowRule.INDEPENDENT].
     */
    val volumeFollowRule: String = "",
    val preferredFormat: String = "automatic",
    val customDeviceName: String = "",
    /** Music Assistant / Sendspin output equalizer (software 5-band). Default off. */
    val musicEqEnabled: Boolean = false,
    val musicEqBassDb: Float = 0f,
    val musicEqLowMidDb: Float = 0f,
    val musicEqMidDb: Float = 0f,
    val musicEqUpperMidDb: Float = 0f,
    val musicEqTrebleDb: Float = 0f,
    /** Level-driven low/high contour on top of the bands. Requires the EQ switch. */
    val musicEqAdaptive: Boolean = false,
)

val Context.sendspinSettingsStore: DataStore<SendspinSettings> by dataStore(
    fileName = "sendspin_settings.json",
    serializer = SettingsSerializer(SendspinSettings.serializer(), SendspinSettings()),
    corruptionHandler = defaultCorruptionHandler(SendspinSettings())
)

class SendspinSettingsStore(dataStore: DataStore<SendspinSettings>) :
    SettingsStoreImpl<SendspinSettings>(dataStore, SendspinSettings()) {
    
    val enabled = SettingState(getFlow().map { it.enabled }) { value ->
        update { it.copy(enabled = value) }
    }
    
    val serverUrl = SettingState(getFlow().map { it.serverUrl }) { value ->
        update { it.copy(serverUrl = value.trim()) }
    }
    
    val autoConnect = SettingState(getFlow().map { it.autoConnect }) { value ->
        update { it.copy(autoConnect = value) }
    }
    
    val advertiseAsPlayer = SettingState(getFlow().map { it.advertiseAsPlayer }) { value ->
        update { it.copy(advertiseAsPlayer = value) }
    }
    
    val lastPlayedServerId = SettingState(getFlow().map { it.lastPlayedServerId }) { value ->
        update { it.copy(lastPlayedServerId = value) }
    }
    
    val useDeviceVolume = SettingState(getFlow().map { it.useDeviceVolume }) { value ->
        update { it.copy(useDeviceVolume = value) }
    }

    val lowMemoryMode = SettingState(getFlow().map { it.lowMemoryMode }) { value ->
        update { it.copy(lowMemoryMode = value) }
    }

    val syncOffsetMs = SettingState(getFlow().map { it.syncOffsetMs }) { value ->
        update { it.copy(syncOffsetMs = value.coerceIn(-5000, 5000)) }
    }

    val volume = SettingState(getFlow().map { it.volume }) { value ->
        update { it.copy(volume = value.coerceIn(0, 100)) }
    }

    val muted = SettingState(getFlow().map { it.muted }) { value ->
        update { it.copy(muted = value) }
    }
    
    val pairedDevices = SettingState(getFlow().map { it.pairedDevices }) { value ->
        update { it.copy(pairedDevices = value) }
    }

    val syncDeviceVolumeWithHa = SettingState(getFlow().map { it.syncDeviceVolumeWithHa }) { value ->
        update { it.copy(syncDeviceVolumeWithHa = value) }
    }

    val volumeFollowRule = SettingState(getFlow().map { VolumeFollowRule.fromSettings(it) }) { value ->
        update {
            it.copy(
                volumeFollowRule = value.storageKey,
                // Keep legacy boolean aligned for older readers / fleet snapshots.
                syncDeviceVolumeWithHa = value.mirrorsDeviceToHa,
            )
        }
    }

    val preferredFormat = SettingState(getFlow().map { it.preferredFormat }) { value ->
        update { it.copy(preferredFormat = SendspinFormatCatalog.normalizePreferredFormat(value)) }
    }

    val customDeviceName = SettingState(getFlow().map { it.customDeviceName }) { value ->
        update { it.copy(customDeviceName = value) }
    }

    val musicEqEnabled = SettingState(getFlow().map { it.musicEqEnabled }) { value ->
        update { it.copy(musicEqEnabled = value) }
    }
    val musicEqBassDb = SettingState(getFlow().map { it.musicEqBassDb }) { value ->
        update { it.copy(musicEqBassDb = com.example.ava.audio.eq.MusicEqGains.clampDb(value)) }
    }
    val musicEqLowMidDb = SettingState(getFlow().map { it.musicEqLowMidDb }) { value ->
        update { it.copy(musicEqLowMidDb = com.example.ava.audio.eq.MusicEqGains.clampDb(value)) }
    }
    val musicEqMidDb = SettingState(getFlow().map { it.musicEqMidDb }) { value ->
        update { it.copy(musicEqMidDb = com.example.ava.audio.eq.MusicEqGains.clampDb(value)) }
    }
    val musicEqUpperMidDb = SettingState(getFlow().map { it.musicEqUpperMidDb }) { value ->
        update { it.copy(musicEqUpperMidDb = com.example.ava.audio.eq.MusicEqGains.clampDb(value)) }
    }
    val musicEqTrebleDb = SettingState(getFlow().map { it.musicEqTrebleDb }) { value ->
        update { it.copy(musicEqTrebleDb = com.example.ava.audio.eq.MusicEqGains.clampDb(value)) }
    }

    val musicEqAdaptive = SettingState(getFlow().map { it.musicEqAdaptive }) { value ->
        update { it.copy(musicEqAdaptive = value) }
    }

    suspend fun setMusicEq(gains: com.example.ava.audio.eq.MusicEqGains) {
        val n = gains.normalized()
        update {
            it.copy(
                musicEqEnabled = n.enabled,
                musicEqBassDb = n.bassDb,
                musicEqLowMidDb = n.lowMidDb,
                musicEqMidDb = n.midDb,
                musicEqUpperMidDb = n.upperMidDb,
                musicEqTrebleDb = n.trebleDb,
                musicEqAdaptive = n.adaptiveEnabled,
            )
        }
    }
    
    // 添加配对设备
    suspend fun addPairedDevice(deviceId: String, deviceName: String) {
        update { settings ->
            val newPaired = settings.pairedDevices.toMutableMap()
            newPaired[deviceId] = deviceName
            settings.copy(pairedDevices = newPaired)
        }
    }
    
    // 移除配对设备
    suspend fun removePairedDevice(deviceId: String) {
        update { settings ->
            val newPaired = settings.pairedDevices.toMutableMap()
            newPaired.remove(deviceId)
            settings.copy(pairedDevices = newPaired)
        }
    }
    
    // 清除所有配对设备
    suspend fun clearPairedDevices() {
        update { it.copy(pairedDevices = emptyMap()) }
    }
    
    // 检查设备是否已配对（suspend — avoid runBlocking / ANR）
    suspend fun isPaired(deviceId: String): Boolean {
        return get().pairedDevices.containsKey(deviceId)
    }

    /** Cached/non-blocking hint — prefer [isPaired] from coroutines. */
    fun isPairedCached(deviceId: String): Boolean {
        return runCatching {
            getCached().pairedDevices.containsKey(deviceId)
        }.getOrDefault(false)
    }
}

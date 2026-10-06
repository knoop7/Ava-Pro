package com.example.ava.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.dataStore
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

@Serializable
data class HaSettings(
    val serverUrl: String = "",
    val accessToken: String = "",
    val preferredPipeline: String = "",
    val guideDismissed: Boolean = false,
    val pipelineGuideDismissed: Boolean = false,
    val entityPickerEnabled: Boolean = true,
    val scanModeSyncEnabled: Boolean = true,
    val allowServiceCalls: Boolean = true,
    val wsCallServiceEnabled: Boolean = false,
    val liveStateEnabled: Boolean = true,
    val btProxyCleanupEnabled: Boolean = true,
    val mediaBearerEnabled: Boolean = true,
    val historyBackfillEnabled: Boolean = true,
    val esphomeIdentityExpanded: Boolean? = null,
)

val Context.haSettingsStore: DataStore<HaSettings> by dataStore(
    fileName = "ha_settings.json",
    serializer = SettingsSerializer(HaSettings.serializer(), HaSettings()),
    corruptionHandler = defaultCorruptionHandler(HaSettings()),
)

class HaSettingsStore(dataStore: DataStore<HaSettings>) :
    SettingsStoreImpl<HaSettings>(dataStore, HaSettings()) {

    val serverUrl = SettingState(getFlow().map { it.serverUrl }) { value ->
        update { it.copy(serverUrl = normalizeHaUrl(value)) }
    }

    val accessToken = SettingState(getFlow().map { it.accessToken }) { value ->
        update { it.copy(accessToken = value.trim()) }
    }

    val preferredPipeline = SettingState(getFlow().map { it.preferredPipeline }) { value ->
        update { it.copy(preferredPipeline = value.trim()) }
    }

    val guideDismissed = SettingState(getFlow().map { it.guideDismissed }) { value ->
        update { it.copy(guideDismissed = value) }
    }

    val pipelineGuideDismissed = SettingState(getFlow().map { it.pipelineGuideDismissed }) { value ->
        update { it.copy(pipelineGuideDismissed = value) }
    }

    val entityPickerEnabled = SettingState(getFlow().map { it.entityPickerEnabled }) { value ->
        update { it.copy(entityPickerEnabled = value) }
    }

    val scanModeSyncEnabled = SettingState(getFlow().map { it.scanModeSyncEnabled }) { value ->
        update { it.copy(scanModeSyncEnabled = value) }
    }

    val allowServiceCalls = SettingState(getFlow().map { it.allowServiceCalls }) { value ->
        update { it.copy(allowServiceCalls = value) }
    }

    val wsCallServiceEnabled = SettingState(getFlow().map { it.wsCallServiceEnabled }) { value ->
        update { it.copy(wsCallServiceEnabled = value) }
    }

    val liveStateEnabled = SettingState(getFlow().map { it.liveStateEnabled }) { value ->
        update { it.copy(liveStateEnabled = value) }
    }

    val btProxyCleanupEnabled = SettingState(getFlow().map { it.btProxyCleanupEnabled }) { value ->
        update { it.copy(btProxyCleanupEnabled = value) }
    }

    val mediaBearerEnabled = SettingState(getFlow().map { it.mediaBearerEnabled }) { value ->
        update { it.copy(mediaBearerEnabled = value) }
    }

    val historyBackfillEnabled = SettingState(getFlow().map { it.historyBackfillEnabled }) { value ->
        update { it.copy(historyBackfillEnabled = value) }
    }

    val esphomeIdentityExpanded = SettingState(getFlow().map { it.esphomeIdentityExpanded }) { value ->
        update { it.copy(esphomeIdentityExpanded = value) }
    }

    companion object {
        fun normalizeHaUrl(raw: String): String {
            var s = raw.trim().trimEnd('/')
            if (s.isEmpty()) return ""
            if (!s.contains("://")) {
                s = "http://$s"
            }
            if (s.endsWith("/api/websocket", ignoreCase = true)) {
                s = s.dropLast("/api/websocket".length).trimEnd('/')
            }
            return s
        }
    }
}

package com.example.ava.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.dataStore
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

/**
 * Legacy on-device engine ids. Kept so old `local_llm_settings.json` still
 * decodes. The runtime no longer loads Needle / LFM / MiniCPM.
 */
@Serializable
enum class LocalLlmEngine {
    NONE,
    NEEDLE,
    LFM,
    MINICPM,
}

/** Exclusive rear-guard seat after Home Assistant's pipeline. */
@Serializable
enum class LocalLlmPath {
    HA,
    REMOTE,
    /** Legacy. [resolvedPath] folds this into [HA]. */
    LOCAL,
}

fun LocalLlmSettings.resolvedPath(): LocalLlmPath =
    if (path == LocalLlmPath.REMOTE) LocalLlmPath.REMOTE else LocalLlmPath.HA

@Serializable
data class LocalLlmSettings(
    val engine: LocalLlmEngine = LocalLlmEngine.NONE,
    val path: LocalLlmPath = LocalLlmPath.HA,
    val lastOfflineEngine: LocalLlmEngine = LocalLlmEngine.LFM,
    val confidenceThreshold: Float = 0.2f,
    val entityServicesEnabled: Boolean = false,
    val guideDismissed: Boolean = false,
    val overlayDismissed: Boolean = false,
    val installedCatalogVersion: Int = 0,
)

val Context.localLlmSettingsStore: DataStore<LocalLlmSettings> by dataStore(
    fileName = "local_llm_settings.json",
    serializer = SettingsSerializer(LocalLlmSettings.serializer(), LocalLlmSettings()),
    corruptionHandler = defaultCorruptionHandler(LocalLlmSettings()),
)

class LocalLlmSettingsStore(dataStore: DataStore<LocalLlmSettings>) :
    SettingsStoreImpl<LocalLlmSettings>(dataStore, LocalLlmSettings()) {

    val path = SettingState(getFlow().map { it.resolvedPath() }) { value ->
        setPath(value)
    }

    suspend fun setPath(path: LocalLlmPath) {
        update {
            val next = if (path == LocalLlmPath.REMOTE) LocalLlmPath.REMOTE else LocalLlmPath.HA
            it.copy(path = next, engine = LocalLlmEngine.NONE)
        }
    }

    val guideDismissed = SettingState(getFlow().map { it.guideDismissed }) { value ->
        update { it.copy(guideDismissed = value) }
    }

    val overlayDismissed = SettingState(getFlow().map { it.overlayDismissed }) { value ->
        update { it.copy(overlayDismissed = value) }
    }

    suspend fun clearLegacyLocalEngine() {
        update { current ->
            if (current.path != LocalLlmPath.LOCAL && current.engine == LocalLlmEngine.NONE) {
                current
            } else {
                current.copy(
                    path = if (current.path == LocalLlmPath.REMOTE) {
                        LocalLlmPath.REMOTE
                    } else {
                        LocalLlmPath.HA
                    },
                    engine = LocalLlmEngine.NONE,
                )
            }
        }
    }
}

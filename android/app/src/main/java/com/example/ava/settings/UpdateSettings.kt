package com.example.ava.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.dataStore
import kotlinx.serialization.Serializable

enum class UpdateDownloadMethod(val storageKey: String) {
    BUILTIN("builtin"),
    SYSTEM("system"),
    ;

    companion object {
        fun fromStored(value: String?): UpdateDownloadMethod =
            entries.firstOrNull { it.storageKey.equals(value, ignoreCase = true) } ?: BUILTIN
    }
}

@Serializable
data class UpdateSettings(
    /** When true, newest stable from version.json is installed automatically (hourly / launch, no popup). */
    val autoUpdate: Boolean = false,
    /** When true, also check shortly after launch (HA entity only; no popup). */
    val checkOnLaunch: Boolean = true,
    /** When true, suppress launch checks and auto-install (manual page still works). */
    val ignoreUpdate: Boolean = false,
    /** When true, expose firmware update entity to Home Assistant. */
    val haUpdateEntity: Boolean = true,
    /** After silent install, try to reopen Ava instead of rebooting. */
    val reopenAfterUpdate: Boolean = true,
    /** After install, best-effort start VoiceSatelliteService (hard path; default off). */
    val startServicesAfterInstall: Boolean = false,
    /** builtin = in-app DownloadManager + install; system = hand off to system downloader. */
    val downloadMethod: String = UpdateDownloadMethod.BUILTIN.storageKey,
) {
    fun resolvedDownloadMethod(): UpdateDownloadMethod = UpdateDownloadMethod.fromStored(downloadMethod)
}

val Context.updateSettingsStore: DataStore<UpdateSettings> by dataStore(
    fileName = "update_settings.json",
    serializer = SettingsSerializer(UpdateSettings.serializer(), UpdateSettings()),
    corruptionHandler = defaultCorruptionHandler(UpdateSettings()),
)

class UpdateSettingsStore(dataStore: DataStore<UpdateSettings>) :
    SettingsStoreImpl<UpdateSettings>(dataStore, UpdateSettings()) {

    suspend fun setAutoUpdate(enabled: Boolean) {
        update { current ->
            if (enabled) current.copy(autoUpdate = true, ignoreUpdate = false)
            else current.copy(autoUpdate = false)
        }
    }

    suspend fun setCheckOnLaunch(enabled: Boolean) {
        update { it.copy(checkOnLaunch = enabled) }
    }

    suspend fun setIgnoreUpdate(enabled: Boolean) {
        update { current ->
            if (enabled) current.copy(ignoreUpdate = true, autoUpdate = false)
            else current.copy(ignoreUpdate = false)
        }
    }

    suspend fun setHaUpdateEntity(enabled: Boolean) {
        update { it.copy(haUpdateEntity = enabled) }
    }

    suspend fun setReopenAfterUpdate(enabled: Boolean) {
        update { it.copy(reopenAfterUpdate = enabled) }
    }

    suspend fun setStartServicesAfterInstall(enabled: Boolean) {
        update { it.copy(startServicesAfterInstall = enabled) }
    }

    suspend fun setDownloadMethod(method: UpdateDownloadMethod) {
        update { it.copy(downloadMethod = method.storageKey) }
    }
}

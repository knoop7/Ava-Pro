package com.example.ava.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.dataStore
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

/**
 * Music Assistant WebSocket API credentials for **frontend** queue scheduling
 * (repeat/shuffle, optional UI progress enhancement via queue clock).
 *
 * Dual-branch: without Mass API login, Sendspin alone owns audio / seek /
 * progress. With Mass API connected, UI progress may be
 * more accurate — this store must never gate the Sendspin protocol path.
 * [authToken] is produced by `auth/login` (or restored for reconnect).
 */
@Serializable
data class MassApiSettings(
    val enabled: Boolean = false,
    /** Base URL, e.g. `http://192.168.1.10:8095` (no `/ws`). */
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    /** Access token from successful login; used for reconnect. */
    val authToken: String = "",
    /** Android KeyChain alias, or [com.example.ava.massapi.MassApiClientCertChooser.PKCS12_ALIAS]. */
    val clientCertAlias: String = "",
    /** Password for the app-private PKCS#12 file; unused for KeyChain aliases. */
    val clientCertPassword: String = "",
)

val Context.massApiSettingsStore: DataStore<MassApiSettings> by dataStore(
    fileName = "mass_api_settings.json",
    serializer = SettingsSerializer(MassApiSettings.serializer(), MassApiSettings()),
    corruptionHandler = defaultCorruptionHandler(MassApiSettings()),
)

class MassApiSettingsStore(dataStore: DataStore<MassApiSettings>) :
    SettingsStoreImpl<MassApiSettings>(dataStore, MassApiSettings()) {

    val enabled = SettingState(getFlow().map { it.enabled }) { value ->
        update { it.copy(enabled = value) }
    }

    val serverUrl = SettingState(getFlow().map { it.serverUrl }) { value ->
        update { it.copy(serverUrl = normalizeServerUrl(value)) }
    }

    val username = SettingState(getFlow().map { it.username }) { value ->
        update { it.copy(username = value.trim()) }
    }

    val password = SettingState(getFlow().map { it.password }) { value ->
        update { it.copy(password = value) }
    }

    val authToken = SettingState(getFlow().map { it.authToken }) { value ->
        update { it.copy(authToken = value.trim()) }
    }

    val clientCertAlias = SettingState(getFlow().map { it.clientCertAlias }) { value ->
        update { it.copy(clientCertAlias = value.trim()) }
    }

    val clientCertPassword = SettingState(getFlow().map { it.clientCertPassword }) { value ->
        update { it.copy(clientCertPassword = value) }
    }

    companion object {
        fun normalizeServerUrl(raw: String): String {
            var s = raw.trim().trimEnd('/')
            if (s.isEmpty()) return ""
            if (!s.contains("://")) {
                s = "http://$s"
            }
            if (s.endsWith("/ws", ignoreCase = true)) {
                s = s.dropLast(3).trimEnd('/')
            }
            return s
        }
    }
}

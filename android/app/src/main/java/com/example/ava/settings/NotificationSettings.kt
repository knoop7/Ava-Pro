package com.example.ava.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.dataStore
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

object BluetoothPresenceAlertTrigger {
    const val NEARBY = "nearby"
    const val NOT_NEARBY = "not_nearby"
}

object NotificationDisplayStyle {
    const val FULLSCREEN = "fullscreen"
    const val BANNER = "banner"
}

@Serializable
data class NotificationSettings(
    val notificationSceneEnabled: Boolean = false,
    val sceneDisplayDuration: Int = 5000,
    val customSceneUrl: String = "",
    val soundEnabled: Boolean = false,
    val soundUri: String = "",
    /** "fullscreen" | "banner" — global exclusive style. */
    val displayStyle: String = NotificationDisplayStyle.FULLSCREEN,
    /** Banner 3x3 grid 0..8, row-major; default 1 = top center. */
    val bannerPosition: Int = 1,
    /** Banner background hex; empty = default white. */
    val bannerColor: String = "",
    /** Home Assistant watermark in the banner corner. */
    val bannerLogoEnabled: Boolean = true,
)

val Context.notificationSettingsStore: DataStore<NotificationSettings> by dataStore(
    fileName = "notification_settings.json",
    serializer = SettingsSerializer(NotificationSettings.serializer(), NotificationSettings()),
    corruptionHandler = defaultCorruptionHandler(NotificationSettings())
)

class NotificationSettingsStore(dataStore: DataStore<NotificationSettings>) :
    SettingsStoreImpl<NotificationSettings>(dataStore, NotificationSettings()) {
    val notificationSceneEnabled =
        SettingState(getFlow().map { it.notificationSceneEnabled }) { value -> update { it.copy(notificationSceneEnabled = value) } }

    val sceneDisplayDuration =
        SettingState(getFlow().map { it.sceneDisplayDuration }) { value -> update { it.copy(sceneDisplayDuration = value) } }

    val customSceneUrl =
        SettingState(getFlow().map { it.customSceneUrl }) { value -> update { it.copy(customSceneUrl = value) } }

    val soundEnabled =
        SettingState(getFlow().map { it.soundEnabled }) { value -> update { it.copy(soundEnabled = value) } }

    val soundUri =
        SettingState(getFlow().map { it.soundUri }) { value -> update { it.copy(soundUri = value) } }

    val displayStyle =
        SettingState(getFlow().map { it.displayStyle }) { value ->
            update {
                it.copy(
                    displayStyle = when (value) {
                        NotificationDisplayStyle.BANNER -> NotificationDisplayStyle.BANNER
                        else -> NotificationDisplayStyle.FULLSCREEN
                    }
                )
            }
        }

    val bannerPosition =
        SettingState(getFlow().map { it.bannerPosition }) { value ->
            update { it.copy(bannerPosition = value.coerceIn(0, 8)) }
        }

    val bannerColor =
        SettingState(getFlow().map { it.bannerColor }) { value -> update { it.copy(bannerColor = value) } }

    val bannerLogoEnabled =
        SettingState(getFlow().map { it.bannerLogoEnabled }) { value ->
            update { it.copy(bannerLogoEnabled = value) }
        }
}

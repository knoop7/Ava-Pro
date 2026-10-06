package com.example.ava.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.dataStore
import com.example.ava.notifications.NotificationScene
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class LocalScenesSettings(
    val scenes: List<LocalSceneEntry> = emptyList(),
)

/**
 * Local-editable scene. Fields mirror [NotificationScene] / scenes_zh.json so
 * round-trip through [NotificationScene.fromJson] stays lossless.
 */
@Serializable
data class LocalSceneEntry(
    val id: String = "",
    val icon: String = "fa-bell",
    val iconColor: String = "text-amber-200",
    val title: String = "",
    val desc: String = "",
    val subDesc: String = "",
    val themeColors: List<String> = listOf("#f59e0b", "#d97706", "#92400e", "#78350f"),
    val beamColor: String = "rgba(251, 191, 36, 0.8)",
    val dividerColor: String = "rgba(251, 191, 36, 0.8)",
    val dotColor: String = "bg-amber-300",
    val animation: String = "",
    val soundUri: String? = null,
    val soundEnabled: Boolean? = null,
) {
    fun toNotificationScene(): NotificationScene = NotificationScene(
        id = id,
        icon = icon,
        iconColor = iconColor,
        title = title,
        desc = desc,
        subDesc = subDesc,
        themeColors = themeColors.ifEmpty { listOf("#f59e0b", "#d97706", "#92400e", "#78350f") },
        beamColor = beamColor,
        dividerColor = dividerColor,
        dotColor = dotColor,
        animation = animation,
        soundUri = soundUri,
        soundEnabled = soundEnabled,
    )

    companion object {
        fun from(scene: NotificationScene, newId: String = scene.id): LocalSceneEntry = LocalSceneEntry(
            id = newId,
            icon = scene.icon,
            iconColor = scene.iconColor,
            title = scene.title,
            desc = scene.desc,
            subDesc = scene.subDesc,
            themeColors = scene.themeColors,
            beamColor = scene.beamColor,
            dividerColor = scene.dividerColor,
            dotColor = scene.dotColor,
            animation = scene.animation,
            soundUri = scene.soundUri,
            soundEnabled = scene.soundEnabled,
        )

        fun newDraft(): LocalSceneEntry = LocalSceneEntry(
            id = "local_${UUID.randomUUID().toString().take(8)}",
            icon = "fa-bell",
            title = "",
            desc = "",
            subDesc = "",
        )
    }
}

val Context.localScenesSettingsStore: DataStore<LocalScenesSettings> by dataStore(
    fileName = "local_notification_scenes.json",
    serializer = SettingsSerializer(LocalScenesSettings.serializer(), LocalScenesSettings()),
    corruptionHandler = defaultCorruptionHandler(LocalScenesSettings())
)

class LocalScenesStore(store: DataStore<LocalScenesSettings>) :
    SettingsStoreImpl<LocalScenesSettings>(store, LocalScenesSettings()) {

    val scenes = SettingState(getFlow().map { it.scenes }) { value ->
        update { it.copy(scenes = value) }
    }

    suspend fun list(): List<LocalSceneEntry> = get().scenes

    suspend fun getById(id: String): LocalSceneEntry? = list().find { it.id == id }

    /**
     * Insert or replace. Always pins the entry to the front of the store so
     * newly saved / re-saved user scenes (pure local or overlays) stay at the
     * top of the library's user section.
     */
    suspend fun upsert(entry: LocalSceneEntry) {
        update { settings ->
            val next = settings.scenes.toMutableList()
            next.removeAll { it.id == entry.id }
            next.add(0, entry)
            settings.copy(scenes = next)
        }
    }

    suspend fun delete(id: String) {
        update { it.copy(scenes = it.scenes.filterNot { s -> s.id == id }) }
    }

    suspend fun copyFrom(scene: NotificationScene): LocalSceneEntry {
        val entry = LocalSceneEntry.from(
            scene,
            newId = "local_${UUID.randomUUID().toString().take(8)}",
        )
        upsert(entry)
        return entry
    }
}

val Context.localScenesStore: LocalScenesStore
    get() = LocalScenesStore(localScenesSettingsStore)

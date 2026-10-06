package com.example.ava.localllm

import android.content.Context
import com.example.ava.homeassistant.entity.HaEntitySummary
import com.example.ava.settings.PlayerSettingsStore
import com.example.ava.settings.QuickEntitySettingsStore
import com.example.ava.settings.ScreensaverSettingsStore
import com.example.ava.settings.VoiceSatelliteSettingsStore
import com.example.ava.settings.playerSettingsStore
import com.example.ava.settings.quickEntitySettingsStore
import com.example.ava.settings.resolveDawnWeatherEntityId
import com.example.ava.settings.resolveScreensaverWeatherEntityId
import com.example.ava.settings.screensaverSettingsStore
import com.example.ava.settings.voiceSatelliteSettingsStore
import com.example.ava.widgets.AvaSensorWidgetStore

/**
 * Entities the user already filled on this device (quick entity, weather, media,
 * Dawn, screensaver, widgets). Default Needle toolbox — no `get_states`.
 */
object LocalToolEntities {
    suspend fun loadInterest(context: Context): List<HaEntitySummary> {
        val app = context.applicationContext
        val out = LinkedHashMap<String, HaEntitySummary>()
        fun add(entityId: String, name: String = "") {
            val id = entityId.trim()
            if (id.isEmpty() || '.' !in id) return
            val domain = id.substringBefore('.')
            val label = name.trim().ifEmpty { id.substringAfter('.').replace('_', ' ') }
            out.putIfAbsent(id, HaEntitySummary(id, label, domain, "unknown"))
        }

        val quick = QuickEntitySettingsStore(app.quickEntitySettingsStore).get()
        if (quick.enableQuickEntity) {
            for (slot in quick.slots) add(slot.entityId, slot.label)
        }

        val sat = VoiceSatelliteSettingsStore(app.voiceSatelliteSettingsStore).get()
        add(sat.haMediaPlayerEntity)

        val player = PlayerSettingsStore(app.playerSettingsStore).get()
        add(player.haWeatherEntity)
        add(resolveScreensaverWeatherEntityId(player))
        add(player.dreamClockTimerEntityId)
        if (player.enableScreensaver && player.enableScreensaverStatusSlots) {
            for (slot in player.screensaverStatusSlots) add(slot.entityId, slot.label)
        }

        val saver = ScreensaverSettingsStore(app.screensaverSettingsStore).get()
        add(resolveDawnWeatherEntityId(saver))
        if (saver.dawnWallpaperEnabled && saver.enableDawnEntitySlots) {
            for (slot in saver.dawnEntitySlots) add(slot.entityId, slot.label)
        }

        for (id in AvaSensorWidgetStore.entityIds(app)) add(id)
        return out.values.toList()
    }
}

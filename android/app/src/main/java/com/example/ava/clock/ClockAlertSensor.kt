package com.example.ava.clock

import android.content.Context
import com.example.ava.R
import com.example.ava.esphome.entities.DateTimeEntity
import com.example.ava.services.SatelliteRestartReason
import com.example.ava.services.VoiceSatelliteService
import com.example.esphomeproto.api.EntityCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Occupied clock-alert slots as HA `datetime.*` entities (date + time).
 * Names stay 任务事件(闹钟) / 任务事件(提醒)-N. Empty slots are not registered.
 */
object ClockAlertSensor {
    const val SLOT_COUNT = 20

    private val _haItems = MutableStateFlow<List<ClockAlert>>(emptyList())

    @Volatile
    private var holdUntilVoiceEnds = false
    @Volatile
    private var pending: List<ClockAlert>? = null
    @Volatile
    private var haSignature: String? = null

    fun ordered(items: List<ClockAlert>): List<ClockAlert> =
        items
            .filter { it.enabled && it.nextAtMillis > 0L }
            .sortedBy { it.nextAtMillis }
            .take(SLOT_COUNT)

    fun signature(items: List<ClockAlert>): String =
        ordered(items).joinToString("|") { item ->
            val kind = if (item.kind == ClockAlert.Kind.REMINDER) "r" else "a"
            "${item.id}:$kind"
        }

    fun epochSeconds(item: ClockAlert): Long =
        (item.nextAtMillis / 1000L).coerceIn(0L, 0xFFFFFFFFL)

    fun haName(context: Context, item: ClockAlert, index: Int): String {
        val kind = context.getString(
            if (item.kind == ClockAlert.Kind.REMINDER) R.string.clock_alert_title_reminder
            else R.string.clock_alert_title_alarm,
        )
        return if (index == 0) {
            context.getString(R.string.entity_task_event_kind, kind)
        } else {
            context.getString(R.string.entity_task_event_kind_n, kind, index)
        }
    }

    fun objectId(index: Int): String =
        if (index == 0) "task_event" else "task_event_$index"

    fun markVoiceHold() {
        holdUntilVoiceEnds = VoiceSatelliteService.isVoiceTurnActive()
    }

    fun prime(context: Context) {
        val next = ordered(ClockAlertStore.list(context.applicationContext))
        holdUntilVoiceEnds = false
        pending = null
        _haItems.value = next
        haSignature = signature(next)
    }

    fun publish(context: Context) {
        val app = context.applicationContext
        val next = ordered(ClockAlertStore.list(app))
        if (holdUntilVoiceEnds) {
            pending = next
            return
        }
        commitHa(next)
    }

    fun onVoiceEnded() {
        holdUntilVoiceEnds = false
        val held = pending ?: return
        pending = null
        commitHa(held)
    }

    fun applyHaDateTime(context: Context, index: Int, epochSeconds: Long) {
        val app = context.applicationContext
        val item = ordered(ClockAlertStore.list(app)).getOrNull(index) ?: return
        val seconds = epochSeconds.coerceIn(0L, 0xFFFFFFFFL)
        if (seconds == 0L) return
        if (epochSeconds(item) == seconds) return
        holdUntilVoiceEnds = false
        pending = null
        ClockAlertStore.rescheduleTo(app, item.id, seconds * 1000L)
    }

    fun dateTimeEntities(context: Context): List<DateTimeEntity> {
        val app = context.applicationContext
        return ordered(ClockAlertStore.list(app)).mapIndexed { index, item ->
            DateTimeEntity(
                key = "${objectId(index)}:${if (item.kind == ClockAlert.Kind.REMINDER) "r" else "a"}".hashCode(),
                name = haName(app, item, index),
                objectId = objectId(index),
                icon = "mdi:alarm",
                getState = dateTimeFlow(index),
                setState = { epoch -> applyHaDateTime(app, index, epoch) },
                entityCategory = EntityCategory.ENTITY_CATEGORY_CONFIG,
            )
        }
    }

    private fun dateTimeFlow(index: Int): Flow<Long?> =
        _haItems
            .map { list -> list.getOrNull(index)?.let { epochSeconds(it) } }
            .distinctUntilChanged()

    private fun commitHa(next: List<ClockAlert>) {
        val sig = signature(next)
        val topologyChanged = haSignature != null && haSignature != sig
        _haItems.value = next
        haSignature = sig
        if (topologyChanged) {
            VoiceSatelliteService.getInstance()
                ?.restartVoiceSatellite(SatelliteRestartReason.SETTINGS)
        }
    }
}

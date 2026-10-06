package com.example.ava.esphome.entities

import android.net.Uri
import com.example.esphomeproto.api.EntityCategory
import com.example.esphomeproto.api.ListEntitiesRequest
import com.example.esphomeproto.api.ListEntitiesTextResponse
import com.example.esphomeproto.api.TextCommandRequest
import com.example.esphomeproto.api.TextStateResponse
import com.example.esphomeproto.api.TextMode
import com.google.protobuf.MessageLite
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

class TextEntity(
    override val key: Int,
    val name: String,
    val objectId: String,
    val icon: String = "",
    val getState: Flow<String>,
    val setState: suspend (String) -> Unit,
    val entityCategory: EntityCategory = EntityCategory.ENTITY_CATEGORY_NONE,
    /** Home Assistant text entity state max is 255; keep ESPHome declaration aligned. */
    val maxLength: Int = 255,
) : Entity {
    override fun handleMessage(message: MessageLite) = flow {
        when (message) {
            is ListEntitiesRequest -> emit(ListEntitiesTextResponse.newBuilder()
                .setKey(this@TextEntity.key)
                .setName(this@TextEntity.name)
                .setObjectId(this@TextEntity.objectId)
                .also { builder ->
                    if (this@TextEntity.icon.isNotEmpty()) {
                        builder.setIcon(this@TextEntity.icon)
                    }
                    builder.setMode(TextMode.TEXT_MODE_TEXT)
                    builder.setMinLength(0)
                    builder.setMaxLength(maxLength)
                    builder.setEntityCategory(this@TextEntity.entityCategory)
                }
                .build())

            is TextCommandRequest -> {
                if (message.key == key)
                    setState(message.state)
            }
        }
    }

    override fun subscribe() = getState.map { raw ->
        TextStateResponse.newBuilder()
            .setKey(this@TextEntity.key)
            .setState(clipToHaTextLimit(raw, maxLength))
            .setMissingState(false)
            .build()
    }
}

/** Prefer scheme://host:port over mid-string chops when over HA's 255-char text limit. */
private fun clipToHaTextLimit(raw: String, maxLength: Int): String {
    if (raw.length <= maxLength) return raw
    return try {
        val uri = Uri.parse(raw)
        val host = uri.host ?: return raw.take(maxLength)
        val scheme = uri.scheme?.takeIf { it.isNotBlank() } ?: "http"
        val origin = buildString {
            append(scheme).append("://").append(host)
            if (uri.port != -1) append(':').append(uri.port)
        }
        if (origin.length <= maxLength) origin else origin.take(maxLength)
    } catch (_: Exception) {
        raw.take(maxLength)
    }
}

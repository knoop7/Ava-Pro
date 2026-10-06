package com.example.ava.esphome.entities

import com.example.esphomeproto.api.DateTimeCommandRequest
import com.example.esphomeproto.api.DateTimeStateResponse
import com.example.esphomeproto.api.EntityCategory
import com.example.esphomeproto.api.ListEntitiesDateTimeResponse
import com.example.esphomeproto.api.ListEntitiesRequest
import com.google.protobuf.MessageLite
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * ESPHome datetime.datetime → Home Assistant `datetime.*`.
 * Picker always has date and time; state is unix epoch seconds.
 */
class DateTimeEntity(
    override val key: Int,
    val name: String,
    val objectId: String,
    val icon: String = "",
    val getState: Flow<Long?>,
    val setState: suspend (epochSeconds: Long) -> Unit,
    val entityCategory: EntityCategory = EntityCategory.ENTITY_CATEGORY_NONE,
) : Entity {
    override fun handleMessage(message: MessageLite) = flow {
        when (message) {
            is ListEntitiesRequest -> emit(
                ListEntitiesDateTimeResponse.newBuilder()
                    .setKey(this@DateTimeEntity.key)
                    .setName(this@DateTimeEntity.name)
                    .setObjectId(this@DateTimeEntity.objectId)
                    .also { builder ->
                        if (this@DateTimeEntity.icon.isNotEmpty()) {
                            builder.setIcon(this@DateTimeEntity.icon)
                        }
                        builder.setEntityCategory(this@DateTimeEntity.entityCategory)
                    }
                    .build(),
            )
            is DateTimeCommandRequest -> {
                if (message.key == key) {
                    setState(message.epochSeconds.toLong() and 0xFFFFFFFFL)
                }
            }
        }
    }

    override fun subscribe() = getState.map { epochSeconds ->
        val builder = DateTimeStateResponse.newBuilder()
            .setKey(this@DateTimeEntity.key)
            .setMissingState(epochSeconds == null)
        if (epochSeconds != null) {
            builder.setEpochSeconds((epochSeconds and 0xFFFFFFFFL).toInt())
        }
        builder.build()
    }
}

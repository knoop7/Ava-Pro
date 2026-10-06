package com.example.ava.esphome.entities

import com.example.esphomeproto.api.EntityCategory
import com.example.esphomeproto.api.ListEntitiesRequest
import com.example.esphomeproto.api.ListEntitiesNumberResponse
import com.example.esphomeproto.api.NumberCommandRequest
import com.example.esphomeproto.api.NumberStateResponse
import com.example.esphomeproto.api.NumberMode
import com.google.protobuf.MessageLite
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

class NumberEntity(
    override val key: Int,
    val name: String,
    val objectId: String,
    val icon: String = "",
    val minValue: Float = 0f,
    val maxValue: Float = 100f,
    val step: Float = 1f,
    val unitOfMeasurement: String = "",
    val getState: Flow<Float>,
    val setState: suspend (Float) -> Unit,
    val entityCategory: EntityCategory = EntityCategory.ENTITY_CATEGORY_NONE,
    val mode: NumberMode = NumberMode.NUMBER_MODE_BOX
) : Entity {
    override fun handleMessage(message: MessageLite) = flow {
        when (message) {
            is ListEntitiesRequest -> emit(ListEntitiesNumberResponse.newBuilder()
                .setKey(this@NumberEntity.key)
                .setName(this@NumberEntity.name)
                .setObjectId(this@NumberEntity.objectId)
                .also { builder ->
                    if (this@NumberEntity.icon.isNotEmpty()) {
                        builder.setIcon(this@NumberEntity.icon)
                    }
                    builder.setMinValue(this@NumberEntity.minValue)
                    builder.setMaxValue(this@NumberEntity.maxValue)
                    builder.setStep(this@NumberEntity.step)
                    if (this@NumberEntity.unitOfMeasurement.isNotEmpty()) {
                        builder.setUnitOfMeasurement(this@NumberEntity.unitOfMeasurement)
                    }
                    builder.setMode(this@NumberEntity.mode)
                    builder.setEntityCategory(this@NumberEntity.entityCategory)
                }
                .build())

            is NumberCommandRequest -> {
                if (message.key == key)
                    setState(message.state)
            }
        }
    }

    override fun subscribe() = getState.map {
        NumberStateResponse.newBuilder()
            .setKey(this@NumberEntity.key)
            .setState(it)
            .setMissingState(false)
            .build()
    }
}

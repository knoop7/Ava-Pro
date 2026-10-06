package com.example.ava.esphome.entities

import com.example.esphomeproto.api.EntityCategory
import com.example.esphomeproto.api.ListEntitiesRequest
import com.example.esphomeproto.api.listEntitiesBinarySensorResponse
import com.example.esphomeproto.api.binarySensorStateResponse
import com.google.protobuf.MessageLite
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map


class BinarySensorEntity(
    override val key: Int,
    val name: String,
    val objectId: String,
    val deviceClass: String = "", 
    val icon: String = "",
    val getState: Flow<Boolean>,
    val entityCategory: EntityCategory = EntityCategory.ENTITY_CATEGORY_NONE,
    val isStatusBinarySensor: Boolean = false,
    val disabledByDefault: Boolean = false
) : Entity {
    override fun handleMessage(message: MessageLite) = flow {
        when (message) {
            is ListEntitiesRequest -> emit(listEntitiesBinarySensorResponse {
                key = this@BinarySensorEntity.key
                name = this@BinarySensorEntity.name
                objectId = this@BinarySensorEntity.objectId
                if (this@BinarySensorEntity.deviceClass.isNotEmpty()) {
                    deviceClass = this@BinarySensorEntity.deviceClass
                }
                if (this@BinarySensorEntity.icon.isNotEmpty()) {
                    icon = this@BinarySensorEntity.icon
                }
                entityCategory = this@BinarySensorEntity.entityCategory
                isStatusBinarySensor = this@BinarySensorEntity.isStatusBinarySensor
                disabledByDefault = this@BinarySensorEntity.disabledByDefault
            })
        }
    }

    override fun subscribe() = getState.map { state ->
        binarySensorStateResponse {
            key = this@BinarySensorEntity.key
            this.state = state
            missingState = false
        }
    }
}

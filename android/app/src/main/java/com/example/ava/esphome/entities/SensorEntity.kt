package com.example.ava.esphome.entities

import com.example.esphomeproto.api.EntityCategory
import com.example.esphomeproto.api.ListEntitiesRequest
import com.example.esphomeproto.api.SensorStateClass
import com.example.esphomeproto.api.listEntitiesSensorResponse
import com.example.esphomeproto.api.sensorStateResponse
import com.google.protobuf.MessageLite
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

class SensorEntity(
    override val key: Int,
    val name: String,
    val objectId: String,
    val icon: String = "",
    val unitOfMeasurement: String = "",
    val accuracyDecimals: Int = 0,
    val deviceClass: String = "",
    val entityCategory: EntityCategory = EntityCategory.ENTITY_CATEGORY_NONE,
    initialState: Float = 0f,
    hasInitialState: Boolean = true
) : Entity {
    
    private val _state = MutableStateFlow(initialState)
    @Volatile
    private var hasState = hasInitialState
    
    fun updateState(value: Float) {
        hasState = true
        _state.value = value
    }
    
    override fun handleMessage(message: MessageLite) = flow {
        when (message) {
            is ListEntitiesRequest -> emit(listEntitiesSensorResponse {
                key = this@SensorEntity.key
                name = this@SensorEntity.name
                objectId = this@SensorEntity.objectId
                if (this@SensorEntity.icon.isNotEmpty()) {
                    icon = this@SensorEntity.icon
                }
                if (this@SensorEntity.unitOfMeasurement.isNotEmpty()) {
                    unitOfMeasurement = this@SensorEntity.unitOfMeasurement
                }
                accuracyDecimals = this@SensorEntity.accuracyDecimals
                if (this@SensorEntity.deviceClass.isNotEmpty()) {
                    deviceClass = this@SensorEntity.deviceClass
                }
                stateClass = SensorStateClass.STATE_CLASS_MEASUREMENT
                entityCategory = this@SensorEntity.entityCategory
            })
        }
    }

    override fun subscribe(): Flow<MessageLite> = _state.map {
        sensorStateResponse {
            key = this@SensorEntity.key
            state = it
            missingState = !hasState
        }
    }
}

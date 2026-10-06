package com.example.ava.esphome.entities

import com.example.esphomeproto.api.EntityCategory
import com.example.esphomeproto.api.ListEntitiesRequest
import com.example.esphomeproto.api.listEntitiesTextSensorResponse
import com.example.esphomeproto.api.textSensorStateResponse
import com.google.protobuf.MessageLite
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

class TextSensorEntity(
    override val key: Int,
    val name: String,
    val objectId: String,
    val icon: String = "",
    val entityCategory: EntityCategory = EntityCategory.ENTITY_CATEGORY_NONE,
    initialState: String = "",
    hasInitialState: Boolean = true
) : Entity {
    
    private val _state = MutableStateFlow(initialState)
    private val _forceEmit = MutableSharedFlow<String>(extraBufferCapacity = 1)
    @Volatile
    private var hasState = hasInitialState
    
    fun updateState(value: String) {
        hasState = true
        _state.value = value
    }
    
    fun forceUpdateState(value: String) {
        hasState = true
        _state.value = value
        _forceEmit.tryEmit(value)
    }
    
    override fun handleMessage(message: MessageLite) = flow {
        when (message) {
            is ListEntitiesRequest -> emit(listEntitiesTextSensorResponse {
                key = this@TextSensorEntity.key
                name = this@TextSensorEntity.name
                objectId = this@TextSensorEntity.objectId
                if (this@TextSensorEntity.icon.isNotEmpty()) {
                    icon = this@TextSensorEntity.icon
                }
                entityCategory = this@TextSensorEntity.entityCategory
            })
        }
    }

    override fun subscribe(): Flow<MessageLite> = merge(
        _state.map { value ->
            textSensorStateResponse {
                key = this@TextSensorEntity.key
                state = value
                missingState = !hasState
            }
        },
        _forceEmit.map { value ->
            textSensorStateResponse {
                key = this@TextSensorEntity.key
                state = value
                missingState = !hasState
            }
        }
    )
}

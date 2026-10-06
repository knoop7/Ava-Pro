package com.example.ava.esphome.entities

import android.util.Log
import com.example.esphomeproto.api.EntityCategory
import com.example.esphomeproto.api.ListEntitiesRequest
import com.example.esphomeproto.api.SwitchCommandRequest
import com.example.esphomeproto.api.listEntitiesSwitchResponse
import com.example.esphomeproto.api.switchStateResponse
import com.google.protobuf.MessageLite
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

class SwitchEntity(
    override val key: Int,
    val name: String,
    val objectId: String,
    val icon: String = "",
    val getState: Flow<Boolean>,
    val entityCategory: EntityCategory = EntityCategory.ENTITY_CATEGORY_NONE,
    /** When set, the command reply uses this value instead of the requested one. */
    val acceptedState: (() -> Boolean)? = null,
    val setState: suspend (Boolean) -> Unit
) : Entity {
    override fun handleMessage(message: MessageLite) = flow {
        when (message) {
            is ListEntitiesRequest -> emit(listEntitiesSwitchResponse {
                key = this@SwitchEntity.key
                name = this@SwitchEntity.name
                objectId = this@SwitchEntity.objectId
                if (this@SwitchEntity.icon.isNotEmpty()) {
                    icon = this@SwitchEntity.icon
                }
                entityCategory = this@SwitchEntity.entityCategory
            })

            is SwitchCommandRequest -> {
                if (message.key == key) {
                    Log.d("SwitchEntity", "SwitchCommand received: key=$key, objectId=$objectId, state=${message.state}")
                    setState(message.state)
                    val reported = acceptedState?.invoke() ?: message.state
                    emit(switchStateResponse {
                        key = this@SwitchEntity.key
                        state = reported
                    })
                }
            }
        }
    }

    override fun subscribe() = getState.map {
        switchStateResponse {
            key = this@SwitchEntity.key
            this.state = it
        }
    }
}
package com.example.ava.esphome.entities

import com.example.esphomeproto.api.EntityCategory
import com.example.esphomeproto.api.ListEntitiesRequest
import com.example.esphomeproto.api.LockCommand
import com.example.esphomeproto.api.LockCommandRequest
import com.example.esphomeproto.api.LockState
import com.example.esphomeproto.api.listEntitiesLockResponse
import com.example.esphomeproto.api.lockStateResponse
import com.google.protobuf.MessageLite
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

class LockEntity(
    override val key: Int,
    val name: String,
    val objectId: String,
    val icon: String = "",
    val getState: Flow<LockState>,
    val entityCategory: EntityCategory = EntityCategory.ENTITY_CATEGORY_NONE,
    val supportsOpen: Boolean = false,
    val assumedState: Boolean = false,
    val setState: suspend (LockCommand, String?) -> Unit
) : Entity {
    override fun handleMessage(message: MessageLite) = flow {
        when (message) {
            is ListEntitiesRequest -> emit(listEntitiesLockResponse {
                key = this@LockEntity.key
                name = this@LockEntity.name
                objectId = this@LockEntity.objectId
                if (this@LockEntity.icon.isNotEmpty()) {
                    icon = this@LockEntity.icon
                }
                entityCategory = this@LockEntity.entityCategory
                supportsOpen = this@LockEntity.supportsOpen
                assumedState = this@LockEntity.assumedState
            })

            is LockCommandRequest -> {
                if (message.key == key) {
                    val code = if (message.hasCode) message.code else null
                    setState(message.command, code)
                    emit(lockStateResponse {
                        key = this@LockEntity.key
                        state = when (message.command) {
                            LockCommand.LOCK_LOCK -> LockState.LOCK_STATE_LOCKED
                            LockCommand.LOCK_UNLOCK -> LockState.LOCK_STATE_UNLOCKED
                            LockCommand.LOCK_OPEN -> LockState.LOCK_STATE_UNLOCKED
                            else -> LockState.LOCK_STATE_NONE
                        }
                    })
                }
            }
        }
    }

    override fun subscribe() = getState.map {
        lockStateResponse {
            key = this@LockEntity.key
            state = it
        }
    }
}

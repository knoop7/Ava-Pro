package com.example.ava.esphome.entities

import android.util.Log
import com.example.esphomeproto.api.ButtonCommandRequest
import com.example.esphomeproto.api.EntityCategory
import com.example.esphomeproto.api.ListEntitiesRequest
import com.example.esphomeproto.api.listEntitiesButtonResponse
import com.google.protobuf.MessageLite
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow

class ButtonEntity(
    override val key: Int,
    val name: String,
    val objectId: String,
    val icon: String = "",
    val entityCategory: EntityCategory = EntityCategory.ENTITY_CATEGORY_NONE,
    val onPress: suspend () -> Unit
) : Entity {
    override fun handleMessage(message: MessageLite) = flow {
        when (message) {
            is ListEntitiesRequest -> emit(listEntitiesButtonResponse {
                key = this@ButtonEntity.key
                name = this@ButtonEntity.name
                objectId = this@ButtonEntity.objectId
                if (this@ButtonEntity.icon.isNotEmpty()) {
                    icon = this@ButtonEntity.icon
                }
                entityCategory = this@ButtonEntity.entityCategory
            })

            is ButtonCommandRequest -> {
                if (message.key == key) {
                    Log.d(TAG, "ButtonCommand received: key=$key, objectId=$objectId")
                    onPress()
                }
            }
        }
    }

    
    override fun subscribe(): Flow<MessageLite> = emptyFlow()

    companion object {
        private const val TAG = "ButtonEntity"
    }
}

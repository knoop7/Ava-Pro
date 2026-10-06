package com.example.ava.esphome.entities

import com.example.esphomeproto.api.EntityCategory
import com.example.esphomeproto.api.ListEntitiesRequest
import com.example.esphomeproto.api.ListEntitiesSelectResponse
import com.example.esphomeproto.api.SelectCommandRequest
import com.example.esphomeproto.api.SelectStateResponse
import com.google.protobuf.MessageLite
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map


class SelectEntity(
    override val key: Int,
    val name: String,
    val objectId: String,
    val icon: String = "",
    val options: List<String>,
    val getState: Flow<String>,
    val setState: suspend (String) -> Unit,
    val getOptions: (() -> List<String>)? = null,
    val entityCategory: EntityCategory = EntityCategory.ENTITY_CATEGORY_NONE
) : Entity {
    
    
    private fun getCurrentOptions(): List<String> {
        return getOptions?.invoke() ?: options
    }
    
    override fun handleMessage(message: MessageLite) = flow {
        when (message) {
            is ListEntitiesRequest -> emit(
                ListEntitiesSelectResponse.newBuilder()
                    .setKey(this@SelectEntity.key)
                    .setName(this@SelectEntity.name)
                    .setObjectId(this@SelectEntity.objectId)
                    .setEntityCategory(this@SelectEntity.entityCategory)
                    .also { builder ->
                        if (this@SelectEntity.icon.isNotEmpty()) {
                            builder.setIcon(this@SelectEntity.icon)
                        }
                        getCurrentOptions().forEach { option ->
                            builder.addOptions(option)
                        }
                    }
                    .build()
            )

            is SelectCommandRequest -> {
                if (message.key == key) {
                    setState(message.state)
                }
            }
        }
    }

    override fun subscribe(): Flow<MessageLite> = getState.map {
        SelectStateResponse.newBuilder()
            .setKey(this@SelectEntity.key)
            .setState(it)
            .setMissingState(false)
            .build()
    }
}


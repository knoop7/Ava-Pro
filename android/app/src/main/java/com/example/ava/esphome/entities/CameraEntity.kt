package com.example.ava.esphome.entities

import com.example.esphomeproto.api.CameraImageRequest
import com.example.esphomeproto.api.EntityCategory
import com.example.esphomeproto.api.ListEntitiesRequest
import com.example.esphomeproto.api.cameraImageResponse
import com.example.esphomeproto.api.listEntitiesCameraResponse
import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow

class CameraEntity(
    override val key: Int,
    val name: String,
    val objectId: String,
    val icon: String = "",
    val entityCategory: EntityCategory = EntityCategory.ENTITY_CATEGORY_NONE
) : Entity {

    // DROP_OLDEST so a late HA collector / full buffer cannot silently drop a fresh screenshot.
    private val _imageFlow = MutableSharedFlow<ByteArray>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    @Volatile private var isStreaming = false
    @Volatile private var isSending = false

    fun sendImage(jpegData: ByteArray) {
        _imageFlow.tryEmit(jpegData)
    }

    override fun handleMessage(message: MessageLite) = flow {
        when (message) {
            is ListEntitiesRequest -> emit(listEntitiesCameraResponse {
                key = this@CameraEntity.key
                name = this@CameraEntity.name
                objectId = this@CameraEntity.objectId
                if (this@CameraEntity.icon.isNotEmpty()) {
                    icon = this@CameraEntity.icon
                }
                entityCategory = this@CameraEntity.entityCategory
            })

            is CameraImageRequest -> {
                if (message.single) {
                    val imageData = _imageFlow.replayCache.lastOrNull()
                    if (imageData != null) {
                        emitImageChunks(imageData)
                    }
                }
                // HA sets stream=true while the camera view is open; false when it closes.
                isStreaming = message.stream
            }
        }
    }

    override fun subscribe(): Flow<MessageLite> = flow {
        _imageFlow.collect { imageData ->
            if (!isSending) {
                isSending = true
                try {
                    emitImageChunks(imageData)
                } finally {
                    isSending = false
                }
            }
        }
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<MessageLite>.emitImageChunks(imageData: ByteArray) {
        val chunkSize = 1024
        val chunks = imageData.toList().chunked(chunkSize)
        chunks.forEachIndexed { index, chunk ->
            emit(cameraImageResponse {
                key = this@CameraEntity.key
                data = ByteString.copyFrom(chunk.toByteArray())
                done = (index == chunks.lastIndex)
            })
        }
    }
}

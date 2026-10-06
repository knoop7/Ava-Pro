package com.example.ava.esphome.entities

import android.util.Log
import com.example.esphomeproto.api.EntityCategory
import com.example.esphomeproto.api.ListEntitiesRequest
import com.example.esphomeproto.api.UpdateCommand
import com.example.esphomeproto.api.UpdateCommandRequest
import com.example.esphomeproto.api.listEntitiesUpdateResponse
import com.example.esphomeproto.api.updateStateResponse
import com.google.protobuf.MessageLite
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

data class UpdateEntityState(
    val inProgress: Boolean = false,
    val hasProgress: Boolean = false,
    val progress: Float = 0f,
    val currentVersion: String = "",
    val latestVersion: String = "",
    val title: String = "",
    val releaseSummary: String = "",
    val releaseUrl: String = "",
)

/**
 * ESPHome Native API update entity (messages 116–118).
 * Home Assistant maps this to `update.*` and can call CHECK / INSTALL remotely.
 */
class UpdateEntity(
    override val key: Int,
    val name: String,
    val objectId: String,
    val icon: String = "mdi:cellphone-arrow-down",
    val deviceClass: String = "firmware",
    val entityCategory: EntityCategory = EntityCategory.ENTITY_CATEGORY_DIAGNOSTIC,
    private val onCheck: suspend () -> Unit,
    private val onInstall: suspend () -> Unit,
) : Entity {
    private val _state = MutableStateFlow(UpdateEntityState())

    fun publish(state: UpdateEntityState) {
        _state.value = state
    }

    fun current(): UpdateEntityState = _state.value

    override fun handleMessage(message: MessageLite) = flow {
        when (message) {
            is ListEntitiesRequest -> emit(listEntitiesUpdateResponse {
                key = this@UpdateEntity.key
                name = this@UpdateEntity.name
                objectId = this@UpdateEntity.objectId
                if (this@UpdateEntity.icon.isNotEmpty()) {
                    icon = this@UpdateEntity.icon
                }
                entityCategory = this@UpdateEntity.entityCategory
                if (this@UpdateEntity.deviceClass.isNotEmpty()) {
                    deviceClass = this@UpdateEntity.deviceClass
                }
            })

            is UpdateCommandRequest -> {
                if (message.key != key) return@flow
                when (message.command) {
                    UpdateCommand.UPDATE_COMMAND_CHECK -> {
                        Log.i(TAG, "UpdateCommand CHECK for $objectId")
                        onCheck()
                    }
                    UpdateCommand.UPDATE_COMMAND_UPDATE -> {
                        Log.i(TAG, "UpdateCommand UPDATE for $objectId")
                        onInstall()
                    }
                    else -> Log.d(TAG, "Ignoring UpdateCommand ${message.command}")
                }
                emit(toStateResponse(_state.value))
            }
        }
    }

    override fun subscribe(): Flow<MessageLite> = _state.map { toStateResponse(it) }

    private fun toStateResponse(state: UpdateEntityState) = updateStateResponse {
        key = this@UpdateEntity.key
        missingState = false
        inProgress = state.inProgress
        hasProgress = state.hasProgress
        progress = state.progress
        currentVersion = state.currentVersion
        latestVersion = state.latestVersion
        title = state.title
        releaseSummary = state.releaseSummary
        releaseUrl = state.releaseUrl
    }

    companion object {
        private const val TAG = "UpdateEntity"
    }
}

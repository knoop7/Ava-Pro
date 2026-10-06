package com.example.ava.esphome.entities

import com.google.protobuf.MessageLite
import kotlinx.coroutines.flow.Flow

interface Entity {
    val key: Int
    fun handleMessage(message: MessageLite): Flow<MessageLite>
    fun subscribe(): Flow<MessageLite>
}
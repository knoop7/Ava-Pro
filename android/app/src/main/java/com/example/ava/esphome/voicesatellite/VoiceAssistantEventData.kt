package com.example.ava.esphome.voicesatellite

import com.example.esphomeproto.api.VoiceAssistantEventResponse

internal fun VoiceAssistantEventResponse.stringField(name: String): String? =
    dataList.firstOrNull { it.name == name }?.value?.takeIf { it.isNotBlank() }

internal fun VoiceAssistantEventResponse.flagField(name: String): Boolean {
    return when (stringField(name)) {
        "1", "true", "True", "TRUE" -> true
        "0", "false", "False", "FALSE" -> false
        else -> false
    }
}

package com.example.ava.permissions

import android.Manifest
import android.os.Build

fun getVoiceSatellitePermissions(voiceChannelEnabled: Boolean): Array<String> {
    val permissions = mutableListOf<String>()
    if (voiceChannelEnabled) {
        permissions += Manifest.permission.RECORD_AUDIO
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        permissions += Manifest.permission.POST_NOTIFICATIONS
    }
    permissions += Manifest.permission.CAMERA
    return permissions.toTypedArray()
}

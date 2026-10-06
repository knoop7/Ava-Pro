package com.example.ava.nsd

import android.content.Context

private const val VERSION = "2025.9.0"

fun registerVoiceSatelliteNsd(
    context: Context,
    name: String,
    port: Int,
    macAddress: String,
    encryptionEnabled: Boolean = false,
): NsdRegistration {
    val attributes = mutableMapOf(
        "version" to VERSION,
        "mac" to macAddress,
        "board" to "host",
        "platform" to "HOST",
        "network" to "wifi",
    )
    if (encryptionEnabled) {
        attributes["api_encryption"] = "NOISE"
    }
    val nsdRegistration = NsdRegistration(
        name = name,
        type = "_esphomelib._tcp",
        port = port,
        attributes = attributes,
    )
    nsdRegistration.register(context)
    return nsdRegistration
}
package com.example.ava.mods

import android.Manifest
import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins host classification against every published ava-mods package.
 * Install-time / AppOps tokens must not enter RequestMultiplePermissions.
 */
class ModPermissionsTest {

    @Test
    fun publishedModsOnlyRequestDangerousRuntimePermissions() {
        publishedMods.forEach { mod ->
            val leftover = leftoverRuntime(mod.required)
            if (mod.id == "ble-adv-proxy") {
                assertTrue(mod.id, leftover.isNotEmpty())
                leftover.forEach { permission ->
                    assertTrue(
                        "${mod.id}: $permission",
                        permission == Manifest.permission.ACCESS_FINE_LOCATION ||
                            permission == Manifest.permission.BLUETOOTH_SCAN ||
                            permission == Manifest.permission.BLUETOOTH_CONNECT ||
                            permission == Manifest.permission.BLUETOOTH_ADVERTISE,
                    )
                }
            } else {
                assertEquals(mod.id, mod.expectedRuntime, leftover)
            }
        }
    }

    @Test
    fun optionalPrivilegedPermissionsNeverLookLikeRuntime() {
        val optional = listOf(
            Manifest.permission.WRITE_SECURE_SETTINGS,
            Manifest.permission.READ_LOGS,
            Manifest.permission.CAMERA,
        )
        val leftover = leftoverRuntime(optional)
        assertEquals(listOf(Manifest.permission.CAMERA), leftover)
        assertTrue(ModPermissions.requiresPrivilegedGrant(Manifest.permission.WRITE_SECURE_SETTINGS))
        assertTrue(ModPermissions.requiresPrivilegedGrant(Manifest.permission.READ_LOGS))
    }

    @Test
    fun deniedInstallTimeAndOverlayAreNotMissingRuntime() {
        assertTrue(
            ModPermissions.missingRuntimePermissions(
                packageManager = { PackageManager.PERMISSION_DENIED },
                permissions = ModPermissions.resolve(
                    listOf(
                        Manifest.permission.INTERNET,
                        Manifest.permission.ACCESS_WIFI_STATE,
                        Manifest.permission.CHANGE_WIFI_MULTICAST_STATE,
                        Manifest.permission.SYSTEM_ALERT_WINDOW,
                        Manifest.permission.USE_BIOMETRIC,
                        Manifest.permission.TRANSMIT_IR,
                    ),
                ),
            ).isEmpty(),
        )
    }

    @Test
    fun cameraAndLocationStillNeedRuntimeGrant() {
        assertTrue(ModPermissions.requiresRuntimeGrant(Manifest.permission.CAMERA))
        assertTrue(ModPermissions.requiresRuntimeGrant(Manifest.permission.ACCESS_FINE_LOCATION))
        assertTrue(ModPermissions.requiresRuntimeGrant(Manifest.permission.RECORD_AUDIO))
    }

    private fun leftoverRuntime(tokens: List<String>?): List<String> {
        return ModPermissions.resolve(tokens).filter(ModPermissions::requiresRuntimeGrant)
    }

    private data class PublishedMod(
        val id: String,
        val required: List<String>?,
        val expectedRuntime: List<String>,
    )

    /**
     * Required `permissions` from each ava-mods `mods/<id>/manifest.json`.
     * (Kotlin block comments nest, so a literal glob with `slash-star-star`
     * inside a KDoc opens a comment that swallows the rest of the file.)
     * Mods with no permissions key are treated as empty, matching ModManager.
     */
    private val publishedMods = listOf(
        PublishedMod("a64-device-support", null, emptyList()),
        PublishedMod(
            "airplay-receiver",
            listOf(
                Manifest.permission.INTERNET,
                Manifest.permission.ACCESS_NETWORK_STATE,
                Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.CHANGE_WIFI_MULTICAST_STATE,
                Manifest.permission.WAKE_LOCK,
                Manifest.permission.SYSTEM_ALERT_WINDOW,
            ),
            emptyList(),
        ),
        PublishedMod(
            "biometric-auth",
            listOf(
                Manifest.permission.USE_BIOMETRIC,
                Manifest.permission.USE_FINGERPRINT,
            ),
            emptyList(),
        ),
        PublishedMod(
            "ble-adv-proxy",
            listOf("bluetooth_scan", "bluetooth_connect", "bluetooth_advertise"),
            emptyList(),
        ),
        PublishedMod(
            "camera-stream-mod",
            listOf(
                "camera",
                Manifest.permission.INTERNET,
                Manifest.permission.ACCESS_NETWORK_STATE,
                Manifest.permission.ACCESS_WIFI_STATE,
            ),
            listOf(Manifest.permission.CAMERA),
        ),
        PublishedMod("connectivity-keepalive", emptyList(), emptyList()),
        PublishedMod(
            "dlna-renderer",
            listOf(
                Manifest.permission.INTERNET,
                Manifest.permission.ACCESS_NETWORK_STATE,
                Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.CHANGE_WIFI_MULTICAST_STATE,
                Manifest.permission.WAKE_LOCK,
                Manifest.permission.SYSTEM_ALERT_WINDOW,
            ),
            emptyList(),
        ),
        PublishedMod("echo-show-support", null, emptyList()),
        PublishedMod(
            "flashlight-mod",
            listOf(Manifest.permission.CAMERA),
            listOf(Manifest.permission.CAMERA),
        ),
        PublishedMod(
            "gps-mod",
            listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
            listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
        ),
        PublishedMod(
            "ha-edge-tts",
            listOf(
                Manifest.permission.INTERNET,
                Manifest.permission.ACCESS_NETWORK_STATE,
            ),
            emptyList(),
        ),
        PublishedMod(
            "ha-stt-engine",
            listOf(
                Manifest.permission.INTERNET,
                Manifest.permission.ACCESS_NETWORK_STATE,
            ),
            emptyList(),
        ),
        PublishedMod(
            "ir-blaster-support",
            listOf(
                Manifest.permission.INTERNET,
                Manifest.permission.ACCESS_NETWORK_STATE,
                Manifest.permission.TRANSMIT_IR,
            ),
            emptyList(),
        ),
        PublishedMod(
            "mimiclaw-ai-assistant",
            listOf(
                Manifest.permission.INTERNET,
                Manifest.permission.ACCESS_NETWORK_STATE,
            ),
            emptyList(),
        ),
        PublishedMod("phicomm-r1-support", null, emptyList()),
        PublishedMod(
            "portal-support",
            listOf(Manifest.permission.RECORD_AUDIO),
            listOf(Manifest.permission.RECORD_AUDIO),
        ),
        PublishedMod("qualcomm-audio-concurrency-fix", emptyList(), emptyList()),
        PublishedMod("screen-capture-mod", emptyList(), emptyList()),
        PublishedMod("screen-color-filter", null, emptyList()),
        PublishedMod("sticky-note", null, emptyList()),
        PublishedMod("tuya-s8e-support", null, emptyList()),
        PublishedMod("yx-led-controller", null, emptyList()),
        PublishedMod("zigbee-gateway-mod", null, emptyList()),
    )
}

package com.example.ava.mods

import android.content.Context
import android.util.Log
import com.example.ava.services.VoiceSatelliteService

/**
 * Headless mod control via [com.example.ava.receivers.AvaControlReceiver].
 *
 * Enabling or reloading a mod updates registry / ClassLoader state; when the voice satellite
 * service is running, the satellite is restarted so mod entities and voice-pipeline hooks load.
 * [EXTRA_NO_RESTART] skips that restart. The satellite still drops disabled-mod entities
 * when the registry generation changes, so a sensor refresh cannot start the mod again.
 */
object AvaModControl {
    private const val TAG = "AvaModControl"

    const val EXTRA_MOD_ID = "mod_id"
    const val EXTRA_MOD_ENABLED = "mod_enabled"
    const val EXTRA_NO_RESTART = "no_restart"

    data class ControlResult(
        val success: Boolean,
        val message: String,
    )

    suspend fun setModEnabled(
        context: Context,
        modId: String?,
        enabled: Boolean,
        noRestart: Boolean,
    ): ControlResult {
        val normalizedId = modId?.trim().orEmpty()
        if (normalizedId.isEmpty()) {
            return ControlResult(false, "mod_id is required")
        }

        val modManager = ModManager.getInstance(context.applicationContext)
        modManager.refreshRegistryFromDisk().onFailure { error ->
            return ControlResult(false, error.message ?: "Failed to read registry")
        }

        if (!modManager.isInstalled(normalizedId)) {
            return ControlResult(false, "Mod not installed: $normalizedId")
        }

        val result = modManager.setModEnabled(normalizedId, enabled)
        if (result.isFailure) {
            val message = result.exceptionOrNull()?.message ?: "Failed to set mod enabled=$enabled"
            Log.w(TAG, message)
            return ControlResult(false, message)
        }

        if (enabled) {
            // Headless enable (adb / fleet) must still prompt Accessibility for screenshot mods.
            ModScreenCapture.ensureAccessibilityForMod(context.applicationContext, normalizedId)
        }

        maybeRestartSatellite(noRestart)
        Log.i(TAG, "Set mod $normalizedId enabled=$enabled")
        return ControlResult(true, "ok")
    }

    /**
     * Reload one mod, or all currently enabled mods when [modId] is null/blank.
     * Only enabled mods are reloaded; disabled mods are left unchanged.
     */
    suspend fun reloadMod(
        context: Context,
        modId: String?,
        noRestart: Boolean,
    ): ControlResult {
        val modManager = ModManager.getInstance(context.applicationContext)
        modManager.refreshRegistryFromDisk().onFailure { error ->
            return ControlResult(false, error.message ?: "Failed to read registry")
        }

        val targetIds = try {
            resolveReloadTargets(modManager, modId?.trim().orEmpty())
        } catch (e: IllegalArgumentException) {
            return ControlResult(false, e.message ?: "Invalid mod_id")
        }
        if (targetIds.isEmpty()) {
            return ControlResult(false, "No mods to reload")
        }

        for (id in targetIds) {
            val status = modManager.reloadModSync(id)
            if (status != "ok") {
                Log.w(TAG, "Reload failed for $id: $status")
                return ControlResult(false, "$id: $status")
            }
        }

        maybeRestartSatellite(noRestart)
        Log.i(TAG, "Reloaded mod(s): ${targetIds.joinToString()}")
        return ControlResult(true, "ok")
    }

    private fun resolveReloadTargets(modManager: ModManager, modId: String): List<String> {
        if (modId.isNotEmpty()) {
            if (!modManager.isInstalled(modId)) {
                throw IllegalArgumentException("Mod not installed: $modId")
            }
            if (!modManager.isEnabled(modId)) {
                throw IllegalArgumentException("Mod is disabled: $modId")
            }
            return listOf(modId)
        }
        return modManager.installedMods.value
            .filter { it.enabled }
            .map { it.id }
    }

    private fun maybeRestartSatellite(noRestart: Boolean) {
        if (noRestart) return
        val service = VoiceSatelliteService.getInstance()
        if (service == null) {
            Log.i(TAG, "Service not running; mod state will apply on next start")
            return
        }
        service.restartVoiceSatellite()
    }
}

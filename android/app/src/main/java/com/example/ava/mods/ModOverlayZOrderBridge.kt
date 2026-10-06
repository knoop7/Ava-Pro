package com.example.ava.mods

import android.content.Context
import android.util.Log

/**
 * Optional overlay z-order hook bridge for enabled mods.
 *
 * Two tiers share the same manager hook method [HOOK_METHOD]:
 * - [overlay_below_voice]: DLNA Cinema and similar media overlays — above dashboard,
 *   below wake ripple / voice UI. Only mods that declare this flag are reasserted here.
 * - [overlay_z_order]: global tint (screen color filter) — reasserted last, above all
 *   Ava foreground overlays including wake ripple and notifications.
 *
 * Zero cost when no enabled mod declares either flag — cached probes avoid manifest scans on
 * every [com.example.ava.services.OverlayZOrderCoordinator] reassert.
 */
object ModOverlayZOrderBridge {
    private const val TAG = "ModOverlayZOrder"
    private const val HOOK_METHOD = "bringOverlayToFrontIfActive"

    /** null = unknown */
    @Volatile
    private var hasBelowVoiceHookCache: Boolean? = null

    @Volatile
    private var hasTopHookCache: Boolean? = null

    fun invalidateCache() {
        hasBelowVoiceHookCache = null
        hasTopHookCache = null
    }

    /** Media-style mod overlays: above dashboard, below wake ripple and voice UI. */
    fun reassertBelowVoiceOverlays(context: Context) {
        reassertTier(context, belowVoice = true)
    }

    /** Global mod overlays (screen tint, etc.): above all Ava foreground overlays. */
    fun reassertTopOverlays(context: Context) {
        reassertTier(context, belowVoice = false)
    }

    /** @deprecated Use [reassertTopOverlays]; kept for existing call sites. */
    fun reassertModOverlays(context: Context) {
        reassertTopOverlays(context)
    }

    private fun reassertTier(context: Context, belowVoice: Boolean) {
        val cache = if (belowVoice) hasBelowVoiceHookCache else hasTopHookCache
        if (cache == false) return

        val appContext = context.applicationContext
        val modManager = ModManager.getInstance(appContext)
        val overlayMods = modManager.getEnabledManifests().filter { manifest ->
            if (belowVoice) manifest.overlayBelowVoice else manifest.overlayZOrder
        }
        if (overlayMods.isEmpty()) {
            if (belowVoice) {
                hasBelowVoiceHookCache = false
            } else {
                hasTopHookCache = false
            }
            return
        }

        if (belowVoice) {
            hasBelowVoiceHookCache = true
        } else {
            hasTopHookCache = true
        }
        for (manifest in overlayMods) {
            invokeHook(appContext, modManager, manifest.id, manifest.manager)
        }
    }

    private fun invokeHook(
        context: Context,
        modManager: ModManager,
        modId: String,
        managerClassName: String?,
    ) {
        if (managerClassName.isNullOrBlank()) return

        runCatching {
            val classLoader = modManager.getModClassLoader(modId) ?: return
            val managerClass = classLoader.loadClass(managerClassName)
            val hookMethod = runCatching {
                managerClass.getMethod(HOOK_METHOD, Context::class.java)
            }.getOrNull() ?: return

            val getInstance = managerClass.getMethod("getInstance", Context::class.java)
            val instance = getInstance.invoke(null, context) ?: return
            hookMethod.invoke(instance, context)
        }.onFailure { e ->
            Log.w(TAG, "Overlay z-order hook failed for $modId", e)
        }
    }
}

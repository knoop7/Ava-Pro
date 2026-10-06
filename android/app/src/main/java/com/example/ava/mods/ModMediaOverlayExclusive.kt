package com.example.ava.mods

import android.content.Context
import android.util.Log

/**
 * True while an enabled mod owns an exclusive media surface (AirPlay cinema,
 * DLNA Cinema, …). Host Sendspin / VinylCover FAB must yield — they are a
 * separate UI and must not stack or steal z-order.
 *
 * Mod opt-in: manager implements `boolean isExclusiveMediaOverlayActive(Context)`.
 */
object ModMediaOverlayExclusive {
    private const val TAG = "ModMediaExclusive"
    private const val HOOK = "isExclusiveMediaOverlayActive"

    @Volatile
    private var cachedGeneration = -1

    @Volatile
    private var cachedHooks: List<Hook> = emptyList()

    fun invalidateCache() {
        cachedGeneration = -1
        cachedHooks = emptyList()
    }

    fun isActive(context: Context): Boolean {
        val app = context.applicationContext
        val modManager = ModManager.getInstance(app)
        val generation = modManager.registryGeneration
        if (generation != cachedGeneration) {
            cachedHooks = buildHooks(app, modManager)
            cachedGeneration = generation
        }
        for (hook in cachedHooks) {
            val active = runCatching {
                hook.method.invoke(hook.instance, app) as? Boolean
            }.onFailure {
                Log.w(TAG, "hook failed for ${hook.modId}", it)
            }.getOrNull()
            if (active == true) return true
        }
        return false
    }

    private fun buildHooks(context: Context, modManager: ModManager): List<Hook> {
        return modManager.getEnabledManifests().mapNotNull { manifest ->
            if (manifest.manager.isNullOrBlank()) return@mapNotNull null
            // Only media-style overlays need exclusivity vs Sendspin FAB.
            if (!manifest.overlayBelowVoice && !manifest.overlayZOrder) return@mapNotNull null
            runCatching {
                val classLoader = modManager.getModClassLoader(manifest.id) ?: return@mapNotNull null
                val managerClass = classLoader.loadClass(manifest.manager!!)
                val method = managerClass.getMethod(HOOK, Context::class.java)
                val instance = managerClass.getMethod("getInstance", Context::class.java)
                    .invoke(null, context)
                    ?: return@mapNotNull null
                Hook(manifest.id, instance, method)
            }.getOrNull()
        }
    }

    private data class Hook(
        val modId: String,
        val instance: Any,
        val method: java.lang.reflect.Method,
    )
}

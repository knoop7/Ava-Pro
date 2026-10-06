package com.example.ava.mods

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import java.lang.reflect.Method

/**
 * Opt-in conversation-engine seat — claim a wake after chorus arbitration and
 * before the HA voice pipeline starts.
 *
 * **Not** the Voice Pipeline notification API. `wake_detected` stays
 * fire-and-forget and still fires before arbitration. This hook is a new
 * manifest flag, synchronous, fail-closed, process-internal only.
 *
 * **Zero cost when unused:** no enabled mod sets [ModManifest.conversationEngine]
 * → [isActive] is false and [offerWake] returns immediately.
 *
 * Opt-in (both required):
 * 1. `"conversation_engine": true` in manifest.json
 * 2. Manager implements `boolean onWakeOffered(Context, Bundle)`
 *
 * Optional: `void onWakeRevoked(Context, Bundle)`
 * Optional: `boolean onWakeOffered(Context, Bundle, ConversationEngineHost)`
 *
 * Only the first enabled conversation-engine mod is bound. Broadcast / SDK
 * receivers cannot claim.
 */
object ModConversationEngine {
    private const val TAG = "ModConversationEngine"

    object Extras {
        const val TOKEN = "token"
        const val WAKE_WORD = "wake_word"
        const val WAKE_WORD_ID = "wake_word_id"
        const val WAKE_CONFIDENCE = "wake_confidence"
        const val SYNTHETIC_WAKE = "synthetic_wake"
        const val REASON = "reason"
        const val LEASE_MS = "lease_ms"
        const val MOD_ID = "mod_id"
    }

    object Reasons {
        const val RELEASED = "released"
        const val TIMEOUT = "timeout"
        const val REVOKED_CHORUS = "revoked_chorus"
        const val REVOKED_STOP = "revoked_stop"
        const val SUPERSEDED = "superseded"
        const val HOST_TEARDOWN = "host_teardown"
    }

    @Volatile
    private var cachedGeneration = -1

    @Volatile
    private var cachedActive = false

    private var cachedBinding: Binding? = null

    private val seat = ConversationEngineSeat(
        nowMs = { SystemClock.elapsedRealtime() },
        leaseMs = ConversationEngineSeat.DEFAULT_LEASE_MS,
    )

    @Volatile
    private var hostListener: ((reason: String) -> Unit)? = null

    @Volatile
    private var appContext: Context? = null

    fun attachHost(listener: (reason: String) -> Unit) {
        hostListener = listener
    }

    fun detachHost() {
        hostListener = null
    }

    fun invalidateCache() {
        if (seat.current != null) {
            revokeCurrent(Reasons.HOST_TEARDOWN)
        }
        cachedGeneration = -1
        cachedBinding = null
        cachedActive = false
    }

    /** Fast path for hot call sites — no ClassLoader when unused. */
    fun isActive(context: Context): Boolean {
        val generation = ModManager.getInstance(context).registryGeneration
        if (generation == cachedGeneration) {
            return cachedActive
        }
        return refreshBinding(context.applicationContext, generation)
    }

    fun isSeatHeld(): Boolean = seat.isHeld()

    fun currentToken(): String? = seat.current?.token

    /**
     * Synchronous offer after a local chorus win (or single-device pass).
     * Returns true only when the bound mod claims immediately.
     * Exception, missing method, or false → HA proceeds (fail-closed).
     */
    fun offerWake(
        context: Context,
        wakeWordPhrase: String,
        wakeWordId: String,
        wakeConfidence: Float,
        syntheticWake: Boolean,
    ): Boolean {
        if (!isActive(context)) return false
        val binding = cachedBinding ?: return false
        val previous = seat.current
        if (previous != null) {
            notifyModRevoked(context, previous, Reasons.SUPERSEDED)
            seat.clear()
        }
        // Issue the token before the callback so extras and ConversationEngineHost
        // are valid inside onWakeOffered. A false / throw drops it without
        // notifying the satellite.
        val lease = seat.issue(binding.modId)
        val extras = Bundle().apply {
            putString(Extras.TOKEN, lease.token)
            putString(Extras.WAKE_WORD, wakeWordPhrase)
            putString(Extras.WAKE_WORD_ID, wakeWordId)
            putFloat(Extras.WAKE_CONFIDENCE, wakeConfidence)
            putBoolean(Extras.SYNTHETIC_WAKE, syntheticWake)
            putLong(Extras.LEASE_MS, ConversationEngineSeat.DEFAULT_LEASE_MS)
            putString(Extras.MOD_ID, binding.modId)
        }
        val claimed = invokeOffer(binding, context, extras, ConversationEngineHost(lease.token))
        if (!claimed || !seat.isCurrent(lease.token)) {
            seat.release(lease.token)
            return false
        }
        Log.i(TAG, "wake claimed by ${binding.modId} token=${lease.token}")
        return true
    }

    @JvmStatic
    fun releaseWake(token: String): Boolean {
        if (!seat.release(token)) return false
        Log.i(TAG, "wake released token=$token")
        hostListener?.invoke(Reasons.RELEASED)
        return true
    }

    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun releaseWake(context: Context, token: String): Boolean = releaseWake(token)

    @JvmStatic
    fun renewWake(token: String): Boolean = seat.renew(token)

    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun renewWake(context: Context, token: String): Boolean = renewWake(token)

    @JvmStatic
    fun isWakeCurrent(token: String): Boolean = seat.isCurrent(token)

    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun isWakeCurrent(context: Context, token: String): Boolean = isWakeCurrent(token)

    /**
     * Host-side revoke. Notifies the mod, drops the token, then tells the
     * satellite to restore the mic. No-op when no seat is held.
     */
    fun revokeCurrent(reason: String) {
        val lease = seat.clear() ?: return
        notifyModRevoked(appContext, lease, reason)
        Log.i(TAG, "wake revoked reason=$reason token=${lease.token}")
        hostListener?.invoke(reason)
    }

    fun expireIfNeeded(): Boolean {
        if (!seat.isExpired()) return false
        revokeCurrent(Reasons.TIMEOUT)
        return true
    }

    private fun refreshBinding(context: Context, generation: Int): Boolean {
        appContext = context.applicationContext
        val modManager = ModManager.getInstance(context)
        val manifest = modManager.getEnabledManifests()
            .firstOrNull { it.conversationEngine && !it.manager.isNullOrBlank() }

        cachedGeneration = generation
        if (manifest == null) {
            cachedBinding = null
            cachedActive = false
            return false
        }

        val binding = bind(context, modManager, manifest)
        cachedBinding = binding
        cachedActive = binding != null
        if (binding == null) {
            Log.w(TAG, "conversation_engine opt-in failed to bind: ${manifest.id}")
        }
        return cachedActive
    }

    private fun bind(
        context: Context,
        modManager: ModManager,
        manifest: ModManifest,
    ): Binding? {
        val classLoader = modManager.getModClassLoader(manifest.id)
        return runCatching {
            val managerClass = classLoader.loadClass(manifest.manager!!)
            val instance = managerClass.getMethod("getInstance", Context::class.java)
                .invoke(null, context)
                ?: return@runCatching null
            val offerWithHost = runCatching {
                managerClass.getMethod(
                    "onWakeOffered",
                    Context::class.java,
                    Bundle::class.java,
                    ConversationEngineHost::class.java,
                )
            }.getOrNull()
            val offer = offerWithHost ?: managerClass.getMethod(
                "onWakeOffered",
                Context::class.java,
                Bundle::class.java,
            )
            val revoke = runCatching {
                managerClass.getMethod(
                    "onWakeRevoked",
                    Context::class.java,
                    Bundle::class.java,
                )
            }.getOrNull()
            Binding(
                modId = manifest.id,
                instance = instance,
                offer = offer,
                offerTakesHost = offerWithHost != null,
                revoke = revoke,
            )
        }.onFailure {
            Log.w(TAG, "Failed to bind conversation engine ${manifest.id}", it)
        }.getOrNull()
    }

    private fun invokeOffer(
        binding: Binding,
        context: Context,
        extras: Bundle,
        host: ConversationEngineHost,
    ): Boolean {
        return runCatching {
            val result = if (binding.offerTakesHost) {
                binding.offer.invoke(binding.instance, context, extras, host)
            } else {
                binding.offer.invoke(binding.instance, context, extras)
            }
            result as? Boolean ?: false
        }.onFailure {
            Log.w(TAG, "onWakeOffered failed for ${binding.modId}", it)
        }.getOrDefault(false)
    }

    private fun notifyModRevoked(
        context: Context?,
        lease: ConversationEngineSeat.Lease,
        reason: String,
        binding: Binding? = cachedBinding,
    ) {
        val bound = binding ?: return
        val revoke = bound.revoke ?: return
        val extras = Bundle().apply {
            putString(Extras.TOKEN, lease.token)
            putString(Extras.REASON, reason)
            putString(Extras.MOD_ID, lease.modId)
        }
        val ctx = context ?: appContext
        runCatching {
            revoke.invoke(bound.instance, ctx, extras)
        }.onFailure {
            Log.w(TAG, "onWakeRevoked failed for ${bound.modId}", it)
        }
    }

    private data class Binding(
        val modId: String,
        val instance: Any,
        val offer: Method,
        val offerTakesHost: Boolean,
        val revoke: Method?,
    )
}

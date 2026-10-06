package com.example.ava.mods

/**
 * Host callbacks for one claimed wake. Passed as the optional third argument to
 * `onWakeOffered`. A stale token (superseded, revoked, timed out) is a no-op.
 *
 * Mods that do not compile against this class can call the same methods as
 * statics on [ModConversationEngine] with the `token` extra.
 */
class ConversationEngineHost internal constructor(
    private val token: String,
) {
    fun token(): String = token

    fun release(): Boolean = ModConversationEngine.releaseWake(token)

    fun renew(): Boolean = ModConversationEngine.renewWake(token)

    fun isCurrent(): Boolean = ModConversationEngine.isWakeCurrent(token)
}

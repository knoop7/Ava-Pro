package com.example.ava.localllm.remote

import com.example.ava.settings.RemoteAiProfile
import java.io.IOException

/** [FAULTS] faults on the current model, then the next filled slot. Chain empty → give up. */
internal class RemoteAiFailover(private val chain: List<RemoteAiProfile>) {
    var index: Int = 0
        private set
    var faultsOnCurrent: Int = 0
        private set

    fun current(): RemoteAiProfile? = chain.getOrNull(index)

    fun noteFault(): After {
        if (chain.isEmpty()) return After.GiveUp
        faultsOnCurrent++
        if (faultsOnCurrent < FAULTS) return After.RetrySame
        if (index + 1 >= chain.size) return After.GiveUp
        index++
        faultsOnCurrent = 0
        return After.Advance
    }

    /**
     * Straight to the next slot without counting. The turn loop no longer uses this:
     * refusals and garbage (401 / 403 / 404 / 400, non-JSON 200) go through [noteFault].
     */
    fun skip(): After {
        if (index + 1 >= chain.size) return After.GiveUp
        index++
        faultsOnCurrent = 0
        return After.Advance
    }

    enum class After { RetrySame, Advance, GiveUp }

    companion object {
        /** Failed requests on one slot before switching, refusals included. Keep within 3..5. */
        const val FAULTS = 3
    }
}

/** The filled continuation chain is done. Do not slice or replay. */
internal class RemoteAiFailoverExhausted(cause: Throwable? = null) :
    IOException("remote AI fallbacks exhausted", cause)

/** The request carried a camera frame and the gateway refused it. Do not retry the same picture. */
internal class RemoteAiVisionRejected(cause: Throwable) :
    IOException(cause.message ?: "remote AI refused attached picture", cause)

/** HTTP 200 whose body is not the wire's JSON (captive portal, HTML error page, wrong path). */
internal class RemoteAiBadReply(cause: Throwable) :
    IOException("remote AI reply is not valid JSON", cause)

package com.example.ava.localllm.remote

import com.example.ava.settings.RemoteAiProfile
import java.io.IOException

/** Two faults on the current model, then the next filled slot. Chain empty → give up. */
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

    enum class After { RetrySame, Advance, GiveUp }

    companion object {
        const val FAULTS = 2
    }
}

/** The filled continuation chain is done. Do not slice or replay. */
internal class RemoteAiFailoverExhausted(cause: Throwable? = null) :
    IOException("remote AI fallbacks exhausted", cause)

/** The request carried a camera frame and the gateway refused it. Do not retry the same picture. */
internal class RemoteAiVisionRejected(cause: Throwable) :
    IOException(cause.message ?: "remote AI refused attached picture", cause)

package com.example.ava.openwakeword

/** Early confirmation may accept; only the final window may reject. */
internal object ProgressiveWakeVerification {
    suspend fun confirm(
        fullAfterMs: Int,
        readWindow: suspend (afterMs: Int) -> ShortArray?,
        classify: suspend (ShortArray) -> Boolean,
        earlyAfterMs: Int = 80,
    ): Boolean? {
        require(fullAfterMs >= 0 && earlyAfterMs >= 0)
        val firstAfterMs = minOf(earlyAfterMs, fullAfterMs)
        val first = readWindow(firstAfterMs)
        if (first != null && classify(first)) return true
        if (firstAfterMs == fullAfterMs) return if (first == null) null else false
        val full = readWindow(fullAfterMs) ?: return null
        return classify(full)
    }
}

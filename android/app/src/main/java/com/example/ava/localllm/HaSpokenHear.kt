package com.example.ava.localllm

/**
 * Wechaty puppet hear: the raw STT string is not the message. The host
 * decides whether two spoken forms are the same name before the model acts.
 * Tight Damerau distance — unique near-miss only, never a short generic word.
 */
object HaSpokenHear {

    /** Contains or a tight near-miss. Used for owned features and aliases. */
    fun meets(spoken: String, candidate: String): Boolean {
        if (spoken == candidate) return true
        if (spoken.isEmpty() || candidate.isEmpty()) return false
        if (spoken.length < 2 || candidate.length < 2) return false
        if (candidate.contains(spoken) || spoken.contains(candidate)) return true
        return close(spoken, candidate)
    }

    fun close(left: String, right: String, loose: Boolean = false): Boolean {
        if (left == right) return true
        if (left.isEmpty() || right.isEmpty()) return false
        val a = left.take(24)
        val b = right.take(24)
        val min = minOf(a.length, b.length)
        val max = maxOf(a.length, b.length)
        if (max - min > 2) return false
        if (DeviceIndex.isGenericHint(a) || DeviceIndex.isGenericHint(b)) return false
        val cjk = looksCjk(a) || looksCjk(b)
        if (min < 2) return false
        if (!cjk && min < 4 && !loose) return false
        val d = damerau(a, b)
        if (d <= 0) return a == b
        val allow = when {
            cjk && min <= 2 -> 1
            min <= 8 -> 1
            else -> 2
        }
        return d <= allow && d * 3 <= max
    }

    private fun looksCjk(s: String): Boolean =
        s.any { ch -> ch.code in 0x2E80..0x9FFF || ch.code in 0xF900..0xFAFF }

    /** Damerau–Levenshtein, enough for a spoken name, cheap on short strings. */
    internal fun damerau(left: String, right: String): Int {
        if (left == right) return 0
        if (left.isEmpty()) return right.length
        if (right.isEmpty()) return left.length
        val n = left.length
        val m = right.length
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) dp[i][0] = i
        for (j in 0..m) dp[0][j] = j
        for (i in 1..n) {
            for (j in 1..m) {
                val cost = if (left[i - 1] == right[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost,
                )
                if (i > 1 && j > 1 &&
                    left[i - 1] == right[j - 2] &&
                    left[i - 2] == right[j - 1]
                ) {
                    dp[i][j] = minOf(dp[i][j], dp[i - 2][j - 2] + 1)
                }
            }
        }
        return dp[n][m]
    }
}

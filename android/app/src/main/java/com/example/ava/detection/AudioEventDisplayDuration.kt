package com.example.ava.detection

object AudioEventDisplayDuration {
    const val DEFAULT_SECONDS = 30
    const val MIN_SECONDS = 5
    const val MAX_SECONDS = 60

    fun coerceSeconds(seconds: Int): Int = seconds.coerceIn(MIN_SECONDS, MAX_SECONDS)

    fun toMillis(seconds: Int): Long = coerceSeconds(seconds) * 1000L
}

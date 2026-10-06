package com.example.ava.microwakeword

/** Per-model live wake probabilities for Voice Stats. */
data class WakeWordLiveProbe(
    val id: String,
    val phrase: String,
    val windowAvg: Float,
    val lastProb: Float,
    val cutoff: Float,
)

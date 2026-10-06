package com.example.ava.audio.eq

import com.example.ava.settings.PlayerSettings
import com.example.ava.settings.SendspinSettings

fun PlayerSettings.toHaMusicEqGains(): MusicEqGains = MusicEqGains(
    enabled = haMusicEqEnabled,
    bassDb = haMusicEqBassDb,
    lowMidDb = haMusicEqLowMidDb,
    midDb = haMusicEqMidDb,
    upperMidDb = haMusicEqUpperMidDb,
    trebleDb = haMusicEqTrebleDb,
    adaptiveEnabled = haMusicEqAdaptive,
).normalized()

fun SendspinSettings.toMusicEqGains(): MusicEqGains = MusicEqGains(
    enabled = musicEqEnabled,
    bassDb = musicEqBassDb,
    lowMidDb = musicEqLowMidDb,
    midDb = musicEqMidDb,
    upperMidDb = musicEqUpperMidDb,
    trebleDb = musicEqTrebleDb,
    adaptiveEnabled = musicEqAdaptive,
).normalized()

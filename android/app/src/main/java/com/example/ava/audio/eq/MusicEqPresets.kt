package com.example.ava.audio.eq

import com.example.ava.R
import kotlin.math.abs

/**
 * Ready-made curves for the 5-band layout in [MusicEqEngine]
 * (80 Hz shelf / 250 Hz / 1 kHz / 4 kHz peaks at Q 0.7 / 10 kHz shelf).
 *
 * Values are tuned for *this* layout: the peaks are wide and overlap, and the outer two bands
 * are shelves, so the same numbers taken from a 10-band preset elsewhere would sum to a far
 * stronger response. Boosts are kept moderate because the HA path has no headroom before the
 * PCM16 clip.
 */
enum class MusicEqPreset(
    val labelRes: Int,
    val bassDb: Float,
    val lowMidDb: Float,
    val midDb: Float,
    val upperMidDb: Float,
    val trebleDb: Float,
) {
    TOP_SURROUND_AVA(R.string.settings_music_eq_preset_top_surround_ava, 9f, 9f, 2f, 0f, 0f),
    NEWS_BROADCAST_AVA(R.string.settings_music_eq_preset_news_broadcast_ava, 4.5f, 8f, -7.5f, 5.5f, 8.5f),
    FLAT(R.string.settings_music_eq_preset_flat, 0f, 0f, 0f, 0f, 0f),
    POP(R.string.settings_music_eq_preset_pop, 2f, -1f, 1.5f, 2f, 1.5f),
    ROCK(R.string.settings_music_eq_preset_rock, 3.5f, -1.5f, -1f, 2.5f, 2.5f),
    HIP_HOP(R.string.settings_music_eq_preset_hip_hop, 5f, 1f, -1.5f, 1f, 1.5f),
    ELECTRONIC(R.string.settings_music_eq_preset_electronic, 4.5f, 0f, -2f, 1f, 3f),
    CLASSICAL(R.string.settings_music_eq_preset_classical, 1f, 0f, 0f, 0.5f, 2f),
    JAZZ(R.string.settings_music_eq_preset_jazz, 2.5f, 0f, 1f, 0.5f, 1.5f),
    VOCAL(R.string.settings_music_eq_preset_vocal, -1.5f, -1f, 2.5f, 3f, 1f),
    BASS_BOOST(R.string.settings_music_eq_preset_bass_boost, 6f, 2f, 0f, 0f, 0f),
    TREBLE_BOOST(R.string.settings_music_eq_preset_treble_boost, 0f, 0f, 0f, 2f, 5f),
    LOUDNESS(R.string.settings_music_eq_preset_loudness, 4.5f, 1f, -2.5f, 0f, 3.5f),
    NIGHT(R.string.settings_music_eq_preset_night, -3f, -1f, 1.5f, 2f, 0f),
    PODCAST(R.string.settings_music_eq_preset_podcast, -4f, -2f, 3f, 3f, -1f),
    SMALL_SPEAKER(R.string.settings_music_eq_preset_small_speaker, -2f, 2f, 1.5f, 2f, 1f),
    ;

    /** Switches the EQ on as well, so picking a curve is audible without a second tap. */
    fun applyTo(gains: MusicEqGains): MusicEqGains = gains.copy(
        enabled = true,
        bassDb = bassDb,
        lowMidDb = lowMidDb,
        midDb = midDb,
        upperMidDb = upperMidDb,
        trebleDb = trebleDb,
    )

    private fun matches(gains: MusicEqGains): Boolean =
        near(gains.bassDb, bassDb) &&
            near(gains.lowMidDb, lowMidDb) &&
            near(gains.midDb, midDb) &&
            near(gains.upperMidDb, upperMidDb) &&
            near(gains.trebleDb, trebleDb)

    companion object {
        /**
         * The preset describing [gains], or null when the bands were hand-tuned into a curve no
         * preset matches. Lets the picker show the active preset without persisting its name.
         */
        fun matching(gains: MusicEqGains): MusicEqPreset? = entries.firstOrNull { it.matches(gains) }

        /** Slider step is 0.5 dB; the tolerance only absorbs JSON float round-trips. */
        private fun near(a: Float, b: Float): Boolean = abs(a - b) < 0.05f
    }
}

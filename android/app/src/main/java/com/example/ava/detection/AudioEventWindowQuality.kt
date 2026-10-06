package com.example.ava.detection

import com.example.ava.audio.AudioEnergy
import java.nio.ByteBuffer

/**
 * Reject silent / dead windows before they reach the classifier.
 *
 * All-zero MFE still yields cough ~0.90 on the INT8 model (verified on EI X_training).
 * Training event windows have feat nonzero ratio p50 ≥ 0.62 for reportable classes.
 */
object AudioEventWindowQuality {
    private const val MIN_PCM_RMS = 0.01f
    private const val MIN_PCM_NONZERO = 0.02f
    private const val MIN_FEATURE_MEAN = 0.02f
    // The model has no background/unknown class, so quiet rooms get classified as a confident
    // garbage label (e.g. baby_cry 0.96). Training reportable events have feat nonzero ratio
    // p50 >= 0.62; a quiet room sits at ~0.15-0.30. Gate at 0.45: above quiet noise, below the
    // event median so a real alarm/siren/baby_cry still passes on its louder windows.
    const val MIN_FEATURE_NONZERO = 0.45f

    fun pcmLooksValid(pcm: ByteBuffer): Boolean {
        val dup = pcm.duplicate()
        if (AudioEnergy.pcm16LeFloatRms(dup) < MIN_PCM_RMS) return false
        if (AudioEnergy.pcm16LeNonZeroRatio(dup) < MIN_PCM_NONZERO) return false
        return true
    }

    fun featuresLookValid(
        result: AudioEventResult,
        minFeatureNonZero: Float = MIN_FEATURE_NONZERO,
    ): Boolean {
        if (result.featureMean < MIN_FEATURE_MEAN) return false
        if (result.featureNonZeroRatio < minFeatureNonZero) return false
        return true
    }
}

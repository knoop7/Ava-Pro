package com.example.ava.esphome.voicesatellite

enum class PlaybackPipeline {
    NONE,
    BUILT_IN_MEDIA,
    HA_MEDIA,
    SENDSPIN_PROTOCOL,
    VOICE_TTS
}

enum class MediaResumeAction {
    NONE,
    RESUME_BUILT_IN_MEDIA,
    RESUME_HA_MEDIA,
    RESUME_OTHER_MEDIA
}

data class MediaRouteSignals(
    val builtInMediaWasPlaying: Boolean,
    val haMediaWasPlaying: Boolean,
    val sendspinProtocolActive: Boolean,
    val voiceTtsActive: Boolean,
    val allowOtherMediaResume: Boolean
)

data class MediaResumeDecision(
    val activePipeline: PlaybackPipeline,
    val resumeAction: MediaResumeAction
)

object MediaResumeArbiter {
    fun decide(signals: MediaRouteSignals): MediaResumeDecision {
        val activePipeline = when {
            signals.voiceTtsActive -> PlaybackPipeline.VOICE_TTS
            signals.sendspinProtocolActive -> PlaybackPipeline.SENDSPIN_PROTOCOL
            signals.builtInMediaWasPlaying -> PlaybackPipeline.BUILT_IN_MEDIA
            signals.haMediaWasPlaying -> PlaybackPipeline.HA_MEDIA
            else -> PlaybackPipeline.NONE
        }

        val resumeAction = when {
            signals.voiceTtsActive -> MediaResumeAction.NONE
            signals.sendspinProtocolActive -> MediaResumeAction.NONE
            signals.builtInMediaWasPlaying -> MediaResumeAction.RESUME_BUILT_IN_MEDIA
            signals.haMediaWasPlaying -> MediaResumeAction.RESUME_HA_MEDIA
            signals.allowOtherMediaResume -> MediaResumeAction.RESUME_OTHER_MEDIA
            else -> MediaResumeAction.NONE
        }

        return MediaResumeDecision(
            activePipeline = activePipeline,
            resumeAction = resumeAction
        )
    }
}

package com.example.ava.ui.components

/** Which teaching page [SatelliteSetupTipOverlayContent] should show. */
enum class SatelliteSetupTipKind {
    /** HA voice-satellite wake-word wizard intercept. */
    WakeConfig,

    /** HA ESPHome option: allow device to perform Home Assistant actions. */
    HaServiceCalls,

    /**
     * HA default Assist intent library replied with `no_intent`
     * ("Sorry, I couldn't understand…" / "Sorry, I couldn't understand that").
     * Guide user to another conversation agent or Claw Assistant (ha_claw).
     */
    ConversationNoIntent,

    /**
     * Repeated permanent Assist pipeline errors (STT / TTS / conversation agent).
     * Tip may reappear after "Got it" if the same streak builds again.
     */
    PipelineConfig,
}

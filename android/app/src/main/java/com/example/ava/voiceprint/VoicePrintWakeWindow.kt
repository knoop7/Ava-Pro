package com.example.ava.voiceprint

import com.example.ava.settings.WakeWordEngine

data class VoicePrintWakeWindow(
    val beforeMs: Int,
    val afterMs: Int,
) {
    val totalMs: Int get() = beforeMs + afterMs
}

object VoicePrintWakeWindows {
    /** Anchor wake mark earlier so the window sits on the wake phrase, not the prompt tone. */
    private const val MARK_LEAD_MS_MICRO = 480

    /**
     * The 900 ms lead was tuned for the retired ONNX vsWakeWord, which detected
     * ~1+ s after word end. The native engine fires ~100–250 ms after word end,
     * so 900 put the window at [detect-1950, detect-350] — it ended BEFORE the
     * phrase finished, with no quiet tail at all. The classifier scores windows
     * shaped "phrase + quiet tail" (its streaming peak lands ~40–120 ms AFTER
     * the detect instant), so the offline extra-strictness burst was
     * structurally deaf: 0/4 padded positives at every cutoff on the
     * oww_verify_sim host test. Lead 0 → [detect-1050, detect+550] covers
     * phrase + tail and scored 4/4 at 0.54/0.58/0.84 there. Longer windows
     * (before=1400) measured WORSE at 0.84 — the 350 ms shift is not a whole
     * 640-sample chunk, and the embedding-grid realignment jitters peaks by
     * ±0.05 — so the window shape stays 1050/550.
     */
    private const val MARK_LEAD_MS_VS = 0

    private val MICRO = VoicePrintWakeWindow(beforeMs = 1100, afterMs = 520)
    private val VS = VoicePrintWakeWindow(beforeMs = 1050, afterMs = 550)

    fun forEngine(engine: WakeWordEngine): VoicePrintWakeWindow = when (engine) {
        WakeWordEngine.MICRO_WAKE_WORD -> MICRO
        WakeWordEngine.OPEN_WAKE_WORD -> VS
    }

    fun markLeadMs(engine: WakeWordEngine): Int = when (engine) {
        WakeWordEngine.MICRO_WAKE_WORD -> MARK_LEAD_MS_MICRO
        WakeWordEngine.OPEN_WAKE_WORD -> MARK_LEAD_MS_VS
    }
}

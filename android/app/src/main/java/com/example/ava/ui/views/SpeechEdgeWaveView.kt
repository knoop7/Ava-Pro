package com.example.ava.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.Choreographer
import android.view.View
import com.example.ava.audio.PlaybackEnergyMonitor
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Faint translucent-black wave along the bottom screen edge while the PERSON is
 * talking (STT listening). Driven by the live mic meter, gated so it only shows on
 * real speech: not during TTS / chime playback (mic uplink still closed, or fresh
 * playback energy), not in the continue gap (the owner's [isListening] is false
 * there), and not on steady room noise (adaptive dB floor + very short sustain).
 * Parks its frame loop whenever nothing is visible.
 *
 * Kept visually separate from the edge glow / A64 green center breathe: black only,
 * bottom edge only, and it publishes [personSpeechVisibility] so the breathe can
 * stay calm while this wave carries the person's voice.
 */
class SpeechEdgeWaveView(
    context: Context,
    /** Live mic meter (linear float RMS, same as VoiceSatelliteService.currentMicrophoneLevel). */
    private val micLevel: () -> Float,
    /** Owner says the turn is in user-speech LISTENING with the sphere on screen. */
    private val isListening: () -> Boolean,
    /**
     * Mic uplink really open (wake / continue chime finished, HA hears the person).
     * Replaces the old fixed 350 ms arm delay as the chime guard.
     */
    private val isUplinkOpen: () -> Boolean = { true },
    /**
     * Wake earcon still playing but the mic is already pre-rolled for HA. People say
     * the wake word and the command in one breath, so on the first wake turn most of
     * the sentence lands here, before [isUplinkOpen]. Watched with a stricter line
     * (earcon residual) instead of being ignored.
     */
    private val isPreRollCapturing: () -> Boolean = { false },
) : View(context) {

    companion object {
        /**
         * 0–1 visibility of the person-speech wave right now (any instance).
         * Read by [VoiceStateOverlayView] so its resting breathe does not swell
         * with the person's voice at the same time (separate cues, no fighting).
         */
        @Volatile
        @JvmStatic
        var personSpeechVisibility: Float = 0f
            private set

        /** Minimum beat after LISTENING starts (wake-word tail echo). Was 350 ms. */
        private const val ARM_MIN_MS = 120L
        /** Uplink flag never came up: start watching anyway after this long. */
        private const val ARM_FALLBACK_MS = 1500L
        /** Extra margin above the open line while the wake earcon is still playing. */
        private const val PREROLL_EXTRA_OPEN_DB = 5f
        /** Sustain while the wake earcon is still playing (earcon transients are short). */
        private const val PREROLL_OPEN_SUSTAIN_MS = 80L
        /** Absolute floor: below this is never speech. Was -42 dB. */
        private const val MIN_SPEECH_DB = -48f
        /** First-turn room floor guess until frames are learned. */
        private const val SEED_FLOOR_DB = -50f
        /** A learned floor stays valid across continue turns for this long. */
        private const val FLOOR_REUSE_MS = 120_000L
        /** Gate opens this far above the learned room floor... (was 10 dB) */
        private const val OPEN_MARGIN_DB = 7f
        /** ...and closes once level falls back under this margin. (was 6 dB) */
        private const val CLOSE_MARGIN_DB = 4f
        /** Speech must hold this long before the wave shows. Was 100 ms. */
        private const val OPEN_SUSTAIN_MS = 40L
        /** Pause between words keeps the wave; longer silence lets it go. Was 380 ms. */
        private const val CLOSE_HOLD_MS = 420L
        /** Something audible on the speaker → not the person. */
        private const val PLAYBACK_FRESH_MS = 150L
        /** Floor rise rate (per second, fraction of gap) while the gate is closed / open. */
        private const val FLOOR_RISE_CLOSED = 0.30f
        private const val FLOOR_RISE_OPEN = 0.06f
        /** dB span above the close line mapped to full level. Was 24 dB. */
        private const val LEVEL_SPAN_DB = 20f
        /** Level shown as soon as the gate opens, so quiet speech still moves. */
        private const val LEVEL_MIN_OPEN = 0.22f
        private const val LEVEL_ATTACK_S = 0.05f
        private const val LEVEL_RELEASE_S = 0.26f
        /** Visibility easing. Was 0.18 / 0.32 s. */
        private const val VIS_ATTACK_S = 0.08f
        private const val VIS_RELEASE_S = 0.40f
        /** Max fill alpha at the very bottom edge (0–255). Was 46 / 64 (18 % / 25 %). */
        private const val BACK_ALPHA = 78
        private const val FRONT_ALPHA = 102
        /** Amplitude smoothing (separate from the gate level) so the crest glides. */
        private const val AMP_ATTACK_S = 0.09f
        private const val AMP_RELEASE_S = 0.30f
    }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val backPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val frontPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val backPath = Path()
    private val frontPath = Path()
    private var ptsX = FloatArray(0)
    private var ptsY = FloatArray(0)

    private var running = false
    /**
     * Owner armed this LISTENING turn. Survives a window detach/re-attach: the first
     * wake turn hard-raises the overlay window (removeView + addView) ~200 ms after
     * [armListening], which used to park the loop for the whole first turn.
     */
    private var armRequested = false
    private var lastFrameNanos = 0L
    private var armedAtNanos = 0L

    private var floorDb = Float.NaN
    private var floorLearnedAtNanos = 0L
    private var gateOpen = false
    private var aboveSinceNanos = 0L
    private var belowSinceNanos = 0L
    private var level = 0f
    /** Smoothed drawing amplitude (0–1), eased from [level]. */
    private var ampLevel = 0f
    private var vis = 0f
    private var clock = 0f

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val dt = if (lastFrameNanos == 0L) 0.016f
            else ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
            lastFrameNanos = frameTimeNanos
            step(System.nanoTime(), dt)
            if (!isListening() && vis < 0.01f) {
                armRequested = false
                park()
                return
            }
            // Every frame (view is only 72 dp tall): half-rate looked steppy.
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    init {
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /**
     * New user-speech LISTENING turn: start watching the mic. A room floor learned
     * in a recent turn is kept, so continue-conversation turns track the first
     * syllable instead of re-learning (a first frame that is already speech used
     * to become the floor and swallow the start of the sentence).
     */
    fun armListening() {
        val now = System.nanoTime()
        armedAtNanos = now
        armRequested = true
        if (floorDb.isNaN() || now - floorLearnedAtNanos > FLOOR_REUSE_MS * 1_000_000L) {
            floorDb = Float.NaN
        }
        gateOpen = false
        aboveSinceNanos = 0L
        belowSinceNanos = 0L
        startLoop()
    }

    private fun startLoop() {
        if (!running) {
            running = true
            lastFrameNanos = 0L
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    private fun park() {
        running = false
        lastFrameNanos = 0L
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        gateOpen = false
        level = 0f
        ampLevel = 0f
        vis = 0f
        personSpeechVisibility = 0f
        invalidate()
    }

    private fun step(now: Long, dt: Float) {
        val listening = isListening()
        val sinceArmMs = (now - armedAtNanos) / 1_000_000L
        val uplink = isUplinkOpen()
        // First wake turn: the earcon holds the uplink ~1.8 s while the person is
        // already talking into the pre-roll. Watching only after uplink-open used to
        // miss nearly the whole first sentence.
        val preRoll = !uplink && isPreRollCapturing()
        val armed = sinceArmMs >= ARM_MIN_MS && (uplink || preRoll || sinceArmMs >= ARM_FALLBACK_MS)
        val playing = PlaybackEnergyMonitor.sampleAgeMs() < PLAYBACK_FRESH_MS
        val raw = micLevel().coerceIn(0f, 1f)
        val db = 20f * log10(max(raw, 1e-5f))

        if (listening && armed && !playing) {
            // Room floor: drops fast to quiet frames, rises slowly (even slower while
            // the gate is open) so a sentence is never absorbed into the floor.
            // Seeded low, never from the first frame, so speech that starts right at
            // uplink-open still opens the gate.
            floorDb = when {
                floorDb.isNaN() -> min(db, SEED_FLOOR_DB)
                db < floorDb -> floorDb + (db - floorDb) * 0.25f
                else -> floorDb + (db - floorDb) *
                    (dt * if (gateOpen) FLOOR_RISE_OPEN else FLOOR_RISE_CLOSED).coerceAtMost(1f)
            }
            floorLearnedAtNanos = now
        }

        val canShow = listening && armed && !playing && !floorDb.isNaN()
        val openLine = max(MIN_SPEECH_DB, floorDb + OPEN_MARGIN_DB) +
            if (preRoll) PREROLL_EXTRA_OPEN_DB else 0f
        val openSustainMs = if (preRoll) PREROLL_OPEN_SUSTAIN_MS else OPEN_SUSTAIN_MS
        val closeLine = max(MIN_SPEECH_DB - 3f, floorDb + CLOSE_MARGIN_DB)
        if (!canShow) {
            gateOpen = false
            aboveSinceNanos = 0L
            belowSinceNanos = 0L
        } else if (!gateOpen) {
            if (db > openLine) {
                if (aboveSinceNanos == 0L) aboveSinceNanos = now
                if (now - aboveSinceNanos >= openSustainMs * 1_000_000L) {
                    gateOpen = true
                    belowSinceNanos = 0L
                }
            } else {
                aboveSinceNanos = 0L
            }
        } else {
            if (db < closeLine) {
                if (belowSinceNanos == 0L) belowSinceNanos = now
                if (now - belowSinceNanos >= CLOSE_HOLD_MS * 1_000_000L) {
                    gateOpen = false
                    aboveSinceNanos = 0L
                }
            } else {
                belowSinceNanos = 0L
            }
        }

        val target = if (gateOpen) {
            val lv = ((db - closeLine) / LEVEL_SPAN_DB).coerceIn(0f, 1f)
            LEVEL_MIN_OPEN + (1f - LEVEL_MIN_OPEN) * sqrt(lv)
        } else {
            0f
        }
        level += (target - level) * (1f - exp(-dt / if (target > level) LEVEL_ATTACK_S else LEVEL_RELEASE_S))
        val visTarget = if (gateOpen) 1f else 0f
        vis += (visTarget - vis) * (1f - exp(-dt / if (visTarget > vis) VIS_ATTACK_S else VIS_RELEASE_S))
        if (visTarget == 0f && vis < 0.01f) vis = 0f
        personSpeechVisibility = vis
        ampLevel += (level - ampLevel) * (1f - exp(-dt / if (level > ampLevel) AMP_ATTACK_S else AMP_RELEASE_S))
        // Gentle, slightly faster when louder; smoothed so speed changes never jerk.
        clock += dt * (0.55f + ampLevel * 0.9f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (h <= 0) return
        // Soft top: alpha eases in (3 stops) instead of a straight ramp, so the crest
        // reads as a smooth shadow rather than a hard band.
        backPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(
                Color.argb(0, 0, 0, 0),
                Color.argb(BACK_ALPHA * 45 / 100, 0, 0, 0),
                Color.argb(BACK_ALPHA, 0, 0, 0),
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        frontPaint.shader = LinearGradient(
            0f, h * 0.25f, 0f, h.toFloat(),
            intArrayOf(
                Color.argb(0, 0, 0, 0),
                Color.argb(FRONT_ALPHA * 45 / 100, 0, 0, 0),
                Color.argb(FRONT_ALPHA, 0, 0, 0),
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    /** Smooth wave: sample points, then join them with quadratic curves through midpoints. */
    private fun buildWave(path: Path, w: Float, h: Float, base: Float, amp: Float, phase: Float, k: Float) {
        val stepPx = dp(10f)
        val n = (w / stepPx).toInt() + 2
        if (ptsX.size < n) {
            ptsX = FloatArray(n)
            ptsY = FloatArray(n)
        }
        for (i in 0 until n) {
            val x = min(i * stepPx, w)
            val u = x / w
            val wave = 0.72f * sin(u * k + clock * 1.6f + phase) +
                0.28f * sin(u * k * 1.6f - clock * 1.05f + phase * 0.7f)
            // Swell a little toward the middle, settle near the corners.
            val env = 0.72f + 0.28f * sin(u * PI.toFloat())
            ptsX[i] = x
            ptsY[i] = h - (base + amp * env * (0.5f + 0.5f * wave))
        }
        path.reset()
        path.moveTo(0f, h)
        path.lineTo(ptsX[0], ptsY[0])
        for (i in 1 until n - 1) {
            val mx = (ptsX[i] + ptsX[i + 1]) * 0.5f
            val my = (ptsY[i] + ptsY[i + 1]) * 0.5f
            path.quadTo(ptsX[i], ptsY[i], mx, my)
        }
        path.lineTo(ptsX[n - 1], ptsY[n - 1])
        path.lineTo(w, h)
        path.close()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (vis <= 0.01f) return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val v = vis.coerceIn(0f, 1f)
        // Smoothed amplitude: crest up to ~45 dp inside the 72 dp view.
        val amp = (dp(6f) + ampLevel * dp(22f)) * v
        val base = dp(12f) * v
        buildWave(backPath, w, h, base + dp(5f) * v, amp * 0.85f, 1.7f, 5.4f)
        buildWave(frontPath, w, h, base, amp, 0f, 3.6f)
        val a = (255f * v).toInt().coerceIn(0, 255)
        backPaint.alpha = a
        frontPaint.alpha = a
        canvas.drawPath(backPath, backPaint)
        canvas.drawPath(frontPath, frontPaint)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Window was hard-raised (removeView + addView) mid-turn: keep watching the
        // mic with the same arm time and learned floor instead of staying parked.
        if (armRequested && isListening()) startLoop()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // Pause only; [armRequested] is kept so a re-attach in the same turn resumes.
        park()
    }
}

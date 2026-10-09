package com.example.ava.ui.views

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.example.ava.audio.PlaybackEnergyMonitor
import com.example.ava.settings.PlayerSettings
import com.example.ava.ui.VoiceAccentColors
import com.example.ava.utils.DeviceFeatureManager
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class VoiceStateOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Phase {
        NONE,
        LISTENING,
        PROCESSING,
        SPEAKING
    }

    companion object {
        /**
         * Session-end wind-down: instead of freezing the glow and fading a static
         * frame, the frame loop keeps running for this long while the level decays
         * to zero. Purely visual — session teardown timing is untouched.
         */
        private const val WIND_DOWN_MS = 2000L
        private const val FADE_IN_MS = 520L

        /** Settings preview: slow retract so the inward stack eases back to the edge. */
        const val PREVIEW_FADE_MS = 2600L

        /**
         * Extra STT/TTS level swing. 1 = designed default (slider left);
         * 2 = previous max inward; 3.5 = farther stack. Idle stacked band is unchanged.
         */
        const val MIN_LEVEL_GAIN = 1f
        const val MAX_LEVEL_GAIN = 3.5f
        /** Still preview overlay: a held energy so gain densifies the stack without jumping. */
        private const val PREVIEW_STATIC_ENERGY = 0.62f
        /** SPEAKING level easing time constants (frame-time based, seconds). */
        private const val SPEAKING_ATTACK_S = 0.040f
        private const val SPEAKING_RELEASE_S = 0.180f
        /** A playback sample older than this no longer drives the glow. */
        private const val SPEAKING_STALE_MS = 150L
        private const val SPEAKING_FLOOR = 0.01f
        /**
         * A64 green center glow is a TTS-playback cue only (plus the PROCESSING
         * breathe). It stays while a TTS sample was heard within this long, so the
         * short pauses between sentences do not blink it.
         */
        private const val CENTER_TTS_HOLD_MS = 450L
        /** showSpeaking → first audible TTS sample: keep the glow bridged this long. */
        private const val CENTER_TTS_BRIDGE_MS = 900L
        private const val CENTER_ATTACK_S = 0.12f
        private const val CENTER_RELEASE_S = 0.18f
        /** extraAmplitude at the old slider max (gain = 2) — default → mid stack. */
        private const val INWARD_MID_REACH = 1f
        /** extraAmplitude from old max to new max (gain 2 → 3.5) — mid → far stack. */
        private const val INWARD_FAR_SPAN = 1.5f

        /**
         * Edge-lighting glow intensity (modulates the pre-blurred bitmap).
         * Idle stays light; STT level / TTS energy ride the breathe term, so
         * activity reads as the glow deepening and moving.
         */
        private const val GLOW_ALPHA_IDLE = 0.46f
        private const val GLOW_ALPHA_ACTIVE = 0.54f
        private const val GLOW_BREATHE_IDLE = 0.16f
        private const val GLOW_BREATHE_ACTIVE = 0.25f

        /**
         * True gaussian glow with zero per-frame blur cost: the blurred round-rect
         * band is rendered ONCE with BlurMaskFilter into low-res bitmaps (blur is
         * low-frequency, so upscaling it back to full screen is invisible). Per
         * frame we only draw the cached bitmaps — a hardware-accelerated textured
         * quad. Breathing cross-fades a thin and a thick variant.
         * All size fractions are relative to vmin.
         */
        private const val GLOW_BITMAP_SCALE = 0.25f
        private const val GLOW_CORNER = 0.09f

        /**
         * Baked profile: deepest right at the screen edge, then each wider layer is
         * fainter and blurrier, so the band thins out gradually toward the screen
         * interior with a wide, smooth transition.
         */
        private val GLOW_PROFILE_WIDTH = floatArrayOf(0.04f, 0.10f, 0.20f, 0.34f, 0.46f)
        private val GLOW_PROFILE_ALPHA = floatArrayOf(0.72f, 0.33f, 0.145f, 0.055f, 0.022f)
        private const val GLOW_THICK_SCALE = 1.26f
        /** Extra stacked width at the old slider max — same layers, reaching further inward. */
        private const val GLOW_INWARD_SCALE = 1.82f
        /** New high gears: same profile, reaching much closer to the screen center. */
        private const val GLOW_FAR_INWARD_SCALE = 3.38f

        /** Blur radius as a fraction of each layer's stroke width. */
        private const val GLOW_BLUR_FACTOR = 0.68f

        /**
         * A64 kiosk boards only (see [DeviceFeatureManager.isA64Device]): full-screen
         * vignette + center pulse on top of a deeper edge glow. All other devices
         * use [GLOW_PROFILE_WIDTH] / [drawEdgeGlow] only — no shared branching.
         */
        /** Edge strokes + inward tail — each layer wider, faintest layers reach deepest. */
        private val A64_GLOW_PROFILE_WIDTH = floatArrayOf(0.06f, 0.11f, 0.25f, 0.43f, 0.59f, 0.73f)
        private val A64_GLOW_PROFILE_ALPHA = floatArrayOf(0.92f, 0.52f, 0.34f, 0.22f, 0.14f, 0.08f)
        private const val A64_GLOW_BLUR_FACTOR = 0.60f
        private const val A64_GLOW_THICK_SCALE = 1.48f
        private const val A64_GLOW_INWARD_SCALE = 1.36f
        private const val A64_GLOW_FAR_INWARD_SCALE = 2.22f
        private const val A64_VIGNETTE_BITMAP_SCALE = 0.25f
        /** Radial vignette stops (center → edge): larger clear core, tint stays nearer edges. */
        private val A64_VIGNETTE_STOPS = floatArrayOf(0f, 0.32f, 0.48f, 0.64f, 1f)
        private val A64_VIGNETTE_ALPHAS = intArrayOf(0, 48, 120, 195, 235)
    }

    /** Hardware detection — same gate as [VolumeControlService] / voice satellite A64 paths. */
    private val useA64EnhancedOverlay = DeviceFeatureManager.isA64Device()

    private var phase = Phase.NONE
    private var overlayColor = VoiceAccentColors.WAKE_WORD_1
    private var fadeAlpha = 1f
    private var fixedWidth = 0
    private var fixedHeight = 0

    private var driftTime = 0f
    private var breatheTime = 0f
    private var a64AnimTime = 0f
    private var motionSpeed = 0.22f
    private var targetMotionSpeed = 0.22f
    private var intensity = 0.66f
    private var targetIntensity = 0.66f
    private var audioEnergy = 0f
    private var targetAudioEnergy = 0f
    /** TTS audio ended while still in SPEAKING (continue wait / chime): level eases to 0. */
    private var speakingAudioEnded = false
    /** 0–1 visibility of the A64 green center glow (TTS playing / PROCESSING only). */
    private var centerVis = 0f
    /**
     * 0–1 visibility of the whole edge glow ring (every device). Follows TTS that is
     * really audible, plus the PROCESSING breathe; the continue gap after a reply
     * (SPEAKING with the audio ended, then the re-opened LISTENING) draws nothing.
     */
    private var glowVis = 0f
    /** LISTENING entered from SPEAKING / PROCESSING = continuous-dialogue gap. */
    private var listenAfterReply = false
    /** When the current SPEAKING phase began, and whether TTS was audible in it yet. */
    private var speakingSinceNanos = 0L
    private var speakingHeardAudio = false
    /** 1 = designed level response; higher = stronger energy swing. */
    private var levelGain = MIN_LEVEL_GAIN
    /** 1 = designed opacity; lower = more translucent (never fully clear). */
    private var opacityMul = 1f
    /** Settings slider preview: hold a still stacked overlay; gain densifies it. */
    private var previewLevelDrive = false

    private var fadeAnimator: ValueAnimator? = null
    private var windingDown = false
    private var frameActive = false
    private val choreographer = Choreographer.getInstance()
    private var lastFrameNanos = 0L

    private var glowBitmapThin: Bitmap? = null
    private var glowBitmapThick: Bitmap? = null
    private var glowBitmapDeepThin: Bitmap? = null
    private var glowBitmapDeepThick: Bitmap? = null
    private var glowBitmapFarThin: Bitmap? = null
    private var glowBitmapFarThick: Bitmap? = null
    private var glowBitmapForW = 0
    private var glowBitmapForH = 0
    private val glowBitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val glowDstRect = RectF()
    private var glowTintColor = 0
    private var glowTintFilter: PorterDuffColorFilter? = null

    private var a64VignetteBitmap: Bitmap? = null
    private var a64VignetteForW = 0
    private var a64VignetteForH = 0
    private val a64VignettePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val a64CenterPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var a64GlowBitmapThin: Bitmap? = null
    private var a64GlowBitmapThick: Bitmap? = null
    private var a64GlowBitmapDeepThin: Bitmap? = null
    private var a64GlowBitmapDeepThick: Bitmap? = null
    private var a64GlowBitmapFarThin: Bitmap? = null
    private var a64GlowBitmapFarThick: Bitmap? = null
    private var a64GlowBitmapForW = 0
    private var a64GlowBitmapForH = 0

    /** Render at half the display refresh rate; the eased state hides the lower fps. */
    private var frameSkip = false

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!frameActive || phase == Phase.NONE) return

            val dt = if (lastFrameNanos == 0L) {
                0.016f
            } else {
                ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
            }
            lastFrameNanos = frameTimeNanos

            motionSpeed += (targetMotionSpeed - motionSpeed) * (0.04f + dt * 2f)
            intensity += (targetIntensity - intensity) * (0.035f + dt * 1.5f)

            if (windingDown) {
                // Session over: stop pulling live levels and let the glow level
                // sag naturally while the fade-out runs.
                audioEnergy += (targetAudioEnergy - audioEnergy) * (0.24f + dt * 9f)
                targetAudioEnergy *= (0.90f - dt * 0.35f).coerceAtLeast(0.82f)
            } else if (phase == Phase.SPEAKING) {
                // Follow what is heard: a stale sample (no fresh audio) or ended audio
                // targets 0, and the release eases there instead of freezing.
                targetAudioEnergy = if (speakingAudioEnded ||
                    PlaybackEnergyMonitor.sampleAgeMs() > SPEAKING_STALE_MS
                ) {
                    0f
                } else {
                    PlaybackEnergyMonitor.currentLevel()
                }
                val tau = if (targetAudioEnergy > audioEnergy) SPEAKING_ATTACK_S else SPEAKING_RELEASE_S
                audioEnergy += (targetAudioEnergy - audioEnergy) * (1f - exp(-dt / tau))
                if (targetAudioEnergy == 0f && audioEnergy < SPEAKING_FLOOR) audioEnergy = 0f
            } else if (phase == Phase.LISTENING) {
                if (previewLevelDrive) {
                    audioEnergy = PREVIEW_STATIC_ENERGY
                    targetAudioEnergy = PREVIEW_STATIC_ENERGY
                } else {
                    audioEnergy += (targetAudioEnergy - audioEnergy) * (0.30f + dt * 11f)
                    targetAudioEnergy *= (0.92f - dt * 0.28f).coerceAtLeast(0.86f)
                }
            } else {
                audioEnergy += (targetAudioEnergy - audioEnergy) * (0.24f + dt * 9f)
                targetAudioEnergy *= (0.90f - dt * 0.35f).coerceAtLeast(0.82f)
            }

            when (phase) {
                Phase.PROCESSING -> if (!previewLevelDrive) breatheTime += dt
                Phase.LISTENING -> if (!previewLevelDrive) driftTime += dt * motionSpeed
                else -> Unit
            }
            if (useA64EnhancedOverlay && !previewLevelDrive) {
                a64AnimTime += dt
            }
            if (!windingDown) {
                // Wind-down keeps its own fade; freezing here avoids a ramp-up flash.
                val glowTarget = if (glowWanted()) 1f else 0f
                val gtau = if (glowTarget > glowVis) CENTER_ATTACK_S else CENTER_RELEASE_S
                glowVis += (glowTarget - glowVis) * (1f - exp(-dt / gtau))
                if (glowTarget == 0f && glowVis < 0.01f) glowVis = 0f
            }
            if (useA64EnhancedOverlay) {
                val centerTarget = if (centerGlowWanted()) 1f else 0f
                val ctau = if (centerTarget > centerVis) CENTER_ATTACK_S else CENTER_RELEASE_S
                centerVis += (centerTarget - centerVis) * (1f - exp(-dt / ctau))
                if (centerTarget == 0f && centerVis < 0.01f) centerVis = 0f
            }

            frameSkip = !frameSkip
            if (!frameSkip) {
                invalidate()
            }

            if (frameActive && phase != Phase.NONE) {
                choreographer.postFrameCallback(this)
            }
        }
    }

    init {
        setWillNotDraw(false)
    }

    fun setDisplaySize(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            fixedWidth = width
            fixedHeight = height
        }
    }

    fun showListening(color: Int = VoiceAccentColors.WAKE_WORD_1) {
        overlayColor = color
        if (windingDown) {
            cancelWindDownAndRestore()
        }
        if (phase != Phase.NONE && fadeAlpha > 0f) {
            if (phase == Phase.LISTENING) return
            if (phase == Phase.SPEAKING || phase == Phase.PROCESSING) {
                // No snap: the level eases down from where it is.
                targetAudioEnergy = 0f
                // Continuous-dialogue gap: no ring until the next reply is audible.
                listenAfterReply = true
            }
            transitionTo(Phase.LISTENING)
        } else {
            beginSession(color, Phase.LISTENING)
        }
        targetMotionSpeed = 0.22f
        targetIntensity = 0.66f
        if (!frameActive) {
            startFrameLoop()
        }
    }

    fun showProcessing(color: Int = VoiceAccentColors.WAKE_WORD_1) {
        if (phase == Phase.NONE) {
            beginSession(color, Phase.PROCESSING)
        } else {
            overlayColor = color
            transitionTo(Phase.PROCESSING)
        }
        breatheTime = 0f
        driftTime = 0f
        targetAudioEnergy = 0f
        targetIntensity = 0.64f
        if (!frameActive) {
            startFrameLoop()
        }
    }

    fun showSpeaking(color: Int = VoiceAccentColors.WAKE_WORD_1) {
        if (phase == Phase.NONE) {
            beginSession(color, Phase.SPEAKING)
        } else {
            overlayColor = color
            transitionTo(Phase.SPEAKING)
        }
        speakingAudioEnded = false
        speakingSinceNanos = System.nanoTime()
        speakingHeardAudio = false
        targetMotionSpeed = 1.05f
        targetIntensity = 0.72f
    }

    /**
     * TTS audio really ended but the session stays in SPEAKING (continue decision,
     * chime). Ease the level to 0 and drop the speaking motion to the resting one.
     * The next phase or a new [showSpeaking] takes over as usual.
     */
    fun endSpeakingAudio() {
        if (phase != Phase.SPEAKING) return
        speakingAudioEnded = true
        targetAudioEnergy = 0f
        targetMotionSpeed = 0.22f
        targetIntensity = 0.66f
        if (!frameActive) startFrameLoop()
    }

    fun setLevelGain(gain: Float) {
        val next = gain.coerceIn(MIN_LEVEL_GAIN, MAX_LEVEL_GAIN)
        if (next == levelGain) return
        levelGain = next
        invalidate()
    }

    fun setOpacityMul(mul: Float) {
        val next = mul.coerceIn(
            PlayerSettings.MIN_EDGE_GLOW_OPACITY_MUL,
            1f,
        )
        if (next == opacityMul) return
        opacityMul = next
        invalidate()
    }

    fun setPreviewLevelDrive(enabled: Boolean) {
        previewLevelDrive = enabled
        if (enabled) {
            audioEnergy = PREVIEW_STATIC_ENERGY
            targetAudioEnergy = PREVIEW_STATIC_ENERGY
            driftTime = 0f
            a64AnimTime = 0f
        } else {
            targetAudioEnergy = 0f
        }
        if (enabled && !frameActive && phase != Phase.NONE) {
            startFrameLoop()
        }
        invalidate()
    }

    fun isShowing(): Boolean = phase != Phase.NONE && fadeAlpha > 0f

    fun feedAudioEnergy(level: Float) {
        if (previewLevelDrive) return
        if (phase == Phase.NONE || phase == Phase.SPEAKING || phase == Phase.PROCESSING) return
        val raw = level.coerceIn(0f, 1f)
        val shaped = raw * 0.40f + sqrt(raw) * 0.56f
        targetAudioEnergy = kotlin.math.max(
            targetAudioEnergy * 0.70f,
            shaped.coerceAtMost(0.95f)
        )
        if (!frameActive) {
            startFrameLoop()
        }
    }

    fun hide(onComplete: (() -> Unit)? = null) {
        hide(WIND_DOWN_MS, onComplete)
    }

    fun hidePreview(onComplete: (() -> Unit)? = null) {
        hide(PREVIEW_FADE_MS, onComplete, keepPreviewDrive = true)
    }

    private fun hide(
        durationMs: Long,
        onComplete: (() -> Unit)?,
        keepPreviewDrive: Boolean = false,
    ) {
        if (phase == Phase.NONE && fadeAlpha <= 0f) {
            onComplete?.invoke()
            return
        }
        fadeAnimator?.cancel()
        if (!keepPreviewDrive) {
            previewLevelDrive = false
        }
        // Keep animating during the fade so the level visibly winds down over
        // ~2s instead of freezing on the last frame and vanishing.
        windingDown = true
        if (!frameActive && phase != Phase.NONE) {
            startFrameLoop()
        }
        val startAlpha = fadeAlpha.coerceIn(0f, 1f)
        fadeAnimator = ValueAnimator.ofFloat(startAlpha, 0f).apply {
            duration = durationMs.coerceAtLeast(1L)
            // Gentler curve than the old 950ms fade: keeps the tail readable
            // for most of the 2s window.
            interpolator = if (keepPreviewDrive) {
                DecelerateInterpolator(1.85f)
            } else {
                DecelerateInterpolator(1.4f)
            }
            addUpdateListener { animation ->
                fadeAlpha = animation.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled) return
                    windingDown = false
                    stopFrameLoop()
                    resetVisualState()
                    fadeAnimator = null
                    onComplete?.invoke()
                }

                override fun onAnimationCancel(animation: Animator) {
                    // A new session interrupted the wind-down: skip teardown.
                    cancelled = true
                    fadeAnimator = null
                }
            })
            start()
        }
    }

    fun hideImmediate() {
        fadeAnimator?.cancel()
        fadeAnimator = null
        windingDown = false
        stopFrameLoop()
        resetVisualState()
    }

    private fun beginSession(color: Int, newPhase: Phase) {
        fadeAnimator?.cancel()
        fadeAnimator = null
        windingDown = false
        overlayColor = color
        driftTime = 0f
        breatheTime = 0f
        a64AnimTime = 0f
        audioEnergy = 0f
        targetAudioEnergy = 0f
        speakingAudioEnded = false
        listenAfterReply = false
        // Fade in from transparent so the glow never pops in at full strength.
        fadeAlpha = 0f
        fadeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = FADE_IN_MS
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { animation ->
                fadeAlpha = animation.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    fadeAlpha = 1f
                    fadeAnimator = null
                }

                override fun onAnimationCancel(animation: Animator) {
                    fadeAnimator = null
                }
            })
            start()
        }
        transitionTo(newPhase)
        startFrameLoop()
        invalidate()
    }

    private fun transitionTo(newPhase: Phase) {
        if (windingDown) {
            cancelWindDownAndRestore()
        }
        phase = newPhase
        speakingAudioEnded = false
        if (!frameActive) {
            startFrameLoop()
        }
        invalidate()
    }

    /**
     * A new session phase arrived while the end-of-session wind-down fade was
     * still running: abort the fade (its guarded listener skips teardown) and
     * ramp the alpha back to full so the overlay isn't removed mid-session.
     */
    private fun cancelWindDownAndRestore() {
        windingDown = false
        fadeAnimator?.cancel()
        val startAlpha = fadeAlpha.coerceIn(0f, 1f)
        fadeAnimator = ValueAnimator.ofFloat(startAlpha, 1f).apply {
            duration = FADE_IN_MS
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { animation ->
                fadeAlpha = animation.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled) return
                    fadeAlpha = 1f
                    fadeAnimator = null
                }

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                    fadeAnimator = null
                }
            })
            start()
        }
    }

    private fun startFrameLoop() {
        if (frameActive) return
        frameActive = true
        lastFrameNanos = 0L
        choreographer.postFrameCallback(frameCallback)
    }

    private fun stopFrameLoop() {
        frameActive = false
        lastFrameNanos = 0L
        choreographer.removeFrameCallback(frameCallback)
    }

    private fun resetVisualState() {
        windingDown = false
        phase = Phase.NONE
        fadeAlpha = 1f
        driftTime = 0f
        breatheTime = 0f
        a64AnimTime = 0f
        motionSpeed = 0.22f
        targetMotionSpeed = 0.22f
        intensity = 0.66f
        targetIntensity = 0.66f
        audioEnergy = 0f
        targetAudioEnergy = 0f
        speakingAudioEnded = false
        centerVis = 0f
        glowVis = 0f
        listenAfterReply = false
        speakingHeardAudio = false
        previewLevelDrive = false
        invalidate()
    }

    private fun contentWidth(): Int = if (width > 0) width else fixedWidth

    private fun contentHeight(): Int = if (height > 0) height else fixedHeight

    /**
     * Renders the blurred glow band once into two low-res white bitmaps (thin and
     * thick stroke). Software BlurMaskFilter is fine here: it runs a single time
     * per screen size, on a quarter-resolution canvas.
     */
    private fun ensureGlowBitmaps(w: Int, h: Int) {
        if (glowBitmapThin != null && glowBitmapFarThin != null && glowBitmapForW == w && glowBitmapForH == h) return
        glowBitmapThin?.recycle()
        glowBitmapThick?.recycle()
        glowBitmapDeepThin?.recycle()
        glowBitmapDeepThick?.recycle()
        glowBitmapFarThin?.recycle()
        glowBitmapFarThick?.recycle()

        val bw = (w * GLOW_BITMAP_SCALE).toInt().coerceAtLeast(8)
        val bh = (h * GLOW_BITMAP_SCALE).toInt().coerceAtLeast(8)
        val vminB = min(bw, bh).toFloat()
        val corner = vminB * GLOW_CORNER

        fun render(widthScale: Float, profileWidth: FloatArray, profileAlpha: FloatArray): Bitmap {
            val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
            }
            for (i in profileWidth.indices) {
                val strokeW = vminB * profileWidth[i] * widthScale
                paint.color = Color.argb(
                    (profileAlpha[i] * 255f).toInt(), 255, 255, 255
                )
                paint.strokeWidth = strokeW
                paint.maskFilter = BlurMaskFilter(
                    (strokeW * GLOW_BLUR_FACTOR).coerceAtLeast(1f),
                    BlurMaskFilter.Blur.NORMAL
                )
                c.drawRoundRect(0f, 0f, bw.toFloat(), bh.toFloat(), corner, corner, paint)
            }
            return bmp
        }

        glowBitmapThin = render(1f, GLOW_PROFILE_WIDTH, GLOW_PROFILE_ALPHA)
        glowBitmapThick = render(GLOW_THICK_SCALE, GLOW_PROFILE_WIDTH, GLOW_PROFILE_ALPHA)
        glowBitmapDeepThin = render(GLOW_INWARD_SCALE, GLOW_PROFILE_WIDTH, GLOW_PROFILE_ALPHA)
        glowBitmapDeepThick = render(
            GLOW_THICK_SCALE * GLOW_INWARD_SCALE,
            GLOW_PROFILE_WIDTH,
            GLOW_PROFILE_ALPHA
        )
        glowBitmapFarThin = render(GLOW_FAR_INWARD_SCALE, GLOW_PROFILE_WIDTH, GLOW_PROFILE_ALPHA)
        glowBitmapFarThick = render(
            GLOW_THICK_SCALE * GLOW_FAR_INWARD_SCALE,
            GLOW_PROFILE_WIDTH,
            GLOW_PROFILE_ALPHA
        )
        glowBitmapForW = w
        glowBitmapForH = h
    }

    private fun ensureA64GlowBitmaps(w: Int, h: Int) {
        if (a64GlowBitmapThin != null && a64GlowBitmapFarThin != null && a64GlowBitmapForW == w && a64GlowBitmapForH == h) return
        a64GlowBitmapThin?.recycle()
        a64GlowBitmapThick?.recycle()
        a64GlowBitmapDeepThin?.recycle()
        a64GlowBitmapDeepThick?.recycle()
        a64GlowBitmapFarThin?.recycle()
        a64GlowBitmapFarThick?.recycle()

        val bw = (w * GLOW_BITMAP_SCALE).toInt().coerceAtLeast(8)
        val bh = (h * GLOW_BITMAP_SCALE).toInt().coerceAtLeast(8)
        val vminB = min(bw, bh).toFloat()
        val corner = vminB * GLOW_CORNER

        fun render(widthScale: Float): Bitmap {
            val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
            }
            for (i in A64_GLOW_PROFILE_WIDTH.indices) {
                val strokeW = vminB * A64_GLOW_PROFILE_WIDTH[i] * widthScale
                paint.color = Color.argb(
                    (A64_GLOW_PROFILE_ALPHA[i] * 255f).toInt(), 255, 255, 255
                )
                paint.strokeWidth = strokeW
                paint.maskFilter = BlurMaskFilter(
                    (strokeW * A64_GLOW_BLUR_FACTOR).coerceAtLeast(1f),
                    BlurMaskFilter.Blur.NORMAL
                )
                c.drawRoundRect(0f, 0f, bw.toFloat(), bh.toFloat(), corner, corner, paint)
            }
            return bmp
        }

        a64GlowBitmapThin = render(1f)
        a64GlowBitmapThick = render(A64_GLOW_THICK_SCALE)
        a64GlowBitmapDeepThin = render(A64_GLOW_INWARD_SCALE)
        a64GlowBitmapDeepThick = render(A64_GLOW_THICK_SCALE * A64_GLOW_INWARD_SCALE)
        a64GlowBitmapFarThin = render(A64_GLOW_FAR_INWARD_SCALE)
        a64GlowBitmapFarThick = render(A64_GLOW_THICK_SCALE * A64_GLOW_FAR_INWARD_SCALE)
        a64GlowBitmapForW = w
        a64GlowBitmapForH = h
    }

    /**
     * Extra amplitude above the designed default. 0 at slider left;
     * 1 at the old max; up to [INWARD_MID_REACH] + [INWARD_FAR_SPAN] at the new right stop.
     * Not remapped 0–1 against the new max, so existing gain=2 keeps the old inward.
     */
    private fun extraAmplitude(gain: Float): Float =
        (gain - MIN_LEVEL_GAIN).coerceIn(0f, INWARD_MID_REACH + INWARD_FAR_SPAN)

    /**
     * 0 at quiet / slider left. Follows STT/TTS energy so the stack reaches
     * inward only when the level is up; gain only sets the amplitude of that swing.
     * Range 0–1 is the mid stack; 1–2.5 continues into the far stack.
     */
    private fun inwardBlend(gain: Float, level: Float): Float {
        val retract = if (previewLevelDrive && windingDown) fadeAlpha.coerceIn(0f, 1f) else 1f
        return extraAmplitude(gain) * level.coerceIn(0f, 1f) * retract
    }

    private fun drawStackedGlow(
        canvas: Canvas,
        thin: Bitmap,
        thick: Bitmap,
        deepThin: Bitmap,
        deepThick: Bitmap,
        farThin: Bitmap,
        farThick: Bitmap,
        pulse: Float,
        alpha: Float,
        inward: Float,
    ) {
        val p = pulse.coerceIn(0f, 1f)
        val reach = inward.coerceIn(0f, INWARD_MID_REACH + INWARD_FAR_SPAN)
        val near: Float
        val mid: Float
        val far: Float
        if (reach <= INWARD_MID_REACH) {
            near = 1f - reach
            mid = reach
            far = 0f
        } else {
            val t = ((reach - INWARD_MID_REACH) / INWARD_FAR_SPAN).coerceIn(0f, 1f)
            near = 0f
            mid = 1f - t
            far = t
        }
        val a255 = alpha * 255f
        fun draw(bmp: Bitmap, weight: Float) {
            val a = (a255 * weight).toInt().coerceIn(0, 255)
            if (a <= 0) return
            glowBitmapPaint.alpha = a
            canvas.drawBitmap(bmp, null, glowDstRect, glowBitmapPaint)
        }
        draw(thin, (1f - p) * near)
        draw(thick, p * near)
        draw(deepThin, (1f - p) * mid)
        draw(deepThick, p * mid)
        draw(farThin, (1f - p) * far)
        draw(farThick, p * far)
    }

    /**
     * Edge-lighting glow with a real gaussian falloff: per frame this only draws
     * the pre-blurred cached bitmaps, tinted via color filter and cross-faded
     * between the thin/thick variants for the breathing width.
     */
    private fun drawEdgeGlow(
        canvas: Canvas,
        w: Float,
        h: Float,
        pulse: Float,
        strength: Float,
        colorR: Int,
        colorG: Int,
        colorB: Int,
        activePhase: Phase,
        levelGain: Float
    ) {
        val p = pulse.coerceIn(0f, 1f)
        val s = strength.coerceIn(0.05f, 1f)
        val isActive = activePhase == Phase.LISTENING || activePhase == Phase.SPEAKING
        val base = if (isActive) GLOW_ALPHA_ACTIVE else GLOW_ALPHA_IDLE
        val breatheMul = 1f + extraAmplitude(levelGain).coerceAtMost(INWARD_MID_REACH)
        val breathe = (if (isActive) GLOW_BREATHE_ACTIVE else GLOW_BREATHE_IDLE) * breatheMul
        val alpha = (s * (base + p * breathe)).coerceIn(0f, 0.90f)

        ensureGlowBitmaps(w.toInt(), h.toInt())
        val thin = glowBitmapThin ?: return
        val thick = glowBitmapThick ?: return
        val deepThin = glowBitmapDeepThin ?: return
        val deepThick = glowBitmapDeepThick ?: return
        val farThin = glowBitmapFarThin ?: return
        val farThick = glowBitmapFarThick ?: return

        val tint = Color.rgb(colorR, colorG, colorB)
        if (glowTintFilter == null || glowTintColor != tint) {
            glowTintColor = tint
            glowTintFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN)
        }
        glowBitmapPaint.colorFilter = glowTintFilter
        glowDstRect.set(0f, 0f, w, h)
        val inwardLevel = if (activePhase == Phase.PROCESSING) 0f else audioEnergy.coerceIn(0f, 1f)
        drawStackedGlow(
            canvas, thin, thick, deepThin, deepThick, farThin, farThick,
            p, alpha, inwardBlend(levelGain, inwardLevel),
        )
    }

    /**
     * A64-only overlay: full-screen vignette + center pulse + deeper edge glow.
     * Completely separate from [drawEdgeGlow] — non-A64 devices never enter here.
     */
    private fun drawA64EnhancedOverlay(
        canvas: Canvas,
        w: Float,
        h: Float,
        pulse: Float,
        strength: Float,
        colorR: Int,
        colorG: Int,
        colorB: Int,
        activePhase: Phase,
        levelGain: Float
    ) {
        drawA64ScreenVignette(canvas, w, h, strength, colorR, colorG, colorB)
        drawA64CenterPulse(canvas, w, h, pulse, strength, colorR, colorG, colorB)

        val p = pulse.coerceIn(0f, 1f)
        val s = strength.coerceIn(0.05f, 1f)
        val isActive = activePhase == Phase.LISTENING || activePhase == Phase.SPEAKING
        val base = if (isActive) GLOW_ALPHA_ACTIVE else GLOW_ALPHA_IDLE
        val breatheMul = 1f + extraAmplitude(levelGain).coerceAtMost(INWARD_MID_REACH)
        val breathe = (if (isActive) GLOW_BREATHE_ACTIVE else GLOW_BREATHE_IDLE) * breatheMul
        val alpha = (s * (base + p * breathe)).coerceIn(0f, 0.90f)

        ensureA64GlowBitmaps(w.toInt(), h.toInt())
        val thin = a64GlowBitmapThin ?: return
        val thick = a64GlowBitmapThick ?: return
        val deepThin = a64GlowBitmapDeepThin ?: return
        val deepThick = a64GlowBitmapDeepThick ?: return
        val farThin = a64GlowBitmapFarThin ?: return
        val farThick = a64GlowBitmapFarThick ?: return

        val tint = Color.rgb(colorR, colorG, colorB)
        if (glowTintFilter == null || glowTintColor != tint) {
            glowTintColor = tint
            glowTintFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN)
        }
        glowBitmapPaint.colorFilter = glowTintFilter
        glowDstRect.set(0f, 0f, w, h)
        val inwardLevel = if (activePhase == Phase.PROCESSING) 0f else audioEnergy.coerceIn(0f, 1f)
        drawStackedGlow(
            canvas, thin, thick, deepThin, deepThick, farThin, farThick,
            p, alpha, inwardBlend(levelGain, inwardLevel),
        )
    }

    /**
     * Bakes a full-screen radial vignette once (center clear → edge deep). Tinted
     * at draw time; A64 devices only.
     */
    private fun ensureA64VignetteBitmap(w: Int, h: Int) {
        if (a64VignetteBitmap != null && a64VignetteForW == w && a64VignetteForH == h) return

        a64VignetteBitmap?.recycle()
        val bw = (w * A64_VIGNETTE_BITMAP_SCALE).toInt().coerceAtLeast(8)
        val bh = (h * A64_VIGNETTE_BITMAP_SCALE).toInt().coerceAtLeast(8)
        val cx = bw / 2f
        val cy = bh / 2f
        val radius = maxOf(bw, bh) * 0.87f

        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val vignetteColors = IntArray(A64_VIGNETTE_ALPHAS.size) { i ->
            Color.argb(A64_VIGNETTE_ALPHAS[i], 255, 255, 255)
        }
        paint.shader = RadialGradient(
            cx, cy, radius,
            vignetteColors,
            A64_VIGNETTE_STOPS,
            Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, bw.toFloat(), bh.toFloat(), paint)
        a64VignetteBitmap = bmp
        a64VignetteForW = w
        a64VignetteForH = h
    }

    private fun drawA64ScreenVignette(
        canvas: Canvas,
        w: Float,
        h: Float,
        strength: Float,
        colorR: Int,
        colorG: Int,
        colorB: Int
    ) {
        ensureA64VignetteBitmap(w.toInt(), h.toInt())
        val bmp = a64VignetteBitmap ?: return

        val tint = Color.rgb(colorR, colorG, colorB)
        if (glowTintFilter == null || glowTintColor != tint) {
            glowTintColor = tint
            glowTintFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN)
        }
        a64VignettePaint.colorFilter = glowTintFilter
        a64VignettePaint.alpha = (strength.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255)
        glowDstRect.set(0f, 0f, w, h)
        canvas.drawBitmap(bmp, null, glowDstRect, a64VignettePaint)
    }

    /**
     * A64 green center glow is wanted only while TTS is really audible in SPEAKING
     * (bridged briefly from showSpeaking to the first sample), or in PROCESSING
     * (the thinking breathe). LISTENING, the continue gap after the audio ended,
     * and the end-of-session wind-down never show it.
     */
    private fun centerGlowWanted(): Boolean {
        if (previewLevelDrive) return true
        if (windingDown) return false
        return when (phase) {
            Phase.PROCESSING -> true
            Phase.SPEAKING -> {
                if (speakingAudioEnded) return false
                val audible = PlaybackEnergyMonitor.sampleAgeMs() <= CENTER_TTS_HOLD_MS
                if (audible) speakingHeardAudio = true
                audible || (!speakingHeardAudio &&
                    System.nanoTime() - speakingSinceNanos < CENTER_TTS_BRIDGE_MS * 1_000_000L)
            }
            else -> false
        }
    }

    /**
     * Edge glow ring wanted: TTS audible (same rule as [centerGlowWanted]), PROCESSING
     * breathe, or a session-opening LISTENING (wake turn without floating captions).
     * The LISTENING re-opened after a reply is the continue gap and stays empty.
     */
    private fun glowWanted(): Boolean {
        if (previewLevelDrive) return true
        return when (phase) {
            Phase.LISTENING -> !listenAfterReply
            Phase.NONE -> false
            else -> centerGlowWanted()
        }
    }

    private fun drawA64CenterPulse(
        canvas: Canvas,
        w: Float,
        h: Float,
        pulse: Float,
        strength: Float,
        colorR: Int,
        colorG: Int,
        colorB: Int
    ) {
        // Not drawn at all outside TTS playback / PROCESSING: no resting breathe in the
        // continue gap or while listening, so it can never sit there stuck.
        val vis = centerVis.coerceIn(0f, 1f)
        if (vis <= 0f) return
        val cx = w * 0.5f
        val cy = h * 0.5f
        val vmin = min(w, h)
        val breathe = (sin(a64AnimTime * 1.6f) + 1f) * 0.5f
        val p = pulse.coerceIn(0f, 1f)
        val radius = vmin * (0.20f + breathe * 0.05f + p * 0.03f)
        val coreAlpha = vis * strength * (0.035f + breathe * 0.025f + p * 0.045f)
        val midAlpha = coreAlpha * 0.45f

        a64CenterPaint.shader = RadialGradient(
            cx, cy, radius,
            intArrayOf(
                Color.argb((coreAlpha * 255f).toInt().coerceIn(0, 255), colorR, colorG, colorB),
                Color.argb((midAlpha * 255f).toInt().coerceIn(0, 255), colorR, colorG, colorB),
                Color.argb(0, colorR, colorG, colorB)
            ),
            floatArrayOf(0f, 0.38f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, a64CenterPaint)
        a64CenterPaint.shader = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (phase == Phase.NONE || fadeAlpha <= 0f) return
        // Continue gap / audio ended: nothing on screen, not a resting ring.
        if (glowVis <= 0.01f) return

        val w = contentWidth().toFloat()
        val h = contentHeight().toFloat()
        if (w <= 0f || h <= 0f) return

        val colorR = Color.red(overlayColor)
        val colorG = Color.green(overlayColor)
        val colorB = Color.blue(overlayColor)

        val pulse: Float
        var strength: Float
        val drawGain = if (phase == Phase.PROCESSING) MIN_LEVEL_GAIN else levelGain

        when (phase) {
            Phase.PROCESSING -> {
                val breathe = (sin(breatheTime * 4.6f) + 1f) * 0.5f
                pulse = breathe
                strength = (intensity + 0.04f).coerceIn(0.36f, 0.76f) * fadeAlpha
            }
            Phase.SPEAKING -> {
                val energy = audioEnergy.coerceIn(0f, 1f)
                val extra = extraAmplitude(drawGain)
                val basePulse = (sqrt(energy) * 0.72f + energy * 0.38f).coerceIn(0f, 1f)
                pulse = (basePulse + (1f - basePulse) * energy * extra).coerceIn(0f, 1f)
                strength = (intensity + 0.05f + energy * 0.05f * (1f + extra))
                    .coerceIn(0.40f, 0.82f) * fadeAlpha
            }
            else -> {
                val energy = audioEnergy.coerceIn(0f, 1f)
                val extra = extraAmplitude(drawGain)
                val slowBreathe = if (previewLevelDrive) {
                    0f
                } else {
                    (sin(driftTime * 1.4f) + 1f) * 0.5f
                }
                val basePulse = (sqrt(energy) * 0.95f + slowBreathe * 0.12f).coerceIn(0f, 1f)
                pulse = (basePulse + (1f - basePulse) * energy * extra).coerceIn(0f, 1f)
                strength = (intensity + 0.05f + energy * 0.08f * (1f + extra))
                    .coerceIn(0.38f, 0.85f) * fadeAlpha
            }
        }
        strength *= opacityMul * glowVis

        if (useA64EnhancedOverlay) {
            drawA64EnhancedOverlay(canvas, w, h, pulse, strength, colorR, colorG, colorB, phase, drawGain)
        } else {
            drawEdgeGlow(canvas, w, h, pulse, strength, colorR, colorG, colorB, phase, drawGain)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        hideImmediate()
        glowBitmapThin?.recycle()
        glowBitmapThick?.recycle()
        glowBitmapDeepThin?.recycle()
        glowBitmapDeepThick?.recycle()
        glowBitmapFarThin?.recycle()
        glowBitmapFarThick?.recycle()
        a64GlowBitmapThin?.recycle()
        a64GlowBitmapThick?.recycle()
        a64GlowBitmapDeepThin?.recycle()
        a64GlowBitmapDeepThick?.recycle()
        a64GlowBitmapFarThin?.recycle()
        a64GlowBitmapFarThick?.recycle()
        a64VignetteBitmap?.recycle()
        glowBitmapThin = null
        glowBitmapThick = null
        glowBitmapDeepThin = null
        glowBitmapDeepThick = null
        glowBitmapFarThin = null
        glowBitmapFarThick = null
        a64GlowBitmapThin = null
        a64GlowBitmapThick = null
        a64GlowBitmapDeepThin = null
        a64GlowBitmapDeepThick = null
        a64GlowBitmapFarThin = null
        a64GlowBitmapFarThick = null
        a64VignetteBitmap = null
        glowBitmapForW = 0
        glowBitmapForH = 0
        a64GlowBitmapForW = 0
        a64GlowBitmapForH = 0
        a64VignetteForW = 0
        a64VignetteForH = 0
    }
}

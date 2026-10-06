package com.example.ava.services

import android.animation.Animator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Service
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import com.example.ava.ui.AvaSystemChrome
import com.example.ava.utils.BlurCompat
import com.example.ava.utils.DeviceFeatureManager
import com.example.ava.utils.EmotionKeywordDetector
import com.example.ava.utils.EmotionKeywordDetector.Expression
import kotlin.math.min
import kotlin.math.roundToInt

class FloatingWindowService : Service() {

    private enum class State { IDLE, LISTENING, PROCESSING, SPEAKING }
    private enum class DisplayMode { NONE, STREAMING, STATIC_TEXT, KARAOKE }

    private data class PerformanceProfile(
        val enableGlowBlur: Boolean,
        val useSmoothScroll: Boolean,
        val streamingUiThrottleMs: Long,
        val pulseAmplitude: Float,
        val glowStrength: Float
    ) {
        companion object {
            fun balanced() = PerformanceProfile(
                enableGlowBlur = true,
                useSmoothScroll = true,
                streamingUiThrottleMs = 0L,
                pulseAmplitude = 0.02f,
                glowStrength = 1.0f
            )

            fun lowPower() = PerformanceProfile(
                enableGlowBlur = false,
                useSmoothScroll = false,
                streamingUiThrottleMs = 90L,
                pulseAmplitude = 0.012f,
                glowStrength = 0.65f
            )
        }
    }

    /** Seed for one emotion sparkle; lives outside EsperSphereView (inner classes can't nest). */
    private class BurstParticle {
        var angle = 0f
        var dist = 0f
        var drift = 0f
        var size = 0f
        var stagger = 0f
        var style = 0
    }

    /**
     * Faint feathered dark veil behind the caption: a flat low-alpha plateau with a linear
     * fade at all four edges (Photoshop-style feather) — reads as fog, not as an oval or
     * a plate. Rendered once per size into a half-res bitmap (vertical gradient × horizontal
     * mask via DST_IN) and then blitted: composing two same-type gradient shaders is broken
     * on pre-API-28 hardware canvases, and a one-off ~150 KB software render followed by a
     * plain bitmap draw is the cheapest per-frame option on low-end devices anyway.
     *
     * The 3-line caption slot stays fixed; [coverHeightPx] hugs the used lines from the
     * top so a 1–2 line sentence does not sit above an empty dark plateau.
     */
    private class CaptionFogDrawable : Drawable() {
        private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
        private val charcoalFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val charcoalStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private var fog: Bitmap? = null
        /** 0 = hidden. Otherwise the cover, including card padding. */
        private var coverHeightPx = 0
        /** Caption-only pages sit in the middle of the slot; sphere pages hug the top baseline. */
        private var coverCentered = false
        /** Button-turn plate: same charcoal family as the FAB, not the sphere mist. */
        private var charcoalPlate = false

        fun setCoverHeight(px: Int, centered: Boolean = false) {
            val h = px.coerceAtLeast(0)
            if (h == coverHeightPx && centered == coverCentered) return
            coverHeightPx = h
            coverCentered = centered
            if (!charcoalPlate) rebuildFog()
            invalidateSelf()
        }

        fun setCharcoalPlate(on: Boolean) {
            if (charcoalPlate == on) return
            charcoalPlate = on
            if (charcoalPlate) {
                fog?.recycle()
                fog = null
            } else {
                rebuildFog()
            }
            invalidateSelf()
        }

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            if (!charcoalPlate) rebuildFog()
        }

        private fun coverRect(): Rect {
            val b = bounds
            if (coverHeightPx <= 0 || b.isEmpty) return Rect()
            val h = coverHeightPx.coerceAtMost(b.height())
            val top = if (coverCentered) b.top + (b.height() - h) / 2 else b.top
            return Rect(b.left, top, b.right, top + h)
        }

        private fun rebuildFog() {
            val r = coverRect()
            val w = (r.width() / 2).coerceAtLeast(0)
            val h = (r.height() / 2).coerceAtLeast(0)
            if (w == 0 || h == 0) {
                fog?.recycle()
                fog = null
                return
            }
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val wf = w.toFloat()
            val hf = h.toFloat()
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            val plateau = if (charcoalPlate) CHARCOAL_PLATE else PLATEAU_COLOR
            val featherV = if (charcoalPlate) CHARCOAL_FEATHER_V else FEATHER_V
            val featherH = if (charcoalPlate) CHARCOAL_FEATHER_H else FEATHER_H
            p.shader = LinearGradient(
                0f, 0f, 0f, hf,
                intArrayOf(0x00000000, plateau, plateau, 0x00000000),
                floatArrayOf(0f, featherV, 1f - featherV, 1f),
                Shader.TileMode.CLAMP
            )
            if (charcoalPlate) {
                val rad = minOf(wf, hf) * 0.22f
                canvas.drawRoundRect(RectF(0f, 0f, wf, hf), rad, rad, p)
            } else {
                canvas.drawRect(0f, 0f, wf, hf, p)
            }
            // Multiply in the horizontal feather (DST_IN keeps dst scaled by src alpha).
            p.shader = LinearGradient(
                0f, 0f, wf, 0f,
                intArrayOf(0x00FFFFFF, 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0x00FFFFFF),
                floatArrayOf(0f, featherH, 1f - featherH, 1f),
                Shader.TileMode.CLAMP
            )
            p.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            canvas.drawRect(0f, 0f, wf, hf, p)
            val previous = fog
            fog = bmp
            previous?.recycle()
        }

        override fun draw(canvas: Canvas) {
            if (charcoalPlate) {
                drawCharcoalPlate(canvas)
                return
            }
            val bmp = fog ?: return
            val dest = coverRect()
            if (dest.isEmpty) return
            canvas.drawBitmap(bmp, null, dest, bitmapPaint)
        }

        private fun drawCharcoalPlate(canvas: Canvas) {
            val dest = coverRect()
            if (dest.isEmpty) return
            val plate = RectF(dest)
            val rad = minOf(plate.height() / 2f, plate.width() * 0.045f + 14f)
            val drawableA = bitmapPaint.alpha.coerceIn(0, 255)
            val fillA = (Color.alpha(CHARCOAL_PLATE) * drawableA / 255f).toInt()
            charcoalFill.color = (CHARCOAL_PLATE and 0x00FFFFFF) or (fillA shl 24)
            canvas.drawRoundRect(plate, rad, rad, charcoalFill)
            val strokeA = (0x33 * drawableA / 255f).toInt()
            charcoalStroke.color = 0x00FFFFFF or (strokeA shl 24)
            charcoalStroke.strokeWidth = 1.2f * (dest.width() / 360f).coerceIn(0.8f, 1.6f)
            canvas.drawRoundRect(plate, rad, rad, charcoalStroke)
        }

        override fun setAlpha(alpha: Int) {
            bitmapPaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            bitmapPaint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        companion object {
            /** ~29% black — faint mist, just enough to lift white text off bright art. */
            private const val PLATEAU_COLOR = 0x4A000000
            /** Button-turn plate: charcoal glass ~75%. 60% read as too thin behind TTS. */
            private const val CHARCOAL_PLATE = 0xC0181A1C.toInt()
            /** Feather band as a fraction of each axis, per edge. */
            private const val FEATHER_V = 0.28f
            private const val FEATHER_H = 0.10f
            private const val CHARCOAL_FEATHER_V = 0.10f
            private const val CHARCOAL_FEATHER_H = 0.07f
        }
    }
    

    private inner class EsperSphereView(
        context: Context,
        private var fixedScreenWidth: Int,
        private var fixedScreenHeight: Int
    ) : View(context) {
        private val density = context.resources.displayMetrics.density
        private fun dp(v: Float) = v * density

        init {
            // BlurMaskFilter on a hardware canvas crashes the RenderThread on old APIs.
            BlurCompat.forceSoftwareLayerIfNeeded(this)
        }
        

        private var calculatedSphereRatio = 0.32f
        
        private var currentExpression = Expression.NEUTRAL
        
        private var sphereDiameter = dp(120f)
        private var sphereRadius = sphereDiameter / 2f
        private var sphereCenterX = 0f
        private var sphereCenterY = 0f
        private var targetCenterY = 0f
        private var baseCenterY = 0f
        private var topCenterY = 0f
        /** True while listening/processing entrance AnimatorSet is owning alpha/translation. */
        var entranceInProgress: Boolean = false
        
        
        private var sphereColor = 0xFF6366F1.toInt()
        private var targetColor = sphereColor
        /** Fixed start of the current tint blend so the ease is a clean lerp, not a moving-target chase. */
        private var colorFrom = sphereColor
        private var colorTransition = 1f
        /** Blink-masked expression swap: committed at the blink trough (see blinkAnimator). */
        private var pendingExpression: Expression? = null
        private var pendingEyesOnly = false
        
        private var statusText = "LISTENING"
        private var statusDotColor = 0xFF00FFAA.toInt()
        private var showStatusBadge = false
        /** Soft badge fade; kept separate from idle pulse so entrance stopAnimations won't kill it. */
        private var badgeAlpha = 0f
        private var targetBadgeAlpha = 0f
        private var badgeFadeAnimator: ValueAnimator? = null
        private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(204, 255, 255, 255)
            textSize = dp(14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.08f
            textAlign = Paint.Align.CENTER
        }
        
        private val expressionColors = mapOf(
            Expression.NEUTRAL   to 0xFF6366F1.toInt(),
            Expression.HAPPY     to 0xFF22C55E.toInt(),
            Expression.SAD       to 0xFF64748B.toInt(),
            Expression.SURPRISED to 0xFFF59E0B.toInt(),
            Expression.THINKING  to 0xFF8B5CF6.toInt(),
            Expression.SLEEPY    to 0xFF475569.toInt(),
            Expression.EXCITED   to 0xFFEC4899.toInt(),
            Expression.CONFUSED  to 0xFFF97316.toInt(),
            Expression.LISTENING to 0xFF3B82F6.toInt(),
            Expression.SPEAKING  to 0xFF06B6D4.toInt(),
            Expression.ANGRY     to 0xFFEF4444.toInt(),
            Expression.SHY       to 0xFFFB7185.toInt(),
            Expression.PROUD     to 0xFFFBBF24.toInt(),
            Expression.CURIOUS   to 0xFF14B8A6.toInt()
        )
        
        private var eyeWidth = dp(10f)
        private var eyeHeight = dp(30f)
        private var eyeCornerRadius = dp(3f)
        private var eyeGap = dp(30f)
        
        private var eyeHeightFactor = 1f
        private var eyeLeftPercent = 0.47f
        private var floatOffsetY = 0f
        private var pulseScale = 1f
        /** Drawn-radius shrink for session exit only — keeps the ball on its own center. */
        private var exitScale = 1f
        
        private val spherePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        /** Own paint — stroke state must not leak into eye/badge fills. */
        private val burstPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val burstParticles = Array(10) { BurstParticle() }
        private var burstStartMs = 0L
        /** Positive/high-energy moods only — sparkles around a SAD/ANGRY face read wrong. */
        private val burstExpressions = setOf(
            Expression.HAPPY, Expression.EXCITED, Expression.SURPRISED,
            Expression.SHY, Expression.PROUD, Expression.CURIOUS
        )
        
        private var glowAlpha = 0.06f
        private var glowScale = 1.15f
        
        
        private var isAnimating = false
        private var animationStartTime = 0L
        


        private val blinkAnimator = ValueAnimator.ofFloat(1f, 0.1f, 1f).apply {
            duration = 160L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                eyeHeightFactor = it.animatedValue as Float
                // Commit a queued expression while the lids are ~closed, so the eye-shape
                // change is hidden behind the blink instead of popping in one frame.
                val pend = pendingExpression
                if (pend != null && eyeHeightFactor <= 0.25f) {
                    val eyesOnly = pendingEyesOnly
                    pendingExpression = null
                    pendingEyesOnly = false
                    applyExpressionNow(pend, changeColor = !eyesOnly)
                }
                invalidate()
            }
        }
        
        private var blinkInterval = 4000L
        
        private val blinkRunnable = object : Runnable {
            override fun run() {
                if (isAnimating) {
                    blinkAnimator.start()
                    handler?.postDelayed(this, blinkInterval)
                }
            }
        }
        
        private val mainAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 30000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                val now = System.currentTimeMillis()
                if (animationStartTime == 0L) animationStartTime = now
                val elapsed = now - animationStartTime
                


                val lookP = (elapsed % 10000L) / 10000f
                eyeLeftPercent = when {
                    lookP < 0.40f -> 0.47f
                    lookP < 0.45f -> 0.47f - (lookP - 0.40f) / 0.05f * 0.07f
                    lookP < 0.55f -> 0.40f
                    lookP < 0.60f -> 0.40f + (lookP - 0.55f) / 0.05f * 0.14f
                    lookP < 0.70f -> 0.54f
                    lookP < 0.75f -> 0.54f - (lookP - 0.70f) / 0.05f * 0.07f
                    else -> 0.47f
                }
                


                val floatP = (elapsed % 6000L) / 6000f
                floatOffsetY = if (floatP < 0.5f) {
                    -dp(10f) * (floatP / 0.5f)
                } else {
                    -dp(10f) * (1f - (floatP - 0.5f) / 0.5f)
                }
                
                val pulseP = (elapsed % 4000L) / 4000f
                pulseScale = 1f + performanceProfile.pulseAmplitude * kotlin.math.sin(pulseP * 2 * Math.PI.toFloat())
                

                val glowP = (elapsed % 4000L) / 4000f
                glowAlpha = (0.04f + 0.03f * kotlin.math.sin(glowP * 2 * Math.PI.toFloat()).toFloat()) * performanceProfile.glowStrength
                glowScale = 1.12f + (0.05f * performanceProfile.glowStrength) * kotlin.math.sin(glowP * 2 * Math.PI.toFloat()).toFloat()
                
                if (colorTransition < 1f) {
                    colorTransition = (colorTransition + 0.05f).coerceAtMost(1f)
                    // Smoothstep on a lerp from a fixed start — even ease-in-out, no front-loaded jump.
                    val eased = colorTransition * colorTransition * (3f - 2f * colorTransition)
                    sphereColor = blendColors(colorFrom, targetColor, eased)
                }

                postInvalidateOnAnimation()
            }
        }

        /**
         * Center ↔ speaking-top slide + badge fade. Must keep running even when idle
         * pulse is stopped for overlay entrance, otherwise the sphere freezes at the
         * previous (upper / no-badge) pose and pops when animations restart.
         */
        private val poseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 30_000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                var dirty = false
                val yDelta = targetCenterY - sphereCenterY
                if (kotlin.math.abs(yDelta) > 0.5f) {
                    sphereCenterY += yDelta * 0.14f
                    dirty = true
                } else if (sphereCenterY != targetCenterY) {
                    sphereCenterY = targetCenterY
                    dirty = true
                }

                // Don't fight revealStatusBadge's dedicated fade animator.
                if (badgeFadeAnimator?.isRunning != true) {
                    val aDelta = targetBadgeAlpha - badgeAlpha
                    if (kotlin.math.abs(aDelta) > 0.01f) {
                        badgeAlpha += aDelta * 0.20f
                        dirty = true
                    } else if (badgeAlpha != targetBadgeAlpha) {
                        badgeAlpha = targetBadgeAlpha
                        dirty = true
                    }
                    if (badgeAlpha <= 0.01f && targetBadgeAlpha <= 0f) {
                        showStatusBadge = false
                        badgeAlpha = 0f
                    }
                }

                if (dirty) {
                    invalidate()
                } else if (!isPoseAnimatingNeeded()) {
                    cancel()
                }
            }
        }

        private fun isPoseAnimatingNeeded(): Boolean {
            return kotlin.math.abs(sphereCenterY - targetCenterY) > 0.5f ||
                kotlin.math.abs(badgeAlpha - targetBadgeAlpha) > 0.01f
        }

        private fun ensurePoseAnimating() {
            // isRunning (not isStarted): minSdk 21; isStarted is API 22+.
            if (!poseAnimator.isRunning) poseAnimator.start()
        }
        
        private fun blendColors(from: Int, to: Int, ratio: Float): Int {
            val r = ((Color.red(from) * (1 - ratio) + Color.red(to) * ratio)).toInt()
            val g = ((Color.green(from) * (1 - ratio) + Color.green(to) * ratio)).toInt()
            val b = ((Color.blue(from) * (1 - ratio) + Color.blue(to) * ratio)).toInt()
            return Color.rgb(r, g, b)
        }
        
        /** Call once after construction so diameter matches screen before first paint. */
        fun ensureScreenMetrics() {
            if (fixedScreenWidth > 0 && fixedScreenHeight > 0) {
                applyLayoutMetrics(fixedScreenWidth, fixedScreenHeight, resetPose = true)
            }
        }

        /**
         * @param resetPose When true (first metrics), snap Y to center. When false (layout
         * pass during entrance), keep current/target Y so the drop isn't yanked.
         */
        private fun applyLayoutMetrics(w: Int, h: Int, resetPose: Boolean) {
            if (w <= 0 || h <= 0) return
            fixedScreenWidth = w
            fixedScreenHeight = h
            sphereCenterX = w / 2f
            baseCenterY = h * 0.50f
            val localAspectRatio = w.toFloat() / h.toFloat()
            val localIsSquare = localAspectRatio in 0.9f..1.1f
            val localMinSide = minOf(w, h)
            val localSmallestWidthDp = minOf(w, h) / density
            val localIsLandscape = w > h
            // Speaking: a bit above center so the caption can sit under the sphere with a small gap.
            topCenterY = when {
                localIsLandscape -> h * 0.40f
                localIsSquare -> h * 0.38f
                localSmallestWidthDp >= 600f -> h * 0.39f
                else -> h * 0.40f
            }

            val wasTop = kotlin.math.abs(targetCenterY - topCenterY) <
                kotlin.math.abs(targetCenterY - baseCenterY) && topCenterY > 0f
            // Only retarget if we already had a pose; first apply always centers.
            if (resetPose || targetCenterY == 0f) {
                sphereCenterY = baseCenterY
                targetCenterY = baseCenterY
            } else {
                targetCenterY = if (wasTop) topCenterY else baseCenterY
                // Keep sphereCenterY; poseAnimator eases toward the updated target.
            }

            calculatedSphereRatio = when {
                localIsSquare && localMinSide <= 340 -> 250f / localMinSide.toFloat()
                localIsSquare -> 0.65f
                localSmallestWidthDp >= 600f -> 0.35f
                localIsLandscape -> 0.50f
                else -> 0.42f
            }
            sphereDiameter = localMinSide * calculatedSphereRatio
            sphereRadius = sphereDiameter / 2f

            val scale = sphereDiameter / dp(200f)
            eyeWidth = dp(10f) * scale
            eyeHeight = dp(30f) * scale
            eyeGap = dp(30f) * scale
            eyeCornerRadius = dp(3f) * scale
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (w <= 0 || h <= 0) return
            val sizeJump = oldw <= 0 || oldh <= 0 ||
                kotlin.math.abs(w - oldw) > 2 || kotlin.math.abs(h - oldh) > 2
            if (!sizeJump) return
            // Never snap pose mid-entrance — that reads as the effect being "cut off".
            applyLayoutMetrics(w, h, resetPose = !entranceInProgress && oldw <= 0)
            invalidate()
        }
        
        /**
         * @param animated When false, snap immediately (use before fade-in from hidden so
         * we never flash the leftover speaking-top pose).
         */
        fun moveToTop(animated: Boolean = true) {
            targetCenterY = topCenterY
            if (!animated) {
                sphereCenterY = targetCenterY
                invalidate()
            } else {
                ensurePoseAnimating()
            }
        }

        fun moveToCenter(animated: Boolean = true) {
            targetCenterY = baseCenterY
            if (!animated) {
                sphereCenterY = targetCenterY
                invalidate()
            } else {
                ensurePoseAnimating()
            }
        }

        /** Speaking-mode sphere center Y (layout space). */
        fun speakingCenterY(): Float = topCenterY

        fun currentSphereRadius(): Float = sphereRadius
        
        fun startAnimations() {
            if (entranceInProgress) return
            isAnimating = true
            animationStartTime = System.currentTimeMillis()
            pulseScale = 1f
            floatOffsetY = 0f
            exitScale = 1f
            mainAnimator.start()
            handler?.postDelayed(blinkRunnable, 1500L)
            invalidate()
        }
        
        fun stopAnimations() {
            isAnimating = false
            blinkAnimator.cancel()
            mainAnimator.cancel()
            handler?.removeCallbacks(blinkRunnable)
            pulseScale = 1f
            floatOffsetY = 0f
            exitScale = 1f
            // Stopping mid-blink must not strand a queued swap — flush it (snap).
            pendingExpression?.let {
                val eyesOnly = pendingEyesOnly
                pendingExpression = null
                pendingEyesOnly = false
                applyExpressionNow(it, changeColor = !eyesOnly, snapColor = true)
            }
        }

        /**
         * Pause life-motion for exit without snapping pose, tint, or float.
         * Mid-blink lids reopen so the face doesn't freeze half-closed.
         */
        fun freezeForExit() {
            isAnimating = false
            blinkAnimator.cancel()
            mainAnimator.cancel()
            poseAnimator.cancel()
            cancelBadgeFade()
            handler?.removeCallbacks(blinkRunnable)
            eyeHeightFactor = 1f
            pendingExpression = null
            pendingEyesOnly = false
            burstStartMs = 0L
            invalidate()
        }

        fun setExitScale(value: Float) {
            exitScale = value
            invalidate()
        }

        fun resetExitVisuals() {
            exitScale = 1f
            pulseScale = 1f
            floatOffsetY = 0f
            eyeHeightFactor = 1f
            burstStartMs = 0L
            invalidate()
        }
        
        fun isAnimating() = isAnimating
        
        override fun onDraw(canvas: Canvas) {
            val cx = sphereCenterX
            val cy = sphereCenterY + floatOffsetY
            val r = sphereRadius * pulseScale * exitScale
            

            val glowRadius = r * 0.08f
            glowPaint.maskFilter = if (performanceProfile.enableGlowBlur) {
                BlurMaskFilter(glowRadius, BlurMaskFilter.Blur.NORMAL)
            } else {
                null
            }
            glowPaint.color = Color.argb((glowAlpha * 255).toInt(), sphereColor shr 16 and 0xFF, sphereColor shr 8 and 0xFF, sphereColor and 0xFF)
            canvas.drawCircle(cx, cy, r * glowScale, glowPaint)
            glowPaint.maskFilter = null
            

            spherePaint.color = sphereColor
            canvas.drawCircle(cx, cy, r, spherePaint)
            

            spherePaint.style = Paint.Style.STROKE
            spherePaint.strokeWidth = r * 0.05f
            spherePaint.color = Color.argb(30, 255, 255, 255)
            canvas.drawCircle(cx, cy, r * 0.95f, spherePaint)
            spherePaint.style = Paint.Style.FILL
            

            drawEyes(canvas, cx, cy, r)

            drawEmotionBurst(canvas, cx, cy, r)
            

            if (badgeAlpha > 0.01f) {
                val fixedBadgeY = sphereCenterY + sphereRadius + dp(40f)
                drawStatusBadge(canvas, sphereCenterX, fixedBadgeY, badgeAlpha.coerceIn(0f, 1f))
            }
        }
        
        private fun drawStatusBadge(canvas: Canvas, cx: Float, badgeY: Float, alpha: Float) {
            val textWidth = badgeTextPaint.measureText(statusText)
            val dotRadius = dp(4f)
            val padding = dp(16f)
            val badgeWidth = textWidth + dotRadius * 2 + dp(10f) + padding * 2
            val badgeHeight = dp(32f)
            val badgeLeft = cx - badgeWidth / 2
            val badgeTop = badgeY - badgeHeight / 2
            val a = alpha.coerceIn(0f, 1f)

            badgePaint.color = Color.argb((20 * a).toInt(), 255, 255, 255)
            canvas.drawRoundRect(badgeLeft, badgeTop, badgeLeft + badgeWidth, badgeTop + badgeHeight, dp(16f), dp(16f), badgePaint)

            val dr = Color.red(statusDotColor)
            val dg = Color.green(statusDotColor)
            val db = Color.blue(statusDotColor)
            badgePaint.color = Color.argb((255 * a).toInt(), dr, dg, db)
            val dotCx = badgeLeft + padding + dotRadius
            canvas.drawCircle(dotCx, badgeY, dotRadius, badgePaint)

            badgeTextPaint.alpha = (204 * a).toInt()
            val textX = dotCx + dotRadius + dp(10f) + textWidth / 2
            canvas.drawText(statusText, textX, badgeY + dp(5f), badgeTextPaint)
            badgeTextPaint.alpha = 204
        }
        
        private fun cancelBadgeFade() {
            badgeFadeAnimator?.cancel()
            badgeFadeAnimator = null
        }

        /**
         * @param animated Fade badge in/out via pose lerp. Prefer [revealStatusBadge] for
         * entrance so the chip eases in slower than the ball drop.
         */
        fun setStatusBadge(text: String, dotColor: Int, visible: Boolean, animated: Boolean = true) {
            cancelBadgeFade()
            statusText = text
            statusDotColor = dotColor
            showStatusBadge = visible || badgeAlpha > 0.01f
            targetBadgeAlpha = if (visible) 1f else 0f
            if (!animated) {
                badgeAlpha = targetBadgeAlpha
                if (!visible) showStatusBadge = false
                invalidate()
            } else {
                ensurePoseAnimating()
            }
        }

        fun isStatusBadgeMostlyVisible(): Boolean =
            badgeAlpha > 0.55f && targetBadgeAlpha > 0.55f

        /**
         * Commit chip copy, then ease alpha in on its own clock (slower than the ball).
         * If the chip is already up, only swap label/color — don't flash alpha to 0.
         */
        fun revealStatusBadge(text: String, dotColor: Int, fadeMs: Long, startDelayMs: Long = 0L) {
            val alreadyUp = isStatusBadgeMostlyVisible()
            cancelBadgeFade()
            statusText = text
            statusDotColor = dotColor
            showStatusBadge = true
            targetBadgeAlpha = 1f
            if (alreadyUp) {
                badgeAlpha = 1f
                invalidate()
                return
            }
            badgeAlpha = 0f
            invalidate()
            if (fadeMs <= 0L) {
                badgeAlpha = 1f
                return
            }
            val anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = fadeMs
                startDelay = startDelayMs.coerceAtLeast(0L)
                interpolator = DecelerateInterpolator(1.35f)
                addUpdateListener {
                    badgeAlpha = it.animatedValue as Float
                    targetBadgeAlpha = 1f
                    invalidate()
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        badgeAlpha = 1f
                        targetBadgeAlpha = 1f
                        if (badgeFadeAnimator === this@apply) badgeFadeAnimator = null
                    }

                    override fun onAnimationCancel(animation: android.animation.Animator) {
                        if (badgeFadeAnimator === this@apply) badgeFadeAnimator = null
                    }
                })
            }
            badgeFadeAnimator = anim
            anim.start()
        }
        
        fun hideStatusBadge(animated: Boolean = true) {
            setStatusBadge(statusText, statusDotColor, visible = false, animated = animated)
        }
        
        private fun drawEyes(canvas: Canvas, cx: Float, cy: Float, r: Float) {

            val sphereDiam = r * 2
            

            val eyeCenterY = (cy - r) + sphereDiam * 0.45f
            


            val leftEyeX = (cx - r) + sphereDiam * eyeLeftPercent
            val rightEyeX = leftEyeX + eyeGap
            
            val currentEyeHeight = eyeHeight * eyeHeightFactor
            
            when (currentExpression) {
                Expression.NEUTRAL -> drawNeutralEyes(canvas, leftEyeX, rightEyeX, eyeCenterY, currentEyeHeight)
                Expression.HAPPY -> drawHappyEyes(canvas, leftEyeX, rightEyeX, eyeCenterY, currentEyeHeight)
                Expression.SAD -> drawSadEyes(canvas, leftEyeX, rightEyeX, eyeCenterY, currentEyeHeight)
                Expression.SURPRISED -> drawSurprisedEyes(canvas, leftEyeX, rightEyeX, eyeCenterY, currentEyeHeight)
                Expression.THINKING -> drawThinkingEyes(canvas, leftEyeX, rightEyeX, eyeCenterY, currentEyeHeight)
                Expression.SLEEPY -> drawSleepyEyes(canvas, leftEyeX, rightEyeX, eyeCenterY, currentEyeHeight)
                Expression.EXCITED -> drawExcitedEyes(canvas, leftEyeX, rightEyeX, eyeCenterY, currentEyeHeight)
                Expression.CONFUSED -> drawConfusedEyes(canvas, leftEyeX, rightEyeX, eyeCenterY, currentEyeHeight)
                Expression.LISTENING -> drawListeningEyes(canvas, leftEyeX, rightEyeX, eyeCenterY, currentEyeHeight)
                Expression.SPEAKING -> drawSpeakingEyes(canvas, leftEyeX, rightEyeX, eyeCenterY, currentEyeHeight)
                Expression.ANGRY -> drawAngryEyes(canvas, leftEyeX, rightEyeX, eyeCenterY, currentEyeHeight)
                Expression.SHY -> drawShyEyes(canvas, leftEyeX, rightEyeX, eyeCenterY, currentEyeHeight)
                Expression.PROUD -> drawProudEyes(canvas, leftEyeX, rightEyeX, eyeCenterY, currentEyeHeight)
                Expression.CURIOUS -> drawCuriousEyes(canvas, leftEyeX, rightEyeX, eyeCenterY, currentEyeHeight)
            }
        }
        

        private fun drawNeutralEyes(canvas: Canvas, leftX: Float, rightX: Float, y: Float, h: Float) {
            val eyeTop = y - h / 2f
            canvas.drawRoundRect(RectF(leftX - eyeWidth / 2f, eyeTop, leftX + eyeWidth / 2f, eyeTop + h), eyeCornerRadius, eyeCornerRadius, eyePaint)
            canvas.drawRoundRect(RectF(rightX - eyeWidth / 2f, eyeTop, rightX + eyeWidth / 2f, eyeTop + h), eyeCornerRadius, eyeCornerRadius, eyePaint)
        }
        
        /** Closed upward crescents (^ ^). h carries the blink squash factor. */
        private fun drawHappyEyes(canvas: Canvas, leftX: Float, rightX: Float, y: Float, h: Float) {
            val arcW = h * 0.75f
            val arcH = h * 0.55f
            eyePaint.style = Paint.Style.STROKE
            eyePaint.strokeWidth = eyeWidth * 0.7f
            eyePaint.strokeCap = Paint.Cap.ROUND
            canvas.drawArc(RectF(leftX - arcW / 2f, y - arcH / 2f, leftX + arcW / 2f, y + arcH / 2f), 200f, 140f, false, eyePaint)
            canvas.drawArc(RectF(rightX - arcW / 2f, y - arcH / 2f, rightX + arcW / 2f, y + arcH / 2f), 200f, 140f, false, eyePaint)
            eyePaint.style = Paint.Style.FILL
        }
        
        /** Outer-drooping bars (mirror of angry) sagging slightly below center. */
        private fun drawSadEyes(canvas: Canvas, leftX: Float, rightX: Float, y: Float, h: Float) {
            val sadH = h * 0.7f
            val sagY = h * 0.12f
            val eyeTop = y - sadH / 2f + sagY
            canvas.save()
            canvas.rotate(15f, leftX, y + sagY)
            canvas.drawRoundRect(RectF(leftX - eyeWidth / 2f, eyeTop, leftX + eyeWidth / 2f, eyeTop + sadH), eyeCornerRadius, eyeCornerRadius, eyePaint)
            canvas.restore()
            canvas.save()
            canvas.rotate(-15f, rightX, y + sagY)
            canvas.drawRoundRect(RectF(rightX - eyeWidth / 2f, eyeTop, rightX + eyeWidth / 2f, eyeTop + sadH), eyeCornerRadius, eyeCornerRadius, eyePaint)
            canvas.restore()
        }
        
        private fun drawSurprisedEyes(canvas: Canvas, leftX: Float, rightX: Float, y: Float, h: Float) {
            val r = h * 0.35f
            canvas.drawCircle(leftX, y, r, eyePaint)
            canvas.drawCircle(rightX, y, r, eyePaint)
        }
        
        private fun drawThinkingEyes(canvas: Canvas, leftX: Float, rightX: Float, y: Float, h: Float) {
            val offsetX = eyeWidth * 0.4f
            val offsetY = -h * 0.1f
            val eyeTop = y - h / 2f + offsetY
            canvas.drawRoundRect(RectF(leftX - eyeWidth / 2f + offsetX, eyeTop, leftX + eyeWidth / 2f + offsetX, eyeTop + h * 0.75f), eyeCornerRadius, eyeCornerRadius, eyePaint)
            canvas.drawRoundRect(RectF(rightX - eyeWidth / 2f + offsetX, eyeTop, rightX + eyeWidth / 2f + offsetX, eyeTop + h * 0.75f), eyeCornerRadius, eyeCornerRadius, eyePaint)
        }
        
        private fun drawSleepyEyes(canvas: Canvas, leftX: Float, rightX: Float, y: Float, h: Float) {
            val sleepyH = h * 0.25f
            val eyeTop = y - sleepyH / 2f
            canvas.drawRoundRect(RectF(leftX - eyeWidth / 2f, eyeTop, leftX + eyeWidth / 2f, eyeTop + sleepyH), eyeCornerRadius, eyeCornerRadius, eyePaint)
            canvas.drawRoundRect(RectF(rightX - eyeWidth / 2f, eyeTop, rightX + eyeWidth / 2f, eyeTop + sleepyH), eyeCornerRadius, eyeCornerRadius, eyePaint)
        }
        
        private fun drawExcitedEyes(canvas: Canvas, leftX: Float, rightX: Float, y: Float, h: Float) {
            val starSize = h * 0.3f
            eyePaint.style = Paint.Style.STROKE
            eyePaint.strokeWidth = eyeWidth * 0.6f
            eyePaint.strokeCap = Paint.Cap.ROUND
            canvas.drawLine(leftX - starSize, y, leftX + starSize, y, eyePaint)
            canvas.drawLine(leftX, y - starSize, leftX, y + starSize, eyePaint)
            canvas.drawLine(rightX - starSize, y, rightX + starSize, y, eyePaint)
            canvas.drawLine(rightX, y - starSize, rightX, y + starSize, eyePaint)
            eyePaint.style = Paint.Style.FILL
        }
        
        private fun drawConfusedEyes(canvas: Canvas, leftX: Float, rightX: Float, y: Float, h: Float) {
            val leftH = h * 0.55f
            canvas.drawRoundRect(RectF(leftX - eyeWidth / 2f, y - leftH / 2f, leftX + eyeWidth / 2f, y + leftH / 2f), eyeCornerRadius, eyeCornerRadius, eyePaint)
            val rightH = h * 1.1f
            canvas.drawRoundRect(RectF(rightX - eyeWidth * 0.6f, y - rightH / 2f, rightX + eyeWidth * 0.6f, y + rightH / 2f), eyeCornerRadius, eyeCornerRadius, eyePaint)
        }
        
        private fun drawListeningEyes(canvas: Canvas, leftX: Float, rightX: Float, y: Float, h: Float) {
            val listenH = h * 0.8f
            val eyeTop = y - listenH / 2f
            canvas.drawRoundRect(RectF(leftX - eyeWidth / 2f, eyeTop, leftX + eyeWidth / 2f, eyeTop + listenH), eyeCornerRadius, eyeCornerRadius, eyePaint)
            canvas.drawRoundRect(RectF(rightX - eyeWidth / 2f, eyeTop, rightX + eyeWidth / 2f, eyeTop + listenH), eyeCornerRadius, eyeCornerRadius, eyePaint)
        }
        
        private fun drawSpeakingEyes(canvas: Canvas, leftX: Float, rightX: Float, y: Float, h: Float) {
            val pulse = 0.9f + 0.1f * kotlin.math.sin(System.currentTimeMillis() / 180.0).toFloat()
            val speakH = h * pulse
            val eyeTop = y - speakH / 2f
            canvas.drawRoundRect(RectF(leftX - eyeWidth / 2f, eyeTop, leftX + eyeWidth / 2f, eyeTop + speakH), eyeCornerRadius, eyeCornerRadius, eyePaint)
            canvas.drawRoundRect(RectF(rightX - eyeWidth / 2f, eyeTop, rightX + eyeWidth / 2f, eyeTop + speakH), eyeCornerRadius, eyeCornerRadius, eyePaint)
        }
        
        private fun drawAngryEyes(canvas: Canvas, leftX: Float, rightX: Float, y: Float, h: Float) {
            val angryH = h * 0.6f
            val eyeTop = y - angryH / 2f
            canvas.save()
            canvas.rotate(-15f, leftX, y)
            canvas.drawRoundRect(RectF(leftX - eyeWidth / 2f, eyeTop, leftX + eyeWidth / 2f, eyeTop + angryH), eyeCornerRadius, eyeCornerRadius, eyePaint)
            canvas.restore()
            canvas.save()
            canvas.rotate(15f, rightX, y)
            canvas.drawRoundRect(RectF(rightX - eyeWidth / 2f, eyeTop, rightX + eyeWidth / 2f, eyeTop + angryH), eyeCornerRadius, eyeCornerRadius, eyePaint)
            canvas.restore()
        }
        
        private fun drawShyEyes(canvas: Canvas, leftX: Float, rightX: Float, y: Float, h: Float) {
            val shyH = h * 0.7f
            val offsetX = eyeWidth * 0.3f
            val offsetY = h * 0.15f
            val eyeTop = y - shyH / 2f + offsetY
            canvas.drawRoundRect(RectF(leftX - eyeWidth / 2f - offsetX, eyeTop, leftX + eyeWidth / 2f - offsetX, eyeTop + shyH), eyeCornerRadius, eyeCornerRadius, eyePaint)
            canvas.drawRoundRect(RectF(rightX - eyeWidth / 2f - offsetX, eyeTop, rightX + eyeWidth / 2f - offsetX, eyeTop + shyH), eyeCornerRadius, eyeCornerRadius, eyePaint)
        }
        
        private fun drawProudEyes(canvas: Canvas, leftX: Float, rightX: Float, y: Float, h: Float) {
            val proudH = h * 0.4f
            val offsetY = -h * 0.1f
            val eyeTop = y - proudH / 2f + offsetY
            canvas.drawRoundRect(RectF(leftX - eyeWidth * 0.6f, eyeTop, leftX + eyeWidth * 0.6f, eyeTop + proudH), eyeCornerRadius * 2, eyeCornerRadius * 2, eyePaint)
            canvas.drawRoundRect(RectF(rightX - eyeWidth * 0.6f, eyeTop, rightX + eyeWidth * 0.6f, eyeTop + proudH), eyeCornerRadius * 2, eyeCornerRadius * 2, eyePaint)
        }
        
        private fun drawCuriousEyes(canvas: Canvas, leftX: Float, rightX: Float, y: Float, h: Float) {
            val leftH = h * 0.7f
            val rightH = h * 1.0f
            canvas.drawRoundRect(RectF(leftX - eyeWidth / 2f, y - leftH / 2f, leftX + eyeWidth / 2f, y + leftH / 2f), eyeCornerRadius, eyeCornerRadius, eyePaint)
            canvas.drawRoundRect(RectF(rightX - eyeWidth * 0.7f, y - rightH / 2f, rightX + eyeWidth * 0.7f, y + rightH / 2f), eyeCornerRadius, eyeCornerRadius, eyePaint)
        }
        
        /**
         * One-shot sparkle burst around the upper sphere on an emotive expression
         * change (concept "Emotion burst"). No animator of its own: while animating,
         * mainAnimator already invalidates every frame, so particles are a pure
         * function of the wall clock inside onDraw.
         */
        private fun startEmotionBurst() {
            val rnd = kotlin.random.Random
            for (p in burstParticles) {
                // Upper arc bias, just outside the rim, drifting slightly outward.
                p.angle = Math.toRadians(rnd.nextDouble(-160.0, -20.0)).toFloat()
                p.dist = 1.02f + rnd.nextFloat() * 0.10f
                p.drift = 0.18f + rnd.nextFloat() * 0.22f
                p.size = dp(3f) + rnd.nextFloat() * dp(3.5f)
                p.stagger = rnd.nextFloat() * 0.35f
                p.style = rnd.nextInt(3)
            }
            burstStartMs = System.currentTimeMillis()
        }

        private fun drawEmotionBurst(canvas: Canvas, cx: Float, cy: Float, r: Float) {
            if (burstStartMs == 0L) return
            val t = (System.currentTimeMillis() - burstStartMs).toFloat() / EMOTION_BURST_DURATION_MS
            if (t >= 1f) {
                burstStartMs = 0L
                return
            }
            // Low-power devices: fewer sparkles (same proxy as the glow/scroll trims).
            val count = if (performanceProfile.enableGlowBlur) burstParticles.size else 6
            val tint = blendColors(targetColor, Color.WHITE, 0.55f)
            for (i in 0 until count) {
                val p = burstParticles[i]
                val pp = ((t - p.stagger) / (1f - p.stagger)).coerceIn(0f, 1f)
                if (pp <= 0f || pp >= 1f) continue
                // Grow-then-shrink twinkle; alpha follows the same curve.
                val fade = kotlin.math.sin(pp * Math.PI.toFloat())
                val dist = r * (p.dist + p.drift * pp)
                val px = cx + kotlin.math.cos(p.angle) * dist
                val py = cy + kotlin.math.sin(p.angle) * dist
                val s = p.size * (0.6f + 0.4f * fade)
                burstPaint.color = Color.argb(
                    (200 * fade).toInt(),
                    Color.red(tint), Color.green(tint), Color.blue(tint)
                )
                when (p.style) {
                    2 -> {
                        burstPaint.style = Paint.Style.FILL
                        canvas.drawCircle(px, py, s * 0.45f, burstPaint)
                        burstPaint.style = Paint.Style.STROKE
                    }
                    else -> {
                        burstPaint.strokeWidth = (s * 0.28f).coerceAtLeast(dp(1f))
                        canvas.save()
                        if (p.style == 1) canvas.rotate(45f + pp * 30f, px, py)
                        canvas.drawLine(px - s, py, px + s, py, burstPaint)
                        canvas.drawLine(px, py - s, px, py + s, burstPaint)
                        canvas.restore()
                    }
                }
            }
        }

        /**
         * Emotion change while the sphere is live: queue it behind a quick blink (committed at
         * the trough by [blinkAnimator]) so the eye shape never pops, and ease the tint from the
         * current color. Hidden / entrance (not animating): snap — no frame driver, no open lids.
         */
        fun setExpression(expression: Expression) {
            // Already the active or queued target — don't restart the blink every stream chunk.
            if (expression == (pendingExpression ?: currentExpression)) return
            if (!isAnimating) {
                pendingExpression = null
                applyExpressionNow(expression, changeColor = true, snapColor = true)
                return
            }
            pendingExpression = expression
            pendingEyesOnly = false
            blinkAnimator.start()
        }

        /**
         * Swap only the eye shape and keep the current sphere tint — per-page TTS emotion must
         * not restart the color blend. Same blink-masked swap as [setExpression].
         */
        fun setEyeExpression(expression: Expression) {
            if (expression == (pendingExpression ?: currentExpression)) return
            if (!isAnimating) {
                pendingExpression = null
                applyExpressionNow(expression, changeColor = false)
                return
            }
            pendingExpression = expression
            pendingEyesOnly = true
            blinkAnimator.start()
        }

        /** Commit an expression: eye shape always; tint only when [changeColor] (eased, or snapped). */
        private fun applyExpressionNow(expression: Expression, changeColor: Boolean, snapColor: Boolean = false) {
            Log.d(TAG, "expression: $currentExpression -> $expression (color=$changeColor)")
            currentExpression = expression
            if (changeColor) {
                val target = expressionColors[expression] ?: 0xFF6366F1.toInt()
                targetColor = target
                if (snapColor) {
                    sphereColor = target
                    colorTransition = 1f
                } else {
                    colorFrom = sphereColor
                    colorTransition = 0f
                }
            }
            blinkInterval = if (expression == Expression.SPEAKING) 1500L else 4000L
            if (isAnimating && expression in burstExpressions) startEmotionBurst()
            invalidate()
        }

        /**
         * Snap expression + fill color immediately (no blend). Call after the exit
         * animation ends so the next wake does not inherit the previous tint —
         * not while the current sphere is still on screen.
         */
        fun resetAppearance(expression: Expression = Expression.NEUTRAL) {
            pendingExpression = null
            pendingEyesOnly = false
            applyExpressionNow(expression, changeColor = true, snapColor = true)
        }

        fun destroy() {
            cancelBadgeFade()
            stopAnimations()
            poseAnimator.cancel()
            poseAnimator.removeAllUpdateListeners()
            blinkAnimator.removeAllUpdateListeners()
            mainAnimator.removeAllUpdateListeners()
        }
    }

    private var windowManager: WindowManager? = null
    private var capsuleView: FrameLayout? = null
    private var capsuleParams: WindowManager.LayoutParams? = null
    /** Current subtitle line (streaming / cursor / karaoke). */
    private var capsuleTextView: TextView? = null
    /** Outgoing line used only during crossfade switches. */
    private var captionOutgoingView: TextView? = null
    private var captionStackView: FrameLayout? = null
    private var esperSphereView: EsperSphereView? = null
    private var textCardView: FrameLayout? = null
    /** Bottom veil behind TTS captions (and sphere turns). Fades; never snapped on-screen. */
    private var captionFalloffView: View? = null
    private var captionFalloffAnimator: Animator? = null
    /** Fired once when a caption-only veil fade-in reaches 1; cancelled fade-ins skip it. */
    private var captionFalloffOnSettled: (() -> Unit)? = null
    private var captionFalloffFadingIn = false
    /** Delayed park after caption-only hide so the veil can finish after the plate. */
    private var captionOnlyHideParkRunnable: Runnable? = null
    private var captionMetrics: CaptionMetrics? = null
    private var stackSwitchAnimator: AnimatorSet? = null
    private var sphereEntranceAnimator: AnimatorSet? = null
    private var sphereExitAnimator: Animator? = null
    private var pendingSphereEntranceRunnable: Runnable? = null
    /** Bumps on clear / newer render so in-flight switch anims cannot repaint stale lines. */
    private var captionRenderGeneration = 0
    /** Caption-only first-page beat: delayed materialize so the reply does not pop with audio. */
    private var captionEntranceRunnable: Runnable? = null

    private var cursorBlinkRunnable: Runnable? = null
    private var isCursorVisible = false
    private var currentState = State.IDLE
    private var displayMode = DisplayMode.NONE
    private var performanceProfile = PerformanceProfile.balanced()
    private var lastStreamingUiUpdateMs = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var lastOrientation = Configuration.ORIENTATION_UNDEFINED
    private var lastScreenWidth = 0
    private var lastScreenHeight = 0
    private var rebuildingForConfiguration = false
    private val configurationCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            onOverlayConfigurationChanged(newConfig)
        }

        override fun onLowMemory() = Unit
    }

    /**
     * Centered subtitle column: side clear for edge glow, bottom-biased under the sphere.
     */
    private data class CaptionMetrics(
        val sideClearPx: Int,
        val bottomClearPx: Int,
        val columnWidthPx: Int,
        val currSp: Float,
        val padHPx: Int,
        val padVPx: Int,
        val switchSlidePx: Float,
        /** Air between the solid sphere and the caption card (px). */
        val sphereGapPx: Int,
        /** Fixed slot height for exactly 3 caption lines (keeps first-line alignment). */
        val threeLineHeightPx: Int
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        if (!com.example.ava.platform.PlatformCapabilities.canDrawOverlays(this)) {
            Log.w(TAG, "No overlay permission, stopping FloatingWindowService")
            stopSelf()
            return
        }
        performanceProfile = detectPerformanceProfile()
        Log.d(
            TAG,
            "PerformanceProfile: blur=${performanceProfile.enableGlowBlur}, smoothScroll=${performanceProfile.useSmoothScroll}, throttle=${performanceProfile.streamingUiThrottleMs}ms"
        )
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        lastOrientation = resources.configuration.orientation
        lastScreenWidth = resources.displayMetrics.widthPixels
        lastScreenHeight = resources.displayMetrics.heightPixels
        createCapsuleView()
        applicationContext.registerComponentCallbacks(configurationCallbacks)
    }

    private fun detectPerformanceProfile(): PerformanceProfile {
        val am = getSystemService(ACTIVITY_SERVICE) as? ActivityManager ?: return PerformanceProfile.balanced()
        val isLowRam = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) am.isLowRamDevice else false
        val weakDevice = isLowRam || am.memoryClass <= 192
        return if (weakDevice) PerformanceProfile.lowPower() else PerformanceProfile.balanced()
    }

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private fun dpF(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private fun buildCaptionMetrics(
        screenWidth: Int,
        screenHeight: Int,
        isLandscape: Boolean,
        isSquareScreen: Boolean,
        isTablet: Boolean
    ): CaptionMetrics {
        val vmin = min(screenWidth, screenHeight).toFloat()
        val a64 = DeviceFeatureManager.isA64Device()

        // Keep a modest bottom clear for the strong glow band; sit lower than the previous 14%.
        val sideFrac = when {
            a64 -> 0.16f
            isLandscape -> 0.11f
            isSquareScreen -> 0.13f
            else -> 0.12f
        }
        val bottomFrac = when {
            a64 -> 0.10f
            isLandscape -> 0.06f
            isSquareScreen -> 0.08f
            isTablet -> 0.07f
            else -> 0.075f
        }
        val colFrac = when {
            a64 -> 0.66f
            isTablet -> 0.60f
            isLandscape -> 0.58f
            isSquareScreen -> 0.70f
            else -> 0.74f
        }

        val sideClear = (vmin * sideFrac).roundToInt().coerceAtLeast(dp(24f))
        val bottomClear = (screenHeight * bottomFrac).roundToInt().coerceAtLeast(dp(22f))
        val columnWidth = min(
            (screenWidth * colFrac).roundToInt(),
            (screenWidth - sideClear * 2).coerceAtLeast(dp(160f))
        )

        val padH = dp(if (isTablet) 14f else 10f)
        val padV = dp(if (isLandscape) 6f else 10f)
        val contentWidthPx = (columnWidth - padH * 2).coerceAtLeast(dp(120f))
        // Same rule as the FAB transcript / caption-only plate: size type by how many
        // CJK glyphs fit on a line (1 glyph ≈ 1 em). Fewer glyphs → larger type; the
        // 3-line page then carries less text instead of packing a wall of small type.
        // Landscape / tablet caps keep a 3-line slot from eating the short side.
        val byChars = spForCjkPerLine(contentWidthPx, SPHERE_CJK_PER_LINE)
        val currSp = when {
            isTablet -> byChars.coerceIn(20f, 30f)
            isLandscape -> byChars.coerceIn(17f, 20f)
            isSquareScreen -> byChars.coerceIn(17f, 22f)
            else -> byChars.coerceIn(17f, 24f)
        }
        val threeLineHeight = measureCaptionThreeLineHeightPx(currSp, contentWidthPx)

        return CaptionMetrics(
            sideClearPx = sideClear,
            bottomClearPx = bottomClear,
            columnWidthPx = columnWidth,
            currSp = currSp,
            padHPx = padH,
            padVPx = padV,
            switchSlidePx = dpF(if (isLandscape) 10f else 14f),
            sphereGapPx = dp(if (isLandscape) 6f else 8f),
            threeLineHeightPx = threeLineHeight
        )
    }

    /** Measure a stable 3-line slot so 1-line and 3-line sentences share the same first-line Y. */
    private fun measureCaptionThreeLineHeightPx(sp: Float, contentWidthPx: Int): Int {
        val probe = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setLineSpacing(0f, 1.32f)
            includeFontPadding = false
            maxLines = 3
            // Three full lines of CJK so height matches real wrapped captions.
            text = "国国国国国国\n国国国国国国\n国国国国国国"
        }
        val w = contentWidthPx.coerceAtLeast(dp(120f))
        val widthSpec = View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        probe.measure(widthSpec, heightSpec)
        return probe.measuredHeight.coerceAtLeast(dp(48f))
    }

    private fun buildCaptionLineView(sp: Float, threeLineHeightPx: Int): TextView {
        return TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                threeLineHeightPx,
                Gravity.TOP
            )
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.argb(245, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            // Top + center: every sentence starts on the same first-line baseline.
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            isSingleLine = false
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.32f)
            includeFontPadding = false
            // Soft glyph emphasis only — no plate / block shadow behind the caption.
            setShadowLayer(dpF(2.2f), 0f, dpF(0.8f), Color.argb(110, 0, 0, 0))
            alpha = 1f
            text = ""
        }
    }

    /**
     * Place the subtitle under the speaking sphere: bottom-biased, with a small air gap.
     */
    private fun relayoutCaptionUnderSphere() {
        val card = textCardView ?: return
        val metrics = captionMetrics ?: return
        // Caption-only turns have no sphere to tuck under: take the plain bottom-band branch.
        val sphere = if (captionOnly) null else esperSphereView
        val screenH = resources.displayMetrics.heightPixels
        val glowClear = metrics.bottomClearPx

        val lp = (card.layoutParams as? FrameLayout.LayoutParams) ?: return
        lp.width = metrics.columnWidthPx
        val captionH = activeThreeLineHeightPx(metrics) + metrics.padVPx * 2
        lp.height = captionH

        if (captionOnly) {
            // Button turn: no sphere to sit under and the bottom band collides with the FAB
            // and its transcript bubble. The reply reads from the centre of the screen.
            lp.gravity = Gravity.CENTER
            lp.bottomMargin = 0
            card.layoutParams = lp
            syncCaptionFogToText()
            return
        }

        lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        if (sphere == null) {
            lp.bottomMargin = glowClear
            card.layoutParams = lp
            syncCaptionFogToText()
            return
        }

        val sphereBottom = sphere.speakingCenterY() + sphere.currentSphereRadius()
        val desiredTop = sphereBottom + metrics.sphereGapPx
        val underSphereBottomMargin = (screenH - desiredTop - captionH).roundToInt()
        // Never lift the block into the upper half — keep subtitles in the lower band.
        val maxLiftFromBottom = (screenH * 0.20f).roundToInt()

        lp.bottomMargin = if (underSphereBottomMargin > maxLiftFromBottom) {
            // Long portrait: stay low (glow clear), don't float mid-screen under a high sphere.
            glowClear
        } else {
            // Short / landscape: keep the gap. Do not raise via glowClear into the sphere.
            underSphereBottomMargin.coerceIn(0, maxLiftFromBottom)
        }
        card.layoutParams = lp
        syncCaptionFogToText()
    }

    /** Ink height of a caption layer (used lines only — the TextView itself is a 3-line slot). */
    private fun captionInkHeight(tv: TextView?): Int {
        if (tv == null || tv.visibility != View.VISIBLE) return 0
        val text = tv.text?.toString().orEmpty()
        if (text.isBlank()) return 0
        val layout = tv.layout
        if (layout != null && layout.lineCount > 0) {
            return (layout.getLineBottom(layout.lineCount - 1) - layout.getLineTop(0)).coerceAtLeast(0)
        }
        val metrics = captionMetrics ?: return 0
        val slot = activeThreeLineHeightPx(metrics)
        val lines = (1 + text.count { it == '\n' }).coerceIn(1, 3)
        return (slot * lines / 3f).roundToInt()
    }

    /**
     * Hug the fog to the used lines. Sphere pages keep a top baseline so 1-line and 3-line
     * share a first-line Y. Caption-only pages share one 3-line plate so a short page and a
     * long page dissolve inside the same card — hugging per page made the plate jump size.
     */
    private fun syncCaptionFogToText(preferText: String? = null) {
        val card = textCardView ?: return
        val fog = card.background as? CaptionFogDrawable ?: return
        val metrics = captionMetrics ?: return
        if (captionOnly) {
            val hasInk = preferText?.isNotBlank() == true ||
                captionInkHeight(capsuleTextView) > 0 ||
                captionInkHeight(captionOutgoingView) > 0
            if (!hasInk) {
                fog.setCoverHeight(0, centered = true)
                return
            }
            val slotH = activeThreeLineHeightPx(metrics) + metrics.padVPx * 2
            val maxH = card.height.takeIf { it > 0 } ?: slotH
            fog.setCoverHeight(slotH.coerceAtMost(maxH), centered = true)
            return
        }
        val ink = if (preferText != null) {
            estimateCaptionInkHeight(preferText)
        } else {
            maxOf(captionInkHeight(capsuleTextView), captionInkHeight(captionOutgoingView))
        }
        if (ink <= 0) {
            fog.setCoverHeight(0, centered = false)
            return
        }
        val slotH = activeThreeLineHeightPx(metrics) + metrics.padVPx * 2
        val maxH = card.height.takeIf { it > 0 } ?: slotH
        fog.setCoverHeight(
            (ink + metrics.padVPx * 2).coerceAtMost(maxH),
            centered = false,
        )
    }

    /** Line-count estimate so the plate can size with the words before TextView layout runs. */
    private fun estimateCaptionInkHeight(text: String): Int {
        if (text.isBlank()) return 0
        val metrics = captionMetrics ?: return 0
        val slot = activeThreeLineHeightPx(metrics)
        val lines = (1 + text.count { it == '\n' }).coerceIn(1, 3)
        return (slot * lines / 3f).roundToInt()
    }

    private fun clearCaptionStack() {
        captionRenderGeneration += 1
        stackSwitchAnimator?.cancel()
        stackSwitchAnimator = null
        captionOutgoingView?.animate()?.cancel()
        capsuleTextView?.animate()?.cancel()
        captionOutgoingView?.text = ""
        captionOutgoingView?.alpha = 0f
        captionOutgoingView?.translationY = 0f
        captionOutgoingView?.visibility = View.INVISIBLE
        capsuleTextView?.text = ""
        capsuleTextView?.alpha = 1f
        capsuleTextView?.translationY = 0f
        capsuleTextView?.scaleX = 1f
        capsuleTextView?.scaleY = 1f
        capsuleTextView?.visibility = View.VISIBLE
        syncCaptionFogToText()
    }

    private fun renderSingleCaption(text: String) {
        captionRenderGeneration += 1
        stackSwitchAnimator?.cancel()
        stackSwitchAnimator = null
        captionOutgoingView?.animate()?.cancel()
        capsuleTextView?.animate()?.cancel()
        captionOutgoingView?.text = ""
        captionOutgoingView?.alpha = 0f
        captionOutgoingView?.translationY = 0f
        captionOutgoingView?.visibility = View.INVISIBLE
        capsuleTextView?.text = text
        capsuleTextView?.alpha = 1f
        capsuleTextView?.translationY = 0f
        capsuleTextView?.visibility = View.VISIBLE
        capsuleTextView?.post { relayoutCaptionUnderSphere() }
    }

    /**
     * Paginate full caption by layout lines (max 3), not by sentence punctuation.
     * Keeps the whole reply readable via time-based page progress.
     */
    private fun buildCaptionPages(fullText: String): List<String> {
        val normalized = fullText.trim().replace(Regex("[ \\t]+"), " ")
        if (normalized.isEmpty()) return emptyList()

        val metrics = captionMetrics
        val probe = capsuleTextView
        val contentWidth = if (metrics != null) {
            (metrics.columnWidthPx - metrics.padHPx * 2).coerceAtLeast(dp(120f))
        } else {
            dp(240f)
        }
        val paint = TextPaint(probe?.paint ?: TextPaint()).apply {
            if (probe == null && metrics != null) {
                textSize = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP,
                    metrics.currSp,
                    resources.displayMetrics
                )
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
        }

        val layout = StaticLayout.Builder
            .obtain(normalized, 0, normalized.length, paint, contentWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.32f)
            .setIncludePad(false)
            .build()

        if (layout.lineCount <= 0) return listOf(normalized)

        val lines = ArrayList<String>(layout.lineCount)
        for (i in 0 until layout.lineCount) {
            val start = layout.getLineStart(i)
            val end = layout.getLineEnd(i)
            lines.add(normalized.substring(start, end).trimEnd('\n'))
        }

        val pages = ArrayList<String>()
        var i = 0
        while (i < lines.size) {
            val end = min(i + 3, lines.size)
            pages.add(lines.subList(i, end).joinToString("\n"))
            i = end
        }
        return pages.ifEmpty { listOf(normalized) }
    }

    private fun prepareCaptionPages(fullText: String, resetIndex: Boolean = true) {
        captionFullText = fullText.trim()
        captionPages = buildCaptionPages(captionFullText)
        captionPageWeights = captionPages.map { calculateSpeechWeight(it.replace("\n", "")) }
        captionPageTotalWeight = captionPageWeights.sum().coerceAtLeast(0.5f)
        if (resetIndex) {
            captionPageIndex = 0
        } else if (captionPages.isNotEmpty()) {
            captionPageIndex = captionPageIndex.coerceIn(0, captionPages.lastIndex)
        } else {
            captionPageIndex = 0
        }
    }

    /**
     * Full-text page progress with vertical dissolve (film-style), not sentence karaoke / music sweep.
     * [firstEntrance] is caption-only page 0: incoming-only rise/fade so the reply materializes
     * instead of snapping in with the audio.
     */
    private fun dissolveCaptionPage(index: Int, animate: Boolean, firstEntrance: Boolean = false) {
        if (captionPages.isEmpty()) {
            clearCaptionStack()
            return
        }
        val safeIndex = index.coerceIn(0, captionPages.lastIndex)
        captionPageIndex = safeIndex
        val curr = captionPages[safeIndex]
        currentText = curr

        val currV = capsuleTextView ?: return
        val outV = captionOutgoingView ?: return
        val generation = captionRenderGeneration + 1
        captionRenderGeneration = generation

        fun settle() {
            if (generation != captionRenderGeneration) return
            outV.animate().cancel()
            currV.animate().cancel()
            outV.text = ""
            outV.alpha = 0f
            outV.translationY = 0f
            outV.visibility = View.INVISIBLE
            currV.text = curr
            currV.alpha = 1f
            currV.translationY = 0f
            currV.scaleX = 1f
            currV.scaleY = 1f
            currV.visibility = View.VISIBLE
            if (captionOnly) {
                textCardView?.let { card ->
                    card.animate().cancel()
                    card.alpha = 1f
                    card.translationY = 0f
                    card.scaleX = 1f
                    card.scaleY = 1f
                }
                syncCaptionFogToText(curr)
                QuickWakeFabService.noteTtsCaptionSettled()
            }
            currV.post { relayoutCaptionUnderSphere() }
        }

        val previous = when {
            outV.visibility == View.VISIBLE &&
                outV.alpha > currV.alpha &&
                outV.text?.isNotBlank() == true -> outV.text.toString()
            else -> currV.text?.toString().orEmpty()
        }
        val playEntrance = firstEntrance && animate
        if (!animate || (!playEntrance && (previous.isBlank() || previous == curr))) {
            stackSwitchAnimator?.cancel()
            stackSwitchAnimator = null
            settle()
            return
        }

        stackSwitchAnimator?.cancel()
        // Soft vertical page-turn — outgoing rises out, incoming rises in.
        val dissolve = (captionMetrics?.switchSlidePx ?: dpF(14f)) *
            if (captionOnly) 1.25f else 1.15f
        val rise = if (playEntrance && captionOnly) 0f else dissolve * 1.1f

        outV.animate().cancel()
        currV.animate().cancel()

        if (playEntrance || previous.isBlank()) {
            outV.text = ""
            outV.alpha = 0f
            outV.translationY = 0f
            outV.visibility = View.INVISIBLE

            currV.text = curr
            currV.visibility = View.VISIBLE

            if (playEntrance && captionOnly) {
                // Glyphs sit inside the card; keep the card at 0 until the veil has
                // faded in so the mask leads and nothing pops a full-alpha frame.
                currV.alpha = 1f
                currV.translationY = 0f
                currV.scaleX = 1f
                currV.scaleY = 1f
                syncCaptionFogToText(curr)
                val card = textCardView
                if (card == null) {
                    settle()
                    return
                }
                card.animate().cancel()
                if (card.width > 0 && card.height > 0) {
                    card.pivotX = card.width / 2f
                    card.pivotY = card.height / 2f
                }
                card.alpha = 0f
                card.translationY = rise
                card.scaleX = 1f
                card.scaleY = 1f
                fadeInCaptionFalloff(CAPTION_ONLY_VEIL_LEAD_MS) {
                    if (generation != captionRenderGeneration) return@fadeInCaptionFalloff
                    if (!captionOnly || currentState != State.SPEAKING) return@fadeInCaptionFalloff
                    playCaptionOnlyCardEntrance(card, currV, generation, rise)
                }
                return
            }

            currV.alpha = 0f
            currV.translationY = rise
            currV.scaleX = 1f
            currV.scaleY = 1f
            if (currV.width > 0 && currV.height > 0) {
                currV.pivotX = currV.width / 2f
                currV.pivotY = currV.height / 2f
            }

            val fadeIn = AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(currV, View.ALPHA, 0f, 1f),
                    ObjectAnimator.ofFloat(currV, View.TRANSLATION_Y, rise, 0f),
                )
                duration = CAPTION_ONLY_ENTRANCE_MS
                interpolator = DecelerateInterpolator(1.45f)
            }
            fadeIn.addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (generation != captionRenderGeneration) return
                    currV.alpha = 1f
                    currV.translationY = 0f
                    currV.scaleX = 1f
                    currV.scaleY = 1f
                    if (stackSwitchAnimator === fadeIn) stackSwitchAnimator = null
                    currV.post { relayoutCaptionUnderSphere() }
                }

                override fun onAnimationCancel(animation: android.animation.Animator) {
                    if (stackSwitchAnimator === fadeIn) stackSwitchAnimator = null
                    if (generation == captionRenderGeneration) settle()
                }
            })
            stackSwitchAnimator = fadeIn
            fadeIn.start()
            return
        }

        // Hide the incoming layer before swapping text so the new block never
        // paints a full-alpha frame (that flash is the SSE page-turn hitch).
        currV.alpha = 0f
        currV.translationY = dissolve
        currV.scaleX = 1f
        currV.scaleY = 1f

        outV.text = previous
        outV.alpha = 1f
        outV.translationY = 0f
        outV.visibility = View.VISIBLE

        currV.text = curr
        currV.visibility = View.VISIBLE
        syncCaptionFogToText()

        val pageEase = PathInterpolator(0.22f, 0.08f, 0.18f, 1f)
        val fadeOut = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(outV, View.ALPHA, 1f, 0f),
                ObjectAnimator.ofFloat(outV, View.TRANSLATION_Y, 0f, -dissolve)
            )
            duration = CAPTION_PAGE_CROSSFADE_OUT_MS
            interpolator = pageEase
        }
        val fadeIn = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(currV, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(currV, View.TRANSLATION_Y, dissolve, 0f)
            )
            duration = CAPTION_PAGE_CROSSFADE_IN_MS
            interpolator = pageEase
        }

        val dissolveSet = AnimatorSet()
        dissolveSet.playTogether(fadeOut, fadeIn)
        dissolveSet.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (generation != captionRenderGeneration) return
                outV.text = ""
                outV.alpha = 0f
                outV.translationY = 0f
                outV.visibility = View.INVISIBLE
                currV.alpha = 1f
                currV.translationY = 0f
                if (stackSwitchAnimator === dissolveSet) stackSwitchAnimator = null
                if (captionOnly) {
                    syncCaptionFogToText(curr)
                } else {
                    currV.post { relayoutCaptionUnderSphere() }
                }
            }

            override fun onAnimationCancel(animation: android.animation.Animator) {
                if (stackSwitchAnimator === dissolveSet) stackSwitchAnimator = null
                if (generation == captionRenderGeneration) settle()
            }
        })
        stackSwitchAnimator = dissolveSet
        dissolveSet.start()
    }

    /** Caption-only plate after the veil has settled — glyphs never lead the mask. */
    private fun playCaptionOnlyCardEntrance(
        card: View,
        currV: TextView,
        generation: Int,
        rise: Float,
    ) {
        card.animate().cancel()
        if (card.width > 0 && card.height > 0) {
            card.pivotX = card.width / 2f
            card.pivotY = card.height / 2f
        }
        card.alpha = 0f
        card.translationY = rise
        card.scaleX = 1f
        card.scaleY = 1f
        val fadeIn = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(card, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(card, View.TRANSLATION_Y, rise, 0f),
            )
            duration = CAPTION_ONLY_ENTRANCE_MS
            interpolator = DecelerateInterpolator(1.6f)
        }
        fadeIn.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (generation != captionRenderGeneration) return
                card.alpha = 1f
                card.translationY = 0f
                card.scaleX = 1f
                card.scaleY = 1f
                if (stackSwitchAnimator === fadeIn) stackSwitchAnimator = null
                QuickWakeFabService.noteTtsCaptionSettled()
                currV.post { relayoutCaptionUnderSphere() }
            }

            override fun onAnimationCancel(animation: android.animation.Animator) {
                if (stackSwitchAnimator === fadeIn) stackSwitchAnimator = null
                if (generation == captionRenderGeneration) {
                    card.alpha = 1f
                    card.translationY = 0f
                    card.scaleX = 1f
                    card.scaleY = 1f
                    currV.post { relayoutCaptionUnderSphere() }
                }
            }
        })
        stackSwitchAnimator = fadeIn
        fadeIn.start()
        currV.post { syncCaptionFogToText() }
    }

    private fun cancelCaptionEntrance() {
        captionEntranceRunnable?.let { handler.removeCallbacks(it) }
        captionEntranceRunnable = null
    }

    /**
     * Caption-only first page: veil fades in first, then the reply plate. The STT
     * bubble stays up through this and leaves on its own a beat after the plate has
     * settled ([QuickWakeFabService.noteTtsCaptionSettled]). Sphere turns still snap page 0.
     */
    private fun presentCaptionFirstPage(index: Int) {
        if (!captionOnly) {
            dissolveCaptionPage(index, animate = false)
            return
        }
        // Mid-utterance restore (rotate): reflow the page, don't replay the entrance.
        if (karaokeProgressMs > 0L) {
            dissolveCaptionPage(index, animate = false)
            return
        }
        val painted = capsuleTextView?.text?.toString().orEmpty().isNotBlank() &&
            (capsuleTextView?.alpha ?: 0f) > 0.4f
        if (painted) {
            val same = capsuleTextView?.text?.toString() == captionPages.getOrNull(index)
            dissolveCaptionPage(index, animate = !same)
            return
        }
        cancelCaptionEntrance()
        capsuleTextView?.alpha = 0f
        capsuleTextView?.scaleX = 1f
        capsuleTextView?.scaleY = 1f
        val runnable = Runnable {
            captionEntranceRunnable = null
            if (!captionOnly || currentState != State.SPEAKING) return@Runnable
            if (captionPages.isEmpty()) return@Runnable
            val safe = index.coerceIn(0, captionPages.lastIndex)
            dissolveCaptionPage(safe, animate = true, firstEntrance = true)
        }
        captionEntranceRunnable = runnable
        handler.post(runnable)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createCapsuleView() {
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        

        val density = resources.displayMetrics.density
        val smallestWidthDp = minOf(screenWidth, screenHeight) / density
        val aspectRatio = screenWidth.toFloat() / screenHeight.toFloat()
        val isLandscape = screenWidth > screenHeight
        val isSquareScreen = aspectRatio in 0.9f..1.1f
        val isTablet = smallestWidthDp >= 600f
        
        // Light full-screen falloff only — caption uses glyph shadow, no plate behind the text.
        val gradientAlphaBottom = when {
            isSquareScreen -> 120
            isTablet -> 110
            isLandscape -> 100
            else -> 140
        }
        val gradientAlphaMiddle = when {
            isSquareScreen -> 10
            isTablet -> 8
            isLandscape -> 6
            else -> 28
        }
        
        val fullscreenContainer = FrameLayout(this).apply {
            fitsSystemWindows = false
        }

        val falloffView = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(
                Color.argb(gradientAlphaBottom, 0, 0, 0),
                Color.argb(gradientAlphaMiddle, 0, 0, 0),
                Color.argb(0, 0, 0, 0)
            ))
            alpha = 0f
            visibility = View.GONE
        }
        captionFalloffView = falloffView
        fullscreenContainer.addView(falloffView)

        val sphereView = EsperSphereView(this, screenWidth, screenHeight).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
            // Lock drawn diameter to screen metrics now — avoids 120dp → real-size jump.
            ensureScreenMetrics()
        }
        esperSphereView = sphereView
        fullscreenContainer.addView(sphereView)
        
        val metrics = buildCaptionMetrics(screenWidth, screenHeight, isLandscape, isSquareScreen, isTablet)
        captionMetrics = metrics

        val captionBlockHeight = metrics.threeLineHeightPx + metrics.padVPx * 2
        val textCardContainer = FrameLayout(this).apply {
            val lp = FrameLayout.LayoutParams(metrics.columnWidthPx, captionBlockHeight)
            lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            lp.bottomMargin = metrics.bottomClearPx
            layoutParams = lp
            setPadding(metrics.padHPx, metrics.padVPx, metrics.padHPx, metrics.padVPx)
            // Fades with the card's own alpha animations — no extra wiring.
            background = CaptionFogDrawable()
            visibility = View.GONE
            alpha = 0f
            clipChildren = false
            clipToPadding = false
        }
        textCardView = textCardContainer

        val stackHost = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                metrics.threeLineHeightPx
            )
            clipChildren = false
            clipToPadding = false
        }
        captionStackView = stackHost

        // Dual-layer for cinema subtitle crossfade (outgoing + current). Only one reads as solid.
        captionOutgoingView = buildCaptionLineView(metrics.currSp, metrics.threeLineHeightPx).apply {
            visibility = View.INVISIBLE
            alpha = 0f
        }
        capsuleTextView = buildCaptionLineView(metrics.currSp, metrics.threeLineHeightPx)

        stackHost.addView(captionOutgoingView)
        stackHost.addView(capsuleTextView)
        textCardContainer.addView(stackHost)
        fullscreenContainer.addView(textCardContainer)
        
        capsuleView = fullscreenContainer
        fullscreenContainer.alpha = 0f
        fullscreenContainer.visibility = View.GONE

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        @Suppress("DEPRECATION")
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            OverlayZOrderCoordinator.voiceSublayerFlags() or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            OverlayZOrderCoordinator.applyVoiceSublayerLayout(this)
        }
        capsuleParams = params

        try {
            windowManager?.addView(fullscreenContainer, params)
            OverlayZOrderCoordinator.noteWindowAdded()
            fullscreenContainer.visibility = View.GONE

            AvaSystemChrome.applyOverlayStyleSystemUi(fullscreenContainer)
            AvaSystemChrome.trackOverlayRoot(fullscreenContainer)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create capsule view", e)
        }
    }

    private var needsBringToFront = true
    /** Cold-start park sits under the boot stack. The first reply climbs once, while still GONE. */
    private var plateNeedsClimb = false
    /** Last hard removeView+addView restack — collapses routine reassert bursts (window switches). */
    private var lastHardRestackAt = 0L

    /**
     * Hard WM restack ([removeView]+[addView]). Stops the Esper pulse for one frame —
     * only for first paint after hidden, or external screensaver restack
     * ([bringToFrontIfVisible]). Mid-session Listening→Processing→Speaking must not
     * call this or style changes hitch.
     */
    private var restackedForBrowserGeneration = -1L

    private fun bringToFrontNow(force: Boolean = false, raiseMic: Boolean = true) {
        val view = capsuleView ?: return
        val params = capsuleParams ?: return
        // removeView+addView costs one blank frame. Buried-recovery (needsBringToFront) always
        // pays it; routine reasserts (app/window switch fires several in a burst) collapse to one
        // within the throttle so a switch flickers at most once.
        val now = System.currentTimeMillis()
        if (!force && !needsBringToFront && now - lastHardRestackAt < HARD_RESTACK_MIN_INTERVAL_MS) {
            // Esper itself did not move. Climbing the mic here was the idle
            // glyph flash on every caption / style tick.
            return
        }
        plateNeedsClimb = false
        val wasAnimating = esperSphereView?.isAnimating() ?: false
        if (wasAnimating) esperSphereView?.stopAnimations()
        OverlayZOrderCoordinator.bringToFront(
            windowManager, view, params, TAG, raiseMic = raiseMic,
        ) { _ ->
            if (wasAnimating) {
                view.post { esperSphereView?.startAnimations() }
            }
        }
        // STT / TTS plate is this window. Mic + its STT bubble climb after it
        // via the snapshot queue so neither restack punches a hole.
        if (raiseMic) OverlayZOrderCoordinator.scheduleVoiceRaise()
        lastHardRestackAt = now
        needsBringToFront = false
    }

    /**
     * Soft z-order for an already-visible session: never remove+add.
     * Hard raise only when [needsBringToFront] (after hide / rebuild) so a buried
     * overlay can recover without paying the cost on every state style change.
     */
    private fun ensureStackedIfNeeded() {
        if (needsBringToFront) {
            bringToFrontNow()
        }
    }

    /**
     * Portrait ↔ landscape: Esper diameter and caption column are baked at create time.
     * Only orientation triggers rebuild — not immersive/nav-bar height jitter when the
     * idle screensaver appears (that was restacking a GONE floating window above it and
     * could steal touches / look like the screensaver timer broke).
     */
    private fun onOverlayConfigurationChanged(newConfig: Configuration) {
        val orientation = newConfig.orientation
        val orientationChanged =
            lastOrientation != Configuration.ORIENTATION_UNDEFINED && orientation != lastOrientation
        if (!orientationChanged) return
        lastOrientation = orientation
        lastScreenWidth = resources.displayMetrics.widthPixels
        lastScreenHeight = resources.displayMetrics.heightPixels
        handler.post { rebuildOverlayForConfiguration() }
    }

    private data class OverlayRestoreSnapshot(
        val state: State,
        val displayMode: DisplayMode,
        val captionFullText: String,
        val karaokeDurationMs: Long,
        val karaokeProgressMs: Long,
        val streamingText: String,
        val timerFinished: Boolean,
        val showing: Boolean,
    )

    private fun rebuildOverlayForConfiguration() {
        if (rebuildingForConfiguration) return
        val snapshot = OverlayRestoreSnapshot(
            state = currentState,
            displayMode = displayMode,
            captionFullText = captionFullText,
            karaokeDurationMs = karaokeDurationMs,
            karaokeProgressMs = karaokeProgressMs,
            streamingText = streamingBuffer.toString(),
            timerFinished = isTimerFinishedMode,
            showing = capsuleView?.visibility == View.VISIBLE || isOverlayVisiblyOn(),
        )
        Log.d(
            TAG,
            "rebuild overlay for config: ${lastScreenWidth}x$lastScreenHeight " +
                "state=${snapshot.state} mode=${snapshot.displayMode} showing=${snapshot.showing}",
        )
        rebuildingForConfiguration = true
        try {
            cancelSphereEntranceAnimator()
            stackSwitchAnimator?.cancel()
            stackSwitchAnimator = null
            cancelCaptionEntrance()
            timerBreathingAnimator?.cancel()
            timerBreathingAnimator = null
            karaokeRunnable?.let { handler.removeCallbacks(it) }
            autoHideRunnable?.let { handler.removeCallbacks(it) }
            scrollRunnable?.let { handler.removeCallbacks(it) }
            cancelStuckListeningWatchdog()
            stopCursorBlink()
            esperSphereView?.destroy()
            try {
                capsuleView?.let { view ->
                    if (view.isAttachedToWindow) windowManager?.removeView(view)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to detach floating window for rebuild", e)
            }
            capsuleView = null
            capsuleParams = null
            capsuleTextView = null
            captionOutgoingView = null
            captionStackView = null
            esperSphereView = null
            textCardView = null
            captionFalloffAnimator?.cancel()
            captionFalloffAnimator = null
            captionFalloffOnSettled = null
            captionFalloffFadingIn = false
            cancelCaptionOnlyHidePark()
            captionFalloffView = null
            captionMetrics = null
            captionOnlyTypographyApplied = false
            captionOnlyThreeLineHeightPx = 0
            // Pages were measured against the previous column width — drop them so restore reflows.
            captionPages = emptyList()
            needsBringToFront = true
            createCapsuleView()
            if (captionOnly) applyCaptionTypography(true)
            restoreOverlayAfterRebuild(snapshot)
            // createCapsuleView() addView's a GONE fullscreen overlay on top of the WM stack.
            // If we are not actually showing voice UI, put the idle screensaver back above us
            // so we do not eat touches or cover the screensaver after a rotate-while-idle.
            reassertScreensaverAboveHiddenFloating()
        } finally {
            rebuildingForConfiguration = false
        }
    }

    /**
     * After an idle rebuild, the new GONE floating window is the newest overlay and can sit
     * above [ScreensaverWebViewService]. Restore the normal passive stack (screensaver →
     * vinyl FAB) without raising this floating window.
     */
    /**
     * Plate is already GONE. Leave it there. The first reply climbs once
     * while it is still invisible; this flag is that one climb.
     */
    private fun placePrewarmedWindow() {
        if (capsuleView?.visibility == View.VISIBLE || currentState != State.IDLE) return
        plateNeedsClimb = true
        needsBringToFront = false
    }

    private fun reassertScreensaverAboveHiddenFloating() {
        if (capsuleView?.visibility == View.VISIBLE) return
        if (!ScreensaverController.isScreensaverVisible()) return
        try {
            ScreensaverWebViewService.bringToFrontIfVisible()
            VinylCoverService.bringToFrontIfVisible()
            DashboardOverlayChrome.bringToFront()
            QuickWakeFabService.raiseAboveVoiceOverlay()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to reassert screensaver above hidden floating window", e)
        }
    }

    private fun restoreOverlayAfterRebuild(snapshot: OverlayRestoreSnapshot) {
        if (snapshot.timerFinished) {
            enterTimerFinishedMode()
            return
        }
        if (!snapshot.showing && snapshot.state == State.IDLE) {
            return
        }
        when (snapshot.state) {
            State.IDLE -> Unit
            State.LISTENING -> {
                if (captionOnly) {
                    enterListeningMode()
                } else {
                    currentState = State.LISTENING
                    displayMode = DisplayMode.NONE
                    esperSphereView?.setExpression(Expression.LISTENING)
                    // Mid-session rotate: skip wake-settle delay, hard-raise above screensaver.
                    showSphereOnly(waitForWakeSettle = false)
                    armStuckListeningWatchdog()
                }
            }
            State.PROCESSING -> {
                if (captionOnly) {
                    enterProcessingMode()
                } else {
                    currentState = State.PROCESSING
                    displayMode = DisplayMode.NONE
                    esperSphereView?.setExpression(Expression.THINKING)
                    showSphereOnly(waitForWakeSettle = false)
                    cancelStuckListeningWatchdog()
                }
            }
            State.SPEAKING -> {
                currentState = State.SPEAKING
                esperSphereView?.setExpression(Expression.SPEAKING)
                val text = snapshot.captionFullText.ifBlank { snapshot.streamingText }
                when {
                    text.isNotBlank() &&
                        (snapshot.displayMode == DisplayMode.KARAOKE ||
                            snapshot.karaokeDurationMs > 0L) -> {
                        displayMode = DisplayMode.KARAOKE
                        captionFullText = text
                        karaokeDurationMs = snapshot.karaokeDurationMs
                        karaokeProgressMs = snapshot.karaokeProgressMs
                        initKaraokeWithDuration(text, snapshot.karaokeDurationMs)
                        if (snapshot.karaokeProgressMs > 0L && snapshot.karaokeDurationMs > 0L) {
                            updateKaraokeProgress(
                                snapshot.karaokeProgressMs,
                                snapshot.karaokeDurationMs,
                            )
                        }
                    }
                    text.isNotBlank() -> {
                        displayMode = DisplayMode.STATIC_TEXT
                        captionFullText = text
                        if (snapshot.displayMode == DisplayMode.STREAMING) {
                            streamingBuffer = StringBuilder(snapshot.streamingText.ifBlank { text })
                        }
                        showAssistantTextKaraoke(text)
                    }
                    else -> showCapsuleWithCard()
                }
            }
        }
        if (capsuleView?.visibility == View.VISIBLE) {
            bringToFrontNow()
        }
    }
    
    private fun cancelPendingSphereEntrance() {
        pendingSphereEntranceRunnable?.let { handler.removeCallbacks(it) }
        pendingSphereEntranceRunnable = null
    }

    private fun cancelSphereEntranceAnimator() {
        cancelPendingSphereEntrance()
        sphereEntranceAnimator?.cancel()
        sphereEntranceAnimator = null
        esperSphereView?.entranceInProgress = false
        esperSphereView?.animate()?.cancel()
    }

    private fun cancelSphereExitAnimator() {
        sphereExitAnimator?.cancel()
        sphereExitAnimator = null
    }

    private fun isOverlayVisiblyOn(): Boolean {
        val root = capsuleView ?: return false
        val sphere = esperSphereView
        return root.visibility == View.VISIBLE &&
            root.alpha > 0.2f &&
            (captionOnly || sphere == null || sphere.alpha > 0.2f)
    }

    private fun isSphereEntranceRunning(): Boolean =
        esperSphereView?.entranceInProgress == true ||
            (sphereEntranceAnimator?.isRunning == true)

    /**
     * Listening / processing chip: copy is committed with the entrance, but alpha
     * eases in slower than the ball so they don't read as one rigid block.
     */
    private fun revealPhaseStatusBadge() {
        val sphere = esperSphereView ?: return
        when (currentState) {
            State.LISTENING -> sphere.revealStatusBadge(
                "LISTENING",
                Color.parseColor("#00FFAA"),
                fadeMs = STATUS_BADGE_FADE_MS,
                startDelayMs = STATUS_BADGE_FADE_DELAY_MS
            )
            State.PROCESSING -> sphere.revealStatusBadge(
                "PROCESSING",
                Color.parseColor("#FBBF24"),
                fadeMs = STATUS_BADGE_FADE_MS,
                startDelayMs = STATUS_BADGE_FADE_DELAY_MS
            )
            else -> Unit
        }
    }

    private fun cancelCaptionOnlyHidePark() {
        captionOnlyHideParkRunnable?.let { handler.removeCallbacks(it) }
        captionOnlyHideParkRunnable = null
    }

    private fun hideCaptionFalloff() {
        captionFalloffOnSettled = null
        captionFalloffFadingIn = false
        captionFalloffAnimator?.cancel()
        captionFalloffAnimator = null
        captionFalloffView?.apply {
            animate().cancel()
            alpha = 0f
            visibility = View.GONE
        }
    }

    private fun fireCaptionFalloffSettled() {
        val cb = captionFalloffOnSettled
        captionFalloffOnSettled = null
        cb?.invoke()
    }

    /**
     * Bottom veil: fade from the current alpha, then park GONE. Never snap a visible
     * mask. Caption-only hide starts the plate first; [startDelayMs] lets the glyphs
     * leave before the mask.
     */
    private fun fadeOutCaptionFalloff(
        durationMs: Long = CAPTION_ONLY_EXIT_MS,
        startDelayMs: Long = 0L,
    ) {
        captionFalloffOnSettled = null
        captionFalloffFadingIn = false
        val falloff = captionFalloffView ?: return
        captionFalloffAnimator?.cancel()
        falloff.animate().cancel()
        if (falloff.visibility != View.VISIBLE || falloff.alpha <= 0.01f) {
            hideCaptionFalloff()
            return
        }
        val start = falloff.alpha.coerceIn(0f, 1f)
        val remaining = (durationMs * start).toLong().coerceAtLeast(16L)
        val anim = ObjectAnimator.ofFloat(falloff, View.ALPHA, start, 0f).apply {
            duration = remaining
            startDelay = startDelayMs.coerceAtLeast(0L)
            interpolator = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationEnd(animation: Animator) {
                    if (captionFalloffAnimator === animation) captionFalloffAnimator = null
                    if (cancelled) return
                    if (falloff.alpha <= 0.02f) {
                        falloff.alpha = 0f
                        falloff.visibility = View.GONE
                    }
                }
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                    if (captionFalloffAnimator === animation) captionFalloffAnimator = null
                }
            })
        }
        captionFalloffAnimator = anim
        anim.start()
    }

    /**
     * Sphere and caption-only TTS share this veil. Fade from the current alpha;
     * never flash to 1. If a fade-in is already running, [onSettled] waits for it.
     */
    private fun fadeInCaptionFalloff(
        durationMs: Long = SPHERE_ENTRANCE_FADE_MS,
        onSettled: (() -> Unit)? = null,
    ) {
        val falloff = captionFalloffView ?: run {
            onSettled?.invoke()
            return
        }
        cancelCaptionOnlyHidePark()
        if (onSettled != null) captionFalloffOnSettled = onSettled
        if (falloff.visibility == View.VISIBLE && falloff.alpha >= 0.99f) {
            // A delayed fade-out may still be armed while alpha is 1 — cancel it
            // before treating the veil as settled, or TTS would appear on a mask
            // that is about to animate away.
            captionFalloffAnimator?.cancel()
            captionFalloffAnimator = null
            captionFalloffFadingIn = false
            fireCaptionFalloffSettled()
            return
        }
        if (captionFalloffFadingIn && captionFalloffAnimator != null) return
        captionFalloffAnimator?.cancel()
        falloff.animate().cancel()
        val start = if (falloff.visibility == View.VISIBLE) falloff.alpha.coerceIn(0f, 1f) else 0f
        falloff.alpha = start
        falloff.visibility = View.VISIBLE
        val remaining = (durationMs * (1f - start)).toLong().coerceAtLeast(16L)
        val anim = ObjectAnimator.ofFloat(falloff, View.ALPHA, start, 1f).apply {
            duration = remaining
            interpolator = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationEnd(animation: Animator) {
                    if (captionFalloffAnimator === animation) captionFalloffAnimator = null
                    captionFalloffFadingIn = false
                    if (cancelled) return
                    falloff.alpha = 1f
                    fireCaptionFalloffSettled()
                }
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                    captionFalloffFadingIn = false
                    if (captionFalloffAnimator === animation) captionFalloffAnimator = null
                }
            })
        }
        captionFalloffFadingIn = true
        captionFalloffAnimator = anim
        anim.start()
    }

    /** Tiny travel — softness is duration + curve, not height. */
    private fun sphereEntranceDropPx(): Float = dpF(5f)

    /** Slow start, long soft settle (ease-in-out, not a snappy ease-out). */
    private fun softLandInterpolator(): android.view.animation.Interpolator =
        PathInterpolator(0.33f, 0.05f, 0.2f, 1f)

    /**
     * Ball: short travel + long soft land. Chip fades on its own slower clock.
     * Root stays opaque so we don't double-multiply alphas.
     */
    private fun playSphereOverlayEntrance(onSettled: (() -> Unit)? = null) {
        val root = capsuleView ?: return
        val sphere = esperSphereView ?: return

        cancelSphereExitAnimator()
        cancelSphereEntranceAnimator()
        root.animate().cancel()
        sphere.animate().cancel()
        sphere.stopAnimations()

        revealPhaseStatusBadge()
        fadeInCaptionFalloff()

        val drop = sphereEntranceDropPx()
        root.visibility = View.VISIBLE
        root.alpha = 1f
        root.translationY = 0f
        OverlayLayerSplit.sync()
        // First paint after GONE: hard raise once, like vinyl show().
        // Already-visible style changes must not remove+add.
        bringToFrontNow(force = true)
        sphere.alpha = 0f
        sphere.scaleX = 1f
        sphere.scaleY = 1f
        sphere.translationY = -drop
        sphere.entranceInProgress = true

        fun runEntrance() {
            if (currentState == State.IDLE) {
                sphere.entranceInProgress = false
                return
            }

            val landEase = softLandInterpolator()
            val sphereFade = ObjectAnimator.ofFloat(sphere, View.ALPHA, 0f, 1f).apply {
                duration = SPHERE_ENTRANCE_FADE_MS
                interpolator = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)
            }
            val sphereDrop = ObjectAnimator.ofFloat(sphere, View.TRANSLATION_Y, -drop, 0f).apply {
                duration = SPHERE_ENTRANCE_DROP_MS
                interpolator = landEase
            }

            val entranceSet = AnimatorSet()
            // Fade can finish first; drop keeps easing so the land feels unhurried.
            entranceSet.playTogether(sphereFade, sphereDrop)
            entranceSet.addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (sphereEntranceAnimator === entranceSet) sphereEntranceAnimator = null
                    sphere.entranceInProgress = false
                    sphere.alpha = 1f
                    root.alpha = 1f
                    sphere.scaleX = 1f
                    sphere.scaleY = 1f
                    sphere.translationY = 0f
                    if (currentState != State.IDLE && !sphere.isAnimating()) {
                        sphere.startAnimations()
                    }
                    onSettled?.invoke()
                }

                override fun onAnimationCancel(animation: android.animation.Animator) {
                    if (sphereEntranceAnimator === entranceSet) sphereEntranceAnimator = null
                    // Keep entranceInProgress if a new entrance owns it; otherwise clear.
                    if (sphereEntranceAnimator == null) {
                        sphere.entranceInProgress = false
                    }
                    sphere.translationY = 0f
                }
            })
            sphereEntranceAnimator = entranceSet
            entranceSet.start()
        }

        if (sphere.width > 0 && sphere.height > 0) {
            runEntrance()
        } else {
            sphere.post { runEntrance() }
        }
    }

    private fun ensureSphereSettledVisible() {
        val root = capsuleView ?: return
        val sphere = esperSphereView ?: return
        // If an entrance is mid-flight, don't yank alpha/translation — let it finish.
        if (isSphereEntranceRunning()) {
            root.visibility = View.VISIBLE
            root.alpha = 1f
            ensureStackedIfNeeded()
            return
        }
        cancelPendingSphereEntrance()
        fadeInCaptionFalloff()
        root.visibility = View.VISIBLE
        root.alpha = 1f
        root.translationY = 0f
        sphere.alpha = 1f
        sphere.translationY = 0f
        // Do not stomp timer breathing scale while that mode is active.
        if (!isTimerFinishedMode) {
            sphere.scaleX = 1f
            sphere.scaleY = 1f
        }
        // Mid-session settle (Listening↔Processing↔Speaking): keep WM order — no remove+add.
        ensureStackedIfNeeded()
        if (!sphere.isAnimating()) {
            sphere.startAnimations()
        }
    }

    /**
     * Already on-screen (e.g. speaking → listening): ball settles with a short drop;
     * chip eases in on its own slower clock.
     */
    private fun playSphereChipSettleDrop() {
        val sphere = esperSphereView ?: return
        revealPhaseStatusBadge()
        sphere.animate().cancel()
        val drop = sphereEntranceDropPx()
        sphere.translationY = -drop
        sphere.alpha = 1f
        sphere.animate()
            .translationY(0f)
            .setDuration(SPHERE_ENTRANCE_DROP_MS)
            .setInterpolator(softLandInterpolator())
            .withEndAction {
                sphere.translationY = 0f
                if (currentState != State.IDLE && !sphere.isAnimating()) {
                    sphere.startAnimations()
                }
            }
            .start()
    }

    /**
     * @param waitForWakeSettle When true (listening after wake), stay hidden only until
     * the wake burst is on-screen, then enter (ball drop + slower chip fade).
     * WakeRippleView timeline is untouched (2s burst).
     */
    private fun showSphereOnly(waitForWakeSettle: Boolean = false) {
        cancelSphereExitAnimator()
        // Listening → processing (or any re-entry) while entrance is playing: only swap chip.
        // Restarting drop/fade here is what felt like the effect being "interrupted".
        // Do not hard-raise here — already-visible session style changes must stay continuous.
        if (isSphereEntranceRunning()) {
            esperSphereView?.moveToCenter(animated = false)
            if (currentState == State.LISTENING || currentState == State.PROCESSING) {
                revealPhaseStatusBadge()
            }
            if (textCardView?.visibility == View.VISIBLE) {
                textCardView?.visibility = View.GONE
                textCardView?.alpha = 0f
            }
            return
        }

        cancelPendingSphereEntrance()

        val visiblyOn = isOverlayVisiblyOn()
        // From hidden: snap to center before entrance. Already visible: ease back to center.
        esperSphereView?.moveToCenter(animated = visiblyOn)

        if (!visiblyOn && waitForWakeSettle) {
            capsuleView?.alpha = 1f
            esperSphereView?.alpha = 0f
            esperSphereView?.translationY = -sphereEntranceDropPx()
            esperSphereView?.scaleX = 1f
            esperSphereView?.scaleY = 1f
            esperSphereView?.stopAnimations()
            val pending = Runnable {
                pendingSphereEntranceRunnable = null
                if (currentState == State.IDLE) return@Runnable
                if (isSphereEntranceRunning()) {
                    revealPhaseStatusBadge()
                    return@Runnable
                }
                if (isOverlayVisiblyOn()) {
                    ensureSphereSettledVisible()
                    if (currentState == State.LISTENING || currentState == State.PROCESSING) {
                        revealPhaseStatusBadge()
                    }
                    return@Runnable
                }
                // First paint: playSphereOverlayEntrance hard-raises once.
                playSphereOverlayEntrance()
            }
            pendingSphereEntranceRunnable = pending
            handler.postDelayed(pending, WAKE_SETTLE_BEFORE_SPHERE_MS)
        } else if (!visiblyOn) {
            playSphereOverlayEntrance()
        } else {
            // Already showing: chip / pose only — no WM remove+add.
            ensureSphereSettledVisible()
            if (currentState == State.LISTENING || currentState == State.PROCESSING) {
                if (esperSphereView?.isStatusBadgeMostlyVisible() == true) {
                    // Listening → processing: only swap chip label, no re-drop.
                    revealPhaseStatusBadge()
                } else {
                    playSphereChipSettleDrop()
                }
            }
        }

        if (textCardView?.visibility == View.VISIBLE) {
            textCardView?.animate()?.cancel()
            textCardView?.animate()
                ?.alpha(0f)
                ?.setDuration(200)
                ?.withEndAction { textCardView?.visibility = View.GONE }
                ?.start()
        }
    }

    private fun showCapsuleWithCard() {
        if (captionOnly) {
            showCaptionCardOnly()
            return
        }
        cancelSphereExitAnimator()
        cancelPendingSphereEntrance()
        val wasHidden = !isOverlayVisiblyOn()

        // Pose first: snap when fading in from hidden; animate when leaving listening/processing.
        esperSphereView?.moveToTop(animated = !wasHidden)
        esperSphereView?.hideStatusBadge(animated = false)

        // Caption appears with audio (sound-first). If idle screensaver restacked during the
        // HA URL wait, re-arm one hard raise — not every Listening/Processing style change.
        if (!wasHidden && ScreensaverController.isScreensaverVisible()) {
            needsBringToFront = true
        }

        if (wasHidden) {
            // First speak after hidden: entrance hard-raises once when VISIBLE.
            playSphereOverlayEntrance()
        } else {
            // Processing → speaking: keep pulse continuous; hard raise only if re-armed above.
            ensureSphereSettledVisible()
        }

        textCardView?.visibility = View.VISIBLE
        textCardView?.alpha = 0f
        relayoutCaptionUnderSphere()
        textCardView?.post { relayoutCaptionUnderSphere() }
        textCardView?.animate()?.cancel()
        textCardView?.animate()
            ?.alpha(1f)
            ?.setDuration(420)
            ?.setStartDelay(if (wasHidden) 280 else 140)
            ?.setInterpolator(DecelerateInterpolator(1.6f))
            ?.start()
    }

    private fun hideCapsule(onEnd: (() -> Unit)? = null) {
        cancelSphereEntranceAnimator()
        cancelSphereExitAnimator()
        timerBreathingAnimator?.cancel()
        timerBreathingAnimator = null
        isTimerFinishedMode = false
        capsuleView?.animate()?.cancel()
        esperSphereView?.animate()?.cancel()

        val root = capsuleView
        val sphere = esperSphereView
        if (root == null) {
            sphere?.resetExitVisuals()
            sphere?.resetAppearance(Expression.NEUTRAL)
            onEnd?.invoke()
            return
        }

        // Freeze the live face/float; do not snap tint or drop the 10dp bob first.
        sphere?.freezeForExit()

        // Timer breathing owns View scale — fold it into drawn radius so we don't snap to 1.
        val inheritedScale = if (sphere != null) {
            val viewScale = ((sphere.scaleX + sphere.scaleY) * 0.5f).coerceAtLeast(0.01f)
            sphere.setExitScale(viewScale)
            sphere.scaleX = 1f
            sphere.scaleY = 1f
            sphere.alpha = 1f
            viewScale
        } else {
            1f
        }
        val targetScale = inheritedScale * SPHERE_EXIT_SCALE
        val startAlpha = root.alpha.coerceIn(0f, 1f)
        val landEase = softLandInterpolator()
        // One clock — AnimatorSet + after(delay) can clip the fade on older APIs,
        // which read as "the exit never ran".
        val totalMs = if (sphere != null) {
            maxOf(SPHERE_EXIT_SCALE_MS, SPHERE_EXIT_FADE_DELAY_MS + SPHERE_EXIT_FADE_MS)
        } else {
            SPHERE_EXIT_FADE_MS
        }

        val hideAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = totalMs
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { animator ->
                val t = animator.animatedFraction * totalMs
                if (sphere != null) {
                    val scaleT = (t / SPHERE_EXIT_SCALE_MS.toFloat()).coerceIn(0f, 1f)
                    val s = inheritedScale +
                        (targetScale - inheritedScale) * landEase.getInterpolation(scaleT)
                    sphere.setExitScale(s)
                }
                val fadeT = if (sphere != null) {
                    ((t - SPHERE_EXIT_FADE_DELAY_MS) / SPHERE_EXIT_FADE_MS.toFloat())
                        .coerceIn(0f, 1f)
                } else {
                    animator.animatedFraction
                }
                root.alpha = startAlpha * (1f - landEase.getInterpolation(fadeT))
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var finished = false

                private fun finishIfStillIdle() {
                    if (finished) return
                    finished = true
                    if (sphereExitAnimator === this@apply) sphereExitAnimator = null
                    sphere?.resetExitVisuals()
                    // A newer session already owns the overlay — don't GONE or swap the face.
                    if (currentState != State.IDLE) return
                    root.visibility = View.GONE
                    root.alpha = 1f
                    root.translationY = 0f
                    OverlayLayerSplit.sync()
                    // FAB already sits above this window from the first show.
                    // GONE does not change z-order; a restack only blanks the disc.
                    sphere?.scaleX = 1f
                    sphere?.scaleY = 1f
                    sphere?.alpha = 1f
                    sphere?.translationY = 0f
                    sphere?.resetAppearance(Expression.NEUTRAL)
                }

                override fun onAnimationEnd(animation: android.animation.Animator) {
                    finishIfStillIdle()
                    if (currentState == State.IDLE) onEnd?.invoke()
                }

                override fun onAnimationCancel(animation: android.animation.Animator) {
                    finishIfStillIdle()
                }
            })
        }
        sphereExitAnimator = hideAnim
        hideAnim.start()
    }

    
    
    /**
     * Wipe subtitle text + paging state at a turn boundary. Symmetric to the sphere's
     * [EsperSphereView.resetAppearance]: continuous dialogue has no hide between turns, so
     * without this the next turn can inherit — or the same-utterance dedup in
     * [initKaraokeWithDuration] can keep — the previous TTS caption.
     */
    private fun resetCaptionState() {
        cancelCaptionEntrance()
        displayMode = DisplayMode.NONE
        lastStreamingUiUpdateMs = 0L
        streamingBuffer.clear()
        currentText = ""
        captionFullText = ""
        captionPages = emptyList()
        captionPageIndex = 0
        captionPageWeights = emptyList()
        captionPageTotalWeight = 1f
        karaokeDurationMs = 0L
        karaokeProgressMs = 0L
        clearCaptionStack()
    }

    private fun enterListeningMode() {
        Log.d(TAG, "state: $currentState -> LISTENING (captionOnly=$captionOnly)")
        currentState = State.LISTENING
        resetCaptionState()
        // STT phase only: stuck at the start with no Processing/Speaking advance.
        armStuckListeningWatchdog()
        if (captionOnly) {
            // Button turn: the disc's level ring already says "listening" and the transcript
            // goes to the button's bubble. Nothing to draw here until the reply; a previous
            // reply fades out instead of lingering.
            if (textCardView?.visibility == View.VISIBLE) fadeOutCaptionOnlyCard()
            return
        }
        esperSphereView?.setExpression(Expression.LISTENING)
        showSphereOnly(waitForWakeSettle = true)
    }

    private fun enterProcessingMode() {
        Log.d(TAG, "state: $currentState -> PROCESSING (captionOnly=$captionOnly)")
        currentState = State.PROCESSING
        // Past STT — cancel listening stuck-lease (session advanced).
        cancelStuckListeningWatchdog()
        if (captionOnly) {
            // Nothing of ours is on screen during Processing; the reply fades in when it lands.
            resetCaptionState()
            return
        }
        // New-turn boundary too — some pipelines skip Listening and go straight to Processing.
        resetCaptionState()
        esperSphereView?.setExpression(Expression.THINKING)
        // If still waiting on wake settle, promote to immediate entrance.
        showSphereOnly(waitForWakeSettle = false)
    }

    private fun enterSpeakingMode() {
        Log.d(TAG, "state: $currentState -> SPEAKING")
        currentState = State.SPEAKING
        currentText = ""
        clearCaptionStack()
        // Emotion is set by the caller (karaoke / assistant-text / streaming) right after this,
        // so don't set SPEAKING here — that only caused a cyan→emotion double color transition.
        cancelStuckListeningWatchdog()
        showCapsuleWithCard()
    }

    /**
     * Caption-only presentation: window up, sphere kept invisible, 3-line block parked at
     * the bottom (the `sphere == null` branch of [relayoutCaptionUnderSphere]).
     */
    private fun showCaptionCardOnly() {
        val root = capsuleView ?: return
        cancelSphereExitAnimator()
        cancelPendingSphereEntrance()
        cancelSphereEntranceAnimator()
        val wasHidden = root.visibility != View.VISIBLE || root.alpha < 0.2f

        esperSphereView?.animate()?.cancel()
        esperSphereView?.stopAnimations()
        esperSphereView?.hideStatusBadge(animated = false)
        esperSphereView?.alpha = 0f
        cancelCaptionOnlyHidePark()
        root.animate().cancel()
        // Still GONE. First reply after the cold-start park climbs once,
        // before the veil, so boot windows are not covering the slot.
        // Later replies leave the slot alone.
        val covered = AppWindowService.hasWindowAttached() || AiBrowserService.isShowing()
        if (wasHidden && (plateNeedsClimb || needsBringToFront || covered)) {
            plateNeedsClimb = false
            bringToFrontNow(force = true)
        }
        // Veil leads the plate. Keep leftover alpha and fade up — never snap it away.
        fadeInCaptionFalloff(CAPTION_ONLY_VEIL_LEAD_MS)
        root.visibility = View.VISIBLE
        root.alpha = 1f
        root.translationY = 0f

        val card = textCardView ?: return
        card.animate().cancel()
        card.visibility = View.VISIBLE
        // Plate stays at 0 here; [presentCaptionFirstPage] fades it in after the veil.
        card.alpha = 0f
        (card.background as? CaptionFogDrawable)?.setCharcoalPlate(true)
        relayoutCaptionUnderSphere()
        card.post { relayoutCaptionUnderSphere() }
    }

    private fun fadeOutCaptionOnlyCard() {
        val card = textCardView ?: return
        captionRenderGeneration += 1
        stackSwitchAnimator?.cancel()
        stackSwitchAnimator = null
        fadeOutCaptionFalloff(
            durationMs = CAPTION_ONLY_EXIT_MS,
            startDelayMs = CAPTION_ONLY_VEIL_EXIT_LAG_MS,
        )
        card.animate().cancel()
        card.animate()
            .alpha(0f)
            .translationY(dpF(8f))
            .setDuration(CAPTION_ONLY_EXIT_MS)
            .setInterpolator(DecelerateInterpolator(1.4f))
            .withEndAction {
                // Speaking re-shows the card itself; only park it if nothing took over meanwhile.
                if (currentState == State.LISTENING || currentState == State.PROCESSING) {
                    card.visibility = View.GONE
                    card.translationY = 0f
                }
            }
            .start()
    }

    private fun hideAll(onEnd: (() -> Unit)? = null) {
        Log.d(TAG, "state: $currentState -> IDLE (hide)")
        OverlayZOrderCoordinator.cancelScheduledVoiceRaise()
        cancelStuckListeningWatchdog()
        stopCursorBlink()
        currentState = State.IDLE
        displayMode = DisplayMode.NONE
        val wasCaptionOnly = captionOnly
        cancelCaptionEntrance()
        cancelCaptionOnlyHidePark()
        if (wasCaptionOnly) {
            captionRenderGeneration += 1
            stackSwitchAnimator?.cancel()
            stackSwitchAnimator = null
        }
        val done = {
            captionOnly = false
            applyCaptionTypography(false)
            onEnd?.invoke()
        }
        if (capsuleView?.visibility == View.VISIBLE || isOverlayVisiblyOn()) {
            if (wasCaptionOnly) {
                // Plate and the last veil fade on their own clocks; root stays opaque
                // so the two alphas are not multiplied into a snap.
                val root = capsuleView
                val card = textCardView
                card?.animate()?.cancel()
                root?.animate()?.cancel()
                fadeOutCaptionFalloff(
                    durationMs = CAPTION_ONLY_EXIT_MS,
                    startDelayMs = CAPTION_ONLY_VEIL_EXIT_LAG_MS,
                )
                val fadeTarget = if (card != null && card.visibility == View.VISIBLE) card else root
                fadeTarget?.animate()
                    ?.alpha(0f)
                    ?.setDuration(CAPTION_ONLY_EXIT_MS)
                    ?.setInterpolator(DecelerateInterpolator(1.45f))
                    ?.withEndAction {
                        if (currentState != State.IDLE) return@withEndAction
                        val park = Runnable {
                            captionOnlyHideParkRunnable = null
                            if (currentState != State.IDLE) return@Runnable
                            root?.visibility = View.GONE
                            root?.alpha = 1f
                            card?.visibility = View.GONE
                            card?.alpha = 0f
                            card?.translationY = 0f
                            hideCaptionFalloff()
                            esperSphereView?.resetExitVisuals()
                            esperSphereView?.resetAppearance(Expression.NEUTRAL)
                            done()
                        }
                        // Veil lags the plate; don't GONE the window (and snap the mask)
                        // until that lag has played out.
                        cancelCaptionOnlyHidePark()
                        if (CAPTION_ONLY_VEIL_EXIT_LAG_MS <= 0L) {
                            park.run()
                        } else {
                            captionOnlyHideParkRunnable = park
                            handler.postDelayed(park, CAPTION_ONLY_VEIL_EXIT_LAG_MS)
                        }
                    }
                    ?.start()
                    ?: run {
                        hideCaptionFalloff()
                        done()
                    }
            } else {
                hideCapsule { done() }
            }
        } else {
            esperSphereView?.resetExitVisuals()
            esperSphereView?.resetAppearance(Expression.NEUTRAL)
            done()
        }
    }

    /**
     * STT / Listening safety lease. Armed only on [enterListeningMode]; cancelled when
     * the session leaves Listening (Processing / Speaking / hide). If still Listening
     * after [STUCK_LISTENING_TIMEOUT_MS], force-dismiss — nobody talks for 2 continuous minutes.
     */
    private fun armStuckListeningWatchdog() {
        cancelStuckListeningWatchdog()
        val runnable = Runnable {
            stuckListeningWatchdogRunnable = null
            if (currentState != State.LISTENING) return@Runnable
            if (capsuleView?.visibility != View.VISIBLE && !isOverlayVisiblyOn()) return@Runnable
            Log.w(TAG, "Listening/STT stuck >${STUCK_LISTENING_TIMEOUT_MS}ms — force dismiss")
            forceDismissStuckOverlay()
        }
        stuckListeningWatchdogRunnable = runnable
        handler.postDelayed(runnable, STUCK_LISTENING_TIMEOUT_MS)
    }

    private fun cancelStuckListeningWatchdog() {
        stuckListeningWatchdogRunnable?.let { handler.removeCallbacks(it) }
        stuckListeningWatchdogRunnable = null
    }

    /** Hard clear display without relying on hide animation completing. */
    private fun forceDismissStuckOverlay() {
        cancelStuckListeningWatchdog()
        cancelPendingSphereEntrance()
        sphereEntranceAnimator?.cancel()
        sphereEntranceAnimator = null
        sphereExitAnimator?.cancel()
        sphereExitAnimator = null
        stackSwitchAnimator?.cancel()
        stackSwitchAnimator = null
        cancelCaptionEntrance()
        timerBreathingAnimator?.cancel()
        timerBreathingAnimator = null
        isTimerFinishedMode = false
        stopCursorBlink()
        karaokeRunnable?.let { handler.removeCallbacks(it) }
        karaokeRunnable = null
        autoHideRunnable?.let { handler.removeCallbacks(it) }
        autoHideRunnable = null
        scrollRunnable?.let { handler.removeCallbacks(it) }
        scrollRunnable = null
        esperSphereView?.stopAnimations()
        esperSphereView?.animate()?.cancel()
        capsuleView?.animate()?.cancel()
        textCardView?.animate()?.cancel()
        currentState = State.IDLE
        displayMode = DisplayMode.NONE
        needsBringToFront = true
        lastStreamingUiUpdateMs = 0L
        streamingBuffer.clear()
        currentText = ""
        captionFullText = ""
        captionPages = emptyList()
        captionPageIndex = 0
        captionPageWeights = emptyList()
        captionPageTotalWeight = 1f
        karaokeDurationMs = 0L
        karaokeProgressMs = 0L
        clearCaptionStack()
        textCardView?.visibility = View.GONE
        textCardView?.alpha = 0f
        val root = capsuleView
        if (root != null) {
            root.animate().cancel()
            root.visibility = View.GONE
            root.alpha = 1f
            root.translationY = 0f
        }
        esperSphereView?.apply {
            scaleX = 1f
            scaleY = 1f
            alpha = 1f
            translationY = 0f
            resetExitVisuals()
            resetAppearance(Expression.NEUTRAL)
        }
    }
    
    private var timerBreathingAnimator: ValueAnimator? = null
    private var isTimerFinishedMode = false
    
    @SuppressLint("ClickableViewAccessibility")
    private fun enterTimerFinishedMode() {
        handler.post {
            isTimerFinishedMode = true
            karaokeRunnable?.let { handler.removeCallbacks(it) }
            autoHideRunnable?.let { handler.removeCallbacks(it) }
            stopCursorBlink()
            
            esperSphereView?.setExpression(Expression.EXCITED)
            showSphereOnly()
            cancelStuckListeningWatchdog()

            // Let entrance settle before timer breathing owns scaleX/Y.
            sphereEntranceAnimator?.cancel()
            sphereEntranceAnimator = null
            timerBreathingAnimator?.cancel()
            timerBreathingAnimator = ValueAnimator.ofFloat(0.85f, 1.15f).apply {
                duration = 600L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = AccelerateDecelerateInterpolator()
                startDelay = 320L
                addUpdateListener { animator ->
                    if (!isTimerFinishedMode) return@addUpdateListener
                    val scale = animator.animatedValue as Float
                    esperSphereView?.scaleX = scale
                    esperSphereView?.scaleY = scale
                }
                start()
            }
        }
    }
    
    private fun exitTimerFinishedMode() {
        handler.post {
            isTimerFinishedMode = false
            timerBreathingAnimator?.cancel()
            timerBreathingAnimator = null
            hideAll()
        }
    }

    private var scrollRunnable: Runnable? = null

    private fun updateDisplayText() {
        // Streaming / cursor path: single centered line (no prev/next).
        renderSingleCaption(if (isCursorVisible) currentText + CURSOR_CHAR else currentText)
    }

    private fun startCursorBlink() {
        isCursorVisible = true
        updateDisplayText()
        cursorBlinkRunnable?.let { handler.removeCallbacks(it) }
        cursorBlinkRunnable = object : Runnable {
            override fun run() {
                isCursorVisible = !isCursorVisible
                updateDisplayText()
                handler.postDelayed(this, 530)
            }
        }
        handler.postDelayed(cursorBlinkRunnable!!, 530)
    }

    private fun stopCursorBlink() {
        cursorBlinkRunnable?.let { handler.removeCallbacks(it) }
        cursorBlinkRunnable = null
        isCursorVisible = false
        if (displayMode == DisplayMode.STREAMING || displayMode == DisplayMode.STATIC_TEXT) {
            renderSingleCaption(currentText)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let { handleIntent(it) }
        return START_STICKY
    }

    /**
     * Decide the presentation for a turn on its first show. A turn that starts from the
     * Quick Wake button never brings the sphere; one that starts mid-flight (e.g. straight
     * to Processing) inherits whatever the satellite says right now.
     */
    private fun lockCaptionOnlyForTurn(force: Boolean) {
        val buttonTurn = VoiceSatelliteService.isQuickWakeSessionActive()
        if (buttonTurn) {
            if (!captionOnly) {
                captionOnly = true
                applyCaptionTypography(true)
            }
            return
        }
        if (force || currentState == State.IDLE) {
            captionOnly = false
            applyCaptionTypography(false)
        }
    }

    /**
     * Caption-only turns read the reply from the middle of the screen. Type is sized by
     * [CAPTION_ONLY_CJK_PER_LINE] so a line holds slightly more glyphs than the sphere
     * caption — quieter, not larger. The 3-line slot is re-measured; paging follows the
     * live TextView paint.
     */
    private fun applyCaptionTypography(captionOnlyMode: Boolean) {
        if (captionOnlyTypographyApplied == captionOnlyMode) return
        val metrics = captionMetrics ?: return
        val cardWidth = if (captionOnlyMode) captionOnlyColumnWidthPx(metrics) else metrics.columnWidthPx
        val padH = if (captionOnlyMode) captionOnlyPadHPx(metrics) else metrics.padHPx
        val contentWidthPx = (cardWidth - padH * 2).coerceAtLeast(dp(120f))
        val sp = if (captionOnlyMode) captionOnlySp(metrics, contentWidthPx) else metrics.currSp
        val threeLine = if (captionOnlyMode) {
            measureCaptionThreeLineHeightPx(sp, contentWidthPx)
        } else {
            metrics.threeLineHeightPx
        }
        for (tv in listOfNotNull(capsuleTextView, captionOutgoingView)) {
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            // Centred block: a one- or two-line page sits in the middle of the slot, not on
            // the first-line baseline the sphere layout aligns to.
            tv.gravity = if (captionOnlyMode) Gravity.CENTER else Gravity.TOP or Gravity.CENTER_HORIZONTAL
            (tv.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.height = threeLine
                tv.layoutParams = lp
            }
        }
        captionStackView?.let { host ->
            val lp = host.layoutParams ?: return@let
            lp.height = threeLine
            host.layoutParams = lp
        }
        captionOnlyThreeLineHeightPx = threeLine
        captionOnlyTypographyApplied = captionOnlyMode
        textCardView?.let { card ->
            card.setPadding(padH, metrics.padVPx, padH, metrics.padVPx)
            (card.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.width = cardWidth
                lp.height = threeLine + metrics.padVPx * 2
                card.layoutParams = lp
            }
        }
        (textCardView?.background as? CaptionFogDrawable)?.setCharcoalPlate(captionOnlyMode)
        if (textCardView?.visibility == View.VISIBLE) syncCaptionFogToText()
    }

    /**
     * Type size so [cjkPerLine] CJK glyphs (≈ 1 em each) fill [contentWidthPx].
     * Phone and tablet then show the same sentence shape; the tablet is just bigger.
     */
    private fun spForCjkPerLine(contentWidthPx: Int, cjkPerLine: Float): Float {
        val density = resources.displayMetrics.density.coerceAtLeast(0.1f)
        val contentDp = contentWidthPx / density
        return contentDp / cjkPerLine
    }

    /**
     * Voice-button TTS plate. A little wider than the sphere caption, still inset
     * on every screen, so a long reply is not pinched left and right.
     */
    private fun captionOnlyColumnWidthPx(metrics: CaptionMetrics): Int {
        val screenW = resources.displayMetrics.widthPixels
        val wider = (metrics.columnWidthPx * CAPTION_ONLY_WIDTH_SCALE).roundToInt()
        val inset = dp(20f)
        val ceiling = (screenW - inset * 2).coerceAtLeast(metrics.columnWidthPx)
        return wider.coerceIn(metrics.columnWidthPx, ceiling)
    }

    /** A little more side room inside the button plate than the sphere caption. */
    private fun captionOnlyPadHPx(metrics: CaptionMetrics): Int =
        metrics.padHPx + dp(4f)

    /**
     * Caption-only type uses more glyphs per line than the sphere caption, so the
     * centred plate stays a touch quieter. Never larger than sphere-mode size.
     */
    private fun captionOnlySp(metrics: CaptionMetrics, contentWidthPx: Int): Float {
        val byChars = spForCjkPerLine(contentWidthPx, CAPTION_ONLY_CJK_PER_LINE)
        val ceiling = metrics.currSp.coerceAtLeast(16f)
        return byChars.coerceIn(16f, ceiling)
    }

    private fun activeThreeLineHeightPx(metrics: CaptionMetrics): Int =
        if (captionOnlyTypographyApplied && captionOnlyThreeLineHeightPx > 0) {
            captionOnlyThreeLineHeightPx
        } else {
            metrics.threeLineHeightPx
        }

    private fun handleIntent(intent: Intent) {
        when (intent.action) {
            ACTION_SHOW_LISTENING -> {
                handler.post {
                    karaokeRunnable?.let { handler.removeCallbacks(it) }
                    autoHideRunnable?.let { handler.removeCallbacks(it) }
                    stopCursorBlink()
                    lockCaptionOnlyForTurn(force = true)
                    enterListeningMode()
                }
            }
            ACTION_SHOW_PROCESSING -> {
                handler.post {
                    karaokeRunnable?.let { handler.removeCallbacks(it) }
                    autoHideRunnable?.let { handler.removeCallbacks(it) }
                    stopCursorBlink()
                    lockCaptionOnlyForTurn(force = false)
                    enterProcessingMode()
                }
            }
            ACTION_SHOW_ASSISTANT_TEXT -> {
                val text = intent.getStringExtra(EXTRA_TEXT) ?: return
                handler.post { lockCaptionOnlyForTurn(force = false) }
                showAssistantTextKaraoke(text)
            }
            ACTION_APPEND_TEXT -> {
                val text = intent.getStringExtra(EXTRA_TEXT) ?: return
                appendText(text)
            }
            ACTION_FINISH_STREAMING -> {
                handler.post {
                    stopCursorBlink()
                    if (displayMode != DisplayMode.KARAOKE) {
                        scheduleAutoHide()
                    }
                }
            }
            ACTION_SHOW_KARAOKE -> {
                val text = intent.getStringExtra(EXTRA_TEXT) ?: return
                val durationMs = intent.getLongExtra(EXTRA_DURATION, 0L)
                handler.post { lockCaptionOnlyForTurn(force = false) }
                initKaraokeWithDuration(text, durationMs)
            }
            ACTION_UPDATE_KARAOKE_PROGRESS -> {
                val currentMs = intent.getLongExtra(EXTRA_CURRENT_MS, 0L)
                val totalMs = intent.getLongExtra(EXTRA_TOTAL_MS, 0L)
                updateKaraokeProgress(currentMs, totalMs)
            }
            ACTION_HIDE -> hideOverlay()
            ACTION_PREWARM -> handler.post { placePrewarmedWindow() }
            ACTION_CLEAR -> clearText()
            ACTION_SHOW_TIMER_FINISHED -> enterTimerFinishedMode()
            ACTION_HIDE_TIMER_FINISHED -> exitTimerFinishedMode()
        }
    }

    private var currentText = ""
    private var karaokeRunnable: Runnable? = null
    private var autoHideRunnable: Runnable? = null
    /** Stuck Listening/STT safety — see [armStuckListeningWatchdog]. */
    private var stuckListeningWatchdogRunnable: Runnable? = null

    /** Full-utterance emotion (owns the sphere tint); per-page eye swaps fall back to it. */
    private var utteranceExpression = Expression.NEUTRAL

    /**
     * Quick Wake turn: the button already is the mic-side status surface (level ring +
     * transcript bubble), so this window runs caption-only — the sphere never appears and
     * the 3-line block is centred on screen carrying the reply. Locked in when the
     * turn's first show arrives; cleared on hide.
     */
    private var captionOnly = false
    /** Typography currently applied to the caption views: true = caption-only (larger) sizing. */
    private var captionOnlyTypographyApplied = false
    /** 3-line slot height re-measured for the caption-only font size. */
    private var captionOnlyThreeLineHeightPx = 0

    /** Full assistant text; shown via layout pages (≤3 lines), not sentence cuts. */
    private var captionFullText = ""
    private var captionPages = listOf<String>()
    private var captionPageIndex = 0
    private var captionPageWeights = listOf<Float>()
    private var captionPageTotalWeight = 1f
    /** May stay 0 until progressive URL TTS reports duration; progress then fills it in. */
    private var karaokeDurationMs = 0L
    /** Last TTS clock seen — used to re-sync page after a late layout rebuild. */
    private var karaokeProgressMs = 0L
    
    private fun showAssistantTextKaraoke(text: String) {
        handler.post {
            karaokeRunnable?.let { handler.removeCallbacks(it) }
            autoHideRunnable?.let { handler.removeCallbacks(it) }
            displayMode = DisplayMode.STATIC_TEXT
            streamingBuffer.clear()

            if (currentState != State.SPEAKING) {
                enterSpeakingMode()
            }

            // New sentence block (SSE): the previous block's TTS clock must not
            // be read as a mid-utterance restore, or the page turn hard-snaps.
            if (captionFullText != text.trim()) {
                karaokeDurationMs = 0L
                karaokeProgressMs = 0L
            }
            prepareCaptionPages(text)
            val detection = EmotionKeywordDetector.detect(text)
            utteranceExpression = detection.expression
            Log.d(
                TAG,
                "assistant text: len=${text.length} pages=${captionPages.size} " +
                    "expr=${detection.expression} keyword=${detection.keyword}"
            )
            esperSphereView?.setExpression(detection.expression)

            if (captionPages.isNotEmpty()) {
                presentCaptionFirstPage(0)
            } else {
                clearCaptionStack()
            }

            if (capsuleView?.visibility != View.VISIBLE) showCapsuleWithCard()
        }
    }
    
    private fun splitIntoSentences(text: String): List<String> {
        if (!hasSemanticText(text)) return emptyList()

        val sentences = mutableListOf<String>()
        var sentenceStart = 0
        text.forEachIndexed { index, char ->
            if (!isSentenceBoundary(text, index, char)) return@forEachIndexed

            val sentence = text.substring(sentenceStart, index + 1).trim()
            if (hasSemanticText(sentence)) {
                sentences.add(sentence)
            }
            sentenceStart = index + 1
        }

        val tail = text.substring(sentenceStart).trim()
        if (hasSemanticText(tail)) {
            sentences.add(tail)
        }
        return sentences.ifEmpty { listOf(text) }
    }

    private fun isSentenceBoundary(text: String, index: Int, char: Char): Boolean {
        if (char == '。' || char == '！' || char == '？' || char == '!' || char == '?') {
            return true
        }
        if (char != '.') return false

        val previous = text.previousNonWhitespace(index)
        if (previous == null || !previous.isLetterOrDigit()) return false
        val next = text.getOrNull(index + 1)
        return next == null || next.isWhitespace()
    }

    private fun hasSemanticText(text: String): Boolean {
        return text.any { it.isLetterOrDigit() || it.code in 0x4E00..0x9FFF }
    }

    private fun String.previousNonWhitespace(index: Int): Char? {
        for (i in index - 1 downTo 0) {
            val char = this[i]
            if (!char.isWhitespace()) return char
        }
        return null
    }

    private fun isCaptionCjkChar(char: Char): Boolean {
        val code = char.code
        return code in 0x4E00..0x9FFF ||
            code in 0x3400..0x4DBF ||
            code in 0x3040..0x30FF ||
            code in 0xAC00..0xD7AF
    }

    /**
     * Speech load for one caption page. Matches lyric units (CJK=1, Latin≈0.5)
     * and adds pause mass for punctuation — HA TTS usually breathes at ，。！？.
     * Old Latin+=2 starved Chinese pages and flipped early.
     */
    private fun calculateSpeechWeight(text: String): Float {
        var units = 0f
        text.forEachIndexed { index, char ->
            when {
                isCaptionCjkChar(char) -> units += 1f
                char.isLetter() -> units += 0.5f
                char.isDigit() -> units += 0.55f
                char == '。' || char == '！' || char == '？' || char == '!' || char == '?' ->
                    units += 2.6f
                char == '.' && isSentenceBoundary(text, index, char) -> units += 2.6f
                char == '；' || char == ';' -> units += 1.7f
                char == '，' || char == '、' || char == ',' -> units += 1.15f
                char == '…' -> units += 1.8f
                char == '：' || char == ':' -> units += 0.85f
                char == '—' || char == '–' -> units += 1.1f
            }
        }
        return units.coerceAtLeast(0.5f)
    }

    /** Pack page boundaries into the spoken span; MP3/URL TTS often pads the end. */
    private fun effectiveCaptionDurationMs(totalMs: Long): Long {
        if (totalMs <= 1L) return 1L
        val trim = (totalMs * CAPTION_TRAILING_SILENCE_FRAC).toLong()
            .coerceIn(CAPTION_TRAILING_SILENCE_MIN_MS, CAPTION_TRAILING_SILENCE_MAX_MS)
        return (totalMs - trim).coerceAtLeast((totalMs * 0.92f).toLong().coerceAtLeast(1L))
    }

    /**
     * Map TTS clock → page. Lead starts dissolve slightly before the spoken
     * boundary so the turn finishes near the first words of the next page.
     */
    private fun resolveCaptionPageIndex(currentMs: Long, totalMs: Long): Int {
        if (captionPages.isEmpty()) return 0
        if (captionPages.size == 1 || captionPageTotalWeight <= 0f) return 0

        val effectiveTotal = effectiveCaptionDurationMs(totalMs)
        val lead = captionPageTurnLeadMs(effectiveTotal)
        val t = (currentMs + lead).coerceIn(0L, effectiveTotal)

        var accumulated = 0f
        for (index in captionPages.indices) {
            if (index == captionPages.lastIndex) return index
            accumulated += captionPageWeights.getOrElse(index) { 0.5f }
            val pageEndMs = (effectiveTotal * (accumulated / captionPageTotalWeight)).toLong()
            if (t < pageEndMs) return index
        }
        return captionPages.lastIndex
    }

    /**
     * How early to start the page dissolve. Caption-only type makes more, shorter pages;
     * a fixed 220ms lead would eat the last words of a ~800ms page. Sphere turns keep
     * the original cap against the whole utterance.
     */
    private fun captionPageTurnLeadMs(effectiveTotal: Long): Long {
        val base = CAPTION_PAGE_TURN_LEAD_MS.coerceAtMost(effectiveTotal / 12L)
        if (!captionOnly || captionPages.size <= 1) return base
        val avgPage = (effectiveTotal / captionPages.size).coerceAtLeast(1L)
        return minOf(base, (avgPage * 0.18f).toLong().coerceAtLeast(80L))
    }

    private fun syncCaptionPageToProgress(animate: Boolean) {
        if (captionPages.isEmpty() || karaokeDurationMs <= 0L) return
        val resolved = resolveCaptionPageIndex(karaokeProgressMs, karaokeDurationMs)
        val target = when {
            resolved >= captionPageIndex -> resolved
            // Only rewind on a clear clock jump (restart / corrected position).
            karaokeProgressMs + 900L <
                pageStartMsApprox(captionPageIndex, karaokeDurationMs) -> resolved
            else -> captionPageIndex
        }
        if (target != captionPageIndex) {
            val fromPage = captionPageIndex
            dissolveCaptionPage(target, animate = animate)
            // TTS clock turned the page: re-read the mood from the words being spoken now.
            // Eyes only — the sphere tint stays on the utterance emotion (no color flicker).
            val pageDetection = EmotionKeywordDetector.detect(captionPages.getOrNull(target))
            val eyes = if (pageDetection.expression == Expression.NEUTRAL) {
                utteranceExpression
            } else {
                pageDetection.expression
            }
            esperSphereView?.setEyeExpression(eyes)
            Log.d(
                TAG,
                "page turn: $fromPage -> $target/${captionPages.lastIndex} " +
                    "@${karaokeProgressMs}/${karaokeDurationMs}ms " +
                    "eyes=$eyes keyword=${pageDetection.keyword}"
            )
        }
    }

    private fun pageStartMsApprox(pageIndex: Int, totalMs: Long): Long {
        if (pageIndex <= 0 || captionPages.isEmpty() || captionPageTotalWeight <= 0f) return 0L
        val effectiveTotal = effectiveCaptionDurationMs(totalMs)
        var accumulatedBefore = 0f
        val end = pageIndex.coerceAtMost(captionPages.size)
        for (i in 0 until end) {
            accumulatedBefore += captionPageWeights.getOrElse(i) { 0.5f }
        }
        return (effectiveTotal * (accumulatedBefore / captionPageTotalWeight)).toLong()
    }

    private fun initKaraokeWithDuration(text: String, durationMs: Long) {
        handler.post {
            karaokeRunnable?.let { handler.removeCallbacks(it) }
            autoHideRunnable?.let { handler.removeCallbacks(it) }
            streamingBuffer.clear()

            val normalized = text.trim()
            // Same utterance already on screen (static or karaoke): only refresh the clock.
            // Progressive URL TTS often starts with duration=0 then reports ~seconds later.
            if ((displayMode == DisplayMode.KARAOKE || displayMode == DisplayMode.STATIC_TEXT) &&
                captionFullText == normalized &&
                captionPages.isNotEmpty()
            ) {
                if (durationMs > 0L) karaokeDurationMs = durationMs
                displayMode = DisplayMode.KARAOKE
                syncCaptionPageToProgress(animate = false)
                return@post
            }

            displayMode = DisplayMode.KARAOKE
            karaokeDurationMs = durationMs.coerceAtLeast(0L)
            karaokeProgressMs = 0L

            if (currentState != State.SPEAKING) {
                enterSpeakingMode()
            } else {
                cancelStuckListeningWatchdog()
            }

            val detection = EmotionKeywordDetector.detect(text)
            utteranceExpression = detection.expression
            Log.d(
                TAG,
                "karaoke start: len=${text.length} duration=${karaokeDurationMs}ms " +
                    "expr=${detection.expression} keyword=${detection.keyword}"
            )
            esperSphereView?.setExpression(detection.expression)

            fun showFullCaption(fromLayoutRebuild: Boolean) {
                prepareCaptionPages(text, resetIndex = !fromLayoutRebuild)
                if (captionPages.isEmpty()) {
                    clearCaptionStack()
                    return
                }
                val index = if (karaokeDurationMs > 0L && karaokeProgressMs > 0L) {
                    resolveCaptionPageIndex(karaokeProgressMs, karaokeDurationMs)
                } else {
                    captionPageIndex
                }
                if (fromLayoutRebuild) {
                    dissolveCaptionPage(index, animate = false)
                } else {
                    presentCaptionFirstPage(index)
                }
            }

            // Rebuild pages after TextView paint/width are ready for accurate 3-line paging.
            // Layout rebuild must not snap back to page 0 if TTS already advanced.
            if (capsuleTextView?.width ?: 0 > 0) {
                showFullCaption(fromLayoutRebuild = false)
            } else if (captionOnly) {
                prepareCaptionPages(text, resetIndex = true)
                capsuleTextView?.alpha = 0f
                capsuleTextView?.post { showFullCaption(fromLayoutRebuild = false) }
            } else {
                prepareCaptionPages(text, resetIndex = true)
                dissolveCaptionPage(0, animate = false)
                capsuleTextView?.post { showFullCaption(fromLayoutRebuild = true) }
            }

            if (capsuleView?.visibility != View.VISIBLE) showCapsuleWithCard()
        }
    }

    private fun updateKaraokeProgress(currentMs: Long, totalMs: Long) {
        if (captionPages.isEmpty()) return
        // Accept progress while caption is already up (static assistant text or karaoke).
        if (displayMode != DisplayMode.KARAOKE && displayMode != DisplayMode.STATIC_TEXT) return

        handler.post {
            karaokeProgressMs = currentMs.coerceAtLeast(0L)
            if (totalMs > 0L) karaokeDurationMs = totalMs
            if (karaokeDurationMs <= 0L) return@post
            if (captionPageTotalWeight <= 0f || captionPages.isEmpty()) return@post
            // First page is still waiting to materialize — keep the clock, don't snap over it.
            if (captionEntranceRunnable != null) return@post

            displayMode = DisplayMode.KARAOKE
            syncCaptionPageToProgress(animate = true)
        }
    }
    
    
    private fun scheduleAutoHide() {
        autoHideRunnable?.let { handler.removeCallbacks(it) }
        autoHideRunnable = Runnable {
            if (displayMode == DisplayMode.KARAOKE) return@Runnable
            hideAll {
                currentText = ""
                clearCaptionStack()
            }
        }
        handler.postDelayed(autoHideRunnable!!, 6000)
    }

    private var streamingBuffer = StringBuilder()
    
    private fun appendText(text: String) {
        handler.post {
            if (displayMode == DisplayMode.KARAOKE) return@post
            autoHideRunnable?.let { handler.removeCallbacks(it) }
            displayMode = DisplayMode.STREAMING
            
            if (currentState != State.SPEAKING) {
                enterSpeakingMode()
            }
            
            streamingBuffer.append(text)
            val fullText = streamingBuffer.toString()
            if (!hasSemanticText(fullText)) {
                streamingBuffer.clear()
                return@post
            }

            val now = System.currentTimeMillis()
            val forceUiUpdate = text.withIndex().any { (index, char) ->
                char == '\n' || isSentenceBoundary(text, index, char)
            }
            if (!forceUiUpdate && performanceProfile.streamingUiThrottleMs > 0L) {
                if (now - lastStreamingUiUpdateMs < performanceProfile.streamingUiThrottleMs) {
                    return@post
                }
            }

            val previousPageIndex = captionPageIndex
            val previousPageText = captionPages.getOrNull(previousPageIndex)
            prepareCaptionPages(fullText)
            val target = captionPages.lastIndex.coerceAtLeast(0)
            if (captionPages.isNotEmpty()) {
                val pageChanged =
                    target != previousPageIndex || captionPages.getOrNull(target) != previousPageText
                dissolveCaptionPage(target, animate = forceUiUpdate && pageChanged)
            } else {
                renderSingleCaption(fullText)
            }
            lastStreamingUiUpdateMs = now
            
            // Per-chunk change dedup lives in setExpression — no per-chunk log here.
            val detection = EmotionKeywordDetector.detect(fullText)
            utteranceExpression = detection.expression
            esperSphereView?.setExpression(detection.expression)
            
            if (capsuleView?.visibility != View.VISIBLE) showCapsuleWithCard()
        }
    }

    private fun hideOverlay() {
        handler.post {
            cancelStuckListeningWatchdog()
            stopCursorBlink()
            karaokeRunnable?.let { handler.removeCallbacks(it) }
            autoHideRunnable?.let { handler.removeCallbacks(it) }
            scrollRunnable?.let { handler.removeCallbacks(it) }
            stackSwitchAnimator?.cancel()
            stackSwitchAnimator = null
            displayMode = DisplayMode.NONE
            lastStreamingUiUpdateMs = 0L
            streamingBuffer.clear()
            captionFullText = ""
            captionPages = emptyList()
            captionPageIndex = 0
            captionPageWeights = emptyList()
            captionPageTotalWeight = 1f
            karaokeDurationMs = 0L
            karaokeProgressMs = 0L
            hideAll {
                currentText = ""
                clearCaptionStack()
            }
        }
    }

    private fun clearText() {
        handler.post {
            stopCursorBlink()
            karaokeRunnable?.let { handler.removeCallbacks(it) }
            autoHideRunnable?.let { handler.removeCallbacks(it) }
            stackSwitchAnimator?.cancel()
            stackSwitchAnimator = null
            displayMode = DisplayMode.NONE
            lastStreamingUiUpdateMs = 0L
            streamingBuffer.clear()
            currentText = ""
            captionFullText = ""
            captionPages = emptyList()
            captionPageIndex = 0
            captionPageWeights = emptyList()
            captionPageTotalWeight = 1f
            karaokeDurationMs = 0L
            karaokeProgressMs = 0L
            clearCaptionStack()
            hideAll()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { applicationContext.unregisterComponentCallbacks(configurationCallbacks) }
        cancelPendingSphereEntrance()
        stackSwitchAnimator?.cancel()
        stackSwitchAnimator = null
        sphereEntranceAnimator?.cancel()
        sphereEntranceAnimator = null
        sphereExitAnimator?.cancel()
        sphereExitAnimator = null
        timerBreathingAnimator?.cancel()
        timerBreathingAnimator = null
        esperSphereView?.destroy()
        handler.removeCallbacksAndMessages(null)
        try {
            capsuleView?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove floating window", e)
        }
        instance = null
    }

    companion object {
        private const val TAG = "FloatingWindowService"
        @Volatile private var instance: FloatingWindowService? = null
        private const val CURSOR_CHAR = "\u2758"

        /**
         * STT bubble must wait until the caption-only plate and bottom veil have
         * left. 0 when nothing of ours is on screen.
         */
        fun ttsCaptionHandoffRemainingMs(): Long {
            val svc = instance ?: return 0L
            if (!svc.captionOnly) return 0L
            val card = svc.textCardView
            val falloff = svc.captionFalloffView
            val cardUp = card != null && card.visibility == View.VISIBLE && card.alpha > 0.02f
            val veilUp = falloff != null && falloff.visibility == View.VISIBLE && falloff.alpha > 0.02f
            if (!cardUp && !veilUp) return 0L
            val a = maxOf(
                if (cardUp) card!!.alpha else 0f,
                if (veilUp) falloff!!.alpha else 0f,
            ).coerceIn(0f, 1f)
            val base = (CAPTION_ONLY_EXIT_MS * a).toLong().coerceIn(40L, CAPTION_ONLY_EXIT_MS)
            val lag = if (veilUp && (falloff?.alpha ?: 0f) >= 0.95f) {
                CAPTION_ONLY_VEIL_EXIT_LAG_MS
            } else {
                0L
            }
            return base + lag
        }

        /**
         * WakeRippleView (do not change that file): WAKE_DURATION=2000ms,
         * DecelerateInterpolator(2). Approximate wall-clock vs fade curves:
         *   ~210ms  fadeRing peak (progress≈0.2) — burst clearly on screen
         *   ~330ms  fadeCircle full (progress≈0.3)
         *   ~900ms  fadeCircle starts falling (progress≈0.7)
         * Wait only until the burst is visible, then fade the sphere in (no scale).
         */
        /** One emotion sparkle burst start→end (stagger included). */
        private const val EMOTION_BURST_DURATION_MS = 800L
        /** Collapse routine z-order reasserts within this window to one hard restack. */
        private const val HARD_RESTACK_MIN_INTERVAL_MS = 250L
        private const val WAKE_SETTLE_BEFORE_SPHERE_MS = 200L
        private const val SPHERE_ENTRANCE_FADE_MS = 400L
        /** Unhurried land; travel is only ~5dp — feel comes from ease, not height. */
        private const val SPHERE_ENTRANCE_DROP_MS = 720L
        /** Session-end only (not continuous-dialogue turns): in-place shrink, then fade. */
        private const val SPHERE_EXIT_SCALE = 0.88f
        private const val SPHERE_EXIT_SCALE_MS = 160L
        private const val SPHERE_EXIT_FADE_MS = 300L
        private const val SPHERE_EXIT_FADE_DELAY_MS = 70L
        /** Chip eases in after the ball starts dropping — slower, not glued to the sphere. */
        private const val STATUS_BADGE_FADE_MS = 480L
        private const val STATUS_BADGE_FADE_DELAY_MS = 90L
        /**
         * Stuck at STT/Listening (session never advanced): force-dismiss after this lease.
         * Nobody continuously speaks for 2 minutes in a single Listening phase.
         */
        private const val STUCK_LISTENING_TIMEOUT_MS = 2L * 60L * 1000L
        /**
         * Sphere caption: CJK glyphs per line (1 glyph ≈ 1 em). Same idea as the FAB
         * transcript bubble ([QuickWakeFabService] STT wrap at 11) — fewer glyphs, larger
         * type, more pages instead of a wall of small text. 12 sits between that bubble
         * and the quieter caption-only plate.
         */
        private const val SPHERE_CJK_PER_LINE = 12f
        /** Caption-only (Quick Wake) turns: slightly more glyphs, slightly smaller type. */
        private const val CAPTION_ONLY_CJK_PER_LINE = 13.5f
        /** Voice-button TTS plate vs the sphere caption. Clamped to a 20dp screen inset. */
        private const val CAPTION_ONLY_WIDTH_SCALE = 1.12f
        /** First page: plate + words rise in as one card. */
        private const val CAPTION_ONLY_ENTRANCE_MS = 640L
        private const val CAPTION_ONLY_ENTRANCE_SCALE = 0.97f
        /** Caption-only: veil reaches full before the plate starts. */
        private const val CAPTION_ONLY_VEIL_LEAD_MS = 380L
        /** Caption-only hide: plate starts leaving, then the veil follows. */
        private const val CAPTION_ONLY_VEIL_EXIT_LAG_MS = 180L
        /** Later pages / SSE sentence blocks: short overlapping page-turn. */
        private const val CAPTION_PAGE_CROSSFADE_OUT_MS = 220L
        private const val CAPTION_PAGE_CROSSFADE_IN_MS = 260L
        /** Caption-only plate leaves first; the veil follows after [CAPTION_ONLY_VEIL_EXIT_LAG_MS]. */
        private const val CAPTION_ONLY_EXIT_MS = 520L

        /**
         * Caption paging (no word-level TTS marks — clock + speech-weight only):
         * - Lead ≈ half dissolve so the turn lands near the next page's first words.
         * - Trailing silence trim: progressive MP3/URL often pads the reported duration.
         */
        private const val CAPTION_PAGE_TURN_LEAD_MS = 220L
        private const val CAPTION_TRAILING_SILENCE_FRAC = 0.025f
        private const val CAPTION_TRAILING_SILENCE_MIN_MS = 120L
        private const val CAPTION_TRAILING_SILENCE_MAX_MS = 600L
        
        const val ACTION_SHOW_LISTENING = "com.example.ava.SHOW_LISTENING"
        const val ACTION_SHOW_PROCESSING = "com.example.ava.SHOW_PROCESSING"
        const val ACTION_SHOW_ASSISTANT_TEXT = "com.example.ava.SHOW_ASSISTANT_TEXT"
        const val ACTION_APPEND_TEXT = "com.example.ava.APPEND_TEXT"
        const val ACTION_FINISH_STREAMING = "com.example.ava.FINISH_STREAMING"
        const val ACTION_SHOW_KARAOKE = "com.example.ava.SHOW_KARAOKE"
        const val ACTION_UPDATE_KARAOKE_PROGRESS = "com.example.ava.UPDATE_KARAOKE_PROGRESS"
        const val ACTION_HIDE = "com.example.ava.HIDE_FLOATING"
        const val ACTION_PREWARM = "com.example.ava.PREWARM_FLOATING"
        const val ACTION_CLEAR = "com.example.ava.CLEAR_FLOATING"
        const val ACTION_SHOW_TIMER_FINISHED = "com.example.ava.SHOW_TIMER_FINISHED"
        const val ACTION_HIDE_TIMER_FINISHED = "com.example.ava.HIDE_TIMER_FINISHED"
        const val ACTION_TIMER_DISMISSED = "com.example.ava.TIMER_DISMISSED"
        const val EXTRA_TEXT = "text"
        const val EXTRA_DURATION = "duration"
        const val EXTRA_CURRENT_MS = "current_ms"
        const val EXTRA_TOTAL_MS = "total_ms"

        fun bringToFrontIfVisible(force: Boolean = false, raiseMic: Boolean = true) {
            val svc = instance ?: return
            val view = svc.capsuleView ?: return
            if (!view.isAttachedToWindow) return
            val browserUp = AiBrowserService.isShowing()
            // GONE still occupies the overlay tier. Skip it for routine
            // screensaver reasserts, but climb the AI page so the first
            // GONE→VISIBLE paint is already above the WebView.
            if (view.visibility != View.VISIBLE && !browserUp) return
            val generation = AiBrowserService.windowGeneration
            val needForce = force ||
                (browserUp && svc.restackedForBrowserGeneration != generation)
            svc.bringToFrontNow(needForce, raiseMic)
            svc.restackedForBrowserGeneration = generation
        }

        /**
         * The Esper / caption host is in WM (VISIBLE or GONE). GONE still sits
         * on top of the same overlay tier — the mic must climb it. No window
         * means hide/reassert must not spawn one just to put it away.
         */
        fun isWindowAttached(): Boolean {
            val view = instance?.capsuleView ?: return false
            return view.isAttachedToWindow
        }

        /** Caption / sphere host is painted — not merely GONE in WM. */
        fun isOverlayShowing(): Boolean {
            val view = instance?.capsuleView ?: return false
            return view.isAttachedToWindow && view.visibility == View.VISIBLE
        }

        fun hasOverlayPermission(context: Context): Boolean {
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || android.provider.Settings.canDrawOverlays(context)
        }

        /** Teaching tip owns the utterance: this window stays down for it. */
        private fun shouldYieldSession(): Boolean =
            SatelliteSetupTipOverlayService.shouldYieldVoiceOverlays()

        fun showListening(context: Context) {
            if (!hasOverlayPermission(context)) return
            if (shouldYieldSession()) return
            context.startService(Intent(context, FloatingWindowService::class.java).apply {
                action = ACTION_SHOW_LISTENING
            })
        }

        fun showProcessing(context: Context) {
            if (!hasOverlayPermission(context)) return
            if (shouldYieldSession()) return
            context.startService(Intent(context, FloatingWindowService::class.java).apply {
                action = ACTION_SHOW_PROCESSING
            })
        }

        fun showAssistantText(context: Context, text: String) {
            if (!hasOverlayPermission(context)) return
            if (shouldYieldSession()) return
            val intent = Intent(context, FloatingWindowService::class.java).apply {
                action = ACTION_SHOW_ASSISTANT_TEXT
                putExtra(EXTRA_TEXT, text)
            }
            context.startService(intent)
        }

        fun appendText(context: Context, text: String) {
            if (!hasOverlayPermission(context)) return
            if (shouldYieldSession()) return
            val intent = Intent(context, FloatingWindowService::class.java).apply {
                action = ACTION_APPEND_TEXT
                putExtra(EXTRA_TEXT, text)
            }
            context.startService(intent)
        }

        fun finishStreaming(context: Context) {
            if (!hasOverlayPermission(context)) return
            if (shouldYieldSession()) return
            context.startService(Intent(context, FloatingWindowService::class.java).apply {
                action = ACTION_FINISH_STREAMING
            })
        }

        fun showKaraokeText(context: Context, text: String, durationMs: Long) {
            if (!hasOverlayPermission(context)) return
            // Teaching tip (e.g. no_intent) owns this utterance — do not karaoke the stock phrase.
            if (shouldYieldSession()) return
            val intent = Intent(context, FloatingWindowService::class.java).apply {
                action = ACTION_SHOW_KARAOKE
                putExtra(EXTRA_TEXT, text)
                putExtra(EXTRA_DURATION, durationMs)
            }
            context.startService(intent)
        }
        
        fun updateKaraokeProgress(context: Context, currentMs: Long, totalMs: Long) {
            if (!hasOverlayPermission(context)) return
            if (shouldYieldSession()) return
            val intent = Intent(context, FloatingWindowService::class.java).apply {
                action = ACTION_UPDATE_KARAOKE_PROGRESS
                putExtra(EXTRA_CURRENT_MS, currentMs)
                putExtra(EXTRA_TOTAL_MS, totalMs)
            }
            context.startService(intent)
        }
        

        /**
         * Button is available and captions are on. Create the GONE plate now so the
         * first reply does not addView a fullscreen window on press.
         */
        fun prewarm(context: Context) {
            if (!hasOverlayPermission(context)) return
            context.startService(Intent(context, FloatingWindowService::class.java).apply {
                action = ACTION_PREWARM
            })
        }

        /** Captions off: the parked plate is really gone, not just GONE. */
        fun release(context: Context) {
            if (instance == null) return
            context.stopService(Intent(context, FloatingWindowService::class.java))
        }

        fun hide(context: Context) {
            // Hide must not start the service. A first hide after boot used to
            // addView a GONE fullscreen plate and then raise the mic — one blink
            // with no floating layer on screen.
            if (instance == null) return
            if (!hasOverlayPermission(context)) return
            val intent = Intent(context, FloatingWindowService::class.java).apply {
                action = ACTION_HIDE
            }
            context.startService(intent)
        }

        fun clear(context: Context) {
            if (instance == null) return
            if (!hasOverlayPermission(context)) return
            val intent = Intent(context, FloatingWindowService::class.java).apply {
                action = ACTION_CLEAR
            }
            context.startService(intent)
        }
        
        fun showTimerFinished(context: Context) {
            if (!hasOverlayPermission(context)) return
            context.startService(Intent(context, FloatingWindowService::class.java).apply {
                action = ACTION_SHOW_TIMER_FINISHED
            })
        }
        
        fun hideTimerFinished(context: Context) {
            if (instance == null) return
            if (!hasOverlayPermission(context)) return
            context.startService(Intent(context, FloatingWindowService::class.java).apply {
                action = ACTION_HIDE_TIMER_FINISHED
            })
        }
    }
}

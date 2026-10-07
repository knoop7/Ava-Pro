package com.example.ava.services

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.PaintDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import android.util.DisplayMetrics
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.ava.notifications.FontAwesomeHelper
import com.example.ava.notifications.NotificationScenes
import com.example.ava.notifications.NotificationScene
import com.example.ava.notifications.SceneReset
import com.example.ava.settings.NotificationSettings
import com.example.ava.settings.NotificationDisplayStyle
import com.example.ava.settings.NotificationSettingsStore
import com.example.ava.settings.notificationSettingsStore
import com.example.ava.ui.glass.LiquidGlass
import com.example.ava.ui.glass.LiquidGlassDrawable
import com.example.ava.utils.BlurCompat
import android.media.RingtoneManager
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch


class NotificationOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var windowParams: WindowManager.LayoutParams? = null
    
    
    private var backgroundView: View? = null
    private var beamView: View? = null
    private var scanLine: View? = null  
    private var coreGlowView: View? = null
    private var techRingView: View? = null
    private var iconView: TextView? = null
    private var titleView: TextView? = null
    private var descView: TextView? = null
    private var dividerView: View? = null
    private var dotView: View? = null
    
    
    private var auroraBlob1: View? = null
    private var auroraBlob2: View? = null
    private var auroraBlob3: View? = null
    private var auroraBlob4: View? = null
    
    private val handler = Handler(Looper.getMainLooper())
    private var autoHideRunnable: Runnable? = null
    private var currentScene: NotificationScene? = null
    /** Auto-hide override of the scene being shown (e.g. 3s preview); null = user setting. */
    private var lastAutoHideOverrideMs: Long? = null
    
    /** Content column width available to title/desc text (set in createOverlayView). */
    private var textAvailWidthPx = 0
    /** Screen-derived title size (sp); fitTitleTextSizeSp() steps down from here. */
    private var titleMaxSp = 35f
    private var techRingAnimator: ObjectAnimator? = null
    private var isShowingAnimation = false
    
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    
    
    private val notificationSettingsStore by lazy { NotificationSettingsStore(applicationContext.notificationSettingsStore) }

    
    private var iconSunRiseAnimator: ObjectAnimator? = null
    private var iconShadowAnimator: ValueAnimator? = null

    /**
     * Infinite glow/pulse animators owned by the current overlay view. Held so
     * [cancelOverlayAnimators] can stop them: [createOverlayView] runs again on every
     * rotation, and an orphaned INFINITE animator keeps ticking on the detached view.
     */
    private var coreGlowAnimator: ValueAnimator? = null
    private var dotPulseAnimator: ObjectAnimator? = null

    
    private var auroraAnimator1: Animator? = null
    private var auroraAnimator2: Animator? = null
    private var auroraAnimator3: Animator? = null
    private var auroraAnimator4: Animator? = null

    private val bannerOverlay by lazy { NotificationBannerOverlay(this) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        LiquidGlass.ensureLoaded(this)
        createOverlayView()
        bannerOverlay.ensureAttached(windowManager!!)

        initializeDefaultColors()


        notificationSettingsStore.sceneDisplayDuration.onEach { duration ->

            if (overlayView?.visibility == View.VISIBLE ||
                bannerOverlay.root?.visibility == View.VISIBLE
            ) {
                scheduleAutoHide(lastAutoHideOverrideMs)
            }
        }.launchIn(serviceScope)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)

        // Capture before tearing views down: only restore what was actually on screen.
        val wasVisible = overlayView?.visibility == View.VISIBLE ||
            bannerOverlay.root?.visibility == View.VISIBLE ||
            isShowingAnimation

        overlayView?.let {
            windowManager?.removeView(it)
            overlayView = null
        }
        createOverlayView()
        bannerOverlay.detach(windowManager)
        windowManager?.let { bannerOverlay.ensureAttached(it) }
        if (!wasVisible) return
        currentScene?.let { scene ->
            serviceScope.launch {
                val settings = notificationSettingsStore.get()
                handler.post {
                    presentScene(scene, settings, lastAutoHideOverrideMs, playSound = false)
                }
            }
        }
    }

    
    private fun initializeDefaultColors() {
        
        NotificationScenes.loadFromAssets(this)

        val defaultScene = NotificationScenes.getSceneById("morning")
        if (defaultScene != null) {
            
            updateContent(defaultScene)
            updateColors(defaultScene)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createOverlayView() {
        // Rotation rebuilds the overlay; stop the previous view's animators first or
        // each rotation leaves another set running against a detached view.
        cancelOverlayAnimators()

        val displayMetrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        val display = windowManager?.defaultDisplay
        display?.getRealMetrics(displayMetrics)
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val density = displayMetrics.density

        
        val isPortraitOrSquare = screenHeight >= screenWidth  
        val minScreenSize = minOf(screenWidth, screenHeight)  
        
        
        
        val iconSize = (screenWidth * 0.05f / density).coerceIn(40f, 64f)
        
        val titleSize = (screenWidth * 0.05f / density).coerceIn(35f, 72f)
        
        val descSize = (screenWidth * 0.018f / density).coerceIn(16f, 26f)
        
        
        val rootContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#02040a"))
            clipChildren = false  
            clipToPadding = false
            visibility = View.GONE
        }
        
        
        
        backgroundView = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            
            
            val gradient = GradientDrawable()
            gradient.gradientType = GradientDrawable.RADIAL_GRADIENT
            gradient.setGradientCenter(0.5f, 1.0f)  
            gradient.gradientRadius = screenHeight.toFloat()
            
            gradient.colors = intArrayOf(
                Color.parseColor("#66f59e0b"),  
                Color.parseColor("#33d97706"),  
                Color.TRANSPARENT               
            )
            background = gradient
        }
        
        
        auroraBlob1 = null
        auroraBlob2 = null
        auroraBlob3 = null
        auroraBlob4 = null

        rootContainer.addView(backgroundView)
        
        
        
        beamView = null
        
        
        scanLine = null
        
        
        
        val maxCardWidth = (1600 * density).toInt()
        val cardWidth: Int
        val cardHeight: Int
        val cardCornerRadius: Float
        
        if (isPortraitOrSquare) {
            
            cardWidth = screenWidth
            cardHeight = screenHeight
            cardCornerRadius = 0f  
        } else {
            
            cardWidth = ((screenWidth * 0.95f).toInt()).coerceAtMost(maxCardWidth)
            cardHeight = (screenHeight * 0.92f).toInt()
            cardCornerRadius = 16 * density  
        }

        val cardContainer = FrameLayout(this).apply {
            val lp = FrameLayout.LayoutParams(cardWidth, cardHeight)
            lp.gravity = Gravity.CENTER
            lp.topMargin = (5 * density).toInt()  
            layoutParams = lp

            
            clipChildren = false
            clipToPadding = false

            
            if (LiquidGlass.enabled) {
                // HUD card as a glass slab; the window blurs the wallpaper behind it.
                background = LiquidGlassDrawable(
                    cornerRadiusPx = cardCornerRadius,
                    tint = Color.argb(150, 5, 10, 25),
                    windowBacked = true,
                ).also { it.setDensity(density) }
            } else {
                val cardBg = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(
                        Color.argb(180, 5, 10, 25),
                        Color.argb(140, 5, 10, 25),
                        Color.argb(80, 5, 10, 25)
                    )
                )
                cardBg.cornerRadius = cardCornerRadius
                if (!isPortraitOrSquare) {
                    cardBg.setStroke((1 * density).toInt(), Color.parseColor("#19FFFFFF"))
                }
                background = cardBg
            }
        }
        
        
        if (!isPortraitOrSquare) {
            val cornerSize = (20 * density).toInt()
            val cornerThickness = (2 * density).toInt()
            val cornerMargin = (16 * density).toInt()
            
            
            addCornerMarker(cardContainer, Gravity.TOP or Gravity.START, cornerSize, cornerThickness, cornerMargin, density)
            
            addCornerMarker(cardContainer, Gravity.TOP or Gravity.END, cornerSize, cornerThickness, cornerMargin, density)
            
            addCornerMarker(cardContainer, Gravity.BOTTOM or Gravity.START, cornerSize, cornerThickness, cornerMargin, density)
            
            addCornerMarker(cardContainer, Gravity.BOTTOM or Gravity.END, cornerSize, cornerThickness, cornerMargin, density)
        }
        
        
        
        val maxContentWidth = (896 * density).toInt()
        val basePadding = if (isPortraitOrSquare) (16 * density).toInt() else (32 * density).toInt()
        // Cap the content column on large screens (mockup max-w); card can be wider.
        val contentWidth = minOf(cardWidth, maxContentWidth)
        textAvailWidthPx = contentWidth - 2 * basePadding
        titleMaxSp = titleSize
        val contentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER  
            clipChildren = false  
            clipToPadding = false
            setPadding(basePadding, basePadding, basePadding, basePadding)
            val lp = FrameLayout.LayoutParams(
                contentWidth,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            lp.gravity = Gravity.CENTER
            layoutParams = lp
        }

        
        
        
        val iconBoxSize = if (isPortraitOrSquare) {
            
            (screenWidth * 0.25f).toInt().coerceIn((90 * density).toInt(), (120 * density).toInt())
        } else {
            
            (screenWidth * 0.10f).toInt().coerceIn((80 * density).toInt(), (120 * density).toInt())
        }
        
        val iconBottomMargin = if (isPortraitOrSquare) (6 * density).toInt() else (24 * density).toInt()
        val iconContainer = FrameLayout(this).apply {
            val lp = LinearLayout.LayoutParams(iconBoxSize, iconBoxSize)
            lp.gravity = Gravity.CENTER
            lp.bottomMargin = iconBottomMargin
            layoutParams = lp
            clipChildren = false  
            clipToPadding = false
        }
        
        
        
        
        coreGlowView = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            val gradient = GradientDrawable()
            gradient.shape = GradientDrawable.OVAL
            gradient.setGradientCenter(0.5f, 0.5f)
            gradient.gradientType = GradientDrawable.RADIAL_GRADIENT
            gradient.gradientRadius = iconBoxSize / 2f
            
            gradient.colors = intArrayOf(
                Color.parseColor("#66fbbf24"),  
                Color.TRANSPARENT
            )
            background = gradient
            alpha = 0.5f  
            
            
            val coreView = this
            coreGlowAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 3000  
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { animation ->
                    val value = animation.animatedValue as Float
                    
                    
                    val (newAlpha, newScale) = if (value <= 0.5f) {
                        val t = value / 0.5f
                        Pair(0.5f + 0.5f * t, 1f + 0.2f * t)
                    } else {
                        val t = (value - 0.5f) / 0.5f
                        Pair(1f - 0.5f * t, 1.2f - 0.2f * t)
                    }
                    coreView.alpha = newAlpha
                    coreView.scaleX = newScale
                    coreView.scaleY = newScale
                }
                start()
            }
        }
        iconContainer.addView(coreGlowView)
        
        

        
        
        val faTypeface = FontAwesomeHelper.loadFont(this)
        iconView = TextView(this).apply {
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            lp.gravity = Gravity.CENTER
            layoutParams = lp
            textSize = iconSize
            gravity = Gravity.CENTER
            typeface = faTypeface  
            setTextColor(Color.parseColor("#fde68a"))  
            text = FontAwesomeHelper.getIconChar("fa-sun")  
            setShadowLayer(25 * density, 0f, 0f, Color.parseColor("#E6fde68a"))
        }
        iconContainer.addView(iconView)

        
        startIconSunRiseAnimation()

        
        
        dotView = View(this).apply {
            val dotSize = (10 * density).toInt()  
            val lp = FrameLayout.LayoutParams(dotSize, dotSize)
            lp.gravity = Gravity.TOP or Gravity.END
            lp.topMargin = (8 * density).toInt()  
            lp.rightMargin = (8 * density).toInt()  
            layoutParams = lp
            
            val dotBg = GradientDrawable()
            dotBg.shape = GradientDrawable.OVAL
            dotBg.setColor(Color.parseColor("#fcd34d"))
            background = dotBg
            
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                outlineAmbientShadowColor = Color.parseColor("#fcd34d")
            }
            elevation = 15 * density
            
            
            dotPulseAnimator = ObjectAnimator.ofFloat(this, "alpha", 1f, 0.5f, 1f).apply {
                duration = 2000
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
        }
        iconContainer.addView(dotView)
        
        contentContainer.addView(iconContainer)
        
        
        
        
        dividerView = View(this).apply {
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (1 * density).toInt()
            )
            
            val marginVh = if (isPortraitOrSquare) (screenHeight * 0.01f).toInt() else (screenHeight * 0.02f).toInt()
            lp.topMargin = marginVh
            lp.bottomMargin = marginVh
            layoutParams = lp
            alpha = 0.8f

            
            val dividerGradient = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.TRANSPARENT, Color.parseColor("#CCfbbf24"), Color.TRANSPARENT)
            )
            background = dividerGradient

            
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                outlineAmbientShadowColor = Color.parseColor("#99fbbf24") 
            }
        }
        contentContainer.addView(dividerView)
        
        
        val titleContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.gravity = Gravity.CENTER
            
            val titleMargin = if (isPortraitOrSquare) (4 * density).toInt() else (8 * density).toInt()
            lp.topMargin = titleMargin
            lp.bottomMargin = titleMargin
            layoutParams = lp
        }

        
        
        titleView = TextView(this).apply {
            textSize = titleSize
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            text = ""
            // Long titles: fitTitleTextSize() steps the size down first; if the floor
            // still overflows, cap at 2 lines and ellipsize instead of pushing the
            // desc row / HA badge out of the card.
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            
            
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.gravity = Gravity.CENTER
            
            lp.bottomMargin = if (isPortraitOrSquare) (4 * density).toInt() else (8 * density).toInt()
            layoutParams = lp
        }
        titleContainer.addView(titleView)

        
        
        val descOuterContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            clipChildren = false  
            clipToPadding = false
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.gravity = Gravity.CENTER
            
            lp.topMargin = if (isPortraitOrSquare) (1.3f * density).toInt() else (3.3f * density).toInt()
            layoutParams = lp
        }
        
        
        // desc + subDesc render as one spannable line; overflow scrolls via the
        // native TextView marquee (same principle as the lyrics CautiousMarquee:
        // framework only animates when the text is wider than the view, short
        // lines stay static and centered). Fading edges stand in for the lyric
        // DstIn dissolve — they draw fine on this software-rendered overlay.
        descView = TextView(this).apply {
            textSize = descSize
            setTextColor(Color.parseColor("#e5e7eb"))  
            text = ""
            setBackgroundColor(Color.TRANSPARENT)
            letterSpacing = 0.05f  
            gravity = Gravity.CENTER
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            // Overlay window is FLAG_NOT_FOCUSABLE — marquee needs selected state.
            isSelected = true
            isHorizontalFadingEdgeEnabled = true
            setFadingEdgeLength((24 * density).toInt())
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.gravity = Gravity.CENTER
            layoutParams = lp
        }
        descOuterContainer.addView(descView)
        
        
        
        
        val haContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL  
            clipChildren = false  
            clipToPadding = false
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.gravity = Gravity.CENTER
            lp.topMargin = (16 * density).toInt()  
            layoutParams = lp
        }

        
        
        val leftLine = View(this).apply {
            val lp = LinearLayout.LayoutParams((32 * density).toInt(), (1 * density).toInt())
            lp.rightMargin = (16 * density).toInt()  
            layoutParams = lp
            setBackgroundColor(Color.parseColor("#33FFFFFF"))  
        }
        haContainer.addView(leftLine)

        
        
        val logoSize = ((screenHeight * 0.03f).toInt().coerceIn((28 * density).toInt(), (40 * density).toInt()))
        val haLogoContainer = FrameLayout(this).apply {
            val lp = LinearLayout.LayoutParams(logoSize, logoSize)
            lp.rightMargin = (8 * density).toInt()  
            layoutParams = lp

            val logoBg = GradientDrawable()
            logoBg.shape = GradientDrawable.OVAL
            logoBg.setColor(Color.parseColor("#0DFFFFFF"))  
            logoBg.setStroke((1 * density).toInt(), Color.parseColor("#1AFFFFFF"))  
            background = logoBg
            
            
            elevation = 100 * density
        }

        
        
        val haIcon = ImageView(this).apply {
            val iconSize = (logoSize * 0.62f).toInt()  
            val lp = FrameLayout.LayoutParams(iconSize, iconSize)
            lp.gravity = Gravity.CENTER
            layoutParams = lp
            try {
                assets.open("ha_logo.png").use { inputStream ->
                    setImageBitmap(android.graphics.BitmapFactory.decodeStream(inputStream))
                }
            } catch (e: Exception) { }
            scaleType = ImageView.ScaleType.FIT_CENTER
            
            translationY = (-logoSize * 0.02f)
        }
        haLogoContainer.addView(haIcon)
        haContainer.addView(haLogoContainer)

        
        val haTextContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            
            layoutParams = lp
        }

        
        
        
        val haTitle = TextView(this).apply {
            text = "Home Assistant"
            
            textSize = (screenWidth * 0.012f / density).coerceIn(11.2f, 16f)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.025f  
        }
        haTextContainer.addView(haTitle)

        
        
        
        val haSubtitle = TextView(this).apply {
            text = "SYSTEM NOTIFICATION"
            
            textSize = (screenWidth * 0.008f / density).coerceIn(6.4f, 10.4f)
            setTextColor(Color.parseColor("#99bfdbfe")) 
            letterSpacing = 0.05f  
        }
        haTextContainer.addView(haSubtitle)

        haContainer.addView(haTextContainer)

        
        
        val rightLine = View(this).apply {
            val lp = LinearLayout.LayoutParams((32 * density).toInt(), (1 * density).toInt())
            lp.leftMargin = (16 * density).toInt()  
            layoutParams = lp
            setBackgroundColor(Color.parseColor("#33FFFFFF"))  
        }
        haContainer.addView(rightLine)
        
        
        descOuterContainer.addView(haContainer)

        
        
        
        
        
        titleContainer.addView(descOuterContainer)

        
        contentContainer.addView(titleContainer)
        
        cardContainer.addView(contentContainer)
        rootContainer.addView(cardContainer)
        
        overlayView = rootContainer
        // Large setShadowLayer on a hardware canvas crashes the RenderThread (RenderScript
        // ScriptIntrinsicBlur) on old APIs; render the whole overlay in software there.
        BlurCompat.forceSoftwareLayerIfNeeded(rootContainer)

        
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
                    WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION or
                    WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            // The HUD card covers ~92% of the screen, so a whole-window backdrop blur reads
            // as the card's own glass rather than a global dim.
            LiquidGlass.applyWindowBlur(this, density)
            OverlayOrientation.apply(this)
        }
        windowParams = params
        
        try {
            windowManager?.addView(overlayView, params)
            overlayView?.visibility = View.GONE
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create notification overlay", e)
        }
    }
    
    
    /** Stops every animator bound to the current overlay view and drops the references. */
    private fun cancelOverlayAnimators() {
        listOfNotNull(
            techRingAnimator,
            iconSunRiseAnimator,
            iconShadowAnimator,
            coreGlowAnimator,
            dotPulseAnimator,
            auroraAnimator1,
            auroraAnimator2,
            auroraAnimator3,
            auroraAnimator4,
        ).forEach { runCatching { it.cancel() } }
        techRingAnimator = null
        iconSunRiseAnimator = null
        iconShadowAnimator = null
        coreGlowAnimator = null
        dotPulseAnimator = null
        auroraAnimator1 = null
        auroraAnimator2 = null
        auroraAnimator3 = null
        auroraAnimator4 = null
    }

    private fun startIconSunRiseAnimation() {
        
        iconSunRiseAnimator = ObjectAnimator.ofFloat(iconView, "scaleX", 1f, 1.1f, 1f).apply {
            duration = 3000  
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            
            addUpdateListener {
                iconView?.scaleY = iconView?.scaleX ?: 1f
            }
            start()
        }

        
        iconShadowAnimator = ValueAnimator.ofFloat(20f, 35f, 20f).apply {
            duration = 3000  
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { animation ->
                val shadowRadius = animation.animatedValue as Float
                val currentColor = iconView?.currentTextColor ?: Color.parseColor("#fde68a")
                iconView?.setShadowLayer(shadowRadius, 0f, 0f, adjustAlpha(currentColor, 0.8f))
            }
            start()
        }
    }

    
    private fun addCornerMarker(container: FrameLayout, gravity: Int, size: Int, thickness: Int, margin: Int, density: Float) {
        val corner = FrameLayout(this).apply {
            val lp = FrameLayout.LayoutParams(size, size)
            lp.gravity = gravity
            when (gravity) {
                Gravity.TOP or Gravity.START -> {
                    lp.leftMargin = margin
                    lp.topMargin = margin
                }
                Gravity.TOP or Gravity.END -> {
                    lp.rightMargin = margin
                    lp.topMargin = margin
                }
                Gravity.BOTTOM or Gravity.START -> {
                    lp.leftMargin = margin
                    lp.bottomMargin = margin
                }
                Gravity.BOTTOM or Gravity.END -> {
                    lp.rightMargin = margin
                    lp.bottomMargin = margin
                }
            }
            layoutParams = lp
            alpha = 0.5f  
        }
        
        
        val hLine = View(this).apply {
            val lp = FrameLayout.LayoutParams(size, thickness)
            lp.gravity = when (gravity) {
                Gravity.TOP or Gravity.START, Gravity.TOP or Gravity.END -> Gravity.TOP or Gravity.START
                else -> Gravity.BOTTOM or Gravity.START
            }
            layoutParams = lp
            setBackgroundColor(Color.parseColor("#4DFFFFFF"))  
        }
        corner.addView(hLine)
        
        
        val vLine = View(this).apply {
            val lp = FrameLayout.LayoutParams(thickness, size)
            lp.gravity = when (gravity) {
                Gravity.TOP or Gravity.START, Gravity.BOTTOM or Gravity.START -> Gravity.TOP or Gravity.START
                else -> Gravity.TOP or Gravity.END
            }
            layoutParams = lp
            setBackgroundColor(Color.parseColor("#4DFFFFFF"))  
        }
        corner.addView(vLine)
        
        container.addView(corner)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let { handleIntent(it) }
        return START_STICKY
    }

    private fun handleIntent(intent: Intent) {
        when (intent.action) {
            ACTION_SHOW_SCENE -> {
                val sceneId = intent.getStringExtra(EXTRA_SCENE_ID)
                val autoHideMs = intent.getLongExtra(EXTRA_AUTO_HIDE_MS, -1L)
                    .takeIf { it > 0 }
                if (sceneId != null) {
                    showScene(sceneId, autoHideMs)
                }
            }
            ACTION_SHOW_SCENE_BY_INDEX -> {
                val index = intent.getIntExtra(EXTRA_SCENE_INDEX, 0)
                showSceneByIndex(index)
            }
            ACTION_SHOW_SCENE_BY_TITLE -> {
                val sceneTitle = intent.getStringExtra(EXTRA_SCENE_TITLE)
                if (sceneTitle != null) {
                    showSceneByTitle(sceneTitle)
                }
            }
            ACTION_HIDE -> {
                hideOverlay()
            }
        }
    }

    private fun showScene(sceneId: String, autoHideOverrideMs: Long? = null) {
        if (SceneReset.matchesToken(sceneId)) {
            hideOverlay()
            return
        }
        val scene = NotificationScenes.getSceneById(sceneId) ?: return
        displayScene(scene, autoHideOverrideMs)
    }
    
    private fun showSceneByTitle(sceneTitle: String) {
        if (SceneReset.matchesToken(sceneTitle)) {
            hideOverlay()
            return
        }
        val scene = NotificationScenes.getSceneByTitle(sceneTitle) ?: return
        displayScene(scene)
    }
    
    private fun showSceneByIndex(index: Int) {
        val scene = NotificationScenes.getSceneByIndex(index) ?: return
        displayScene(scene)
    }
    
    private fun displayScene(scene: NotificationScene, autoHideOverrideMs: Long? = null) {
        if (SceneReset.matchesToken(scene.id) || SceneReset.matchesToken(scene.title)) {
            hideOverlay()
            return
        }
        serviceScope.launch {
            val settings = notificationSettingsStore.get()
            handler.post { presentScene(scene, settings, autoHideOverrideMs) }
        }
    }

    private fun presentScene(
        scene: NotificationScene,
        settings: com.example.ava.settings.NotificationSettings,
        autoHideOverrideMs: Long? = null,
        playSound: Boolean = true,
    ) {
        if (SceneReset.matchesToken(scene.id) || SceneReset.matchesToken(scene.title)) {
            hideOverlay()
            return
        }
        ScreensaverController.dismissForTransientOverlay()
        ScreensaverService.notifySmartAodInterrupt()
        QuickEntityOverlayService.notifySmartAodInterrupt()
        currentScene = scene
        lastAutoHideOverrideMs = autoHideOverrideMs

        autoHideRunnable?.let { handler.removeCallbacks(it) }
        if (isShowingAnimation) {
            overlayView?.animate()?.cancel()
            bannerOverlay.root?.animate()?.cancel()
        }

        val useBanner =
            settings.displayStyle == com.example.ava.settings.NotificationDisplayStyle.BANNER
        if (useBanner) {
            hideFullscreenImmediate()
            showBanner(scene, settings)
        } else {
            hideBannerImmediate()
            showFullscreen(scene)
        }
        if (playSound) playNotificationSound(scene)
        scheduleAutoHide(autoHideOverrideMs)
    }

    private fun showFullscreen(scene: NotificationScene) {
        updateContent(scene)
        updateColors(scene)

        isShowingAnimation = true
        // Restack while GONE so the HUD lands above the screensaver without a
        // visible remove+add hole (that hole was the mic disc flashing).
        bringToFront()
        overlayView?.alpha = 0f
        overlayView?.visibility = View.VISIBLE
        overlayView?.animate()
            ?.alpha(1f)
            ?.setDuration(300)
            ?.setInterpolator(AccelerateDecelerateInterpolator())
            ?.withEndAction { isShowingAnimation = false }
            ?.start()
    }

    private fun showBanner(
        scene: NotificationScene,
        settings: com.example.ava.settings.NotificationSettings,
    ) {
        val wm = windowManager ?: return
        bannerOverlay.ensureAttached(wm)
        bannerOverlay.onLayoutChanged = {
            bannerOverlay.windowParams?.let { params ->
                try {
                    bannerOverlay.root?.let { wm.updateViewLayout(it, params) }
                } catch (_: Exception) {
                }
            }
        }
        bannerOverlay.onExpandedChanged = { expanded ->
            if (expanded) scheduleAutoHide(lastAutoHideOverrideMs)
        }
        bannerOverlay.applyPosition(settings)
        bannerOverlay.bind(scene, settings) { resolveSceneText(it) }
        bannerOverlay.windowParams?.let { params ->
            try {
                bannerOverlay.root?.let { wm.updateViewLayout(it, params) }
            } catch (_: Exception) {
            }
        }
        isShowingAnimation = true
        bannerOverlay.root?.alpha = 0f
        bannerOverlay.root?.visibility = View.VISIBLE
        OverlayZOrderCoordinator.bringToFront(
            windowManager,
            bannerOverlay.root,
            bannerOverlay.windowParams,
            TAG
        )
        bannerOverlay.root?.animate()
            ?.alpha(1f)
            ?.setDuration(300)
            ?.setInterpolator(AccelerateDecelerateInterpolator())
            ?.withEndAction { isShowingAnimation = false }
            ?.start()
    }

    private fun hideFullscreenImmediate() {
        overlayView?.animate()?.cancel()
        overlayView?.visibility = View.GONE
        overlayView?.alpha = 0f
    }

    private fun hideBannerImmediate() {
        bannerOverlay.root?.animate()?.cancel()
        bannerOverlay.root?.visibility = View.GONE
        bannerOverlay.root?.alpha = 0f
    }
    
    
    private fun playNotificationSound(scene: NotificationScene) {
        serviceScope.launch {
            val settings = notificationSettingsStore.get()
            val uri = scene.resolveSoundUri(settings) ?: return@launch
            playSoundUri(uri)
        }
    }

    private fun playSoundUri(uri: String) {
        try {
            when {
                uri.startsWith("asset://") ||
                    uri.startsWith("http://") ||
                    uri.startsWith("https://") -> {
                    VoiceSatelliteService.getInstance()?.playSceneNotificationSound(uri)
                        ?: Log.w(TAG, "VoiceSatelliteService unavailable, cannot play scene sound")
                }
                else -> {
                    RingtoneManager.getRingtone(this, Uri.parse(uri))?.play()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play notification sound: $uri", e)
        }
    }
    
    private fun updateContent(scene: NotificationScene) {
        iconView?.text = FontAwesomeHelper.getIconChar(scene.icon)

        val title = resolveSceneText(scene.title)
        titleView?.let { view ->
            view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, fitTitleTextSizeSp(view, title))
            view.text = title
        }

        // Single spannable line: desc keeps bold + scene primary color, subDesc
        // stays regular gray. Separator space only when both parts are present.
        val desc = resolveSceneText(scene.desc)
        val subDesc = resolveSceneText(scene.subDesc)
        descView?.let { view ->
            if (desc.isBlank() && subDesc.isBlank()) {
                view.visibility = View.GONE
                return@let
            }
            view.visibility = View.VISIBLE
            val line = android.text.SpannableStringBuilder()
            if (desc.isNotBlank()) {
                line.append(desc)
                line.setSpan(
                    android.text.style.StyleSpan(Typeface.BOLD),
                    0, line.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                line.setSpan(
                    android.text.style.ForegroundColorSpan(scene.getPrimaryColor()),
                    0, line.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            if (subDesc.isNotBlank()) {
                if (line.isNotEmpty()) line.append(" ")
                line.append(subDesc)
            }
            view.text = line
            // Text change resets the marquee; re-assert selection so an
            // overflowing line starts scrolling again.
            view.isSelected = true
        }
    }

    /**
     * Font-size fit check: trial-layout with StaticLayout at the available width. If the
     * title exceeds 2 lines, step down 2sp at a time, down to [TITLE_MIN_SP]. If it still
     * does not fit, maxLines + ellipsize is the fallback. Available on every API (minSdk 21).
     */
    private fun fitTitleTextSizeSp(view: TextView, text: String): Float {
        val availWidth = textAvailWidthPx
        var sp = titleMaxSp
        if (availWidth <= 0 || text.isEmpty()) return sp
        val paint = android.text.TextPaint(view.paint)
        val scaledDensity = resources.displayMetrics.scaledDensity
        while (sp > TITLE_MIN_SP) {
            paint.textSize = sp * scaledDensity
            @Suppress("DEPRECATION")
            val layout = android.text.StaticLayout(
                text, paint, availWidth,
                android.text.Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false
            )
            if (layout.lineCount <= 2) break
            sp = (sp - 2f).coerceAtLeast(TITLE_MIN_SP)
        }
        return sp
    }

    /**
     * Replace {{entity_id}} / {{entity_id|attr}} placeholders with entity values from the
     * current VoiceSatelliteService cache. If the service is not ready or the cache has
     * no value, the placeholder becomes "--".
     */
    private fun resolveSceneText(text: String): String {
        if (!com.example.ava.notifications.SceneTemplateResolver.hasPlaceholders(text)) return text
        val svc = VoiceSatelliteService.getInstance()
        val states = svc?.getSceneEntityStates().orEmpty()
        val units = svc?.getSceneEntityUnits().orEmpty()
        val attrs = svc?.getSceneEntityAttributes().orEmpty()
        return com.example.ava.notifications.SceneTemplateResolver.resolve(text) { eid, haAttr ->
            when (haAttr) {
                "" -> states[eid]
                "unit_of_measurement" -> units[eid]
                else -> attrs[eid]?.get(haAttr)
            }
        }
    }

    /** Called when VoiceSatellite receives a new state for a target entity: if the current scene is showing and references that entity, refresh the text. */
    fun onSceneEntityChanged(entityId: String) {
        val scene = currentScene ?: return
        val fullscreenVisible = overlayView?.visibility == View.VISIBLE
        val bannerVisible = bannerOverlay.root?.visibility == View.VISIBLE
        if (!fullscreenVisible && !bannerVisible) return
        val refs = com.example.ava.notifications.SceneTemplateResolver
            .extractRefs(scene.title, scene.desc, scene.subDesc)
        if (refs.none { it.entityId == entityId.lowercase() }) return
        handler.post {
            if (fullscreenVisible) updateContent(scene)
            if (bannerVisible) {
                serviceScope.launch {
                    val settings = notificationSettingsStore.get()
                    handler.post {
                        bannerOverlay.bind(scene, settings) { resolveSceneText(it) }
                    }
                }
            }
        }
    }
    
    private fun updateColors(scene: NotificationScene) {
        val themeColors = scene.getThemeColorInts()
        val primaryColor = scene.getPrimaryColor()
        val beamColor = scene.getBeamColorInt()
        val dividerColor = scene.getDividerColorInt()
        val dotColor = scene.getDotColorInt()
        val iconColor = scene.getIconColorInt()

        
        val bgGradient = backgroundView?.background as? GradientDrawable
        if (bgGradient != null && themeColors.isNotEmpty()) {
            val color1 = adjustAlpha(themeColors.getOrElse(0) { primaryColor }, 0.4f)
            val color2 = adjustAlpha(themeColors.getOrElse(1) { primaryColor }, 0.2f)
            bgGradient.colors = intArrayOf(color1, color2, Color.TRANSPARENT)
        }

        
        val dividerGradient = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.TRANSPARENT, dividerColor, Color.TRANSPARENT)
        )
        dividerView?.background = dividerGradient

        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            dividerView?.outlineAmbientShadowColor = adjustAlpha(dividerColor, 0.6f)
        }

        
        // desc line colors live in updateContent's spans (desc = primary, subDesc = gray).

        
        iconView?.setTextColor(iconColor)
        iconView?.setShadowLayer(25f, 0f, 0f, adjustAlpha(iconColor, 0.9f))

        
        val beamGradient = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                Color.TRANSPARENT,
                beamColor,
                primaryColor,
                beamColor,
                Color.TRANSPARENT
            )
        )
        beamView?.background = beamGradient

        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            beamView?.outlineAmbientShadowColor = adjustAlpha(primaryColor, 0.3f)
        }

        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            scanLine?.outlineAmbientShadowColor = adjustAlpha(primaryColor, 0.2f)
        }

        
        val iconBoxSize = coreGlowView?.width ?: 100
        val coreGradient = GradientDrawable()
        coreGradient.shape = GradientDrawable.OVAL
        coreGradient.gradientType = GradientDrawable.RADIAL_GRADIENT
        coreGradient.gradientRadius = iconBoxSize / 2f
        coreGradient.colors = intArrayOf(
            adjustAlpha(primaryColor, 0.4f),
            Color.TRANSPARENT
        )
        coreGlowView?.background = coreGradient

        
        (dotView?.background as? GradientDrawable)?.setColor(dotColor)
    }
    
    private fun adjustAlpha(color: Int, factor: Float): Int {
        val alpha = (255 * factor).toInt().coerceIn(0, 255)
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun scheduleAutoHide(overrideMs: Long? = null) {

        autoHideRunnable?.let { handler.removeCallbacks(it) }

        val currentRunnable = Runnable {
            val targets = listOfNotNull(overlayView, bannerOverlay.root)
                .filter { it.visibility == View.VISIBLE }
            if (targets.isEmpty()) return@Runnable
            currentScene = null
            OverlayZOrderCoordinator.cancelScheduledVoiceRaise()
            targets.forEach { view ->
                view.animate()
                    ?.alpha(0f)
                    ?.setDuration(300)
                    ?.withEndAction { finishHidingScene(view) }
                    ?.start()
            }
        }
        autoHideRunnable = currentRunnable

        if (overrideMs != null) {
            handler.postDelayed(currentRunnable, overrideMs)
            return
        }

        serviceScope.launch {
            val settings = notificationSettingsStore.get()

            if (autoHideRunnable === currentRunnable) {
                handler.postDelayed(currentRunnable, settings.sceneDisplayDuration.toLong())
            }
        }
    }

    private fun finishHidingScene(view: View) {
        view.visibility = View.GONE
        // Scene sat on top of the disc; GONE does not bury it. Climbing here is the
        // same glyph twitch as opening the scene.
        OverlayZOrderCoordinator.cancelScheduledVoiceRaise()
    }

    private fun hideOverlay() {
        OverlayZOrderCoordinator.cancelScheduledVoiceRaise()
        handler.post {
            autoHideRunnable?.let { handler.removeCallbacks(it) }
            currentScene = null
            listOfNotNull(overlayView, bannerOverlay.root).forEach { view ->
                view.animate()
                    ?.alpha(0f)
                    ?.setDuration(200)
                    ?.withEndAction { finishHidingScene(view) }
                    ?.start()
            }
        }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        
        
        serviceScope.cancel()

        
        cancelOverlayAnimators()

        try {
            overlayView?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove notification overlay", e)
        }
        bannerOverlay.detach(windowManager)
    }

    companion object {
        private const val TAG = "NotificationOverlay"
        /** Title auto-shrink floor; below this we ellipsize instead. */
        private const val TITLE_MIN_SP = 20f

        @Volatile
        private var instance: NotificationOverlayService? = null

        /** Used by a running service to get the current overlay instance (for example, to refresh a visible scene when an HA state push arrives). */
        fun getInstance(): NotificationOverlayService? = instance

        fun bringToFrontIfVisible() {
            instance?.bringToFront()
            instance?.bringBannerToFront()
        }

        /** True while a scene notification (fullscreen or banner) is on screen (or animating in). */
        fun isOverlayVisible(): Boolean {
            val service = instance ?: return false
            return service.overlayView?.visibility == View.VISIBLE ||
                service.bannerOverlay.root?.visibility == View.VISIBLE ||
                service.isShowingAnimation
        }

        /** Fullscreen scene only — the banner cannot trap the panel. */
        fun isFullscreenShowing(): Boolean {
            val service = instance ?: return false
            return service.overlayView?.visibility == View.VISIBLE
        }

        const val ACTION_SHOW_SCENE = "com.example.ava.SHOW_NOTIFICATION_SCENE"
        const val ACTION_SHOW_SCENE_BY_INDEX = "com.example.ava.SHOW_NOTIFICATION_SCENE_INDEX"
        const val ACTION_SHOW_SCENE_BY_TITLE = "com.example.ava.SHOW_NOTIFICATION_SCENE_TITLE"
        const val ACTION_HIDE = "com.example.ava.HIDE_NOTIFICATION"
        const val EXTRA_SCENE_ID = "scene_id"
        const val EXTRA_SCENE_INDEX = "scene_index"
        const val EXTRA_SCENE_TITLE = "scene_title"
        const val EXTRA_AUTO_HIDE_MS = "auto_hide_ms"
        private const val PREVIEW_DURATION_MS = 3000L

        fun showScene(context: Context, sceneId: String) {
            val intent = Intent(context, NotificationOverlayService::class.java).apply {
                action = ACTION_SHOW_SCENE
                putExtra(EXTRA_SCENE_ID, sceneId)
            }
            context.startService(intent)
        }

        /** Settings-page preview: fixed short auto-hide, ignores sceneDisplayDuration. */
        fun previewScene(context: Context, sceneId: String) {
            val intent = Intent(context, NotificationOverlayService::class.java).apply {
                action = ACTION_SHOW_SCENE
                putExtra(EXTRA_SCENE_ID, sceneId)
                putExtra(EXTRA_AUTO_HIDE_MS, PREVIEW_DURATION_MS)
            }
            context.startService(intent)
        }
        
        fun showSceneByTitle(context: Context, sceneTitle: String) {
            val intent = Intent(context, NotificationOverlayService::class.java).apply {
                action = ACTION_SHOW_SCENE_BY_TITLE
                putExtra(EXTRA_SCENE_TITLE, sceneTitle)
            }
            context.startService(intent)
        }
        
        fun showSceneByIndex(context: Context, index: Int) {
            val intent = Intent(context, NotificationOverlayService::class.java).apply {
                action = ACTION_SHOW_SCENE_BY_INDEX
                putExtra(EXTRA_SCENE_INDEX, index)
            }
            context.startService(intent)
        }

        fun hide(context: Context) {
            val intent = Intent(context, NotificationOverlayService::class.java).apply {
                action = ACTION_HIDE
            }
            context.startService(intent)
        }
    }

    private fun bringToFront() {
        val view = overlayView ?: return
        if (view.visibility != View.VISIBLE && !isShowingAnimation) return
        OverlayZOrderCoordinator.bringToFront(
            windowManager,
            view,
            windowParams,
            TAG,
            raiseMic = false,
        )
    }

    private fun bringBannerToFront() {
        val view = bannerOverlay.root ?: return
        if (view.visibility != View.VISIBLE && !isShowingAnimation) return
        OverlayZOrderCoordinator.bringToFront(
            windowManager,
            view,
            bannerOverlay.windowParams,
            TAG
        )
    }
}

package com.example.ava.services

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.example.ava.R
import com.example.ava.ui.glass.LiquidGlass
import com.example.ava.utils.LightKeywordDetector

class HaSwitchOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var iconView: ImageView? = null
    private var circleBackground: View? = null
    private var glowView: View? = null
    private var glowAnimator: ValueAnimator? = null
    
    private val handler = Handler(Looper.getMainLooper())
    private var autoHideRunnable: Runnable? = null
    private var isShowing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        LiquidGlass.ensureLoaded(this)
        createOverlayView()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        overlayView?.let {
            windowManager?.removeView(it)
            overlayView = null
        }
        createOverlayView()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createOverlayView() {
        val displayMetrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager?.defaultDisplay?.getRealMetrics(displayMetrics)
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val density = displayMetrics.density
        
        val minDim = minOf(screenWidth, screenHeight)
        val circleSize = (minDim * 0.45f).toInt().coerceIn((160 * density).toInt(), (280 * density).toInt())
        val iconSize = (circleSize * 0.38f / density)

        val rootContainer = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            // With backdrop blur the scrim can be lighter; the blur already separates the dial.
            setBackgroundColor(if (LiquidGlass.enabled) 0x66000000 else Color.parseColor("#99000000"))
        }

        val centerContainer = FrameLayout(this).apply {
            val containerSize = (circleSize * 2.5f).toInt()
            val lp = FrameLayout.LayoutParams(containerSize, containerSize)
            lp.gravity = Gravity.CENTER
            layoutParams = lp
            clipChildren = false
            clipToPadding = false
        }

        glowView = View(this).apply {
            val glowSize = (circleSize * 2.5f).toInt()
            val lp = FrameLayout.LayoutParams(glowSize, glowSize)
            lp.gravity = Gravity.CENTER
            layoutParams = lp
            
            val gradient = GradientDrawable()
            gradient.shape = GradientDrawable.OVAL
            gradient.gradientType = GradientDrawable.RADIAL_GRADIENT
            gradient.setGradientCenter(0.5f, 0.5f)
            gradient.gradientRadius = glowSize / 2f
            gradient.colors = intArrayOf(
                Color.parseColor("#4DFFC107"),
                Color.parseColor("#26FFC107"),
                Color.parseColor("#0DFFC107"),
                Color.TRANSPARENT
            )
            background = gradient
            alpha = 0f
            scaleX = 0.6f
            scaleY = 0.6f
        }
        centerContainer.addView(glowView)

        circleBackground = View(this).apply {
            val lp = FrameLayout.LayoutParams(circleSize, circleSize)
            lp.gravity = Gravity.CENTER
            layoutParams = lp
            
            if (LiquidGlass.enabled) {
                // Circular glass puck: the drawable's corner radius = half the size → a circle.
                LiquidGlass.applyTo(
                    this,
                    cornerRadiusPx = circleSize / 2f,
                    tint = 0x33FFFFFF,
                    windowBacked = true,
                ).setDensity(density)
            } else {
                val bg = GradientDrawable()
                bg.shape = GradientDrawable.OVAL
                bg.setColor(Color.parseColor("#1AFFFFFF"))
                bg.setStroke((3 * density).toInt(), Color.parseColor("#66FFFFFF"))
                background = bg
            }
            elevation = 8 * density
        }
        centerContainer.addView(circleBackground)

        val iconSizePx = (circleSize * 0.5f).toInt()
        iconView = ImageView(this).apply {
            val lp = FrameLayout.LayoutParams(iconSizePx, iconSizePx)
            lp.gravity = Gravity.CENTER
            layoutParams = lp
            setImageResource(R.drawable.ic_ha_light)
            setColorFilter(Color.parseColor("#9E9E9E"))
        }
        centerContainer.addView(iconView)

        rootContainer.addView(centerContainer)
        overlayView = rootContainer

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val flags = WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            // This overlay is a full-screen dim + centred dial, so blurring everything behind
            // it is the intended look. Radius follows the Liquid Glass intensity; off = none.
            LiquidGlass.applyWindowBlur(this, density)
            OverlayOrientation.apply(this)
        }
        windowParams = params

        try {
            windowManager?.addView(overlayView, params)
            OverlayZOrderCoordinator.noteWindowAdded()
            overlayView?.visibility = View.GONE
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create HA switch overlay", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let { handleIntent(it) }
        return START_STICKY
    }

    private fun handleIntent(intent: Intent) {
        when (intent.action) {
            ACTION_SHOW_DEVICE -> {
                val deviceTypeName = intent.getStringExtra(EXTRA_DEVICE_TYPE) ?: return
                val isOn = intent.getBooleanExtra(EXTRA_IS_ON, true)
                val deviceType = try {
                    LightKeywordDetector.DeviceType.valueOf(deviceTypeName)
                } catch (e: Exception) {
                    LightKeywordDetector.DeviceType.LIGHT
                }
                showDeviceState(deviceType, isOn)
            }
            ACTION_HIDE -> hideOverlay()
        }
    }

    private fun showDeviceState(deviceType: LightKeywordDetector.DeviceType, isOn: Boolean) {
        handler.post {
            autoHideRunnable?.let { handler.removeCallbacks(it) }
            
            updateDeviceState(deviceType, isOn)
            bringToFront()
            
            if (!isShowing) {
                isShowing = true
                overlayView?.alpha = 0f
                overlayView?.scaleX = 0.8f
                overlayView?.scaleY = 0.8f
                overlayView?.visibility = View.VISIBLE
                
                overlayView?.animate()
                    ?.alpha(1f)
                    ?.scaleX(1f)
                    ?.scaleY(1f)
                    ?.setDuration(300)
                    ?.setInterpolator(OvershootInterpolator(1.2f))
                    ?.start()
            }
            
            scheduleAutoHide()
        }
    }

    private fun updateDeviceState(deviceType: LightKeywordDetector.DeviceType, isOn: Boolean) {
        glowAnimator?.cancel()
        val density = resources.displayMetrics.density
        
        val iconRes = when (deviceType) {
            LightKeywordDetector.DeviceType.LIGHT -> R.drawable.ic_ha_light
            LightKeywordDetector.DeviceType.SWITCH -> R.drawable.ic_ha_switch
            LightKeywordDetector.DeviceType.BUTTON -> R.drawable.ic_ha_button
        }
        iconView?.setImageResource(iconRes)
        
        if (isOn) {
            iconView?.setColorFilter(Color.parseColor("#FFD54F"))
            
            val circleBg = circleBackground?.background as? GradientDrawable
            circleBg?.setColor(Color.parseColor("#33FFC107"))
            circleBg?.setStroke((3 * density).toInt(), Color.parseColor("#99FFC107"))
            
            glowView?.alpha = 0f
            glowView?.scaleX = 0.6f
            glowView?.scaleY = 0.6f
            
            glowAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1500
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                addUpdateListener { animator ->
                    val value = animator.animatedValue as Float
                    glowView?.alpha = 0.3f + value * 0.4f
                    glowView?.scaleX = 0.7f + value * 0.3f
                    glowView?.scaleY = 0.7f + value * 0.3f
                }
                start()
            }
        } else {
            iconView?.setColorFilter(Color.parseColor("#757575"))
            
            val circleBg = circleBackground?.background as? GradientDrawable
            circleBg?.setColor(Color.parseColor("#1AFFFFFF"))
            circleBg?.setStroke((3 * density).toInt(), Color.parseColor("#66FFFFFF"))
            
            glowView?.animate()
                ?.alpha(0f)
                ?.scaleX(0.5f)
                ?.scaleY(0.5f)
                ?.setDuration(300)
                ?.start()
        }
    }

    private fun scheduleAutoHide() {
        autoHideRunnable?.let { handler.removeCallbacks(it) }
        autoHideRunnable = Runnable { hideOverlay() }
        handler.postDelayed(autoHideRunnable!!, AUTO_HIDE_DELAY)
    }

    private fun hideOverlay() {
        OverlayZOrderCoordinator.cancelScheduledVoiceRaise()
        handler.post {
            autoHideRunnable?.let { handler.removeCallbacks(it) }
            glowAnimator?.cancel()
            
            overlayView?.animate()
                ?.alpha(0f)
                ?.setDuration(500)
                ?.setInterpolator(AccelerateDecelerateInterpolator())
                ?.withEndAction {
                    overlayView?.visibility = View.GONE
                    isShowing = false
                }
                ?.start()
        }
    }

    override fun onDestroy() {
        glowAnimator?.cancel()
        autoHideRunnable?.let { handler.removeCallbacks(it) }
        overlayView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove overlay view", e)
            }
        }
        instance = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "HaSwitchOverlay"
        private const val AUTO_HIDE_DELAY = 2000L
        @Volatile private var instance: HaSwitchOverlayService? = null
        
        const val ACTION_SHOW_DEVICE = "com.example.ava.action.SHOW_DEVICE"
        const val ACTION_HIDE = "com.example.ava.action.HIDE_SWITCH"
        const val EXTRA_DEVICE_TYPE = "device_type"
        const val EXTRA_IS_ON = "is_on"

        fun bringToFrontIfVisible() {
            instance?.bringToFront()
        }

        fun isOverlayShowing(): Boolean = instance?.isShowing == true

        fun showDeviceAction(context: Context, deviceType: LightKeywordDetector.DeviceType, isOn: Boolean) {
            val intent = Intent(context, HaSwitchOverlayService::class.java).apply {
                action = ACTION_SHOW_DEVICE
                putExtra(EXTRA_DEVICE_TYPE, deviceType.name)
                putExtra(EXTRA_IS_ON, isOn)
            }
            context.startService(intent)
        }

        fun hide(context: Context) {
            val intent = Intent(context, HaSwitchOverlayService::class.java).apply {
                action = ACTION_HIDE
            }
            context.startService(intent)
        }
    }

    private fun bringToFront() {
        if (!isShowing) return
        OverlayZOrderCoordinator.bringToFront(windowManager, overlayView, windowParams, TAG)
    }
}

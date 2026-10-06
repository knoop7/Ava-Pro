package com.example.ava.services

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.example.ava.R
import com.example.ava.esphome.Disconnected
import com.example.ava.esphome.Stopped
import com.example.ava.ui.glass.LiquidGlass
import com.example.ava.ui.glass.LiquidGlassDrawable
import com.example.ava.utils.DeviceFeatureManager

class VolumeControlService : Service() {
    
    companion object {
        const val ACTION_VOLUME_UP = "com.example.ava.VOLUME_UP"
        const val ACTION_VOLUME_DOWN = "com.example.ava.VOLUME_DOWN"
        private const val LEVEL_COUNT = 8
        
        private var instance: VolumeControlService? = null

        fun bringToFrontIfVisible() {
            instance?.bringToFront()
        }

        private fun isVoiceServiceReady(): Boolean {
            val service = VoiceSatelliteService.getInstance() ?: return false
            val state = service.getState()
            return state !is Disconnected && state !is Stopped
        }
        
        fun volumeUp(context: Context) {
            if (!DeviceFeatureManager.isA64Device() || !isVoiceServiceReady()) return
            instance?.let {
                it.adjustVolume(true)
                it.showVolume()
            } ?: context.startService(Intent(context, VolumeControlService::class.java).apply {
                action = ACTION_VOLUME_UP
            })
        }
        
        fun volumeDown(context: Context) {
            if (!DeviceFeatureManager.isA64Device() || !isVoiceServiceReady()) return
            instance?.let {
                it.adjustVolume(false)
                it.showVolume()
            } ?: context.startService(Intent(context, VolumeControlService::class.java).apply {
                action = ACTION_VOLUME_DOWN
            })
        }
    }
    
    private var windowManager: WindowManager? = null
    private var audioManager: AudioManager? = null
    private var volumeView: VolumeView? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())
    private var hideRunnable: Runnable? = null
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        LiquidGlass.ensureLoaded(this)
        createVolumeView()
    }
    
    private fun createVolumeView() {
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        
        volumeView = VolumeView(this)
        
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            LiquidGlass.applyWindowBlur(this, resources.displayMetrics.density)
            OverlayOrientation.apply(this)
        }
        windowParams = layoutParams
        
        try {
            windowManager?.addView(volumeView, layoutParams)
            OverlayZOrderCoordinator.noteWindowAdded()
            volumeView?.visibility = View.GONE
        } catch (e: Exception) {
            android.util.Log.e("VolumeControlService", "Failed to add volume view", e)
            volumeView = null
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!DeviceFeatureManager.isA64Device() || !isVoiceServiceReady()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_VOLUME_UP -> {
                adjustVolume(true)
                showVolume()
            }
            ACTION_VOLUME_DOWN -> {
                adjustVolume(false)
                showVolume()
            }
        }
        return START_NOT_STICKY
    }
    
    internal fun adjustVolume(isUp: Boolean) {
        val maxSystemVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
        val currentVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
        val intervalValue = maxSystemVolume.toFloat() / LEVEL_COUNT
        val currentLevel = ((currentVolume / intervalValue) + 0.5f).toInt().coerceIn(1, LEVEL_COUNT)
        val newLevel = if (isUp) {
            (currentLevel + 1).coerceAtMost(LEVEL_COUNT)
        } else {
            (currentLevel - 1).coerceAtLeast(1)
        }
        val newVolume = if (newLevel >= LEVEL_COUNT) maxSystemVolume else ((newLevel * intervalValue).toInt().coerceIn(1, maxSystemVolume))
        audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
        VoiceSatelliteService.notifyHardwareMusicVolumeChanged()
    }
    
    internal fun showVolume() {
        val maxSystemVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
        val volume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 4
        val intervalValue = maxSystemVolume.toFloat() / LEVEL_COUNT
        val level = ((volume / intervalValue) + 0.5f).toInt().coerceIn(1, LEVEL_COUNT)
        
        volumeView?.setLevel(level)
        bringToFront()
        volumeView?.let { v ->
            v.animate().cancel()
            if (v.visibility != View.VISIBLE) {
                v.alpha = 0f
                v.visibility = View.VISIBLE
                v.animate().alpha(1f).setDuration(300)
                    .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
            }
        }
        
        hideRunnable?.let { handler.removeCallbacks(it) }
        hideRunnable = Runnable {
            volumeView?.let { v ->
                v.animate().cancel()
                v.animate().alpha(0f).setDuration(300)
                    .setInterpolator(android.view.animation.AccelerateInterpolator())
                    .withEndAction {
                        v.visibility = View.GONE
                        v.alpha = 1f
                    }.start()
            }
        }
        handler.postDelayed(hideRunnable!!, 2000)
    }
    
    override fun onDestroy() {
        hideRunnable?.let { handler.removeCallbacks(it) }
        volumeView?.let {
            try { windowManager?.removeView(it) } catch (e: Exception) { }
        }
        volumeView = null
        windowParams = null
        instance = null
        super.onDestroy()
    }
    
    override fun onBind(intent: Intent?): IBinder? = null

    private fun bringToFront() {
        val view = volumeView ?: return
        if (view.visibility != View.VISIBLE) return
        OverlayZOrderCoordinator.bringToFront(windowManager, view, windowParams, "VolumeControlService")
    }
    
    inner class VolumeView(context: Context) : View(context) {
        
        private val maxLevel = 8
        private var level = 4
        
        private val circleColor = Color.argb(200, 255, 255, 255)
        private val selectedColor = Color.argb(255, 236, 28, 36)
        private val unselectedColor = Color.WHITE
        
        private val paintCircle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = circleColor }
        private val paintSelected = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = selectedColor }
        private val paintUnselected = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = unselectedColor }
        
        private val soundRadius = dpToPx(5f)
        private val soundBetween = dpToPx(10f)
        private val soundMarginBottom = dpToPx(90f)
        private val viewSize = dpToPx(300f)
        private val glassDisc = LiquidGlassDrawable(
            cornerRadiusPx = viewSize / 2f,
            tint = LiquidGlassDrawable.DEFAULT_LIGHT_TINT,
            light = true,
            windowBacked = true,
        ).also { it.setDensity(resources.displayMetrics.density) }
        
        private val imgResources = intArrayOf(R.drawable.sound_low, R.drawable.sound_medium, R.drawable.sound_high)
        private val selections = intArrayOf(0, 3, 6)
        
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(viewSize.toInt(), viewSize.toInt())
        }
        
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            
            val w = width
            val h = height
            val cx = w / 2
            val cy = h / 2
            val radius = (if (w > h) h else w) / 2
            val marginBottom = dpToPx(23f).toInt()
            
            if (LiquidGlass.enabled) {
                // Light glass puck (circle = corner radius of half the size). The window blurs
                // what's behind, so the body can be more transparent than the flat disc.
                glassDisc.setBounds(cx - radius, cy - radius, cx + radius, cy + radius)
                glassDisc.draw(canvas)
            } else {
                canvas.drawCircle(cx.toFloat(), cy.toFloat(), radius.toFloat(), paintCircle)
            }
            drawSound(canvas, cx, h - marginBottom)
            drawImg(canvas, cx, cy - marginBottom)
        }
        
        private fun drawSound(canvas: Canvas, x: Int, y: Int) {
            val size = (soundRadius * 8 + soundBetween * 7).toInt()
            var startX = x - size / 2
            val startY = (y - soundMarginBottom).toInt()
            
            for (i in 1..8) {
                val paint = if (i <= level) paintSelected else paintUnselected
                canvas.drawCircle(startX.toFloat(), startY.toFloat(), soundRadius, paint)
                startX += (soundRadius + soundBetween).toInt()
            }
        }
        
        private fun drawImg(canvas: Canvas, x: Int, y: Int) {
            var idx = 0
            for (i in selections.indices) {
                if (i < imgResources.size && selections[i] <= level) {
                    idx = i
                }
            }
            
            val drawable = resources.getDrawable(imgResources[idx], null)
            val bitmapDrawable = drawable as? BitmapDrawable ?: return
            val w = bitmapDrawable.bitmap.width
            val h = bitmapDrawable.bitmap.height
            val l = x - w / 2
            val r = x + w / 2
            val t = y - h / 2
            val b = y + h / 2
            drawable.setBounds(l, t, r, b)
            drawable.draw(canvas)
        }
        
        fun setLevel(newLevel: Int) {
            level = newLevel.coerceIn(1, maxLevel)
            invalidate()
        }
        
        private fun dpToPx(dp: Float): Float = dp * resources.displayMetrics.density
    }
}

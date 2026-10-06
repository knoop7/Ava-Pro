package com.example.ava.services

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import com.example.ava.R
import com.example.ava.settings.playerSettingsStore
import com.example.ava.ui.OverlayLogoBadge
import com.example.ava.weather.WeatherData
import com.example.ava.weather.WeatherService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.random.Random


class WeatherOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var weatherHost: FrameLayout? = null
    private var weatherView: WeatherOverlayView? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var isWeatherEnabled = false
    private var isWeatherVisible = true
    /** Bumped on every show/hide so a cancelled fade-out cannot leave the view stuck VISIBLE. */
    private var weatherHideGeneration = 0
    @Volatile private var isServiceRunning = true

    override fun onBind(intent: Intent?): IBinder? = null

    private val weatherListener: (WeatherData) -> Unit = { weather ->
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            weatherView?.updateWeather(weather)
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        OverlayLayerSplit.register(
            this,
            OverlayLayerSplit.Layer.WEATHER,
            showing = { isOverlayShowing() },
            host = { weatherHost },
            apply = { frame -> applyLayerSplit(frame) },
        )
        WeatherService.addWeatherListener(weatherListener)
    }
    
    private fun bringToFront() {
        if (!isWeatherEnabled || !isWeatherVisible) return
        OverlayLayerSplit.sync()
        if (!OverlayLayerSplit.isPaneView(weatherHost)) {
            OverlayZOrderCoordinator.bringToFront(windowManager, weatherHost, windowParams, TAG)
        }
        OverlayZOrderCoordinator.raiseVinylFabAbovePassiveDashboard()
    }
    
    private fun ensureWeatherViewCreated() {
        if (weatherView == null) {
            createWeatherView()
            fetchWeather()
        }
    }

    private fun fetchWeather() {
        serviceScope.launch {
            try {
                val settings = playerSettingsStore.data.first()
                val weatherEntity = settings.haWeatherEntity
                if (weatherEntity.isBlank()) {
                    Log.w(TAG, "No HA weather entity configured, skipping weather fetch")
                    return@launch
                }

                val weather = WeatherService.getCachedWeather()
                if (weather != null) {
                    weatherView?.updateWeather(weather)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch weather: ${e.message}")
            }
        }
        
        if (isServiceRunning) {
            handler.postDelayed({ fetchWeather() }, 60 * 1000L)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createWeatherView() {
        val displayMetrics = resources.displayMetrics

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        
        val realMetrics = android.util.DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            windowManager?.defaultDisplay?.getRealMetrics(realMetrics)
        } else {
            windowManager?.defaultDisplay?.getMetrics(realMetrics)
        }
        val realWidth = realMetrics.widthPixels
        val realHeight = realMetrics.heightPixels

        windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
                    WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION or
                    WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            OverlayOrientation.apply(this)
        }

        val overlay = WeatherOverlayView(this, realWidth, realHeight).apply {
            setOnTouchListener(
                DashboardTouchListener(
                    context = this@WeatherOverlayService,
                    kind = DashboardOverlayChrome.Kind.WEATHER,
                    onSwipeLeft = { switchToClock() },
                )
            )
        }
        weatherView = overlay
        val host = FrameLayout(this).apply {
            addView(
                overlay,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            visibility = View.GONE
        }
        weatherHost = host
        DashboardOverlayChrome.attach(host, DashboardOverlayChrome.Kind.WEATHER)

        try {
            windowManager?.addView(host, windowParams)
            OverlayZOrderCoordinator.noteWindowAdded()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add weather window", e)
        }
    }

    private fun switchToClock() {
        
        serviceScope.launch {
            try {
                val settings = playerSettingsStore.data.first()
                
                if (settings.enableDreamClock) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        hide(this@WeatherOverlayService)
                        DreamClockService.show(this@WeatherOverlayService)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check clock settings: ${e.message}")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> {
                ensureWeatherViewCreated()
                val alreadyShown = isWeatherEnabled && isWeatherVisible &&
                    weatherHost?.visibility == View.VISIBLE
                isWeatherEnabled = true
                isWeatherVisible = true
                OverlayLayerSplit.noteOpened(OverlayLayerSplit.Layer.WEATHER)
                if (!alreadyShown) {
                    OverlayLayerSplit.sync()
                    bringToFront()
                    weatherHost?.let { fadeInWeather(it) }
                    weatherView?.startAnimation()
                    OverlayZOrderCoordinator.raiseVinylFabAbovePassiveDashboard()
                }
                DashboardOverlayChrome.bind(this, DashboardOverlayChrome.Kind.WEATHER)
                if (!alreadyShown) {
                    DashboardOverlayChrome.revealOnShow(DashboardOverlayChrome.Kind.WEATHER)
                }
            }
            ACTION_HIDE -> {
                isWeatherEnabled = false
                isWeatherVisible = false
                OverlayLayerSplit.sync()
                weatherView?.stopAnimation()
                weatherHost?.let { fadeOutWeather(it) }
                DashboardOverlayChrome.unbind(DashboardOverlayChrome.Kind.WEATHER)
            }
            ACTION_SET_VISIBLE -> {
                if (isWeatherEnabled) {
                    val visible = intent.getBooleanExtra(EXTRA_VISIBLE, true)
                    if (visible && !isWeatherVisible) {
                        isWeatherVisible = true
                        OverlayLayerSplit.sync()
                        weatherHost?.let { fadeInWeather(it) }
                        weatherView?.startAnimation()
                        DashboardOverlayChrome.bind(this, DashboardOverlayChrome.Kind.WEATHER)
                        DashboardOverlayChrome.revealOnShow(DashboardOverlayChrome.Kind.WEATHER)
                        OverlayZOrderCoordinator.raiseVinylFabAbovePassiveDashboard()
                    } else if (!visible && isWeatherVisible) {
                        isWeatherVisible = false
                        OverlayLayerSplit.sync()
                        weatherView?.stopAnimation()
                        weatherHost?.let { fadeOutWeather(it) }
                        DashboardOverlayChrome.unbind(DashboardOverlayChrome.Kind.WEATHER)
                    }
                } else if (!intent.getBooleanExtra(EXTRA_VISIBLE, true)) {
                    // Feature already disabled but window may still be stuck VISIBLE.
                    isWeatherVisible = false
                    OverlayLayerSplit.sync()
                    weatherView?.stopAnimation()
                    weatherHost?.let { fadeOutWeather(it) }
                    DashboardOverlayChrome.unbind(DashboardOverlayChrome.Kind.WEATHER)
                }
            }
            "com.example.ava.ACTION_BRING_TO_FRONT" -> {
                bringToFront()
            }
        }
        return START_NOT_STICKY
    }

    private fun fadeInWeather(v: View) {
        weatherHideGeneration++
        v.animate().cancel()
        v.animate().setListener(null)
        v.alpha = 0f
        v.visibility = View.VISIBLE
        OverlayLayerSplit.fadeWhenPaneReady(OverlayLayerSplit.Layer.WEATHER) {
            if (v.visibility != View.VISIBLE) return@fadeWhenPaneReady
            v.animate().alpha(1f).setDuration(230)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun fadeOutWeather(v: View) {
        OverlayZOrderCoordinator.cancelScheduledVoiceRaise()
        val gen = ++weatherHideGeneration
        v.animate().cancel()
        v.animate().setListener(null)
        v.animate().alpha(0f).setDuration(230)
            .setInterpolator(AccelerateInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                private var canceled = false
                override fun onAnimationCancel(animation: Animator) {
                    canceled = true
                    // Cancelled without a following show — still force GONE.
                    if (gen == weatherHideGeneration && !isWeatherVisible) {
                        v.visibility = View.GONE
                        v.alpha = 1f
                        v.animate().setListener(null)
                    }
                }
                override fun onAnimationEnd(animation: Animator) {
                    v.animate().setListener(null)
                    if (canceled || gen != weatherHideGeneration) return
                    v.visibility = View.GONE
                    v.alpha = 1f
                }
            })
            .start()
    }

    private fun applyLayerSplit(frame: OverlayLayerSplit.Frame?) {
        val host = weatherHost ?: return
        if (frame == null && !isWeatherVisible && host.visibility == View.VISIBLE) return
        OverlayLayerSplit.applyTo(
            OverlayLayerSplit.Layer.WEATHER,
            windowManager,
            host,
            windowParams,
            frame,
        )
    }

    override fun onDestroy() {
        OverlayLayerSplit.unregister(OverlayLayerSplit.Layer.WEATHER)
        super.onDestroy()
        DashboardOverlayChrome.unbind(DashboardOverlayChrome.Kind.WEATHER)
        isServiceRunning = false
        WeatherService.removeWeatherListener(weatherListener)
        handler.removeCallbacksAndMessages(null)
        serviceScope.cancel()
        weatherView?.stopAnimation()
        try {
            weatherHost?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove weather window", e)
        }
        weatherHost = null
        weatherView = null
        if (instance === this) instance = null
    }

    companion object {
        private const val TAG = "WeatherOverlayService"
        private var instance: WeatherOverlayService? = null
        
        const val ACTION_SHOW = "com.example.ava.SHOW_WEATHER"
        const val ACTION_HIDE = "com.example.ava.HIDE_WEATHER"
        
        fun isOverlayShowing(): Boolean = instance?.let { it.isWeatherEnabled && it.isWeatherVisible } == true

        fun bringToFrontStatic() {
            instance?.bringToFront()
        }

        fun show(context: Context) {
            val svc = instance
            if (svc != null && svc.isWeatherEnabled && svc.isWeatherVisible) return
            val intent = Intent(context, WeatherOverlayService::class.java).apply {
                action = ACTION_SHOW
            }
            context.startService(intent)
        }

        fun hide(context: Context) {
            val intent = Intent(context, WeatherOverlayService::class.java).apply {
                action = ACTION_HIDE
            }
            context.startService(intent)
        }
        
        const val ACTION_SET_VISIBLE = "com.example.ava.SET_WEATHER_VISIBLE"
        const val EXTRA_VISIBLE = "visible"
        
        fun setVisible(context: Context, visible: Boolean) {
            val intent = Intent(context, WeatherOverlayService::class.java).apply {
                action = ACTION_SET_VISIBLE
                putExtra(EXTRA_VISIBLE, visible)
            }
            context.startService(intent)
        }
    }
}


class WeatherOverlayView(
    context: Context,
    private var screenWidth: Int,
    private var screenHeight: Int
) : View(context) {

    init {
        
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1) {
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }
    }

    
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            screenWidth = w
            screenHeight = h
            initParticles()
        }
    }

    
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val parentWidth = MeasureSpec.getSize(widthMeasureSpec)
        val parentHeight = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(
            if (parentWidth > 0) parentWidth else screenWidth,
            if (parentHeight > 0) parentHeight else screenHeight
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false

    
    private var temperature = 0
    private var condition = ""
    private var humidity = 0
    private var windSpeed = 0f
    private var windDirection = ""    
    private var windDirectionEn = ""  
    private var aqi = 0
    private var pm25 = 0
    private var visibility = 0f
    private var pressure = 0f
    private var cityName = ""
    private var isDay = true

    
    private val particles = mutableListOf<Particle>()
    private val maxParticles: Int
        get() = when (condition) {
            "heavy_rain", "sandstorm" -> 100
            "light_rain", "heavy_snow" -> 60
            "light_snow" -> 35
            "wind" -> 30
            "fog" -> 30
            "haze" -> 50
            else -> 40
        }

    
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dashboardPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    
    private val oswaldLight: Typeface = try {
        androidx.core.content.res.ResourcesCompat.getFont(context, com.example.ava.R.font.oswald_light)
            ?: Typeface.create("sans-serif-light", Typeface.NORMAL)
    } catch (e: Exception) {
        Typeface.create("sans-serif-light", Typeface.NORMAL)
    }
    private val rajdhaniSemibold: Typeface = try {
        androidx.core.content.res.ResourcesCompat.getFont(context, com.example.ava.R.font.rajdhani_semibold)
            ?: Typeface.create("sans-serif", Typeface.BOLD)
    } catch (e: Exception) {
        Typeface.create("sans-serif", Typeface.BOLD)
    }
    private val labelTypeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private var haLogoBitmap: Bitmap? = null

    private fun haLogoBitmap(): Bitmap? {
        haLogoBitmap?.let { if (!it.isRecycled) return it }
        haLogoBitmap = OverlayLogoBadge.loadHaLogo(context)
        return haLogoBitmap
    }

    fun updateWeather(data: com.example.ava.weather.WeatherData) {
        val oldCondition = condition
        
        temperature = data.temperature
        condition = data.condition
        humidity = data.humidity
        windSpeed = data.windSpeed
        windDirection = data.windDirection
        windDirectionEn = data.windDirectionEn
        aqi = data.aqi
        pm25 = data.pm25
        visibility = data.visibility
        pressure = data.pressure
        cityName = data.city
        
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        isDay = hour in 6..18
        
        if (oldCondition != condition || particles.isEmpty()) {
            initParticles()
        }
        postInvalidate()
    }

    private fun initParticles() {
        particles.clear()
        repeat(maxParticles) {
            particles.add(createParticle(true))
        }
    }

    private fun createParticle(initial: Boolean): Particle {
        val p = Particle()
        p.x = Random.nextFloat() * screenWidth
        p.y = if (initial) Random.nextFloat() * screenHeight else -20f
        p.opacity = Random.nextFloat() * 0.5f + 0.1f

        when (condition) {
            "sunny", "cloudy" -> {
                p.vx = (Random.nextFloat() - 0.5f) * 0.3f
                p.vy = (Random.nextFloat() - 0.5f) * 0.3f
                p.radius = Random.nextFloat() * 1.5f
                p.isLine = false
            }
            "rainy", "light_rain" -> {
                p.vx = 0.3f
                p.vy = 4f + Random.nextFloat() * 4f
                p.length = 8f + Random.nextFloat() * 8f
                p.isLine = true
            }
            "heavy_rain" -> {
                p.vx = 0.8f
                p.vy = 10f + Random.nextFloat() * 10f
                p.length = 18f + Random.nextFloat() * 15f
                p.isLine = true
            }
            "snowy", "light_snow" -> {
                p.vx = (Random.nextFloat() - 0.5f) * 1f
                p.vy = 0.8f + Random.nextFloat() * 1.2f
                p.radius = 1f + Random.nextFloat() * 2f
                p.isLine = false
            }
            "heavy_snow" -> {
                p.vx = (Random.nextFloat() - 0.5f) * 1.5f
                p.vy = 1.5f + Random.nextFloat() * 2.5f
                p.radius = 2f + Random.nextFloat() * 3f
                p.isLine = false
            }
            "sandstorm" -> {
                p.x = if (initial) Random.nextFloat() * screenWidth else -50f
                p.y = Random.nextFloat() * screenHeight
                p.vx = 4f + Random.nextFloat() * 4f
                p.vy = (Random.nextFloat() - 0.5f) * 1.5f
                p.radius = 1f + Random.nextFloat() * 1.5f
                p.isLine = false
            }
            "wind" -> {
                p.x = if (initial) Random.nextFloat() * screenWidth else -50f
                p.y = Random.nextFloat() * screenHeight
                p.vx = 6f + Random.nextFloat() * 4f
                p.vy = (Random.nextFloat() - 0.5f) * 0.3f
                p.length = 30f + Random.nextFloat() * 20f
                p.isLine = true
            }
            "fog" -> {
                p.vx = (Random.nextFloat() - 0.5f) * 0.5f
                p.vy = (Random.nextFloat() - 0.5f) * 0.3f
                p.radius = 3f + Random.nextFloat() * 5f
                p.isLine = false
            }
            "haze" -> {
                p.vx = (Random.nextFloat() - 0.5f) * 0.6f
                p.vy = (Random.nextFloat() - 0.5f) * 0.4f
                p.radius = 1.5f + Random.nextFloat() * 2.5f
                p.isLine = false
            }
        }
        return p
    }

    fun startAnimation() {
        if (isRunning) return
        isRunning = true
        initParticles()
        scheduleUpdate()
    }

    fun stopAnimation() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
    }

    private fun scheduleUpdate() {
        if (!isRunning) return
        handler.postDelayed({
            updateParticles()
            invalidate()
            scheduleUpdate()
        }, frameIntervalMs())
    }
    
    private fun frameIntervalMs(): Long {
        return when (condition) {
            "heavy_rain", "sandstorm" -> 33L
            "wind" -> 24L
            else -> 16L
        }
    }

    private fun updateParticles() {
        particles.forEach { p ->
            p.x += p.vx
            p.y += p.vy
            if (p.x > screenWidth + 100 || p.x < -100 || p.y > screenHeight + 20) {
                val newP = createParticle(false)
                p.x = newP.x
                p.y = newP.y
                p.vx = newP.vx
                p.vy = newP.vy
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        if (width > 0 && height > 0 && (width != screenWidth || height != screenHeight)) {
            screenWidth = width
            screenHeight = height
            initParticles()
        }
        
        drawSkyBackground(canvas)
        
        
        drawExposureLayer(canvas)
        
        
        if (condition == "sunny") {
            drawSunAssembly(canvas)
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            if (hour in 6..8) {
                drawMorningBubbles(canvas)
            }
        } else if (condition == "clear_night") {
            drawMoonGlow(canvas)
        }
        
        if (condition in listOf("cloudy", "partly_cloudy", "light_rain", "heavy_rain", "light_snow", "heavy_snow", "fog")) {
            drawClouds(canvas)
        }
        
        
                
        
        drawParticles(canvas)
        
        
        drawUILayer(canvas)
    }

    private fun drawSkyBackground(canvas: Canvas) {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val isVeryDeepNight = hour in 0..3 || hour >= 23
        val isDeepNight = hour in 4..4 || hour in 22..22
        val isNight = hour in 5..5 || hour in 19..21
        val isMorning = hour in 6..8
        val isEvening = hour in 17..18
        
        val colors = when (condition) {
            "sunny" -> when {
                isMorning -> intArrayOf(Color.parseColor("#7dd3fc"), Color.parseColor("#38bdf8"))
                isEvening -> intArrayOf(Color.parseColor("#0ea5e9"), Color.parseColor("#0284c7"))
                else -> intArrayOf(Color.parseColor("#0284c7"), Color.parseColor("#0369a1"))
            }
            "clear_night" -> when {
                isVeryDeepNight -> intArrayOf(Color.parseColor("#000308"), Color.parseColor("#020610"))
                isDeepNight -> intArrayOf(Color.parseColor("#010410"), Color.parseColor("#050918"))
                else -> intArrayOf(Color.parseColor("#0a1628"), Color.parseColor("#0f1d35"))
            }
            "cloudy" -> when {
                isVeryDeepNight -> intArrayOf(Color.parseColor("#0a0f18"), Color.parseColor("#050810"))
                isDeepNight -> intArrayOf(Color.parseColor("#1e293b"), Color.parseColor("#0f172a"))
                isNight -> intArrayOf(Color.parseColor("#334155"), Color.parseColor("#1e293b"))
                else -> intArrayOf(Color.parseColor("#0ea5e9"), Color.parseColor("#0284c7"))
            }
            "partly_cloudy" -> when {
                isVeryDeepNight -> intArrayOf(Color.parseColor("#050810"), Color.parseColor("#020408"))
                isDeepNight -> intArrayOf(Color.parseColor("#0f172a"), Color.parseColor("#020617"))
                isNight -> intArrayOf(Color.parseColor("#1e3a5f"), Color.parseColor("#0f172a"))
                else -> intArrayOf(Color.parseColor("#38bdf8"), Color.parseColor("#0ea5e9"))
            }
            "rainy", "light_rain" -> when {
                isDeepNight -> intArrayOf(Color.parseColor("#334155"), Color.parseColor("#1e293b"))
                else -> intArrayOf(Color.parseColor("#64748b"), Color.parseColor("#475569"))
            }
            "heavy_rain" -> when {
                isVeryDeepNight -> intArrayOf(Color.parseColor("#1a1f2e"), Color.parseColor("#0f1318"))
                isDeepNight -> intArrayOf(Color.parseColor("#252d3a"), Color.parseColor("#1a2028"))
                isNight -> intArrayOf(Color.parseColor("#334155"), Color.parseColor("#1e293b"))
                else -> intArrayOf(Color.parseColor("#475569"), Color.parseColor("#334155"))
            }
            "snowy", "light_snow", "heavy_snow" -> when {
                isVeryDeepNight -> intArrayOf(Color.parseColor("#2a2f3a"), Color.parseColor("#1a1f28"))
                isDeepNight -> intArrayOf(Color.parseColor("#3a4050"), Color.parseColor("#2a3040"))
                isNight -> intArrayOf(Color.parseColor("#64748b"), Color.parseColor("#475569"))
                else -> intArrayOf(Color.parseColor("#94a3b8"), Color.parseColor("#64748b"))
            }
            "fog" -> when {
                isVeryDeepNight -> intArrayOf(Color.parseColor("#1a1a2e"), Color.parseColor("#16213e"))
                isDeepNight -> intArrayOf(Color.parseColor("#2d3436"), Color.parseColor("#1e272e"))
                isNight -> intArrayOf(Color.parseColor("#4a5568"), Color.parseColor("#2d3748"))
                else -> intArrayOf(Color.parseColor("#d1d5db"), Color.parseColor("#9ca3af"))
            }
            "haze" -> when {
                isVeryDeepNight -> intArrayOf(Color.parseColor("#1a1510"), Color.parseColor("#0f0d08"))
                isDeepNight -> intArrayOf(Color.parseColor("#3d3428"), Color.parseColor("#2a2318"))
                isNight -> intArrayOf(Color.parseColor("#5c5346"), Color.parseColor("#3d3428"))
                else -> intArrayOf(Color.parseColor("#a8a29e"), Color.parseColor("#78716c"))
            }
            "wind" -> when {
                isVeryDeepNight -> intArrayOf(Color.parseColor("#0a1520"), Color.parseColor("#050a10"))
                isDeepNight -> intArrayOf(Color.parseColor("#1a2530"), Color.parseColor("#0f1820"))
                isNight -> intArrayOf(Color.parseColor("#2c3e50"), Color.parseColor("#1a2530"))
                else -> intArrayOf(Color.parseColor("#94a3b8"), Color.parseColor("#64748b"))
            }
            "sandstorm" -> when {
                isVeryDeepNight -> intArrayOf(Color.parseColor("#1a1510"), Color.parseColor("#0f0a05"))
                isDeepNight -> intArrayOf(Color.parseColor("#2a2015"), Color.parseColor("#1a1510"))
                isNight -> intArrayOf(Color.parseColor("#3e2723"), Color.parseColor("#2a2015"))
                else -> intArrayOf(Color.parseColor("#5d4037"), Color.parseColor("#3e2723"))
            }
            else -> when {
                isDeepNight -> intArrayOf(Color.parseColor("#020617"), Color.parseColor("#0f0a1e"))
                isNight -> intArrayOf(Color.parseColor("#0f172a"), Color.parseColor("#1e1b4b"))
                else -> intArrayOf(Color.parseColor("#0284c7"), Color.parseColor("#0369a1"))
            }
        }
        
        bgPaint.shader = LinearGradient(
            0f, 0f, 0f, screenHeight.toFloat(),
            colors, null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, screenWidth.toFloat(), screenHeight.toFloat(), bgPaint)
        
    }
    
    
    private fun drawSunnyLightBlobs(canvas: Canvas) {
        val cloudPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        cloudPaint.style = Paint.Style.FILL
        
        cloudPaint.maskFilter = android.graphics.BlurMaskFilter(
            screenWidth * 0.08f, android.graphics.BlurMaskFilter.Blur.NORMAL
        )
        cloudPaint.color = Color.argb(100, 255, 255, 255)  
        
        
        
        cloudPaint.alpha = 100
        canvas.drawCircle(screenWidth * 0.25f, screenHeight * 0.12f, screenWidth * 0.2f, cloudPaint)
        
        
        cloudPaint.alpha = 130
        canvas.drawCircle(screenWidth * 0.5f, screenHeight * 0.08f, screenWidth * 0.28f, cloudPaint)
        
        
        cloudPaint.alpha = 80
        canvas.drawCircle(screenWidth * 0.68f, screenHeight * 0.15f, screenWidth * 0.15f, cloudPaint)
        
        
        
        cloudPaint.alpha = 80
        canvas.drawCircle(screenWidth * 0.85f, screenHeight * 0.45f, screenWidth * 0.22f, cloudPaint)
        
        
        cloudPaint.alpha = 100
        canvas.drawCircle(screenWidth * 0.55f, screenHeight * 0.5f, screenWidth * 0.3f, cloudPaint)
        
        
        cloudPaint.maskFilter = null
    }
    
    private fun drawMorningBubbles(canvas: Canvas) {
        val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        bubblePaint.style = Paint.Style.FILL
        
        val vmin = minOf(screenWidth, screenHeight).toFloat()
        val time = System.currentTimeMillis()
        
        val bubbles = listOf(
            Triple(0.15f, 0.7f, 0.02f),
            Triple(0.25f, 0.8f, 0.015f),
            Triple(0.35f, 0.75f, 0.018f),
            Triple(0.55f, 0.85f, 0.012f),
            Triple(0.65f, 0.72f, 0.016f),
            Triple(0.75f, 0.78f, 0.014f),
            Triple(0.85f, 0.82f, 0.02f),
            Triple(0.45f, 0.9f, 0.01f),
            Triple(0.2f, 0.65f, 0.013f),
            Triple(0.8f, 0.68f, 0.017f)
        )
        
        for ((i, bubble) in bubbles.withIndex()) {
            val baseX = bubble.first
            val baseY = bubble.second
            val radius = bubble.third
            
            val speed = 0.00002f + (i % 3) * 0.00001f
            val yOffset = ((time * speed) % 1f)
            val y = (baseY - yOffset + 1f) % 1f
            
            val xOffset = (Math.sin((time / 2000.0) + i) * 0.02f).toFloat()
            val x = baseX + xOffset
            
            val alpha = (40 + (i % 4) * 10)
            bubblePaint.color = Color.argb(alpha, 255, 255, 255)
            bubblePaint.maskFilter = BlurMaskFilter(vmin * 0.01f, BlurMaskFilter.Blur.NORMAL)
            
            canvas.drawCircle(screenWidth * x, screenHeight * y, vmin * radius, bubblePaint)
            
            bubblePaint.color = Color.argb(alpha / 2, 255, 255, 255)
            canvas.drawCircle(
                screenWidth * x - vmin * radius * 0.3f,
                screenHeight * y - vmin * radius * 0.3f,
                vmin * radius * 0.3f,
                bubblePaint
            )
        }
        
        bubblePaint.maskFilter = null
    }

    private fun drawExposureLayer(canvas: Canvas) {
        if (condition == "sunny" || condition == "clear_night") {
            return
        }
        
        val vmin = minOf(screenWidth, screenHeight).toFloat()
        
        if (isDay) {
            glowPaint.shader = RadialGradient(
                vmin * 0.2f, vmin * 0.2f, vmin * 0.5f,
                intArrayOf(Color.argb(40, 255, 255, 255), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, screenWidth.toFloat(), screenHeight.toFloat(), glowPaint)
        } else {
            val cx = screenWidth / 2f
            val cy = screenHeight / 2f
            val radius = maxOf(screenWidth, screenHeight) * 0.7f
            
            glowPaint.shader = RadialGradient(
                cx, cy, radius,
                intArrayOf(Color.TRANSPARENT, Color.argb(80, 0, 0, 0)),
                floatArrayOf(0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, screenWidth.toFloat(), screenHeight.toFloat(), glowPaint)
        }
    }

    private var sunAnimTime = 0L
    
    private fun drawSunAssembly(canvas: Canvas) {
        val vmin = minOf(screenWidth, screenHeight).toFloat()
        val sunX = -vmin * 0.4f
        val sunY = -vmin * 0.4f
        
        sunAnimTime = System.currentTimeMillis()
        val breathPhase = (sunAnimTime % 12000) / 12000.0
        val breathScale = 1f + (Math.sin(breathPhase * Math.PI * 2) * 0.12f).toFloat()
        
        val sunRadius = vmin * 2.5f * breathScale
        
        glowPaint.shader = RadialGradient(
            sunX, sunY, sunRadius,
            intArrayOf(
                Color.argb(80, 255, 250, 210),
                Color.argb(45, 255, 240, 190),
                Color.argb(20, 255, 235, 170),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.2f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(sunX, sunY, sunRadius, glowPaint)
    }
    
    private var moonAnimTime = 0L
    
    private fun drawMoonGlow(canvas: Canvas) {
        val vmin = minOf(screenWidth, screenHeight).toFloat()
        val moonX = -vmin * 0.4f
        val moonY = -vmin * 0.4f
        
        moonAnimTime = System.currentTimeMillis()
        val breathPhase = (moonAnimTime % 12000) / 12000.0
        val breathScale = 1f + (Math.sin(breathPhase * Math.PI * 2) * 0.12f).toFloat()
        
        val moonRadius = vmin * 2.5f * breathScale
        
        glowPaint.shader = RadialGradient(
            moonX, moonY, moonRadius,
            intArrayOf(
                Color.argb(70, 255, 245, 190),
                Color.argb(40, 255, 240, 170),
                Color.argb(15, 255, 235, 150),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.2f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(moonX, moonY, moonRadius, glowPaint)
    }

    
    private var cloudAnimTime = 0L
    
    private fun drawClouds(canvas: Canvas) {
        val cloudAlpha = when (condition) {
            "cloudy" -> 55
            "partly_cloudy" -> 45
            "light_rain", "light_snow" -> 40
            "heavy_rain", "heavy_snow" -> 35
            "fog" -> 55
            else -> 40
        }
        
        
        cloudAnimTime = System.currentTimeMillis()
        
        
        val t1 = cloudAnimTime / 8000.0  
        val t2 = cloudAnimTime / 12000.0 
        val t3 = cloudAnimTime / 15000.0 
        
        val offset1X = (Math.sin(t1) * 30 + Math.sin(t1 * 1.7) * 15).toFloat()
        val offset1Y = (Math.sin(t1 * 0.8) * 10 + Math.cos(t1 * 1.3) * 8).toFloat()
        
        val offset2X = (Math.sin(t2 + 1.5) * 35 + Math.cos(t2 * 1.4) * 18).toFloat()
        val offset2Y = (Math.cos(t2 * 0.9) * 12 + Math.sin(t2 * 1.1) * 6).toFloat()
        
        val offset3X = (Math.sin(t3 + 3.0) * 25 + Math.sin(t3 * 1.2) * 20).toFloat()
        val offset3Y = (Math.cos(t3 * 0.7) * 8 + Math.sin(t3 * 1.5) * 10).toFloat()
        
        
        val vmin = minOf(screenWidth, screenHeight).toFloat()
        val cloudPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        cloudPaint.style = Paint.Style.FILL
        cloudPaint.maskFilter = BlurMaskFilter(vmin * 0.08f, BlurMaskFilter.Blur.NORMAL)
        
        
        cloudPaint.shader = LinearGradient(
            0f, 0f, 0f, screenHeight * 0.25f,
            intArrayOf(Color.argb(cloudAlpha, 255, 255, 255), Color.argb(cloudAlpha / 3, 255, 255, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.save()
        canvas.translate(offset1X, offset1Y)
        canvas.drawRect(-30f, -20f, screenWidth * 0.45f + 30f, screenHeight * 0.25f, cloudPaint)
        canvas.restore()
        
        
        cloudPaint.shader = LinearGradient(
            0f, 0f, 0f, screenHeight * 0.3f,
            intArrayOf(Color.argb(cloudAlpha, 255, 255, 255), Color.argb(cloudAlpha / 3, 255, 255, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.save()
        canvas.translate(offset2X, offset2Y)
        canvas.drawRect(screenWidth * 0.5f - 30f, -20f, screenWidth + 30f, screenHeight * 0.3f, cloudPaint)
        canvas.restore()
        
        
        cloudPaint.shader = LinearGradient(
            0f, screenHeight * 0.1f, 0f, screenHeight * 0.35f,
            intArrayOf(Color.argb(cloudAlpha / 2, 255, 255, 255), Color.argb(cloudAlpha / 4, 255, 255, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.save()
        canvas.translate(offset3X, offset3Y)
        canvas.drawRect(screenWidth * 0.2f - 30f, screenHeight * 0.1f - 20f, screenWidth * 0.8f + 30f, screenHeight * 0.35f, cloudPaint)
        canvas.restore()
        
        if (condition == "cloudy" || condition == "partly_cloudy") {
            val extraAlpha = if (condition == "cloudy") cloudAlpha else cloudAlpha * 4 / 5
            

            val t4 = cloudAnimTime / 10000.0
            val offset4X = (Math.sin(t4 + 2.0) * 20 + Math.cos(t4 * 1.3) * 15).toFloat()
            val offset4Y = (Math.cos(t4 * 0.6) * 8).toFloat()
            
            cloudPaint.shader = LinearGradient(
                0f, -screenHeight * 0.05f, 0f, screenHeight * 0.45f,
                intArrayOf(Color.argb(extraAlpha, 245, 245, 250), Color.argb(extraAlpha * 2 / 3, 235, 235, 240), Color.argb(extraAlpha / 3, 225, 225, 230), Color.TRANSPARENT),
                floatArrayOf(0f, 0.3f, 0.6f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.save()
            canvas.translate(offset4X, offset4Y)
            canvas.drawRect(-50f, -50f, screenWidth * 0.55f, screenHeight * 0.45f, cloudPaint)
            canvas.restore()
            

            val t5 = cloudAnimTime / 14000.0
            val offset5X = (Math.cos(t5 + 1.0) * 25 + Math.sin(t5 * 1.1) * 12).toFloat()
            val offset5Y = (Math.sin(t5 * 0.5) * 6).toFloat()
            
            cloudPaint.shader = LinearGradient(
                0f, -screenHeight * 0.03f, 0f, screenHeight * 0.4f,
                intArrayOf(Color.argb(extraAlpha, 240, 240, 248), Color.argb(extraAlpha * 2 / 3, 230, 230, 238), Color.argb(extraAlpha / 4, 220, 220, 228), Color.TRANSPARENT),
                floatArrayOf(0f, 0.35f, 0.65f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.save()
            canvas.translate(offset5X, offset5Y)
            canvas.drawRect(screenWidth * 0.35f, -50f, screenWidth + 50f, screenHeight * 0.4f, cloudPaint)
            canvas.restore()
            

            val t6 = cloudAnimTime / 18000.0
            val offset6X = (Math.sin(t6 + 3.5) * 30 + Math.cos(t6 * 0.9) * 18).toFloat()
            val offset6Y = (Math.cos(t6 * 0.4) * 5).toFloat()
            
            cloudPaint.shader = LinearGradient(
                0f, screenHeight * 0.1f, 0f, screenHeight * 0.5f,
                intArrayOf(Color.argb(extraAlpha * 3 / 4, 238, 238, 245), Color.argb(extraAlpha / 2, 228, 228, 235), Color.TRANSPARENT),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.save()
            canvas.translate(offset6X, offset6Y)
            canvas.drawRect(screenWidth * 0.15f, screenHeight * 0.08f, screenWidth * 0.85f, screenHeight * 0.5f, cloudPaint)
            canvas.restore()
            

            val t7 = cloudAnimTime / 22000.0
            val offset7X = (Math.cos(t7 + 0.5) * 15).toFloat()
            val offset7Y = (Math.sin(t7 * 0.3) * 4).toFloat()
            
            cloudPaint.shader = LinearGradient(
                0f, screenHeight * 0.25f, 0f, screenHeight * 0.55f,
                intArrayOf(Color.argb(extraAlpha / 2, 235, 235, 242), Color.argb(extraAlpha / 4, 225, 225, 232), Color.TRANSPARENT),
                floatArrayOf(0f, 0.4f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.save()
            canvas.translate(offset7X, offset7Y)
            canvas.drawRect(-30f, screenHeight * 0.2f, screenWidth + 30f, screenHeight * 0.55f, cloudPaint)
            canvas.restore()
        }
        
        cloudPaint.maskFilter = null
    }

    private fun drawCoreGlow(canvas: Canvas) {
        val cx = screenWidth / 2f
        val cy = screenHeight / 2f
        val vmin = minOf(screenWidth, screenHeight).toFloat()
        
        val glowColor = when (condition) {
            "sunny" -> if (isDay) intArrayOf(Color.argb(80, 255, 230, 200), Color.argb(40, 255, 240, 220), Color.TRANSPARENT)
                       else intArrayOf(Color.argb(50, 220, 230, 255), Color.argb(25, 200, 210, 240), Color.TRANSPARENT)
            "rainy", "light_rain", "heavy_rain" -> intArrayOf(Color.argb(60, 100, 150, 255), Color.argb(30, 120, 170, 255), Color.TRANSPARENT)
            "snowy", "light_snow", "heavy_snow" -> intArrayOf(Color.argb(70, 255, 255, 255), Color.argb(35, 240, 245, 255), Color.TRANSPARENT)
            "cloudy" -> intArrayOf(Color.argb(60, 200, 230, 255), Color.argb(30, 210, 235, 255), Color.TRANSPARENT)
            "wind" -> intArrayOf(Color.argb(50, 150, 255, 220), Color.argb(25, 170, 255, 230), Color.TRANSPARENT)
            "sandstorm" -> intArrayOf(Color.argb(60, 255, 180, 100), Color.argb(30, 255, 200, 130), Color.TRANSPARENT)
            "haze" -> intArrayOf(Color.argb(50, 180, 160, 130), Color.argb(25, 190, 170, 140), Color.TRANSPARENT)
            "fog" -> intArrayOf(Color.argb(60, 220, 220, 220), Color.argb(30, 230, 230, 230), Color.TRANSPARENT)
            else -> if (isDay) intArrayOf(Color.argb(60, 255, 255, 255), Color.argb(30, 255, 255, 255), Color.TRANSPARENT)
                    else intArrayOf(Color.argb(50, 220, 230, 255), Color.argb(25, 200, 210, 240), Color.TRANSPARENT)
        }
        
        val radius = if (!isDay && (condition == "sunny" || condition == "cloudy" || condition.isEmpty())) vmin * 0.35f else vmin * 0.32f
        val blurRadius = vmin * 0.14f
        
        val stops = floatArrayOf(0f, 0.5f, 1f)
        glowPaint.shader = RadialGradient(cx, cy, radius, glowColor, stops, Shader.TileMode.CLAMP)
        glowPaint.maskFilter = BlurMaskFilter(blurRadius, BlurMaskFilter.Blur.NORMAL)
        canvas.drawCircle(cx, cy, radius, glowPaint)
        glowPaint.maskFilter = null
    }

    private fun drawParticles(canvas: Canvas) {
        particles.forEach { p ->
            val color = when (condition) {
                "sunny", "cloudy" -> Color.argb((p.opacity * 255).toInt(), 255, 255, 255)
                "rainy", "light_rain" -> Color.argb((p.opacity * 0.7f * 255).toInt(), 180, 210, 255)
                "heavy_rain" -> Color.argb((p.opacity * 255).toInt(), 160, 200, 255)
                "snowy", "light_snow" -> Color.argb((p.opacity * 0.8f * 255).toInt(), 255, 255, 255)
                "heavy_snow" -> Color.argb((p.opacity * 255).toInt(), 255, 255, 255)
                "sandstorm" -> Color.argb((p.opacity * 255).toInt(), 210, 160, 100)
                "wind" -> Color.argb((p.opacity * 0.4f * 255).toInt(), 255, 255, 255)
                "fog" -> Color.argb((p.opacity * 0.3f * 255).toInt(), 255, 255, 255)
                "haze" -> Color.argb((p.opacity * 0.5f * 255).toInt(), 180, 160, 130)
                else -> Color.argb((p.opacity * 255).toInt(), 255, 255, 255)
            }
            
            particlePaint.color = color
            if (p.isLine) {
                particlePaint.style = Paint.Style.STROKE
                particlePaint.strokeWidth = 1f
                val endY = if (condition == "wind") p.y + p.vy * 3 else p.y + p.length
                val endX = if (condition == "wind") p.x + p.length else p.x + p.vx
                canvas.drawLine(p.x, p.y, endX, endY, particlePaint)
            } else {
                particlePaint.style = Paint.Style.FILL
                canvas.drawCircle(p.x, p.y, p.radius, particlePaint)
            }
        }
    }

    private fun drawUILayer(canvas: Canvas) {
        val actualWidth = width
        val actualHeight = height
        val squarePanel = com.example.ava.ui.isCompactSquarePixels(
            maxOf(actualWidth, actualHeight),
            minOf(actualWidth, actualHeight),
        )
        val isSmallSquareScreen = squarePanel && minOf(actualWidth, actualHeight) <= 500
        val isLandscape = !squarePanel && actualWidth > actualHeight * 1.2f
        val vmin = minOf(actualWidth, actualHeight).toFloat()
        val padding = if (isSmallSquareScreen) actualWidth * 0.1f else actualWidth * 0.06f
        
        
        val maskPaint = Paint()
        maskPaint.shader = LinearGradient(
            0f, actualHeight * 0.75f, 0f, actualHeight.toFloat(),
            Color.TRANSPARENT, Color.argb(153, 0, 0, 0),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, actualHeight * 0.75f, actualWidth.toFloat(), actualHeight.toFloat(), maskPaint)
        
        
        if (isDay) {
            textPaint.setShadowLayer(8f, 0f, 2f, Color.argb(77, 0, 0, 0))
        } else {
            textPaint.setShadowLayer(4f, 0f, 1f, Color.argb(50, 0, 0, 0))
        }
        
        

        val logoLayout = OverlayLogoBadge.layoutPx(this)
        haLogoBitmap()?.let { bitmap ->
            OverlayLogoBadge.draw(canvas, actualWidth, bitmap, logoLayout)
        }
        
        
        val tempY = if (isSmallSquareScreen) actualHeight * 0.45f - 40f else actualHeight * 0.35f - 20f
        val tempSize = if (isSmallSquareScreen) vmin * 0.55f else vmin * 0.4f
        
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = oswaldLight
        textPaint.textSize = tempSize
        textPaint.letterSpacing = -0.03f
        textPaint.color = Color.WHITE
        
        val tempText = "$temperature"
        
        val digitCount = tempText.replace("-", "").length + (if (temperature < 0) 1 else 0)
        val offsetX = if (isSmallSquareScreen && digitCount > 1) vmin * 0.04f * (digitCount - 1) else 0f
        val tempX = actualWidth / 2f - offsetX
        canvas.drawText(tempText, tempX, tempY + textPaint.textSize * 0.35f, textPaint)
        
        
        val tempBounds = android.graphics.Rect()
        textPaint.getTextBounds(tempText, 0, tempText.length, tempBounds)
        val tempHalfWidth = tempBounds.width() / 2f
        
        val unitSize = if (isSmallSquareScreen) vmin * 0.12f else vmin * 0.08f
        textPaint.textSize = unitSize
        textPaint.typeface = oswaldLight
        textPaint.alpha = 128
        val unitX = tempX + tempHalfWidth + vmin * 0.03f
        val unitY = tempY - vmin * 0.05f
        canvas.drawText("°", unitX, unitY, textPaint)
        textPaint.alpha = 255
        
        
        drawDashboard(canvas, padding, isSmallSquareScreen, actualWidth, actualHeight)
    }

    private fun drawDashboard(canvas: Canvas, padding: Float, isSmallSquareScreen: Boolean, actualWidth: Int, actualHeight: Int) {
        val vmin = minOf(actualWidth, actualHeight).toFloat()
        val isLandscape = !isSmallSquareScreen && actualWidth > actualHeight * 1.2f
        
        
        val gridLeft = padding
        val gridRight = actualWidth - padding
        val gridWidth = gridRight - gridLeft
        val cellWidth = gridWidth / 3f
        val cellHeight = if (isSmallSquareScreen) vmin * 0.14f else vmin * 0.16f
        val rowGap = if (isSmallSquareScreen) vmin * 0.02f else vmin * 0.03f
        
        
        val gridHeight = cellHeight * 2 + rowGap
        val bottomPadding = when {
            isSmallSquareScreen -> padding + 10f
            isLandscape -> padding
            else -> actualHeight * 0.08f
        }
        val gridBottom = actualHeight - bottomPadding
        val gridTop = gridBottom - gridHeight
        
        
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        linePaint.style = Paint.Style.STROKE
        linePaint.strokeWidth = 1f
        
        
        val lineOffset = 5f
        
        
        val midY = gridTop + cellHeight + rowGap / 2 + lineOffset
        val hLineMargin = gridWidth * 0.08f
        linePaint.shader = LinearGradient(
            gridLeft + hLineMargin, midY, gridRight - hLineMargin, midY,
            intArrayOf(Color.TRANSPARENT, Color.argb(20, 255, 255, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawLine(gridLeft + hLineMargin, midY, gridRight - hLineMargin, midY, linePaint)
        
        
        val vLineMargin = cellHeight * 0.35f
        for (i in 1..2) {
            val lineX = gridLeft + cellWidth * i
            val lineTop = gridTop + vLineMargin + lineOffset
            val lineBottom = gridBottom - vLineMargin + lineOffset
            linePaint.shader = LinearGradient(
                lineX, lineTop, lineX, lineBottom,
                intArrayOf(Color.TRANSPARENT, Color.argb(25, 255, 255, 255), Color.TRANSPARENT),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawLine(lineX, lineTop, lineX, lineBottom, linePaint)
        }
        
        
        val cards = listOf(
            Triple(context.getString(R.string.weather_humidity), "$humidity", "%"),
            Triple(context.getString(R.string.weather_air), "$aqi", "AQI"),
            Triple(context.getString(R.string.weather_pressure), "${pressure.toInt()}", "hPa"),
            Triple("PM2.5", "$pm25", "μg"),
            Triple(context.getString(R.string.weather_wind_speed), String.format("%.1f", windSpeed), "km/h"),
            Triple(context.getString(R.string.weather_wind_direction), getLocalizedWindDirection(windDirectionEn.ifEmpty { "N" }), "N")
        )
        
        cards.forEachIndexed { index, (label, value, unit) ->
            val col = index % 3
            val row = index / 3
            val cellX = gridLeft + col * cellWidth + cellWidth / 2
            val cellY = gridTop + row * (cellHeight + rowGap) + cellHeight / 2
            
            drawGridCell(canvas, cellX, cellY, label, value, unit, vmin, isSmallSquareScreen)
        }
    }
    
    private fun drawGridCell(canvas: Canvas, x: Float, y: Float, label: String, value: String, unit: String, vmin: Float, isSmall: Boolean) {
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.setShadowLayer(4f, 0f, 2f, Color.argb(100, 0, 0, 0))
        
        
        val isChinese = value.any { it.code > 0x4E00 && it.code < 0x9FFF }
        
        
        if (isChinese) {
            
            textPaint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            textPaint.textSize = if (isSmall) vmin * 0.045f else vmin * 0.05f
        } else {
            textPaint.typeface = rajdhaniSemibold
            textPaint.textSize = if (isSmall) vmin * 0.055f else vmin * 0.06f
        }
        textPaint.color = Color.WHITE
        textPaint.alpha = 255
        
        val valueY = y - vmin * 0.005f
        canvas.drawText(value, x, valueY, textPaint)
        
        
        if (unit.isNotEmpty()) {
            textPaint.textSize = if (isSmall) vmin * 0.022f else vmin * 0.026f
            textPaint.alpha = 100
            val unitY = if (isChinese) y + vmin * 0.038f else y + vmin * 0.032f
            canvas.drawText(unit, x, unitY, textPaint)
        }
        
        
        textPaint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        textPaint.textSize = if (isSmall) vmin * 0.025f else vmin * 0.03f
        textPaint.alpha = 100
        canvas.drawText(label, x, y + vmin * 0.07f, textPaint)
        textPaint.alpha = 255
        textPaint.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
    }

    private data class Particle(
        var x: Float = 0f,
        var y: Float = 0f,
        var vx: Float = 0f,
        var vy: Float = 0f,
        var radius: Float = 1f,
        var length: Float = 10f,
        var opacity: Float = 0.5f,
        var isLine: Boolean = false
    )
    
    
    private fun getLocalizedWindDirection(dirEn: String): String {
        return when (dirEn) {
            "N" -> context.getString(R.string.weather_wind_n)
            "NE" -> context.getString(R.string.weather_wind_ne)
            "E" -> context.getString(R.string.weather_wind_e)
            "SE" -> context.getString(R.string.weather_wind_se)
            "S" -> context.getString(R.string.weather_wind_s)
            "SW" -> context.getString(R.string.weather_wind_sw)
            "W" -> context.getString(R.string.weather_wind_w)
            "NW" -> context.getString(R.string.weather_wind_nw)
            else -> dirEn
        }
    }
    
    private fun getConditionText(condition: String): String {
        return when (condition) {
            "sunny" -> context.getString(R.string.weather_sunny)
            "clear_night" -> context.getString(R.string.weather_clear_night)
            "cloudy" -> context.getString(R.string.weather_cloudy)
            "partly_cloudy" -> context.getString(R.string.weather_partly_cloudy)
            "light_rain" -> context.getString(R.string.weather_light_rain)
            "rainy" -> context.getString(R.string.weather_rainy)
            "heavy_rain" -> context.getString(R.string.weather_heavy_rain)
            "light_snow" -> context.getString(R.string.weather_light_snow)
            "snowy" -> context.getString(R.string.weather_snowy)
            "heavy_snow" -> context.getString(R.string.weather_heavy_snow)
            "fog" -> context.getString(R.string.weather_fog)
            "haze" -> context.getString(R.string.weather_haze)
            "wind" -> context.getString(R.string.weather_wind)
            "sandstorm" -> context.getString(R.string.weather_sandstorm)
            else -> context.getString(R.string.weather_sunny)
        }
    }
}

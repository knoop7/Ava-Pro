package com.example.ava.esphome.voicesatellite

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.example.ava.R
import com.example.ava.esphome.EspHomeDevice
import com.example.ava.mods.ModDeviceSupport
import com.example.ava.utils.ScreenControlUtils
import com.example.ava.esphome.entities.BinarySensorEntity
import com.example.ava.esphome.entities.NumberEntity
import com.example.ava.esphome.entities.TextSensorEntity
import com.example.ava.sensor.PresenceFusionEngine
import com.example.ava.sensor.ScreenGestureRecognizer
import com.example.ava.sensor.ScreenGestureSensor
import com.example.ava.sensor.ScreenTouchSensor
import com.example.ava.settings.ExperimentalSettingsStore
import com.example.esphomeproto.api.EntityCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class VoiceSatelliteScreen(
    private val context: Context,
    private val scope: CoroutineScope,
    private val device: EspHomeDevice,
    private val experimentalSettingsStore: ExperimentalSettingsStore
) {
    private val powerManager = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
    private val _screenBrightness = MutableStateFlow(0f)
    private val _proximityState = MutableStateFlow(false)
    private val _latestProximityDistance = MutableStateFlow<Float?>(null)
    
    private var environmentSensorManager: com.example.ava.sensor.EnvironmentSensorManager? = null
    private var proximityDistanceEntity: TextSensorEntity? = null
    private var proximityJob: Job? = null
    private var proximityDistancePublishJob: Job? = null
    private var proximityRefreshJob: Job? = null
    private var brightnessObserver: android.database.ContentObserver? = null
    private var brightnessPanelJob: Job? = null
    /** Non-null only when an enabled device mod supplied [ModDeviceSupport.installedMinBrightness]. */
    private var brightnessWriteFloor: Int? = null
    private var orientationOverlayView: android.view.View? = null
    private var lastWakeTime = 0L
    private var currentProximityInterval = 0
    
    private var lastPublishedProximityStr = ""
    private var screenTouchEntity: BinarySensorEntity? = null
    private var screenGestureEntity: TextSensorEntity? = null
    private var screenGestureJob: Job? = null
    
    companion object {
        private const val TAG = "VoiceSatelliteScreen"
    }

    fun ensureEntitiesRegistered(settings: com.example.ava.settings.ExperimentalSettings) {
        val sensorManager = environmentSensorManager ?: com.example.ava.sensor.EnvironmentSensorManager(context).also {
            environmentSensorManager = it
        }
        if (
            settings.proximitySendToHass &&
            sensorManager.hasProximitySensor &&
            proximityDistanceEntity == null
        ) {
            proximityDistanceEntity = TextSensorEntity(
                key = "proximity_distance".hashCode(),
                name = context.getString(R.string.entity_proximity_distance),
                objectId = "proximity_distance",
                icon = "mdi:map-marker-distance",
                entityCategory = EntityCategory.ENTITY_CATEGORY_DIAGNOSTIC,
                hasInitialState = false
            )
            proximityDistanceEntity?.let { device.addEntity(it) }
        }
    }

    fun initProximitySensor() {
        val settings = experimentalSettingsStore.getCached()
        ensureEntitiesRegistered(settings)
        val sensorManager = environmentSensorManager ?: return
        sensorManager.startListening()
        sensorManager.requestImmediateProximityRefresh()
        
        var awayDelayJob: Job? = null
        var wasNear = false
        
        proximityJob?.cancel()
        proximityJob = scope.launch {
            proximityDistancePublishJob?.cancel()
            proximityRefreshJob?.cancel()
            proximityRefreshJob = scope.launch {
                repeat(12) {
                    if (sensorManager.hasProximityReading.value) return@launch
                    sensorManager.requestImmediateProximityRefresh()
                    delay(500)
                }
            }
            if (settings.proximitySendToHass && sensorManager.hasProximitySensor) {
                currentProximityInterval = settings.proximityHassUpdateInterval.coerceAtLeast(5)
                lastPublishedProximityStr = ""
                proximityDistancePublishJob = scope.launch {
                    while (isActive) {
                        val latestDistance = sensorManager.proximity.value
                        if (latestDistance != null) {
                            _latestProximityDistance.value = latestDistance
                            val currentValue = latestDistance.toInt()
                            val lightLevel = sensorManager.lightLevel.value
                            val isLowLight = lightLevel < 20f
                            val farThreshold = if (isLowLight) 120 else 130
                            val newState = when {
                                currentValue == 0 -> "idle"
                                currentValue == 1 -> "1"
                                currentValue in 2..10 -> currentValue.toString()
                                currentValue >= farThreshold -> currentValue.toString()
                                else -> "idle"
                            }
                            if (newState != lastPublishedProximityStr) {
                                lastPublishedProximityStr = newState
                                proximityDistanceEntity?.forceUpdateState(newState)
                            }
                        }
                        delay(currentProximityInterval * 1000L)
                    }
                }
            }
            sensorManager.proximity.collect { distance ->
                if (distance == null) return@collect
                _latestProximityDistance.value = distance
                val currentSettings = experimentalSettingsStore.get()
                
                if (currentSettings.proximitySendToHass) {
                    val currentValue = distance.toInt()
                    val lightLevel = sensorManager.lightLevel.value
                    val isLowLight = lightLevel < 20f
                    val farThreshold = if (isLowLight) 120 else 130
                    val newState = when {
                        currentValue == 0 -> "idle"
                        currentValue == 1 -> "1"
                        currentValue in 2..10 -> currentValue.toString()
                        currentValue >= farThreshold -> currentValue.toString()
                        else -> "idle"
                    }
                    if (newState != lastPublishedProximityStr) {
                        lastPublishedProximityStr = newState
                        proximityDistanceEntity?.forceUpdateState(newState)
                    }
                }
                
                val isNear = distance < sensorManager.proximityMaxRange
                // Raw near/far feeds fusion directly; the engine applies its own leave decay.
                PresenceFusionEngine.report(PresenceFusionEngine.Source.PROXIMITY, isNear)
                
                if (isNear) {
                    awayDelayJob?.cancel()
                    awayDelayJob = null
                    _proximityState.value = true
                    
                    if (currentSettings.proximityWakeScreen && !powerManager.isInteractive) {
                        val now = System.currentTimeMillis()
                        if (now - lastWakeTime > 3000) { 
                            wakeScreen(currentSettings.proximityAutoUnlock)
                            lastWakeTime = now
                        }
                    }
                } else if (wasNear && awayDelayJob == null) {
                    val delayMs = currentSettings.proximityAwayDelay * 1000L
                    awayDelayJob = scope.launch {
                        delay(delayMs)
                        _proximityState.value = false
                        awayDelayJob = null
                    }
                }
                wasNear = isNear
            }
        }
    }
    
    @Suppress("DEPRECATION")
    private fun wakeScreen(unlock: Boolean) {
        if (powerManager.isInteractive) return
        
        val flags = android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
            android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or
            (if (unlock) android.os.PowerManager.ON_AFTER_RELEASE else 0)
        
        val wakeLock = powerManager.newWakeLock(flags, "Ava:ProximityWake")
        wakeLock.acquire(3000L)
        wakeLock.release()
        
        if (unlock) {
            try {
                val intent = android.content.Intent(context, com.example.ava.UnlockActivity::class.java)
                intent.addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_NO_HISTORY or
                        android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
                )
                intent.putExtra("unlock", true)
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot start unlock activity: ${e.message}")
            }
        }
    }
    
    fun stopProximitySensor() {
        proximityJob?.cancel()
        proximityJob = null
        proximityDistancePublishJob?.cancel()
        proximityDistancePublishJob = null
        proximityRefreshJob?.cancel()
        proximityRefreshJob = null
        _latestProximityDistance.value = null
        _proximityState.value = false
        PresenceFusionEngine.report(PresenceFusionEngine.Source.PROXIMITY, false)
        environmentSensorManager?.stopListening()
        proximityDistanceEntity = null
    }

    fun updateProximityPublishInterval(newIntervalSeconds: Int) {
        val interval = newIntervalSeconds.coerceAtLeast(5)
        if (interval == currentProximityInterval) return
        currentProximityInterval = interval
        
        val sensorManager = environmentSensorManager ?: return
        if (!sensorManager.hasProximitySensor) return
        
        lastPublishedProximityStr = ""
        proximityDistancePublishJob?.cancel()
        proximityDistancePublishJob = scope.launch {
            while (isActive) {
                val latestDistance = sensorManager.proximity.value
                if (latestDistance != null) {
                    _latestProximityDistance.value = latestDistance
                    val currentValue = latestDistance.toInt()
                    val lightLevel = sensorManager.lightLevel.value
                    val isLowLight = lightLevel < 20f
                    val farThreshold = if (isLowLight) 120 else 130
                    val newState = when {
                        currentValue == 0 -> "idle"
                        currentValue == 1 -> "1"
                        currentValue in 2..10 -> currentValue.toString()
                        currentValue >= farThreshold -> currentValue.toString()
                        else -> "idle"
                    }
                    if (newState != lastPublishedProximityStr) {
                        lastPublishedProximityStr = newState
                        proximityDistanceEntity?.forceUpdateState(newState)
                    }
                }
                delay(currentProximityInterval * 1000L)
            }
        }
    }
    
    fun initScreenTouchSensor() {
        ScreenTouchSensor.setAwayDelaySeconds(
            experimentalSettingsStore.getCached().screenTouchAwayDelay,
        )
        if (screenTouchEntity != null) return
        val entity = BinarySensorEntity(
            key = "screen_touch".hashCode(),
            name = context.getString(R.string.entity_screen_touch),
            objectId = "screen_touch",
            icon = "mdi:gesture-tap",
            getState = ScreenTouchSensor.touched,
            entityCategory = EntityCategory.ENTITY_CATEGORY_DIAGNOSTIC,
        )
        screenTouchEntity = entity
        device.addEntity(entity)
    }

    fun initScreenGestureSensor() {
        val settings = experimentalSettingsStore.getCached()
        ScreenGestureRecognizer.sync(settings)
        if (screenGestureEntity != null) return
        val entity = TextSensorEntity(
            key = "screen_gesture".hashCode(),
            name = context.getString(R.string.entity_screen_gesture),
            objectId = "screen_gesture",
            icon = "mdi:gesture-swipe",
            entityCategory = EntityCategory.ENTITY_CATEGORY_DIAGNOSTIC,
            initialState = ScreenGestureSensor.state.value,
        )
        screenGestureEntity = entity
        device.addEntity(entity)
        screenGestureJob?.cancel()
        screenGestureJob = scope.launch {
            ScreenGestureSensor.state.collectLatest { entity.forceUpdateState(it) }
        }
    }

    fun initScreenBrightness() {
        val floor = ModDeviceSupport.installedMinBrightness(context)
        brightnessWriteFloor = floor
        val panelOn = ScreenControlUtils.panelOnState.value
        _screenBrightness.value = if (floor != null && !panelOn) 0f else readSystemBrightness()

        val brightnessEntity = NumberEntity(
            key = "screen_brightness".hashCode(),
            name = context.getString(R.string.entity_screen_brightness),
            objectId = "screen_brightness",
            icon = "mdi:brightness-6",
            minValue = if (floor != null) 0f else 1f,
            maxValue = 255f,
            step = 1f,
            getState = _screenBrightness,
            setState = { value -> applyBrightnessCommand(value.toInt()) },
            entityCategory = EntityCategory.ENTITY_CATEGORY_NONE,
            mode = com.example.esphomeproto.api.NumberMode.NUMBER_MODE_SLIDER
        )
        device.addEntity(brightnessEntity)

        startBrightnessObserver()
        if (floor != null) startBrightnessPanelWatch()
    }

    /**
     * With a device mod, 0 is screen off and is never written into system brightness.
     * Any higher command turns the panel on and writes at least the mod's floor.
     */
    private fun applyBrightnessCommand(requested: Int) {
        val floor = brightnessWriteFloor
        if (floor != null && requested <= 0) {
            if (ScreenControlUtils.setScreenOn(context, false)) {
                _screenBrightness.value = 0f
            }
            return
        }
        if (floor != null && !ScreenControlUtils.panelOnState.value) {
            ScreenControlUtils.setScreenOn(context, true)
        }
        val written = if (floor != null) requested.coerceIn(floor, 255) else requested.coerceIn(0, 255)
        setScreenBrightness(written)
        _screenBrightness.value = written.toFloat()
    }

    @Suppress("DEPRECATION")
    private fun readSystemBrightness(): Float {
        return try {
            android.provider.Settings.System.getInt(
                context.contentResolver,
                android.provider.Settings.System.SCREEN_BRIGHTNESS
            ).toFloat()
        } catch (e: Exception) {
            128f
        }
    }

    private fun publishBrightnessReading() {
        val floor = brightnessWriteFloor
        if (floor != null && !ScreenControlUtils.panelOnState.value) {
            _screenBrightness.value = 0f
            return
        }
        val raw = readSystemBrightness()
        _screenBrightness.value = if (floor != null) raw.coerceAtLeast(floor.toFloat()) else raw
    }

    private fun startBrightnessPanelWatch() {
        brightnessPanelJob?.cancel()
        brightnessPanelJob = scope.launch {
            ScreenControlUtils.panelOnState.collectLatest { on ->
                if (brightnessWriteFloor == null) return@collectLatest
                if (!on) {
                    _screenBrightness.value = 0f
                } else if (_screenBrightness.value == 0f) {
                    publishBrightnessReading()
                }
            }
        }
    }
    
    @Suppress("DEPRECATION")
    private fun startBrightnessObserver() {
        brightnessObserver = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                try {
                    publishBrightnessReading()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to read brightness: ${e.message}")
                }
            }
        }
        context.contentResolver.registerContentObserver(
            android.provider.Settings.System.getUriFor(android.provider.Settings.System.SCREEN_BRIGHTNESS),
            false,
            brightnessObserver!!
        )
    }
    
    @Suppress("DEPRECATION")
    private fun setScreenBrightness(brightness: Int) {
        val value = brightness.coerceIn(0, 255)
        try {
            if (!com.example.ava.platform.PlatformCapabilities.canWriteSettings(context)) {
                Log.w(TAG, "No WRITE_SETTINGS permission")
                return
            }
            val resolver = context.contentResolver
            android.provider.Settings.System.putInt(
                resolver,
                android.provider.Settings.System.SCREEN_BRIGHTNESS,
                value
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set screen brightness", e)
        }
    }
    
    @SuppressLint("InflateParams")
    fun initForceOrientation() {
        scope.launch {
            try {
                val settings = experimentalSettingsStore.get()
                com.example.ava.services.OverlayOrientation.syncForceSettings(
                    settings.forceOrientationEnabled,
                    settings.forceOrientationMode,
                )
                if (!com.example.ava.platform.PlatformCapabilities.canDrawOverlays(context)) {
                    Log.w(TAG, "No overlay permission for force orientation")
                    if (settings.forceOrientationEnabled) {
                        experimentalSettingsStore.setForceOrientationEnabled(false)
                    }
                    return@launch
                }

                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    removeOrientationOverlay()

                    val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
                    val params = android.view.WindowManager.LayoutParams(
                        0, 0,
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                            android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        else
                            @Suppress("DEPRECATION")
                            android.view.WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                        android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        android.graphics.PixelFormat.TRANSLUCENT
                    )
                    com.example.ava.services.OverlayOrientation.apply(params)

                    val view = android.view.View(context)
                    windowManager.addView(view, params)
                    orientationOverlayView = view
                    Log.i(
                        TAG,
                        "Overlay orientation: ${com.example.ava.services.OverlayOrientation.screenOrientation()}",
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to init overlay orientation", e)
            }
        }
    }
    
    fun stopBrightnessObserver() {
        brightnessPanelJob?.cancel()
        brightnessPanelJob = null
        brightnessObserver?.let {
            context.contentResolver.unregisterContentObserver(it)
        }
        brightnessObserver = null
    }
    
    fun removeOrientationOverlay() {
        val view = orientationOverlayView ?: return
        orientationOverlayView = null
        val doRemove = Runnable {
            try {
                if (view.isAttachedToWindow) {
                    val wm = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
                    wm.removeView(view)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove orientation overlay: ${e.message}")
            }
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            doRemove.run()
        } else {
            android.os.Handler(android.os.Looper.getMainLooper()).post(doRemove)
        }
    }
    
    fun close() {
        screenGestureJob?.cancel()
        screenGestureJob = null
        stopProximitySensor()
        stopBrightnessObserver()
        removeOrientationOverlay()
        environmentSensorManager?.stopListening()
        environmentSensorManager = null
    }
}

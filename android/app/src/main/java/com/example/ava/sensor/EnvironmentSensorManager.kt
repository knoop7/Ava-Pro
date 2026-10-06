package com.example.ava.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow


class EnvironmentSensorManager(context: Context) : SensorEventListener {
    
    private var isTouching = false
    
    companion object {
        private const val TAG = "EnvironmentSensor"
    }
    
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val lightSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
    private val magneticSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val proximitySensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    val proximityMaxRange: Float = proximitySensor?.maximumRange ?: 5f
    
    
    private val _lightLevel = MutableStateFlow(0f)
    val lightLevel: StateFlow<Float> = _lightLevel.asStateFlow()
    
    
    private val _magneticField = MutableStateFlow(0f)
    val magneticField: StateFlow<Float> = _magneticField.asStateFlow()
    
    
    private val _proximity = MutableStateFlow<Float?>(null)
    val proximity: StateFlow<Float?> = _proximity.asStateFlow()

    private val _hasProximityReading = MutableStateFlow(false)
    val hasProximityReading: StateFlow<Boolean> = _hasProximityReading.asStateFlow()
    
    
    val hasLightSensor: Boolean get() = lightSensor != null
    val hasMagneticSensor: Boolean get() = magneticSensor != null
    val hasProximitySensor: Boolean get() = proximitySensor != null
    val hasAnySensor: Boolean get() = hasLightSensor || hasMagneticSensor || hasProximitySensor
    
    private var isRegistered = false
    
    
    fun startListening() {
        if (isRegistered) return
        
        if (lightSensor != null) {
            
            sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_FASTEST, 0)
            Log.d(TAG, "Light sensor registered: ${lightSensor.name}, max: ${lightSensor.maximumRange} lux")
        } else {
            Log.w(TAG, "Light sensor NOT available on this device")
        }
        
        if (magneticSensor != null) {
            sensorManager.registerListener(this, magneticSensor, SensorManager.SENSOR_DELAY_NORMAL)
            Log.d(TAG, "Magnetic sensor registered")
        } else {
            Log.w(TAG, "Magnetic sensor NOT available on this device")
        }
        
        if (proximitySensor != null) {
            _hasProximityReading.value = false
            _proximity.value = null
            sensorManager.registerListener(this, proximitySensor, SensorManager.SENSOR_DELAY_FASTEST, 0)
            runCatching { sensorManager.flush(this) }
            Log.d(TAG, "Proximity sensor registered: max range = $proximityMaxRange")
        } else {
            Log.w(TAG, "Proximity sensor NOT available on this device")
        }
        
        isRegistered = true
    }
    
    
    fun stopListening() {
        if (!isRegistered) return
        
        sensorManager.unregisterListener(this)
        isRegistered = false
        Log.d(TAG, "Sensors unregistered")
    }

    fun requestImmediateProximityRefresh() {
        val sensor = proximitySensor ?: return
        runCatching { sensorManager.unregisterListener(this, sensor) }
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST, 0)
        runCatching { sensorManager.flush(this) }
    }
    
    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_LIGHT -> {
                val value = event.values[0]
                _lightLevel.value = value
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                _magneticField.value = kotlin.math.sqrt(x * x + y * y + z * z)
            }
            Sensor.TYPE_PROXIMITY -> {
                val value = event.values[0]
                _hasProximityReading.value = true
                _proximity.value = value
            }
        }
    }
    
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        
    }
    
    fun onTouchEvent(isTouching: Boolean) {
        this.isTouching = isTouching
    }
    
    fun isScreenTouched(): Boolean = isTouching
}

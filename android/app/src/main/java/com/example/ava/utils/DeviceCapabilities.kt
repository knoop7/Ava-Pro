package com.example.ava.utils

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log
import java.io.File


object DeviceCapabilities {
    
    private const val TAG = "DeviceCapabilities"
    
    
    private var _hasCamera: Boolean? = null
    private var _hasLightSensor: Boolean? = null
    private var _hasMagneticSensor: Boolean? = null
    private var _hasMicrophone: Boolean? = null
    private var _hasProximitySensor: Boolean? = null
    private var _hasTemperatureSensor: Boolean? = null
    private var _hasHumiditySensor: Boolean? = null
    private var _hasPressureSensor: Boolean? = null
    
    
    fun hasCamera(context: Context): Boolean {
        if (_hasCamera != null) return _hasCamera!!

        _hasCamera = detectCameraHardware(context)
        return _hasCamera!!
    }

    private fun detectCameraHardware(context: Context): Boolean {
        try {
            val devDir = File("/dev")
            if (devDir.exists()) {
                val videoDevices = devDir.listFiles { file -> file.name.startsWith("video") }
                if (videoDevices != null && videoDevices.isNotEmpty()) {
                    return true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check /dev/video*", e)
        }

        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            if (cameraManager.cameraIdList.isNotEmpty()) {
                return true
            }
        } catch (e: android.hardware.camera2.CameraAccessException) {
            // Camera service busy or unavailable — fall through to PackageManager.
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check CameraManager", e)
        }

        return try {
            val pm = context.packageManager
            pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) ||
                pm.hasSystemFeature(PackageManager.FEATURE_CAMERA) ||
                pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check PackageManager features", e)
            false
        }
    }
    
    
    fun hasFrontCamera(context: Context): Boolean {
        // PackageManager first: fast, and it avoids touching the camera.
        try {
            if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT)) {
                return true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check FEATURE_CAMERA_FRONT", e)
        }
        
        // If PackageManager finds nothing, try CameraManager (this can occupy the camera).
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraIds = cameraManager.cameraIdList
            if (cameraIds.isEmpty()) return false
            
            for (id in cameraIds) {
                try {
                    val characteristics = cameraManager.getCameraCharacteristics(id)
                    val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                    if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                        return true
                    }
                } catch (e: android.hardware.camera2.CameraAccessException) {
                    // Camera is busy or unavailable; skip it.
                    Log.w(TAG, "Camera $id not accessible: ${e.reason}", e)
                    continue
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check front camera via CameraManager", e)
        }
        
        return false
    }
    
    
    fun hasBackCamera(context: Context): Boolean {
        // PackageManager first: fast, and it avoids touching the camera.
        try {
            if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA)) {
                return true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check FEATURE_CAMERA", e)
        }
        
        // If PackageManager finds nothing, try CameraManager (this can occupy the camera).
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraIds = cameraManager.cameraIdList
            if (cameraIds.isEmpty()) return false
            
            for (id in cameraIds) {
                try {
                    val characteristics = cameraManager.getCameraCharacteristics(id)
                    val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                    if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                        return true
                    }
                } catch (e: android.hardware.camera2.CameraAccessException) {
                    // Camera is busy or unavailable; skip it.
                    Log.w(TAG, "Camera $id not accessible: ${e.reason}", e)
                    continue
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check back camera via CameraManager", e)
        }
        
        return false
    }
    
    
    fun hasLightSensor(context: Context): Boolean {
        if (_hasLightSensor != null) return _hasLightSensor!!
        
        _hasLightSensor = try {
            val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT) != null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check light sensor", e)
            false
        }
        return _hasLightSensor!!
    }
    
    
    fun hasProximitySensor(context: Context): Boolean {
        if (_hasProximitySensor != null) return _hasProximitySensor!!
        
        _hasProximitySensor = try {
            val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY) != null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check proximity sensor", e)
            false
        }
        return _hasProximitySensor!!
    }

    fun hasMagneticSensor(context: Context): Boolean {
        if (_hasMagneticSensor != null) return _hasMagneticSensor!!

        _hasMagneticSensor = try {
            val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check magnetic sensor", e)
            false
        }
        return _hasMagneticSensor!!
    }

    fun hasMicrophone(context: Context): Boolean {
        if (_hasMicrophone != null) return _hasMicrophone!!

        _hasMicrophone = try {
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check microphone feature", e)
            false
        }
        return _hasMicrophone!!
    }
    
    
    fun hasTemperatureSensor(context: Context): Boolean {
        if (_hasTemperatureSensor != null) return _hasTemperatureSensor!!
        
        _hasTemperatureSensor = try {
            val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            sensorManager.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE) != null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check temperature sensor", e)
            false
        }
        return _hasTemperatureSensor!!
    }
    
    
    fun hasHumiditySensor(context: Context): Boolean {
        if (_hasHumiditySensor != null) return _hasHumiditySensor!!
        
        _hasHumiditySensor = try {
            val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            sensorManager.getDefaultSensor(Sensor.TYPE_RELATIVE_HUMIDITY) != null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check humidity sensor", e)
            false
        }
        return _hasHumiditySensor!!
    }
    
    
    fun hasPressureSensor(context: Context): Boolean {
        if (_hasPressureSensor != null) return _hasPressureSensor!!
        
        _hasPressureSensor = try {
            val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE) != null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check pressure sensor", e)
            false
        }
        return _hasPressureSensor!!
    }
    
    
    fun hasAnyEnvironmentSensor(context: Context): Boolean {
        return hasLightSensor(context) ||
               hasMagneticSensor(context) ||
               hasMicrophone(context)
    }
    
    
    fun clearCache() {
        _hasCamera = null
        _hasLightSensor = null
        _hasMagneticSensor = null
        _hasMicrophone = null
        _hasProximitySensor = null
        _hasTemperatureSensor = null
        _hasHumiditySensor = null
        _hasPressureSensor = null
    }
}

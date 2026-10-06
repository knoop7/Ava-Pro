package com.example.ava.esphome.voicesatellite

import android.content.Context
import com.example.ava.R
import com.example.ava.esphome.EspHomeDevice
import com.example.ava.esphome.entities.SensorEntity
import com.example.ava.esphome.entities.TextSensorEntity
import com.example.ava.settings.ExperimentalSettingsStore
import com.example.esphomeproto.api.EntityCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class VoiceSatelliteSensors(
    private val context: Context,
    private val scope: CoroutineScope,
    private val device: EspHomeDevice,
    private val experimentalSettingsStore: ExperimentalSettingsStore
) {
    private var environmentSensorManager: com.example.ava.sensor.EnvironmentSensorManager? = null
    private var lightSensorEntity: SensorEntity? = null
    private var magneticSensorEntity: SensorEntity? = null
    private var sensorUpdateJob: Job? = null
    private var currentSensorInterval = 0

    val audioEventEntity = TextSensorEntity(
        key = 43,
        name = context.getString(R.string.entity_audio_event),
        objectId = "audio_event",
        icon = "mdi:microphone-outline",
        entityCategory = EntityCategory.ENTITY_CATEGORY_DIAGNOSTIC,
        initialState = "idle",
    )

    fun registerAudioEventEntity() {
        device.addEntity(audioEventEntity)
        audioEventEntity.forceUpdateState("idle")
    }

    fun unregisterAudioEventEntity() {
        device.removeEntity(audioEventEntity)
        audioEventEntity.updateState("idle")
    }

    fun ensureEntitiesRegistered(settings: com.example.ava.settings.ExperimentalSettings) {
        val sensorManager = environmentSensorManager ?: com.example.ava.sensor.EnvironmentSensorManager(context).also {
            environmentSensorManager = it
        }

        if (sensorManager.hasLightSensor && settings.environmentLightSensorEnabled && lightSensorEntity == null) {
            lightSensorEntity = SensorEntity(
                key = 20,
                name = context.getString(R.string.entity_light_sensor),
                objectId = "light_sensor",
                icon = "mdi:brightness-6",
                unitOfMeasurement = "lx",
                accuracyDecimals = 0,
                deviceClass = "illuminance",
                entityCategory = EntityCategory.ENTITY_CATEGORY_NONE
            )
            lightSensorEntity?.let { device.addEntity(it) }
        }

        if (sensorManager.hasMagneticSensor && settings.environmentMagneticSensorEnabled && magneticSensorEntity == null) {
            magneticSensorEntity = SensorEntity(
                key = 21,
                name = context.getString(R.string.entity_magnetic_sensor),
                objectId = "magnetic_sensor",
                icon = "mdi:magnet",
                unitOfMeasurement = "μT",
                accuracyDecimals = 1,
                entityCategory = EntityCategory.ENTITY_CATEGORY_NONE
            )
            magneticSensorEntity?.let { device.addEntity(it) }
        }
    }

    fun init() {
        val settings = experimentalSettingsStore.getCached()
        ensureEntitiesRegistered(settings)
        val sensorManager = environmentSensorManager ?: return
        sensorManager.startListening()
        startSensorUpdateLoop()
    }

    private fun startSensorUpdateLoop() {
        sensorUpdateJob?.cancel()
        sensorUpdateJob = scope.launch {
            val settings = experimentalSettingsStore.get()
            currentSensorInterval = settings.sensorUpdateInterval.coerceIn(5, 60)
            while (true) {
                updateSensorValuesFiltered()
                delay(currentSensorInterval * 1000L)
            }
        }
    }
    
    fun updateSensorInterval(intervalSeconds: Int) {
        val interval = intervalSeconds.coerceIn(5, 60)
        if (interval == currentSensorInterval) return
        currentSensorInterval = interval
        
        if (sensorUpdateJob?.isActive != true) return
        
        sensorUpdateJob?.cancel()
        sensorUpdateJob = scope.launch {
            while (true) {
                updateSensorValuesFiltered()
                delay(currentSensorInterval * 1000L)
            }
        }
    }

    private suspend fun updateSensorValuesFiltered() {
        val manager = environmentSensorManager ?: return

        val lightSamples = mutableListOf<Float>()
        val magneticSamples = mutableListOf<Float>()

        repeat(3) {
            if (lightSensorEntity != null) {
                lightSamples.add(manager.lightLevel.value)
            }
            if (magneticSensorEntity != null) {
                magneticSamples.add(manager.magneticField.value)
            }
            delay(100)
        }

        lightSamples.takeIf { it.isNotEmpty() }?.let { lightSensorEntity?.updateState(it.sorted()[it.size / 2]) }
        magneticSamples.takeIf { it.isNotEmpty() }?.let { magneticSensorEntity?.updateState(it.sorted()[it.size / 2]) }
    }

    fun stop() {
        sensorUpdateJob?.cancel()
        sensorUpdateJob = null
        environmentSensorManager?.stopListening()
        environmentSensorManager = null
    }
}

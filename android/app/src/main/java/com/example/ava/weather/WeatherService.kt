package com.example.ava.weather

import android.util.Log

data class WeatherData(
    val temperature: Int = 0,
    val condition: String = "sunny",  
    val humidity: Int = 0,
    val windSpeed: Float = 0f,
    val windDirection: String = "",   
    val windDirectionEn: String = "", 
    val visibility: Float = 0f,
    val pressure: Float = 0f,
    val aqi: Int = 0,
    val pm25: Int = 0,
    val city: String = "",
    val temperatureUnit: String = "°C",
    val pressureUnit: String = "hPa",
    val windSpeedUnit: String = "km/h",
    val visibilityUnit: String = "km",
    val precipitationUnit: String = "mm"
)

object WeatherService {
    private const val TAG = "WeatherService"
    private const val LOG_MIN_INTERVAL_MS = 2000L
    private const val LOG_FALLBACK_INTERVAL_MS = 10000L
    
    private var cachedWeather: WeatherData? = null
    private var lastUpdateTime: Long = 0
    private const val CACHE_DURATION = 5 * 60 * 1000L
    private var lastLogAt: Long = 0
    private var lastLoggedWeather: WeatherData? = null
    
    private val weatherListeners = java.util.concurrent.CopyOnWriteArrayList<(WeatherData) -> Unit>()
    
    fun addWeatherListener(listener: (WeatherData) -> Unit) {
        weatherListeners.add(listener)
    }
    
    fun removeWeatherListener(listener: (WeatherData) -> Unit) {
        weatherListeners.remove(listener)
    }
    
    fun getCachedWeather(): WeatherData? {
        val now = System.currentTimeMillis()
        if (cachedWeather != null && now - lastUpdateTime < CACHE_DURATION) {
            return cachedWeather
        }
        return cachedWeather
    }
    
    /**
     * Parse HA weather state/attrs into [WeatherData] without touching the shared cache.
     * Used by Simple Clock's dedicated weather path (decoupled from the overlay).
     */
    fun parseFromHa(
        state: String,
        temperature: String?,
        humidity: String?,
        windSpeed: String?,
        windBearing: String?,
        friendlyName: String?,
        aqi: String? = null,
        pm25: String? = null,
        visibility: String? = null,
        pressure: String? = null,
        temperatureUnit: String? = null,
        pressureUnit: String? = null,
        windSpeedUnit: String? = null,
        visibilityUnit: String? = null,
        precipitationUnit: String? = null
    ): WeatherData {
        val temp = temperature?.toDoubleOrNull()?.let { kotlin.math.round(it).toInt() } ?: 0
        val hum = humidity?.toDoubleOrNull()?.toInt() ?: 0
        val wind = windSpeed?.toFloatOrNull() ?: 0f
        val bearing = windBearing?.toDoubleOrNull()?.toInt() ?: 0
        val aqiVal = aqi?.toDoubleOrNull()?.toInt() ?: 0
        val pm25Val = pm25?.toDoubleOrNull()?.toInt() ?: 0
        val vis = visibility?.toFloatOrNull() ?: 0f
        val pres = pressure?.toFloatOrNull() ?: 0f
        val condition = convertHaState(state)
        val windDir = getWindDirectionText(bearing)
        return WeatherData(
            temperature = temp,
            condition = condition,
            humidity = hum,
            windSpeed = wind,
            windDirection = windDir,
            windDirectionEn = getWindDirectionTextEn(bearing),
            visibility = vis,
            pressure = pres,
            aqi = aqiVal,
            pm25 = pm25Val,
            city = friendlyName ?: "",
            temperatureUnit = temperatureUnit ?: "°C",
            pressureUnit = pressureUnit ?: "hPa",
            windSpeedUnit = windSpeedUnit ?: "km/h",
            visibilityUnit = visibilityUnit ?: "km",
            precipitationUnit = precipitationUnit ?: "mm"
        )
    }

    fun updateFromHa(
        state: String,
        temperature: String?,
        humidity: String?,
        windSpeed: String?,
        windBearing: String?,
        friendlyName: String?,
        aqi: String? = null,
        pm25: String? = null,
        visibility: String? = null,
        pressure: String? = null,
        temperatureUnit: String? = null,
        pressureUnit: String? = null,
        windSpeedUnit: String? = null,
        visibilityUnit: String? = null,
        precipitationUnit: String? = null
    ) {
        val newWeather = parseFromHa(
            state = state,
            temperature = temperature,
            humidity = humidity,
            windSpeed = windSpeed,
            windBearing = windBearing,
            friendlyName = friendlyName,
            aqi = aqi,
            pm25 = pm25,
            visibility = visibility,
            pressure = pressure,
            temperatureUnit = temperatureUnit,
            pressureUnit = pressureUnit,
            windSpeedUnit = windSpeedUnit,
            visibilityUnit = visibilityUnit,
            precipitationUnit = precipitationUnit
        )
        
        if (newWeather != cachedWeather) {
            cachedWeather = newWeather
            lastUpdateTime = System.currentTimeMillis()
            if (shouldLogWeather(newWeather)) {
                Log.d(TAG, "Weather updated from HA: $cachedWeather")
                lastLogAt = System.currentTimeMillis()
                lastLoggedWeather = newWeather
            }
            
            weatherListeners.forEach { it.invoke(newWeather) }
        }
    }
    
    private fun shouldLogWeather(newWeather: WeatherData): Boolean {
        if (newWeather == lastLoggedWeather) return false
        
        val now = System.currentTimeMillis()
        val hasExtendedFields = newWeather.city.isNotBlank() ||
            newWeather.aqi > 0 ||
            newWeather.pm25 > 0 ||
            newWeather.pressure > 0f ||
            newWeather.visibility > 0f
        val minInterval = if (hasExtendedFields) LOG_MIN_INTERVAL_MS else LOG_FALLBACK_INTERVAL_MS
        return now - lastLogAt >= minInterval
    }
    
    private fun convertHaState(state: String): String {
        return when (state.lowercase()) {
            "sunny" -> "sunny"
            "clear-night" -> "clear_night"
            "cloudy" -> "cloudy"
            "partlycloudy" -> "partly_cloudy"
            "rainy", "pouring" -> "rainy"
            "lightning", "lightning-rainy" -> "heavy_rain"
            "snowy", "snowy-rainy" -> "snowy"
            "hail" -> "heavy_snow"
            "fog" -> "fog"
            "windy", "windy-variant" -> "wind"
            "exceptional" -> "sandstorm"
            else -> {
                val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                if (hour in 6..18) "sunny" else "clear_night"
            }
        }
    }
    
    fun getWindDirectionText(direction: Int): String {
        return getWindDirectionTextEn(direction)
    }
    
    private fun getWindDirectionTextEn(direction: Int): String {
        return when {
            direction < 22.5 || direction >= 337.5 -> "N"
            direction < 67.5 -> "NE"
            direction < 112.5 -> "E"
            direction < 157.5 -> "SE"
            direction < 202.5 -> "S"
            direction < 247.5 -> "SW"
            direction < 292.5 -> "W"
            else -> "NW"
        }
    }
    
    fun getWindLevel(speed: Float): String {
        return when {
            speed < 1 -> "0"
            speed < 6 -> "1-2"
            speed < 12 -> "3"
            speed < 20 -> "4"
            speed < 29 -> "5"
            speed < 39 -> "6"
            speed < 50 -> "7"
            speed < 62 -> "8"
            else -> "9+"
        }
    }
    
    fun clearCache() {
        cachedWeather = null
        lastUpdateTime = 0
    }
}

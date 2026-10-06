package com.example.ava.localllm.remote

import com.example.ava.weather.WeatherData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AvaSelfWeatherTest {

    @Test
    fun weatherJsonIncludesVisibilityAndPressureWhenCached() {
        val json = AvaSelfTools.weatherJson(
            WeatherData(
                temperature = 22,
                condition = "cloudy",
                humidity = 60,
                visibility = 10f,
                pressure = 1012f,
                visibilityUnit = "km",
                pressureUnit = "hPa",
            ),
        )
        assertEquals(10, json.getLong("visibility"))
        assertEquals("km", json.getString("visibility_unit"))
        assertEquals(1012, json.getLong("pressure"))
        assertEquals("hPa", json.getString("pressure_unit"))
    }

    @Test
    fun weatherJsonOmitsZeroVisibilityAndPressure() {
        val json = AvaSelfTools.weatherJson(WeatherData(temperature = 18, condition = "sunny"))
        assertFalse(json.has("visibility"))
        assertFalse(json.has("pressure"))
        assertFalse(json.has("visibility_unit"))
        assertFalse(json.has("pressure_unit"))
    }
}

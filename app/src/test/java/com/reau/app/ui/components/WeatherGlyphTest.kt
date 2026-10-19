package com.reau.app.ui.components

import com.reau.app.weather.WeatherCondition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The weather icons are stacks of coloured layers, for more visibility. */
class WeatherGlyphTest {
    @Test
    fun `every condition has its layers, back to front`() {
        assertEquals(listOf(WeatherGlyphLayer.SUN), weatherGlyphLayers(WeatherCondition.CLEAR))
        assertEquals(listOf(WeatherGlyphLayer.SUN_BEHIND, WeatherGlyphLayer.CLOUD), weatherGlyphLayers(WeatherCondition.PARTLY_CLOUDY))
        assertEquals(listOf(WeatherGlyphLayer.DARK_CLOUD, WeatherGlyphLayer.RAIN), weatherGlyphLayers(WeatherCondition.RAIN))
        assertEquals(
            listOf(WeatherGlyphLayer.DARK_CLOUD, WeatherGlyphLayer.BOLT, WeatherGlyphLayer.RAIN),
            weatherGlyphLayers(WeatherCondition.THUNDERSTORM),
        )
        assertEquals(listOf(WeatherGlyphLayer.UNKNOWN), weatherGlyphLayers(WeatherCondition.UNKNOWN))
        for (c in WeatherCondition.entries) assertTrue("$c has layers", weatherGlyphLayers(c).isNotEmpty())
    }

    @Test
    fun `the sun and the bolt are yellow, the rain blue, the clouds grey, so no two icons are one tint`() {
        assertEquals(0xFFFFB300, weatherGlyphColor(WeatherGlyphLayer.SUN))
        assertEquals(0xFF1E88E5, weatherGlyphColor(WeatherGlyphLayer.RAIN))
        assertEquals(0xFFB0BEC5, weatherGlyphColor(WeatherGlyphLayer.CLOUD))
        assertTrue(weatherGlyphColor(WeatherGlyphLayer.DARK_CLOUD) != weatherGlyphColor(WeatherGlyphLayer.CLOUD))
    }
}

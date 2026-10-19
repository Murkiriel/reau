package com.reau.app.weather

import com.reau.app.dash.protocol.DashCommands
import com.reau.app.service.DayNight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Clima no painel": what the dash is told from the weather, and the request. The parser has its own test on the device
 * (org.json).
 */
class WeatherKeeperTest {
    private fun now(code: Int?, temp: Double = 23.6) =
        CurrentWeather(temp, null, null, null, code, null, null, null, null, null, null)

    private fun snapshot(current: CurrentWeather?, at: Long = 1_000L) =
        WeatherKeeper.Snapshot(PointForecast(current, emptyList(), emptyList()), at)

    @Test
    fun `the WMO codes become what the dash can draw`() {
        val icons = mapOf(0 to 5, 1 to 5, 2 to 5, 3 to 1, 45 to 1, 51 to 3, 63 to 3, 81 to 3, 71 to 4, 86 to 4, 95 to 2, 99 to 2)
        icons.forEach { (code, icon) -> assertEquals("code $code", icon, WeatherCondition.forCode(code).dashIcon) }
        assertNull(WeatherCondition.forCode(null).dashIcon)
        assertNull(WeatherCondition.forCode(123).dashIcon)
        assertEquals(WeatherCondition.RAIN, WeatherCondition.forCode(65))
    }

    @Test
    fun `the dash gets the temperature rounded and the icon of the code`() {
        assertEquals(DashCommands.Weather(24, 3), WeatherKeeper.forDash(snapshot(now(61)), 2_000L))
        assertEquals(DashCommands.Weather(-3, 4), WeatherKeeper.forDash(snapshot(now(73, -2.6)), 2_000L))
    }

    @Test
    fun `a code the table lacks gives the temperature and the icon no data`() {
        assertEquals(DashCommands.Weather(24, DashCommands.WEATHER_NONE), WeatherKeeper.forDash(snapshot(now(null)), 2_000L))
    }

    @Test
    fun `no weather, no current reading, or one older than three hours is told to the dash as none`() {
        assertNull(WeatherKeeper.forDash(null, 2_000L))
        assertNull(WeatherKeeper.forDash(snapshot(null), 2_000L))
        val usableUntil = 1_000L + WeatherKeeper.USABLE_MS
        assertEquals(DashCommands.Weather(24, 3), WeatherKeeper.forDash(snapshot(now(61)), usableUntil)?.copy())
        assertNull(WeatherKeeper.forDash(snapshot(now(61)), usableUntil + 1))
    }

    @Test
    fun `the request carries the place to a tenth of a degree, with points, and the fields of the weather screen`() {
        val url = WeatherKeeper.url(DayNight.Place(-23.5, -46.6))
        assertTrue(url, url.startsWith("https://api.open-meteo.com/v1/forecast?latitude=-23.5&longitude=-46.6&"))
        for (field in listOf("temperature_2m", "wind_gusts_10m", "surface_pressure", "uv_index", "sunrise", "forecast_days=14", "timezone=auto")) {
            assertTrue("$field in $url", url.contains(field))
        }
        // A finer place is cut to a tenth in the request itself, whatever the caller kept.
        assertTrue(WeatherKeeper.url(DayNight.Place(-23.5505, -46.6333)).contains("latitude=-23.6&longitude=-46.6&"))
    }
}

/** The units of the weather section follow what the dash says (03 03, 03 04). */
class WeatherUnitsTest {
    @Test
    fun `the default is Celsius and kilometres`() {
        val u = WeatherUnits()
        assertEquals("24 °C", u.temp(23.6))
        assertEquals("-3 °C", u.temp(-2.6))
        assertEquals("12 km/h", u.speed(12.4))
    }

    @Test
    fun `Fahrenheit and miles come from what the dash said`() {
        val u = WeatherUnits.of(com.reau.app.dash.DashReport.settings(mapOf(
            "0303" to com.reau.app.dash.DashReading("AA", 0L, 1), "0304" to com.reau.app.dash.DashReading("AA", 0L, 1),
        )))
        assertEquals(true, u.fahrenheit)
        assertEquals(true, u.miles)
        assertEquals("74 °F", u.temp(23.6))
        assertEquals("32 °F", u.temp(0.0))
        assertEquals("19", u.number(-7.0))
        assertEquals("8 mph", u.speed(12.4))
    }

    @Test
    fun `a dash that never said, or said what nobody knows, leaves Celsius and kilometres`() {
        assertEquals(WeatherUnits(), WeatherUnits.of(com.reau.app.dash.DashReport.settings(emptyMap())))
        assertEquals(WeatherUnits(), WeatherUnits.of(com.reau.app.dash.DashReport.settings(mapOf("0303" to com.reau.app.dash.DashReading("77", 0L, 1)))))
    }
}

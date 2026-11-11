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
    fun `with the switch off the weather stays usable until three hours, as always, however old`() {
        val fetched = snapshot(now(61))
        val at = fun(ageMs: Long) = 1_000L + ageMs
        assertEquals(DashCommands.Weather(24, 3), WeatherKeeper.forDash(fetched, at(WeatherKeeper.FRESH_MS + 1), freshOnly = false))
        assertEquals(DashCommands.Weather(24, 3), WeatherKeeper.forDash(fetched, at(2 * 60 * 60_000L), freshOnly = false))
        assertEquals(DashCommands.Weather(24, 3), WeatherKeeper.forDash(fetched, at(WeatherKeeper.USABLE_MS), freshOnly = false))
        assertNull(WeatherKeeper.forDash(fetched, at(WeatherKeeper.USABLE_MS + 1), freshOnly = false))
    }

    @Test
    fun `with the switch on the weather leaves the dash after half an hour`() {
        val fetched = snapshot(now(61))
        assertEquals(30 * 60_000L, WeatherKeeper.FRESH_MS)
        assertEquals(DashCommands.Weather(24, 3), WeatherKeeper.forDash(fetched, 1_000L, freshOnly = true))
        assertEquals(DashCommands.Weather(24, 3), WeatherKeeper.forDash(fetched, 1_000L + WeatherKeeper.FRESH_MS, freshOnly = true))
        assertNull(WeatherKeeper.forDash(fetched, 1_000L + WeatherKeeper.FRESH_MS + 1, freshOnly = true))
        assertNull(WeatherKeeper.forDash(fetched, 1_000L + 2 * 60 * 60_000L, freshOnly = true))
    }

    @Test
    fun `the switch does not change what the weather says, only for how long`() {
        for (freshOnly in listOf(false, true)) {
            assertEquals(DashCommands.Weather(24, 3), WeatherKeeper.forDash(snapshot(now(61)), 2_000L, freshOnly = freshOnly))
            assertNull(WeatherKeeper.forDash(snapshot(now(61, Double.NaN)), 2_000L, freshOnly = freshOnly))
            assertNull(WeatherKeeper.forDash(null, 2_000L, freshOnly = freshOnly))
        }
    }

    @Test
    fun `the heartbeat and the weather screen read the switch from the live settings`() {
        val live = java.io.File("src/main/java/com/reau/app/service/LiveSides.kt").readText()
        assertTrue(live.contains("setup.weather(tuned?.experimentWeatherAge == true)"))
        assertTrue(live.contains("WeatherKeeper.forDash(freshOnly = freshOnly)"))
        val screen = java.io.File("src/main/java/com/reau/app/ui/WeatherScreen.kt").readText()
        assertTrue(screen.contains("WeatherKeeper.forDash(freshOnly = SettingsStore.open(context).load().experimentWeatherAge)"))
    }

    @Test
    fun `no weather, no current reading, or one older than three hours is told to the dash as none`() {
        assertNull(WeatherKeeper.forDash(null, 2_000L))
        // A temperature that is not a number (a broken answer) is no weather for the dash: rounding it threw.
        assertNull(WeatherKeeper.forDash(snapshot(now(61, Double.NaN)), 2_000L))
        assertNull(WeatherKeeper.forDash(snapshot(now(61, Double.POSITIVE_INFINITY)), 2_000L))
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
        // The next two hours in quarters (the one now and seven more), for the dash's info ring.
        assertTrue(url, url.contains("minutely_15=precipitation,weather_code,wind_gusts_10m&forecast_minutely_15=8"))
        // A finer place is cut to a tenth in the request itself, whatever the caller kept.
        assertTrue(WeatherKeeper.url(DayNight.Place(-23.5505, -46.6333)).contains("latitude=-23.6&longitude=-46.6&"))
    }

    /** Audit of 20/10/2026: a clock set back made the weather look newer than it was, so it was kept and told to the dash. */
    @Test
    fun `the age of this run's fetch comes off the monotonic clock, not the wall clock`() {
        val fetched = WeatherKeeper.Snapshot(PointForecast(now(61), emptyList(), emptyList()), fetchedAtMs = 10_000_000L, monoAtMs = 1_000L)
        // The wall clock went back by hours; three hours and a second went by on the monotonic one.
        val wallAfterSetBack = 5_000_000L
        val monoLater = 1_000L + WeatherKeeper.USABLE_MS + 1
        assertEquals(WeatherKeeper.USABLE_MS + 1, fetched.ageMs(wallAfterSetBack, monoLater))
        assertNull("too old to tell the dash", WeatherKeeper.forDash(fetched, wallAfterSetBack, monoLater))
        assertEquals("a fresh one still goes", DashCommands.Weather(24, 3), WeatherKeeper.forDash(fetched, wallAfterSetBack, 61_000L))
        assertTrue("and is not asked for again at 15 minutes less a second",
            fetched.ageMs(wallAfterSetBack, 1_000L + WeatherKeeper.REFRESH_MS - 1_000) < WeatherKeeper.REFRESH_MS)
    }

    @Test
    fun `what an earlier run kept ages by the wall clock, and a time in the future is not trusted`() {
        val kept = snapshot(now(61), at = 10_000L)
        assertEquals(5_000L, kept.ageMs(15_000L, 1L))
        assertEquals("a clock that is before the fetch: stale, fetched again", Long.MAX_VALUE, kept.ageMs(9_000L, 1L))
        assertNull(WeatherKeeper.forDash(kept, 9_000L, 1L))
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

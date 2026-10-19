package com.reau.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reau.app.weather.AirQuality
import com.reau.app.weather.WeatherKeeper
import com.reau.app.weather.WeatherParser
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The weather screen (a shortcut of its own): the switch, and with it on everything a full weather screen has. */
@RunWith(AndroidJUnit4::class)
class WeatherScreenTest {
    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var on by mutableStateOf(false)
    private var toggles = mutableListOf<Boolean>()
    private var backs = 0

    @Before
    fun setUp() {
        WeatherKeeper.clear(context)
        compose.setContent {
            WeatherScreen(on, { toggles += it; on = it }) { backs++ }
        }
    }

    /** An answer in Open-Meteo's own shape, with the hours and days starting now (the section shows the next ones). */
    private fun answer(): String {
        val hour = LocalDateTime.now().withMinute(0).withSecond(0).withNano(0)
        val hours = (0 until 24).map { hour.plusHours(it.toLong()) }
        val today = LocalDate.now()
        val days = (0 until 14).map { today.plusDays(it.toLong()) }
        fun list(values: List<String>) = values.joinToString(",", "[", "]")
        return """
            {"current":{"temperature_2m":23.6,"apparent_temperature":25.1,"relative_humidity_2m":61,"precipitation":0.2,"weather_code":61,
              "wind_speed_10m":12.4,"wind_direction_10m":200,"wind_gusts_10m":30.2,"surface_pressure":1009.5,"cloud_cover":80,"is_day":1},
             "hourly":{"time":${list(hours.map { "\"$it\"" })},
              "temperature_2m":${list(hours.map { "22.0" })},"precipitation_probability":${list(hours.map { "40" })},
              "weather_code":${list(hours.map { "61" })},"uv_index":${list(hours.map { "5.0" })}},
             "daily":{"time":${list(days.map { "\"$it\"" })},"temperature_2m_max":${list(days.map { "27.0" })},"temperature_2m_min":${list(days.map { "18.0" })},
              "precipitation_probability_max":${list(days.map { "70" })},"uv_index_max":${list(days.map { "8.0" })},
              "sunrise":${list(days.map { "\"${it}T06:12\"" })},"sunset":${list(days.map { "\"${it}T18:47\"" })},
              "daylight_duration":${list(days.map { "44400.0" })},"wind_speed_10m_max":${list(days.map { "20.0" })},
              "wind_gusts_10m_max":${list(days.map { "40.0" })},"weather_code":${list(days.map { "63" })}}}
        """.trimIndent()
    }

    private fun seedWeather() = compose.runOnIdle {
        WeatherKeeper.seed(WeatherKeeper.Snapshot(WeatherParser.parse(answer()), System.currentTimeMillis(), AirQuality(42, 18.0, 9.0, 60.0)))
    }

    @Test
    fun theTopBarHasTheTitleAndGoesBack() {
        compose.onNodeWithTag("weather_title").assertTextContains("Clima")
        compose.onNodeWithTag("back").performClick()
        assertEquals(1, backs)
    }

    @Test
    fun offAtFirstItShowsTheSwitchAndNothingOfTheWeather() {
        compose.onNodeWithTag("dash_weather").assertIsOff()
        compose.onNodeWithTag("weather_state").assertDoesNotExist()
        compose.onNodeWithTag("weather_now").assertDoesNotExist()
        compose.onNodeWithTag("weather_chart").assertDoesNotExist()
    }

    @Test
    fun theSwitchSavesAndExplainsItself() {
        compose.onNodeWithTag("dash_weather").performClick()
        assertEquals(listOf(true), toggles)
        compose.onNodeWithTag("dash_weather").assertIsOn()
        compose.onNodeWithTag("dash_weather_info").performScrollTo().performClick()
        compose.onNodeWithText("toda vez que você abre o REau", substring = true).assertIsDisplayed()
    }

    @Test
    fun withTheSwitchOnAndAWeatherItShowsAllOfIt() {
        on = true
        seedWeather()
        compose.onNodeWithTag("weather_now").performScrollTo().assertTextContains("24 °C", substring = true)
        // The hours one under another: eight, and no ninth.
        compose.onNodeWithTag("weather_hour_0").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("weather_hour_7").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("weather_hour_8").assertDoesNotExist()
        // Today and the seven after it: eight days, and no ninth.
        compose.onNodeWithTag("weather_day_0").performScrollTo().assertTextContains("27 °C", substring = true).assertTextContains("18 °C", substring = true)
        compose.onNodeWithTag("weather_day_7").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("weather_day_8").assertDoesNotExist()
        compose.onNodeWithTag("weather_chart").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("weather_wind").performScrollTo().assertTextContains("12 km/h SSO", substring = true).assertTextContains("Rajadas de 30 km/h", substring = true)
        compose.onNodeWithTag("weather_uv").performScrollTo().assertTextContains("8 Muito alto", substring = true)
        compose.onNodeWithTag("weather_air").performScrollTo().assertTextContains("42 Boa", substring = true).assertTextContains("PM10 18", substring = true)
        compose.onNodeWithTag("weather_sunrise").performScrollTo().assertTextContains("06:12", substring = true)
        compose.onNodeWithTag("weather_moon").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("weather_sent").performScrollTo().assertTextContains("24 °C", substring = true).assertTextContains("chuva", substring = true)
        // Open-Meteo's licence (CC BY 4.0) asks for the credit where its data shows.
        compose.onNodeWithTag("weather_credit").performScrollTo().assertTextContains("Open-Meteo.com", substring = true)
            .assertTextContains("open-meteo.com", substring = true)
    }

    @Test
    fun withTheSwitchOffTheWeatherKeptIsNotShown() {
        seedWeather()
        compose.onNodeWithTag("weather_now").assertDoesNotExist()
        compose.onNodeWithTag("weather_day_0").assertDoesNotExist()
        compose.onNodeWithTag("weather_air").assertDoesNotExist()
        compose.onNodeWithTag("weather_credit").assertDoesNotExist()
    }

    @Test
    fun switchedOffAfterBeingOnItShowsNothingAndTheKeeperForgets() {
        on = true
        seedWeather()
        compose.onNodeWithTag("weather_now").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("dash_weather").performScrollTo().performClick()
        compose.onNodeWithTag("weather_now").assertDoesNotExist()
        assertEquals(null, WeatherKeeper.snapshot.value)
    }

    @Test
    fun theUnitsAreTheOnesTheDashSaid() {
        on = true
        seedWeather()
        compose.runOnIdle {
            com.reau.app.dash.DashReadings.clear()
            com.reau.app.dash.DashReadings.onValue(0x03, 0x03, byteArrayOf(0xAA.toByte()))
            com.reau.app.dash.DashReadings.onValue(0x03, 0x04, byteArrayOf(0xAA.toByte()))
            com.reau.app.dash.DashReadings.flush()
        }
        compose.onNodeWithTag("weather_now").performScrollTo().assertTextContains("74 °F", substring = true)
        compose.onNodeWithTag("weather_wind").performScrollTo().assertTextContains("8 mph", substring = true)
        compose.runOnIdle { com.reau.app.dash.DashReadings.clear() }
    }
}

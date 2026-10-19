// The forecast's data classes and its parser ([WeatherParser]); [WeatherCondition] has the dash's icon.
package com.reau.app.weather

import org.json.JSONObject

/**
 * Current conditions at one point. Every numeric field but the temperature is nullable: a model can omit any of them for a
 * place, and the screen then shows "not said" rather than a guessed number.
 */
data class CurrentWeather(
    val temperatureC: Double,
    val apparentTemperatureC: Double?,
    val relativeHumidityPercent: Int?,
    val precipitationMm: Double?,
    val weatherCode: Int?,
    val windSpeedKmh: Double?,
    val windDirectionDeg: Int?,
    val windGustsKmh: Double?,
    val pressureHpa: Double?,
    val cloudCoverPercent: Int?,
    val isDay: Boolean?,
)

/** One hour of the forecast; [timeIso] is Open-Meteo's own local ISO 8601 string (`"2026-09-27T15:00"`, in the place's zone). */
data class HourlyWeather(
    val timeIso: String,
    val temperatureC: Double,
    val precipitationProbabilityPercent: Int?,
    val weatherCode: Int?,
    val uvIndex: Double?,
)

/** One day of the forecast; [dateIso] is `"2026-09-27"`, sunrise and sunset are local timestamps. */
data class DailyWeather(
    val dateIso: String,
    val tempMaxC: Double,
    val tempMinC: Double,
    val precipitationProbabilityMaxPercent: Int?,
    val uvIndexMax: Double?,
    val sunriseIso: String?,
    val sunsetIso: String?,
    val daylightDurationSec: Double?,
    val windSpeedMaxKmh: Double?,
    val windGustsMaxKmh: Double?,
    val weatherCode: Int?,
)

/** Everything one fetch returns for one point. */
data class PointForecast(
    val current: CurrentWeather?,
    val hourly: List<HourlyWeather>,
    val daily: List<DailyWeather>,
)

/**
 * Coarse condition of Open-Meteo's `weather_code` (WMO table), with what the dash can draw ([dashIcon]: its `06 08`,
 * 1 cloudy, 2 thunder, 3 rain, 4 snow, 5 sun; the dash has no fog, no partly cloudy). The name the rider reads is in the
 * resources (`weather_condition_*`, picked by `conditionName` in WeatherScreen).
 */
enum class WeatherCondition(val dashIcon: Int?) {
    CLEAR(5),
    PARTLY_CLOUDY(5),
    CLOUDY(1),
    FOG(1),
    DRIZZLE(3),
    RAIN(3),
    SNOW(4),
    THUNDERSTORM(2),

    /** No code, or one outside the table: never guess a condition. */
    UNKNOWN(null);

    companion object {
        fun forCode(code: Int?): WeatherCondition = when (code) {
            null -> UNKNOWN
            0 -> CLEAR
            1, 2 -> PARTLY_CLOUDY
            3 -> CLOUDY
            45, 48 -> FOG
            51, 53, 55, 56, 57 -> DRIZZLE
            61, 63, 65, 66, 67, 80, 81, 82 -> RAIN
            71, 73, 75, 77, 85, 86 -> SNOW
            95, 96, 99 -> THUNDERSTORM
            else -> UNKNOWN
        }
    }
}

/** Open-Meteo's answer turned into [PointForecast]. Kept apart from the network so a test can feed it a saved answer. */
object WeatherParser {
    fun parse(json: String): PointForecast {
        val root = JSONObject(json)
        return PointForecast(
            current = root.optJSONObject("current")?.let(::parseCurrent),
            hourly = root.optJSONObject("hourly")?.let(::parseHourly) ?: emptyList(),
            daily = root.optJSONObject("daily")?.let(::parseDaily) ?: emptyList(),
        )
    }

    private fun JSONObject.doubleOrNull(name: String): Double? = if (has(name) && !isNull(name)) optDouble(name) else null
    private fun JSONObject.intOrNull(name: String): Int? = if (has(name) && !isNull(name)) optInt(name) else null

    private fun parseCurrent(o: JSONObject): CurrentWeather? {
        // The temperature gates the rest: without it the reading is not usable.
        val temp = o.doubleOrNull("temperature_2m") ?: return null
        return CurrentWeather(
            temperatureC = temp,
            apparentTemperatureC = o.doubleOrNull("apparent_temperature"),
            relativeHumidityPercent = o.intOrNull("relative_humidity_2m"),
            precipitationMm = o.doubleOrNull("precipitation"),
            weatherCode = o.intOrNull("weather_code"),
            windSpeedKmh = o.doubleOrNull("wind_speed_10m"),
            windDirectionDeg = o.intOrNull("wind_direction_10m"),
            windGustsKmh = o.doubleOrNull("wind_gusts_10m"),
            pressureHpa = o.doubleOrNull("surface_pressure"),
            cloudCoverPercent = o.intOrNull("cloud_cover"),
            isDay = o.intOrNull("is_day")?.let { it != 0 },
        )
    }

    /**
     * Open-Meteo's hourly and daily blocks are columnar: one array per field, all as long as `time`. A shorter or missing
     * array leaves that field null at that index; an entry goes only when the value every entry needs is missing.
     */
    private fun parseHourly(o: JSONObject): List<HourlyWeather> {
        val time = o.optJSONArray("time") ?: return emptyList()
        val temp = o.optJSONArray("temperature_2m")
        val precipProb = o.optJSONArray("precipitation_probability")
        val code = o.optJSONArray("weather_code")
        val uv = o.optJSONArray("uv_index")
        val out = ArrayList<HourlyWeather>(time.length())
        for (i in 0 until time.length()) {
            val t = temp?.optDouble(i)?.takeUnless { it.isNaN() } ?: continue
            out += HourlyWeather(
                timeIso = time.optString(i),
                temperatureC = t,
                precipitationProbabilityPercent = precipProb?.optInt(i, -1)?.takeIf { it >= 0 },
                weatherCode = code?.optInt(i, -1)?.takeIf { it >= 0 },
                uvIndex = uv?.optDouble(i)?.takeUnless { it.isNaN() },
            )
        }
        return out
    }

    private fun parseDaily(o: JSONObject): List<DailyWeather> {
        val date = o.optJSONArray("time") ?: return emptyList()
        val tMax = o.optJSONArray("temperature_2m_max")
        val tMin = o.optJSONArray("temperature_2m_min")
        val precipProbMax = o.optJSONArray("precipitation_probability_max")
        val uvMax = o.optJSONArray("uv_index_max")
        val sunrise = o.optJSONArray("sunrise")
        val sunset = o.optJSONArray("sunset")
        val daylightSec = o.optJSONArray("daylight_duration")
        val windMax = o.optJSONArray("wind_speed_10m_max")
        val gustsMax = o.optJSONArray("wind_gusts_10m_max")
        val code = o.optJSONArray("weather_code")
        val out = ArrayList<DailyWeather>(date.length())
        for (i in 0 until date.length()) {
            val max = tMax?.optDouble(i)?.takeUnless { it.isNaN() }
            val min = tMin?.optDouble(i)?.takeUnless { it.isNaN() }
            if (max == null || min == null) continue
            out += DailyWeather(
                dateIso = date.optString(i),
                tempMaxC = max,
                tempMinC = min,
                precipitationProbabilityMaxPercent = precipProbMax?.optInt(i, -1)?.takeIf { it >= 0 },
                uvIndexMax = uvMax?.optDouble(i)?.takeUnless { it.isNaN() },
                sunriseIso = sunrise?.optString(i)?.takeIf { it.isNotEmpty() },
                sunsetIso = sunset?.optString(i)?.takeIf { it.isNotEmpty() },
                daylightDurationSec = daylightSec?.optDouble(i)?.takeUnless { it.isNaN() },
                windSpeedMaxKmh = windMax?.optDouble(i)?.takeUnless { it.isNaN() },
                windGustsMaxKmh = gustsMax?.optDouble(i)?.takeUnless { it.isNaN() },
                weatherCode = code?.optInt(i, -1)?.takeIf { it >= 0 },
            )
        }
        return out
    }
}

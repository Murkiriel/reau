// Air quality (the reading and the severity bands), UV severity, the moon's phase and the wind's points of the compass.
// The parser reads a string, the request is the keeper's; the compass points are in Portuguese (L, O).
package com.reau.app.weather

import org.json.JSONObject

/** Current air quality at one point; every field is nullable, "not said" rather than a guessed number. */
data class AirQuality(val usAqi: Int?, val pm10: Double?, val pm25: Double?, val ozoneUgM3: Double?)

/** Air quality has its own Open-Meteo host (`air-quality-api.open-meteo.com`); a failure there never breaks the forecast. */
object AirQualityParser {
    /** The reading, or null when even `us_aqi` is missing: the one field the badge needs. */
    fun parse(json: String): AirQuality? {
        val current = JSONObject(json).optJSONObject("current")
        if (current == null || !current.has("us_aqi") || current.isNull("us_aqi")) return null
        fun number(name: String): Double? = if (current.has(name) && !current.isNull(name)) current.optDouble(name) else null
        return AirQuality(current.optInt("us_aqi"), number("pm10"), number("pm2_5"), number("ozone"))
    }
}

/** The US AQI bands (EPA): 0-50 good, 51-100 moderate, 101-150 unhealthy for sensitive groups, 151-200, 201-300, 301+. */
enum class AirQualitySeverity { GOOD, MODERATE, UNHEALTHY_SENSITIVE, UNHEALTHY, VERY_UNHEALTHY, HAZARDOUS, UNKNOWN;

    companion object {
        fun forUsAqi(usAqi: Int?): AirQualitySeverity = when {
            usAqi == null -> UNKNOWN
            usAqi <= 50 -> GOOD
            usAqi <= 100 -> MODERATE
            usAqi <= 150 -> UNHEALTHY_SENSITIVE
            usAqi <= 200 -> UNHEALTHY
            usAqi <= 300 -> VERY_UNHEALTHY
            else -> HAZARDOUS
        }
    }
}

/** The international UV index bands: 0-2 low, 3-5 moderate, 6-7 high, 8-10 very high, 11+ extreme. */
enum class UvSeverity { LOW, MODERATE, HIGH, VERY_HIGH, EXTREME, UNKNOWN;

    companion object {
        fun forIndex(uvIndex: Double?): UvSeverity = when {
            uvIndex == null -> UNKNOWN
            uvIndex < 3.0 -> LOW
            uvIndex < 6.0 -> MODERATE
            uvIndex < 8.0 -> HIGH
            uvIndex < 11.0 -> VERY_HIGH
            else -> EXTREME
        }
    }
}

/** Eight phases of the moon. */
enum class MoonPhase { NEW, WAXING_CRESCENT, FIRST_QUARTER, WAXING_GIBBOUS, FULL, WANING_GIBBOUS, LAST_QUARTER, WANING_CRESCENT }

/**
 * The moon's phase with no network (Open-Meteo has no moon data): days since a known new moon, modulo the synodic month,
 * in eight slices. Right to about a day, which is all a badge needs.
 */
object MoonPhaseCalculator {
    internal const val SYNODIC_MONTH_DAYS = 29.530588853

    /** 2000-01-06 18:14 UTC, a real new moon. */
    internal const val KNOWN_NEW_MOON_MS = 947182440000L

    fun phaseFor(epochMs: Long): MoonPhase = phaseForAge(((epochMs - KNOWN_NEW_MOON_MS) / 86_400_000.0).mod(SYNODIC_MONTH_DAYS))

    internal fun phaseForAge(ageDays: Double): MoonPhase {
        val eighth = SYNODIC_MONTH_DAYS / 8.0
        val half = eighth / 2.0 // the new, quarter and full moons are centred on their slices
        return when {
            ageDays < half -> MoonPhase.NEW
            ageDays < half + eighth -> MoonPhase.WAXING_CRESCENT
            ageDays < half + eighth * 2 -> MoonPhase.FIRST_QUARTER
            ageDays < half + eighth * 3 -> MoonPhase.WAXING_GIBBOUS
            ageDays < half + eighth * 4 -> MoonPhase.FULL
            ageDays < half + eighth * 5 -> MoonPhase.WANING_GIBBOUS
            ageDays < half + eighth * 6 -> MoonPhase.LAST_QUARTER
            ageDays < half + eighth * 7 -> MoonPhase.WANING_CRESCENT
            else -> MoonPhase.NEW
        }
    }
}

/** The point of the compass the wind comes from, 0 (north) to 15 (north-northwest) in steps of 22.5 degrees; the names are the resource `wind_points`. */
fun windPointIndex(degrees: Int): Int = ((degrees.mod(360) / 22.5) + 0.5).toInt().mod(WIND_POINTS)

private const val WIND_POINTS = 16

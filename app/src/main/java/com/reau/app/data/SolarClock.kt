package com.reau.app.data

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * How high the sun stands for a place and an instant, the one thing the day/night theme
 * ([DayNight][com.reau.app.service.DayNight]) needs to tell light from dark.
 *
 * It follows the equations NOAA publishes for its solar calculator (General Solar Position Calculations, public domain),
 * which work in Julian centuries since J2000: the sun's mean longitude and anomaly, the equation of centre, the apparent
 * longitude and the corrected obliquity give the declination; the equation of time gives the true solar time, and from it
 * the hour angle. The answer is the geometric elevation (no refraction), good to a few hundredths of a degree, far finer
 * than the dead band of [resolveIsLight] asks for.
 *
 * The elevation is worked in angle rather than looked up as sunrise and sunset times, so no time zone database is needed
 * and the polar day and night come out right by themselves.
 */
object SolarClock {
    private const val MS_PER_DAY = 86_400_000.0
    private const val UNIX_EPOCH_JD = 2_440_587.5
    private const val J2000_JD = 2_451_545.0
    private const val DAYS_PER_CENTURY = 36_525.0
    private const val MINUTES_PER_DAY = 1_440.0

    /** Sun elevation in degrees above the horizon (negative: below it) at [lat]/[lng] (east positive) at [epochMs]. */
    fun elevationDeg(lat: Double, lng: Double, epochMs: Long): Double {
        val julianDay = epochMs / MS_PER_DAY + UNIX_EPOCH_JD
        val t = (julianDay - J2000_JD) / DAYS_PER_CENTURY

        val meanLongitude = Math.toRadians(wrap(280.46646 + t * (36000.76983 + 0.0003032 * t), 360.0))
        val meanAnomaly = Math.toRadians(wrap(357.52911 + t * (35999.05029 - 0.0001537 * t), 360.0))
        val eccentricity = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)

        val centre = sin(meanAnomaly) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
            sin(2 * meanAnomaly) * (0.019993 - 0.000101 * t) +
            sin(3 * meanAnomaly) * 0.000289
        val trueLongitude = Math.toDegrees(meanLongitude) + centre
        val node = Math.toRadians(125.04 - 1934.136 * t)
        val apparentLongitude = Math.toRadians(trueLongitude - 0.00569 - 0.00478 * sin(node))

        val meanObliquity = 23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0
        val obliquity = Math.toRadians(meanObliquity + 0.00256 * cos(node))
        val declination = asin(sin(obliquity) * sin(apparentLongitude))

        val y = tan(obliquity / 2).let { it * it }
        val equationOfTimeMin = 4.0 * Math.toDegrees(
            y * sin(2 * meanLongitude) -
                2 * eccentricity * sin(meanAnomaly) +
                4 * eccentricity * y * sin(meanAnomaly) * cos(2 * meanLongitude) -
                0.5 * y * y * sin(4 * meanLongitude) -
                1.25 * eccentricity * eccentricity * sin(2 * meanAnomaly),
        )

        val minutesUtc = (epochMs % 86_400_000L) / 60_000.0
        val trueSolarMin = wrap(minutesUtc + equationOfTimeMin + 4.0 * lng, MINUTES_PER_DAY)
        val hourAngle = Math.toRadians(trueSolarMin / 4.0 - 180.0)

        val latRad = Math.toRadians(lat)
        val sinElevation = sin(latRad) * sin(declination) + cos(latRad) * cos(declination) * cos(hourAngle)
        return Math.toDegrees(asin(sinElevation.coerceIn(-1.0, 1.0)))
    }

    /**
     * Light or dark from the sun's elevation, with a dead band so the theme does not flip back and forth at dusk and dawn:
     * above [LIGHT_ABOVE_DEG] it is light, below [DARK_BELOW_DEG] it is dark, and in between it stays as it was
     * ([previouslyLight]).
     */
    fun resolveIsLight(elevationDeg: Double, previouslyLight: Boolean): Boolean = when {
        elevationDeg > LIGHT_ABOVE_DEG -> true
        elevationDeg < DARK_BELOW_DEG -> false
        else -> previouslyLight
    }

    private const val DARK_BELOW_DEG = -6.0
    private const val LIGHT_ABOVE_DEG = 0.0

    /** [value] brought into 0 until [period]. */
    private fun wrap(value: Double, period: Double): Double = ((value % period) + period) % period
}

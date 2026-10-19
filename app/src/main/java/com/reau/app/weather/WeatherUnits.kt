package com.reau.app.weather

import kotlin.math.roundToInt

/**
 * The units the weather section shows, the ones the dash itself says it uses (`03 03` and `03 04`, which the dash says
 * on every connection and [com.reau.app.dash.DashReadings] keeps). Never connected, or a value nobody knows: Celsius
 * and kilometres, as before.
 * The weather itself comes in Celsius and km/h; this only changes what is written. What REau tells the dash stays Celsius
 * (`06 10`, the unit the dash reads).
 */
data class WeatherUnits(val fahrenheit: Boolean = false, val miles: Boolean = false) {
    /** [celsius] as a rounded number in the unit, with no sign of the unit ("24"); for a range written once at the end. */
    fun number(celsius: Double): String = (if (fahrenheit) celsius * 9.0 / 5.0 + 32.0 else celsius).roundToInt().toString()

    /** "24 °C" or "75 °F". */
    fun temp(celsius: Double): String = "${number(celsius)} ${if (fahrenheit) "°F" else "°C"}"

    /** "12 km/h" or "8 mph". */
    fun speed(kmh: Double): String = if (miles) "${(kmh * KM_TO_MILES).roundToInt()} mph" else "${kmh.roundToInt()} km/h"

    companion object {
        private const val KM_TO_MILES = 0.621371

        /** The units from what the dash said ([com.reau.app.dash.DashReport.Settings]). */
        fun of(settings: com.reau.app.dash.DashReport.Settings): WeatherUnits = WeatherUnits(
            fahrenheit = settings.temperature == com.reau.app.dash.DashReport.TempUnit.FAHRENHEIT,
            miles = settings.distance == com.reau.app.dash.DashReport.DistanceUnit.MILES,
        )
    }
}

package com.reau.app.service

import android.content.Context
import androidx.core.content.edit
import kotlin.math.roundToInt

/**
 * Where the phone is, for the sun only (the automatic theme by the sunrise and the sunset, with
 * "Usar a localização" on). Android gives no position to an app in the background, which is where the projection runs,
 * so the place is learned while REau is on the screen ([PhonePlace.learn]) and kept, rounded to a tenth of a degree
 * (about 11 km): enough for the sun. It stays on the phone: never in the log, never sent. Off, it is forgotten.
 */
object SunPlace {
    private const val PREFS = "reau_sun_place"
    private const val KEY_LAT = "lat"
    private const val KEY_LNG = "lng"
    private const val TENTHS = 10.0

    fun rounded(lat: Double, lng: Double): DayNight.Place = DayNight.Place(tenth(lat), tenth(lng))

    /** The place kept, or null with none (or with the switch off). */
    fun saved(context: Context, useLocation: Boolean): DayNight.Place? {
        if (!useLocation) return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_LAT)) return null
        return DayNight.Place(Double.fromBits(prefs.getLong(KEY_LAT, 0)), Double.fromBits(prefs.getLong(KEY_LNG, 0)))
    }

    fun save(context: Context, place: DayNight.Place) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putLong(KEY_LAT, place.lat.toRawBits())
            putLong(KEY_LNG, place.lng.toRawBits())
        }
    }

    fun forget(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { clear() }
    }

    private fun tenth(value: Double): Double = (value * TENTHS).roundToInt() / TENTHS
}

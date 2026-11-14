package com.reau.app.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.edit
import com.reau.app.settings.LocationUse
import kotlin.math.roundToInt

/**
 * Where the phone is, for the sun only (the automatic theme by the sunrise and the sunset, with
 * "Usar a localização" on). Android gives no position to an app in the background, which is where the projection runs,
 * so the place is learned while REau is on the screen ([PhonePlace.learn]) and kept, rounded to a tenth of a degree
 * (about 11 km): enough for the sun. It stays on the phone: never in the log, and the only thing of it that leaves is the
 * weather request (the weather on the dash and the info ring). Off, it is forgotten. With the location permission revoked in
 * Android it is not forgotten (nothing of the rider's is erased) but it is not used either: [saved] gives none, so neither the
 * weather request nor the sun reads it, and it is used again if the permission comes back.
 */
object SunPlace {
    private const val PREFS = "reau_sun_place"
    private const val KEY_LAT = "lat"
    private const val KEY_LNG = "lng"
    private const val TENTHS = 10.0

    fun rounded(lat: Double, lng: Double): DayNight.Place = DayNight.Place(tenth(lat), tenth(lng))

    /** The place kept, or null with none, with the switch off, or with no location permission given (revoked in Android). */
    fun saved(context: Context, useLocation: Boolean): DayNight.Place? =
        saved(useLocation, { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) { read(context) }

    /** [saved] with the question to Android ([granted], by the permission's name) and the store ([read]) handed in: both permissions are asked, then [keptIf] decides. */
    internal fun saved(useLocation: Boolean, granted: (String) -> Boolean, read: () -> DayNight.Place?): DayNight.Place? {
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        return keptIf(useLocation, fine, coarse, read)
    }

    /** The place [read] gives, but only where [LocationUse.mayReadPlace] allows: otherwise null, and [read] is not even called. */
    internal fun keptIf(useLocation: Boolean, fineGranted: Boolean, coarseGranted: Boolean, read: () -> DayNight.Place?): DayNight.Place? =
        if (LocationUse.mayReadPlace(useLocation, fineGranted, coarseGranted)) read() else null

    private fun read(context: Context): DayNight.Place? {
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

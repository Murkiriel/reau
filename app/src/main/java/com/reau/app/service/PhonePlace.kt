package com.reau.app.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import android.content.pm.PackageManager
import com.reau.app.settings.LocationUse

/**
 * The phone's location permission as REau uses it, both with the rider's switch "Usar a localização" on: to read the
 * Wi-Fi list (the dash's full name, [LocationUse]) and the place for the sun ([SunPlace], for the
 * theme by the sunrise and the sunset), learned while REau is on the screen: Android hands no position to an app
 * in the background, which is where the projection runs.
 */
object PhonePlace {
    /**
     * With the switch on and the permission given, the phone's last known place (no new fix asked: nothing turns the GPS
     * on) is kept for the sun, rounded ([SunPlace]); with the switch off, the place kept is forgotten.
     */
    @SuppressLint("MissingPermission") // checked just above
    fun learn(context: Context, useLocation: Boolean) {
        if (!useLocation) return SunPlace.forget(context)
        fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        if (!granted(Manifest.permission.ACCESS_COARSE_LOCATION) && !granted(Manifest.permission.ACCESS_FINE_LOCATION)) return
        val manager = context.getSystemService(LocationManager::class.java) ?: return
        manager.allProviders.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
            ?.let { SunPlace.save(context, SunPlace.rounded(it.latitude, it.longitude)) }
    }

    /** Whether REau may read the Wi-Fi list (to learn the dash's whole name): the location permission (LocationUse). */
    fun mayScan(context: Context): Boolean =
        LocationUse.mayScan(locationGranted = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
}

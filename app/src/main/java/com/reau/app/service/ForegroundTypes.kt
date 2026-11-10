package com.reau.app.service

import android.annotation.SuppressLint
import android.content.pm.ServiceInfo
import android.os.Build

/**
 * The projection's foreground service types, the richest first. With Gemini (behind the
 * switch "Gemini pelo joystick", with the permission given) the microphone type comes along: without it Android hands a
 * service in the background silence instead of the rider's voice. With the precise location permission given the
 * location type comes along too, whatever the info ring: without it Android gives a service in the background no location with the phone's
 * screen off, and an empty Wi-Fi list (the dash's network cannot be seen in it while REau is behind another app). If Android
 * refuses a type, the ones without it are tried next, down to the connected device alone, as before: the extras are lost, never the projection.
 */
object ForegroundTypes {
    /** The microphone type exists from Android 11 ([sdk] 30), the location one from Android 10; before them, the connected device alone. */
    @SuppressLint("InlinedApi") // each type is used only where [sdk] has it
    fun ladder(microphone: Boolean, location: Boolean = false, sdk: Int = Build.VERSION.SDK_INT): List<Int> {
        val device = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        val mic = if (microphone && sdk >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        val loc = if (location && sdk >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        return listOfNotNull(
            (device or mic or loc).takeIf { mic != 0 && loc != 0 },
            (device or mic).takeIf { mic != 0 },
            (device or loc).takeIf { loc != 0 },
            device,
        )
    }

    /** The types in [types] by name, for the ride log ("connectedDevice|microphone|location"); "none" for 0. No network names, no allocation per package. */
    @SuppressLint("InlinedApi") // only names
    fun names(types: Int): String {
        val known = listOf(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE to "connectedDevice",
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE to "microphone",
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION to "location",
        )
        val named = known.filter { types and it.first != 0 }.map { it.second }
        val rest = types and known.fold(0) { all, k -> all or k.first }.inv()
        return (named + listOfNotNull("other(0x${Integer.toHexString(rest)})".takeIf { rest != 0 })).joinToString("|").ifEmpty { "none" }
    }
}

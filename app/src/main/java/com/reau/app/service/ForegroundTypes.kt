package com.reau.app.service

import android.annotation.SuppressLint
import android.content.pm.ServiceInfo
import android.os.Build

/**
 * The projection's foreground service types, the richest first. With Gemini (behind the
 * switch "Gemini pelo joystick", with the permission given) the microphone type comes along: without it Android hands a
 * service in the background silence instead of the rider's voice. If Android refuses it, the connected device alone
 * is tried next, as before: Gemini is lost, never the projection.
 */
object ForegroundTypes {
    /** The microphone type exists from Android 11 ([sdk] 30); before it, the connected device alone. */
    @SuppressLint("InlinedApi") // the microphone type is used only where [sdk] has it
    fun ladder(microphone: Boolean, sdk: Int = Build.VERSION.SDK_INT): List<Int> {
        val device = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        val withMicrophone = microphone && sdk >= Build.VERSION_CODES.R
        return if (withMicrophone) listOf(device or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, device) else listOf(device)
    }
}

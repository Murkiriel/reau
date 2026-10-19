package com.reau.app

import android.os.Build

/** Which kind of device the instrumented tests are running on, for the few that depend on the emulator image's own setup. */
object TestDevice {
    /** True on an Android emulator (the images carry a stub of Android Auto and a Wi-Fi called "AndroidWifi"). */
    val isEmulator: Boolean
        get() = Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish" || Build.FINGERPRINT.contains("generic", ignoreCase = true)
}

package com.reau.app.dash

import android.annotation.SuppressLint
import android.net.ConnectivityManager
import android.os.Build

/**
 * What counts as a dash's Wi-Fi name, and learning it.
 *
 * The dash checks its full name inside the encrypted handshake, so the prefix every dash's name begins with (`RE_`)
 * is never enough. Android only lets an app read the Wi-Fi scan, or the name of the network it joined, while the app
 * is in use (location "while in use"); a projection usually starts in the background, when Android Auto connects.
 * So the name is learned when the rider has REau on screen ([Learner]), and asked of Android once connected where
 * the system allows it ([callbackFlags]).
 */
object DashName {
    /** Android 12's flag, copied by value at compile time; [callbackFlags] passes it only on Android 12 and later. */
    @SuppressLint("InlinedApi")
    const val FLAG_INCLUDE_LOCATION_INFO = ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO

    /**
     * A name a dash can have: longer than the factory prefix `RE_` alone, which is how every dash's name begins and no
     * dash's whole name. A rider may type the whole name as the prefix (a way round a name that was not learned), so a
     * name equal to a longer prefix counts.
     */
    fun isDashName(ssid: String): Boolean = ssid.length > DashPairing.DEFAULT_PREFIX.length

    /** A dash name that also starts with the prefix being looked for: what a scan or the Wi-Fi may answer. */
    fun isFullName(ssid: String, prefix: String): Boolean = isDashName(ssid) && ssid.startsWith(prefix)

    /** The flags of the bike Wi-Fi's network callback: from Android 12 on, ask for the network's name too. */
    fun callbackFlags(sdk: Int = Build.VERSION.SDK_INT): Int = if (sdk >= Build.VERSION_CODES.S) FLAG_INCLUDE_LOCATION_INFO else 0

    /** With the app on screen: if the dash's name is not known yet, look for it in the last Wi-Fi scan and keep it. */
    class Learner(
        private val known: () -> String,
        private val prefix: () -> String,
        private val scan: (String) -> String?,
        private val remember: (String) -> Unit,
    ) {
        /** The name learned now, or null (already known, or nothing in the scan). */
        fun learn(): String? {
            if (isDashName(known())) return null
            val p = prefix()
            return scan(p)?.takeIf { isFullName(it, p) }?.also(remember)
        }
    }
}

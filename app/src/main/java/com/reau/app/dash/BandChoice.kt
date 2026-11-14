package com.reau.app.dash

import android.net.wifi.ScanResult
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import com.reau.app.data.RideDiagnostics

/**
 * The experimental band of the request for the dash's Wi-Fi ([com.reau.app.settings.REauSettings.experimentWifiBand]). The dash's access
 * point is on 2.4 GHz; a request that names the band spares Android the other bands when it looks for it. Android takes the band in a
 * request from Android 12 (API 31), and its documentation says a phone may not support asking for a particular band: the request then ends
 * unanswered. So that a phone like that still reaches the dash with the switch on, a request with the band that ends unanswered is followed
 * by one without it, and then the band is asked again: at worst every other request is the one REau always made.
 *
 * Off (the default) no request names a band. The decision is plain logic ([forRequest], [unanswered]); [nameOn] and [endedUnanswered] are
 * what the manager calls, from its request and from the request's end.
 */
internal class BandChoice {
    private var askedLast = false
    private var skipNext = false

    /** Whether the request about to be made names the 2.4 GHz band: the switch is [on], Android is 12 or later ([sdk] 31), and the last one with it was answered. */
    fun forRequest(on: Boolean, sdk: Int): Boolean {
        askedLast = on && sdk >= MIN_SDK && !skipNext
        skipNext = false
        return askedLast
    }

    /**
     * For the manager, with the [builder] of request number [request] it is about to make: decides ([forRequest]) and, when the band is
     * asked, names it on the builder and writes the ride log's line. Without the band the builder is left as it was and nothing is written.
     */
    fun nameOn(builder: WifiNetworkSpecifier.Builder, on: Boolean, request: Int) =
        nameOn(on, Build.VERSION.SDK_INT, request) {
            // Already so by [nameOn]'s own check; written out where the lint can see it.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setBand(ScanResult.WIFI_BAND_24_GHZ)
        }

    /** [nameOn] with the Android's version ([sdk]) and the naming of the band ([name], called only when the band is asked) handed in. */
    internal fun nameOn(on: Boolean, sdk: Int, request: Int, name: () -> Unit) {
        if (!forRequest(on, sdk) || sdk < Build.VERSION_CODES.S) return
        name()
        RideDiagnostics.log("wifi", requestLine(request))
    }

    /** For the manager, when a request ends with no network: writes the line if it named the band, and notes it ([unanswered]). */
    fun endedUnanswered() {
        if (askedLast) RideDiagnostics.warn("wifi", UNANSWERED_LINE)
        unanswered()
    }

    /** Whether the last request named the band. */
    val lastNamedTheBand: Boolean get() = askedLast

    /** The request ended with no network: if it named the band, the next one does not. */
    fun unanswered() {
        if (askedLast) skipNext = true
        askedLast = false
    }

    companion object {
        /** Android 12: the first with a band in the request. */
        const val MIN_SDK = 31

        /** The ride log's line for request number [request] when it names the band; a request without it writes nothing new. */
        fun requestLine(request: Int): String = "request #$request names the 2.4 GHz band (experiment)"

        /** The ride log's line when a request with the band ended unanswered. */
        const val UNANSWERED_LINE = "the request that named the 2.4 GHz band (experiment) was not answered: the next one is made without the band"
    }
}

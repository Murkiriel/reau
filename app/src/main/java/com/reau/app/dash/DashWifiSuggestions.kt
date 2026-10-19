package com.reau.app.dash

import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.util.Log
import com.reau.app.data.RideDiagnostics
import com.reau.app.settings.SettingsStore

/**
 * Keeps the OS's WifiNetworkSuggestion for the dash in sync with the "join the bike's Wi-Fi by itself" switch.
 * This is what lets Android join the dash's Wi-Fi on its
 * own whenever it is in range, without the app running a scan and without the network picker. It does not start
 * streaming: the projection still asks for the network, which then answers at once.
 *
 * Stateless: a suggestion is either in effect or not. Every call site (app start, the switch, the dash's name learned
 * or forgotten) goes through [sync], which is idempotent and cheap.
 */
object DashWifiSuggestions {
    private const val TAG = "DashWifiSuggestions"

    /**
     * The suggestion that should be in effect, or null. A suggestion needs the EXACT name -- there is no "any RE_*
     * network" suggestion -- so it stays off until the dash's whole name is known (never the factory prefix alone).
     */
    internal fun suggestionFor(enabled: Boolean, ssid: String, password: String): WifiNetworkSuggestion? {
        if (!enabled) return null
        if (!DashName.isDashName(ssid)) return null
        return WifiNetworkSuggestion.Builder()
            .setSsid(ssid)
            .apply { if (password.isNotBlank()) setWpa2Passphrase(password) }
            .build()
    }

    /** Reads the switch and what is remembered of the dash, and applies them. */
    fun sync(context: Context) {
        val settings = runCatching { SettingsStore.open(context).load() }.getOrNull()
        val pairing = runCatching { DashPairing.open(context) }.getOrNull()
        apply(context, settings?.autoJoinWifi == true, pairing?.ssid.orEmpty(), pairing?.password.orEmpty())
    }

    /** [sync] off the main thread (binder calls): after the switch changed or the dash was forgotten. */
    fun syncSoon(context: Context) {
        kotlin.concurrent.thread(name = "wifi-suggestion") { sync(context) }
    }

    /**
     * Clears any suggestion this app registered and adds the current one, if any. Logged to the ride log, since this
     * runs at moments (app start, a name just learned) when nobody watches logcat.
     */
    fun apply(context: Context, enabled: Boolean, ssid: String, password: String) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        // Removing with an empty list clears every suggestion this app has registered (documented WifiManager
        // behaviour): at most one suggestion, the current dash, is ever in effect.
        runCatching { wifi.removeNetworkSuggestions(emptyList()) }
            .onFailure {
                Log.w(TAG, "removeNetworkSuggestions failed: ${it.message}")
                RideDiagnostics.log("wifi", "suggestion remove FAILED: ${it.message}")
            }
        // Called from Application.onCreate: a password the builder refuses (not ASCII) must not take the app down.
        val suggestion = runCatching { suggestionFor(enabled, ssid, password) }
            .onFailure {
                Log.w(TAG, "the suggestion was refused: ${it.message}")
                RideDiagnostics.log("wifi", "auto-join suggestion refused: ${it.javaClass.simpleName}: ${it.message}")
            }
            .getOrNull() ?: run {
            // Only with the switch on: with it off (the default) this would be a line in every ride log for nothing.
            if (enabled) RideDiagnostics.log("wifi", "auto-join suggestion: none in effect (dash name known=${DashName.isDashName(ssid)})")
            return
        }
        val status = runCatching { wifi.addNetworkSuggestions(listOf(suggestion)) }
            .onFailure { Log.w(TAG, "addNetworkSuggestions threw: ${it.message}") }
            .getOrNull()
        val masked = DashWifiManager.maskSsid(ssid)
        Log.i(TAG, "addNetworkSuggestions('$masked') -> status=$status")
        // status 0 == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS; anything else means Android will not auto-join.
        RideDiagnostics.log(
            "wifi", "auto-join suggestion for '$masked' -> status=$status" + (if (status != 0) " (0=success; non-zero means Android rejected it)" else ""),
        )
    }
}

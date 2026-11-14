package com.reau.app.service

/**
 * The phone's own word on its Wi-Fi, for the ride log (`[system] wifi …`), written while the app lives. The `phone side of the loss:` line is read once, at
 * the loss; the system's lines about it (`wpa_supplicant`, `ConnectivityService`) need `READ_LOGS` and only adb collects them. These are what the app may
 * hear with no new permission: the Wi-Fi radio on and off, the supplicant's states (the association, the handshake, the disconnection), the network
 * state, and the Wi-Fi networks that come and go. No network name, no address and no signal: the state names and counts only (the broadcasts'
 * extras that carry a name, the BSSID, the SSID and the network info's text, are never read).
 *
 * Plain logic with the strings and numbers passed in; [SystemEvents] holds the receivers and writes what these return.
 */
internal object WifiEvents {
    /** The radio's state ([android.net.wifi.WifiManager.WIFI_STATE_CHANGED_ACTION]'s `wifi_state`), as [com.reau.app.dash.PhoneSide.radioName] names it. */
    fun radioLine(radio: String): String = "wifi radio $radio"

    /** The supplicant states that are only a scan going on: ten of them a minute on a phone with nothing to join. Not written. */
    private val SCAN_NOISE = setOf("SCANNING", "INTERFACE_DISABLED", "DORMANT", "UNINITIALIZED")

    /** The ERROR_AUTHENTICATING of [android.net.wifi.WifiManager.EXTRA_SUPPLICANT_ERROR]. */
    private const val ERROR_AUTHENTICATING = 1

    /**
     * The supplicant's state changes as lines, one for each change of state: the same state twice in a row is told once, and the scanning states not at
     * all. An [error] (the broadcast's `supplicantError`) other than none is said with the state it came with.
     */
    class Supplicant {
        private var last: String? = null

        @Synchronized
        fun line(state: String?, error: Int): String? {
            val name = state ?: "?"
            val repeated = name == last && error == 0
            if (name in SCAN_NOISE || repeated) return null
            last = name
            val why = if (error == ERROR_AUTHENTICATING) " (authentication failed)" else if (error != 0) " (error $error)" else ""
            return "wifi supplicant: $name$why"
        }
    }

    /** The network state's changes ([android.net.NetworkInfo.DetailedState] names, such as CONNECTED or DISCONNECTED), once for each change. */
    class NetworkState {
        private var last: String? = null

        @Synchronized
        fun line(detailed: String?): String? {
            val name = detailed ?: "?"
            if (name == last) return null
            last = name
            return "wifi network state: $name"
        }
    }

    /** The Wi-Fi networks the phone has up (a network's id is the key; it is not written), for the lines of their coming and going. */
    class Networks {
        private val up = HashSet<Int>()

        @Synchronized
        fun available(id: Int): String {
            up += id
            return "wifi network up (${up.size} now)"
        }

        @Synchronized
        fun losing(id: Int, maxMsToLive: Int): String = "wifi network losing in $maxMsToLive ms (${if (id in up) "one of ${up.size}" else "not known"})"

        @Synchronized
        fun lost(id: Int): String {
            val known = up.remove(id)
            return "wifi network lost (${up.size} left${if (known) "" else ", not one REau had heard"})"
        }

        companion object {
            const val UNAVAILABLE = "wifi network request unavailable"
        }
    }
}

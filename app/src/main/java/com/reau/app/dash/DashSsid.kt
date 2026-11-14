package com.reau.app.dash

/**
 * The rules of the dash's network name: reading it as Android gives it, matching it against the one wanted, and masking it for the logs.
 * Pure, so they are tested without a real ConnectivityManager/WifiManager (DashWifiManagerHelpersTest, DashFailureTruthTest).
 */
internal object DashSsid {
    // Android returns this sentinel from WifiInfo.getSsid() when it can't read the SSID.
    private const val WifiManagerUnknownSsid = "<unknown ssid>"

    /** Strips the quotes Android wraps SSIDs in and collapses the "can't read it" sentinel to
     * blank, so callers can treat "blank" as the one signal for "no usable SSID". */
    internal fun normalizeSsid(raw: String?): String =
        raw.orEmpty().trim('"').let { if (it == WifiManagerUnknownSsid) "" else it }

    /** Does [ssid] match what we're trying to connect to — exact or prefix, per [pendingPrefix]? */
    /**
     * Whether the last scan shows the dash: true, false, or null when the scan says nothing. Android
     * hands an app in the background an EMPTY scan instead of refusing, and an empty scan was read as "the dash is
     * not nearby" with the bike beside the phone; somewhere with no network at all is rare enough to be unknown.
     */
    internal fun scanVerdict(ssids: List<String>?, pendingSsid: String, pendingPrefix: Boolean): Boolean? =
        ssids?.takeIf { it.isNotEmpty() }?.any { matchesPendingSsid(it, pendingSsid, pendingPrefix) }

    internal fun matchesPendingSsid(ssid: String, pendingSsid: String, pendingPrefix: Boolean): Boolean =
        if (pendingPrefix) ssid.startsWith(pendingSsid) else ssid == pendingSsid

    /** The actual decision inside [DashWifiManager.findAlreadyConnectedDashNetwork]'s `cm.allNetworks` scan,
     * pulled out so it's unit-testable without a real ConnectivityManager — everything else in
     * that function is framework enumeration, this is the one real judgment call: a candidate
     * network with no readable SSID (some OEMs hide it without location permission) is accepted
     * as a best-effort match; one with a readable SSID must equal [activeSsid] exactly. */
    internal fun candidateMatchesActive(candidateSsid: String?, activeSsid: String): Boolean =
        candidateSsid == null || candidateSsid == activeSsid

    /** Redacts an SSID for logs: the first three and the last two characters of a name of eight or more, two of a shorter one (with
     * the first rule a name of five or six came out whole, or all but one character) — the dash's SSID
     * ties a log line to a specific bike/rider, so exported diagnostics (Settings → Export
     * logs) shouldn't carry it in the clear: such logs get pulled/shared far more casually
     * than expected. */
    private const val SHORT_SSID = 7

    internal fun maskSsid(ssid: String): String {
        if (ssid.isBlank()) return ""
        if (ssid.length <= SHORT_SSID) return ssid.take(2) + "**"
        return ssid.take(3) + "****" + ssid.takeLast(2)
    }
}

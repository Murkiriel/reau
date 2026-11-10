package com.reau.app.dash

// What the ride log says about the Wi-Fi list beyond the dash itself, for the reader of the log only (no decision reads any of it; no network
// names, no BSSID). Added on 08/11/2026 after the ride logs of 05 to 07/11: an empty list could not be told from "no network around" (the
// four empty readings of 05/11 17:59 to 18:00 were a place with no other network), and the 5.6 s between the dash in the scan and the request
// could not be split between the app and Android's own scan.
internal const val MICROS_PER_SECOND_SCAN = 1_000_000L
private const val MS_PER_SECOND_SCAN = 1_000L

/**
 * The last reading of the Wi-Fi list that had networks: how many, when, and the stamp of its newest result. The list is read in several places
 * (the lookup, the watches, the request lines), so one process-wide instance ([ScanHistory]) keeps what every one of them saw; an empty or
 * unreadable reading changes nothing here, which is what the empty reading is compared with.
 */
internal class ScanTrail {
    @Volatile private var count = 0
    @Volatile private var atMs = 0L
    @Volatile private var newestStampUs = 0L

    /** A reading of [networks] networks at [nowMs] (milliseconds since boot) whose newest result is stamped [newestUs] (microseconds since boot; 0: none). */
    fun note(networks: Int, nowMs: Long, newestUs: Long) {
        if (networks <= 0) return
        count = networks
        atMs = nowMs
        newestStampUs = newestUs
    }

    /** `last non-empty reading: 12 networks, 31 s ago, newest result 35 s old`, or `last non-empty reading: none yet`. */
    fun describe(nowMs: Long, nowUs: Long): String {
        if (atMs == 0L) return "last non-empty reading: none yet"
        val ago = ((nowMs - atMs) / MS_PER_SECOND_SCAN).coerceAtLeast(0)
        val stamp = newestAge(newestStampUs, nowUs)?.let { ", newest result $it s old" } ?: ""
        return "last non-empty reading: $count networks, $ago s ago$stamp"
    }

    fun forget() {
        count = 0
        atMs = 0L
        newestStampUs = 0L
    }
}

/** The one trail of the process. */
internal object ScanHistory {
    val trail = ScanTrail()
}

/** How old the stamp [stampUs] is at [nowUs] (both microseconds since boot), in whole seconds; null for a stamp that says nothing (zero or negative). */
internal fun newestAge(stampUs: Long, nowUs: Long): Long? =
    if (stampUs <= 0L) null else ((nowUs - stampUs) / MICROS_PER_SECOND_SCAN).coerceAtLeast(0)

/** The newest stamp of [seen], or 0 for none. */
internal fun newestStamp(seen: List<ScanSeen>): Long = seen.maxOfOrNull { it.timestampUs }?.coerceAtLeast(0) ?: 0L

/**
 * Whether the phone is joined to a Wi-Fi, in words of one token for the reader: `no`, `dash`, `other` or `dash+other`. [wifiNetworks] is how
 * many Wi-Fi networks the phone has up and [dashAmongThem] whether the dash's own is one of them.
 */
internal fun joinedWifiWords(wifiNetworks: Int, dashAmongThem: Boolean): String {
    val others = wifiNetworks - if (dashAmongThem) 1 else 0
    return when {
        dashAmongThem && others > 0 -> "dash+other"
        dashAmongThem -> "dash"
        others > 0 -> "other"
        else -> "no"
    }
}

/**
 * The line for one look at the Wi-Fi list by a watch of the dash (the pause after a failed request, the wait for a first approval, a
 * lost network), written only when the look says something new: the dash came into the list or left it, or the list's newest stamp moved
 * (Android made a new scan). So the ride log has, for each scan Android made while REau was looking, the age of the dash's stamp at REau's
 * look, which splits "painel visto → pedido" into Android's part and the app's. At most [MAX_LINES] per instance, then one line says it stops.
 */
internal class ScanLookLog(private val maxLines: Int = MAX_LINES) {
    private var lines = 0
    private var started = false
    private var lastDash = false
    private var lastReadable = true
    private var lastDashStamp = 0L
    private var lastNewest = 0L
    private var stopped = false

    /** The line for this look, or null when it adds nothing. [seen] null is an unreadable list; [nowUs] microseconds since boot. */
    fun line(seen: List<ScanSeen>?, wanted: String, prefix: Boolean, nowUs: Long): String? {
        val dash = seen.orEmpty().filter { DashWifiManager.matchesPendingSsid(it.ssid, wanted, prefix) }
        val dashStamp = dash.maxOfOrNull { it.timestampUs } ?: 0L
        val newest = seen?.let(::newestStamp) ?: 0L
        val same = started && lastReadable == (seen != null) && lastDash == dash.isNotEmpty() && lastDashStamp == dashStamp && lastNewest == newest
        started = true
        lastReadable = seen != null
        lastDash = dash.isNotEmpty()
        lastDashStamp = dashStamp
        lastNewest = newest
        return if (same || stopped) null else say(seen, dash.size, dashStamp, newest, nowUs)
    }

    private fun say(seen: List<ScanSeen>?, dashCount: Int, dashStamp: Long, newest: Long, nowUs: Long): String {
        if (lines >= maxLines) {
            stopped = true
            return "scan look: $maxLines lines written, the next looks of this session are not"
        }
        lines++
        val newestWords = newestAge(newest, nowUs)?.let { ", newest result $it s old" } ?: ""
        return when {
            seen == null -> "scan look: the list cannot be read"
            seen.isEmpty() -> "scan look: the list is empty"
            dashCount == 0 -> "scan look: the dash is not among ${seen.size} networks$newestWords"
            else -> "scan look: the dash is in the list of ${seen.size} networks, its result " +
                (newestAge(dashStamp, nowUs)?.let { "$it s old" } ?: "unstamped") + newestWords
        }
    }

    companion object {
        const val MAX_LINES = 80
    }
}

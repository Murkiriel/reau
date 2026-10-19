package com.reau.app.dash

/** Where the phone remembers which dash network the rider already let REau join (DashPairing keeps it). */
interface JoinMemory {
    /** The name of the dash network last joined from REau; empty if it never was, or it was forgotten. */
    var joinedSsid: String
}

/**
 * The first time REau asks Android for the dash's network, Android stops to ask the rider ("Conectar ao dispositivo") and
 * waits for a tap; from then on the network is approved and the join is silent. The human tap has to fit inside the wait
 * of the request REau holds out, or Android shows "Algo deu errado" under the rider's finger and starts over. A real
 * session took minutes to get past that dialog with a 30 s wait. So a network never joined from REau gets a longer wait;
 * every other request keeps the waits it always had.
 */
object DashJoin {
    /** How long the request for a network the rider has not approved yet waits: time to read the dialog and tap. */
    const val FIRST_APPROVAL_TIMEOUT_MS = 120_000

    /**
     * Whether asking for [wantedSsid] ([wantedIsPrefix]: only its beginning) is the first time, so the system will ask the
     * rider: nothing joined before, or what was joined is another network. [joinedSsid] null says the phone has no memory
     * of it (a Wi-Fi manager made without one), and then nothing is assumed: false.
     */
    fun needsApproval(joinedSsid: String?, wantedSsid: String, wantedIsPrefix: Boolean): Boolean =
        joinedSsid != null && (joinedSsid.isBlank() || !DashWifiManager.matchesPendingSsid(joinedSsid, wantedSsid, wantedIsPrefix))

    /**
     * How long a request waits: a reconnection of this session [everConnected] keeps [reconnectMs], the first approval of
     * a network gets [FIRST_APPROVAL_TIMEOUT_MS], any other first request of a session [firstMs] (the old wait).
     */
    fun timeoutMs(everConnected: Boolean, firstApproval: Boolean, reconnectMs: Int, firstMs: Int): Int = when {
        everConnected -> reconnectMs
        firstApproval -> maxOf(FIRST_APPROVAL_TIMEOUT_MS, firstMs)
        else -> firstMs
    }

    /**
     * How old a sighting of the dash in the Wi-Fi scan may be and still count as "it is there now". The first approval is the
     * one request that opens Android's dialog over whatever the rider is doing; with the dash off or away it only says "no
     * device found", and again at each request. So that request waits for a sighting. A minute covers the pace of the system's
     * own scans (screen on, not joined: some tens of seconds) yet lets a bike switched off drop out of "seen" about as soon as
     * the next retry; the scan list itself may keep a gone dash for minutes, which is why its own timestamp is read.
     */
    const val SIGHTING_FRESH_MS = 60_000L

    private const val MICROS_PER_MILLI = 1_000L

    /**
     * Whether the scan [seen] has the dash ([wantedSsid], only its beginning with [wantedIsPrefix]) seen at most
     * [SIGHTING_FRESH_MS] before [nowUs] (microseconds since boot, as the scan's own timestamps; given, not read, so a test
     * sets the clock). A sighting with a timestamp that cannot be (zero, negative or in the future) says nothing of its age
     * and counts: holding the request back for a clock the phone gets wrong would keep the dash from ever joining. An
     * unreadable scan (null) or an empty one has no sighting.
     */
    internal fun seenRecently(seen: List<ScanSeen>?, wantedSsid: String, wantedIsPrefix: Boolean, nowUs: Long): Boolean =
        seen.orEmpty().any {
            DashWifiManager.matchesPendingSsid(it.ssid, wantedSsid, wantedIsPrefix) &&
                (it.timestampUs <= 0 || it.timestampUs > nowUs || nowUs - it.timestampUs <= SIGHTING_FRESH_MS * MICROS_PER_MILLI)
        }

    /** Whether [name] is worth keeping as "joined": a full network name, not the bare prefix a prefix request was made with. */
    fun worthKeeping(name: String, pendingSsid: String, pendingIsPrefix: Boolean): Boolean =
        name.isNotBlank() && !(pendingIsPrefix && name == pendingSsid)
}

package com.reau.app.dash

/**
 * The dash whose Wi-Fi hung (ride log of 01/11/2026, 19:12 to 19:16): the dash went quiet in the middle of the ride, Android lost its
 * network, and for 3 min 54 s the dash stayed in the Wi-Fi scan with a good signal (-47 to -63 dBm, the same channel) while 23 requests in a
 * row ended unavailable in 6 s each. The rest of the dash (speed, trip) worked, only the picture was gone; it came back when the rider switched
 * the ignition off and on. REau cannot undo that, but it can tell the rider what does: a notification, once for the absence.
 *
 * Told apart from the bike being switched off by the scan: a dash that is off leaves it, a hung one stays. Pure, so the JVM tests cover it
 * (StuckDashWatchTest); the Wi-Fi manager feeds it the facts and shows the notification.
 *
 * This covers the dash that hangs **in the middle of a ride** (the Wi-Fi was joined, spoke, was lost). The older detection,
 * [JoinRules.Refusal.DASH_STUCK] (tested by DashStuckTest), is only evaluated in the manager's "initial connect" branch (before any join
 * of the projection, or after a quick refusal of a network that had worked) and only puts the words `dash_err_dash_stuck` on the screen; it
 * raises no notification. The two are independent.
 */
internal object DashStuck {
    /** Failed tries in a row, with the dash in the scan, that make it hung. Each try is about 10 s of request and pause (19:12:55 to 19:13:36: five). */
    const val MIN_TRIES = 3

    /** And the dash must have been away at least this long: three tries are about 30 s, which a restarting dash does not take. */
    const val MIN_ABSENT_MS = 30_000L

    /** A signal at least this good is a dash within reach: one that refuses is not too far to join. */
    const val GOOD_SIGNAL_DBM = -75

    /** The sighting must be at most this old to count as "there now" (the logs show them 3 to 20 s old with the screen on). */
    const val FRESH_SIGHTING_MS = 15_000L

    private const val MICROS_PER_MILLI = 1_000L

    /** What the scan says of the dash at the end of a try: it counts, it says nothing, or the dash is gone. */
    enum class Sighting { GOOD, UNKNOWN, GONE }

    /**
     * The scan [seen] as it bears on "the dash is there and could be joined" at [nowUs] (microseconds since boot, as the scan's stamps).
     * A readable scan with the dash not in it, or only as an entry older than [DashJoin.SIGHTING_FRESH_MS] (the list keeps a switched-off
     * dash for minutes), is [Sighting.GONE]: the bike is off or away. Fresh and at [GOOD_SIGNAL_DBM] or better is [Sighting.GOOD]. A scan that
     * cannot be read or is empty (the screen is off), a sighting between the two ages or a weak one say nothing: [Sighting.UNKNOWN].
     */
    fun sighting(seen: List<ScanSeen>?, wantedSsid: String, wantedIsPrefix: Boolean, nowUs: Long): Sighting {
        val dash = seen.orEmpty().filter { DashSsid.matchesPendingSsid(it.ssid, wantedSsid, wantedIsPrefix) }
        val freshest = dash.filter { it.timestampUs in 1..nowUs }.maxByOrNull { it.timestampUs }
        val ageMs = freshest?.let { (nowUs - it.timestampUs) / MICROS_PER_MILLI }
        return when {
            seen.isNullOrEmpty() -> Sighting.UNKNOWN
            dash.isEmpty() || !DashJoin.seenRecently(dash, wantedSsid, wantedIsPrefix, nowUs) -> Sighting.GONE
            ageMs != null && ageMs <= FRESH_SIGHTING_MS && dash.maxOf { it.level } >= GOOD_SIGNAL_DBM -> Sighting.GOOD
            else -> Sighting.UNKNOWN
        }
    }

    /** The line for the ride log, said once for an absence. */
    fun line(absentMs: Long, tries: Int, rssiDbm: Int?): String =
        "dash visible but refusing for ${absentMs / 1_000} s ($tries tries${rssiDbm?.let { ", $it dBm" } ?: ""}) — " +
            "dash Wi-Fi likely hung; rider told to cycle ignition"

    /** What a verdict says: how many tries and how long. */
    data class Verdict(val tries: Int, val absentMs: Long)
}

/**
 * Keeps the facts of one absence of the dash (from the Wi-Fi lost to the Wi-Fi back) and says, once, when it is a hung dash: it had spoken
 * in this projection ([lost] with `spoke`), and since the loss [DashStuck.MIN_TRIES] tries in a row ended with the dash in the scan with a good
 * signal ([failedTry]), over at least [DashStuck.MIN_ABSENT_MS]. A try with the dash gone from the scan starts the count over: that is the bike
 * switched off. Times are given (monotonic milliseconds), not read. Thread-safe: the system's callbacks and the watch call it.
 */
internal class StuckDashWatch {
    private var lostAtMs: Long? = null
    private var spoke = false
    private var tries = 0
    private var told = false

    /** The Wi-Fi was lost at [nowMs]; [dashSpoke]: the dash had spoken in this projection. Only the first loss of an absence counts. */
    @Synchronized
    fun lost(nowMs: Long, dashSpoke: Boolean) {
        if (lostAtMs != null) return
        lostAtMs = nowMs
        spoke = dashSpoke
        tries = 0
        told = false
    }

    /** A try for the dash's Wi-Fi ended without it, with the dash [sighting] in the scan; the verdict the first time it is a hung dash, else null. */
    @Synchronized
    fun failedTry(nowMs: Long, sighting: DashStuck.Sighting): DashStuck.Verdict? {
        val lostAt = lostAtMs ?: return null
        if (!spoke || told) return null
        when (sighting) {
            DashStuck.Sighting.GONE -> tries = 0
            DashStuck.Sighting.UNKNOWN -> Unit
            DashStuck.Sighting.GOOD -> tries++
        }
        val absent = nowMs - lostAt
        if (tries < DashStuck.MIN_TRIES || absent < DashStuck.MIN_ABSENT_MS) return null
        told = true
        return DashStuck.Verdict(tries, absent)
    }

    /** The dash is back (or the rider switched off): the absence is over. True when the rider had been told, so the notice goes. */
    @Synchronized
    fun back(): Boolean {
        val wasTold = told
        lostAtMs = null
        spoke = false
        tries = 0
        told = false
        return wasTold
    }
}

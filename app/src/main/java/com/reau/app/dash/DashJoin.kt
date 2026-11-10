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

    /** How long a request has to try on its own before a sighting of the dash makes REau put it out again. */
    const val REFILE_MIN_WAIT_MS = 2_000L

    /**
     * Whether a request still unanswered is to be put out again because the dash has just shown up in the scan. Observed in
     * the ride logs of 19/10/2026: a first request made while the bike was still waking up rode its whole 30 s with the dash
     * in the scan for the last 8 s, and the next one, put out 2 s after the first gave up, joined in 1.3 s. The system does
     * not look again at what it already searched for, so REau asks again when the dash appears. Never for the first approval
     * of a network ([firstApproval]: the system's dialog is up or about to be, and a new request would start it over), nor
     * after a join this session ([everConnected]: those requests are short and re-asked anyway), nor when the dash was
     * already in the scan when the request went out ([seenAtRequest]: then it is the system's to answer).
     */
    fun refileOnSighting(firstApproval: Boolean, everConnected: Boolean, seenAtRequest: Boolean, seenNow: Boolean, waitedMs: Long): Boolean =
        !firstApproval && !everConnected && !seenAtRequest && seenNow && waitedMs >= REFILE_MIN_WAIT_MS

    /** A request made with the dash already in the scan is left this long to join on its own before it is put out again. */
    const val REFILE_SIGHTED_MS = 8_000L

    /** How many times a first connection's request is put out again for a dash that was in the scan when it went out. */
    const val MAX_SIGHTED_REFILES = 2

    /**
     * Whether a request made with the dash **already in the scan** ([seenAtRequest]) is put out again because, [waitedMs] later, it has not
     * joined: Android answers such a request in 1.3 to 5.4 s in the logs of 19 and 20/10/2026, and one left the whole 30 s (the scan list
     * had the dash only as an old entry, or the search was slow with the screen off) before the next, put out right after, joined in 5.4 s.
     * Never for the first approval of a network ([firstApproval]: the system's dialog), nor after a join this session ([everConnected]); at most
     * [MAX_SIGHTED_REFILES] times ([refiles] so far) so a dash that really refuses is not asked in a loop.
     */
    @Suppress("LongParameterList") // one decision with its facts
    fun refileSightedAndSlow(firstApproval: Boolean, everConnected: Boolean, seenAtRequest: Boolean, waitedMs: Long, refiles: Int): Boolean =
        !firstApproval && !everConnected && seenAtRequest && waitedMs >= REFILE_SIGHTED_MS && refiles < MAX_SIGHTED_REFILES

    /** How long a request that is being answered (its network already joined) is left to finish before it is put out again. */
    const val HOLD_WHILE_JOINING_MS = 6_000L

    /**
     * Whether to hold back putting a request out again because the dash's Wi-Fi is **already joined** ([joiningNow]) while the
     * request is still unanswered: that network is held up by the request itself, and letting it go to ask again tears it down
     * (ride log of 26/10/2026, 08:52: the request released 22 s in, the "already connected" network reused at once, and it was
     * gone 0.2 s later, "Network is unreachable", 7 s lost). The request is given [HOLD_WHILE_JOINING_MS] since the first time it
     * was held ([heldMs]) to be answered; after that the old way applies, since a joined network that never answers is the
     * system's own and reusing it is what that path is for.
     */
    fun holdWhileJoining(joiningNow: Boolean, heldMs: Long): Boolean = joiningNow && heldMs < HOLD_WHILE_JOINING_MS

    /**
     * What [refileAfterLossHeld] decided: nothing to do, a refiling held back because the dash's Wi-Fi is being joined ([HELD_FIRST]: the
     * first time of this join, when the log says so), or a refiling.
     */
    enum class LossRefile { NO, HELD_FIRST, HELD, REFILE }

    /**
     * [refileAfterLoss] with the hold of [holdWhileJoining]: ride log of 31/10/2026, 10:06:56. The dash came back after 93 s, the loss
     * watch saw it in the scan and put the request out again 4.8 s after it went out, and 47 ms later reused the "already connected" Wi-Fi
     * of the very request it had just let go: the system took that network down, the reuse sat on a dead `Network` and every try after
     * it failed with EPERM for 4.5 minutes. [watchForTheDash] had the hold since 26/10; the watch over a loss did not. [joiningNow] is read
     * only when a refiling is due (it asks Android for its networks), and [hold] keeps since when the Wi-Fi has been joined.
     */
    @Suppress("LongParameterList") // the seven facts of [refileAfterLoss] and the two of the hold
    internal fun refileAfterLossHeld(
        firstApproval: Boolean,
        lostAtUs: Long,
        sightingStampUs: Long?,
        nowUs: Long,
        requestAgeMs: Long,
        inPause: Boolean,
        sinceLastRefileMs: Long?,
        hold: HoldWhileJoining,
        nowMs: Long,
        joiningNow: () -> Boolean,
    ): LossRefile {
        if (!refileAfterLoss(firstApproval, lostAtUs, sightingStampUs, nowUs, requestAgeMs, inPause, sinceLastRefileMs)) return LossRefile.NO
        val verdict = hold.holds(joiningNow(), nowMs)
        return when {
            !verdict.holds -> LossRefile.REFILE
            verdict.began -> LossRefile.HELD_FIRST
            else -> LossRefile.HELD
        }
    }

    /** The least between the failure of a request and the sighting that cuts the pause after it: the pace of the machine's first wait (2 s). */
    const val PAUSE_CUT_MIN_GAP_MS = 2_000L

    /** The least between two pauses cut by a sighting: a dash that flickers in the scan cannot turn the pauses into a request every few seconds. */
    const val PAUSE_CUT_EVERY_MS = 10_000L

    /**
     * Whether the dash showing up in the scan **in the pause between two requests** cuts the pause short, so the next request goes out at
     * once (ride log of 01/11/2026: the app was on before the bike, the dash was in the scan at 18:45:57, the pause of 30 s that began at
     * 18:45:38.7 ended at 18:46:08.7, and the request joined in 1.5 s: 11 s lost for nothing). The watch over a request ([refileOnSighting])
     * only looks while one is out, and the one over a first approval only while it waits for the dash; the pause of a network already approved
     * ([firstApproval] false) was not watched. The rules keep it from being a loop of requests: the dash must have been **out of the scan when the
     * request failed** ([seenWhenFailed] false: a dash that is there and refusing is the 10 s pace of the controller's, and the stuck-dash notice),
     * must be in it now ([seenNow]), [sinceFailureMs] must reach [minGapMs] ([PAUSE_CUT_MIN_GAP_MS]; a test makes it short), and [sinceLastCutMs] ([PAUSE_CUT_EVERY_MS]; null: none yet)
     * keeps one cut from following another too soon. One cut per failure: the watch ends with it.
     */
    @Suppress("LongParameterList") // one decision with its facts
    fun cutsPauseOnSighting(
        firstApproval: Boolean,
        seenWhenFailed: Boolean,
        seenNow: Boolean,
        sinceFailureMs: Long,
        sinceLastCutMs: Long?,
        minGapMs: Long = PAUSE_CUT_MIN_GAP_MS,
    ): Boolean =
        !firstApproval && !seenWhenFailed && seenNow && sinceFailureMs >= minGapMs &&
            (sinceLastCutMs == null || sinceLastCutMs >= PAUSE_CUT_EVERY_MS)

    /** The least a request put out after a loss is left alone, and the least between two refilings of one loss. */
    const val REFILE_MIN_GAP_MS = 4_000L

    /**
     * Whether, after the dash's Wi-Fi was lost, to ask for it again because the dash showed up. Ride logs of 20/10/2026: the dash
     * was back in the scan 10 s and 12 s before REau joined it, because the 4 s pause and the 10 s request ran their course.
     * A sighting counts only when it is newer than the loss ([lostAtUs], microseconds since boot, as the scan's stamps) and than
     * the latest request (so a dash that request already saw is the system's to answer, and one sighting does not refile a request
     * every 4 s). [sightingStampUs] is the newest stamp of the dash in the scan; null for none, and for a scan that is empty (the
     * screen is off) or unreadable: nothing is decided then. [requestAgeMs] is how long ago the latest request went out; [inPause]:
     * it is over and the 4 s pause before the next one is running, which a sighting cuts short. The same rules of a newer sighting
     * and of the gap between two refilings apply in the pause, so a dash in sight that refuses the join does not turn it into a
     * request every 2 s; only the 2 s a request is left alone first do not. Never for the first approval of a network
     * ([firstApproval]: the system's dialog). [sinceLastRefileMs] is null when this loss has not been refiled yet.
     */
    @Suppress("LongParameterList") // one decision with its seven facts; a holder class would only rename them
    fun refileAfterLoss(
        firstApproval: Boolean,
        lostAtUs: Long,
        sightingStampUs: Long?,
        nowUs: Long,
        requestAgeMs: Long,
        inPause: Boolean,
        sinceLastRefileMs: Long?,
    ): Boolean {
        if (firstApproval || sightingStampUs == null || sightingStampUs <= lostAtUs) return false
        val newerThanRequest = sightingStampUs > nowUs - requestAgeMs * MICROS_PER_MILLI
        val gapKept = sinceLastRefileMs == null || sinceLastRefileMs >= REFILE_MIN_GAP_MS
        return newerThanRequest && gapKept && (inPause || requestAgeMs >= REFILE_MIN_WAIT_MS)
    }

    /** Whether [name] is worth keeping as "joined": a full network name, not the bare prefix a prefix request was made with. */
    fun worthKeeping(name: String, pendingSsid: String, pendingIsPrefix: Boolean): Boolean =
        name.isNotBlank() && !(pendingIsPrefix && name == pendingSsid)
}

/**
 * Since when the dash's Wi-Fi has been seen joined while a request is unanswered, for [DashJoin.holdWhileJoining]: one per watch.
 * Not joined any more, the count is forgotten, so a watch that lasts minutes (a loss) holds the next join for its own
 * [DashJoin.HOLD_WHILE_JOINING_MS] too. Time is given, not read, so a test sets the clock.
 */
internal class HoldWhileJoining {
    /** [holds]: leave the request alone; [began]: the first time of this join, the moment to say so in the log. */
    data class Verdict(val holds: Boolean, val began: Boolean)

    private var since: Long? = null

    fun holds(joiningNow: Boolean, nowMs: Long): Verdict {
        if (!joiningNow) {
            since = null
            return Verdict(holds = false, began = false)
        }
        val began = since == null
        val from = since ?: nowMs.also { since = it }
        return Verdict(DashJoin.holdWhileJoining(true, nowMs - from), began)
    }
}

package com.reau.app.dash

/**
 * Rules of the Wi-Fi manager for a bike network that is not what it looks like (ride log of 31/10/2026, 10:06:56 to 10:11:28: the dash
 * was back on the air after 93 s and REau spent 4.5 minutes binding to a `Network` that did not exist any more, ten times, with EPERM).
 * Pure, so the JVM tests cover them (DashRecoveryTest).
 */
internal object JoinedNetwork {
    /** How long after taking a network the phone had joined by itself the manager checks that Android still knows it. */
    const val CHECK_AFTER_MS = 1_500L

    /**
     * Whether a network found "already connected" is usable: Android still has its capabilities ([capsKnown]: `getNetworkCapabilities`
     * is not null; it is null for a network that is gone, which `allNetworks` can still list for a moment) and they say Wi-Fi
     * ([wifiTransport]). Anything else is a network that died, and using it is what made every try fail with EPERM.
     */
    fun usable(capsKnown: Boolean, wifiTransport: Boolean): Boolean = capsKnown && wifiTransport

    /** The line for a network found dead when it was meant to be used, or some time after. */
    fun deadLine(network: Any?, whenWords: String): String =
        "the already-connected Wi-Fi (network $network) is not known to Android any more ($whenWords): not used, treated as lost"
}

/**
 * Whose a network is, for the ride log at the moment a socket is bound to it (ride log of 06/11/2026, 17:47:21: the network that was refused
 * was the one the phone had joined by itself through the suggestion, not an answer to REau's request, and nothing in the log said so).
 */
internal object NetworkOwner {
    /** [joinedByPhone]: taken as "already connected" (the system's own join: a suggestion or a saved network); else the answer to request [request]. */
    fun words(joinedByPhone: Boolean, request: Int, notRestricted: Boolean? = null, validated: Boolean? = null): String {
        val whose = if (joinedByPhone) "joined by the phone itself (a suggestion or a saved network), not the answer to REau's request #$request"
        else "the answer to REau's request #$request"
        return "$whose; not-restricted=${notRestricted ?: "?"}, validated=${validated ?: "?"}"
    }
}

/**
 * The refusal of a network is handled at once, but the projection has already been told the session failed and waits (up to 30 s after many
 * tries) before it tries the dash again; ride log of 06/11/2026, 17:47:21: the manager had another network up 20 ms after the refusal and the
 * dash was tried only 30 s later. [AFTER_MS] after the refusal, with the new network up, the projection is told to try now.
 */
internal object RefusalRetry {
    /** Long enough for the failed session to reach the projection (which only cuts a wait that already exists), short for the rider. */
    const val AFTER_MS = 2_000L

    /**
     * Whether the projection is told: the rider still wants the dash, no other refusal or switch-off came since ([superseded]), and a network is up
     * now ([connected], with [networkRefused] false). With the request still unanswered nothing is said: its answer tells the projection by itself.
     */
    fun due(wantConnected: Boolean, superseded: Boolean, connected: Boolean, networkRefused: Boolean): Boolean =
        wantConnected && !superseded && connected && !networkRefused

    fun line(network: Any?, afterMs: Long): String =
        "network $network took the place of the refused one $afterMs ms ago and is still up: the projection is told, it tries the dash now instead of waiting"
}

/**
 * What to do when binding a socket to the dash's network is refused with EPERM (a network that is dead, set aside by Android or held by a
 * VPN). The first refusal says the network is no good: it is dropped, the request let go and the network asked for again. If the new
 * network is refused too ([refused] again before any [bound]), asking again at once only repeats it, so the manager backs off: no
 * request until [Verdict.backoffMs] has passed, longer at each refusal in a row. A bind that works ([bound]) starts the count over.
 * Never the same network again: the manager keeps the refused ones out of "already connected".
 *
 * A network the phone joined by itself (a suggestion; not REau's to keep) is not REau's own request going wrong: when one is refused
 * and the one asked for in its place is a **different** network the phone joined again ([phoneJoined], not the same network), it earns
 * one more quick try ([PHONE_QUICK_MAX]) instead of the 15 s. The same network twice, or a refusal of REau's own request, backs off as before.
 */
internal class BindRefusals {
    enum class Step { ASK_AGAIN, BACK_OFF }

    /**
     * [inARow]: refusals since the last bind that worked, this one included; [sameNetwork]: the network is the one refused before; [tell]: this
     * is the refusal at which the rider is told (the [TELL_AT]th in a row, once per run: the next ones in the same run are not told again).
     */
    data class Verdict(val step: Step, val inARow: Int, val sameNetwork: Boolean, val backoffMs: Long, val tell: Boolean = false) {
        /** The log line: what was refused, whose the network was ([owner], [NetworkOwner.words]), what REau does about it. */
        fun line(network: Any?, owner: String? = null): String = "binding the socket to network $network was refused (EPERM, #$inARow in a row" +
            (if (sameNetwork) ", the same network again" else "") + (if (owner != null) ", $owner" else "") + "): the network is dropped and " +
            when (step) {
                Step.ASK_AGAIN -> "asked for again"
                Step.BACK_OFF -> "not asked for again for ${backoffMs / MS_PER_S} s"
            }
    }

    private var inARow = 0
    private var last: Any? = null
    private var phoneQuick = 0
    private var told = false

    /**
     * Told `true` at the refusal that tells the rider ([TELL_AT] in a row) and `false` when that is over ([bound]): the service shows or takes
     * away the notice. Called outside the lock, after the line of the log.
     */
    @Volatile var onTold: ((Boolean) -> Unit)? = null

    fun refused(network: Any?, phoneJoined: Boolean = false): Verdict {
        val verdict = synchronized(this) { countRefusal(network, phoneJoined) }
        if (verdict.tell) {
            com.reau.app.data.RideDiagnostics.warn("wifi", tellLine(verdict.inARow))
            onTold?.invoke(true)
        }
        return verdict
    }

    private fun countRefusal(network: Any?, phoneJoined: Boolean): Verdict {
        inARow++
        val tell = !told && inARow >= TELL_AT
        if (tell) told = true
        val same = last != null && last == network
        last = network
        val quick = inARow == 1 || (phoneJoined && !same && phoneQuick < PHONE_QUICK_MAX)
        if (quick && inARow > 1) phoneQuick++
        return if (quick) Verdict(Step.ASK_AGAIN, inARow, same, 0L, tell) else Verdict(Step.BACK_OFF, inARow, same, backoffMs(inARow), tell)
    }

    /**
     * A socket was bound to the network (or the session opened, or the rider switched off): the refusals so far were not a pattern. When the
     * rider had been told ([Verdict.tell]) the notice is taken away ([onTold] with `false`, after a line of the log). True then.
     */
    fun bound(): Boolean {
        val wasTold = synchronized(this) {
            val was = told
            inARow = 0
            last = null
            phoneQuick = 0
            told = false
            was
        }
        if (wasTold) {
            com.reau.app.data.RideDiagnostics.log(
                "wifi", "a socket was bound again (or REau was switched off): the notice of the refused network is taken away",
            )
            onTold?.invoke(false)
        }
        return wasTold
    }

    companion object {
        /**
         * Refusals in a row at which the rider is told. A refusal that is only a network still being set up goes away at the first or second
         * try (the quick one, then the 15 s one); the fourth in a row comes at least 45 s after the second (15 s and 30 s of back off between
         * them) without a single bind in between: the dash's network refused again and again (the ride log of 31/10/2026 had the same dead
         * network handed back every 30 s for 4.5 minutes), which only the rider can clear (a VPN, the phone's switch to mobile data).
         */
        const val TELL_AT = 4

        /** The log line of the refusal that tells the rider. */
        fun tellLine(inARow: Int): String =
            "binding the socket to the dash's network was refused $inARow times in a row (EPERM): rider told Android is refusing the network"

        /** The quick tries, after the first refusal, that a refusal of a network the phone joined by itself earns (a different network each). */
        const val PHONE_QUICK_MAX = 1
        const val BACKOFF_BASE_MS = 15_000L
        const val BACKOFF_MAX_MS = 60_000L
        private const val MS_PER_S = 1_000L
        private const val MAX_DOUBLINGS = 10

        /** 15 s at the second refusal in a row, 30 s at the third, then 60 s. */
        fun backoffMs(inARow: Int): Long =
            (BACKOFF_BASE_MS shl (inARow - 2).coerceIn(0, MAX_DOUBLINGS)).coerceAtMost(BACKOFF_MAX_MS)
    }
}

/**
 * The experimental retries of a refused bind ([com.reau.app.settings.REauSettings.experimentWifiBind]). Android sometimes refuses
 * `Network.bindSocket` with EPERM for a moment on a network that is fine (it is still being set up); [BindRefusals] and the manager let that
 * network go at once and ask for another. With the switch on, the session first tries the same network again, up to [MAX_RETRIES] times
 * with [PAUSE_MS] between, and only if it is still refused after them does the refusal go on to the manager as before. Off, there is
 * no retry: the first refusal goes on.
 */
internal object BindRetry {
    const val MAX_RETRIES = 5
    const val PAUSE_MS = 400L

    /** Whether the bind refused is tried again: the switch is on, it was an EPERM refusal of the network, and [retriesDone] are fewer than [MAX_RETRIES]. */
    fun again(enabled: Boolean, refusal: Boolean, retriesDone: Int): Boolean = enabled && refusal && retriesDone < MAX_RETRIES

    fun retryLine(network: Any?, retry: Int, pauseMs: Long = PAUSE_MS): String =
        "binding the socket to network $network was refused (EPERM): trying the same network again in $pauseMs ms (retry $retry of $MAX_RETRIES)"

    fun workedLine(network: Any?, retries: Int): String = "binding the sockets to network $network worked after $retries retries on the same network"
}

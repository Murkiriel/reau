package com.reau.app.dash

/**
 * What [DashWifiManager] read from one snapshot of Android's about the network (a `NetworkCapabilities`), as Android gave it: [mhz] and
 * [rssiDbm] are null without a `WifiInfo`, and [specifier] is null when Android does not say. [NetNotes] decides what of it is worth keeping.
 */
internal data class CapsRead(
    val mhz: Int?,
    val rssiDbm: Int?,
    val specifier: Boolean?,
    val validated: Boolean,
    val notRestricted: Boolean,
)

/**
 * What the phone knew of the network that is up (the line of its loss), and the channel of the connection that was lost,
 * to say on which channel the dash came back ([LinkLoss]). Pure, so the JVM tests cover it (NetNotesTest); the fields are read and
 * written by the system's callback threads and the coroutines, as they were in the manager.
 */
internal class NetNotes {
    @Volatile var facts: NetFacts? = null
        private set
    @Volatile private var lostMhz: Int? = null
    @Volatile private var awaitingChannel = false

    /**
     * Keeps what Android says of the network that is up ([NetFacts]); the first time the channel is known after a loss, hands back the
     * line that says whether the dash came back on it. [read] null (Android has no snapshot yet) changes nothing.
     */
    fun note(read: CapsRead?, nowMs: Long): String? {
        val before = facts
        val mhz = read?.mhz?.takeIf { it > 0 } ?: before?.mhz
        facts = NetFacts(
            upAtMs = before?.upAtMs ?: nowMs,
            mhz = mhz,
            rssiDbm = read?.rssiDbm?.takeIf { it > NO_RSSI } ?: before?.rssiDbm,
            specifier = read?.specifier ?: before?.specifier,
            validated = read?.validated ?: before?.validated,
            notRestricted = read?.notRestricted ?: before?.notRestricted,
        )
        if (awaitingChannel && mhz != null) {
            awaitingChannel = false
            return LinkLoss.backLine(lostMhz, mhz)
        }
        return null
    }

    /** The network went: the next connection of this projection is compared with the channel this one had (kept if it is not known now). */
    fun lost() {
        facts?.mhz?.let { lostMhz = it }
        awaitingChannel = lostMhz != null
        facts = null
    }

    /** A fresh connect of the rider's: nothing of the network before is kept. */
    fun forget() {
        facts = null
        lostMhz = null
        awaitingChannel = false
    }

    /** Whose the network in use is, for the ride log ([NetworkOwner]); a flag Android did not say now ([notRestricted], [validated] null) is the one kept. */
    fun owner(joinedByPhone: Boolean, request: Int, notRestricted: Boolean?, validated: Boolean?): String = NetworkOwner.words(
        joinedByPhone, request,
        notRestricted ?: facts?.notRestricted,
        validated ?: facts?.validated,
    )

    private companion object {
        /** WifiInfo's own "no signal reading" (INVALID_RSSI is hidden from the public SDK). */
        const val NO_RSSI = -127
    }
}

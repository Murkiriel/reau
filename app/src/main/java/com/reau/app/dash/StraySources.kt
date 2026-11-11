package com.reau.app.dash

/**
 * Packets that reach the dash socket from an address other than the dash's own (192.168.1.1), kept for the ride log and nothing else: the packet is
 * taken like any other (the owner decided in 2026 not to filter by address, because not every dash is known), only noted. A different
 * address says that something else on the dash's network is talking to REau's port, or that a dash of another firmware answers from another address.
 *
 * The first packet of a session from a stray address is told at once ([heard]), with the address; the others are only counted, and [summary] says
 * how many and from how many addresses when the socket closes (nothing when no more than that first one came). At most [MAX_ADDRESSES] addresses are
 * kept apart; the rest count as one more. No packet content is read or written.
 */
internal class StraySources(private val expected: String) {
    private var packets = 0
    private val addresses = LinkedHashSet<String>()
    private var others = 0

    /** A packet came from [from] (a host address). The line to write the first time a stray one does in this session, null otherwise (and for the dash). */
    @Synchronized
    fun heard(from: String): String? {
        if (from == expected) return null
        packets++
        if (addresses.size < MAX_ADDRESSES) addresses += from else if (from !in addresses) others++
        return if (packets == 1) {
            "rx from $from, not the dash's $expected: taken like any other packet (not filtered); told once per session"
        } else {
            null
        }
    }

    /** What the session saw, once, when the socket closes: null when no stray packet came, or only one (already told). */
    @Synchronized
    fun summary(): String? {
        if (packets <= 1) return null
        val extra = if (others > 0) " (and others past the first $MAX_ADDRESSES)" else ""
        return "rx from other addresses than the dash's $expected: $packets packets from ${addresses.size} address(es)$extra, " +
            "first ${addresses.first()}, not filtered"
    }

    companion object {
        const val MAX_ADDRESSES = 4
    }
}

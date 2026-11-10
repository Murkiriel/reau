package com.reau.app.dash

import java.util.Locale

/**
 * What REau was sending to the dash in the seconds before it went quiet, kept in a few arrays and written to the ride log in one line when the
 * Wi-Fi is lost or a watchdog fires. Ride log of 01/11/2026: the dash's Wi-Fi hung at 19:12:39 after 26 minutes of steady 72 Mbps, with no sign
 * in the log of what came just before; if a burst (a big keyframe) or a command sets it off, this line is where it shows.
 *
 * Per second, for the last [SLOTS] seconds: RTP bytes and packets sent, and the biggest burst (packets less than [BURST_GAP_NS] apart, one
 * keyframe being the usual one). Apart: the last keyframe (size, packets, when) and the last command to the dash that is not one of the periodic
 * heartbeats. The window of the line is the [WINDOW_S] seconds up to the last RTP packet sent, not up to the moment it is written (the Wi-Fi
 * lost line is written at once and the watchdog's 4 s later), and says how long the silence was.
 *
 * Cheap on the hot path, which is the RTP send: [rtp] adds to arrays that exist, without allocating; a lock that nobody else holds is the whole
 * cost. Times are `System.nanoTime()` readings, given by the caller in the tests. Thread-safe.
 */
class SendTail {
    private val second = LongArray(SLOTS) { NONE }
    private val bytes = LongArray(SLOTS)
    private val packets = IntArray(SLOTS)
    private val burst = IntArray(SLOTS)
    private var lastRtpNs = NONE
    private var burstNow = 0
    private var keyAtNs = NONE
    private var keyBytes = 0
    private var keyPackets = 0
    private var commandAtNs = NONE
    private var commandKey: String? = null
    private var reportedNs = NONE

    /** One RTP packet of [size] bytes went out at [nowNs]. */
    @Synchronized
    fun rtp(size: Int, nowNs: Long = System.nanoTime()) {
        val sec = Math.floorDiv(nowNs, NS_PER_S)
        val i = Math.floorMod(sec, SLOTS.toLong()).toInt()
        if (second[i] != sec) {
            second[i] = sec
            bytes[i] = 0L
            packets[i] = 0
            burst[i] = 0
        }
        bytes[i] += size
        packets[i]++
        burstNow = if (lastRtpNs != NONE && nowNs - lastRtpNs in 0..BURST_GAP_NS) burstNow + 1 else 1
        lastRtpNs = nowNs
        if (burstNow > burst[i]) burst[i] = burstNow
    }

    /** A keyframe of [size] bytes in [packetCount] RTP packets went out at [nowNs]. */
    @Synchronized
    fun keyframe(size: Int, packetCount: Int, nowNs: Long = System.nanoTime()) {
        keyAtNs = nowNs
        keyBytes = size
        keyPackets = packetCount
    }

    /** A command to the dash, [key] its first TLV (`0501`), which is not a periodic heartbeat, went out at [nowNs]. */
    @Synchronized
    fun command(key: String, nowNs: Long = System.nanoTime()) {
        commandKey = key
        commandAtNs = nowNs
    }

    @Synchronized
    fun clear() {
        second.fill(NONE)
        bytes.fill(0L)
        packets.fill(0)
        burst.fill(0)
        lastRtpNs = NONE
        burstNow = 0
        keyAtNs = NONE
        commandAtNs = NONE
        commandKey = null
        reportedNs = NONE
    }

    /**
     * The line for [why] (`the Wi-Fi was lost`, `a watchdog fired`) written at [nowNs]; [connected] is the words for how long the link had been up
     * (`on this network for 59 s`), or null when not known. Said once for a loss: the Wi-Fi's loss and the watchdog that follows it are one
     * event, so a second call within [ONCE_S] seconds returns null.
     */
    @Synchronized
    fun reportOnce(why: String, connected: String?, nowNs: Long = System.nanoTime()): String? {
        if (reportedNs != NONE && nowNs - reportedNs in 0 until ONCE_S * NS_PER_S) return null
        reportedNs = nowNs
        return line(why, connected, nowNs)
    }

    private fun line(why: String, connected: String?, nowNs: Long): String {
        val head = "send tail before $why" + (connected?.let { " ($it)" } ?: "")
        if (lastRtpNs == NONE) return "$head: no RTP sent; ${tail(nowNs)}"
        val lastSec = Math.floorDiv(lastRtpNs, NS_PER_S)
        val perSecond = (lastSec - WINDOW_S + 1..lastSec).map { s ->
            val i = Math.floorMod(s, SLOTS.toLong()).toInt()
            if (second[i] == s) Triple(packets[i], bytes[i], burst[i]) else Triple(0, 0L, 0)
        }
        val totalBytes = perSecond.sumOf { it.second }
        val totalPackets = perSecond.sumOf { it.first }
        val biggest = perSecond.maxOf { it.third }
        val silentFor = (nowNs - lastRtpNs).coerceAtLeast(0) / NS_PER_S
        return head + String.format(
            Locale.US, ": the last %d s of RTP (up to the last packet, %d s ago) %.1f pkt/s %.1f kB/s, per second oldest first %s, biggest burst %d pkts; %s",
            WINDOW_S, silentFor, totalPackets / WINDOW_S.toDouble(), totalBytes / WINDOW_S / BYTES_PER_KB,
            perSecond.joinToString(" ") { "${it.first}p/${String.format(Locale.US, "%.1f", it.second / BYTES_PER_KB)}k" }, biggest, tail(nowNs),
        )
    }

    private fun tail(nowNs: Long): String {
        val key = if (keyAtNs == NONE) "no keyframe" else "last keyframe $keyBytes B in $keyPackets pkts ${ago(nowNs, keyAtNs)}"
        val command = commandKey?.let { "last command $it ${ago(nowNs, commandAtNs)}" } ?: "no command"
        return "$key, $command"
    }

    private fun ago(nowNs: Long, atNs: Long) = String.format(Locale.US, "%.1f s before", (nowNs - atNs).coerceAtLeast(0) / NS_PER_S.toDouble())

    companion object {
        /** Seconds kept; more than the window so a line written a few seconds late still has all of it. */
        const val SLOTS = 32

        /** Seconds the line covers. */
        const val WINDOW_S = 10

        /** Packets this close are one burst. A keyframe is packetised in a loop with nothing between its packets. */
        const val BURST_GAP_NS = 2_000_000L

        const val ONCE_S = 30L
        private const val NS_PER_S = 1_000_000_000L
        private const val BYTES_PER_KB = 1_000.0
        private const val NONE = Long.MIN_VALUE
    }
}

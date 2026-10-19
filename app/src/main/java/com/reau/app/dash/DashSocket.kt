// Every control packet in and out, and every failed or held-back send, is counted in [stats]; with the detailed log
// on, each one goes to it ([DashDump], the route card's text and the key exchange left out). The control and video
// sockets carry a DSCP mark ([TOS_CONTROL], [TOS_VIDEO]).
package com.reau.app.dash

import android.util.Log
import com.reau.app.dash.protocol.K1GPacket
import com.reau.app.data.RideDiagnostics
import com.reau.app.util.Dbg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * UDP sockets for the dash, matching better-dash exactly:
 *   TX  – bound to :2000, SO_BROADCAST, sends to 192.168.1.255:2000.
 *         The bike IP is never used for the control plane.
 *   RX  – bound to :2002. Must be open BEFORE the first TX packet, both to
 *         catch the early pubkey reply and because unanswered dash→phone
 *         packets generate ICMP port-unreachable, which confuses the dash's
 *         protocol state machine.
 *   RTP – ephemeral, sends H.264 to 192.168.1.1:5000.
 *
 * Every control packet gets the rolling K1G seq byte patched on send.
 */
class DashSocket(
    private val network: android.net.Network? = null,
    // WHERE control packets/RTP are sent, injectable for a
    // test to point at a fake dash instead of the real K1G broadcast/unicast addresses — the bind
    // ports below (CTRL_PORT for TX, RX_PORT for RX) are NEVER injected, they stay the real,
    // fixed protocol ports the dash expects the phone to use, on loopback exactly like on the real
    // subnet. Defaults reproduce today's exact hardcoded destinations — every real caller (nobody
    // passes these two) is byte-for-byte unaffected; see DashSocketTest's golden checks.
    private val ctrlDest: InetSocketAddress = InetSocketAddress(InetAddress.getByName(BROADCAST), CTRL_PORT),
    private val rtpDest: InetSocketAddress = InetSocketAddress(InetAddress.getByName(DASH_IP), RTP_PORT),
) : AutoCloseable {
    companion object {
        const val DASH_IP    = "192.168.1.1"
        const val BROADCAST  = "192.168.1.255"
        const val CTRL_PORT  = 2000
        const val RX_PORT    = 2002
        const val RTP_PORT   = 5000
        private const val BUF             = 65535
        private const val RECV_TIMEOUT_MS = 500
        private const val TAG             = "DashSocket"
        // Another project's DashSocket logged "34k+ RTP + 5k+ TX 'ENETUNREACH'" in a
        // single ride during a WiFi outage. Once a channel has failed this many sends in a row,
        // stop actually hitting the socket (and stop hex-logging) for most calls — just probe
        // once every [PROBE_EVERY] to detect recovery on its own, self-contained (no external
        // wiring to DashWifiManager/DashSession needed).
        private const val SUPPRESS_AFTER_STREAK = 5
        private const val PROBE_EVERY = 20

        // The IP header's DSCP (DSCP/WMM), which the
        // Wi-Fi driver turns into a WMM queue. Control packets (button acks, heartbeats) as CS6 (48, the voice
        // queue), the video as AF41 (34, the video queue), so a button ack never waits behind a keyframe. Not EF
        // (46): Linux's older mapping takes the top three bits as the user priority, so EF lands on 5, the video
        // queue like AF41; CS6 lands on 6, the voice queue, under both that and RFC 8325. A radio that ignores the
        // mark sends everything as before.
        internal const val TOS_CONTROL = 48 shl 2
        internal const val TOS_VIDEO = 34 shl 2

        /** Marks [socket] with [tos]; false (and the socket as it was) where the system refuses. */
        internal fun mark(socket: DatagramSocket, tos: Int): Boolean =
            runCatching { socket.trafficClass = tos }.isSuccess
    }

    private val txSocket:  DatagramSocket
    private val rxSocket:  DatagramSocket
    private val rtpSocket: DatagramSocket

    private val seq = AtomicInteger(0)

    /** Where every control packet in and out is counted; null counts nothing. */
    @Volatile var stats: DashStats? = null
    // Send failures logged only at streak start/recovery (not per-packet -- these run at up to
    // 4 Hz, logging every one would spam a ride log into uselessness). A sustained streak here is
    // the direct explanation for "video froze/disappeared mid-ride" that the RX watchdog only
    // catches indirectly (once the dash stops acking, after its own timeout).
    private var txFailStreak = 0
    private var rtpFailStreak = 0
    private var txSuppressed = false
    private var txProbeCounter = 0
    private var txSuppressedTotal = 0
    private var rtpSuppressed = false
    private var rtpProbeCounter = 0
    private var rtpSuppressedTotal = 0

    init {
        var tx:  DatagramSocket? = null
        var rx:  DatagramSocket? = null
        var rtp: DatagramSocket? = null
        @Suppress("TooGenericExceptionCaught") // closes what was opened, then rethrows
        try {
            tx = DatagramSocket(null).also {
                it.reuseAddress = true
                it.broadcast = true
                it.bind(InetSocketAddress(CTRL_PORT))
                network?.bindSocket(it)
            }
            rx = DatagramSocket(null).also {
                it.reuseAddress = true
                it.soTimeout = RECV_TIMEOUT_MS
                it.bind(InetSocketAddress(RX_PORT))
                network?.bindSocket(it)
            }
            rtp = DatagramSocket().also { network?.bindSocket(it) }
            val marked = "control CS6=${mark(tx, TOS_CONTROL)} video AF41=${mark(rtp, TOS_VIDEO)}"
            Log.i(TAG, "Sockets open — TX :$CTRL_PORT→${ctrlDest.address.hostAddress}:${ctrlDest.port}, " +
                "RX :$RX_PORT, RTP→${rtpDest.address.hostAddress}:${rtpDest.port}, DSCP $marked")
            com.reau.app.data.RideDiagnostics.log("dash", "sockets marked for Wi-Fi priority: $marked")
            txSocket  = tx
            rxSocket  = rx
            rtpSocket = rtp
        } catch (e: Exception) {
            tx?.close(); rx?.close(); rtp?.close()
            throw e
        }
    }

    /** Send a K1G control packet (seq patched here, like K1GTx in the reference). */
    @Suppress("TooGenericExceptionCaught") // a failed send is counted and logged; the link may be down
    fun send(data: ByteArray) {
        if (txSuppressed) {
            txProbeCounter++
            if (txProbeCounter % PROBE_EVERY != 0) { txSuppressedTotal++; stats?.sendFailed(); return }
            // Every PROBE_EVERY-th call falls through and actually hits the socket, so a
            // recovered link is detected on its own instead of staying suppressed forever.
        }
        val pkt = K1GPacket.patchSeq(data, seq.getAndIncrement())
        // No packet bytes to logcat (the detailed log's DashDump shows them, behind its switch and without what is
        // private): in hex they would carry the song and the caller.
        // UDP fire-and-forget: a dropped/unreachable link (ENETUNREACH, EBADF) must never
        // crash the app — the session will fail and reconnect.
        try {
            txSocket.send(DatagramPacket(pkt, pkt.size, ctrlDest.address, ctrlDest.port))
            stats?.sent(pkt)
            com.reau.app.data.DetailedLog.offer("dash") { "tx " + DashDump.outgoing(pkt) }
            if (txFailStreak > 0) {
                Dbg.i(TAG) { "TX send recovered after $txFailStreak failures" }
                com.reau.app.data.RideDiagnostics.log("dash", "TX send recovered after $txFailStreak failures")
                txFailStreak = 0
            }
            if (txSuppressed) {
                Dbg.i(TAG) { "TX link recovered — resuming normal sends (suppressed $txSuppressedTotal in between)" }
                com.reau.app.data.RideDiagnostics.log("dash", "TX link recovered — resumed after suppressing $txSuppressedTotal sends")
                txSuppressed = false; txProbeCounter = 0; txSuppressedTotal = 0
            }
        } catch (e: Exception) {
            Log.w(TAG, "TX send failed (link down?): ${e.message}")
            stats?.sendFailed()
            if (txFailStreak == 0) {
                // Class name, not just message: some socket exceptions (seen in use) carry a null message, logging an unhelpful bare
                // "null" with no clue which OS error actually fired.
                com.reau.app.data.RideDiagnostics.warn("dash", "TX send failing (link down?): ${NetworkBlock.describe(e)}")
            }
            txFailStreak++
            if (!txSuppressed && txFailStreak >= SUPPRESS_AFTER_STREAK) {
                txSuppressed = true
                RideDiagnostics.warn("dash", "TX link appears down after $txFailStreak failures — suppressing sends, probing every $PROBE_EVERY")
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // a failed send is counted and logged; the link may be down
    fun sendRtp(data: ByteArray) {
        if (rtpSuppressed) {
            rtpProbeCounter++
            if (rtpProbeCounter % PROBE_EVERY != 0) { rtpSuppressedTotal++; return }
        }
        try {
            rtpSocket.send(DatagramPacket(data, data.size, rtpDest.address, rtpDest.port))
            if (rtpFailStreak > 0) {
                com.reau.app.data.RideDiagnostics.log("dash", "RTP send recovered after $rtpFailStreak failures")
                rtpFailStreak = 0
            }
            if (rtpSuppressed) {
                com.reau.app.data.RideDiagnostics.log("dash", "RTP link recovered — resumed after suppressing $rtpSuppressedTotal sends")
                rtpSuppressed = false; rtpProbeCounter = 0; rtpSuppressedTotal = 0
            }
        } catch (e: Exception) {
            Log.d(TAG, "RTP send failed (link down?): ${e.message}")
            if (rtpFailStreak == 0) {
                com.reau.app.data.RideDiagnostics.warn("dash", "RTP send failing (link down?): ${NetworkBlock.describe(e)}")
            }
            rtpFailStreak++
            if (!rtpSuppressed && rtpFailStreak >= SUPPRESS_AFTER_STREAK) {
                rtpSuppressed = true
                RideDiagnostics.warn("dash", "RTP link appears down after $rtpFailStreak failures — suppressing sends, probing every $PROBE_EVERY")
            }
        }
    }

    // One 64 KB buffer for every receive (it was one per call, and the loop calls at every packet or every
    // RECV_TIMEOUT_MS of silence: 130 to 400 KB/s of garbage). Only the receive loop may call [receiveBlocking].
    private val rxBuffer = ByteArray(BUF)
    private val rxPacket = DatagramPacket(rxBuffer, BUF)

    /**
     * Blocks up to RECV_TIMEOUT_MS; returns null on timeout. Throws on a genuine socket
     * error (closed/unreachable) — the caller's receive loop catches it and ends the
     * session instead of letting the exception crash the whole app. One caller at a time: the buffer is shared; what
     * comes back is the packet's own copy.
     */
    fun receiveBlocking(): ByteArray? {
        // A receive sets the packet's length to what arrived, and the DatagramSocket contract lets the next one cut a
        // message to that length: given back whole. (JDK 21 and Android's sockets keep the buffer's own length apart and
        // do not cut — DashSocketReceiveDeviceTest — but the contract does not promise it.)
        rxPacket.setData(rxBuffer, 0, BUF)
        return try {
            rxSocket.receive(rxPacket)
            val bytes = rxBuffer.copyOf(rxPacket.length)
            com.reau.app.data.DetailedLog.offer("dash") { "rx " + DashDump.incoming(bytes) }
            bytes
        } catch (_: java.net.SocketTimeoutException) {
            null
        }
    }

    /** [receiveBlocking] off the caller's thread, for tests; the session's loop, already on IO, calls it directly. */
    suspend fun receive(): ByteArray? = withContext(Dispatchers.IO) { receiveBlocking() }

    /** Test-only: the buffer receives go into. */
    internal fun testOnlyRxBuffer(): ByteArray = rxPacket.data

    /** Test-only visibility into the link-suppression state — see [SUPPRESS_AFTER_STREAK]/[PROBE_EVERY]. */
    internal fun testOnlyTxSuppressed(): Boolean = txSuppressed
    internal fun testOnlyRtpSuppressed(): Boolean = rtpSuppressed
    internal fun testOnlyCtrlDest(): InetSocketAddress = ctrlDest
    internal fun testOnlyRtpDest(): InetSocketAddress = rtpDest

    override fun close() {
        Log.d(TAG, "Sockets closed")
        runCatching { txSocket.close() }
        runCatching { rxSocket.close() }
        runCatching { rtpSocket.close() }
    }
}


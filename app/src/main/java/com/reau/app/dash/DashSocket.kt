// Every control packet in and out, and every failed or held-back send, is counted in [stats]; with the detailed log
// on, each one goes to it ([DashDump], the route card's text and the key exchange left out). The control and video
// sockets carry a DSCP mark ([TOS_CONTROL], [TOS_VIDEO]) in a release build; a debug build leaves them unmarked unless the experimental
// switch "Marcar a prioridade na debug" is on ([priorityMarks]).
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
@Suppress("TooManyFunctions") // the three sockets of the dash: sends, receive, close and the tests' views
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
    // The experimental switch (REauSettings.experimentDebugDscp): a debug build marks the sockets too. Off, the marking is the build's own, as always.
    private val markInDebug: Boolean = false,
    // The experimental switch (REauSettings.experimentTolerantRx): the control packets leave this many milliseconds apart ([ControlGap]). 0: they leave as they come, as always.
    controlGapMs: Long = 0L,
) : AutoCloseable {
    companion object {
        const val DASH_IP    = "192.168.1.1"
        const val BROADCAST  = "192.168.1.255"
        const val CTRL_PORT  = 2000
        const val RX_PORT    = 2002
        const val RTP_PORT   = 5000
        private const val NS_PER_MS = 1_000_000L
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

        /**
         * The marks of the control and video sockets, or null for none: a debug build sends the dash's sockets unmarked (the owner's phone,
         * 28/10/2026, to tell whether the dash's Wi-Fi restarts with them) unless [markInDebug], the experimental switch, is on, and a release
         * always marks them.
         */
        internal fun priorityMarks(
            debugBuild: Boolean = com.reau.app.BuildConfig.DEBUG,
            markInDebug: Boolean = false,
        ): Pair<Int, Int>? = if (debugBuild && !markInDebug) null else TOS_CONTROL to TOS_VIDEO

        /** Marks [socket] with [tos]; false (and the socket as it was) where the system refuses. */
        internal fun mark(socket: DatagramSocket, tos: Int): Boolean =
            runCatching { socket.trafficClass = tos }.isSuccess

        /** Lines about a send made on the main thread, per socket: a caller's bug repeats at every send, and one warning says it. */
        private const val MAIN_THREAD_WARNINGS = 3

        /**
         * True for the exception Android throws when a socket is used on the main thread. By class name, as the log writes it
         * ([NetworkBlock.describe]); it says nothing about the link, so [sendFailed] keeps it out of the streak that suppresses sends.
         */
        internal fun isMainThreadViolation(e: Throwable): Boolean = e.javaClass.simpleName == "NetworkOnMainThreadException"
    }

    private val txSocket:  DatagramSocket
    private val rxSocket:  DatagramSocket
    private val rtpSocket: DatagramSocket

    private val seq = AtomicInteger(0)
    private val controlGap = ControlGap(controlGapMs)

    /** Where every control packet in and out is counted; null counts nothing. */
    @Volatile var stats: DashStats? = null
    // Send failures logged only at streak start/recovery (not per-packet -- these run at up to
    // 4 Hz, logging every one would spam a ride log into uselessness). A sustained streak here is
    // the direct explanation for "video froze/disappeared mid-ride" that the RX watchdog only
    // catches indirectly (once the dash stops acking, after its own timeout).
    // The control channel is sent on from several threads (the session, the heartbeat, the joystick's answers): its bookkeeping is
    // changed under [txLock] (short sections, no I/O and no log inside), and its flag is volatile for the cheap read before it.
    private val txLock = Any()
    private var txFailStreak = 0
    private var rtpFailStreak = 0
    @Volatile private var txSuppressed = false
    private var txProbeCounter = 0
    private val mainThreadWarnings = AtomicInteger()
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
            val marks = priorityMarks(markInDebug = markInDebug)
            val marked = marks?.let { (control, video) ->
                "control CS6=${mark(tx, control)} video AF41=${mark(rtp, video)}" +
                    if (com.reau.app.BuildConfig.DEBUG) " (debug build, by the experimental switch)" else ""
            } ?: "none (debug build: no priority marking)"
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

    /** Send a K1G control packet (seq patched here, like K1GTx in the reference); true when it reached the wire (false: suppressed, or the send failed and was counted and logged). */
    fun send(data: ByteArray): Boolean {
        val pkt = wire(data) ?: return false
        afterWire(pkt)
        return true
    }

    /**
     * Sends [packets] one after another with nothing between them but the wire: the statistics and the logs of all of them come after the
     * last. For the answer to a button, where every millisecond before the ring changes is seen by the rider (the log's lock is shared with
     * the video and Android Auto threads, which write all the time).
     */
    fun sendBatch(packets: List<ByteArray>) {
        packets.mapNotNull(::wire).forEach(::afterWire)
    }

    /** The packet on the wire, or null when it was not sent (the link is suppressed, or the send failed: counted and logged here). */
    @Suppress("TooGenericExceptionCaught") // a failed send is counted and logged; the link may be down
    private fun wire(data: ByteArray): ByteArray? {
        if (txSuppressed && heldBack()) {
            stats?.sendFailed()
            return null
        }
        // Every PROBE_EVERY-th call of a suppressed link falls through and actually hits the socket, so a
        // recovered link is detected on its own instead of staying suppressed forever.
        waitForTheGap()
        val pkt = K1GPacket.patchSeq(data, seq.getAndIncrement())
        // UDP fire-and-forget: a dropped/unreachable link (ENETUNREACH, EBADF) must never
        // crash the app — the session will fail and reconnect.
        return try {
            txSocket.send(DatagramPacket(pkt, pkt.size, ctrlDest.address, ctrlDest.port))
            pkt
        } catch (e: Exception) {
            sendFailed(e)
            null
        }
    }

    /** With the experimental gap ([ControlGap]) set, holds the packet until the one before it has had its time on the wire; with none set it returns at once. */
    private fun waitForTheGap() {
        val ns = controlGap.waitNs(System.nanoTime())
        if (ns <= 0L) return
        try {
            Thread.sleep(ns / NS_PER_MS, (ns % NS_PER_MS).toInt())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** True when this call of a suppressed link is not the probe and is not sent; the counters move together, under the lock. */
    private fun heldBack(): Boolean = synchronized(txLock) {
        if (!txSuppressed) return@synchronized false
        txProbeCounter++
        val held = txProbeCounter % PROBE_EVERY != 0
        if (held) txSuppressedTotal++
        held
    }

    private fun sendFailed(e: Exception) {
        if (isMainThreadViolation(e)) {
            mainThreadViolation(e)
            return
        }
        Log.w(TAG, "TX send failed (link down?): ${e.message}")
        stats?.sendFailed()
        val first: Boolean
        val crossed: Int?
        synchronized(txLock) {
            first = txFailStreak == 0
            txFailStreak++
            crossed = txFailStreak.takeIf { !txSuppressed && it >= SUPPRESS_AFTER_STREAK }?.also { txSuppressed = true }
        }
        if (first) {
            // Class name, not just message: some socket exceptions (seen in use) carry a null message, logging an unhelpful bare
            // "null" with no clue which OS error actually fired.
            com.reau.app.data.RideDiagnostics.warn("dash", "TX send failing (link down?): ${NetworkBlock.describe(e)}")
        }
        if (crossed != null) {
            RideDiagnostics.warn("dash", "TX link appears down after $crossed failures — suppressing sends, probing every $PROBE_EVERY")
        }
    }

    /**
     * A send made on the main thread is the caller's bug, not a dead link: counted as a failed send, but left out of [txFailStreak] (five
     * of them would silence the control channel with the Wi-Fi fine) and accused in the log, a few times only.
     */
    private fun mainThreadViolation(e: Exception) {
        Log.e(TAG, "TX send on the main thread (programming bug, not the link): ${e.message}")
        stats?.sendFailed()
        if (mainThreadWarnings.incrementAndGet() <= MAIN_THREAD_WARNINGS) {
            RideDiagnostics.warn(
                "dash",
                "TX send refused: ${NetworkBlock.describe(e)} (the caller was the main thread, a programming bug, not the link); " +
                    "nothing was sent, not counted toward suppression",
            )
        }
    }

    /** What follows a packet that went out: the statistics, the detailed log (no packet bytes to logcat: in hex they would carry the song and the caller) and the recovery lines. */
    private fun afterWire(pkt: ByteArray) {
        stats?.sent(pkt)
        com.reau.app.data.DetailedLog.offer("dash") { "tx " + DashDump.outgoing(pkt) }
        val failures: Int
        val resumedAfter: Int?
        synchronized(txLock) {
            failures = txFailStreak
            txFailStreak = 0
            resumedAfter = txSuppressedTotal.takeIf { txSuppressed }
            if (txSuppressed) {
                txSuppressed = false
                txProbeCounter = 0
                txSuppressedTotal = 0
            }
        }
        if (failures > 0) {
            Dbg.i(TAG) { "TX send recovered after $failures failures" }
            com.reau.app.data.RideDiagnostics.log("dash", "TX send recovered after $failures failures")
        }
        if (resumedAfter != null) {
            Dbg.i(TAG) { "TX link recovered — resuming normal sends (suppressed $resumedAfter in between)" }
            com.reau.app.data.RideDiagnostics.log("dash", "TX link recovered — resumed after suppressing $resumedAfter sends")
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
            stats?.sendTail?.rtp(data.size)
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

    /** When the last packet came off the socket ([System.nanoTime]), before anything is logged: what the answer to a button is timed from. */
    @Volatile var arrivedNs: Long = 0L
        private set

    // One 64 KB buffer for every receive (it was one per call, and the loop calls at every packet or every
    // RECV_TIMEOUT_MS of silence: 130 to 400 KB/s of garbage). Only the receive loop may call [receiveBlocking].
    private val rxBuffer = ByteArray(BUF)
    private val rxPacket = DatagramPacket(rxBuffer, BUF)

    // Packets from an address other than the dash's (where RTP goes): taken as ever, only noted in the ride log, once, and counted at the close.
    private val dashAddress: InetAddress = rtpDest.address
    private val strays = StraySources(dashAddress.hostAddress.orEmpty())

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
            arrivedNs = System.nanoTime()
            val from = rxPacket.address
            if (from != null && from != dashAddress) {
                strays.heard(from.hostAddress.orEmpty())?.let { com.reau.app.data.RideDiagnostics.log("dash", it) }
            }
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
    /** Test-only: a send that failed with [e], as the socket's own failure would be handled by [wire]. */
    internal fun testOnlySendFailed(e: Exception) = sendFailed(e)
    internal fun testOnlyMainThreadWarnings(): Int = mainThreadWarnings.get()
    internal fun testOnlyTxFailStreak(): Int = synchronized(txLock) { txFailStreak }
    internal fun testOnlyTxSuppressedTotal(): Int = synchronized(txLock) { txSuppressedTotal }
    internal fun testOnlyTxProbeCounter(): Int = synchronized(txLock) { txProbeCounter }
    internal fun testOnlyRtpSuppressedTotal(): Int = rtpSuppressedTotal

    /** Test-only: the RTP channel as after [SUPPRESS_AFTER_STREAK] failures, on a socket that can send (to see it come back). */
    internal fun testOnlySuppressRtp() {
        rtpFailStreak = SUPPRESS_AFTER_STREAK
        rtpSuppressed = true
    }
    internal fun testOnlyCtrlDest(): InetSocketAddress = ctrlDest
    internal fun testOnlyRtpDest(): InetSocketAddress = rtpDest

    override fun close() {
        Log.d(TAG, "Sockets closed")
        strays.summary()?.let { com.reau.app.data.RideDiagnostics.log("dash", it) }
        runCatching { txSocket.close() }
        runCatching { rxSocket.close() }
        runCatching { rtpSocket.close() }
    }
}


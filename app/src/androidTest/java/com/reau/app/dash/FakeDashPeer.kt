package com.reau.app.dash

import com.reau.app.dash.protocol.K1GPacket
import com.reau.app.dash.protocol.Tlv
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import kotlin.concurrent.thread

/**
 * The DASH's own side of the K1G control protocol, in loopback — proves
 * SEQUENCE and WIRING against a real [DashSession] talking through a real [DashSocket] with its
 * destination injected here, not that a real physical dash would accept any of this.
 *
 * **Permissive by design, not a protocol authority**: it answers exactly the bytes the real dash's
 * firmware is documented to send (see `K1GPacket`'s own kdoc), never
 * validates that what the phone sends would satisfy real hardware beyond what's checked here.
 *
 * The streaming extras ([autoAck], [frameArrivals], [firstUnits]) are off by default, so the plain session tests
 * see the plain peer; [packetsReceived] and [tlvsSent] check the session's dash-stats against what was exchanged.
 *
 * Binds two loopback sockets on OS-chosen ephemeral ports (`ctrlDest`/`rtpDest` — feed these into
 * `DashSocket`'s injected destination) and always replies to `127.0.0.1:phoneRxPort`, the phone's
 * own fixed, real RX port — never the source port of whatever it just received, matching how the
 * real dash and the real [DashSocket] are both wired to a fixed pair of ports, not a reflected one.
 */
class FakeDashPeer(phoneRxPort: Int = DashSocket.RX_PORT) : AutoCloseable {
    private val loopback = InetAddress.getLoopbackAddress()
    private val phoneAddr = InetSocketAddress(loopback, phoneRxPort)
    private val ctrlSocket = DatagramSocket(0, loopback)
    private val rtpSocket = DatagramSocket(0, loopback)

    val ctrlDest: InetSocketAddress get() = InetSocketAddress(loopback, ctrlSocket.localPort)
    val rtpDest: InetSocketAddress get() = InetSocketAddress(loopback, rtpSocket.localPort)

    private val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.genKeyPair()
    private val pub = rsa.public as RSAPublicKey
    private val priv = rsa.private as RSAPrivateKey

    /** Every TLV received, in arrival order — assertions read this instead of re-parsing raw bytes. */
    val received = CopyOnWriteArrayList<Tlv>()

    /** Control packets received from the phone, and TLVs sent to it. */
    val packetsReceived = AtomicInteger(0)
    val tlvsSent = AtomicInteger(0)
    val rtpBytesReceived = AtomicInteger(0)

    /** Set once the phone's session key (RSA-encrypted SSID‖AES-256 key) is decrypted — proves the whole
     *  handshake actually carried a real, usable session key, not just that some bytes moved. */
    @Volatile var sessionKey: ByteArray? = null
        private set
    @Volatile var lastSsid: String? = null
        private set

    /** When true, received packets are recorded but never answered — simulates the dash going
     *  silent (out of range / powered off) for the RX watchdog. */
    @Volatile var silent = false

    /** Reject the FIRST session-key attempt (07 01 00), accept every attempt after — for the
     *  auth-retry path (`DashAuth.reset` + a fresh `authRequest`), the reconnection
     *  at the DashSession layer (WiFi-level reconnection lives in the caller, out of scope here). */
    @Volatile var rejectFirstKey = false
    private var keyAttempts = 0

    @Volatile private var running = true
    private var seq = 0

    private fun send(vararg tlvs: Tlv) {
        val pkt = K1GPacket.patchSeq(K1GPacket.build(*tlvs), seq++)
        tlvsSent.addAndGet(tlvs.size)
        runCatching { ctrlSocket.send(DatagramPacket(pkt, pkt.size, phoneAddr.address, phoneAddr.port)) }
    }

    private val ctrlThread = thread(name = "FakeDashPeer-ctrl", isDaemon = true) {
        val buf = ByteArray(65535)
        while (running) {
            val p = DatagramPacket(buf, buf.size)
            try { ctrlSocket.receive(p) } catch (e: Exception) { break }
            val tlvs = parseFromApp(p.data.copyOf(p.length))
            packetsReceived.incrementAndGet()
            received.addAll(tlvs)
            if (!silent) for (tlv in tlvs) handle(tlv)
        }
    }
    private val rtpThread = thread(name = "FakeDashPeer-rtp", isDaemon = true) {
        val buf = ByteArray(65535)
        while (running) {
            val p = DatagramPacket(buf, buf.size)
            try { rtpSocket.receive(p) } catch (e: Exception) { break }
            if (!silent) {
                rtpBytesReceived.addAndGet(p.length)
                onRtp(p.data.copyOf(p.length))
            }
        }
    }

    // ── streaming extras, off by default ─────────────────────────────────────────────────────────────

    /** Answer every access unit with a decode ack (09 06 55 for a keyframe, 09 04 55 otherwise), as the real dash
     *  does while streaming; without them the session's watchdogs end the stream. */
    @Volatile var autoAck = false

    /** SystemClock.elapsedRealtime() of each access unit's last packet (RTP marker bit). */
    val frameArrivals = CopyOnWriteArrayList<Long>()

    /** SystemClock.elapsedRealtime() of each keyframe (a unit with SPS or IDR): a new stream starts with one. */
    val keyframeArrivals = CopyOnWriteArrayList<Long>()

    /** The first [FIRST_UNITS_KEPT] access units, put back together (Annex-B, start codes between NAL units). */
    val firstUnits = CopyOnWriteArrayList<ByteArray>()

    private var unit = ByteArray(0)
    private var unitIsKey = false

    private fun onRtp(packet: ByteArray) {
        if (packet.size < 14) return
        val payloadType = packet[12].toInt() and 0x1F
        if (payloadType == FU_A) {
            val start = packet[13].toInt() and 0x80 != 0
            if (start) {
                val nalType = packet[13].toInt() and 0x1F
                unitIsKey = unitIsKey || nalType == NAL_SPS || nalType == NAL_IDR
                val header = ((packet[12].toInt() and 0xE0) or nalType).toByte()
                unit += START_CODE + header
            }
            unit += packet.copyOfRange(14, packet.size)
        } else {
            unitIsKey = unitIsKey || payloadType == NAL_SPS || payloadType == NAL_IDR
            unit += START_CODE + packet.copyOfRange(12, packet.size)
        }
        if (packet[1].toInt() and 0x80 == 0) return // not the last packet of the unit
        frameArrivals += android.os.SystemClock.elapsedRealtime()
        if (unitIsKey) keyframeArrivals += android.os.SystemClock.elapsedRealtime()
        if (firstUnits.size < FIRST_UNITS_KEPT) firstUnits += unit
        if (autoAck) if (unitIsKey) ackIdrFrame() else ackPFrame()
        unit = ByteArray(0)
        unitIsKey = false
    }

    private companion object {
        const val FU_A = 28
        const val NAL_IDR = 5
        const val NAL_SPS = 7
        const val FIRST_UNITS_KEPT = 30
        val START_CODE = byteArrayOf(0, 0, 0, 1)
    }

    private fun handle(tlv: Tlv) {
        if (tlv.type != 0x08) return
        when (tlv.sub) {
            0x04 -> sendPubkey()                 // "send me your RSA public key"
            0x00 -> onSessionKey(tlv.value)       // the session key: RSA(ssid ‖ AES-256 key), always 128 B
        }
    }

    private fun sendPubkey() {
        send(K1GPacket.tlv(0x07, 0x00, pub.modulus.toByteArray().stripLeadingZero()))
        send(K1GPacket.tlv(0x07, 0x03, pub.publicExponent.toByteArray().stripLeadingZero()))
    }

    private fun onSessionKey(ciphertext: ByteArray) {
        keyAttempts++
        if (rejectFirstKey && keyAttempts == 1) {
            send(K1GPacket.tlv(0x07, 0x01, 0x00))   // rejected — DashAuth.reset() + resend authRequest
            return
        }
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, priv)
        val payload = cipher.doFinal(ciphertext)
        sessionKey = payload.copyOfRange(payload.size - 32, payload.size)
        lastSsid = String(payload.copyOfRange(0, payload.size - 32), Charsets.UTF_8)
        send(K1GPacket.tlv(0x07, 0x01, 0x01))       // confirmed
    }

    /** A joystick/button event, as the dash sends it (09 00 .. code) — see DashButtonEvent for the code table. */
    fun pressButton(code: Int) = send(K1GPacket.tlv(0x09, 0x00, 0x00, code))

    /** Frame-decoded acks, as the real dash sends continuously while streaming (mandatory: the
     *  phone's RX watchdog treats their absence as a dead link). */
    fun ackIdrFrame() = send(K1GPacket.tlv(0x09, 0x06, 0x55))
    fun ackPFrame() = send(K1GPacket.tlv(0x09, 0x04, 0x55))

    fun countOf(type: Int, sub: Int? = null): Int = received.count { it.type == type && (sub == null || it.sub == sub) }

    /** Everything received AFTER the last real session-key exchange (08 00): on a shared test
     *  machine, a PRIOR test's phone-side coroutines can still have an in-flight send queued when
     *  its scope is cancelled, and the OS can hand THIS test's fresh ephemeral port straight back
     *  out — a late, stale packet (sometimes a whole stale handshake) then lands here before the
     *  real one. The real handshake for THIS session is always the LAST 08/00 (a stale one, if
     *  any, is always earlier), so anchoring there discards stale traffic without guessing a count. */
    fun receivedSinceHandshake(): List<Tlv> {
        val anchor = received.indexOfLast { it.type == 0x08 && it.sub == 0x00 }
        return if (anchor >= 0) received.drop(anchor + 1) else received
    }

    /** The subsequence of TLVs matching (type, sub), in the order they arrived, since the real
     *  handshake — for order-sensitive assertions without caring about interleaved heartbeats.
     *  **Matches on type+sub only, NOT the value** — most TLVs are unambiguous by (type, sub)
     *  alone, but a caller distinguishing two commands that reuse the same slot with different
     *  values (05,56: 0x55 = projectionFrame "on" vs 0xAA = projectionStop/the initial burst's
     *  own "off" announcement) needs [receivedSinceHandshake] directly instead. */
    fun sequenceOf(vararg pairs: Pair<Int, Int>): List<Pair<Int, Int>> =
        receivedSinceHandshake().map { it.type to it.sub }.filter { it in pairs }

    override fun close() {
        running = false
        ctrlSocket.close(); rtpSocket.close()
    }
}

private fun ByteArray.stripLeadingZero(): ByteArray =
    if (size > 1 && this[0] == 0.toByte()) copyOfRange(1, size) else this

/**
 * Parses a packet in the APP→DASH (outgoing) layout — [K1GPacket.parseIncoming] is for the OTHER
 * direction (DASH→APP, TLVs starting at byte 8, per that function's own kdoc) and would silently
 * misparse anything [K1GPacket.build] itself produced (TLVs start at byte 17: outer_len(2) +
 * seg_count(2) + the 12-byte FIXED header + seq(1)). `segCount` includes that fixed header as its
 * own segment (`K1GPacket.build`'s `segCount = 1 + tlvs.size`), so the loop starts counting at 1.
 */
private fun parseFromApp(data: ByteArray): List<Tlv> {
    val tlvs = mutableListOf<Tlv>()
    if (data.size < 17) return tlvs
    val segCount = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
    var i = 17
    var n = 1
    while (n < segCount && i + 4 <= data.size) {
        val type = data[i].toInt() and 0xFF
        val sub = data[i + 1].toInt() and 0xFF
        val len = ((data[i + 2].toInt() and 0xFF) shl 8) or (data[i + 3].toInt() and 0xFF)
        i += 4
        val end = (i + len).coerceAtMost(data.size)
        tlvs += Tlv(type, sub, data.copyOfRange(i, end))
        i = end
        n++
    }
    return tlvs
}

package com.reau.app.aa

import java.io.Closeable
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/**
 * Test double for the phone's Android Auto: the other end of the head-unit protocol, on a loopback socket.
 *
 * It listens on a local port, answers the version request, runs the SERVER side of the TLS handshake (TLS records
 * travel inside protocol messages on channel 0, type 3) with a certificate made on the spot, takes the "status OK",
 * and from there on exchanges encrypted messages: [send] and [receive]. A [Script] makes it misbehave on purpose.
 *
 * What it assumes about the real Android Auto, none of it verified here against the real thing: TLS 1.2, a client
 * certificate asked for but any accepted, version response "1.x, status 0".
 *
 * Plain JVM code (java.net and javax.net.ssl), so it serves both the JVM tests and the instrumented ones.
 */
class FakeAndroidAutoPeer(
    private val script: Script = Script(),
    listenOn: Int = 0,
    /** True: this Android Auto does not wait for the head unit; [dial] makes it call the head unit, as Android Auto 17.3
     *  and older does when asked. The conversation after the connection is the same. */
    private val dials: Boolean = false,
) : Closeable {

    /** How this peer behaves. The defaults are a well-behaved Android Auto. */
    data class Script(
        /** Never answer the version request. */
        val silent: Boolean = false,
        /** Extra version responses, sent once TLS has begun (after the head unit's first TLS message): what a slow
         *  peer does when the version request was retried. */
        val lateVersionResponses: Int = 0,
        /** Close the socket at this point of the conversation. */
        val hangUpAt: Stage? = null,
        /** Minor version announced in the version response. */
        val minorVersion: Int = 7,
        /** Above zero: every TLS handshake flight is cut into messages of at most this many bytes. */
        val tlsChunkBytes: Int = 0,
        /** Answer every ping request (control channel) with a ping response, and say nothing else: Android Auto with
         *  its screen still. */
        val answerPings: Boolean = false,
        /** Not null: answer the head unit's bye with a bye response after this many ms, as Android Auto does. */
        val answerByeAfterMs: Long? = null,
    )

    enum class Stage { ACCEPTED, VERSION_ANSWERED, TLS_HALFWAY, HANDSHAKE_DONE }

    /** One message after decryption. */
    class Received(val channel: Int, val flags: Int, val type: Int, val body: ByteArray)

    /** 127.0.0.1, as a number: where Android Auto's head unit server listens. Not "the loopback address", which on
     *  Android is ::1, where a peer bound to 127.0.0.1 is not. */
    val address: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    // [listenOn] 0 takes any free port. A fixed one (Android Auto's own is 5277) can be taken again right after
    // another peer let go of it.
    private val server = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(address, listenOn), 1)
    }
    val port: Int get() = server.localPort

    /** What happened, in order, for a test to read when something goes wrong. */
    val events = CopyOnWriteArrayList<String>()

    /** Ping requests the head unit sent; kept out of [receive]. */
    val pingsReceived = java.util.concurrent.atomic.AtomicInteger()

    @Volatile var versionRequest: ByteArray? = null
        private set
    @Volatile var statusOk: ByteArray? = null
        private set
    @Volatile var clientCertificate: X509Certificate? = null
        private set
    @Volatile var protocol: String? = null
        private set
    @Volatile var failure: Throwable? = null
        private set

    private val identity = TestCertificate("Fake Android Auto")
    private val handshakeDone = CountDownLatch(1)
    private val inbox = LinkedBlockingQueue<Received>()
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: OutputStream? = null
    private var engine: SSLEngine? = null
    private val worker: Thread? = if (dials) null else thread(name = "fake-android-auto") { runConversation { serve() } }
    @Volatile private var dialWorker: Thread? = null

    private fun runConversation(body: () -> Unit) {
        runCatching(body).onFailure {
            if (!server.isClosed) {
                failure = it
                events += "failed: $it"
            }
        }
    }

    /**
     * Calls the head unit listening on 127.0.0.1:[headUnitPort], then converses as usual. Only with `dials = true`; a second
     * call while a conversation is going on does nothing.
     */
    fun dial(headUnitPort: Int) {
        check(dials) { "this peer waits to be called: build it with dials = true" }
        synchronized(this) {
            if (dialWorker?.isAlive == true) return
            events += "dialling $headUnitPort"
            dialWorker = thread(name = "fake-android-auto-dial") {
                runConversation { converse(Socket(address, headUnitPort)) }
            }
        }
    }

    /** Waits for the handshake (version, TLS, status OK) to finish. False if it did not within [timeoutMs]. */
    fun awaitHandshake(timeoutMs: Long = 10_000): Boolean = handshakeDone.await(timeoutMs, TimeUnit.MILLISECONDS)

    /** The next decrypted message the head unit sent, or null if none came within [timeoutMs]. */
    fun receive(timeoutMs: Long = 3_000): Received? = inbox.poll(timeoutMs, TimeUnit.MILLISECONDS)

    /** Sends one encrypted message to the head unit. [body] must fit one TLS record (under 16 KB). */
    fun send(channel: Int, type: Int, body: ByteArray, flags: Int = 0x0B) {
        val plain = byteArrayOf((type shr 8).toByte(), type.toByte()) + body
        val encrypted = synchronized(this) { wrap(plain) }
        writeFrame(channel, flags, encrypted)
    }

    /**
     * Sends one message cut in [pieces] (at least 2): the first flagged 9 and carrying the total size, the middle
     * ones 8, the last 10, each encrypted on its own. Pieces whose index is in [drop] are left out, as if lost.
     *
     * A piece after the first never begins with a zero byte here: the receiver's dispatcher reads the first two
     * bytes of every piece as a message type, and 00 00 or 00 01 would earn a continuation piece an
     * acknowledgement of its own. Cuts are moved forward to avoid that, so the count of acknowledgements in a
     * test is exact.
     */
    fun sendInPieces(channel: Int, type: Int, body: ByteArray, pieces: Int, drop: Set<Int> = emptySet()) {
        require(pieces >= 2) { "one piece is a whole message: use send()" }
        val plain = byteArrayOf((type shr 8).toByte(), type.toByte()) + body
        val cuts = (1 until pieces).map { index ->
            var at = plain.size * index / pieces
            while (at < plain.size - 1 && plain[at].toInt() == 0) at++
            at
        }
        val bounds = listOf(0) + cuts + plain.size
        check(bounds.zipWithNext().all { (a, b) -> b - a >= 2 }) { "the message is too short for $pieces pieces" }
        for (index in 0 until pieces) {
            if (index in drop) continue
            val encrypted = synchronized(this) { wrap(plain.copyOfRange(bounds[index], bounds[index + 1])) }
            val flags = when (index) {
                0 -> FLAGS_FIRST_PIECE
                pieces - 1 -> FLAGS_LAST_PIECE
                else -> FLAGS_MIDDLE_PIECE
            }
            val header = byteArrayOf(channel.toByte(), flags.toByte(), (encrypted.size shr 8).toByte(), encrypted.size.toByte())
            val total = plain.size
            val totalSize = byteArrayOf((total shr 24).toByte(), (total shr 16).toByte(), (total shr 8).toByte(), total.toByte())
            sendRaw(if (index == 0) header + totalSize + encrypted else header + encrypted)
        }
    }

    /** Sends raw bytes as they are: for garbage, half messages and the like. */
    fun sendRaw(bytes: ByteArray) {
        val out = checkNotNull(output) { "no connection yet" }
        synchronized(out) {
            out.write(bytes)
            out.flush()
        }
    }

    /** Hangs up on the head unit, as a phone leaving does. */
    fun hangUp() {
        events += "hung up"
        runCatching { socket?.close() }
    }

    override fun close() {
        runCatching { server.close() }
        runCatching { socket?.close() }
        worker?.join(2_000)
        dialWorker?.join(2_000)
    }

    // ── the conversation ───────────────────────────────────────────────────────────────────────────────────

    private fun serve() = converse(server.accept())

    /** The conversation, on a connection that was accepted ([serve]) or dialled ([dial]). */
    private fun converse(accepted: Socket) {
        socket = accepted
        accepted.tcpNoDelay = true
        input = DataInputStream(accepted.getInputStream())
        output = accepted.getOutputStream()
        events += if (dials) "dialled" else "accepted"
        if (hangUpHere(Stage.ACCEPTED) || script.silent) return

        val request = readFrame()
        check(request.channel == 0 && request.type == TYPE_VERSION_REQUEST) { "expected a version request, got type ${request.type}" }
        versionRequest = request.wire
        events += "version request"
        writeVersionResponse()
        events += "version answered"
        if (hangUpHere(Stage.VERSION_ANSWERED)) return

        if (!tlsHandshake()) return
        events += "tls done"

        // A head unit on Conscrypt wraps once more after its handshake is over and sends the nothing that comes out
        // of it as one more TLS message. The real Android Auto evidently lives with that; so does this one.
        var status = readFrame()
        while (status.channel == 0 && status.type == TYPE_TLS && status.payload.size == 2) {
            events += "empty tls message"
            status = readFrame()
        }
        check(status.channel == 0 && status.type == TYPE_AUTH_COMPLETE) { "expected status OK, got type ${status.type}" }
        statusOk = status.wire
        events += "status ok"
        handshakeDone.countDown()
        if (hangUpHere(Stage.HANDSHAKE_DONE)) return

        while (true) {
            val frame = readFrame()
            val plain = synchronized(this) { unwrap(frame.payload) }
            if (plain.size < 2) continue
            val type = ((plain[0].toInt() and 0xFF) shl 8) or (plain[1].toInt() and 0xFF)
            // The head unit's own keep-alive pings are counted apart, so they never stand in for the message a
            // test waits for.
            if (frame.channel == 0 && type == com.reau.app.aa.proto.Control.ControlMsgType.MESSAGE_PING_REQUEST_VALUE) {
                pingsReceived.incrementAndGet()
                if (script.answerPings) {
                    val pong = com.reau.app.aa.proto.Control.PingResponse.newBuilder().setTimestamp(System.nanoTime()).build()
                    send(0, com.reau.app.aa.proto.Control.ControlMsgType.MESSAGE_PING_RESPONSE_VALUE, pong.toByteArray())
                }
                continue
            }
            inbox += Received(frame.channel, frame.flags, type, plain.copyOfRange(2, plain.size))
            val byeDelay = script.answerByeAfterMs
            if (frame.channel == 0 && type == com.reau.app.aa.proto.Control.ControlMsgType.MESSAGE_BYEBYE_REQUEST_VALUE && byeDelay != null) {
                Thread.sleep(byeDelay)
                val answer = com.reau.app.aa.proto.Control.ByeByeResponse.newBuilder().build()
                runCatching { send(0, com.reau.app.aa.proto.Control.ControlMsgType.MESSAGE_BYEBYE_RESPONSE_VALUE, answer.toByteArray()) }
                    .onSuccess { events += "bye answered" }
                    .onFailure { events += "bye answer failed: ${it.javaClass.simpleName}" }
            }
        }
    }

    /** Major 1, the minor of the script, status 0. */
    private fun writeVersionResponse() =
        writeFrame(0, FLAGS_PLAIN, byteArrayOf(0, TYPE_VERSION_RESPONSE.toByte(), 0, 1, 0, script.minorVersion.toByte(), 0, 0))

    private fun hangUpHere(stage: Stage): Boolean {
        if (script.hangUpAt != stage) return false
        hangUp()
        return true
    }

    /** The server side of the TLS handshake. False if the script hung up in the middle of it. */
    private fun tlsHandshake(): Boolean {
        val tls = newEngine().also { engine = it }
        tls.beginHandshake()
        var pending = ByteArray(0)
        var flights = 0
        var lateAnswersSent = false
        while (true) {
            when (tls.handshakeStatus) {
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                    if (pending.isEmpty()) {
                        pending = readTlsBytes()
                        if (!lateAnswersSent) {
                            lateAnswersSent = true
                            repeat(script.lateVersionResponses) { writeVersionResponse() }
                        }
                    }
                    val source = ByteBuffer.wrap(pending)
                    val result = tls.unwrap(source, ByteBuffer.allocate(tls.session.applicationBufferSize))
                    if (result.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                        pending += readTlsBytes()
                    } else {
                        check(result.status == SSLEngineResult.Status.OK) { "TLS unwrap: ${result.status}" }
                        pending = ByteArray(source.remaining()).also { source.get(it) }
                    }
                }
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    val net = ByteBuffer.allocate(tls.session.packetBufferSize)
                    tls.wrap(ByteBuffer.allocate(0), net)
                    net.flip()
                    val bytes = ByteArray(net.remaining()).also { net.get(it) }
                    val chunk = if (script.tlsChunkBytes > 0) script.tlsChunkBytes else bytes.size.coerceAtLeast(1)
                    for (start in bytes.indices step chunk) {
                        val end = minOf(start + chunk, bytes.size)
                        writeFrame(0, FLAGS_PLAIN, byteArrayOf(0, TYPE_TLS.toByte()) + bytes.copyOfRange(start, end))
                    }
                    flights++
                    if (flights == 1 && hangUpHere(Stage.TLS_HALFWAY)) return false
                }
                SSLEngineResult.HandshakeStatus.NEED_TASK -> generateSequence { tls.delegatedTask }.forEach { it.run() }
                else -> break
            }
        }
        protocol = tls.session.protocol
        clientCertificate = runCatching { tls.session.peerCertificates.firstOrNull() as? X509Certificate }.getOrNull()
        return true
    }

    private fun newEngine(): SSLEngine {
        val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setKeyEntry("fake", identity.keyPair.private, CharArray(0), arrayOf(identity.certificate))
        }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, CharArray(0)) }.keyManagers
        val trustAnyone = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS").apply { init(keyManagers, arrayOf(trustAnyone), null) }
        return context.createSSLEngine().apply {
            useClientMode = false
            wantClientAuth = true
            enabledProtocols = arrayOf("TLSv1.2")
        }
    }

    /** The TLS bytes of the next handshake message (channel 0, type 3). */
    private fun readTlsBytes(): ByteArray {
        val frame = readFrame()
        check(frame.channel == 0 && frame.type == TYPE_TLS) { "expected TLS data, got channel ${frame.channel} type ${frame.type}" }
        return frame.payload.copyOfRange(2, frame.payload.size)
    }

    private fun wrap(plain: ByteArray): ByteArray {
        val tls = checkNotNull(engine) { "no TLS session yet" }
        val net = ByteBuffer.allocate(tls.session.packetBufferSize)
        val result = tls.wrap(ByteBuffer.wrap(plain), net)
        check(result.status == SSLEngineResult.Status.OK && result.bytesConsumed() == plain.size) { "TLS wrap: $result" }
        net.flip()
        return ByteArray(net.remaining()).also { net.get(it) }
    }

    private fun unwrap(encrypted: ByteArray): ByteArray {
        val tls = checkNotNull(engine) { "no TLS session yet" }
        val plain = ByteBuffer.allocate(tls.session.applicationBufferSize + encrypted.size)
        val source = ByteBuffer.wrap(encrypted)
        while (source.hasRemaining()) {
            val result = tls.unwrap(source, plain)
            check(result.status == SSLEngineResult.Status.OK) { "TLS unwrap: ${result.status}" }
        }
        plain.flip()
        return ByteArray(plain.remaining()).also { plain.get(it) }
    }

    // ── frames: channel, flags, payload length (2 bytes, big-endian), payload ─────────────────────────────────

    /** [payload] starts with the two type bytes when the frame is in the clear; [wire] is the frame as it came. */
    private class Frame(val channel: Int, val flags: Int, val payload: ByteArray, val wire: ByteArray) {
        val type: Int get() = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
    }

    private fun readFrame(): Frame {
        val source = checkNotNull(input)
        val header = ByteArray(4).also { source.readFully(it) }
        val length = ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
        val payload = ByteArray(length).also { source.readFully(it) }
        return Frame(header[0].toInt() and 0xFF, header[1].toInt() and 0xFF, payload, header + payload)
    }

    private fun writeFrame(channel: Int, flags: Int, payload: ByteArray) {
        sendRaw(byteArrayOf(channel.toByte(), flags.toByte(), (payload.size shr 8).toByte(), payload.size.toByte()) + payload)
    }

    private companion object {
        const val FLAGS_PLAIN = 0x03
        const val FLAGS_FIRST_PIECE = 0x09
        const val FLAGS_MIDDLE_PIECE = 0x08
        const val FLAGS_LAST_PIECE = 0x0A
        const val TYPE_VERSION_REQUEST = 1
        const val TYPE_VERSION_RESPONSE = 2
        const val TYPE_TLS = 3
        const val TYPE_AUTH_COMPLETE = 4
    }
}

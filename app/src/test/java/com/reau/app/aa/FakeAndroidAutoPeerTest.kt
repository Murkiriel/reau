package com.reau.app.aa

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.X509TrustManager

/**
 * The fake Android Auto checked on its own, against a head unit written here from nothing but the JDK: so that the
 * fake and the app's copied receiver cannot be wrong in the same way and agree with each other.
 */
class FakeAndroidAutoPeerTest {

    /** A head unit in forty lines: plain blocking socket, plain SSLEngine in client mode. */
    private class MiniHeadUnit(address: InetAddress, port: Int, private val identity: TestCertificate) {
        private val socket = Socket(address, port).apply { soTimeout = 5_000 }
        private val input = DataInputStream(socket.getInputStream())
        private val output = socket.getOutputStream()
        private val engine: SSLEngine = SSLContext.getInstance("TLS").run {
            val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null)
                setKeyEntry("headunit", identity.keyPair.private, CharArray(0), arrayOf(identity.certificate))
            }
            val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, CharArray(0)) }.keyManagers
            val trustAnyone = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            init(keyManagers, arrayOf(trustAnyone), null)
            createSSLEngine().apply { useClientMode = true }
        }

        fun frame(channel: Int, flags: Int, payload: ByteArray) {
            output.write(byteArrayOf(channel.toByte(), flags.toByte(), (payload.size shr 8).toByte(), payload.size.toByte()) + payload)
            output.flush()
        }

        /** Channel, flags and payload of the next frame. */
        fun read(): Triple<Int, Int, ByteArray> {
            val header = ByteArray(4).also { input.readFully(it) }
            val length = ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
            return Triple(header[0].toInt(), header[1].toInt(), ByteArray(length).also { input.readFully(it) })
        }

        /** Version responses that arrived when a TLS message was expected. */
        var lateVersionResponses = 0
            private set

        /** The TLS bytes of the next TLS message, stepping over version responses that come late. */
        private fun readTls(): ByteArray {
            while (true) {
                val payload = read().third
                if (payload[1].toInt() == 2) lateVersionResponses++ else return payload.copyOfRange(2, payload.size)
            }
        }

        fun tlsHandshake() {
            engine.beginHandshake()
            var pending = ByteArray(0)
            while (true) {
                when (engine.handshakeStatus) {
                    SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                        val net = ByteBuffer.allocate(engine.session.packetBufferSize)
                        engine.wrap(ByteBuffer.allocate(0), net)
                        net.flip()
                        frame(0, 3, byteArrayOf(0, 3) + ByteArray(net.remaining()).also { net.get(it) })
                    }
                    SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                        if (pending.isEmpty()) pending = readTls()
                        val source = ByteBuffer.wrap(pending)
                        val result = engine.unwrap(source, ByteBuffer.allocate(engine.session.applicationBufferSize))
                        pending = if (result.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                            pending + readTls()
                        } else {
                            ByteArray(source.remaining()).also { source.get(it) }
                        }
                    }
                    SSLEngineResult.HandshakeStatus.NEED_TASK -> generateSequence { engine.delegatedTask }.forEach { it.run() }
                    else -> return
                }
            }
        }

        fun encrypt(plain: ByteArray): ByteArray {
            val net = ByteBuffer.allocate(engine.session.packetBufferSize)
            engine.wrap(ByteBuffer.wrap(plain), net)
            net.flip()
            return ByteArray(net.remaining()).also { net.get(it) }
        }

        fun decrypt(encrypted: ByteArray): ByteArray {
            val plain = ByteBuffer.allocate(engine.session.applicationBufferSize)
            engine.unwrap(ByteBuffer.wrap(encrypted), plain)
            plain.flip()
            return ByteArray(plain.remaining()).also { plain.get(it) }
        }

        fun close() = socket.close()
    }

    private var peer: FakeAndroidAutoPeer? = null
    private var headUnit: MiniHeadUnit? = null
    private val identity = TestCertificate("Mini head unit")

    @After
    fun tearDown() {
        headUnit?.close()
        peer?.close()
    }

    private fun connect(script: FakeAndroidAutoPeer.Script = FakeAndroidAutoPeer.Script()): Pair<FakeAndroidAutoPeer, MiniHeadUnit> {
        val fake = FakeAndroidAutoPeer(script).also { peer = it }
        return fake to MiniHeadUnit(fake.address, fake.port, identity).also { headUnit = it }
    }

    private fun MiniHeadUnit.handshake() {
        frame(0, 3, byteArrayOf(0, 1, 0, 1, 0, 2))
        read()
        tlsHandshake()
        frame(0, 3, byteArrayOf(0, 4, 8, 0))
    }

    @Test
    fun `the certificate made on the spot is a valid self-signed X509 certificate`() {
        val made = TestCertificate("Somebody")
        made.certificate.verify(made.keyPair.public)
        made.certificate.checkValidity()
        assertEquals("CN=Somebody", made.certificate.subjectX500Principal.name)
        assertEquals(made.certificate.subjectX500Principal, made.certificate.issuerX500Principal)
        assertEquals(3, made.certificate.version)
    }

    @Test
    fun `it answers the version request, completes TLS 1_2 as the server and takes the status OK`() {
        val (fake, unit) = connect()

        unit.frame(0, 3, byteArrayOf(0, 1, 0, 1, 0, 2))
        val (channel, flags, payload) = unit.read()
        assertEquals(0, channel)
        assertEquals(3, flags)
        assertArrayEquals("type 2, version 1.7, status 0", byteArrayOf(0, 2, 0, 1, 0, 7, 0, 0), payload)

        unit.tlsHandshake()
        unit.frame(0, 3, byteArrayOf(0, 4, 8, 0))

        assertTrue("the fake never finished: ${fake.events}", fake.awaitHandshake())
        assertNull(fake.failure)
        assertEquals("TLSv1.2", fake.protocol)
        assertEquals("it asks for the head unit's certificate and keeps it", identity.certificate, fake.clientCertificate)
        assertArrayEquals(byteArrayOf(0, 3, 0, 6, 0, 1, 0, 1, 0, 2), fake.versionRequest)
        assertArrayEquals(byteArrayOf(0, 3, 0, 4, 0, 4, 8, 0), fake.statusOk)
    }

    @Test
    fun `after the handshake messages go both ways encrypted`() {
        val (fake, unit) = connect()
        unit.handshake()
        assertTrue(fake.awaitHandshake())

        unit.frame(2, 0x0B, unit.encrypt(byteArrayOf(0x80.toByte(), 0x04, 0x08, 7, 0x10, 1)))
        val got = checkNotNull(fake.receive())
        assertEquals(2, got.channel)
        assertEquals(0x0B, got.flags)
        assertEquals(0x8004, got.type)
        assertArrayEquals(byteArrayOf(0x08, 7, 0x10, 1), got.body)

        fake.send(channel = 0, type = 11, body = byteArrayOf(0x08, 42))
        val (channel, flags, payload) = unit.read()
        assertEquals(0, channel)
        assertEquals(0x0B, flags)
        assertArrayEquals("the type, then the body", byteArrayOf(0, 11, 0x08, 42), unit.decrypt(payload))
    }

    @Test
    fun `the script makes it answer the version again in the middle of TLS, or cut TLS into small messages`() {
        val (fake, unit) = connect(FakeAndroidAutoPeer.Script(lateVersionResponses = 2, tlsChunkBytes = 64, minorVersion = 9))
        unit.frame(0, 3, byteArrayOf(0, 1, 0, 1, 0, 2))
        assertArrayEquals(byteArrayOf(0, 2, 0, 1, 0, 9, 0, 0), unit.read().third)

        unit.tlsHandshake() // the head unit here puts the 64-byte pieces back together
        unit.frame(0, 3, byteArrayOf(0, 4, 8, 0))
        assertTrue("the fake never finished: ${fake.events}", fake.awaitHandshake())
        assertNotNull(fake.clientCertificate)
        assertEquals("the extra version answers came after TLS had begun", 2, unit.lateVersionResponses)
    }

    @Test
    fun `the script makes it hang up where told`() {
        for (stage in listOf(FakeAndroidAutoPeer.Stage.ACCEPTED, FakeAndroidAutoPeer.Stage.VERSION_ANSWERED)) {
            val (fake, unit) = connect(FakeAndroidAutoPeer.Script(hangUpAt = stage))
            unit.frame(0, 3, byteArrayOf(0, 1, 0, 1, 0, 2))
            val read = runCatching { repeat(2) { unit.read() } }
            assertTrue("$stage: the socket must be closed on the head unit, events ${fake.events}", read.isFailure)
            assertEquals("hung up", fake.events.last())
            tearDown()
        }
    }
}

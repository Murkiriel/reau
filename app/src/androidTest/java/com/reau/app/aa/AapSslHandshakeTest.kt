package com.reau.app.aa

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reau.app.aa.proto.Media
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Socket

/**
 * The copied TLS layer (AapSslContext, SingleKeyKeyManager, SocketAccessoryConnection) against the fake Android
 * Auto, over a real loopback socket. Characterisation: written AFTER the code, except the case about late version
 * responses, which is the open-headunit fix this item brings.
 *
 * It runs on the emulator and not on the JVM because the copied SingleKeyKeyManager only works with Android's
 * key store: the JDK's default one lower-cases the alias it looks its key up by. The version exchange is done by
 * hand here; AapTransport, which does it in the app, has its own test (AapTransportTest).
 */
@RunWith(AndroidJUnit4::class)
class AapSslHandshakeTest {
    private val headUnit = TestCertificate("Test head unit")
    private var peer: FakeAndroidAutoPeer? = null
    private var connection: SocketAccessoryConnection? = null

    @After
    fun tearDown() {
        connection?.disconnect()
        peer?.close()
        AaLog.sink = null
    }

    private fun connect(script: FakeAndroidAutoPeer.Script = FakeAndroidAutoPeer.Script()): Pair<FakeAndroidAutoPeer, SocketAccessoryConnection> {
        val fake = FakeAndroidAutoPeer(script).also { peer = it }
        val conn = SocketAccessoryConnection(Socket(fake.address, fake.port)).also { connection = it }
        return fake to conn
    }

    private fun newSsl() = AapSslContext(SingleKeyKeyManager(headUnit.certificate, headUnit.keyPair.private))

    /** Sends the version request and reads ONE version response, as AapTransport does before TLS. */
    private fun exchangeVersion(conn: SocketAccessoryConnection): ByteArray {
        val request = Messages.versionRequest
        assertEquals(request.size, conn.sendBlocking(request, request.size, 2000))
        val response = ByteArray(12)
        assertEquals(12, conn.recvBlocking(response, 12, 2000, true))
        return response
    }

    /** Version, TLS, status OK. */
    private fun handshake(conn: SocketAccessoryConnection, ssl: AapSslContext): Boolean {
        exchangeVersion(conn)
        if (!ssl.performHandshake(conn)) return false
        ssl.postHandshakeReset()
        val ok = Messages.statusOk
        return conn.sendBlocking(ok, ok.size, 2000) == ok.size
    }

    @Test
    fun theVersionResponseIsChannel0Type2Version1DotSomethingStatus0() {
        val (_, conn) = connect()
        assertArrayEquals(byteArrayOf(0, 3, 0, 8, 0, 2, 0, 1, 0, 7, 0, 0), exchangeVersion(conn))
    }

    @Test
    fun theHandshakeGoesThroughAndTheHeadUnitPresentsItsCertificate() {
        val (fake, conn) = connect()
        val ssl = newSsl()

        assertTrue(handshake(conn, ssl))
        assertTrue("the peer never saw the handshake finish: ${fake.events}", fake.awaitHandshake())

        assertArrayEquals(Messages.versionRequest, fake.versionRequest)
        assertArrayEquals(Messages.statusOk, fake.statusOk)
        assertEquals("TLSv1.2", fake.protocol)
        assertEquals("the head unit is the TLS client and must present its certificate", headUnit.certificate, fake.clientCertificate)
        // On Conscrypt the head unit sends one empty TLS message after its handshake is over, before the status OK.
        assertEquals(
            listOf("accepted", "version request", "version answered", "tls done", "empty tls message", "status ok"),
            fake.events.toList(),
        )
    }

    @Test
    fun afterTheHandshakeAMessageEncryptedByTheHeadUnitReachesThePeerInTheClear() {
        val (fake, conn) = connect()
        val ssl = newSsl()
        assertTrue(handshake(conn, ssl))

        // What AapTransport does to send: encrypt everything after the 4 header bytes, then rewrite the header.
        val message = MediaAck(Channel.ID_VID, sessionId = 7)
        val encrypted = checkNotNull(ssl.encrypt(AapMessage.HEADER_SIZE, message.size - AapMessage.HEADER_SIZE, message.data))
        assertEquals("the array is the whole frame: 4 header bytes left free, then the TLS record", encrypted.data.size, encrypted.limit)
        encrypted.data[0] = message.data[0]
        encrypted.data[1] = message.data[1]
        Utils.intToBytes(encrypted.limit - AapMessage.HEADER_SIZE, 2, encrypted.data)
        conn.sendBlocking(encrypted.data, encrypted.limit, 2000)

        val got = checkNotNull(fake.receive()) { "the peer received nothing: ${fake.events}" }
        assertEquals(Channel.ID_VID, got.channel)
        assertEquals(0x0B, got.flags)
        assertEquals(Media.MsgType.MEDIA_MESSAGE_ACK_VALUE, got.type)
        assertArrayEquals(byteArrayOf(0x08, 7, 0x10, 1), got.body)
    }

    @Test
    fun afterTheHandshakeAMessageEncryptedByThePeerIsDecryptedByTheHeadUnit() {
        val (fake, conn) = connect()
        val ssl = newSsl()
        assertTrue(handshake(conn, ssl))
        assertTrue(fake.awaitHandshake())

        fake.send(Channel.ID_CTR, type = 11, body = byteArrayOf(0x08, 42))
        val header = ByteArray(4)
        assertEquals(4, conn.recvBlocking(header, 4, 2000, true))
        val length = Utils.bytesToInt(header, 2, true)
        val frame = ByteArray(200)
        assertEquals(length, conn.recvBlocking(frame, length, 2000, true))

        val plain = checkNotNull(ssl.decrypt(0, length, frame))
        assertArrayEquals("the type, then the body", byteArrayOf(0, 11, 0x08, 42), plain.data.copyOf(plain.limit))
        assertEquals(0, header[0].toInt())
        assertEquals(0x0B, header[1].toInt())
    }

    @Test
    fun aTlsFlightThatArrivesCutIntoSmallMessagesIsPutBackTogether() {
        val (fake, conn) = connect(FakeAndroidAutoPeer.Script(tlsChunkBytes = 100))
        assertTrue(handshake(conn, newSsl()))
        assertTrue(fake.awaitHandshake())
    }

    // ── the fix from open-headunit ─────────────────────────────────────────────────────────────────────────

    @Test
    fun versionResponsesThatArriveLateInTheMiddleOfTlsAreSkipped() {
        val lines = mutableListOf<String>()
        AaLog.sink = { synchronized(lines) { lines += it } }
        val (fake, conn) = connect(FakeAndroidAutoPeer.Script(lateVersionResponses = 2))

        assertTrue(handshake(conn, newSsl()))
        assertTrue(fake.awaitHandshake())
        assertEquals(2, synchronized(lines) { lines.count { "discarded a late VERSION_RESPONSE" in it } })
    }

    // ── failures ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun aPeerThatHangsUpInTheMiddleOfTlsFailsTheHandshakeWithTheReasonInTheLog() {
        val lines = mutableListOf<String>()
        AaLog.sink = { synchronized(lines) { lines += it } }
        val (_, conn) = connect(FakeAndroidAutoPeer.Script(hangUpAt = FakeAndroidAutoPeer.Stage.TLS_HALFWAY))

        assertFalse(handshake(conn, newSsl()))
        assertTrue("got $lines", synchronized(lines) { lines.any { "SSL Handshake: Failed to read AAP" in it } })
    }

    @Test
    fun nothingIsEncryptedOrDecryptedBeforeAHandshake() {
        val lines = mutableListOf<String>()
        AaLog.sink = { synchronized(lines) { lines += it } }
        val ssl = newSsl()
        assertNull(ssl.encrypt(4, 2, ByteArray(8)))
        assertNull(ssl.decrypt(0, 8, ByteArray(8)))
        // Refused up front, each with its own line, not as a failure of the TLS engine.
        assertEquals(listOf("[AA] W: SSL Encrypt: Not initialized yet", "[AA] W: SSL Decrypt: Not initialized yet"), lines.toList())
    }

    @Test
    fun garbageIsNoMessageAndSixteenFfBytesMeanTheUserDisconnected() {
        val (_, conn) = connect()
        val ssl = newSsl()
        assertTrue(handshake(conn, ssl))

        assertNull(ssl.decrypt(0, 32, ByteArray(32) { (it * 7).toByte() }))
        assertFalse(ssl.isUserDisconnect)
        assertNull(ssl.decrypt(0, 16, ByteArray(16) { 0xFF.toByte() }))
        assertTrue(ssl.isUserDisconnect)
    }

    // ── the socket connection ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun aReadThatTimesOutIs0AClosedPeerIsMinus1AndSendingAfterDisconnectIsMinus1() {
        val (fake, conn) = connect(FakeAndroidAutoPeer.Script(silent = true))
        assertTrue(conn.isConnected)
        assertTrue(conn.isSingleMessage)
        assertTrue(conn.connect())
        assertEquals("nothing came within the timeout", 0, conn.recvBlocking(ByteArray(4), 4, 100, true))

        fake.hangUp()
        assertEquals("the peer is gone", -1, conn.recvBlocking(ByteArray(4), 4, 1000, true))
        assertEquals("a partial read of a closed peer too", -1, conn.recvBlocking(ByteArray(4), 4, 1000, false))

        conn.disconnect()
        assertFalse(conn.isConnected)
        assertEquals(-1, conn.sendBlocking(ByteArray(4), 4, 100))
        assertEquals(-1, conn.recvBlocking(ByteArray(4), 4, 100, true))
    }

    @Test
    fun aSendOnASocketThatWasClosedUnderTheConnectionFails() {
        val fake = FakeAndroidAutoPeer(FakeAndroidAutoPeer.Script(silent = true)).also { peer = it }
        val socket = Socket(fake.address, fake.port)
        val conn = SocketAccessoryConnection(socket).also { connection = it }
        assertEquals(4, conn.sendBlocking(ByteArray(4), 4, 100))

        socket.close() // not through the connection: it still holds its streams
        assertEquals("the write fails and that is reported", -1, conn.sendBlocking(ByteArray(4), 4, 100))
        assertFalse(conn.isConnected)
    }
}

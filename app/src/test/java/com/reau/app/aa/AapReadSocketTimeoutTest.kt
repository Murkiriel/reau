package com.reau.app.aa

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * A timeout inside a message, over a real loopback socket, with the real [SocketAccessoryConnection]: its full
 * read turns a timeout into 0 after DataInputStream.readFully has already consumed the bytes that did arrive, so the
 * reader must end the connection rather than skip the message and read the next "header" from inside it.
 *
 * The timeouts are the reader's parameters (the real ones are 15 s and 10 s), so the test waits a fraction of a second.
 */
class AapReadSocketTimeoutTest {
    private class PassThroughSsl : AapSsl {
        override fun decrypt(start: Int, length: Int, buffer: ByteArray): ByteArrayWithLimit? =
            ByteArrayWithLimit(buffer.copyOfRange(start, start + length), length)
        override fun encrypt(offset: Int, length: Int, buffer: ByteArray): ByteArrayWithLimit? = null
        override fun postHandshakeReset() = Unit
        override fun performHandshake(connection: AccessoryConnection) = true
        override fun release() = Unit
    }

    private val handled = mutableListOf<AapMessage>()
    private val handler = object : AapMessageHandler {
        override fun handle(message: AapMessage) {
            handled += message
        }
    }

    private lateinit var server: ServerSocket
    private lateinit var peer: Socket // Android Auto's end
    private lateinit var client: Socket // REau's end
    private lateinit var connection: SocketAccessoryConnection

    @Before
    fun connect() {
        server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        client = Socket(InetAddress.getLoopbackAddress(), server.localPort)
        peer = server.accept()
        connection = SocketAccessoryConnection(client)
    }

    @After
    fun close() {
        runCatching { peer.close() }
        runCatching { client.close() }
        runCatching { server.close() }
    }

    private fun reader() = AapReadSingleMessage(connection, PassThroughSsl(), handler, headerTimeoutMs = 2_000, bodyTimeoutMs = 300)

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun send(vararg values: Int) {
        peer.getOutputStream().write(bytes(*values))
        peer.getOutputStream().flush()
    }

    @Test
    fun `a whole message over the socket reaches the handler and the loop carries on`() {
        send(2, 0x0B, 0, 6, 0x80, 0x04, 0x08, 7, 0x10, 1)
        assertEquals(0, reader().read())
        val message = handled.single()
        assertEquals(Channel.ID_VID, message.channel)
        assertArrayEquals(byteArrayOf(0x08, 7, 0x10, 1), message.data.copyOfRange(message.dataOffset, message.size))
    }

    @Test
    fun `a message whose body stops half-way, then nothing within the timeout, ends the connection's reading`() {
        send(2, 0x0B, 0, 6, 0x80, 0x04) // the header says 6 bytes follow; 2 do, then silence
        val begun = System.currentTimeMillis()

        assertEquals(-1, reader().read())

        val took = System.currentTimeMillis() - begun
        assertTrue("ended by the body timeout (took $took ms), not by the longer header one", took < 1_500)
        assertTrue("nothing half-read reaches the handler", handled.isEmpty())
        // The transport closes the link on -1; the connection lets it (and the next session dials anew).
        connection.disconnect()
        assertFalse(connection.isConnected)
    }

    @Test
    fun `the partial read really consumed the bytes, so a later read would take a header from inside the message`() {
        // What the reader guards against, on the real stream: after a timeout, the 2 bytes that arrived are gone.
        send(2, 0x0B, 0, 6, 0x80, 0x04)
        val header = ByteArray(4)
        assertEquals(4, connection.recvBlocking(header, 4, 300, true))
        val body = ByteArray(6)
        assertEquals("the real full read reports the timeout as 0, although 2 bytes went into the buffer", 0, connection.recvBlocking(body, 6, 300, true))
        assertArrayEquals(bytes(0x80, 0x04), body.copyOf(2))
        send(0x08, 7, 0x10, 1) // the rest of the body comes late
        val rest = ByteArray(4)
        assertEquals(4, connection.recvBlocking(rest, 4, 300, true))
        assertArrayEquals("the stream resumes after the 2 bytes already taken: they can never be re-read", bytes(0x08, 7, 0x10, 1), rest)
    }

    @Test
    fun `a first fragment whose total size does not come in time ends the reading too`() {
        send(2, 0x09, 0, 4) // flags 09: the four bytes of the total size are due; they never come
        assertEquals(-1, reader().read())
        assertTrue(handled.isEmpty())
    }

    @Test
    fun `silence before any header is the header's own rule, a lost connection`() {
        assertEquals(-1, reader().read())
    }
}

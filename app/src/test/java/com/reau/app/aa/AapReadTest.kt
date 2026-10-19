package com.reau.app.aa

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque

/**
 * Characterisation of the copied read loop (AapReadSingleMessage for a socket that hands one message at a time,
 * AapReadMultipleMessages for a stream that hands bytes as they come), written AFTER the code. A scripted
 * connection plays the wire and a pass-through "TLS" leaves the bytes as they are, so each test is about framing.
 *
 * What read() returns: 0 = carry on, -1 = the connection is gone, -2 = the peer left on purpose.
 */
class AapReadTest {

    /**
     * Hands out the bytes it was given; when they run out, the scripted result (0 = timeout, -1 = closed). A full
     * read ([readFully]) behaves as the real one over a socket (DataInputStream.readFully): it takes whatever bytes
     * there are and, short of the length asked, gives the scripted result — the bytes taken are gone.
     */
    private class ScriptedConnection(override val isSingleMessage: Boolean, private val whenEmpty: Int = 0) : AccessoryConnection {
        val wire = ArrayDeque<Byte>()
        val chunks = ArrayDeque<ByteArray>() // for the stream kind: what each read returns
        var connected = true

        fun put(vararg bytes: Int) = bytes.forEach { wire.add(it.toByte()) }

        override val isConnected: Boolean get() = connected
        override fun connect() = true
        override fun disconnect() = Unit
        override fun sendBlocking(buf: ByteArray, length: Int, timeout: Int) = length

        override fun recvBlocking(buf: ByteArray, length: Int, timeout: Int, readFully: Boolean): Int {
            if (!readFully) {
                val chunk = chunks.poll() ?: return whenEmpty
                chunk.copyInto(buf)
                return chunk.size
            }
            val n = minOf(length, wire.size)
            repeat(n) { buf[it] = wire.poll() }
            return if (n == length) n else whenEmpty
        }
    }

    /** Leaves the bytes as they are. */
    private class PassThroughSsl(private val fail: Boolean = false) : AapSsl {
        override fun decrypt(start: Int, length: Int, buffer: ByteArray): ByteArrayWithLimit? =
            if (fail) null else ByteArrayWithLimit(buffer.copyOfRange(start, start + length), length)
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

    private fun AapMessage.body() = data.copyOfRange(dataOffset, size)

    // ── one message at a time (the loopback socket) ────────────────────────────────────────────────────────

    private fun single(connection: ScriptedConnection, ssl: AapSsl = PassThroughSsl()) = AapRead.Factory.create(connection, ssl, handler)

    @Test
    fun `a whole frame becomes one message for the handler`() {
        val connection = ScriptedConnection(isSingleMessage = true)
        connection.put(2, 0x0B, 0, 6, 0x80, 0x04, 0x08, 7, 0x10, 1)

        assertEquals(0, single(connection).read())

        val message = handled.single()
        assertEquals(Channel.ID_VID, message.channel)
        assertEquals(0x0B.toByte(), message.flags)
        assertEquals(0x8004, message.type)
        assertArrayEquals(byteArrayOf(0x08, 7, 0x10, 1), message.body())
    }

    @Test
    fun `the first piece of a fragmented message carries four extra bytes, which are skipped`() {
        val connection = ScriptedConnection(isSingleMessage = true)
        connection.put(2, 0x09, 0, 4) // flags 09: first fragment
        connection.put(0, 0, 0x12, 0x34) // total size of the whole message: not part of this frame's payload
        connection.put(0, 0, 0xAA, 0xBB)

        assertEquals(0, single(connection).read())
        assertArrayEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte()), handled.single().body())
        assertEquals(0x09.toByte(), handled.single().flags)
    }

    @Test
    fun `a closed connection ends the loop, a quiet one does not`() {
        assertEquals("closed", -1, single(ScriptedConnection(isSingleMessage = true, whenEmpty = -1)).read())
        assertEquals("nothing yet", 0, single(ScriptedConnection(isSingleMessage = true, whenEmpty = 0)).read())
        assertTrue(handled.isEmpty())
    }

    @Test
    fun `a connection that closes in the middle of a frame ends the loop`() {
        val connection = ScriptedConnection(isSingleMessage = true, whenEmpty = -1)
        connection.put(2, 0x0B, 0, 6) // the header says 6 bytes follow; none do
        assertEquals(-1, single(connection).read())
    }

    // ── a timeout inside a message ends the connection ──────────────────────────────────────────────────────────
    // A full read that times out has already taken the bytes it got (DataInputStream.readFully gives them no way
    // back): the next read would take a "header" from inside the message, and nothing after it would ever decrypt,
    // while the session stayed "active". Before the fix the frame was skipped and the loop carried on exactly so.

    @Test
    fun `a frame cut short by a timeout ends the loop, since the bytes already taken cannot be put back`() {
        val connection = ScriptedConnection(isSingleMessage = true)
        connection.put(2, 0x0B, 0, 6, 0x80, 0x04) // 2 of the 6 bytes, then silence
        assertEquals(-1, single(connection).read())
        assertTrue(handled.isEmpty())
    }

    @Test
    fun `a fragment whose size does not come in time ends the loop too`() {
        val connection = ScriptedConnection(isSingleMessage = true)
        connection.put(2, 0x09, 0, 4) // flags 09: four bytes of total size are due next; none come
        assertEquals(-1, single(connection).read())
        assertTrue(handled.isEmpty())

        val twoOfFour = ScriptedConnection(isSingleMessage = true).apply { put(2, 0x09, 0, 4, 0, 0) }
        assertEquals(-1, single(twoOfFour).read())
    }

    @Test
    fun `a whole message after a whole message is read as before`() {
        val connection = ScriptedConnection(isSingleMessage = true)
        connection.put(2, 0x0B, 0, 6, 0x80, 0x04, 0x08, 7, 0x10, 1)
        connection.put(1, 0x0B, 0, 3, 0x80, 0x03, 9)
        val read = single(connection)
        assertEquals(0, read.read())
        assertEquals(0, read.read())
        assertEquals(listOf(Channel.ID_VID, Channel.ID_SEN), handled.map { it.channel })
        assertEquals("quiet after the two is not an end", 0, read.read())
    }

    @Test
    fun `a header that does not come whole is still skipped off a connection that is not a socket`() {
        // Unchanged from before: the half header stays with the header's own rule (no timeout on a non-socket
        // connection, which this app never has). The timeout rule is about what comes after a header already consumed.
        val halfHeader = ScriptedConnection(isSingleMessage = true).apply { put(2, 0x0B) }
        assertEquals(0, single(halfHeader).read())
    }

    @Test
    fun `four FF bytes instead of a header mean the peer left on purpose`() {
        val connection = ScriptedConnection(isSingleMessage = true)
        connection.put(0xFF, 0xFF, 0xFF, 0xFF)
        assertEquals(-2, single(connection).read())
    }

    @Test
    fun `a frame that does not decrypt is dropped`() {
        val connection = ScriptedConnection(isSingleMessage = true)
        connection.put(2, 0x0B, 0, 6, 0x80, 0x04, 0x08, 7, 0x10, 1)
        assertEquals(0, single(connection, PassThroughSsl(fail = true)).read())
        assertTrue(handled.isEmpty())
    }

    @Test
    fun `a handler that throws does not end the loop`() {
        val connection = ScriptedConnection(isSingleMessage = true)
        connection.put(2, 0x0B, 0, 6, 0x80, 0x04, 0x08, 7, 0x10, 1)
        val throwing = object : AapMessageHandler {
            override fun handle(message: AapMessage) = error("handler broke")
        }
        assertEquals(0, AapRead.Factory.create(connection, PassThroughSsl(), throwing).read())
    }

    // ── bytes as they come (a stream) ──────────────────────────────────────────────────────────────────────

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun stream(connection: ScriptedConnection) = AapRead.Factory.create(connection, PassThroughSsl(), handler)

    @Test
    fun `two frames in one read are two messages`() {
        val connection = ScriptedConnection(isSingleMessage = false)
        connection.chunks += bytes(2, 0x0B, 0, 4, 0x80, 0x04, 1, 2) + bytes(1, 0x0B, 0, 3, 0x80, 0x03, 9)

        assertEquals(0, stream(connection).read())

        assertEquals(listOf(Channel.ID_VID, Channel.ID_SEN), handled.map { it.channel })
        assertArrayEquals(byteArrayOf(1, 2), handled[0].body())
        assertArrayEquals(byteArrayOf(9), handled[1].body())
    }

    @Test
    fun `a frame that arrives over three reads is one message, when the last piece comes`() {
        val connection = ScriptedConnection(isSingleMessage = false)
        connection.chunks += bytes(2, 0x0B)
        connection.chunks += bytes(0, 4, 0x80)
        connection.chunks += bytes(0x04, 1, 2)
        val read = stream(connection)

        assertEquals(0, read.read())
        assertEquals(0, read.read())
        assertTrue("nothing until the frame is whole", handled.isEmpty())
        assertEquals(0, read.read())
        assertArrayEquals(byteArrayOf(1, 2), handled.single().body())
    }

    @Test
    fun `in a stream too the first fragment's four extra bytes are skipped`() {
        val connection = ScriptedConnection(isSingleMessage = false)
        connection.chunks += bytes(2, 0x09, 0, 4, 0, 0, 0x12, 0x34, 0, 0, 0xAA, 0xBB)
        assertEquals(0, stream(connection).read())
        assertArrayEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte()), handled.single().body())
    }

    @Test
    fun `a stream that stops giving bytes ends the loop only if the connection is gone`() {
        val alive = ScriptedConnection(isSingleMessage = false, whenEmpty = -1)
        assertEquals("a failed read on a live connection", 0, stream(alive).read())

        val gone = ScriptedConnection(isSingleMessage = false, whenEmpty = -1).apply { connected = false }
        assertEquals(-1, stream(gone).read())

        assertEquals("a quiet stream", 0, stream(ScriptedConnection(isSingleMessage = false, whenEmpty = 0)).read())
    }
}

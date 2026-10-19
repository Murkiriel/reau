package com.reau.app.aa

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * `AapRead: Connection closed (EOF)` came twice, 0.3 s and 3 s after the charger was plugged
 * and unplugged, and the line said nothing about why. The line that follows it now says how long the session lived, how
 * long ago the last message came, and what the phone was doing (the charger, the screen, doze) as far as REau knew.
 */
class AapReadEndNoteTest {
    private class PassThroughSsl : AapSsl {
        override fun decrypt(start: Int, length: Int, buffer: ByteArray): ByteArrayWithLimit? =
            ByteArrayWithLimit(buffer.copyOfRange(start, start + length), length)
        override fun encrypt(offset: Int, length: Int, buffer: ByteArray): ByteArrayWithLimit? = null
        override fun postHandshakeReset() = Unit
        override fun performHandshake(connection: AccessoryConnection) = true
        override fun release() = Unit
    }

    private val handler = object : AapMessageHandler {
        override fun handle(message: AapMessage) = Unit
    }
    private val said = mutableListOf<String>()
    private lateinit var server: ServerSocket
    private lateinit var peer: Socket
    private lateinit var client: Socket
    private lateinit var connection: SocketAccessoryConnection

    @Before
    fun connect() {
        said.clear()
        AaLog.sink = { said += it.removePrefix("[AA] ") }
        AaLog.phoneNow = { "charger in, screen on, doze off" }
        server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        client = Socket(InetAddress.getLoopbackAddress(), server.localPort)
        peer = server.accept()
        connection = SocketAccessoryConnection(client)
    }

    @After
    fun close() {
        AaLog.sink = null
        AaLog.phoneNow = null
        runCatching { peer.close() }
        runCatching { client.close() }
        runCatching { server.close() }
    }

    private fun reader() = AapReadSingleMessage(connection, PassThroughSsl(), handler, headerTimeoutMs = 400, bodyTimeoutMs = 300)

    private fun send(vararg values: Int) {
        peer.getOutputStream().write(ByteArray(values.size) { values[it].toByte() })
        peer.getOutputStream().flush()
    }

    @Test
    fun `an end of file says how long the session lived, when the last message came and what the phone was doing`() {
        val read = reader()
        send(2, 0x0B, 0, 6, 0x80, 0x04, 0x08, 7, 0x10, 1)
        assertEquals(0, read.read())
        Thread.sleep(150)
        peer.close()
        assertEquals(-1, read.read())

        val eof = said.indexOfFirst { "Connection closed (EOF)" in it }
        assertTrue(said.toString(), eof >= 0)
        val note = said[eof + 1]
        assertTrue(note, note.startsWith("AapRead: the link ended after "))
        assertTrue(note, Regex("""the last message came \d+ ms before""").containsMatchIn(note))
        assertTrue(note, note.endsWith("; phone: charger in, screen on, doze off"))
        val before = Regex("""the last message came (\d+) ms before""").find(note)!!.groupValues[1].toLong()
        assertTrue("about 150 ms after the one message: $before", before in 100..2_000)
    }

    @Test
    fun `a session that never heard a message says so, and a missing phone state is left out`() {
        AaLog.phoneNow = null
        val read = reader()
        peer.close()
        assertEquals(-1, read.read())
        val note = said.single { it.startsWith("AapRead: the link ended after ") }
        assertTrue(note, "no message came" in note)
        assertTrue(note, "phone:" !in note)
    }

    @Test
    fun `a read timeout says it too, after its own line`() {
        val read = reader()
        assertEquals(-1, read.read()) // nothing comes: the 400 ms header timeout
        val timeout = said.indexOfFirst { "WiFi read timeout (15s)" in it }
        assertTrue(said.toString(), timeout >= 0)
        assertTrue(said[timeout + 1], said[timeout + 1].startsWith("AapRead: the link ended after "))
    }
}

package com.reau.app.aa

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** The protocol version the handshake asks: 1.7 as always, or 6.0 first on the experimental switch with the way back to 1.7. */
class ProtocolAskTest {
    private val said = mutableListOf<Pair<String, Boolean>>()
    private val say: (String, Boolean) -> Unit = { line, failed -> said += line to failed }

    @Before
    @After
    fun reset() = ProtocolAsk.testOnlyReset()

    @Test
    fun `with the switch off 1 dot minor is asked, whatever happened before`() {
        assertEquals(ProtocolAsk.Version(1, 7), ProtocolAsk.resolve(wantSix = false, minor = 7))
        assertEquals(ProtocolAsk.Version(1, 2), ProtocolAsk.resolve(wantSix = false, minor = 2))
        ProtocolAsk.settle(ProtocolAsk.SIX, ok = false, answered = null, say = say)
        assertEquals("a failed 6.0 changes nothing for who never asked it", ProtocolAsk.Version(1, 7), ProtocolAsk.resolve(false, 7))
    }

    @Test
    fun `with the switch on 6 dot 0 is asked`() {
        assertEquals(ProtocolAsk.Version(6, 0), ProtocolAsk.resolve(wantSix = true, minor = 7))
        assertEquals("6.0", ProtocolAsk.resolve(true, 7).toString())
    }

    @Test
    fun `a handshake that asked 6 dot 0 and did not complete makes the next ones ask 1 dot 7, and says so`() {
        ProtocolAsk.settle(ProtocolAsk.SIX, ok = false, answered = "1.7, status -1", say = say)
        assertEquals(ProtocolAsk.Version(1, 7), ProtocolAsk.resolve(true, 7))
        assertEquals(1, said.size)
        assertTrue(said[0].second)
        assertTrue(said[0].first, "asked protocol 6.0, answered 1.7, status -1" in said[0].first && "1.7 is asked from now on" in said[0].first)
    }

    @Test
    fun `no answer at all is told as such`() {
        ProtocolAsk.settle(ProtocolAsk.SIX, ok = false, answered = null, say = say)
        assertTrue(said[0].first, "answered no answer" in said[0].first)
    }

    @Test
    fun `a handshake that completed keeps asking 6 dot 0, even when Android Auto answered another version`() {
        ProtocolAsk.settle(ProtocolAsk.SIX, ok = true, answered = "1.7, status 0", say = say)
        assertEquals(ProtocolAsk.SIX, ProtocolAsk.resolve(true, 7))
        assertEquals(1, said.size)
        assertEquals(false, said[0].second)
        assertTrue(said[0].first, "asked protocol 6.0, answered 1.7, status 0" in said[0].first && "completed" in said[0].first)
    }

    @Test
    fun `a handshake that asked 1 dot 7 is never told about, ok or not`() {
        ProtocolAsk.settle(ProtocolAsk.Version(1, 7), ok = true, answered = "1.7, status 0", say = say)
        ProtocolAsk.settle(ProtocolAsk.Version(1, 7), ok = false, answered = null, say = say)
        assertEquals(emptyList<Pair<String, Boolean>>(), said)
    }

    @Test
    fun `the receiver asks through it and the transport tells it how the handshake ended`() {
        val receiver = File("src/main/java/com/reau/app/aa/AaReceiver.kt").readText()
        assertTrue(receiver.contains("ProtocolAsk.resolve(config.protocolSix, config.protocolMinor)"))
        assertTrue(receiver.contains("protocolMajor = asked.major, protocolMinor = asked.minor"))
        val transport = File("src/main/java/com/reau/app/aa/AapTransport.kt").readText()
        assertTrue(transport.contains("ProtocolAsk.settle(ProtocolAsk.Version(protocolMajor, protocolMinor), handshook, answeredVersion)"))
        assertTrue(transport.contains("Messages.versionRequestFor(protocolMajor, protocolMinor)"))
        assertTrue("what Android Auto answered is kept for the line", transport.contains("answeredVersion = Messages.versionAnswer(buffer, ret)"))
        assertTrue("one request, as ever", transport.contains("versionRequests: Int = AaConfig.VERSION_REQUESTS"))
    }
}

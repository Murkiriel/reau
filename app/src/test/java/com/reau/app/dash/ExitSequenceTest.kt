package com.reau.app.dash

import com.reau.app.data.RideDiagnostics
import com.reau.app.dash.protocol.DashCommands
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The commands sent to the dash when it is switched off (Desligar), and what the ride log says of them (item 29 of the 11/11/2026 list). The
 * commands, their order and their number are what they were: the first test holds that on the wire; the log is the addition.
 */
class ExitSequenceTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var fakeDash: DatagramSocket? = null

    @After
    fun tearDown() {
        scope.cancel()
        fakeDash?.close()
        RideDiagnostics.testOnlyReset()
    }

    // ── the log's line, pure ──

    private class Clock(var now: Long = 1_000) : () -> Long {
        override fun invoke() = now
    }

    @Test
    fun `each command is told with whether it went and when, the repeats grouped`() {
        val clock = Clock()
        val seq = ExitSequence(clock)
        seq.step("projection-stop", true)
        clock.now += 1
        seq.step("projection-off", true)
        clock.now += 1
        seq.step("back", false)
        repeat(4) { clock.now += 1; seq.step("idle", true) }
        repeat(2) { clock.now += 1; seq.step("boundary", true) }
        assertEquals(
            "exit sequence: 9 commands, 1 not sent: projection-stop sent +0 ms; projection-off sent +1 ms; back NOT SENT +2 ms; " +
                "idle sent x4 +3..6 ms; boundary sent x2 +7..8 ms; socket closed +8 ms; " +
                "no answer of the dash read (the receive loop is cancelled before the commands and the socket closes right after them)",
            seq.summary(),
        )
    }

    @Test
    fun `an answer that reached the receive loop is told with its keys, after the first command, and counted in the summary`() {
        val clock = Clock()
        val seq = ExitSequence(clock)
        assertEquals("exit sequence: the dash answered before the first exit command (0B02)", seq.reply("0B02"))
        seq.step("projection-stop", true)
        clock.now += 3
        assertEquals("exit sequence: the dash answered 3 ms after the first exit command (0902), last command sent: projection-stop", seq.reply("0902"))
        assertTrue(seq.summary(), "2 packet(s) of the dash read meanwhile" in seq.summary())
    }

    @Test
    fun `only the first answers are written, the rest are counted`() {
        val seq = ExitSequence(Clock())
        val written = (1..ExitSequence.MAX_REPLY_LINES + 4).count { seq.reply("0B02") != null }
        assertEquals(ExitSequence.MAX_REPLY_LINES, written)
        assertNull(seq.reply("0B02"))
        assertTrue(seq.summary(), "${ExitSequence.MAX_REPLY_LINES + 5} packet(s)" in seq.summary())
    }

    // ── on the wire and in the log, with real sockets ──

    private fun commandKeys(packet: ByteArray) = DashDump.outgoingTlvs(packet).map { "%02X%02X:%s".format(it.type, it.sub, it.value.joinToString("") { b -> "%02X".format(b) }) }

    @Test
    fun `disconnect sends the same nine commands in the same order, and the log tells them`() {
        val log: File = tmp.newFile("ride-exit.log")
        RideDiagnostics.testOnlySetFile(log)
        val dash = DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)).also { fakeDash = it; it.soTimeout = 200 }
        val ctrl = InetSocketAddress(InetAddress.getLoopbackAddress(), dash.localPort)
        val rtp = InetSocketAddress(InetAddress.getLoopbackAddress(), 1)
        val session = DashSession(scope, socketFactory = { DashSocket(it, ctrlDest = ctrl, rtpDest = rtp) })
        session.connect("RE_TEST_1234")
        val deadline = System.currentTimeMillis() + 5_000
        while (session.state.value != DashState.AUTHENTICATING && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(DashState.AUTHENTICATING, session.state.value)
        drain(dash)

        session.disconnect()
        val received = drain(dash)

        // The tail of what the dash heard is the exit sequence: stop, off, back, four idles, two boundaries (nine packets).
        val expected = listOf(
            DashCommands.projectionStop(), DashCommands.projectionOff(), DashCommands.exitBack(),
            DashCommands.exitProjectionIdle(), DashCommands.exitProjectionIdle(), DashCommands.exitProjectionIdle(), DashCommands.exitProjectionIdle(),
            DashCommands.exitSessionBoundary(), DashCommands.exitSessionBoundary(),
        ).map(::commandKeys)
        val heard = received.map(::commandKeys)
        assertEquals("the last ${expected.size} packets are the exit sequence, in order: $heard", expected, heard.takeLast(expected.size))

        RideDiagnostics.logAndWait("ui", "end")
        val line = log.readLines().single { "exit sequence:" in it && "commands" in it }
        assertTrue(line, "9 commands, 0 not sent: projection-stop sent +" in line && "idle sent x4" in line && "boundary sent x2" in line)
    }

    /** Everything that reaches [socket] until it is quiet for its timeout. */
    private fun drain(socket: DatagramSocket): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        val buffer = ByteArray(2048)
        while (true) {
            val p = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(p)
            } catch (_: java.net.SocketTimeoutException) {
                return out
            }
            out += p.data.copyOf(p.length)
        }
    }
}

package com.reau.app.dash

import com.reau.app.dash.protocol.TelemetryEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [DashSession.dispatchIncoming]'s telemetry-emitting branches (0x0F, 0x0C, and the generic
 * fallback — see that function's own comments) feed the Telemetry Lab via
 * [DashSession.telemetry]. Feeds hand-built raw TLV packets straight into [DashSession.dispatchIncoming]
 * (made `internal` for exactly this) — no real handshake/dash peer needed, same "real DashSocket,
 * no Android dependency" approach [DashSessionTest] already established, just deeper into the
 * dispatch path than that file's connect()-guard tests go.
 */
class DashSessionTelemetryTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var session: DashSession? = null
    private var sock: DashSocket? = null

    @After
    fun tearDown() {
        sock?.close()
        session?.disconnect()
        scope.cancel()
    }

    /** Minimal incoming-shaped packet: 8-byte header (segCount at [2..3]) + one TLV — same shape
     *  K1GPacketTest's own `parseIncoming` fixtures use. */
    private fun rawPacket(type: Int, sub: Int, value: ByteArray): ByteArray {
        val header = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00)
        val tlvHeader = byteArrayOf(
            type.toByte(), sub.toByte(),
            ((value.size shr 8) and 0xFF).toByte(), (value.size and 0xFF).toByte(),
        )
        return header + tlvHeader + value
    }

    /** Starts collecting [DashSession.telemetry] into [into] BEFORE the caller emits anything —
     *  it's a plain [kotlinx.coroutines.flow.MutableSharedFlow] with replay=0, so an emit with no
     *  subscriber yet would be silently lost. Launched on [runBlockingScope] itself (its
     *  single-threaded event-loop dispatcher), NOT the class's own IO-dispatched [scope] used to
     *  construct [DashSession] — collecting on a different real thread would make the emit →
     *  `into.add(it)` handoff genuinely asynchronous, racing the assertions right after
     *  `dispatchIncoming()` returns. On the shared runBlocking dispatcher, a plain `yield()` after
     *  emitting is enough to let the buffered event actually get consumed before asserting.
     *  [CoroutineStart.UNDISPATCHED] runs the coroutine synchronously up to its first suspension
     *  point (subscribing inside `collect`), so by the time this function returns the subscription
     *  is already live. */
    private fun collectTelemetry(runBlockingScope: CoroutineScope, session: DashSession, into: MutableList<TelemetryEvent>): Job =
        runBlockingScope.launch(start = CoroutineStart.UNDISPATCHED) {
            session.telemetry.collect { into.add(it) }
        }

    @Test
    fun `a 0C telemetry TLV is emitted with no decoded payload`() = runBlocking {
        val s = DashSession(scope).also { session = it }
        val events = mutableListOf<TelemetryEvent>()
        val job = collectTelemetry(this, s, events)
        val sockLocal = DashSocket().also { sock = it }

        s.dispatchIncoming(rawPacket(0x0C, 0x02, byteArrayOf(0x11, 0x22)), sockLocal)
        yield()

        assertEquals(1, events.size)
        assertEquals(0x0C, events[0].type)
        assertEquals(0x02, events[0].sub)
        assertArrayEquals(byteArrayOf(0x11, 0x22), events[0].raw)
        assertNull(events[0].decoded)
        job.cancel()
    }

    @Test
    fun `a 0F telemetry TLV is emitted with a null decoded payload when there is no session key yet`() = runBlocking {
        // dispatchIncoming reads auth?.sessionKey — auth is only set by connect(), which this test
        // deliberately never calls (no real handshake), so decryption is expected to no-op to null
        // rather than throw.
        val s = DashSession(scope).also { session = it }
        val events = mutableListOf<TelemetryEvent>()
        val job = collectTelemetry(this, s, events)
        val sockLocal = DashSocket().also { sock = it }

        s.dispatchIncoming(rawPacket(0x0F, 0x05, byteArrayOf(0xAA.toByte(), 0xBB.toByte())), sockLocal)
        yield()

        assertEquals(1, events.size)
        assertEquals(0x0F, events[0].type)
        assertEquals(0x05, events[0].sub)
        assertArrayEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte()), events[0].raw)
        assertNull(events[0].decoded)
        job.cancel()
    }

    @Test
    fun `an unrecognized TLV type falls through to the generic telemetry branch`() = runBlocking {
        val s = DashSession(scope).also { session = it }
        val events = mutableListOf<TelemetryEvent>()
        val job = collectTelemetry(this, s, events)
        val sockLocal = DashSocket().also { sock = it }

        s.dispatchIncoming(rawPacket(0x42, 0x01, byteArrayOf(0x01)), sockLocal)
        yield()

        assertEquals(1, events.size)
        assertEquals(0x42, events[0].type)
        assertEquals(0x01, events[0].sub)
        job.cancel()
    }

    @Test
    fun `multiple TLVs in one packet each produce their own telemetry event, in order`() = runBlocking {
        val s = DashSession(scope).also { session = it }
        val events = mutableListOf<TelemetryEvent>()
        val job = collectTelemetry(this, s, events)
        val sockLocal = DashSocket().also { sock = it }

        val header = byteArrayOf(0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00, 0x00)
        val tlv1 = byteArrayOf(0x0C, 0x01, 0x00, 0x01, 0x10)
        val tlv2 = byteArrayOf(0x0C, 0x02, 0x00, 0x01, 0x20)
        s.dispatchIncoming(header + tlv1 + tlv2, sockLocal)
        yield()

        assertEquals(2, events.size)
        assertEquals(0x01, events[0].sub)
        assertEquals(0x02, events[1].sub)
        job.cancel()
    }

    @Test
    fun `a frame-decode ack TLV (09 06 55) does not produce a telemetry event`() = runBlocking {
        // The frame-ack/joystick/auth branches all `continue` before reaching the telemetry emit —
        // this pins that they stay silent on the telemetry flow instead of flooding it at ~8x/s.
        val s = DashSession(scope).also { session = it }
        val events = mutableListOf<TelemetryEvent>()
        val job = collectTelemetry(this, s, events)
        val sockLocal = DashSocket().also { sock = it }

        s.dispatchIncoming(rawPacket(0x09, 0x06, byteArrayOf(0x55)), sockLocal)
        yield()

        assertEquals(0, events.size)
        job.cancel()
    }
}

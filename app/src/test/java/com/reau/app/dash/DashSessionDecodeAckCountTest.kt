package com.reau.app.dash

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** [DashSession.decodedFrameCount] counts the dash's per-frame decode acks (09 06 55 keyframe,
 *  09 04 55 P-frame) — the automatic-fps controller's only dash-side delivery signal. */
class DashSessionDecodeAckCountTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = DashSession(scope)
    private val sock = DashSocket()

    @After
    fun tearDown() {
        sock.close()
        session.disconnect()
        scope.cancel()
    }

    private fun packet(vararg tlvs: Triple<Int, Int, ByteArray>): ByteArray {
        val header = byteArrayOf(0x00, 0x00, 0x00, tlvs.size.toByte(), 0x00, 0x00, 0x00, 0x00)
        return tlvs.fold(header) { acc, (type, sub, value) ->
            acc + byteArrayOf(type.toByte(), sub.toByte(), ((value.size shr 8) and 0xFF).toByte(), (value.size and 0xFF).toByte()) + value
        }
    }

    private val idrAck = Triple(0x09, 0x06, byteArrayOf(0x55))
    private val pAck = Triple(0x09, 0x04, byteArrayOf(0x55))

    @Test
    fun `starts at zero`() {
        assertEquals(0L, session.decodedFrameCount)
    }

    @Test
    fun `counts both keyframe and P-frame decode acks`() {
        session.dispatchIncoming(packet(idrAck), sock)
        repeat(5) { session.dispatchIncoming(packet(pAck), sock) }
        assertEquals(6L, session.decodedFrameCount)
    }

    @Test
    fun `counts every ack TLV when several arrive in one packet`() {
        session.dispatchIncoming(packet(pAck, pAck, idrAck), sock)
        assertEquals(3L, session.decodedFrameCount)
    }

    @Test
    fun `other TLVs on the same sub-types are not counted`() {
        session.dispatchIncoming(packet(Triple(0x09, 0x04, byteArrayOf(0x00))), sock)
        session.dispatchIncoming(packet(Triple(0x09, 0x06, byteArrayOf(0x01))), sock)
        assertEquals(0L, session.decodedFrameCount)
    }
}

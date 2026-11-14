package com.reau.app.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The experimental spacing of the RTP packets: a pause after every 4 packets of a frame and at most 400 packets in a second, on the thread that
 * sends, with nothing queued. Off (no [RtpPacing] in the [RtpOutput]), the packets leave exactly as they did.
 */
class RtpPacingTest {
    private var clockNs = 1_000_000_000L
    private val pauses = mutableListOf<Long>()
    private val events = mutableListOf<String>()
    private val pacing = RtpPacing(now = { clockNs }, pause = { pauses += it; clockNs += it })

    private fun send(count: Int) = repeat(count) {
        pacing.before()
        events += "send"
        pacing.after()
    }

    @Test
    fun `a frame of ten packets waits 2 ms after the fourth and after the eighth`() {
        pacing.frameStart()
        send(10)
        assertEquals(listOf(2_000_000L, 2_000_000L), pauses)
    }

    @Test
    fun `three packets leave with no wait at all`() {
        pacing.frameStart()
        send(3)
        assertEquals(emptyList<Long>(), pauses)
    }

    @Test
    fun `the count of four starts again with each frame`() {
        pacing.frameStart()
        send(3)
        pacing.frameStart()
        send(3)
        assertEquals("three and three are not four in a frame", emptyList<Long>(), pauses)
        pacing.frameStart()
        send(4)
        assertEquals(1, pauses.size)
    }

    @Test
    fun `the pause comes after the packet, never before the first one`() {
        val order = mutableListOf<String>()
        val p = RtpPacing(now = { clockNs }, pause = { order += "pause" })
        p.frameStart()
        repeat(5) {
            p.before()
            order += "send"
            p.after()
        }
        assertEquals(listOf("send", "send", "send", "send", "pause", "send"), order)
    }

    @Test
    fun `the 401st packet of a second waits for the second to end`() {
        // 100 frames of 4 packets inside the same second (this clock stands still, so the 2 ms pauses do not move it).
        val noPause = RtpPacing(now = { clockNs }, pause = { pauses += it })
        repeat(400) {
            if (it % 4 == 0) noPause.frameStart()
            noPause.before()
            noPause.after()
        }
        val before = pauses.size
        noPause.before()
        assertEquals("one wait more, for the rest of the second", before + 1, pauses.size)
        assertEquals(1_000_000_000L, pauses.last())
        assertEquals(1, noPause.capHits)
    }

    @Test
    fun `399 packets in a second do not wait for it`() {
        val noPause = RtpPacing(now = { clockNs }, pause = { pauses += it })
        repeat(399) {
            if (it % 3 == 0) noPause.frameStart()
            noPause.before()
            noPause.after()
        }
        assertEquals(0, noPause.capHits)
        noPause.before()
        assertEquals("the 400th is still allowed", 0, noPause.capHits)
    }

    @Test
    fun `a packet after the second has ended starts a new count`() {
        val slow = RtpPacing(now = { clockNs }, pause = { pauses += it })
        repeat(400) {
            if (it % 3 == 0) slow.frameStart()
            slow.before()
            slow.after()
        }
        clockNs += 1_200_000_000L
        pauses.clear()
        slow.before()
        assertEquals("a new second: no wait for the cap", 0, slow.capHits)
        assertTrue(pauses.isEmpty())
    }

    @Test
    fun `the wait for the second counts only what is left of it`() {
        val slow = RtpPacing(now = { clockNs }, pause = { pauses += it })
        repeat(400) {
            if (it % 3 == 0) slow.frameStart()
            slow.before()
            slow.after()
        }
        clockNs += 300_000_000L
        slow.before()
        assertEquals(700_000_000L, pauses.last())
    }

    // The output of the encoder, with and without the experiment.

    private val config = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0xC0.toByte(), 0x29, 0x11, 0, 0, 0, 1, 0x68, 0x22)
    private fun keyframe(size: Int) = byteArrayOf(0, 0, 0, 1, 0x65) + ByteArray(size) { 7 }

    private fun run(withPacing: RtpPacing?): List<ByteArray> {
        val sent = mutableListOf<ByteArray>()
        val encoder = Any()
        val out = RtpOutput(pacing = withPacing) { sent += it }.also { it.switchTo(encoder) }
        out.onEncoded(encoder, config, isKey = false, ptsMs = 0)
        out.onEncoded(encoder, keyframe(8_000), isKey = true, ptsMs = 0) // six packets of 1380
        return sent
    }

    @Test
    fun `off, the keyframe leaves in a burst, and on it leaves with the same packets and one wait after the fourth`() {
        val burst = run(null)
        val spaced = run(RtpPacing(now = { clockNs }, pause = { pauses += it }))
        assertTrue("the keyframe is several packets: ${burst.size}", burst.size >= 6)
        // The header carries a random source id and a random first sequence number of each stream: the sizes and what they carry are compared.
        assertEquals("the packets are the same, only their timing differs", burst.map { it.size to it.drop(12) }, spaced.map { it.size to it.drop(12) })
        assertEquals("a wait after the fourth packet of the frame", listOf(2_000_000L), pauses)
    }

    @Test
    fun `the wait is on the calling thread, between two sends, with nothing queued`() {
        val threads = mutableSetOf<Thread>()
        val encoder = Any()
        val p = RtpPacing(now = { clockNs }, pause = { threads += Thread.currentThread() })
        val out = RtpOutput(pacing = p) { threads += Thread.currentThread() }.also { it.switchTo(encoder) }
        out.onEncoded(encoder, config, isKey = false, ptsMs = 0)
        out.onEncoded(encoder, keyframe(8_000), isKey = true, ptsMs = 0)
        assertEquals(setOf(Thread.currentThread()), threads)
    }

    @Test
    fun `the switch off gives no pacing at all, on gives one`() {
        assertEquals(null, RtpPacing.whenOn(false))
        assertTrue(RtpPacing.whenOn(true) != null)
    }

    @Test
    fun `the numbers are 4 packets, 2 ms and 400 a second`() {
        assertEquals(4, RtpPacing.PACKETS_PER_PAUSE)
        assertEquals(2_000_000L, RtpPacing.PAUSE_NANOS)
        assertEquals(400, RtpPacing.MAX_PER_SECOND)
    }

    @Test
    fun `each frame of the encoder counts its packets from the first, so frames of three packets never wait`() {
        val encoder = Any()
        val p = RtpPacing(now = { clockNs }, pause = { pauses += it })
        val out = RtpOutput(pacing = p) { }.also { it.switchTo(encoder) }
        out.onEncoded(encoder, config, isKey = false, ptsMs = 0)
        out.onEncoded(encoder, byteArrayOf(0, 0, 0, 1, 0x65, 1), isKey = true, ptsMs = 0) // one packet
        repeat(3) { out.onEncoded(encoder, byteArrayOf(0, 0, 0, 1, 0x41) + ByteArray(3_000) { 5 }, isKey = false, ptsMs = 62L * (it + 1)) } // three packets each
        assertEquals("1 + 3 + 3 + 3 packets, never four in one frame", emptyList<Long>(), pauses)
    }
}

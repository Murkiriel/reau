package com.reau.app.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The way out of the encoder: gate, NAL processing, RTP. Fed with hand-made encoder buffers, so what a real
 * encoder happens to do (answer a keyframe request at once, always open with a keyframe) cannot hide a hole.
 *
 * Written after the code it tests: the logic was first inside VideoLoop, where six mutations of it survived the
 * emulator tests for exactly that reason.
 */
class RtpOutputTest {
    private val packets = mutableListOf<ByteArray>()
    private val encoder = Any()
    private val output = RtpOutput { packets += it }.also { it.switchTo(encoder) }

    private val config = bytes(0, 0, 0, 1, 0x67, 0x42, 0xC0, 0x29, 0x11, 0, 0, 0, 1, 0x68, 0x22)
    private val idr = bytes(0, 0, 0, 1, 0x65, 0x33, 0x34)
    private fun pFrame(marker: Int) = bytes(0, 0, 0, 1, 0x41, marker)

    /** First payload byte of each packet: the NAL header. */
    private fun sentNalHeaders() = packets.map { it[12].toInt() and 0xFF }

    private fun timestamp(packet: ByteArray) = (0..3).fold(0L) { acc, i -> (acc shl 8) or (packet[4 + i].toLong() and 0xFF) }

    @Test
    fun `nothing reaches the dash before a keyframe, and the keyframe goes with its parameter sets in one unit`() {
        output.onEncoded(encoder, pFrame(1), isKey = false, ptsMs = 0)
        output.onEncoded(encoder, config, isKey = false, ptsMs = 0)
        output.onEncoded(encoder, pFrame(2), isKey = false, ptsMs = 62)
        assertEquals("frames before the keyframe are held back, the parameter sets are only kept", 0, packets.size)

        output.onEncoded(encoder, idr, isKey = true, ptsMs = 124)
        assertEquals(1, packets.size)
        val unit = packets.single().drop(12).map { it.toInt() and 0xFF }
        // SPS (constraint byte rewritten to 00 for the dash) + start code + PPS + start code + IDR.
        assertEquals(listOf(0x67, 0x42, 0x00, 0x29, 0x11, 0, 0, 0, 1, 0x68, 0x22, 0, 0, 0, 1, 0x65, 0x33, 0x34), unit)

        output.onEncoded(encoder, pFrame(3), isKey = false, ptsMs = 186)
        assertEquals(listOf(0x67, 0x41), sentNalHeaders())
        assertEquals(2, output.heldBack)
    }

    @Test
    fun `holding the output drops every frame until the next keyframe`() {
        output.onEncoded(encoder, config, isKey = false, ptsMs = 0)
        output.onEncoded(encoder, idr, isKey = true, ptsMs = 0)
        output.onEncoded(encoder, pFrame(1), isKey = false, ptsMs = 62)
        assertEquals(2, packets.size)

        output.holdUntilKeyframe()
        output.onEncoded(encoder, pFrame(2), isKey = false, ptsMs = 124)
        output.onEncoded(encoder, pFrame(3), isKey = false, ptsMs = 186)
        assertEquals("held back", 2, packets.size)
        output.onEncoded(encoder, idr, isKey = true, ptsMs = 248)
        output.onEncoded(encoder, pFrame(4), isKey = false, ptsMs = 310)
        assertEquals(listOf(0x67, 0x41, 0x67, 0x41), sentNalHeaders())
        assertEquals(2, output.heldBack)
    }

    @Test
    fun `after a switch only the new encoder is heard, from its first keyframe on`() {
        output.onEncoded(encoder, config, isKey = false, ptsMs = 0)
        output.onEncoded(encoder, idr, isKey = true, ptsMs = 0)
        val fresh = Any()
        output.switchTo(fresh)

        output.onEncoded(encoder, pFrame(1), isKey = false, ptsMs = 62) // the old encoder's last frame, still in flight
        output.onEncoded(encoder, idr, isKey = true, ptsMs = 124) // even a keyframe of the old one
        output.onEncoded(fresh, pFrame(2), isKey = false, ptsMs = 186) // the new one, before its keyframe
        assertEquals("nothing of these may go out", 1, packets.size)

        output.onEncoded(fresh, config, isKey = false, ptsMs = 186)
        output.onEncoded(fresh, idr, isKey = true, ptsMs = 248)
        output.onEncoded(fresh, pFrame(3), isKey = false, ptsMs = 310)
        assertEquals(listOf(0x67, 0x67, 0x41), sentNalHeaders())
    }

    @Test
    fun `with no encoder switched in nothing goes out`() {
        val idle = RtpOutput { packets += it }
        idle.onEncoded(encoder, config, isKey = false, ptsMs = 0)
        idle.onEncoded(encoder, idr, isKey = true, ptsMs = 0)
        assertEquals(0, packets.size)
    }

    @Test
    fun `every packet carries the time its frame was stamped with, on the 90 kHz clock`() {
        output.onEncoded(encoder, config, isKey = false, ptsMs = 0)
        output.onEncoded(encoder, idr, isKey = true, ptsMs = 1_000)
        output.onEncoded(encoder, pFrame(1), isKey = false, ptsMs = 1_062)
        // A frame too large for one packet: all its fragments share one time.
        output.onEncoded(encoder, bytes(0, 0, 0, 1, 0x41) + ByteArray(3_000) { 0x55 }, isKey = false, ptsMs = 1_124)

        val times = packets.map { timestamp(it) }
        assertTrue("the large frame must have been fragmented, got ${packets.size} packets", packets.size >= 5)
        assertEquals(62L * 90, (times[1] - times[0]) and 0xFFFFFFFFL)
        assertEquals(62L * 90, (times[2] - times[1]) and 0xFFFFFFFFL)
        assertEquals("fragments of one frame", setOf(times[2]), times.drop(2).toSet())
    }

    @Test
    fun `frames and bytes are counted as they go out, parameter sets and held frames are not`() {
        output.onEncoded(encoder, pFrame(1), isKey = false, ptsMs = 0) // held back
        output.onEncoded(encoder, config, isKey = false, ptsMs = 0) // not a frame
        output.onEncoded(encoder, idr, isKey = true, ptsMs = 0)
        output.onEncoded(encoder, pFrame(2), isKey = false, ptsMs = 62)
        assertEquals(
            RtpOutput.Counts(frames = 2, bytes = (idr.size + pFrame(2).size).toLong(), keyframes = 1, keyBytesMax = idr.size, keyPacketsMax = 1),
            output.takeCounts(),
        )
        assertEquals("the count starts again", RtpOutput.Counts(0, 0), output.takeCounts())
    }

    // ── the tap (the recording of what is sent) ────────────────────────────────────────────────────────────

    @Test
    fun `the tap gets exactly the units that went to the dash, each with its frame's time`() {
        val tapped = mutableListOf<Triple<List<Int>, Boolean, Long>>()
        val tap = { nal: ByteArray, isKey: Boolean, ptsMs: Long ->
            tapped += Triple(nal.map { it.toInt() and 0xFF }, isKey, ptsMs)
            Unit
        }
        val tappedOutput = RtpOutput(tap) { packets += it }.also { it.switchTo(encoder) }

        tappedOutput.onEncoded(encoder, pFrame(1), isKey = false, ptsMs = 0) // held back: not sent, not tapped
        tappedOutput.onEncoded(encoder, config, isKey = false, ptsMs = 0) // kept for the keyframe: not a unit of its own
        tappedOutput.onEncoded(encoder, idr, isKey = true, ptsMs = 124)
        tappedOutput.onEncoded(encoder, pFrame(2), isKey = false, ptsMs = 186)

        assertEquals(
            listOf(
                Triple(listOf(0x67, 0x42, 0x00, 0x29, 0x11, 0, 0, 0, 1, 0x68, 0x22, 0, 0, 0, 1, 0x65, 0x33, 0x34), true, 124L),
                Triple(listOf(0x41, 2), false, 186L),
            ),
            tapped,
        )
        assertEquals(2, packets.size)
    }

    @Test
    fun `a unit whose send failed is not tapped`() {
        val tapped = mutableListOf<Long>()
        val failing = RtpOutput(tap = { _, _, ptsMs -> tapped += ptsMs }) { error("the dash socket is gone") }.also { it.switchTo(encoder) }
        failing.onEncoded(encoder, config, isKey = false, ptsMs = 0)
        val failure = runCatching { failing.onEncoded(encoder, idr, isKey = true, ptsMs = 0) }.exceptionOrNull()

        assertTrue("the failure must reach the caller (the encoder's output thread reports it), got $failure", failure is IllegalStateException)
        assertEquals(emptyList<Long>(), tapped)
    }

    @Test
    fun `a keyframe with no parameter sets before it is withheld, and the frames after it wait for the next keyframe`() {
        output.onEncoded(encoder, idr, isKey = true, ptsMs = 0)
        output.onEncoded(encoder, pFrame(1), isKey = false, ptsMs = 62)
        assertEquals(0, packets.size)
        assertEquals(1, output.idrWithheld)
        assertEquals(0, output.takeCounts().frames)

        output.onEncoded(encoder, config, isKey = false, ptsMs = 124)
        output.onEncoded(encoder, idr, isKey = true, ptsMs = 124)
        assertEquals(1, packets.size)
        assertEquals(1, output.idrWithheld)
    }

    // ── the keyframe as a burst: how big, in bytes and in packets ───────────────────────────────────────────

    @Test
    fun `the biggest keyframe of the window is counted in bytes and packets, then the count starts again`() {
        val big = ByteArray(5 + 3_000) { 1 }.also { it[0] = 0; it[1] = 0; it[2] = 0; it[3] = 1; it[4] = 0x65 } // 00 00 00 01 65 + 3000 bytes: three packets
        output.onEncoded(encoder, config, isKey = false, ptsMs = 0)
        output.onEncoded(encoder, big, isKey = true, ptsMs = 0)
        output.onEncoded(encoder, pFrame(1), isKey = false, ptsMs = 62)
        output.onEncoded(encoder, idr, isKey = true, ptsMs = 124)
        val counts = output.takeCounts()
        assertEquals(2, counts.keyframes)
        assertEquals(big.size, counts.keyBytesMax)
        assertEquals("the SPS and PPS ride in the keyframe's own unit: its bundle is 3000 + about 20 bytes, three packets", 3, counts.keyPacketsMax)
        val again = output.takeCounts()
        assertEquals(listOf(0, 0, 0), listOf(again.keyframes, again.keyBytesMax, again.keyPacketsMax))
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}

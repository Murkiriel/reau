package com.reau.app.dash

import com.reau.app.dash.video.RtpPacketizer
import com.reau.app.stream.RtpOutput
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The video the app sends, received as the dash would: the real way out of the encoder ([RtpOutput], with the real NAL processor and
 * packetizer) feeds an [RtpSink], which checks every packet against the rules of the stream and puts the frames back together. The second
 * half proves the sink itself: a stream broken by hand in each way is told.
 */
class RtpSinkTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val sink = RtpSink()
    private val encoder = Any()
    private val sent = mutableListOf<ByteArray>()
    private val output = RtpOutput(tap = { nal, _, _ -> sent += nal }) { sink.onPacket(it) }.also { it.switchTo(encoder) }

    private val startCode = byteArrayOf(0, 0, 0, 1)
    private val config = startCode + bytes(0x67, 0x42, 0x00, 0x29, 0x11) + startCode + bytes(0x68, 0x22)
    private fun idr(size: Int) = startCode + ByteArray(size) { if (it == 0) 0x65 else (it % 251).toByte() }
    private fun pFrame(size: Int, salt: Int) = startCode + ByteArray(size) { if (it == 0) 0x41 else ((it + salt) % 247).toByte() }

    private fun aStream() {
        output.onEncoded(encoder, config, isKey = false, ptsMs = 0)
        output.onEncoded(encoder, idr(9_000), isKey = true, ptsMs = 1_000) // seven packets
        output.onEncoded(encoder, pFrame(300, 1), isKey = false, ptsMs = 1_250)
        output.onEncoded(encoder, pFrame(1_380, 2), isKey = false, ptsMs = 1_500) // exactly one packet
        output.onEncoded(encoder, pFrame(1_381, 3), isKey = false, ptsMs = 1_750) // one byte over: two pieces
        output.onEncoded(encoder, idr(2_000), isKey = true, ptsMs = 2_000)
    }

    @Test
    fun `the stream the app sends breaks no rule, and the frames come back as they went`() {
        aStream()
        assertEquals("faults", emptyList<String>(), sink.faults)
        assertFalse(sink.endedInsideAFrame)
        assertEquals("one frame out for each frame in", 5, sink.frames.size)
        assertEquals(sent.size, sink.frames.size)
        for ((i, nal) in sent.withIndex()) assertArrayEquals("frame $i", startCode + nal, sink.frames[i])
        assertEquals("a keyframe leaves with its parameter sets in front", 0x67, sink.frames[0][4].toInt())
        assertEquals("7 + 1 + 1 + 2 + 2 packets", 13, sink.packets)
    }

    @Test
    fun `each frame has the time it was stamped with, on a 90 kHz clock`() {
        aStream()
        val steps = sink.timestamps.zipWithNext { a, b -> (b - a) and 0xFFFFFFFFL }
        assertEquals("250 ms between frames is 22500 ticks", listOf(22_500L, 22_500L, 22_500L, 22_500L), steps)
    }

    @Test
    fun `the stream is written as a file a player opens, byte for byte what was sent`() {
        aStream()
        val file = folder.newFile("stream.h264")
        val written = sink.writeTo(file)
        val expected = sent.fold(ByteArray(0)) { all, nal -> all + startCode + nal }
        assertEquals(expected.size, written)
        assertArrayEquals(expected, file.readBytes())
    }

    // ── the sink itself: each way of breaking the stream is told ─────────────────────────────────

    private fun packetsOf(vararg nals: ByteArray, atMs: Long = 0): MutableList<ByteArray> {
        val out = mutableListOf<ByteArray>()
        val packetizer = RtpPacketizer { out += it }
        for (nal in nals) packetizer.packetize(nal, endOfAU = true, wallClockMs = atMs)
        return out
    }

    private fun faultsOf(packets: List<ByteArray>): List<String> = RtpSink().also { s -> packets.forEach(s::onPacket) }.faults

    private val big = ByteArray(4_000) { if (it == 0) 0x65 else 7 }
    private val small = bytes(0x41, 1, 2, 3)

    @Test
    fun `a clean stream of the packetizer alone has no fault`() {
        val out = mutableListOf<ByteArray>()
        val packetizer = RtpPacketizer { out += it }
        packetizer.packetize(big, endOfAU = true, wallClockMs = 0)
        packetizer.packetize(small, endOfAU = true, wallClockMs = 250)
        assertEquals(emptyList<String>(), faultsOf(out))
    }

    @Test
    fun `a packet that went missing is told by the sequence`() {
        val packets = packetsOf(big, small)
        packets.removeAt(1)
        assertTrue(faultsOf(packets).any { "sequence" in it })
    }

    @Test
    fun `a frame that ends without its marker is told when the next one comes`() {
        val packetizer = mutableListOf<ByteArray>()
        val p = RtpPacketizer { packetizer += it }
        p.packetize(small, endOfAU = false, wallClockMs = 0)
        p.packetize(small, endOfAU = true, wallClockMs = 250)
        assertTrue(faultsOf(packetizer).any { "inside the frame of timestamp" in it })
    }

    @Test
    fun `a marker in the middle of a NAL in pieces is told`() {
        val packets = packetsOf(big)
        packets[1][1] = (packets[1][1].toInt() or 0x80).toByte()
        assertTrue(faultsOf(packets).any { "the marker on a piece that is not the last" in it })
    }

    @Test
    fun `pieces without their first, of another type, or both first and last are told`() {
        assertTrue(faultsOf(packetsOf(big).drop(1)).any { "whose first piece did not come" in it })
        val otherType = packetsOf(big).also { it[1][13] = ((it[1][13].toInt() and 0xE0) or 1).toByte() }
        assertTrue(faultsOf(otherType).any { "inside a NAL of type 5" in it })
        val both = packetsOf(big).also { it[0][13] = (it[0][13].toInt() or 0x40).toByte() }
        assertTrue(faultsOf(both).any { "both the first and the last" in it })
    }

    @Test
    fun `an aggregation packet, an oversized one, another source and a second frame with the same time are told`() {
        val stap = packetsOf(small).also { it[0][12] = 24 }
        assertTrue(faultsOf(stap).any { "aggregation" in it })
        val fat = packetsOf(small).map { it + ByteArray(1_400) }
        assertTrue(faultsOf(fat).any { "over 1380" in it })
        val twoSources = packetsOf(small) + packetsOf(small, atMs = 250) // each packetizer draws its own source id
        assertTrue(faultsOf(twoSources).any { "source id changed" in it || "sequence" in it })
        val sameTime = packetsOf(small, small)
        assertTrue(faultsOf(sameTime).any { "timestamp of the one before" in it })
    }

    @Test
    fun `a stream cut inside a frame says so`() {
        val s = RtpSink()
        packetsOf(big).dropLast(1).forEach(s::onPacket)
        assertTrue(s.endedInsideAFrame)
        assertEquals(0, s.frames.size)
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}

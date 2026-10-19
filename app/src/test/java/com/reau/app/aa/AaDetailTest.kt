package com.reau.app.aa

import com.reau.app.data.DetailedLog
import com.reau.app.data.DetailedLogger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** With the detailed log on, each Android Auto message is summed up in it. */
class AaDetailTest {
    private val written = mutableListOf<String>()
    private val original = DetailedLog.logger

    @Before
    fun setUp() {
        DetailedLog.logger = DetailedLogger(maxPerSecond = 100, nowMs = { 0L }) { _, line -> written += line }
    }

    @After
    fun tearDown() {
        DetailedLog.logger = original
    }

    private fun handler(onMessage: () -> Unit) = object : AapMessageHandler {
        override fun handle(message: AapMessage) = onMessage()
    }

    private fun message() = AapMessage(Channel.ID_NAV, 0x0B.toByte(), 0x8007, 6, 10, ByteArray(16))

    /** A video message of [size] bytes with [flags]: 0x0B whole, 0x09 first piece, 0x08 middle, 0x0A last. */
    private fun video(flags: Int, type: Int, size: Int) = AapMessage(Channel.ID_VID, flags.toByte(), type, 6, size, ByteArray(16))

    @Test
    fun `on, each message goes in as channel, type, flags and size, then on to the session`() {
        DetailedLog.logger.enabled = true
        var handed = 0
        AaStatsHandler(AaStats { 0L }, handler { handed++ }).handle(message())
        assertEquals(listOf("aa rx ch=10 type=32775 flags=0x0B size=10"), written)
        assertEquals(1, handed)
    }

    @Test
    fun `off, nothing`() {
        AaStatsHandler(AaStats { 0L }, handler { }).handle(message())
        assertEquals(emptyList<String>(), written)
    }

    /** The video's messages were 47% of a ride's log, and a piece in the middle has no type of its own. */
    @Test
    fun `the video channel is one line a second, with its pieces counted and the types of the pieces that have one`() {
        DetailedLog.logger.enabled = true
        var now = 10_000L
        val handler = AaStatsHandler(AaStats { 0L }, handler { }, nowMs = { now })
        handler.handle(video(0x09, 0, 16_000)) // a first piece
        handler.handle(video(0x08, 42_357, 15_000)) // a middle piece: its "type" is picture bytes
        handler.handle(video(0x0A, 65_534, 9_000)) // the last piece
        handler.handle(video(0x0B, 0, 800)) // a whole message
        handler.handle(video(0x0B, 1, 30)) // a whole one of another type (the codec's setup)
        assertEquals("nothing is said before the second is over", emptyList<String>(), written)
        now = 11_200L
        handler.handle(video(0x0B, 0, 700))
        assertEquals(
            listOf("aa rx video 5 msgs (3 first or whole, 2 continuation) bytes=40830 max=16000 types=0:2,1:1"),
            written,
        )
    }

    @Test
    fun `a message on any other channel is still a line of its own, in the middle of the video's second`() {
        DetailedLog.logger.enabled = true
        val handler = AaStatsHandler(AaStats { 0L }, handler { }, nowMs = { 5_000L })
        handler.handle(video(0x0B, 0, 800))
        handler.handle(message())
        handler.handle(AapMessage(Channel.ID_CTR, 0x0B.toByte(), 12, 6, 4, ByteArray(16)))
        assertEquals(listOf("aa rx ch=10 type=32775 flags=0x0B size=10", "aa rx ch=0 type=12 flags=0x0B size=4"), written)
    }

    @Test
    fun `with the switch off the video counts nothing, and the second that was open is not said when it comes back on`() {
        var now = 1_000L
        val handler = AaStatsHandler(AaStats { 0L }, handler { }, nowMs = { now })
        DetailedLog.logger.enabled = true
        handler.handle(video(0x0B, 0, 800))
        DetailedLog.logger.enabled = false
        now = 2_500L
        handler.handle(video(0x0B, 0, 800))
        DetailedLog.logger.enabled = true
        now = 9_000L
        handler.handle(video(0x0B, 0, 800))
        now = 10_100L
        handler.handle(video(0x0B, 0, 800))
        assertEquals(listOf("aa rx video 1 msgs (1 first or whole, 0 continuation) bytes=800 max=800 types=0:1"), written)
    }
}

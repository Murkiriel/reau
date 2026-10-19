package com.reau.app.aa

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The debug option that keeps what Android Auto sent in a session, message by message as the handler saw them
 * (already decrypted): channel, flags, the payload with its type, and when. A test can then feed a handler with it
 * again.
 */
class SessionRecorderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val log = mutableListOf<String>()
    private var nowMs = 1_000_000L

    private fun recorder(maxBytes: Long = 1_000_000, dir: File = File(tmp.root, "sessions"), name: String = "reau_20261002_150000_000") =
        SessionRecorder(dir, name, maxBytes, { log += it }, { nowMs })

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    /** A message as the read loop hands it over: the payload begins with the two type bytes. */
    private fun message(channel: Int, flags: Int, payload: ByteArray, bufferBytes: Int = payload.size): AapMessage {
        val type = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
        return AapMessage(channel, flags.toByte(), type, 2, payload.size, payload.copyOf(bufferBytes))
    }

    // ── what is written ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `every message comes back as it was, in order`() {
        val r = recorder()
        r.write(message(Channel.ID_CTR, 0x0B, bytes(0x00, 0x05, 0x0A, 0x04)))
        r.write(message(Channel.ID_VID, 0x09, bytes(0x00, 0x00, 1, 2, 3, 4, 5, 6, 7, 8, 0, 0, 0, 1, 0x65)))
        r.write(message(Channel.ID_VID, 0x0A, bytes(0x99, 0x98, 0x97)))
        r.write(message(Channel.ID_VID, 0x0B, bytes(0x80, 0x04))) // a message with no body
        r.close()

        val read = SessionRecorder.read(r.file)
        assertEquals(listOf(Channel.ID_CTR, Channel.ID_VID, Channel.ID_VID, Channel.ID_VID), read.messages.map { it.channel })
        assertEquals(listOf(0x0B, 0x09, 0x0A, 0x0B), read.messages.map { it.flags })
        assertEquals(listOf(5, 0, 0x9998, 0x8004), read.messages.map { it.type })
        assertArrayEquals(bytes(0x00, 0x05, 0x0A, 0x04), read.messages[0].payload)
        assertArrayEquals(bytes(0x00, 0x00, 1, 2, 3, 4, 5, 6, 7, 8, 0, 0, 0, 1, 0x65), read.messages[1].payload)
        assertArrayEquals(bytes(0x99, 0x98, 0x97), read.messages[2].payload)
        assertArrayEquals(bytes(0x80, 0x04), read.messages[3].payload)
        assertFalse(read.truncated)
        assertEquals(4, r.messages)
    }

    @Test
    fun `only the bytes of the message are kept, not the rest of the buffer it came in`() {
        val r = recorder()
        r.write(message(Channel.ID_CTR, 0x0B, bytes(0x00, 0x0B, 0x08, 0x2A), bufferBytes = 64))
        r.close()
        assertArrayEquals(bytes(0x00, 0x0B, 0x08, 0x2A), SessionRecorder.read(r.file).messages.single().payload)
    }

    @Test
    fun `each message carries the time since the first one, and the file the clock time it began at`() {
        val r = recorder()
        r.write(message(Channel.ID_CTR, 0x0B, bytes(0, 5)))
        nowMs += 40
        r.write(message(Channel.ID_VID, 0x0B, bytes(0, 0)))
        nowMs += 3_000
        r.write(message(Channel.ID_VID, 0x0B, bytes(0, 0)))
        r.close()

        val read = SessionRecorder.read(r.file)
        assertEquals(listOf(0L, 40L, 3_040L), read.messages.map { it.timeMs })
        assertEquals(1_000_000L, read.startedAtMs)
    }

    @Test
    fun `the file is named after the recording and nothing exists before the first message`() {
        val r = recorder()
        assertEquals(File(tmp.root, "sessions/reau_20261002_150000_000.aasession"), r.file)
        assertFalse("a session that brought nothing leaves no file", r.file.exists())
        r.close()
        assertFalse(r.file.exists())
        assertTrue("and nothing is said of a recording that never began: $log", log.isEmpty())
    }

    @Test
    fun `names sort by the moment the recording began, to the millisecond`() {
        val first = SessionRecorder.nameFor(1_790_000_000_000)
        val second = SessionRecorder.nameFor(1_790_000_000_007)
        val later = SessionRecorder.nameFor(1_790_000_061_000)
        assertTrue("got $first", Regex("""reau_\d{8}_\d{6}_\d{3}""").matches(first))
        assertTrue("$first < $second < $later", first < second && second < later)
    }

    // ── where it stops ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the recording ends by itself before the file would pass the limit`() {
        val r = recorder(maxBytes = 200)
        val payload = ByteArray(50) { 0x11 }
        repeat(3) { r.write(message(Channel.ID_VID, 0x0B, payload)) }
        assertFalse(r.isDone)
        r.write(message(Channel.ID_VID, 0x0B, payload)) // would take the file past 200 bytes
        assertTrue(r.isDone)
        r.write(message(Channel.ID_CTR, 0x0B, bytes(0, 5))) // ignored

        assertTrue("the file is ${r.file.length()} bytes", r.file.length() <= 200)
        val read = SessionRecorder.read(r.file)
        assertEquals(3, read.messages.size)
        assertFalse("the file ends on a whole message", read.truncated)
        assertEquals(3, r.messages)
        assertTrue("got $log", log.single().let { "session recording full" in it && "3 messages" in it && r.file.path in it })
    }

    @Test
    fun `the limit counts every byte of the file, the header too, and may be filled to the last one`() {
        val payload = ByteArray(50) { 0x11 } // 60 bytes in the file, after 13 of header: three messages are 193
        val exact = recorder(maxBytes = 193, name = "exact")
        val oneShort = recorder(maxBytes = 192, name = "one_short")
        repeat(3) {
            exact.write(message(Channel.ID_VID, 0x0B, payload))
            oneShort.write(message(Channel.ID_VID, 0x0B, payload))
        }
        assertFalse("three messages fit exactly", exact.isDone)
        assertTrue(oneShort.isDone)
        exact.close()

        assertEquals(193L, exact.file.length())
        assertEquals(3, SessionRecorder.read(exact.file).messages.size)
        assertEquals(133L, oneShort.file.length())
        assertEquals(2, SessionRecorder.read(oneShort.file).messages.size)
    }

    @Test
    fun `close ends the recording and says how much was kept`() {
        val r = recorder()
        r.write(message(Channel.ID_CTR, 0x0B, bytes(0, 5, 1)))
        r.write(message(Channel.ID_CTR, 0x0B, bytes(0, 5, 2)))
        r.close()
        r.write(message(Channel.ID_CTR, 0x0B, bytes(0, 5, 3))) // after the end: ignored
        r.close()

        assertTrue(r.isDone)
        assertEquals(2, SessionRecorder.read(r.file).messages.size)
        assertTrue("one line, got $log", log.single().let { "session recording closed" in it && "2 messages" in it })
    }

    @Test
    fun `a zero limit means the option is off`() {
        val r = recorder(maxBytes = 0)
        r.write(message(Channel.ID_CTR, 0x0B, bytes(0, 5)))
        assertFalse(r.file.exists())
        assertTrue(log.isEmpty())
    }

    @Test
    fun `only the last three recordings are kept`() {
        val dir = File(tmp.root, "sessions")
        for (second in 1..4) {
            recorder(dir = dir, name = "reau_20261002_15000${second}_000").apply {
                write(message(Channel.ID_CTR, 0x0B, bytes(0, 5)))
                close()
            }
        }
        File(dir, "notes.txt").writeText("not a recording")

        recorder(dir = dir, name = "reau_20261002_150005_000").apply {
            write(message(Channel.ID_CTR, 0x0B, bytes(0, 5)))
            close()
        }

        assertEquals(
            listOf("notes.txt", "reau_20261002_150003_000.aasession", "reau_20261002_150004_000.aasession", "reau_20261002_150005_000.aasession"),
            dir.list()!!.sorted(),
        )
    }

    @Test
    fun `a folder that cannot be written costs the recording and one line in the log, never the session`() {
        val notAFolder = tmp.newFile("in_the_way")
        val r = recorder(dir = notAFolder)

        r.write(message(Channel.ID_CTR, 0x0B, bytes(0, 5))) // must not throw
        r.write(message(Channel.ID_CTR, 0x0B, bytes(0, 5)))

        assertTrue(r.isDone)
        assertTrue("one line, got $log", log.single().startsWith("session recording failed"))
    }

    // ── reading ────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a file cut in the middle of a message gives the whole messages before it and says it was cut`() {
        val r = recorder()
        r.write(message(Channel.ID_CTR, 0x0B, bytes(0, 5, 1)))
        r.write(message(Channel.ID_VID, 0x0B, ByteArray(40) { 0x22 }))
        r.close()
        val whole = r.file.readBytes()

        for (cut in listOf(1, 20, 39, 45)) { // inside the second message's payload, and inside its header
            val file = tmp.newFile("cut_$cut.aasession").apply { writeBytes(whole.copyOf(whole.size - cut)) }
            val read = SessionRecorder.read(file)
            assertEquals("cut $cut bytes short", 1, read.messages.size)
            assertArrayEquals(bytes(0, 5, 1), read.messages.single().payload)
            assertTrue("cut $cut bytes short", read.truncated)
        }
    }

    @Test
    fun `a file that is not a recording is refused`() {
        val other = tmp.newFile("other.aasession").apply { writeBytes(ByteArray(64) { 0x41 }) }
        val empty = tmp.newFile("empty.aasession")
        assertThrows(IllegalArgumentException::class.java) { SessionRecorder.read(other) }
        assertThrows(IllegalArgumentException::class.java) { SessionRecorder.read(empty) }

        // Right in everything but one letter of the four it begins with.
        val r = recorder()
        r.write(message(Channel.ID_CTR, 0x0B, bytes(0, 5)))
        r.close()
        val almost = tmp.newFile("almost.aasession").apply { writeBytes(r.file.readBytes().also { it[3] = 0x42 }) }
        assertThrows(IllegalArgumentException::class.java) { SessionRecorder.read(almost) }
    }

    @Test
    fun `a recording of another version of the format is refused`() {
        val r = recorder()
        r.write(message(Channel.ID_CTR, 0x0B, bytes(0, 5)))
        r.close()
        val bytes = r.file.readBytes()
        assertEquals("the file begins with REAA and the version 1", listOf(0x52, 0x45, 0x41, 0x41, 1), bytes.take(5).map { it.toInt() })

        bytes[4] = 2
        val newer = tmp.newFile("newer.aasession").apply { writeBytes(bytes) }
        assertThrows(IllegalArgumentException::class.java) { SessionRecorder.read(newer) }
    }

    @Test
    fun `a length that makes no sense ends the reading as a cut file instead of asking for the memory`() {
        val r = recorder()
        r.write(message(Channel.ID_CTR, 0x0B, bytes(0, 5, 1)))
        r.write(message(Channel.ID_CTR, 0x0B, bytes(0, 5, 2)))
        r.close()
        val bytes = r.file.readBytes()
        // The second record's length field: the last 4 bytes before its 3-byte payload.
        val at = bytes.size - 3 - 4
        for (first in listOf(0x7F, 0xFF)) { // two thousand million bytes, and a negative length
            bytes[at] = first.toByte()
            val broken = tmp.newFile("broken_$first.aasession").apply { writeBytes(bytes) }

            val read = SessionRecorder.read(broken)
            assertEquals(1, read.messages.size)
            assertTrue(read.truncated)
        }
    }

    // ── in front of the handler ────────────────────────────────────────────────────────────────────────────

    private class Seen : AapMessageHandler {
        val messages = mutableListOf<AapMessage>()
        var failWith: Exception? = null
        override fun handle(message: AapMessage) {
            messages += message
            failWith?.let { throw AapMessageHandler.HandleException(it) }
        }
    }

    @Test
    fun `the recording handler keeps the message and passes the same one on`() {
        val r = recorder()
        val seen = Seen()
        val handler = RecordingHandler(r, seen)
        val ping = message(Channel.ID_CTR, 0x0B, bytes(0, 0x0B, 8, 1))

        handler.handle(ping)
        r.close()

        assertSame(ping, seen.messages.single())
        assertArrayEquals(bytes(0, 0x0B, 8, 1), SessionRecorder.read(r.file).messages.single().payload)
    }

    @Test
    fun `a recording that is over or failed never keeps a message from the handler`() {
        val seen = Seen()
        val full = RecordingHandler(recorder(maxBytes = 20), seen)
        val failed = RecordingHandler(recorder(dir = tmp.newFile("in_the_way")), seen)

        repeat(2) { full.handle(message(Channel.ID_VID, 0x0B, ByteArray(30))) }
        repeat(2) { failed.handle(message(Channel.ID_VID, 0x0B, ByteArray(30))) }

        assertEquals(4, seen.messages.size)
    }

    @Test
    fun `a message the handler fails on is in the recording, and the failure goes on up`() {
        val r = recorder()
        val seen = Seen().apply { failWith = IllegalStateException("bad message") }

        assertThrows(AapMessageHandler.HandleException::class.java) {
            RecordingHandler(r, seen).handle(message(Channel.ID_CTR, 0x0B, bytes(0, 5)))
        }
        r.close()
        assertEquals("the message that broke the handler is the one worth having", 1, SessionRecorder.read(r.file).messages.size)
    }
}

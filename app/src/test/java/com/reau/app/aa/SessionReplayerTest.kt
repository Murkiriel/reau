package com.reau.app.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The test tool that replays a recording: what a handler gets from it is what the handler of the session got. */
class SessionReplayerTest {
    @get:Rule val tmp = TemporaryFolder()

    private var nowMs = 5_000L

    private class Seen : AapMessageHandler {
        val messages = mutableListOf<AapMessage>()
        val atMs = mutableListOf<Long>()
        override fun handle(message: AapMessage) {
            messages += message
            atMs += System.nanoTime() / 1_000_000
        }
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    /** As AapMessageIncoming builds it, in a buffer larger than the message. */
    private fun incoming(channel: Int, flags: Int, payload: ByteArray): AapMessage {
        val header = AapMessageIncoming.EncryptedHeader().apply {
            bytes(channel, flags, payload.size shr 8, payload.size and 0xFF).copyInto(buf)
            decode()
        }
        val passThrough = object : AapSsl {
            override fun decrypt(start: Int, length: Int, buffer: ByteArray) = ByteArrayWithLimit(payload.copyOf(payload.size + 32), payload.size)
            override fun encrypt(offset: Int, length: Int, buffer: ByteArray): ByteArrayWithLimit? = null
            override fun postHandshakeReset() = Unit
            override fun performHandshake(connection: AccessoryConnection) = true
            override fun release() = Unit
        }
        return checkNotNull(AapMessageIncoming.decrypt(header, 0, payload, passThrough))
    }

    private fun record(vararg gapsAndMessages: Pair<Long, AapMessage>): Pair<SessionRecording, List<AapMessage>> {
        val live = Seen()
        val recorder = SessionRecorder(tmp.root, "reau_20261002_150000_000", 1_000_000, { }, { nowMs })
        val handler = RecordingHandler(recorder, live)
        for ((gapMs, message) in gapsAndMessages) {
            nowMs += gapMs
            handler.handle(message)
        }
        recorder.close()
        return SessionRecorder.read(recorder.file) to live.messages
    }

    @Test
    fun `the handler of the replay gets the messages the handler of the session got`() {
        val (recording, live) = record(
            0L to incoming(Channel.ID_CTR, 0x0B, bytes(0x00, 0x05, 0x0A, 0x04, 0x46, 0x61, 0x6B, 0x65)),
            0L to incoming(Channel.ID_VID, 0x0F, bytes(0x00, 0x07, 0x08, 0x00, 0x10, 0x02)),
            0L to incoming(Channel.ID_VID, 0x09, bytes(0x00, 0x00, 1, 2, 3, 4, 5, 6, 7, 8, 0, 0, 0, 1, 0x65, 0x11)),
            0L to incoming(Channel.ID_VID, 0x0A, bytes(0x22, 0x33, 0x44)),
            0L to incoming(Channel.ID_VID, 0x0B, bytes(0x80, 0x04)),
        )
        val replayed = Seen()

        val count = SessionReplayer.replay(recording, replayed)

        assertEquals(5, count)
        // AapMessage.equals: channel, flags, type, where the body starts, size and every byte of it.
        assertEquals(live, replayed.messages)
        assertEquals("the buffer of a replayed message is the message and nothing more", listOf(8, 6, 16, 3, 2), replayed.messages.map { it.data.size })
    }

    @Test
    fun `an empty recording replays nothing`() {
        val replayed = Seen()
        assertEquals(0, SessionReplayer.replay(SessionRecording(0, emptyList(), false), replayed))
        assertTrue(replayed.messages.isEmpty())
    }

    @Test
    fun `a paced replay waits between messages as the recording says, and never longer than two seconds`() {
        val ping = incoming(Channel.ID_CTR, 0x0B, bytes(0x00, 0x0B, 0x08, 0x01))
        val (recording, _) = record(0L to ping, 300L to ping, 300L to ping, 60_000L to ping)
        val replayed = Seen()

        SessionReplayer.replay(recording, replayed, paced = true)

        val gaps = replayed.atMs.zipWithNext { a, b -> b - a }
        assertTrue("the 300 ms of the recording, got $gaps", gaps[0] in 290..550)
        assertTrue("300 ms after the one before, not 600 after the first, got $gaps", gaps[1] in 290..550)
        assertTrue("a minute of silence is cut to two seconds, got $gaps", gaps[2] in 1_990..2_600)
    }

    @Test
    fun `an unpaced replay does not wait`() {
        val ping = incoming(Channel.ID_CTR, 0x0B, bytes(0x00, 0x0B, 0x08, 0x01))
        val (recording, _) = record(0L to ping, 1_500L to ping, 1_500L to ping)
        val replayed = Seen()

        val start = System.nanoTime()
        SessionReplayer.replay(recording, replayed)

        assertEquals(3, replayed.messages.size)
        assertTrue((System.nanoTime() - start) / 1_000_000 < 1_000)
    }
}

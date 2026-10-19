package com.reau.app.aa

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The microphone for Gemini (two taps up call Gemini, the voice from the Bluetooth
 * headset or else the phone). Android Auto asks to open it when the assistant starts listening and to close it when
 * it is done; in between the rider's voice goes up the microphone channel as raw 16 kHz mono PCM.
 */
class AaMicrophoneTest {
    /** A voice that hands out the chunks it is given, then waits; closed, it says so. */
    private class FakeVoice(private val opens: Boolean = true) : VoiceSource {
        val chunks = LinkedBlockingQueue<ShortArray>()
        @Volatile var opened = 0
        @Volatile var closed = 0

        override fun open(): Boolean = opens.also { if (it) opened++ }

        override fun read(into: ShortArray): Int {
            while (closed == 0) {
                val chunk = chunks.poll(10, TimeUnit.MILLISECONDS) ?: continue
                chunk.copyInto(into)
                return chunk.size
            }
            return -1
        }

        override fun close() {
            closed++
        }
    }

    private val sent = CopyOnWriteArrayList<AapMessage>()

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 2_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertTrue(what, condition())
    }

    @Test
    fun `a data message is the channel, the media flags, the length, type 0, the time and little-endian PCM`() {
        val message = AaMicrophone.dataMessage(shortArrayOf(1, -2, 0x1234), count = 3, timestampUs = 0x0102030405060708L)
        val bytes = message.data.copyOf(message.size)
        assertEquals(Channel.ID_MIC, message.channel)
        assertEquals(0, message.type)
        assertEquals(4 + 2 + 8 + 6, message.size)
        assertArrayEquals(
            byteArrayOf(
                7, 0x0b, 0, 16, // channel, flags, length of what follows the header (type + time + PCM)
                0, 0, // MEDIA_MESSAGE_DATA
                1, 2, 3, 4, 5, 6, 7, 8, // the time, big-endian microseconds
                1, 0, -2, -1, 0x34, 0x12, // the samples, little-endian
            ),
            bytes,
        )
    }

    @Test
    fun `asked to open, it opens the voice and sends what it hears until asked to close`() {
        val voice = FakeVoice()
        val mic = AaMicrophone(voice, { sent += it }, clockUs = { 42L })
        assertTrue("the answer is success", mic.request(open = true))
        assertEquals(1, voice.opened)
        voice.chunks.put(shortArrayOf(5, 6))
        voice.chunks.put(shortArrayOf(7))
        await("both chunks went to Android Auto, got ${sent.size}") { sent.size == 2 }
        val first = sent[0].data.copyOf(sent[0].size)
        assertEquals(42L, ByteBuffer.wrap(first, 6, 8).order(ByteOrder.BIG_ENDIAN).long)
        assertEquals(5.toShort(), ByteBuffer.wrap(first, 14, 2).order(ByteOrder.LITTLE_ENDIAN).short)

        assertTrue("closing is success", mic.request(open = false))
        await("the voice is closed") { voice.closed == 1 }
        voice.chunks.put(shortArrayOf(9))
        Thread.sleep(50)
        assertEquals("nothing after it was closed", 2, sent.size)
    }

    @Test
    fun `a voice that cannot open is answered as a failure and sends nothing`() {
        val voice = FakeVoice(opens = false)
        val mic = AaMicrophone(voice, { sent += it })
        assertFalse(mic.request(open = true))
        Thread.sleep(50)
        assertTrue(sent.isEmpty())
        assertTrue("closing what never opened is still fine", mic.request(open = false))
        assertEquals("and closes nothing", 0, voice.closed)
    }

    @Test
    fun `a second open while open does not open twice, and the session ending closes it`() {
        val voice = FakeVoice()
        val mic = AaMicrophone(voice, { sent += it })
        assertTrue(mic.request(open = true))
        assertTrue(mic.request(open = true))
        assertEquals(1, voice.opened)
        mic.stop("session ended")
        await("the voice is closed once") { voice.closed == 1 }
        mic.stop("again")
        Thread.sleep(50)
        assertEquals(1, voice.closed)
        // and it can open again in the next listening
        assertTrue(mic.request(open = true))
        assertEquals(2, voice.opened)
        mic.stop("end")
    }

    /**
     * The session's end stops the microphone first, in the `finally` of the receiver's
     * session, on the thread that AaReceiver.stop() interrupts. An interruption during the wait for the pump used to
     * leave stop() by exception, which skipped the release of the decoder, the video thread, the statistics executor,
     * the recorder and the socket after it.
     */
    @Test
    fun `an interruption while the stop waits still closes the voice and keeps the interrupt for what follows`() {
        val voice = FakeVoice()
        val mic = AaMicrophone(voice, { sent += it })
        assertTrue(mic.request(open = true))
        assertEquals(1, voice.opened)

        Thread.currentThread().interrupt()
        try {
            mic.stop("session ended")
        } finally {
            assertTrue("the interrupt is kept for the callers after it", Thread.interrupted()) // and cleared here
        }

        await("the voice is closed although the wait was cut short") { voice.closed == 1 }
        // The microphone is usable again afterwards, as after any stop.
        assertTrue(mic.request(open = true))
        assertEquals(2, voice.opened)
        mic.stop("end")
        await("closed again") { voice.closed == 2 }
    }

    @Test
    fun `a voice that breaks while listening is closed and the next request opens it again`() {
        val voice = object : VoiceSource {
            var opened = 0
            var closed = 0
            override fun open(): Boolean = true.also { opened++ }
            override fun read(into: ShortArray): Int = -3 // AudioRecord's ERROR_INVALID_OPERATION, say
            override fun close() {
                closed++
            }
        }
        val mic = AaMicrophone(voice, { sent += it })
        assertTrue(mic.request(open = true))
        await("the broken voice is closed") { voice.closed == 1 }
        assertTrue(mic.request(open = true))
        assertEquals(2, voice.opened)
        mic.stop("end")
    }
}

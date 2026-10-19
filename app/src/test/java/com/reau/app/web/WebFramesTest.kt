package com.reau.app.web

import com.reau.app.aa.AaAudioFeed
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the browser's binary messages say they are, with the sound: one byte in front, 0 video, 1 media, 2 the guide's
 * voice.
 */
class WebFramesTest {
    private val data = byteArrayOf(9, 8, 7)

    @Test
    fun `video goes with a 0 in front`() {
        assertArrayEquals(byteArrayOf(0, 9, 8, 7), WebFrames.video(data))
    }

    @Test
    fun `the media sound goes with a 1 and the guide's voice with a 2`() {
        assertArrayEquals(byteArrayOf(1, 9, 8, 7), WebFrames.audio(AaAudioFeed.Pcm(AaAudioFeed.Stream.MEDIA, data)))
        assertArrayEquals(byteArrayOf(2, 9, 8, 7), WebFrames.audio(AaAudioFeed.Pcm(AaAudioFeed.Stream.GUIDANCE, data)))
    }

    @Test
    fun `the assistant's speech is the same 16 kHz mono as the guide's voice, so it goes with a 2 too`() {
        assertArrayEquals(byteArrayOf(2, 9, 8, 7), WebFrames.audio(AaAudioFeed.Pcm(AaAudioFeed.Stream.SPEECH, data)))
    }

    @Test
    fun `the tags are the ones the page reads`() {
        val page = java.io.File("src/main/assets/web/index.html").readText()
        assertEquals(0, WebFrames.VIDEO.toInt())
        assertEquals(1, WebFrames.MEDIA.toInt())
        assertEquals(2, WebFrames.GUIDANCE.toInt())
        assert(page.contains("tag === 1") && page.contains("tag === 2") && page.contains("tag === 0"))
    }

    @Test
    fun `the data is copied, not shared`() {
        val framed = WebFrames.video(data)
        data[0] = 0
        assertEquals(9, framed[1].toInt())
    }
}

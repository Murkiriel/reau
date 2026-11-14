package com.reau.app.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A frame the decoder's full queue dropped counts as lost and asks for a keyframe, at most once a second like a broken
 * frame does.
 */
class AapVideoBehindTest {
    private var nowMs = 10_000L
    private var keyframeRequests = 0
    private val stats = AaStats { nowMs }
    private val video = AapVideo(decode = { _, _, _ -> }, onFrameCorrupted = { keyframeRequests++ }, nowMs = { nowMs }, stats = stats)

    @Test
    fun `a dropped frame is lost and asks for a keyframe, at most once a second`() {
        video.decoderBehind()
        nowMs += 300
        video.decoderBehind()
        assertEquals("one request inside the second", 1, keyframeRequests)
        nowMs += 1_000
        video.decoderBehind()
        assertEquals("another after it", 2, keyframeRequests)
        val line = stats.windowLine()
        assertTrue("every drop is lost: $line", line.contains(" lost=3 "))
    }

    @Test
    fun `a frame lost anywhere is counted and asks for a keyframe, at most once a second`() {
        video.frameLost("the decoder failed on a picture")
        assertEquals(1, keyframeRequests)
        assertTrue(stats.windowLine().contains(" lost=1 "))
        nowMs += 300
        video.frameLost("no input buffer free in the decoder")
        assertEquals("the second inside the second is not asked again", 1, keyframeRequests)
        nowMs += 1_000
        video.frameLost("3 picture(s) dropped before the decoder started")
        assertEquals(2, keyframeRequests)
        assertTrue(stats.windowLine().contains(" lost=3 "))
    }
}

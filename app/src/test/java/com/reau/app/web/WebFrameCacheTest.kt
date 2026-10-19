package com.reau.app.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a viewer who comes in the middle receives first: the parameter sets, the last key frame and what followed it.
 */
class WebFrameCacheTest {
    private fun frame(id: Int) = byteArrayOf(0, 0, 0, 1, id.toByte())

    private fun ids(frames: List<ByteArray>) = frames.map { it.last().toInt() }

    @Test
    fun `nothing is cached at first`() {
        assertTrue(WebFrameCache().snapshot().isEmpty())
    }

    @Test
    fun `the parameter sets alone are sent while no key frame has come`() {
        val cache = WebFrameCache()
        cache.setConfig(frame(100))
        cache.addFrame(frame(1), key = false)
        assertEquals(listOf(100), ids(cache.snapshot()))
    }

    @Test
    fun `a joiner gets the parameters, the last key frame and what came after it, in order`() {
        val cache = WebFrameCache()
        cache.setConfig(frame(100))
        cache.addFrame(frame(1), key = true)
        cache.addFrame(frame(2), key = false)
        cache.addFrame(frame(3), key = false)
        assertEquals(listOf(100, 1, 2, 3), ids(cache.snapshot()))
    }

    @Test
    fun `a new key frame starts a new group and the old frames are gone`() {
        val cache = WebFrameCache()
        cache.setConfig(frame(100))
        cache.addFrame(frame(1), key = true)
        cache.addFrame(frame(2), key = false)
        cache.addFrame(frame(3), key = true)
        cache.addFrame(frame(4), key = false)
        assertEquals(listOf(100, 3, 4), ids(cache.snapshot()))
    }

    @Test
    fun `a group that grows past the limit without a key frame is dropped, never half kept`() {
        val cache = WebFrameCache(maxFrames = 3)
        cache.setConfig(frame(100))
        cache.addFrame(frame(1), key = true)
        cache.addFrame(frame(2), key = false)
        cache.addFrame(frame(3), key = false)
        assertEquals(listOf(100, 1, 2, 3), ids(cache.snapshot()))
        cache.addFrame(frame(4), key = false)
        assertEquals("only the parameters: a joiner waits for the next key frame", listOf(100), ids(cache.snapshot()))
        cache.addFrame(frame(5), key = false)
        assertEquals(listOf(100), ids(cache.snapshot()))
        cache.addFrame(frame(6), key = true)
        assertEquals(listOf(100, 6), ids(cache.snapshot()))
    }

    @Test
    fun `a newer parameter set replaces the older one`() {
        val cache = WebFrameCache()
        cache.setConfig(frame(100))
        cache.setConfig(frame(101))
        assertEquals(listOf(101), ids(cache.snapshot()))
    }

    @Test
    fun `clear forgets everything`() {
        val cache = WebFrameCache()
        cache.setConfig(frame(100))
        cache.addFrame(frame(1), key = true)
        cache.clear()
        assertTrue(cache.snapshot().isEmpty())
    }

    @Test
    fun `the snapshot is a copy, so later frames do not change what was handed out`() {
        val cache = WebFrameCache()
        cache.addFrame(frame(1), key = true)
        val first = cache.snapshot()
        cache.addFrame(frame(2), key = false)
        assertEquals(listOf(1), ids(first))
    }
}

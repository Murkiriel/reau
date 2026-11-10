package com.reau.app.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameBusyTest {
    private var clock = 10_000L
    private val busy = FrameBusy { clock }

    @Test
    fun betweenFramesItIsZero() {
        assertEquals(0L, busy.busyMs())
        busy.track { clock += 5 }
        clock += 1_000
        assertEquals("a frame that ended leaves nothing behind", 0L, busy.busyMs())
    }

    @Test
    fun insideAFrameItIsTheTimeSinceTheFrameBegan() {
        var seen = -1L
        busy.track {
            clock += 1_250
            seen = busy.busyMs()
        }
        assertEquals(1_250L, seen)
    }

    @Test
    fun aFrameThatThrowsStillClearsIt() {
        runCatching { busy.track { clock += 300; error("the swap failed") } }
        assertEquals(0L, busy.busyMs())
    }

    @Test
    fun trackGivesBackWhatTheFrameGives() {
        assertEquals(42, busy.track { 42 })
    }

    @Test
    fun aFrameBeginningAtTheClockZeroIsNotMistakenForBetweenFrames() {
        // 0 means "between frames": the real clock (uptime) is never 0 while the compositor runs.
        clock = 1
        var seen = -1L
        busy.track { clock += 7; seen = busy.busyMs() }
        assertTrue("seen $seen", seen == 7L)
    }
}

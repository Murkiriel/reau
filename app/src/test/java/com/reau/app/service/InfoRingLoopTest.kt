package com.reau.app.service

import com.reau.app.weather.RingFrame
import com.reau.app.weather.RingIcon
import com.reau.app.weather.RingKind
import com.reau.app.weather.RingPage
import com.reau.app.weather.RingTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * One look of the info ring's loop ([InfoRingFeed.Loop]): the ring off the video gives the GPS back (the listener is a process-wide one and
 * the ring's loop dies with its scope without stopping it, 26/10/2026), and a picture that failed is told once. It used to be read from the
 * source of LiveSides.
 */
class InfoRingLoopTest {
    private val told = mutableListOf<String>()
    private var gpsStops = 0
    private val loop = InfoRingFeed.Loop(ticksPerLook = 3, stopGps = { gpsStops++ }, warn = { told += it })

    private fun frame(level: Int) = RingFrame(RingPage(RingKind.SKY, level, RingIcon.CLEAR, RingTone.NONE, emptyList()), stale = false, night = false)

    @Test
    fun `with the ring off the video the GPS is given back at every look and no frame is shown`() {
        repeat(4) { assertNull(loop.step(on = false) { frame(1) }) }
        assertEquals(4, gpsStops)
    }

    @Test
    fun `with the ring on the GPS is left to whoever asked for it`() {
        repeat(7) { loop.step(on = true) { frame(1) } }
        assertEquals(0, gpsStops)
    }

    @Test
    fun `a picture is made at the first look and then every few, the frame in between is the last one`() {
        var made = 0
        val shown = (1..7).map { loop.step(on = true) { made++; frame(made) } }
        assertEquals("looks 1, 4 and 7", 3, made)
        assertSame(shown[0], shown[2])
        assertEquals(frame(2), shown[3])
        assertEquals(frame(3), shown[6])
    }

    @Test
    fun `taking the ring off drops the frame and starts the count again, so the next picture is made at once`() {
        var made = 0
        loop.step(on = true) { made++; frame(1) }
        loop.step(on = true) { made++; frame(1) }
        assertNull(loop.step(on = false) { frame(1) })
        assertEquals(frame(9), loop.step(on = true) { made++; frame(9) })
        assertEquals("the picture of the first look after the ring came back", 2, made)
    }

    @Test
    fun `the first failure is told with its kind and the next ones are not, and each leaves no ring until the next try`() {
        val boom = { throw IllegalArgumentException("Cannot round NaN value.") }
        assertNull(loop.step(on = true, boom))
        assertEquals(listOf("the info ring could not be made (IllegalArgumentException): it is off the video until it can; told once"), told)
        repeat(8) { assertNull(loop.step(on = true, boom)) }
        assertEquals("told once", 1, told.size)
    }

    @Test
    fun `a picture that works after a failure is shown, and a later failure is not told again`() {
        assertNull(loop.step(on = true) { error("x") })
        repeat(2) { assertNull(loop.step(on = true) { frame(1) }) }
        assertEquals(frame(5), loop.step(on = true) { frame(5) })
        repeat(2) { loop.step(on = true) { frame(1) } }
        assertNull(loop.step(on = true) { error("y") })
        assertEquals("one line in all", 1, told.size)
    }
}

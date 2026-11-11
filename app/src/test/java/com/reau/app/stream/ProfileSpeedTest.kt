package com.reau.app.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The speed the speed profile reads: the route's estimate as always, or with the switch the GPS fix's when a route runs and the GPS speaks. */
class ProfileSpeedTest {
    private val said = mutableListOf<String>()
    private val speed = ProfileSpeed { said += it }

    @Test
    fun `with the switch off it is the route's estimate whatever the GPS says, and the log says nothing`() {
        assertEquals(40.0, speed.kmh(on = false, routeActive = true, gpsMps = 30f, routeKmh = 40.0)!!, 0.0)
        assertNull(speed.kmh(on = false, routeActive = true, gpsMps = 30f, routeKmh = null))
        assertEquals(emptyList<String>(), said)
    }

    @Test
    fun `with it on and a GPS fix on a route the profile reads the GPS in km per hour`() {
        assertEquals(72.0, speed.kmh(on = true, routeActive = true, gpsMps = 20f, routeKmh = 60.0)!!, 0.001)
        assertEquals("a stopped bike is 0, not the route's guess", 0.0, speed.kmh(on = true, routeActive = true, gpsMps = 0f, routeKmh = 30.0)!!, 0.0)
    }

    @Test
    fun `the GPS fills the gaps of the route's estimate while the route runs`() {
        assertEquals(36.0, speed.kmh(on = true, routeActive = true, gpsMps = 10f, routeKmh = null)!!, 0.001)
    }

    @Test
    fun `with no route the GPS gives no verdict, as the route's estimate gives none`() {
        assertNull(speed.kmh(on = true, routeActive = false, gpsMps = 20f, routeKmh = null))
        assertEquals(50.0, speed.kmh(on = true, routeActive = false, gpsMps = 20f, routeKmh = 50.0)!!, 0.0)
    }

    @Test
    fun `without a GPS speed it is the route's estimate, and a bad number is no speed`() {
        assertEquals(55.0, speed.kmh(on = true, routeActive = true, gpsMps = null, routeKmh = 55.0)!!, 0.0)
        assertEquals(55.0, speed.kmh(on = true, routeActive = true, gpsMps = Float.NaN, routeKmh = 55.0)!!, 0.0)
        assertEquals(55.0, speed.kmh(on = true, routeActive = true, gpsMps = -1f, routeKmh = 55.0)!!, 0.0)
        assertEquals(55.0, speed.kmh(on = true, routeActive = true, gpsMps = Float.POSITIVE_INFINITY, routeKmh = 55.0)!!, 0.0)
    }

    @Test
    fun `each change of source is said once`() {
        speed.kmh(true, true, null, 50.0)
        assertEquals("starting on the route's estimate is not news", emptyList<String>(), said)
        speed.kmh(true, true, 20f, 50.0)
        speed.kmh(true, true, 21f, 50.0)
        assertEquals(1, said.size)
        assertTrue(said[0], said[0].startsWith("speed profile: reads the GPS speed (72 km/h; the route says 50 km/h)"))
        speed.kmh(true, true, null, 50.0)
        assertEquals(2, said.size)
        assertTrue(said[1], said[1].startsWith("speed profile: back to the route's estimate"))
    }

    @Test
    fun `turning the switch off forgets the source`() {
        speed.kmh(true, true, 20f, 50.0)
        speed.kmh(false, true, 20f, 50.0)
        speed.kmh(true, true, 20f, 50.0)
        assertEquals("read again, said again", 2, said.size)
    }

    @Test
    fun `the sides read the GPS only with the switch and the rider's location on, for a route that runs`() {
        val live = java.io.File("src/main/java/com/reau/app/service/LiveSides.kt").readText()
        assertTrue(live.contains("val gpsSpeed = s?.experimentGpsSpeed == true && s.useLocation"))
        assertTrue(live.contains("on = gpsSpeed, routeActive = g.active && g.distanceToManeuverMeters >= 0, gpsMps = if (gpsSpeed) GpsWatch.read()?.speedMps else null,"))
        assertTrue(live.contains("routeKmh = navSpeed.kmhAt(android.os.SystemClock.elapsedRealtime()),"))
        // and never switches the GPS on: only reads what the info ring already listens to
        assertTrue(!live.substringAfter("val gpsSpeed =").substringBefore("mode = s?.navProfileMode").contains("GpsWatch.want"))
    }
}

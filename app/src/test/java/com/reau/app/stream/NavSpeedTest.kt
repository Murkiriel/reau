package com.reau.app.stream

import com.reau.app.aa.NavGuidance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The bike's speed for the ride log with no location permission: how fast the
 * distance to the next maneuver, which Android Auto sends while a route runs, goes down.
 */
class NavSpeedTest {
    private val speed = NavSpeed()

    private fun at(ms: Long, meters: Int, active: Boolean = true) =
        speed.onGuidance(NavGuidance(active = active, distanceToManeuverMeters = meters), ms)

    @Test
    fun `the distance going down 22 m a second is 80 km per hour`() {
        for (s in 0..10) at(s * 1_000L, 2_000 - s * 22)
        assertEquals(79.2, speed.kmhAt(10_000)!!, 0.5)
    }

    @Test
    fun `only the last 10 s count`() {
        for (s in 0..10) at(s * 1_000L, 5_000 - s * 11) // 40 km/h
        for (s in 11..30) at(s * 1_000L, 5_000 - 110 - (s - 10) * 28) // then 100 km/h
        assertEquals(100.8, speed.kmhAt(30_000)!!, 0.5)
    }

    @Test
    fun `a new maneuver starts the count again, and the speed before it stands until 6 s of the new one`() {
        for (s in 0..7) at(s * 1_000L, 300 - s * 25)
        assertEquals(90.0, speed.kmhAt(7_000)!!, 0.5)
        at(8_000, 1_800) // the turn was taken: the next one is further
        assertEquals("the last good speed stands: the bike did not change speed with the turn", 90.0, speed.kmhAt(8_000)!!, 0.5)
        for (s in 9..13) at(s * 1_000L, 1_800 - (s - 8) * 11) // 40 km/h
        assertEquals("under 6 s of the new count: still the old speed", 90.0, speed.kmhAt(13_000)!!, 0.5)
        at(14_000, 1_800 - 6 * 11)
        assertEquals(39.6, speed.kmhAt(14_000)!!, 0.5)
    }

    /** Waze says the distance in steps of 100 m, so two readings 2 s apart say 100 m in 2 s: about 180 km/h at 100. */
    @Test
    fun `two readings a few seconds apart from a distance in steps of 100 m are no speed`() {
        at(0, 8_700)
        at(2_100, 8_600) // 100 m in 2.1 s would be 171 km/h; the bike did 100
        assertNull(speed.kmhAt(2_100))
        at(5_700, 8_500)
        assertNull("5.7 s is still short", speed.kmhAt(5_700))
        at(9_300, 8_400)
        assertEquals("300 m in 9.3 s: 116, within the 100 m of the steps, and not 171 or 180", 116.0, speed.kmhAt(9_300)!!, 1.0)
    }

    @Test
    fun `the last good speed stands while the new maneuver has under 6 s, and a count too short to be good is not kept`() {
        for (s in 0..7) at(s * 1_000L, 900 - s * 20) // 72 km/h
        at(8_000, 2_000) // new maneuver
        at(10_900, 1_980)
        assertEquals(72.0, speed.kmhAt(10_900)!!, 0.5)
        val other = NavSpeed()
        for (s in 0..3) other.onGuidance(NavGuidance(active = true, distanceToManeuverMeters = 900 - s * 20), s * 1_000L)
        other.onGuidance(NavGuidance(active = true, distanceToManeuverMeters = 2_000), 4_000) // a turn after only 3 s of readings
        assertNull("3 s of readings never was a speed", other.kmhAt(4_000))
    }

    @Test
    fun `the same distance said again by another message is no new reading`() {
        at(0, 1_000)
        at(2_000, 950)
        at(3_000, 925)
        at(3_500, 925) // a turn detail carrying the distance it had
        at(6_000, 850)
        assertEquals(90.0, speed.kmhAt(6_000)!!, 0.5)
    }

    @Test
    fun `the number on the dash stands for 3 s when there is none, then reads 0`() {
        for (s in 0..7) at(s * 1_000L, 900 - s * 20) // 72 km/h
        assertEquals(72.0, speed.displayKmhAt(7_000)!!, 0.5)
        // no new distance after 7 s: the last speed stands 3 s from that reading, then 0
        assertEquals(72.0, speed.displayKmhAt(9_900)!!, 0.5)
        assertEquals(72.0, speed.displayKmhAt(10_000)!!, 0.5)
        assertEquals(0.0, speed.displayKmhAt(10_100)!!, 0.0)
        assertEquals("the log's own wait is 8 s", 72.0, speed.kmhAt(10_100)!!, 0.5)
        assertEquals(0.0, speed.displayKmhAt(22_000)!!, 0.0)
    }

    @Test
    fun `a new reading takes over from the held one`() {
        for (s in 0..7) at(s * 1_000L, 900 - s * 20)
        speed.displayKmhAt(7_000)
        at(8_000, 900 - 8 * 20)
        at(9_000, 740 - 40) // 40 m in 1 s: faster
        at(10_000, 700 - 40)
        assertEquals(true, speed.displayKmhAt(10_000)!! > 72.0)
    }

    @Test
    fun `after a new maneuver the display holds, and with no route it says nothing`() {
        for (s in 0..7) at(s * 1_000L, 300 - s * 25)
        speed.displayKmhAt(7_000)
        at(8_000, 1_800) // new maneuver
        assertEquals(90.0, speed.displayKmhAt(8_000)!!, 0.5)
        at(12_000, 1_000, active = false)
        assertNull(speed.displayKmhAt(12_000))
        assertNull("no route: nothing to say, not a 0", speed.displayKmhAt(30_000))
        assertNull("and no speed of the old route either", speed.kmhAt(12_500))
    }

    @Test
    fun `a route with no speed yet reads 0 on the dash and unknown in the log`() {
        at(0, 2_000)
        assertNull(speed.kmhAt(500))
        assertEquals(0.0, speed.displayKmhAt(500)!!, 0.0)
    }

    @Test
    fun `no reading for 8 s, no route or no distance is unknown`() {
        for (s in 0..7) at(s * 1_000L, 900 - s * 20)
        assertEquals("8 s is still fresh", 72.0, speed.kmhAt(15_000)!!, 0.5)
        assertNull("over 8 s is stale", speed.kmhAt(15_001))
        at(16_000, 700, active = false)
        assertNull(speed.kmhAt(16_000))
        at(17_000, -1)
        assertNull(speed.kmhAt(17_000))
    }

    /** Waze's 100 m steps come 3 to 6 s apart at 60 to 120 km/h. */
    @Test
    fun `the speed stands between two 100 m steps that come 5 s apart`() {
        for (i in 0..3) at(i * 3_600L, 6_400 - i * 100) // 100 km/h: 100 m every 3.6 s
        assertEquals(100.0, speed.kmhAt(10_800)!!, 1.0)
        assertEquals("5 s after the last step, before the next one: still the speed, not unknown", 100.0, speed.kmhAt(15_800)!!, 1.0)
    }

    /** Waze says whole km from 10 km up: 12 km -> 11 km in 6 s would read as 600 km/h. */
    @Test
    fun `a fall of a whole km at once is no speed but a new count`() {
        at(0, 12_000)
        at(6_000, 11_000)
        assertNull("not 600 km/h", speed.kmhAt(6_000))
        at(80_000, 10_900)
        at(86_000, 10_800)
        at(91_500, 10_700)
        at(97_000, 10_600)
        at(103_000, 10_500)
        assertEquals("100 m in 6 s", 60.0, speed.kmhAt(103_000)!!, 1.0)
    }

    @Test
    fun `the speed before a jump stands, as after a turn`() {
        for (s in 0..7) at(s * 1_000L, 5_000 - s * 25) // 90 km/h
        at(8_000, 4_000) // a new route, 825 m nearer at once
        assertEquals(90.0, speed.kmhAt(8_000)!!, 0.5)
    }
}

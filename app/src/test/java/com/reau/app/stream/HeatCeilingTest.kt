package com.reau.app.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The battery's heat as a second source of the frame ceiling's status: with the switch off Android's status goes through untouched. */
class HeatCeilingTest {
    private val said = mutableListOf<String>()
    private val heat = HeatCeiling { said += it }

    @Test
    fun `with the switch off the status of Android goes through whatever the battery says, and nothing is said`() {
        for (system in listOf(-1, 0, 1, 2, 3, 4)) for (temp in listOf(null, 30f, 44f, 49f)) {
            assertEquals("system $system temp $temp", system, heat.status(system, on = false, tempC = temp))
        }
        assertEquals(emptyList<String>(), said)
    }

    @Test
    fun `with it off the ceiling is exactly what it is today at every battery temperature`() {
        for (temp in listOf(30f, 44f, 47f, 50f)) {
            val status = heat.status(system = 0, on = false, tempC = temp)
            assertEquals(4, ThermalCeiling.capFor(status, 4))
        }
    }

    @Test
    fun `with it on a cool battery changes nothing`() {
        assertEquals(0, heat.status(0, on = true, tempC = 43.9f))
        assertEquals(-1, heat.status(-1, on = true, tempC = 30f))
        assertEquals(emptyList<String>(), said)
    }

    @Test
    fun `44 degrees is the moderate step, 46 the severe and 48 the critical, and the ceiling falls with them`() {
        assertEquals(2, heat.status(0, on = true, tempC = 44f))
        assertEquals(3, heat.status(0, on = true, tempC = 46f))
        assertEquals(4, heat.status(0, on = true, tempC = 48.5f))
        // the frame ceiling of the profile 8 fps: 3/4, 5/8, 1/2 of it, on even rates
        assertEquals(6, ThermalCeiling.capFor(2, 8))
        assertEquals(4, ThermalCeiling.capFor(4, 8))
    }

    @Test
    fun `a step is left only three degrees under where it was entered`() {
        heat.status(0, on = true, tempC = 44.2f)
        assertEquals("43.9 is not yet cool", 2, heat.status(0, on = true, tempC = 43.9f))
        assertEquals(2, heat.status(0, on = true, tempC = 41.0f))
        assertEquals("under 41 it is left", 0, heat.status(0, on = true, tempC = 40.9f))
    }

    @Test
    fun `from the severe step the battery comes down one step at a time`() {
        heat.status(0, on = true, tempC = 46.5f)
        assertEquals(3, heat.status(0, on = true, tempC = 43.1f))
        assertEquals("under 43 the severe step is left for the moderate", 2, heat.status(0, on = true, tempC = 42.9f))
        assertEquals(0, heat.status(0, on = true, tempC = 40.0f))
    }

    @Test
    fun `a battery that says nothing keeps the step, and Android's higher status wins`() {
        heat.status(0, on = true, tempC = 45f)
        assertEquals(2, heat.status(0, on = true, tempC = null))
        assertEquals("severe from Android over the battery's moderate", 3, heat.status(3, on = true, tempC = 45f))
        assertEquals("moderate from the battery over Android's light", 2, heat.status(1, on = true, tempC = 45f))
    }

    @Test
    fun `switching it off forgets the step, and on again starts cool`() {
        heat.status(0, on = true, tempC = 47f)
        assertEquals(0, heat.status(0, on = false, tempC = 47f))
        assertEquals("on again with no reading: cool", 0, heat.status(0, on = true, tempC = null))
    }

    @Test
    fun `each change of step is said once, with the temperature and no place`() {
        heat.status(0, on = true, tempC = 44.3f)
        heat.status(0, on = true, tempC = 44.9f)
        heat.status(0, on = true, tempC = 46.0f)
        heat.status(0, on = true, tempC = 40.0f)
        assertEquals(3, said.size)
        assertTrue(said[0], said[0].startsWith("battery at 44.3 C") && "moderate" in said[0])
        assertTrue(said[1], "severe" in said[1])
        assertTrue(said[2], said[2].startsWith("battery back to 40.0 C"))
    }

    @Test
    fun `the sides give the video loop the status through it, with the rider's switch and the battery the device look reads`() {
        val live = java.io.File("src/main/java/com/reau/app/service/LiveSides.kt").readText()
        assertTrue(live.contains("heat.status(setup.thermalStatus(), tuned?.experimentHeatCeiling == true, com.reau.app.data.PhoneNow.batteryTempNow())"))
        assertTrue(live.contains("VideoLoop(compositor, dash, videoSettings, thermalStatus, link = linkSample"))
        assertTrue(live.contains("com.reau.app.stream.HeatCeiling { RideDiagnostics.log(\"heat\", it) }"))
    }
}

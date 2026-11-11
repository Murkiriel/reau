package com.reau.app.service

import com.reau.app.data.SolarClock
import com.reau.app.settings.MapTheme
import com.reau.app.settings.REauSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Calendar
import java.util.TimeZone

/**
 * The projection's memory of day and night, behind its experimental switch. Off (the default) every look starts as day, as the projection
 * always did (a new clock at each look): the band between day and night is day. On, the band keeps the answer of the look before.
 */
class DayNightMemoryTest {
    private val saoPaulo = DayNight.Place(-23.55, -46.63)
    private val offset = -3 * 3_600_000L

    private fun utcMs(hour: Int, minute: Int = 0): Long =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(2026, 9, 3, hour, minute, 0)
        }.timeInMillis

    private val midnight = utcMs(3)
    private val noon = utcMs(15)

    /** A minute before sunrise with the sun in the band, between 1 and 5 degrees under the horizon. */
    private val dawn = (0 until 300).map { utcMs(7) + it * 60_000L }.first { SolarClock.elevationDeg(saoPaulo.lat, saoPaulo.lng, it) in -5.0..-1.0 }

    private fun DayNight.Memory.at(remember: Boolean, ms: Long) = isNight(remember, MapTheme.AUTO, ms, saoPaulo, offset)

    @Test
    fun `the switch is off by default`() {
        assertFalse(REauSettings().experimentNightMemory)
    }

    @Test
    fun `off, dawn in the band is day already, whatever the look before said, as it always was`() {
        val memory = DayNight.Memory()
        assertTrue(memory.at(remember = false, ms = midnight))
        assertFalse("the band, after a night: day, about half an hour before sunrise", memory.at(remember = false, ms = dawn))
        // The same answers as a new clock at every look, which is what the projection did.
        for (ms in listOf(midnight, dawn, noon)) {
            val fresh = DayNight.Clock(MapTheme.AUTO, { saoPaulo }, { offset }, { ms }).isNight()
            assertEquals(fresh, DayNight.Memory().at(remember = false, ms = ms))
        }
    }

    @Test
    fun `on, the band keeps the answer of the look before, at dawn as at dusk`() {
        val memory = DayNight.Memory()
        assertTrue(memory.at(remember = true, ms = midnight))
        assertTrue("the band, after a night: still night", memory.at(remember = true, ms = dawn))
        assertFalse("the sun is up: day", memory.at(remember = true, ms = noon))
        assertFalse("the band, after a day: still day", memory.at(remember = true, ms = dawn))
    }

    @Test
    fun `outside the band the memory changes nothing, and the fixed themes ignore it`() {
        for (remember in listOf(true, false)) {
            val memory = DayNight.Memory()
            assertTrue(memory.at(remember, midnight))
            assertFalse(memory.at(remember, noon))
            assertTrue(memory.at(remember, midnight))
            assertFalse(memory.isNight(remember, MapTheme.DAY, midnight, saoPaulo, offset))
            assertTrue(memory.isNight(remember, MapTheme.NIGHT, noon, saoPaulo, offset))
        }
    }

    @Test
    fun `the projection asks one memory, with the rider's switch`() {
        val live = File("src/main/java/com/reau/app/service/LiveSides.kt").readText()
        assertTrue(live.contains("private val nightMemory = DayNight.Memory()"))
        assertTrue(live.contains("nightMemory.isNightNow(settings.experimentNightMemory, settings.mapTheme, SunPlace.saved(context, settings.useLocation))"))
        assertFalse("no new clock at each look any more", live.contains("DayNight.Clock("))
    }
}

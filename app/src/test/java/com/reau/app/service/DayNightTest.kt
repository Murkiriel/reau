package com.reau.app.service

import com.reau.app.data.SolarClock
import com.reau.app.settings.MapTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Day or night for Android Auto's night sensor, which switches Waze's map theme: by the sun
 * where the phone is ([SolarClock]), or always day, or always night, as the rider sets.
 * With no place known, the time zone stands in for it: its offset gives a longitude (15° an hour) on the equator,
 * where day and night are twelve hours each.
 */
class DayNightTest {
    private val saoPaulo = DayNight.Place(-23.55, -46.63)
    private val threeHoursBehind = -3 * 3_600_000L

    private fun utcMs(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    @Test
    fun `by the sun where the phone is`() {
        assertFalse("noon in São Paulo", DayNight.isNight(MapTheme.AUTO, utcMs(2026, 10, 3, 15), saoPaulo, threeHoursBehind, wasNight = true))
        assertTrue("midnight in São Paulo", DayNight.isNight(MapTheme.AUTO, utcMs(2026, 10, 3, 3), saoPaulo, threeHoursBehind, wasNight = false))
    }

    @Test
    fun `with no place, the time zone stands in for it`() {
        assertFalse("noon at UTC-3", DayNight.isNight(MapTheme.AUTO, utcMs(2026, 10, 3, 15), null, threeHoursBehind, wasNight = true))
        assertTrue("midnight at UTC-3", DayNight.isNight(MapTheme.AUTO, utcMs(2026, 10, 3, 3), null, threeHoursBehind, wasNight = false))
        assertFalse("noon at UTC+9 is 03:00 UTC", DayNight.isNight(MapTheme.AUTO, utcMs(2026, 10, 3, 3), null, 9 * 3_600_000L, wasNight = true))
    }

    @Test
    fun `the stand-in place is the zone's longitude on the equator`() {
        assertEquals(DayNight.Place(0.0, -45.0), DayNight.standIn(threeHoursBehind))
        assertEquals(DayNight.Place(0.0, 135.0), DayNight.standIn(9 * 3_600_000L))
        assertEquals(DayNight.Place(0.0, -52.5), DayNight.standIn(-3 * 3_600_000L - 30 * 60_000L))
    }

    @Test
    fun `at dusk it keeps what it was, so the map does not flicker`() {
        // A minute of the evening in São Paulo when the sun is between 0° and 6° below the horizon.
        val dusk = (0 until 240).map { utcMs(2026, 10, 3, 20) + it * 60_000L }
            .first { SolarClock.elevationDeg(saoPaulo.lat, saoPaulo.lng, it) in -5.0..-1.0 }
        assertTrue(DayNight.isNight(MapTheme.AUTO, dusk, saoPaulo, threeHoursBehind, wasNight = true))
        assertFalse(DayNight.isNight(MapTheme.AUTO, dusk, saoPaulo, threeHoursBehind, wasNight = false))
    }

    /** Written after the code, for the mutation run: the known place wins over the time zone's stand-in. */
    @Test
    fun `a known place wins over the time zone`() {
        val standIn = DayNight.standIn(threeHoursBehind)
        // The December solstice: São Paulo's day is about an hour and a half longer than the equator's.
        val evening = (0 until 240).map { utcMs(2026, 12, 21, 20) + it * 60_000L }.first {
            SolarClock.elevationDeg(saoPaulo.lat, saoPaulo.lng, it) > 0.5 && SolarClock.elevationDeg(standIn.lat, standIn.lng, it) < -6.5
        }
        assertFalse("São Paulo still has the sun", DayNight.isNight(MapTheme.AUTO, evening, saoPaulo, threeHoursBehind, wasNight = false))
        assertTrue("the zone's stand-in is past dusk", DayNight.isNight(MapTheme.AUTO, evening, null, threeHoursBehind, wasNight = false))
    }

    @Test
    fun `always day and always night ignore the sun`() {
        for (hour in listOf(3, 15)) {
            assertFalse(DayNight.isNight(MapTheme.DAY, utcMs(2026, 10, 3, hour), saoPaulo, threeHoursBehind, wasNight = true))
            assertTrue(DayNight.isNight(MapTheme.NIGHT, utcMs(2026, 10, 3, hour), saoPaulo, threeHoursBehind, wasNight = false))
        }
    }

    @Test
    fun `a clock remembers what it said, for the dusk rule`() {
        var now = utcMs(2026, 10, 3, 3)
        val clock = DayNight.Clock(MapTheme.AUTO, place = { saoPaulo }, offsetMs = { threeHoursBehind }, nowMs = { now })
        assertTrue("midnight", clock.isNight())
        now = (0 until 240).map { utcMs(2026, 10, 3, 20) + it * 60_000L }
            .first { SolarClock.elevationDeg(saoPaulo.lat, saoPaulo.lng, it) in -5.0..-1.0 }
        assertTrue("dusk after a night: still night", clock.isNight())
        now = utcMs(2026, 10, 3, 15)
        assertFalse("noon", clock.isNight())
        now = (0 until 240).map { utcMs(2026, 10, 3, 20) + it * 60_000L }
            .first { SolarClock.elevationDeg(saoPaulo.lat, saoPaulo.lng, it) in -5.0..-1.0 }
        assertFalse("dusk after a day: still day", clock.isNight())
    }

    @Test
    fun `a clock with no place falls back to the time zone`() {
        val clock = DayNight.Clock(MapTheme.AUTO, place = { null }, offsetMs = { threeHoursBehind }, nowMs = { utcMs(2026, 10, 3, 3) })
        assertTrue(clock.isNight())
    }
}

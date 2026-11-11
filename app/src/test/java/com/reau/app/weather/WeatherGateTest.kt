package com.reau.app.weather

import com.reau.app.service.DayNight
import com.reau.app.weather.WeatherGate.Verdict as Gate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What [WeatherGate] ([WeatherKeeper.refreshIfStale]) decides before it looks for a network: no place means no request (nothing of the rider leaves the
 * phone), a weather kept for this place and young enough means none either, and a caller that respects the backoff waits out the failures
 * ([WeatherBackoffTest] has the waits themselves). It used to be read from the source.
 */
class WeatherGateTest {
    private val place = DayNight.Place(-10.1, -50.6)
    private val elsewhere = DayNight.Place(-23.6, -46.6)
    private val maxAge = WeatherKeeper.REFRESH_MS

    private fun snapshot(monoAtMs: Long) = WeatherKeeper.Snapshot(PointForecast(null, emptyList(), emptyList()), fetchedAtMs = 1L, monoAtMs = monoAtMs)

    private fun gate(
        at: DayNight.Place? = place,
        kept: WeatherKeeper.Snapshot? = null,
        keptPlace: DayNight.Place? = null,
        mono: Long = 10_000_000L,
        respectBackoff: Boolean = false,
        backoff: WeatherBackoff = WeatherBackoff(),
    ) = WeatherGate(maxAge, respectBackoff, backoff).verdict(at, kept, keptPlace, nowMs = 5L, monoNowMs = mono)

    @Test
    fun `with no place nothing is asked, however fresh the weather kept`() {
        assertEquals(Gate.NO_PLACE, gate(at = null))
        assertEquals(Gate.NO_PLACE, gate(at = null, kept = snapshot(9_999_000L), keptPlace = place))
        assertEquals(Gate.NO_PLACE, gate(at = null, respectBackoff = true))
    }

    @Test
    fun `a weather kept for this place and younger than the age is enough`() {
        assertEquals(Gate.FRESH_ENOUGH, gate(kept = snapshot(10_000_000L - maxAge + 1), keptPlace = place))
    }

    @Test
    fun `a weather as old as the age, or of another place, or none, is fetched again`() {
        assertEquals(Gate.GO, gate(kept = snapshot(10_000_000L - maxAge), keptPlace = place))
        assertEquals(Gate.GO, gate(kept = snapshot(9_999_000L), keptPlace = elsewhere))
        assertEquals(Gate.GO, gate(kept = snapshot(9_999_000L), keptPlace = null))
        assertEquals(Gate.GO, gate(kept = null))
    }

    @Test
    fun `a caller that respects the backoff waits out the failures, one that does not goes on`() {
        val backoff = WeatherBackoff()
        backoff.failed(10_000_000L)
        assertEquals(Gate.BACKING_OFF, gate(respectBackoff = true, backoff = backoff, mono = 10_000_000L + WeatherBackoff.STEPS_MS[0] - 1))
        assertEquals(Gate.GO, gate(respectBackoff = true, backoff = backoff, mono = 10_000_000L + WeatherBackoff.STEPS_MS[0]))
        assertEquals("opening the app asks at once", Gate.GO, gate(respectBackoff = false, backoff = backoff, mono = 10_000_000L))
    }

    @Test
    fun `a fresh weather needs no request even while the backoff waits`() {
        val backoff = WeatherBackoff().also { it.failed(10_000_000L) }
        assertEquals(Gate.FRESH_ENOUGH, gate(kept = snapshot(9_999_000L), keptPlace = place, respectBackoff = true, backoff = backoff))
    }
}

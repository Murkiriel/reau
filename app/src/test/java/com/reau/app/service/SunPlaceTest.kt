package com.reau.app.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The automatic theme by the sun where the phone is, with "Usar a localização" on. The place is
 * kept rounded to a tenth of a degree (about 11 km): enough for the sun, no more than that of where the rider is.
 */
class SunPlaceTest {
    @Test
    fun `a place is kept to a tenth of a degree`() {
        assertEquals(DayNight.Place(-10.1, -50.6), SunPlace.rounded(-10.1234, -50.5678))
        assertEquals(DayNight.Place(-23.6, -46.6), SunPlace.rounded(-23.5505, -46.6333))
        assertEquals(DayNight.Place(0.0, 0.0), SunPlace.rounded(0.04, -0.04))
    }
}

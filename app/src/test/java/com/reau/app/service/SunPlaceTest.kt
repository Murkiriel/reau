package com.reau.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The automatic theme by the sun where the phone is, with "Usar a localização" on. The place is
 * kept rounded to a tenth of a degree (about 11 km): enough for the sun, no more than that of where the rider is.
 * Privacy: the place kept is used only while the location permission is given (the weather request is the one thing of it that leaves the phone).
 */
class SunPlaceTest {
    private val kept = DayNight.Place(-10.1, -50.6)

    @Test
    fun `a place is kept to a tenth of a degree`() {
        assertEquals(DayNight.Place(-10.1, -50.6), SunPlace.rounded(-10.1234, -50.5678))
        assertEquals(DayNight.Place(-23.6, -46.6), SunPlace.rounded(-23.5505, -46.6333))
        assertEquals(DayNight.Place(0.0, 0.0), SunPlace.rounded(0.04, -0.04))
    }

    @Test
    fun `the place kept is given with the switch on and either permission, precise or approximate`() {
        assertEquals(kept, SunPlace.keptIf(useLocation = true, fineGranted = true, coarseGranted = true) { kept })
        assertEquals(kept, SunPlace.keptIf(useLocation = true, fineGranted = true, coarseGranted = false) { kept })
        assertEquals(kept, SunPlace.keptIf(useLocation = true, fineGranted = false, coarseGranted = true) { kept })
    }

    @Test
    fun `a permission revoked in Android takes the place kept out of use, and the kept place is not even read`() {
        var read = false
        val place = SunPlace.keptIf(useLocation = true, fineGranted = false, coarseGranted = false) { read = true; kept }
        assertNull("the weather would have asked with it", place)
        assertFalse("nothing is read from the store, so nothing can leave", read)
    }

    @Test
    fun `the switch off gives no place even with the permission, and none kept stays none`() {
        assertNull(SunPlace.keptIf(useLocation = false, fineGranted = true, coarseGranted = true) { kept })
        assertNull(SunPlace.keptIf(useLocation = true, fineGranted = true, coarseGranted = true) { null })
    }
}

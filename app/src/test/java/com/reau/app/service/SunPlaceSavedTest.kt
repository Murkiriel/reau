package com.reau.app.service

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [SunPlace.saved] asks Android for the two location permissions and hands the answers to the gate ([SunPlace.keptIf], in SunPlaceTest): the
 * place kept is read only with the switch on and either permission given. Here the question to Android is a fake, so what is checked is what
 * `saved` asks and what it does with the answer (it used to be read from the source).
 */
class SunPlaceSavedTest {
    private val kept = DayNight.Place(-10.1, -50.6)
    private val fine = Manifest.permission.ACCESS_FINE_LOCATION
    private val coarse = Manifest.permission.ACCESS_COARSE_LOCATION

    private fun saved(useLocation: Boolean, given: Set<String>, asked: MutableList<String> = mutableListOf(), read: () -> DayNight.Place? = { kept }) =
        SunPlace.saved(useLocation, { asked += it; it in given }, read)

    @Test
    fun `either permission alone gives the place back, as the approximate one is enough for the sun`() {
        assertEquals(kept, saved(true, setOf(fine)))
        assertEquals(kept, saved(true, setOf(coarse)))
        assertEquals(kept, saved(true, setOf(fine, coarse)))
    }

    @Test
    fun `with neither permission given the place is not given and the store is not even read`() {
        var read = false
        assertNull(saved(true, emptySet()) { read = true; kept })
        assertFalse("the store of the place was read with no permission", read)
    }

    @Test
    fun `the switch off gives none even with both permissions, and none kept stays none`() {
        assertNull(saved(false, setOf(fine, coarse)))
        assertNull(saved(true, setOf(fine, coarse)) { null })
    }

    @Test
    fun `it asks Android for the precise and for the approximate permission, by their names`() {
        val asked = mutableListOf<String>()
        saved(true, setOf(fine, coarse), asked)
        assertEquals(setOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION"), asked.toSet())
    }
}

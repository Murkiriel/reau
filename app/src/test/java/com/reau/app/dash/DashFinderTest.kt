package com.reau.app.dash

import com.reau.app.dash.DashFinder.Step
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The app finds and fills in the dash's Wi-Fi by itself, as simply as possible. With REau on screen and the dash's
 * whole name unknown, it searches by itself; the only question is Android's own (the location, only for the Wi-Fi
 * list), once.
 */
class DashFinderTest {
    private fun step(known: Boolean = false, allowed: Boolean = false, asked: Boolean = false) = DashFinder.step(known, allowed, asked)

    @Test
    fun `a known name needs nothing`() {
        assertEquals(Step.KNOWN, step(known = true))
        assertEquals(Step.KNOWN, step(known = true, allowed = true))
    }

    @Test
    fun `with the Wi-Fi list allowed it searches by itself`() {
        assertEquals(Step.SEARCH, step(allowed = true))
        assertEquals(Step.SEARCH, step(allowed = true, asked = true))
    }

    @Test
    fun `before the terms of use are accepted nothing is asked and nothing is searched`() {
        assertEquals(Step.CANNOT, DashFinder.step(nameKnown = false, scanAllowed = false, askedAlready = false, termsAccepted = false))
        assertEquals(Step.CANNOT, DashFinder.step(nameKnown = false, scanAllowed = true, askedAlready = false, termsAccepted = false))
        assertEquals(Step.CANNOT, DashFinder.step(nameKnown = true, scanAllowed = true, askedAlready = true, termsAccepted = false))
    }

    @Test
    fun `without it, Android is asked once as REau opens`() {
        assertEquals(Step.ASK, step())
        assertEquals("asked already: no nagging", Step.CANNOT, step(asked = true))
    }
}

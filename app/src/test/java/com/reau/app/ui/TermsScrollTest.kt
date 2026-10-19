package com.reau.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TermsScrollTest {
    private val density = 2.75f

    @Test
    fun theRollTakesOneSecond() {
        assertEquals(1_000, TermsScroll.MIN_MS)
        assertEquals(1_000, TermsScroll.MAX_MS)
    }

    @Test
    fun aShortDistanceTakesTheMinimum() {
        assertEquals(TermsScroll.MIN_MS, TermsScroll.durationMs(1_000, density))
    }

    @Test
    fun noDistanceTakesTheMinimumToo() {
        assertEquals(TermsScroll.MIN_MS, TermsScroll.durationMs(0, density))
        assertEquals(TermsScroll.MIN_MS, TermsScroll.durationMs(-5, density))
    }

    @Test
    fun aVeryLongTextTakesTheMaximum() {
        assertEquals(TermsScroll.MAX_MS, TermsScroll.durationMs(200_000, density))
    }

    @Test
    fun aTypicalPhoneTakesTheSameTimeAsAnyOther() {
        assertEquals(1_000, TermsScroll.durationMs(27_000, density))
        assertEquals(1_000, TermsScroll.durationMs(4_950, density))
        assertEquals(1_000, TermsScroll.durationMs(9_900, density))
    }

    @Test
    fun theSameTimeOnAnyDensity() {
        assertEquals(TermsScroll.durationMs(8_000, 2f), TermsScroll.durationMs(12_000, 3f))
        assertEquals(1_000, TermsScroll.durationMs(5_400, 2f))
        assertEquals(1_000, TermsScroll.durationMs(8_100, 3f))
    }

    @Test
    fun aBrokenDensityFallsBackToTheMinimum() {
        assertEquals(TermsScroll.MIN_MS, TermsScroll.durationMs(20_000, 0f))
    }
}

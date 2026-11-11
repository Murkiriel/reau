package com.reau.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TermsScrollTest {
    private val density = 2.75f

    @Test
    fun theRollTakesBetweenOneAndTenSeconds() {
        assertEquals(1_000, TermsScroll.MIN_MS)
        assertEquals(10_000, TermsScroll.MAX_MS)
    }

    @Test
    fun theTextGoesByAtAPaceTheEyeCanFollow() {
        // Under 1,000 dp a second, a few screens each second; the old 1,800 dp (5,000 px) a second was a blur.
        assertTrue(TermsScroll.DP_PER_SECOND <= 1_000f)
        assertTrue(TermsScroll.DP_PER_SECOND >= 300f)
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
        assertEquals(TermsScroll.MAX_MS, TermsScroll.durationMs(2_000_000, density))
    }

    @Test
    fun aLongerTextTakesLongerUpToTheMaximum() {
        // 600 dp a second at density 2.75 is 1,650 px a second.
        assertEquals(2_000, TermsScroll.durationMs(3_300, density))
        assertEquals(5_000, TermsScroll.durationMs(8_250, density))
        assertTrue(TermsScroll.durationMs(8_250, density) > TermsScroll.durationMs(3_300, density))
    }

    @Test
    fun theSameTimeOnAnyDensity() {
        assertEquals(TermsScroll.durationMs(8_000, 2f), TermsScroll.durationMs(12_000, 3f))
        assertEquals(6_667, TermsScroll.durationMs(8_000, 2f))
    }

    @Test
    fun aBrokenDensityFallsBackToTheMinimum() {
        assertEquals(TermsScroll.MIN_MS, TermsScroll.durationMs(20_000, 0f))
    }
}

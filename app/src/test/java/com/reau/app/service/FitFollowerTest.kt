package com.reau.app.service

import com.reau.app.aa.AaScreen
import com.reau.app.settings.Fit
import com.reau.app.settings.FitParams
import com.reau.app.settings.REauSettings
import com.reau.app.video.FrameFit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The projection follows Android Auto's screen with its fit. */
class FitFollowerTest {
    private val menu = FitParams(Fit.CROP, 74, 150, -50, 0, 0)
    private val remapped = mutableListOf<FrameFit.Mapping>()
    private val said = mutableListOf<String>()

    private fun follower(settings: REauSettings?) = FitFollower({ settings }, { remapped += it }, { said += it })

    @Test
    fun `the menu takes its own fit and the apps theirs`() {
        val s = REauSettings(menuFit = menu)
        val f = follower(s)
        f.on(AaScreen.MENU)
        f.on(AaScreen.APPS)
        assertEquals(listOf(s.menuMapping, s.mapping), remapped)
        assertTrue(said.first(), said.first().contains("app menu"))
    }

    @Test
    fun `not known yet, the apps' fit`() {
        val s = REauSettings(menuFit = menu)
        follower(s).on(AaScreen.UNKNOWN)
        assertEquals(listOf(s.mapping), remapped)
    }

    @Test
    fun `without the settings (a test's sides) nothing is touched`() {
        follower(null).on(AaScreen.MENU)
        assertEquals(emptyList<FrameFit.Mapping>(), remapped)
    }
}

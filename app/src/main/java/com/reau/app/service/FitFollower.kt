package com.reau.app.service

import com.reau.app.aa.AaScreen
import com.reau.app.settings.REauSettings
import com.reau.app.video.FrameFit

/**
 * The navigation apps (Waze, Google Maps) can have a crop of their own, and everything else, the app menu too, shares the apps' ([REauSettings.mappingFor]): the projection follows the screen Android Auto
 * shows (com.reau.app.aa.AaScreens, from the picture) with the fit made for it
 * ([REauSettings.mappingFor]); each switch goes to the ride log. No settings (a test's sides), nothing.
 */
class FitFollower(
    private val settings: () -> REauSettings?,
    private val remap: (FrameFit.Mapping) -> Unit,
    private val say: (String) -> Unit,
) {
    fun on(screen: AaScreen) {
        val s = settings() ?: return
        val mapping = s.mappingFor(screen)
        say("the fit for ${name(screen)}: $mapping")
        remap(mapping)
    }

    private fun name(screen: AaScreen) = when (screen) {
        AaScreen.NAV -> "navigation (Waze, Google Maps)"
        AaScreen.MENU -> "the apps (the app menu)"
        else -> "the apps"
    }
}

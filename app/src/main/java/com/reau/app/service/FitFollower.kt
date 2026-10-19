package com.reau.app.service

import com.reau.app.aa.AaScreen
import com.reau.app.settings.REauSettings
import com.reau.app.video.FrameFit

/**
 * The app menu has a crop of its own and the apps all share theirs: the projection follows the screen Android Auto
 * shows (com.reau.app.aa.AaScreens, from the accessibility service) with the fit made for it
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
        say("the fit for ${if (screen == AaScreen.MENU) "the app menu" else "the apps"}: $mapping")
        remap(mapping)
    }
}

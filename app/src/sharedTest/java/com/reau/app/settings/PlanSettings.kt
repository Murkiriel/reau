package com.reau.app.settings

/**
 * The first defaults, the base the screen and setup tests were written against. The defaults have changed since
 * (AppDefaultsTest); what those tests check of the behaviour does not depend on them, so
 * they start from here.
 */
object PlanSettings {
    val PLAN = REauSettings(
        density = 160, fit = Fit.FILL_DOME, cropPercent = 60, cropX = null, cropY = null, fpsCeiling = 16, menuFit = null,
        maneuverData = false, autoJoinWifi = false, answerCalls = false, dashMusic = false, dashCaller = false,
        gemini = false, joystick = "", aaServerAuto = false, dashMask = false, cardImage = null,
    )
}

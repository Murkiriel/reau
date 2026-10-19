package com.reau.app.service

import com.reau.app.service.LiveTuning.Step
import com.reau.app.settings.Fit
import com.reau.app.settings.MapTheme
import com.reau.app.settings.REauSettings
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a running projection does when a setting changes: every video control applies live, with no restart. The
 * picture's place changes at once; what Android Auto was told when
 * its session began (resolution, density, margins) takes a new session of its own, the dash staying connected; the
 * pace takes a new stream; the map theme is asked again at once.
 */
class LiveTuningTest {
    // The first apps fit (filling the dome, no menu fit of its own) as the base: the defaults now crop.
    private val before = REauSettings(fit = Fit.FILL_DOME, cropPercent = 60, cropX = null, cropY = null, menuFit = null)

    @Test
    fun `the fit, the crop and the move only refit the picture`() {
        assertEquals(setOf(Step.REFIT), LiveTuning.steps(before, before.copy(fit = Fit.CROP)))
        assertEquals(setOf(Step.REFIT), LiveTuning.steps(before, before.copy(fit = Fit.CROP, cropPercent = 90)))
        assertEquals(setOf(Step.REFIT), LiveTuning.steps(before, before.copy(fit = Fit.WHOLE_VISIBLE, moveX = -20, moveY = 10)))
        // the app menu's own fit too
        val menu = com.reau.app.settings.FitParams(Fit.CROP, 74, 150, -50, 0, 0)
        assertEquals(setOf(Step.REFIT), LiveTuning.steps(before, before.copy(menuFit = menu)))
    }

    @Test
    fun `density and margins take a new Android Auto session, which also brings the new fit`() {
        assertEquals(setOf(Step.NEW_ANDROID_AUTO_SESSION), LiveTuning.steps(before, before.copy(density = 240)))
        // The margins change the fit too, but Android Auto still draws with the old ones until its new session: the
        // fit waits for it (refitting at once put the crop out of place).
        assertEquals(setOf(Step.NEW_ANDROID_AUTO_SESSION), LiveTuning.steps(before, before.copy(marginW = 100)))
    }

    @Test
    fun `the frame rate ceiling and the video clock take a new stream`() {
        assertEquals(setOf(Step.NEW_STREAM), LiveTuning.steps(before, before.copy(fpsCeiling = before.fpsCeiling + 2)))
        assertEquals(setOf(Step.NEW_STREAM), LiveTuning.steps(before, before.copy(clockSlowPercent = 5)))
    }

    @Test
    fun `the map theme is asked again at once`() {
        assertEquals(setOf(Step.NIGHT_NOW), LiveTuning.steps(before, before.copy(mapTheme = MapTheme.NIGHT)))
    }

    @Test
    fun `using the location asks day or night again at once`() {
        assertEquals(setOf(Step.NIGHT_NOW), LiveTuning.steps(before, before.copy(useLocation = !before.useLocation)))
    }

    /** A joystick page changed while running is taken at the next tap. */
    @Test
    fun `the joystick page is taken while running`() {
        assertEquals(setOf(Step.JOYSTICK), LiveTuning.steps(before, before.copy(joystick = "media.center.twice=HOME")))
    }

    /** What the dash shows besides the picture is read from the live settings: each of the four is told to the running service. */
    @Test
    fun `the phone status, the album art, the route extras and the weather are told to the running projection`() {
        assertEquals(setOf(Step.DASH_EXTRAS), LiveTuning.steps(before, before.copy(dashPhoneStatus = !before.dashPhoneStatus)))
        assertEquals(setOf(Step.DASH_EXTRAS), LiveTuning.steps(before, before.copy(dashAlbumArt = !before.dashAlbumArt)))
        assertEquals(setOf(Step.DASH_EXTRAS), LiveTuning.steps(before, before.copy(dashRouteExtras = !before.dashRouteExtras)))
        assertEquals(setOf(Step.DASH_EXTRAS), LiveTuning.steps(before, before.copy(dashWeather = !before.dashWeather)))
        // the two that Android Auto is told only when a session begins are not live
        assertEquals(emptySet<Step>(), LiveTuning.steps(before, before.copy(dashCaller = !before.dashCaller, dashMusic = !before.dashMusic)))
    }

    @Test
    fun `several at once do each, and nothing of the picture is nothing`() {
        assertEquals(
            setOf(Step.REFIT, Step.NEW_STREAM, Step.NIGHT_NOW),
            LiveTuning.steps(before, before.copy(moveX = 5, fpsCeiling = before.fpsCeiling + 1, mapTheme = MapTheme.DAY)),
        )
        assertEquals(emptySet<Step>(), LiveTuning.steps(before, before.copy(detailedLog = true, answerCalls = true)))
    }
}

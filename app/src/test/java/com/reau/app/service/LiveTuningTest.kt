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
    // The first apps fit (the whole picture, no menu fit of its own) as the base: the defaults now crop.
    private val before = REauSettings(fit = Fit.WHOLE_VISIBLE, cropPercent = 60, cropX = null, cropY = null, menuFit = null)

    @Test
    fun `the fit, the crop and the move only refit the picture`() {
        assertEquals(setOf(Step.REFIT), LiveTuning.steps(before, before.copy(fit = Fit.CROP)))
        assertEquals(setOf(Step.REFIT), LiveTuning.steps(before, before.copy(fit = Fit.CROP, cropPercent = 90)))
        assertEquals(setOf(Step.REFIT), LiveTuning.steps(before, before.copy(fit = Fit.WHOLE_VISIBLE, moveX = -20, moveY = 10)))
        // the fit once kept for the app menu is no longer used: changing it refits nothing
        val menu = com.reau.app.settings.FitParams(Fit.CROP, 74, 150, -50, 0, 0)
        assertEquals(emptySet<Step>(), LiveTuning.steps(before, before.copy(menuFit = menu)))
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

    /** What the dash shows besides the picture is read from the live settings: each is told to the running service. */
    @Test
    fun `the phone status, the album art, the maneuver ring and the weather are told to the running projection`() {
        assertEquals(setOf(Step.DASH_EXTRAS), LiveTuning.steps(before, before.copy(dashPhoneStatus = !before.dashPhoneStatus)))
        assertEquals(setOf(Step.DASH_EXTRAS), LiveTuning.steps(before, before.copy(dashAlbumArt = !before.dashAlbumArt)))
        assertEquals(setOf(Step.DASH_EXTRAS), LiveTuning.steps(before, before.copy(dashWeather = !before.dashWeather)))
        assertEquals("the ring too", setOf(Step.DASH_EXTRAS), LiveTuning.steps(before, before.copy(maneuverRing = !before.maneuverRing)))
        assertEquals("and the info ring", setOf(Step.DASH_EXTRAS), LiveTuning.steps(before, before.copy(infoRing = !before.infoRing)))
        assertEquals("what it says", setOf(Step.DASH_EXTRAS), LiveTuning.steps(before, before.copy(infoRingData = 3)))
        assertEquals("the carousel", setOf(Step.DASH_EXTRAS), LiveTuning.steps(before, before.copy(infoRingCarousel = !before.infoRingCarousel)))
        assertEquals("its time", setOf(Step.DASH_EXTRAS), LiveTuning.steps(before, before.copy(infoRingSeconds = 12)))
        assertEquals("its place", setOf(Step.DASH_EXTRAS), LiveTuning.steps(before, before.copy(infoRingInPlace = !before.infoRingInPlace)))
        // the two that Android Auto is told only when a session begins are not live
        assertEquals(emptySet<Step>(), LiveTuning.steps(before, before.copy(dashCaller = !before.dashCaller, dashMusic = !before.dashMusic)))
    }

    /**
     * Which of the settings' switches the "NA HORA" chip may carry (SettingsScreenNowTest lists the rows): "Usar a localização" is a
     * step of its own (the dash's day or night asked again), the dash's phone status and album art are told to the service, and "Atender
     * ligação" is read at each press, with no step. Gemini and the two cards are only taken when a session begins.
     */
    @Test
    fun `the location is told at once, and answering calls and Gemini wait for no step`() {
        assertEquals(setOf(Step.NIGHT_NOW), LiveTuning.steps(before, before.copy(useLocation = !before.useLocation)))
        assertEquals(emptySet<Step>(), LiveTuning.steps(before, before.copy(answerCalls = !before.answerCalls)))
        assertEquals(emptySet<Step>(), LiveTuning.steps(before, before.copy(gemini = !before.gemini)))
    }

    @Test
    fun `several at once do each, and nothing of the picture is nothing`() {
        assertEquals(
            setOf(Step.REFIT, Step.NEW_STREAM, Step.NIGHT_NOW),
            LiveTuning.steps(before, before.copy(moveX = 5, fpsCeiling = before.fpsCeiling + 1, mapTheme = MapTheme.DAY)),
        )
        assertEquals(emptySet<Step>(), LiveTuning.steps(before, before.copy(detailedLog = true, answerCalls = !before.answerCalls)))
    }

    @Test
    fun `the video experiments restart the stream and the connection ones do nothing live`() {
        assertEquals(setOf(Step.NEW_STREAM), LiveTuning.steps(before, before.copy(experimentRtpPacing = true)))
        assertEquals(setOf(Step.NEW_STREAM), LiveTuning.steps(before, before.copy(experimentAdaptiveBitrate = true)))
        assertEquals(emptySet<Step>(), LiveTuning.steps(before, before.copy(experimentDebugDscp = true)))
        assertEquals(emptySet<Step>(), LiveTuning.steps(before, before.copy(experimentWifiBind = true)))
    }
}

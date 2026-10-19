package com.reau.app.video

import com.reau.app.data.KeystoreBackedPrefsTest
import com.reau.app.service.LiveTuning
import com.reau.app.settings.REauSettings
import com.reau.app.settings.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The stream's FPS and bitrate written on the dash's video, behind a switch. */
class StatsOverlayTest {
    /** The Portuguese words, as the service reads them from values-pt/; the English ones are in values/. */
    private val words = StatsWords(panel = "painel", defaultMode = "padrão", frameUnit = "KB/quadro", keyframe = "QC")
    private val english = StatsWords(panel = "dash", defaultMode = "default", frameUnit = "KB/frame", keyframe = "KF")

    @Test
    fun `the numbers say the rate and bitrate, and dashes before the first measurement`() {
        assertEquals("3.8 fps  187 kbps", StatsOverlay.numbers(Reading(3.8, 187.0)))
        assertEquals("4.0 fps  200 kbps", StatsOverlay.numbers(Reading(3.96, 199.6)))
        assertEquals("-- fps  -- kbps", StatsOverlay.numbers(null))
    }

    @Test
    fun `frames and bytes over a span are a rate and a bitrate in kbps`() {
        // 12 pictures and 25 000 bytes in 1.25 s: 9.6 fps and 160 kbit/s
        val r = StatsOverlay.reading(12, 25_000, 1_250)!!
        assertEquals(9.6, r.fps, 0.001)
        assertEquals(160.0, r.kbps, 0.001)
        assertEquals(null, StatsOverlay.reading(1, 1, 0))
    }

    @Test
    fun `the frame size is the bitrate over the rate, the keyframe says fixed or automatic, the speed is whole km an hour`() {
        assertEquals("6.3 KB/quadro", StatsOverlay.frameSize(Reading(4.0, 200.0), words))
        assertEquals("-- KB/quadro", StatsOverlay.frameSize(null, words))
        assertEquals("-- KB/quadro", StatsOverlay.frameSize(Reading(0.0, 0.0), words))
        assertEquals("QC 1s", StatsOverlay.keyframe(1, fixed = true, words))
        assertEquals("QC auto 2s", StatsOverlay.keyframe(2, fixed = false, words))
        assertEquals("VBR", StatsOverlay.mode(com.reau.app.dash.BitrateMode.VBR, words))
        assertEquals("padrão", StatsOverlay.mode(null, words))
        assertEquals("42 km/h", StatsOverlay.speed(41.6))
        assertEquals(null, StatsOverlay.speed(null))
    }

    @Test
    fun `the words come from the language given, the numbers do not change`() {
        assertEquals("6.3 KB/frame", StatsOverlay.frameSize(Reading(4.0, 200.0), english))
        assertEquals("KF 1s", StatsOverlay.keyframe(1, fixed = true, english))
        assertEquals("KF auto 2s", StatsOverlay.keyframe(2, fixed = false, english))
        assertEquals("default", StatsOverlay.mode(null, english))
    }

    @Test
    fun `it is off by default, with a place where the rider sees it`() {
        val s = REauSettings()
        assertFalse(s.statsOverlay)
        assertFalse(s.overlay.on)
        // the default box's top-left and its width at the default size are on the glass
        assertTrue(DashGlass.isVisible(s.statsX.toDouble(), s.statsY.toDouble()))
        assertTrue(DashGlass.isVisible(s.statsX + 200.0, s.statsY + s.statsSize.toDouble()))
    }

    @Test
    fun `the switch, the place and the size are kept when saved`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(prefs).save(REauSettings(statsOverlay = true, statsX = 30, statsY = 120, statsSize = 40))
        val back = SettingsStore(prefs).load()
        assertEquals(StatsOverlay(true, 30, 120, 40), back.overlay)
    }

    @Test
    fun `the place and the size stay within the dash's frame and what is offered`() {
        val s = REauSettings(statsX = 9999, statsY = -5, statsSize = 1).sanitized()
        assertEquals(DashGlass.FRAME_W, s.statsX)
        assertEquals(0, s.statsY)
        assertEquals(StatsOverlay.SIZE.first, s.statsSize)
        assertEquals(StatsOverlay.SIZE.last, REauSettings(statsSize = 500).sanitized().statsSize)
    }

    @Test
    fun `changing the text while running is taken at once and alone`() {
        val before = REauSettings()
        assertEquals(emptySet<LiveTuning.Step>(), LiveTuning.steps(before, before.copy()))
        for (changed in listOf(before.copy(statsOverlay = true), before.copy(statsX = 10), before.copy(statsY = 10), before.copy(statsSize = 30))) {
            assertEquals(setOf(LiveTuning.Step.STATS_OVERLAY), LiveTuning.steps(before, changed))
        }
    }
}

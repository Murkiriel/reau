package com.reau.app.settings

import com.reau.app.data.KeystoreBackedPrefsTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The settings chosen after use are what a new install starts with. Not the debug ones (recordings, detailed log
 * stay off), not the slow pace experiment (decided after its report), and the card picture apart.
 */
class AppDefaultsTest {
    private val joystick = "media.left.once=PREVIOUS_SONG;media.right.once=NEXT_SONG;media.up.once=HOME;" +
        "media.down.once=ASSISTANT;media.center.once=PLAY_PAUSE;map.down.once=CONFIRM"

    @Test
    fun `a new install starts with the chosen defaults`() {
        val s = REauSettings()
        assertEquals(180, s.density)
        assertEquals(0 to 0, s.marginW to s.marginH)
        assertEquals(FitParams(Fit.CROP, 74, 0, 70, 0, 0), s.appsFit)
        assertEquals(FitParams(Fit.CROP, 65, -80, -40, 8, 0), s.menuFit)
        // The conservative envelope for the video (profile 1, FixedProfilesTest).
        assertEquals(4, s.fpsCeiling)
        assertEquals(MapTheme.AUTO, s.mapTheme)
        assertEquals(com.reau.app.dash.NavProfileMode.PESSIMISTA, s.navProfileMode)
        assertEquals(joystick, s.joystick)
        val on = listOf(s.maneuverData, s.autoJoinWifi, s.answerCalls, s.dashMusic, s.dashCaller, s.gemini, s.aaServerAuto,
            s.dashMask, s.phoneTouch, s.rideLog, s.maneuverFpsProfile, s.speedFpsProfile, s.aaNewProtocol)
        assertEquals(
            "manobra, Wi-Fi, atender, música, quem liga, Gemini, servidor, máscara, toque, registro, perfil por manobra, perfil por velocidade, protocolo 1.7",
            List(13) { true },
            on,
        )
        val off = listOf(
            s.recordVideo, s.recordSessions, s.detailedLog, s.useLocation,
        )
        assertEquals(
            "gravações, registro detalhado, localização",
            List(4) { false },
            off,
        )
        assertEquals(30, s.logKeepDays)
    }

    @Test
    fun `an empty store loads the same, and the defaults are already sane`() {
        assertEquals(REauSettings(), REauSettings().sanitized())
        assertEquals(REauSettings(), SettingsStore(KeystoreBackedPrefsTest.FakePrefs()).load())
    }

    @Test
    fun `a crop saved centred stays centred, the default place is only for nothing saved`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        val centred = REauSettings(cropX = null, cropY = null, menuFit = REauSettings.DEFAULT_MENU_FIT.copy(cropX = null, cropY = null))
        SettingsStore(prefs).save(centred)
        val back = SettingsStore(prefs).load()
        assertEquals(null to null, back.cropX to back.cropY)
        assertEquals(null to null, back.menuFit?.cropX to back.menuFit?.cropY)
    }

    /** The 526×300 measuring grid goes inside the app as the card's default. */
    @Test
    fun `the card starts with the measuring grid built in, and the legibility card once chosen stays`() {
        assertEquals("android.resource://com.reau.app/drawable/grade_medicao", REauSettings().cardImage)
        assertEquals("grade_medicao", com.reau.app.card.CardName.of(REauSettings().cardImage!!))
        assertEquals(REauSettings().cardImage, SettingsStore(KeystoreBackedPrefsTest.FakePrefs()).load().cardImage)
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(prefs).save(REauSettings(cardImage = null))
        assertEquals("the legibility card, chosen, is not the default again", null, SettingsStore(prefs).load().cardImage)
    }

    @Test
    fun `what a phone stored is kept`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        prefs.edit().putInt("density", 160).putString("fit", "FILL_DOME").putInt("fps_ceiling", 16)
            .putBoolean("menu_fit_on", false).putBoolean("gemini", false).putString("joystick", "media.up.once=HOME").apply()
        val s = SettingsStore(prefs).load()
        assertEquals(160, s.density)
        assertEquals(Fit.FILL_DOME, s.fit)
        // But for the video, which an older store gets conservative once.
        assertEquals(4, s.fpsCeiling)
        assertEquals("the menu's own fit switched off stays off", null, s.menuFit)
        assertEquals(false, s.gemini)
        assertEquals("media.up.once=HOME", s.joystick)
    }
}

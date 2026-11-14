package com.reau.app.settings

import com.reau.app.data.KeystoreBackedPrefsTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The settings chosen after use are what a new install starts with. Not the debug ones (recordings, detailed log:
 * on in a debug build, off in a release), not the slow pace experiment (decided after its report), and the card picture apart.
 */
class AppDefaultsTest {
    // The owner's own page (28/10/2026): the app menu and Gemini in the media mode, zoom on the map mode's sides.
    private val joystick = "media.up.once=HOME;media.down.once=ASSISTANT;map.left.once=ZOOM_OUT;map.right.once=ZOOM_IN"

    @Test
    fun `a new install starts with the chosen defaults`() {
        val s = REauSettings()
        assertEquals(180, s.density)
        assertEquals(0 to 0, s.marginW to s.marginH)
        // The fits of the owner's phone, taken as the defaults on 25/10/2026 and again on 28/10/2026 (the apps' crop 64% at -34, 54) (no zoom, no black bands)
        assertEquals(FitParams(Fit.WHOLE_VISIBLE, 64, -34, 54, 20, 0), s.appsFit)
        assertEquals(FitParams(Fit.WHOLE_VISIBLE, 90, 32, 20, 20, 0), s.menuFit)
        assertEquals(FitParams(Fit.CROP, 74, 90, 62, 0, 0), s.navFit)
        // The conservative envelope for the video (profile 1, FixedProfilesTest).
        assertEquals(4, s.fpsCeiling)
        assertEquals(MapTheme.AUTO, s.mapTheme)
        assertEquals(com.reau.app.dash.NavProfileMode.PESSIMISTA, s.navProfileMode)
        assertEquals(joystick, s.joystick)
        val on = listOf(s.maneuverData, s.autoJoinWifi, s.dashMusic, s.dashCaller,
            s.dashMask, s.phoneTouch, s.rideLog, s.maneuverFpsProfile, s.speedFpsProfile, s.aaNewProtocol,
            s.useLocation, s.dashPhoneStatus, s.dashAlbumArt, s.answerCalls, s.gemini)
        assertEquals(
            "manobra, Wi-Fi, música, quem liga, máscara, toque, registro, perfil por manobra, perfil por velocidade, protocolo 1.7, " +
                "localização, estado do celular, capa do álbum, atender ligação, Gemini (as quatro últimas só funcionam com a permissão liberada)",
            List(15) { true },
            on,
        )
        val off = listOf(s.maneuverRing)
        assertEquals("anel de manobras (o painel fica mais limpo)", List(1) { false }, off)
        // The debug options come on in a debug build (the owner's phone, 28/10/2026) and are written as the build type, so off in a release.
        assertEquals(List(3) { com.reau.app.BuildConfig.DEBUG }, listOf(s.recordVideo, s.recordSessions, s.detailedLog))
        assertEquals("o registro de diagnóstico vem ligado também na produção", true, s.rideLog)
        assertEquals("o tempo sem o painel", 20, s.noDashStopMinutes)
        assertEquals("nunca na build de debug, 30 dias na produção", if (com.reau.app.BuildConfig.DEBUG) 0 else 30, s.logKeepDays)
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
        assertEquals("the fill-the-dome of before reads as the whole picture", Fit.WHOLE_VISIBLE, s.fit)
        // But for the video, which an older store gets conservative once.
        assertEquals(4, s.fpsCeiling)
        assertEquals("the menu's own fit switched off stays off", null, s.menuFit)
        assertEquals(false, s.gemini)
        assertEquals("media.up.once=HOME", s.joystick)
    }
}

package com.reau.app.settings

import com.reau.app.aa.Messages
import com.reau.app.data.KeystoreBackedPrefsTest
import com.reau.app.video.AaMargins
import com.reau.app.video.DashGlass
import com.reau.app.video.FrameFit
import com.reau.app.video.RectPx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rider's settings: what is asked of Android Auto, how its picture is fitted into the dome, the pace, and the
 * debug options. A value out of range is brought back into it, so a bad file can never reach the session.
 */
class REauSettingsTest {
    /**
     * The first defaults, the base these tests were written against. The defaults have changed since
     * (AppDefaultsTest); what a test checks of the behaviour does not depend on them.
     */
    private val plan = REauSettings(
        density = 160, fit = Fit.FILL_DOME, cropPercent = 60, cropX = null, cropY = null, fpsCeiling = 16, menuFit = null,
        maneuverData = false, autoJoinWifi = false, answerCalls = false, dashMusic = false, dashCaller = false,
        gemini = false, joystick = "", aaServerAuto = false, dashMask = false,
    )

    @Test
    fun `the defaults are the chosen ones, the clock on time and no recording`() {
        // Changed on purpose; the full list is AppDefaultsTest.
        val s = REauSettings()
        assertEquals(180, s.density)
        assertEquals(0 to 0, s.marginW to s.marginH)
        assertEquals(Fit.CROP, s.fit)
        // The conservative envelope's 4 (profile 1, FixedProfilesTest).
        assertEquals(4, s.fpsCeiling)
        assertEquals(0, s.clockSlowPercent)
        assertEquals(false to false, s.recordVideo to s.recordSessions)
    }

    @Test
    fun `the Android Auto configuration carries density and margins`() {
        val s = REauSettings(density = 240, marginW = 100, marginH = 40)
        assertEquals(240, s.aaConfig.density)
        assertEquals(AaMargins(100, 40), s.aaConfig.margins)
    }

    @Test
    fun `each fit gives the mapping of FrameFit for the content Android Auto draws`() {
        val content = RectPx(50, 0, 700, 480) // 800x480 with 100 px of width margin, centred
        val base = plan.copy(marginW = 100)
        assertEquals(FrameFit.fillDome(content), base.copy(fit = Fit.FILL_DOME).mapping)
        assertEquals(FrameFit.wholeVisible(content), base.copy(fit = Fit.WHOLE_VISIBLE).mapping)
        assertEquals(FrameFit.centerCrop(content, 45), base.copy(fit = Fit.CROP, cropPercent = 45).mapping)
    }

    @Test
    fun `the picture starts unmoved, and a move is applied after the fit`() {
        assertEquals(0 to 0, plan.moveX to plan.moveY)
        val s = plan.copy(moveX = -8, moveY = 24)
        assertEquals(FrameFit.moved(plan.mapping, -8, 24), s.mapping)
        assertEquals("as tall as the frame: only the sideways move shows", RectPx(5, 0, 500, 300), s.mapping.dest)
        assertEquals(RectPx(70, 54, 370, 222), plan.copy(fit = Fit.WHOLE_VISIBLE, moveX = -8, moveY = -24).mapping.dest)
    }

    @Test
    fun `the first defaults' mapping fills the dome box`() {
        val dest = plan.mapping.dest
        assertTrue("got $dest inside ${DashGlass.DOME_BOX}", dest.x >= DashGlass.DOME_BOX.x && dest.right <= DashGlass.DOME_BOX.right)
        assertTrue(dest.w == DashGlass.DOME_BOX.w || dest.h == DashGlass.DOME_BOX.h)
    }

    @Test
    fun `the choices offered`() {
        assertEquals(80..640, REauSettings.DENSITY)
        assertEquals(4..30, REauSettings.FPS)
        assertEquals(20..100, REauSettings.CROP)
        assertEquals(listOf(0, 3, 5, 8), REauSettings.CLOCK)
        assertEquals(10, REauSettings.STEP_DENSITY)
        assertEquals(10, REauSettings.STEP_MARGIN)
        assertEquals("negative moves too: left and up", -150..150, REauSettings.MOVE)
        // The crop and the move go one by one, for the finest fit (were 10, 5 and 4).
        assertEquals(1, REauSettings.STEP_MOVE)
        // The crop's place in pixels, by 1 and by 10.
        assertEquals(1, REauSettings.STEP_CROP_POS)
        assertEquals(10, REauSettings.STEP_CROP_POS_BIG)
        assertEquals(1, REauSettings.STEP_CROP)
    }

    @Test
    fun `values out of range are brought back into it`() {
        val wild = REauSettings(
            density = 9_999, marginW = -5, marginH = 900,
            cropPercent = 3, fpsCeiling = 99, clockSlowPercent = 4, moveX = -999, moveY = 999,
        ).sanitized()
        assertEquals(640, wild.density)
        assertEquals(0, wild.marginW)
        assertEquals("at least 16 px of picture left", 480 - 16, wild.marginH)
        assertEquals(20, wild.cropPercent)
        assertEquals("fixed: profile 1 is 4 fps (FixedProfilesTest)", 4, wild.fpsCeiling)
        assertEquals("not one of the clock options", 0, wild.clockSlowPercent)
        assertEquals(-150 to 150, wild.moveX to wild.moveY)

        val low = REauSettings(density = 1, fpsCeiling = 1).sanitized()
        assertEquals(80 to 4, low.density to low.fpsCeiling)
    }

    @Test
    fun `margins are bounded by the resolution they are taken from`() {
        // Always 800×480 now (FixedSettingsTest).
        val s = REauSettings(marginW = 2_000, marginH = 900).sanitized()
        assertEquals(800 - 16 to 480 - 16, s.marginW to s.marginH)
    }

    @Test
    fun `sane values are kept as they are`() {
        val s = REauSettings(density = 200, marginW = 60, marginH = 20, fit = Fit.CROP, cropPercent = 70, recordVideo = true)
        assertEquals(s, s.sanitized())
    }

    // ── the store ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `nothing stored gives the defaults`() {
        assertEquals(REauSettings(), SettingsStore(KeystoreBackedPrefsTest.FakePrefs()).load())
    }

    @Test
    fun `touching Android Auto from the phone is announced only with it on (native)`() {
        assertEquals(true, REauSettings(phoneTouch = true).aaConfig.touch)
        assertEquals(false, REauSettings(phoneTouch = false).aaConfig.touch)
    }

    @Test
    fun `the newer Android Auto protocol is always asked for, whatever was saved`() {
        assertEquals(true, REauSettings().aaNewProtocol)
        assertEquals(Messages.MINOR_NEWER, REauSettings().aaConfig.protocolMinor)
        assertEquals("the old request stays one switch away in code", Messages.MINOR_DEFAULT, REauSettings(aaNewProtocol = false).aaConfig.protocolMinor)
        assertEquals(true, REauSettings(aaNewProtocol = false).sanitized().aaNewProtocol)
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(prefs).save(REauSettings(aaNewProtocol = false))
        assertEquals(true, SettingsStore(prefs).load().aaNewProtocol)
    }

    /** A slower Android Auto did not pay off (it queued the pictures late), so the option is gone. */
    @Test
    fun `the slower Android Auto is gone, a phone that saved it loses its keys on the next save and nothing reads them`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        prefs.edit().putBoolean("aa_slow_pace", true).putInt("aa_slow_pace_fps", 18).putBoolean("gemini", false).apply()
        val store = SettingsStore(prefs)
        assertEquals("the rest is kept", false, store.load().gemini)
        store.save(store.load())
        assertEquals(false, prefs.contains("aa_slow_pace"))
        assertEquals(false, prefs.contains("aa_slow_pace_fps"))
        assertEquals(false, store.load().gemini)
    }

    /** Discarding the start of a stream is gone too (never measured on the bench). */
    @Test
    fun `discarding the start of a stream is gone, a phone that saved it loses its key and keeps the rest`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        prefs.edit().putBoolean("discard_start", true).putBoolean("gemini", false).apply()
        val store = SettingsStore(prefs)
        store.save(store.load())
        assertEquals(false, prefs.contains("discard_start"))
        assertEquals(false, store.load().gemini)
    }

    /** The real time left, the next maneuver and the lost-signal icon, off until seen on the bike. */
    @Test
    fun `the real route figures for the dash are off by default and saved`() {
        assertEquals(false, REauSettings().dashRouteExtras)
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(prefs).save(REauSettings(dashRouteExtras = true))
        assertEquals(true, SettingsStore(prefs).load().dashRouteExtras)
    }

    /** The picture's reduction is the default one and its choice is gone (a phone may have saved CUBIC). */
    @Test
    fun `the reduction choice is gone, a phone that saved one loses its key and keeps the rest`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        prefs.edit().putString("reduction_filter", "CUBIC").putBoolean("gemini", false).apply()
        val store = SettingsStore(prefs)
        store.save(store.load())
        assertEquals(false, prefs.contains("reduction_filter"))
        assertEquals(false, store.load().gemini)
    }

    /** The blue focus taken away a minute after the joystick; always, with no switch. */
    @Test
    fun `the focus is always taken away, whatever was saved`() {
        assertEquals(true, REauSettings().clearAaFocus)
        assertEquals(true, REauSettings(clearAaFocus = false).sanitized().clearAaFocus)
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(prefs).save(REauSettings(clearAaFocus = false))
        assertEquals(true, SettingsStore(prefs).load().clearAaFocus)
    }

    @Test
    fun `logs go by themselves after 30 days unless the rider picks 7, 14 or never`() {
        assertEquals(30, REauSettings().logKeepDays)
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(prefs).save(REauSettings(logKeepDays = 7))
        assertEquals(7, SettingsStore(prefs).load().logKeepDays)
        assertEquals(listOf(7, 14, 30, 0), REauSettings.LOG_KEEP_DAYS)
        assertEquals("a value not offered falls back", 30, REauSettings(logKeepDays = 5).sanitized().logKeepDays)
        assertEquals(0, REauSettings(logKeepDays = 0).sanitized().logKeepDays)
    }

    @Test
    fun `the ride log is on until switched off, and kept off when saved`() {
        assertEquals(true, REauSettings().rideLog)
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(prefs).save(REauSettings(rideLog = false))
        assertEquals(false, SettingsStore(prefs).load().rideLog)
        assertEquals(true, SettingsStore(KeystoreBackedPrefsTest.FakePrefs()).load().rideLog)
    }

    @Test
    fun `the dash's music and call cards are on by default, kept off when saved off, and announced only when on`() {
        assertEquals(true, REauSettings().dashMusic)
        assertEquals(true, REauSettings().dashCaller)
        assertEquals(true, REauSettings().aaConfig.media)
        assertEquals(true, REauSettings().aaConfig.phone)
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(prefs).save(REauSettings(dashMusic = false, dashCaller = false))
        val loaded = SettingsStore(prefs).load()
        assertEquals(false, loaded.dashMusic)
        assertEquals(false, loaded.dashCaller)
        assertEquals(true, plan.copy(dashMusic = true).aaConfig.media)
        assertEquals(false, plan.copy(dashMusic = true).aaConfig.phone)
        assertEquals(true, plan.copy(dashCaller = true).aaConfig.phone)
    }

    @Test
    fun `the phone's state goes to the dash by default and the album picture does not`() {
        val d = REauSettings()
        assertEquals(true, d.dashPhoneStatus)
        assertEquals(false, d.dashAlbumArt)
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(prefs).save(REauSettings(dashPhoneStatus = false, dashAlbumArt = true))
        val loaded = SettingsStore(prefs).load()
        assertEquals(false, loaded.dashPhoneStatus)
        assertEquals(true, loaded.dashAlbumArt)
    }

    @Test
    fun `the dash's mask on the Android Auto screen is on by default, and kept when saved`() {
        assertEquals(true, REauSettings().dashMask)
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(prefs).save(REauSettings(dashMask = true))
        assertEquals(true, SettingsStore(prefs).load().dashMask)
        SettingsStore(prefs).save(REauSettings(dashMask = false))
        assertEquals(false, SettingsStore(prefs).load().dashMask)
    }

    @Test
    fun `Gemini by the joystick is on by default, and kept off when saved off`() {
        assertEquals(true, REauSettings().gemini)
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(prefs).save(REauSettings(gemini = false))
        assertEquals(false, SettingsStore(prefs).load().gemini)
        assertEquals("the fit is not touched by it", false, REauSettings().refitNeeded(REauSettings(gemini = false)))
    }

    /** The joystick page's choices are kept as written by JoystickBindings.encode; none at first. */
    @Test
    fun `the joystick page starts with the default page and keeps the ones saved`() {
        assertEquals(REauSettings.DEFAULT_JOYSTICK, REauSettings().joystick)
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(prefs).save(REauSettings(joystick = "media.center.twice=HOME"))
        assertEquals("media.center.twice=HOME", SettingsStore(prefs).load().joystick)
        assertEquals("the fit is not touched by it", false, REauSettings().refitNeeded(REauSettings(joystick = "media.center.twice=HOME")))
    }

    @Test
    fun `answering calls by the joystick is on by default, and kept off when saved off`() {
        assertEquals(true, REauSettings().answerCalls)
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(prefs).save(REauSettings(answerCalls = false))
        assertEquals(false, SettingsStore(prefs).load().answerCalls)
    }

    @Test
    fun `the detailed log is off until switched on, and kept when saved`() {
        assertEquals(false, REauSettings().detailedLog)
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(prefs).save(REauSettings(detailedLog = true))
        assertEquals(true, SettingsStore(prefs).load().detailedLog)
    }

    @Test
    fun `what is saved is what is loaded`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        val s = REauSettings(
            density = 320, marginW = 200, marginH = 100, fit = Fit.WHOLE_VISIBLE,
            cropPercent = 80, recordVideo = true, recordSessions = true,
            moveX = -12, moveY = 40,
        )
        SettingsStore(prefs).save(s)
        assertEquals(s, SettingsStore(prefs).load())
    }

    @Test
    fun `a stored value out of range or unknown is read back sane`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        // A store saved by this version: an older one gets the conservative video first.
        prefs.edit().putBoolean("conservative_video_20261010", true)
            .putInt("density", 5).putString("fit", "NOPE").putInt("fps_ceiling", 200)
            .putInt("stream_bitrate", 5_000_000).putInt("keyframe_sec", 9).apply()
        val s = SettingsStore(prefs).load()
        assertEquals(80, s.density)
        assertEquals("an unknown fit is read as the default", REauSettings().fit, s.fit)
        assertEquals("the stream's four values are fixed (FixedProfilesTest)", listOf(4, 400_000, 1), listOf(s.fpsCeiling, s.streamBitrateBps, s.keyframeSec))
    }

    @Test
    fun `the card is the built-in grid until the rider picks a picture or the legibility card`() {
        assertEquals(REauSettings.DEFAULT_CARD, REauSettings().cardImage)
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        val store = SettingsStore(prefs)
        store.save(REauSettings(cardImage = "content://pictures/card.png"))
        assertEquals("content://pictures/card.png", SettingsStore(prefs).load().cardImage)
        store.save(SettingsStore(prefs).load().copy(cardImage = null))
        assertEquals("back to the legibility card", null, SettingsStore(prefs).load().cardImage)
    }

    @Test
    fun `the map theme follows the sun until the rider fixes it`() {
        assertEquals(MapTheme.AUTO, REauSettings().mapTheme)
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(prefs).save(REauSettings(mapTheme = MapTheme.NIGHT))
        assertEquals(MapTheme.NIGHT, SettingsStore(prefs).load().mapTheme)
        prefs.edit().putString("map_theme", "PURPLE").apply()
        assertEquals("an unknown theme is read as the default", MapTheme.AUTO, SettingsStore(prefs).load().mapTheme)
    }

    @Test
    fun `maneuver data to the dash is on by default, and it is what Android Auto is told`() {
        assertEquals(true, REauSettings().maneuverData)
        assertEquals(false, plan.aaConfig.navigation)
        assertEquals(true, REauSettings(maneuverData = true).aaConfig.navigation)
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(prefs).save(REauSettings(maneuverData = false))
        assertEquals("fixed on (FixedProfilesTest)", true, SettingsStore(prefs).load().maneuverData)
    }

    @Test
    fun `joining the bike's Wi-Fi by itself is on by default, and kept off when saved off`() {
        assertEquals(true, REauSettings().autoJoinWifi)
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(prefs).save(REauSettings(autoJoinWifi = false))
        assertEquals(false, SettingsStore(prefs).load().autoJoinWifi)
    }

    // ── which part of Android Auto's frame the crop takes, and when a running projection is refitted ─────────────

    @Test
    fun `the crop takes the middle until the rider places it, in pixels`() {
        // the window's top left pixel (it was a percent of the slack)
        assertEquals(null, plan.cropX)
        assertEquals(null, plan.cropY)
        val content = AaMargins(0, 0).contentRect(800, 480)
        val crop = plan.copy(fit = Fit.CROP, cropPercent = 80)
        assertEquals(FrameFit.centerCrop(content, 80), crop.mapping)
        assertEquals(FrameFit.cropAt(content, 80, 0, 105), crop.copy(cropX = 0, cropY = 105).mapping)
        assertTrue("left edge taken", crop.copy(cropX = 0).mapping.source.x < crop.mapping.source.x)
    }

    @Test
    fun `the crop's centre only matters to the crop`() {
        val fill = REauSettings(fit = Fit.FILL_DOME)
        assertEquals(fill.mapping, fill.copy(cropX = 0, cropY = 0).mapping)
    }

    @Test
    fun `the crop's place may leave the picture by all the window but a pixel, and is saved`() {
        // (was half the window, and before that -100..200 % of the slack): at 74% the window is 711×405, so -710..799
        // across and -404..479 down
        val beyond = plan.copy(fit = Fit.CROP, cropPercent = 74, cropX = -300, cropY = 270).sanitized()
        assertEquals(-300 to 270, beyond.cropX to beyond.cropY)
        val wild = plan.copy(fit = Fit.CROP, cropPercent = 74, cropX = -1_000, cropY = 1_000).sanitized()
        assertEquals(-710 to 479, wild.cropX to wild.cropY)
        assertEquals("centred stays centred", null, plan.sanitized().cropX)
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(prefs).save(plan.copy(cropX = 30, cropY = 70))
        val back = SettingsStore(prefs).load()
        assertEquals(30 to 70, back.cropX to back.cropY)
        SettingsStore(prefs).save(plan)
        assertEquals(null, SettingsStore(prefs).load().cropX)
    }

    @Test
    fun `a crop saved in percent comes back at the same place in pixels`() {
        // 74% on 800×480: a 711×405 window, a slack of 89 × 75; 100% across is x 89, 20% down is y 15. The menu's too.
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        prefs.edit().putString("fit", "CROP").putInt("crop_percent", 74).putInt("crop_center_x", 100).putInt("crop_center_y", 20)
            .putBoolean("menu_fit_on", true).putString("menu_fit", "CROP").putInt("menu_crop_percent", 74)
            .putInt("menu_crop_center_x", 0).putInt("menu_crop_center_y", 60).commit()
        val back = SettingsStore(prefs).load()
        assertEquals(89 to 15, back.cropX to back.cropY)
        assertEquals(0 to 45, back.menuFit?.cropX to back.menuFit?.cropY)
        assertEquals(RectPx(89, 15, 711, 405), back.mapping.source)
    }

    @Test
    fun `a running projection is refitted only when what decides the picture's place changed`() {
        val s = plan
        assertTrue(s.refitNeeded(s.copy(moveX = -8)))
        assertTrue(s.refitNeeded(s.copy(fit = Fit.CROP)))
        val crop = s.copy(fit = Fit.CROP, cropPercent = 80)
        assertTrue(crop.refitNeeded(crop.copy(cropX = 20)))
        assertFalse("the place does not move a picture that is not cropped", s.refitNeeded(s.copy(cropX = 20)))
        assertFalse(s.refitNeeded(s.copy(fpsCeiling = 12)))
        assertFalse(s.refitNeeded(s))
    }

    // ── what the rider changed goes to the log, which setting, from what to what ─────────────────────────────────

    @Test
    fun `a changed setting is described as which, from what, to what`() {
        val before = plan
        val after = before.copy(fit = Fit.CROP, moveX = 8)
        assertEquals(listOf("fit: FILL_DOME -> CROP", "moveX: 0 -> 8"), before.changesTo(after))
        assertEquals(emptyList<String>(), before.changesTo(before))
    }

    /** The app menu's own fit is said by its own names, never mixed with the apps'. */
    @Test
    fun `the menu's own fit is said as menuFit's fields, and the apps' changes are never hidden by it`() {
        val menu = FitParams(Fit.CROP, cropPercent = 74, cropX = null, cropY = -37, moveX = 0, moveY = 0)
        val off = plan
        val on = off.copy(menuFit = menu)
        assertEquals(listOf("menuFit: null -> FitParams(fit=CROP, cropPercent=74, cropX=null, cropY=-37, moveX=0, moveY=0)"), off.changesTo(on))
        assertEquals(listOf("menuFit.cropX: null -> 10"), on.changesTo(on.copy(menuFit = menu.copy(cropX = 10))))
        assertEquals("the apps' fit, with the menu's own on", listOf("fit: FILL_DOME -> CROP"), on.changesTo(on.copy(fit = Fit.CROP)))
        assertEquals(listOf("menuFit: FitParams(fit=CROP, cropPercent=74, cropX=null, cropY=-37, moveX=0, moveY=0) -> null"), on.changesTo(off))
    }

    @Test
    fun `what is saved is saved sane`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(prefs).save(REauSettings(density = 5_000))
        assertEquals(640, prefs.getInt("density", 0))
    }

    // The map-mode choices of the page saved before the one-tap joystick are forgotten on the first reading; the media
    // ones stay; a map choice made after that is kept.
    @Test
    fun `the map-mode joystick choices of before the one-tap joystick are forgotten once`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        prefs.edit().putString("joystick", "media.left.once=HOME;map.left.once=ASSISTANT").apply()
        val store = SettingsStore(prefs)
        assertEquals("media.left.once=HOME", store.load().joystick)
        store.save(store.load().copy(joystick = "media.left.once=HOME;map.left.once=ASSISTANT"))
        assertEquals("a map choice made after that is kept", "media.left.once=HOME;map.left.once=ASSISTANT", store.load().joystick)
    }

    // ── the app menu's own fit ──────────────────────────────────────────────────────────────────────────────────

    private val menuOwn = FitParams(Fit.CROP, cropPercent = 74, cropX = 133, cropY = -37, moveX = 0, moveY = 0)

    @Test
    fun `with no fit of its own the menu takes the apps'`() {
        val s = plan.copy(fit = Fit.WHOLE_VISIBLE)
        assertEquals(null, s.menuFit)
        assertEquals(s.mapping, s.menuMapping)
        assertEquals(s.mapping, s.mappingFor(com.reau.app.aa.AaScreen.MENU))
    }

    @Test
    fun `the menu's own fit is used on the menu only`() {
        val s = plan.copy(menuFit = menuOwn)
        val expected = plan.copy(fit = Fit.CROP, cropPercent = 74, cropX = 133, cropY = -37).mapping
        assertEquals(expected, s.mappingFor(com.reau.app.aa.AaScreen.MENU))
        assertEquals(plan.mapping, s.mappingFor(com.reau.app.aa.AaScreen.APPS))
        assertEquals("not known yet: the apps'", plan.mapping, s.mappingFor(com.reau.app.aa.AaScreen.UNKNOWN))
    }

    @Test
    fun `the apps' fit reads and writes the fields of before`() {
        val s = plan.copy(fit = Fit.CROP, cropPercent = 70, cropX = 10, cropY = 20, moveX = 3, moveY = -4)
        assertEquals(FitParams(Fit.CROP, 70, 10, 20, 3, -4), s.appsFit)
        assertEquals(s, plan.withAppsFit(s.appsFit))
    }

    @Test
    fun `the menu's own fit is saved, kept within its ranges, and asks the projection to refit`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(prefs).save(plan.copy(menuFit = menuOwn))
        assertEquals(menuOwn, SettingsStore(prefs).load().menuFit)
        SettingsStore(prefs).save(plan.copy(menuFit = null))
        assertEquals(null, SettingsStore(prefs).load().menuFit)
        val wild = plan.copy(menuFit = menuOwn.copy(cropPercent = 5, cropX = 900, moveY = -9_999)).sanitized().menuFit!!
        assertEquals(plan.copy(fit = Fit.CROP, cropPercent = 5, cropX = 900, cropY = -37, moveY = -9_999).sanitized().appsFit, wild)
        assertTrue(plan.refitNeeded(plan.copy(menuFit = menuOwn)))
    }

    // ── switching the menu's own fit off and on again must give back the one it had ─────────────────────────────

    @Test
    fun `switching the menu's own fit on again gives back the one it had, not a copy of the apps'`() {
        val on = plan.copy(fit = Fit.WHOLE_VISIBLE, menuFit = menuOwn).sanitized()
        val off = on.withMenuFitOwn(false)
        assertEquals(null, off.menuFit)
        // the apps' fit changes while the menu's is off (the app is open on the screen): it must not leak into the menu's
        val appsMoved = off.copy(fit = Fit.CROP, cropPercent = 74, cropX = 0, cropY = 70)
        assertEquals(on.menuFit, appsMoved.withMenuFitOwn(true).menuFit)
        assertEquals(menuOwn.cropX, appsMoved.withMenuFitOwn(true).menuFit?.cropX)
    }

    @Test
    fun `a menu fit never customized comes on as the menu's default, not the apps'`() {
        val s = plan.copy(fit = Fit.WHOLE_VISIBLE).sanitized()
        assertEquals(null, s.menuFit)
        assertEquals(REauSettings.DEFAULT_MENU_FIT, s.withMenuFitOwn(true).menuFit)
    }

    @Test
    fun `what is changed on the menu's fit while it is on is what comes back after off and on`() {
        val first = plan.copy(menuFit = menuOwn).sanitized()
        val edited = first.copy(menuFit = menuOwn.copy(cropPercent = 70, moveX = 5)).sanitized()
        assertEquals(edited.menuFit, edited.withMenuFitOwn(false).withMenuFitOwn(true).menuFit)
    }

    @Test
    fun `the menu's fit kept while off survives the save, the restart and a new load`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(prefs).save(plan.copy(menuFit = menuOwn).sanitized().withMenuFitOwn(false))
        val back = SettingsStore(prefs).load()
        assertEquals("it is off", null, back.menuFit)
        assertEquals("but not forgotten", menuOwn, back.withMenuFitOwn(true).menuFit)
        // and saved again as it is (off), the kept one is still there
        SettingsStore(prefs).save(back)
        assertEquals(menuOwn, SettingsStore(prefs).load().withMenuFitOwn(true).menuFit)
    }

    @Test
    fun `restoring the default on the menu tab also resets the one kept while the menu's own fit is off`() {
        val off = plan.copy(menuFit = menuOwn).sanitized().withMenuFitOwn(false)
        assertEquals(null, off.withMenuFitReset().menuFit)
        assertEquals(REauSettings.DEFAULT_MENU_FIT, off.withMenuFitReset().withMenuFitOwn(true).menuFit)
        val on = plan.copy(menuFit = menuOwn).sanitized()
        assertEquals(REauSettings.DEFAULT_MENU_FIT, on.withMenuFitReset().menuFit)
    }

    @Test
    fun `the kept menu fit is not a setting the rider changed, the log says only the menu's own`() {
        val on = plan.copy(menuFit = menuOwn).sanitized()
        val edited = on.copy(menuFit = menuOwn.copy(cropX = 10)).sanitized()
        assertEquals(listOf("menuFit.cropX: 133 -> 10"), on.changesTo(edited))
    }
}

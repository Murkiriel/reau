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
        density = 160, fit = Fit.WHOLE_VISIBLE, cropPercent = 60, cropX = null, cropY = null, moveX = 0, fpsCeiling = 16, menuFit = null,
        maneuverData = false, autoJoinWifi = false, answerCalls = false, dashMusic = false, dashCaller = false,
        gemini = false, joystick = "", dashMask = false,
    )

    @Test
    fun `the defaults are the chosen ones, the clock on time and the recordings of a debug build`() {
        // Changed on purpose; the full list is AppDefaultsTest.
        val s = REauSettings()
        assertEquals(180, s.density)
        assertEquals(0 to 0, s.marginW to s.marginH)
        assertEquals(Fit.WHOLE_VISIBLE, s.fit)
        // The conservative envelope's 4 (profile 1, FixedProfilesTest).
        assertEquals(4, s.fpsCeiling)
        assertEquals(0, s.clockSlowPercent)
        assertEquals("on in the debug build the tests run, off in a release (FixedProfilesTest)", com.reau.app.BuildConfig.DEBUG to com.reau.app.BuildConfig.DEBUG, s.recordVideo to s.recordSessions)
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
        assertEquals(FrameFit.wholeVisible(content), base.copy(fit = Fit.WHOLE_VISIBLE).mapping)
        assertEquals(FrameFit.centerCrop(content, 45), base.copy(fit = Fit.CROP, cropPercent = 45).mapping)
    }

    @Test
    fun `the picture starts unmoved, and a move is applied after the fit`() {
        assertEquals(0 to 0, plan.moveX to plan.moveY)
        val s = plan.copy(moveX = -8, moveY = 24)
        assertEquals(FrameFit.moved(plan.mapping, -8, 24), s.mapping)
        assertEquals("resting on the frame's bottom: only the sideways move shows", RectPx(70, 78, 370, 222), s.mapping.dest)
        assertEquals(RectPx(70, 54, 370, 222), plan.copy(fit = Fit.WHOLE_VISIBLE, moveX = -8, moveY = -24).mapping.dest)
    }

    @Test
    fun `the first defaults' mapping stays inside the dome box`() {
        val dest = plan.mapping.dest
        assertTrue("got $dest inside ${DashGlass.DOME_BOX}", dest.x >= DashGlass.DOME_BOX.x && dest.right <= DashGlass.DOME_BOX.right)
        assertTrue(dest.w <= DashGlass.DOME_BOX.w && dest.h <= DashGlass.DOME_BOX.h)
    }

    @Test
    fun `the stats text is drawn on a debug build only, whatever a phone saved`() {
        assertEquals(com.reau.app.BuildConfig.DEBUG, REauSettings(statsOverlay = true).overlay.on)
        assertEquals(false, REauSettings(statsOverlay = false).overlay.on)
    }

    @Test
    fun `the button code is drawn on a debug build only, whatever a phone saved`() {
        assertEquals(com.reau.app.BuildConfig.DEBUG, REauSettings(buttonOverlay = true).buttonLook.on)
        assertEquals(false, REauSettings(buttonOverlay = false).buttonLook.on)
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

    /** The dash's maneuver ring is off by default (28/10/2026: the dash looks cleaner), and its choice is kept. */
    @Test
    fun `the maneuver ring is off by default and its choice is saved`() {
        assertEquals(false, REauSettings().maneuverRing)
        assertEquals(false, SettingsStore(KeystoreBackedPrefsTest.FakePrefs()).load().maneuverRing)
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(prefs).save(REauSettings(maneuverRing = true))
        assertEquals(true, SettingsStore(prefs).load().maneuverRing)
    }

    /** The theme by the dash's light sensor was taken out (the sensor is read only every five seconds): a setting saved with it is the automatic theme. */
    @Test
    fun `the three themes are kept and the removed dash light theme reads as automatic`() {
        assertEquals(MapTheme.AUTO, REauSettings().mapTheme)
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(prefs).save(REauSettings(mapTheme = MapTheme.NIGHT))
        assertEquals(MapTheme.NIGHT, SettingsStore(prefs).load().mapTheme)
        assertEquals(listOf("AUTO", "DAY", "NIGHT"), MapTheme.entries.map { it.name })
        prefs.edit().putString("map_theme", "DASH_LIGHT").apply()
        assertEquals(MapTheme.AUTO, SettingsStore(prefs).load().mapTheme)
    }

    /** A setting saved with "every kind" before the phone's cards still means every kind: the new ones come in. */
    @Test
    fun `a saved every kind of the old ring now includes the phone's cards, and a choice stays as it is`() {
        fun loaded(saved: Int): Int {
            val prefs = KeystoreBackedPrefsTest.FakePrefs()
            SettingsStore(prefs).save(REauSettings(infoRingData = saved))
            return SettingsStore(prefs).load().infoRingData
        }
        assertEquals(com.reau.app.weather.RingData.ALL, loaded(255))
        assertEquals(0b1010, loaded(0b1010))
        assertEquals(com.reau.app.weather.RingData.ALL, loaded(com.reau.app.weather.RingData.ALL))
        assertEquals("a choice of the old kinds, as the rider left it", 0b1111_1110, loaded(0b1111_1110))
        assertEquals(com.reau.app.weather.RingData.ALL, REauSettings().infoRingData)
    }

    /** The info ring is off by default (30/10/2026; on from 28/10/2026): on Waze it covers the speed limit; the rider switches it on, and the choice is kept. */
    @Test
    fun `the info ring is off by default and its choice is saved`() {
        assertEquals(false, REauSettings().infoRing)
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(prefs).save(REauSettings(infoRing = true))
        assertEquals(true, SettingsStore(prefs).load().infoRing)
        assertEquals(false, SettingsStore(KeystoreBackedPrefsTest.FakePrefs()).load().infoRing)
    }

    /** The info ring says every kind of data at first, as a carousel of 6 seconds; the rider's choices are kept and held in range. */
    @Test
    fun `the info ring's choices are all, a carousel of seven seconds, and are saved`() {
        val d = REauSettings()
        assertEquals(com.reau.app.weather.RingData.ALL, d.infoRingData)
        assertEquals(true, d.infoRingCarousel)
        assertEquals(7, d.infoRingSeconds)
        assertEquals(com.reau.app.weather.RingOptions(), d.infoRingOptions)
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(prefs).save(REauSettings(infoRingData = 0b1010, infoRingCarousel = false, infoRingSeconds = 12, infoRingInPlace = true))
        val back = SettingsStore(prefs).load()
        assertEquals(0b1010, back.infoRingData)
        assertEquals(false, back.infoRingCarousel)
        assertEquals(12, back.infoRingSeconds)
        assertEquals(true, back.infoRingInPlace)
        assertEquals(false, d.infoRingInPlace)
        assertEquals(com.reau.app.weather.RingOptions(0b1010, false, 12), back.infoRingOptions)
    }

    @Test
    fun `the info ring's time and kinds are brought back into range`() {
        assertEquals(5, REauSettings(infoRingSeconds = 1).sanitized().infoRingSeconds)
        assertEquals(30, REauSettings(infoRingSeconds = 500).sanitized().infoRingSeconds)
        assertEquals(12, REauSettings(infoRingSeconds = 12).sanitized().infoRingSeconds)
        assertEquals(com.reau.app.weather.RingData.ALL, REauSettings(infoRingData = 0x7FFF).sanitized().infoRingData)
        assertEquals(0, REauSettings(infoRingData = 0).sanitized().infoRingData)
    }

    /** The weather is kept and fetched for the dash's temperature or for the info ring, and for nothing else. */
    @Test
    fun `the weather is wanted by the weather switch or by the info ring`() {
        assertEquals("both are on by default", true, REauSettings().weatherWanted)
        assertEquals(false, REauSettings(dashWeather = false, infoRing = false).weatherWanted)
        assertEquals(true, REauSettings(dashWeather = true, infoRing = false).weatherWanted)
        assertEquals(true, REauSettings(infoRing = true, dashWeather = false).weatherWanted)
        assertEquals(true, REauSettings(dashWeather = true, infoRing = true).weatherWanted)
    }

    /** The real time left, the next maneuver and the lost-signal icon are always on (28/10/2026): no setting is left to turn them off. */
    @Test
    fun `the real route figures for the dash have no setting, and a key a phone saved for it is ignored`() {
        val names = REauSettings::class.java.declaredFields.map { it.name }
        assertEquals("a field of the real route is back: $names", emptyList<String>(), names.filter { it.contains("RouteExtras", ignoreCase = true) })
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        prefs.edit().putInt("density", 160).putBoolean("dash_route_extras", false).apply()
        assertEquals(REauSettings().copy(density = 160), SettingsStore(prefs).load().copy(density = 160))
        val saved = KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(saved).save(REauSettings())
        assertEquals(false, saved.contains("dash_route_extras"))
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
    fun `logs are kept never in a debug build and go after 30 days in a release, unless the rider picks 7, 14, 30 or never`() {
        assertEquals("a debug build starts with never", 0, REauSettings.defaultLogKeepDays(debugBuild = true))
        assertEquals("a release starts with 30 days", 30, REauSettings.defaultLogKeepDays(debugBuild = false))
        assertEquals(REauSettings.defaultLogKeepDays(), REauSettings().logKeepDays)
        assertEquals(REauSettings.defaultLogKeepDays(), SettingsStore(KeystoreBackedPrefsTest.FakePrefs()).load().logKeepDays)
        assertEquals("a value stored stays, in any build", 14, SettingsStore(KeystoreBackedPrefsTest.FakePrefs().also { it.edit().putInt("log_keep_days", 14).putInt("density", 160).apply() }).load().logKeepDays)
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(prefs).save(REauSettings(logKeepDays = 7))
        assertEquals(7, SettingsStore(prefs).load().logKeepDays)
        assertEquals(listOf(7, 14, 30, 0), REauSettings.LOG_KEEP_DAYS)
        assertEquals("a value not offered falls back to the build's own", 0, REauSettings(logKeepDays = 5).sanitized(debugBuild = true).logKeepDays)
        assertEquals("a value not offered falls back to the build's own", 30, REauSettings(logKeepDays = 5).sanitized(debugBuild = false).logKeepDays)
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
    fun `the phone's state and the album picture go to the dash by default, and what was saved stays`() {
        val d = REauSettings()
        assertEquals(true, d.dashPhoneStatus)
        assertEquals(true, d.dashAlbumArt)
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(prefs).save(REauSettings(dashPhoneStatus = false, dashAlbumArt = false))
        val loaded = SettingsStore(prefs).load()
        assertEquals(false, loaded.dashPhoneStatus)
        assertEquals(false, loaded.dashAlbumArt)
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

    /** A new install saves its first change: the default page's map-mode zoom must not be forgotten as a choice "of before the one-tap joystick". */
    @Test
    fun `saving the first change on a new install keeps the default joystick page, zoom included`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        val first = SettingsStore(prefs).load()
        assertEquals(REauSettings.DEFAULT_JOYSTICK, first.joystick)
        SettingsStore(prefs).save(first.copy(dashMusic = false))
        val again = SettingsStore(prefs).load()
        assertEquals(REauSettings.DEFAULT_JOYSTICK, again.joystick)
        assertEquals(true, com.reau.app.aa.JoystickBindings.decode(again.joystick).zoomControls)
        // And one change on the page keeps the rest of it.
        val page = com.reau.app.aa.JoystickBindings.decode(again.joystick)
            .with(com.reau.app.aa.JoystickBindings.Slot(com.reau.app.aa.JoystickBindings.Mode.MEDIA, com.reau.app.aa.JoystickBindings.Control.UP, com.reau.app.aa.JoystickBindings.Gesture.ONCE), com.reau.app.aa.JoystickBindings.Action.BACK)
        SettingsStore(prefs).save(again.copy(joystick = page.encode()))
        val saved = com.reau.app.aa.JoystickBindings.decode(SettingsStore(prefs).load().joystick)
        assertEquals(true, saved.zoomControls)
        assertEquals(com.reau.app.aa.JoystickBindings.Action.BACK, saved[com.reau.app.aa.JoystickBindings.Slot(com.reau.app.aa.JoystickBindings.Mode.MEDIA, com.reau.app.aa.JoystickBindings.Control.UP, com.reau.app.aa.JoystickBindings.Gesture.ONCE)])
    }

    @Test
    fun `answering calls by the joystick is on by default, and kept off when saved off`() {
        assertEquals(true, REauSettings().answerCalls)
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(prefs).save(REauSettings(answerCalls = false))
        assertEquals(false, SettingsStore(prefs).load().answerCalls)
    }

    @Test
    fun `the auto-off without the dash is 20 minutes by default, and keeps 5, 10, 20 or none`() {
        assertEquals(20, REauSettings().noDashStopMinutes)
        for (minutes in listOf(5, 10, 20, 0)) {
            val prefs = KeystoreBackedPrefsTest.FakePrefs()
            SettingsStore(prefs).save(REauSettings(noDashStopMinutes = minutes))
            assertEquals(minutes, SettingsStore(prefs).load().noDashStopMinutes)
        }
    }

    @Test
    fun `a limit that is not one of the offered is the default, and an old phone with none saved gets 20`() {
        assertEquals(20, REauSettings(noDashStopMinutes = 7).sanitized().noDashStopMinutes)
        assertEquals(20, REauSettings(noDashStopMinutes = -1).sanitized().noDashStopMinutes)
        val old = KeystoreBackedPrefsTest.FakePrefs()
        old.edit().putInt("density", 160).apply()
        assertEquals(20, SettingsStore(old).load().noDashStopMinutes)
    }

    // ── Gemini and "Atender ligação" are on by default again (28/10/2026): a new install has them on, what a phone chose stays ──────

    @Test
    fun `a new install has both on, and writes no mark of a migration`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        val loaded = SettingsStore(prefs).load()
        assertEquals(true, loaded.gemini)
        assertEquals(true, loaded.answerCalls)
        assertEquals("nothing is written by only reading", true, prefs.all.isEmpty())
        SettingsStore(prefs).save(loaded)
        assertEquals(true, SettingsStore(prefs).load().gemini)
        assertEquals(true, SettingsStore(prefs).load().answerCalls)
    }

    @Test
    fun `a phone that already used REau and never chose these two has them on`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        prefs.edit().putInt("density", 160).putBoolean("dash_music", true).apply()
        val loaded = SettingsStore(prefs).load()
        assertEquals(true, loaded.gemini)
        assertEquals(true, loaded.answerCalls)
    }

    @Test
    fun `what an old phone chose is kept, off or on`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        prefs.edit().putInt("density", 160).putBoolean("gemini", false).putBoolean("answer_calls", true).apply()
        val loaded = SettingsStore(prefs).load()
        assertEquals("chosen off, stays off", false, loaded.gemini)
        assertEquals("chosen on, stays on", true, loaded.answerCalls)
    }

    @Test
    fun `a rider who turns Gemini off keeps it off`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        prefs.edit().putInt("density", 160).apply()
        val store = SettingsStore(prefs)
        assertEquals(true, store.load().gemini)
        store.save(store.load().copy(gemini = false))
        assertEquals(false, SettingsStore(prefs).load().gemini)
    }

    @Test
    fun `the detailed log is on by default in a debug build, and kept off when saved off`() {
        assertEquals(com.reau.app.BuildConfig.DEBUG, REauSettings().detailedLog)
        assertEquals(true, SettingsStore(KeystoreBackedPrefsTest.FakePrefs()).load().detailedLog)
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(prefs).save(REauSettings(detailedLog = false))
        assertEquals(false, SettingsStore(prefs).load().detailedLog)
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
        val fill = REauSettings(fit = Fit.WHOLE_VISIBLE)
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
        assertEquals(listOf("fit: WHOLE_VISIBLE -> CROP", "moveX: 0 -> 8"), before.changesTo(after))
        assertEquals(emptyList<String>(), before.changesTo(before))
    }

    /** The app menu's own fit is said by its own names, never mixed with the apps'. */
    @Test
    fun `the menu's own fit is said as menuFit's fields, and the apps' changes are never hidden by it`() {
        val menu = FitParams(Fit.CROP, cropPercent = 74, cropX = null, cropY = -37, moveX = 0, moveY = 0)
        val off = plan
        val on = off.copy(menuFit = menu)
        assertEquals(listOf("menuFit: null -> FitParams(fit=CROP, cropPercent=74, cropX=null, cropY=-37, moveX=0, moveY=0, zoom=100, cover=Cover(top=0, bottom=0, left=0, right=0))"), off.changesTo(on))
        assertEquals(listOf("menuFit.cropX: null -> 10"), on.changesTo(on.copy(menuFit = menu.copy(cropX = 10))))
        assertEquals("the apps' fit, with the menu's own on", listOf("fit: WHOLE_VISIBLE -> CROP"), on.changesTo(on.copy(fit = Fit.CROP)))
        assertEquals(listOf("menuFit: FitParams(fit=CROP, cropPercent=74, cropX=null, cropY=-37, moveX=0, moveY=0, zoom=100, cover=Cover(top=0, bottom=0, left=0, right=0)) -> null"), on.changesTo(off))
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
    fun `the menu takes the apps' fit`() {
        val s = plan.copy(fit = Fit.WHOLE_VISIBLE)
        assertEquals(s.mapping, s.mappingFor(com.reau.app.aa.AaScreen.MENU))
    }

    @Test
    fun `a fit kept for the menu is no longer used, two crops only, the navigation's and the rest's`() {
        val s = plan.copy(menuFit = menuOwn)
        assertEquals("the menu takes the apps' fit", plan.mapping, s.mappingFor(com.reau.app.aa.AaScreen.MENU))
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
        assertFalse("the menu's fit is no longer used, so changing it refits nothing", plan.refitNeeded(plan.copy(menuFit = menuOwn)))
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

    // ── the navigation apps' own fit (Waze and Google Maps share it): three kinds of screen, a crop for each ───────────

    private val navOwn = FitParams(Fit.CROP, cropPercent = 70, cropX = 12, cropY = 40, moveX = 2, moveY = -3)

    @Test
    fun `navigation has a fit of its own from the start, the apps' default crop until one was set`() {
        val s = REauSettings()
        assertEquals(REauSettings.DEFAULT_NAV_FIT, s.navFit)
        assertEquals(s.navMapping, s.mappingFor(com.reau.app.aa.AaScreen.NAV))
        assertEquals("the owner's phone's crop", FrameFit.cropAt(s.content, 74, 90, 62), s.navMapping)
        assertEquals("the window the owner's phone logged on 30/10/2026", RectPx(90, 62, 711, 405), s.navMapping.source)
        assertTrue("not the apps' fit", s.mapping != s.navMapping)
    }

    @Test
    fun `the navigation has its own mapping and every other screen the apps'`() {
        val s = plan.copy(menuFit = menuOwn, navFit = navOwn)
        val nav = plan.copy(fit = Fit.CROP, cropPercent = 70, cropX = 12, cropY = 40, moveX = 2, moveY = -3).mapping
        assertEquals(nav, s.mappingFor(com.reau.app.aa.AaScreen.NAV))
        assertEquals(plan.mapping, s.mappingFor(com.reau.app.aa.AaScreen.MENU))
        assertEquals(plan.mapping, s.mappingFor(com.reau.app.aa.AaScreen.APPS))
        assertEquals(plan.mapping, s.mappingFor(com.reau.app.aa.AaScreen.UNKNOWN))
        assertTrue(nav != plan.mapping)
    }

    @Test
    fun `the navigation fit is saved and asks the projection to refit`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        val own = plan.copy(navFit = navOwn).sanitized()
        SettingsStore(prefs).save(own)
        assertEquals(navOwn, SettingsStore(prefs).load().navFit)
        assertTrue(plan.refitNeeded(plan.copy(navFit = navOwn)))
        val wild = plan.copy(navFit = navOwn.copy(cropPercent = 5, moveY = -9_999)).sanitized().navFit
        assertTrue("brought into its range", wild.moveY > -9_999)
    }

    @Test
    fun `a settings file from before has the navigation on the default fit, and the old switch is let go`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        assertEquals(REauSettings.DEFAULT_NAV_FIT, SettingsStore(prefs).load().navFit)
        prefs.edit().putBoolean("nav_fit_on", false).putInt("nav_crop_percent", 70).putString("nav_fit", "CROP").apply()
        val s = SettingsStore(prefs).load()
        assertEquals("the fit it stored is the one used, whatever the switch said", 70, s.navFit.cropPercent)
        SettingsStore(prefs).save(s)
        assertEquals("and the switch is no longer stored", false, prefs.contains("nav_fit_on"))
    }

    @Test
    fun `the log says which fields of the navigation fit changed`() {
        val off = plan.copy(navFit = navOwn).sanitized()
        assertEquals(listOf("navFit.cropPercent: 70 -> 75"), off.changesTo(off.copy(navFit = off.navFit.copy(cropPercent = 75))))
    }

    // ── the zoom and the black bands, one of each for each screen of Android Auto ─────────────────────────────────

    private val covered = FrameFit.Cover(top = 10, bottom = 20, left = 30, right = 40)

    @Test
    fun `the zoom and the bands start off for every screen`() {
        val s = REauSettings()
        assertEquals(100 to FrameFit.Cover.NONE, s.zoom to s.cover)
        for (screen in com.reau.app.aa.AaScreen.entries) {
            val m = s.mappingFor(screen)
            assertEquals(screen.name, FrameFit.Cover.NONE, m.cover)
        }
    }

    @Test
    fun `the apps' zoom and bands reach their mapping and the others' do not`() {
        val s = plan.copy(zoom = 50, cover = covered)
        assertEquals(covered, s.mapping.cover)
        assertEquals("zoomed out: a smaller picture", plan.mapping.dest.w / 2, s.mapping.dest.w)
        assertEquals("the navigation has its own, with none", FrameFit.Cover.NONE, s.navMapping.cover)
        assertEquals("and the menu takes the apps'", s.mapping.cover, s.copy(menuFit = menuOwn).mappingFor(com.reau.app.aa.AaScreen.MENU).cover)
    }

    @Test
    fun `the navigation and the rest have their own zoom and bands`() {
        val s = plan.copy(
            menuFit = menuOwn.copy(zoom = 80, cover = FrameFit.Cover(left = 60)),
            navFit = REauSettings.DEFAULT_NAV_FIT.copy(zoom = 150, cover = FrameFit.Cover(top = 15)),
            cover = FrameFit.Cover(bottom = 25),
        )
        assertEquals("the menu takes the apps'", FrameFit.Cover(bottom = 25), s.mappingFor(com.reau.app.aa.AaScreen.MENU).cover)
        assertEquals(FrameFit.Cover(top = 15), s.mappingFor(com.reau.app.aa.AaScreen.NAV).cover)
        assertEquals(FrameFit.Cover(bottom = 25), s.mappingFor(com.reau.app.aa.AaScreen.APPS).cover)
    }

    @Test
    fun `the zoom and the bands are saved for the apps, the menu and the navigation, and brought into their range`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        val s = plan.copy(
            zoom = 120, cover = covered,
            menuFit = menuOwn.copy(zoom = 90, cover = FrameFit.Cover(top = 5, right = 6)),
            navFit = navOwn.copy(zoom = 60, cover = FrameFit.Cover(bottom = 7, left = 8)),
        ).sanitized()
        SettingsStore(prefs).save(s)
        val back = SettingsStore(prefs).load()
        assertEquals(120 to covered, back.zoom to back.cover)
        assertEquals(90 to FrameFit.Cover(top = 5, right = 6), back.menuFit?.zoom to back.menuFit?.cover)
        assertEquals(60 to FrameFit.Cover(bottom = 7, left = 8), back.navFit.zoom to back.navFit.cover)
        val wild = plan.copy(zoom = 999, cover = FrameFit.Cover(top = 9_999, left = -3)).sanitized()
        assertEquals(FrameFit.ZOOM.last to FrameFit.Cover(top = FrameFit.MAX_COVER_Y), wild.zoom to wild.cover)
    }

    @Test
    fun `a zoom or a band changed asks the projection to refit, and the log says which`() {
        assertTrue(plan.refitNeeded(plan.copy(zoom = 120)))
        assertTrue(plan.refitNeeded(plan.copy(cover = covered)))
        assertEquals(listOf("zoom: 100 -> 120"), plan.changesTo(plan.copy(zoom = 120)))
        assertEquals(listOf("cover.top: 0 -> 10"), plan.changesTo(plan.copy(cover = FrameFit.Cover(top = 10))))
    }
}

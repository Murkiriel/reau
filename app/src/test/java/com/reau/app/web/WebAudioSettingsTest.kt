package com.reau.app.web

import com.reau.app.data.KeystoreBackedPrefsTest
import com.reau.app.service.LiveTuning
import com.reau.app.settings.REauSettings
import com.reau.app.settings.SettingsInUse
import com.reau.app.settings.SettingsStore
import com.reau.app.settings.WebAudioMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sound of the web server: where Android Auto's sound goes, on the phone only (as always), in the browser only, or
 * in both. Anything but "on the phone only" needs Android Auto to send the sound to REau (the media and guidance
 * sinks), and that depends on the mode alone: the browser switch going on and off does not take a new Android Auto
 * session.
 */
class WebAudioSettingsTest {
    @Test
    fun `the sound stays on the phone at first, as it always was`() {
        val s = REauSettings()
        assertEquals(WebAudioMode.PHONE, s.webAudio)
        assertFalse(s.audioSinks)
        assertFalse(s.aaConfig.audio)
    }

    @Test
    fun `each mode is kept when saved`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        for (mode in WebAudioMode.entries) {
            SettingsStore(prefs).save(REauSettings(webAudio = mode))
            assertEquals(mode, SettingsStore(prefs).load().webAudio)
        }
    }

    @Test
    fun `an unknown stored mode is the phone only`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(prefs).save(REauSettings(webAudio = WebAudioMode.BOTH))
        prefs.edit().putString("web_audio", "NOPE").apply()
        assertEquals(WebAudioMode.PHONE, SettingsStore(prefs).load().webAudio)
    }

    @Test
    fun `the sinks are announced by the mode alone, whatever the browser switch says`() {
        for (mode in WebAudioMode.entries) {
            for (switch in listOf(false, true)) {
                assertEquals("browser $switch, $mode", mode != WebAudioMode.PHONE, REauSettings(webView = switch, webAudio = mode).audioSinks)
            }
        }
        assertTrue(REauSettings(webView = false, webAudio = WebAudioMode.BROWSER).aaConfig.audio)
        assertTrue(REauSettings(webView = true, webAudio = WebAudioMode.BOTH).aaConfig.audio)
        assertFalse(REauSettings(webView = true, webAudio = WebAudioMode.PHONE).aaConfig.audio)
    }

    @Test
    fun `with the sinks announced the phone plays the sound unless the browser alone is to have it`() {
        assertFalse("phone only: Android Auto plays it itself", REauSettings(webView = true, webAudio = WebAudioMode.PHONE).soundOnPhoneViaReau)
        assertTrue("both", REauSettings(webView = true, webAudio = WebAudioMode.BOTH).soundOnPhoneViaReau)
        assertTrue("both, browser off", REauSettings(webView = false, webAudio = WebAudioMode.BOTH).soundOnPhoneViaReau)
        assertFalse("browser only, browser on: the phone stays mute", REauSettings(webView = true, webAudio = WebAudioMode.BROWSER).soundOnPhoneViaReau)
        assertTrue("browser only, browser off: nobody would hear it, so the phone does", REauSettings(webView = false, webAudio = WebAudioMode.BROWSER).soundOnPhoneViaReau)
    }

    @Test
    fun `the browser switch alone never takes a new Android Auto session, the mode going to or from the phone only does`() {
        for (mode in WebAudioMode.entries) {
            val off = REauSettings(webView = false, webAudio = mode)
            assertFalse("switch on, $mode", LiveTuning.Step.NEW_ANDROID_AUTO_SESSION in LiveTuning.steps(off, off.copy(webView = true)))
            assertFalse("switch off, $mode", LiveTuning.Step.NEW_ANDROID_AUTO_SESSION in LiveTuning.steps(off.copy(webView = true), off))
        }
        val phone = REauSettings(webView = true, webAudio = WebAudioMode.PHONE)
        assertTrue(LiveTuning.Step.NEW_ANDROID_AUTO_SESSION in LiveTuning.steps(phone, phone.copy(webAudio = WebAudioMode.BOTH)))
        assertTrue(LiveTuning.Step.NEW_ANDROID_AUTO_SESSION in LiveTuning.steps(phone.copy(webAudio = WebAudioMode.BROWSER), phone))
        val browser = phone.copy(webAudio = WebAudioMode.BROWSER)
        val steps = LiveTuning.steps(browser, browser.copy(webAudio = WebAudioMode.BOTH))
        assertFalse(LiveTuning.Step.NEW_ANDROID_AUTO_SESSION in steps)
        assertEquals(setOf(LiveTuning.Step.WEB), steps)
    }

    @Test
    fun `a new Android Auto session is told the sinks of the settings now, along with the density and the margins`() {
        val running = com.reau.app.aa.AaConfig(density = 180, navigation = true, touch = true, media = true, audio = false)
        val browser = REauSettings(density = 200, marginW = 10, marginH = 20, webView = true, webAudio = WebAudioMode.BROWSER)
        val on = LiveTuning.sessionConfig(running, browser)
        assertTrue("the sinks are announced", on.audio)
        assertEquals(200, on.density)
        assertEquals(com.reau.app.video.AaMargins(10, 20), on.margins)
        assertTrue("what the session was told before and is not about to change stays", on.navigation && on.touch && on.media)
        val off = LiveTuning.sessionConfig(on, browser.copy(webAudio = WebAudioMode.PHONE))
        assertFalse("and they are withdrawn", off.audio)
        assertTrue("the browser switch off does not withdraw them", LiveTuning.sessionConfig(on, browser.copy(webView = false)).audio)
    }

    @Test
    fun `the mode alone, with the browser switch off, takes a new session only to or from the phone only`() {
        val off = REauSettings(webView = false)
        val steps = LiveTuning.steps(off, off.copy(webAudio = WebAudioMode.BOTH))
        assertEquals(setOf(LiveTuning.Step.NEW_ANDROID_AUTO_SESSION, LiveTuning.Step.WEB), steps)
    }

    @Test
    fun `the log line of the settings in use says the mode`() {
        assertTrue(SettingsInUse.line(REauSettings()).endsWith(" webView=false webTouch=false webAudio=PHONE"))
        assertTrue(SettingsInUse.line(REauSettings(webView = true, webAudio = WebAudioMode.BOTH)).endsWith(" webAudio=BOTH"))
    }
}

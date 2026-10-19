package com.reau.app.web

import com.reau.app.data.KeystoreBackedPrefsTest
import com.reau.app.service.LiveTuning
import com.reau.app.settings.REauSettings
import com.reau.app.settings.SettingsInUse
import com.reau.app.settings.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The two switches of the web server: both off at first, kept when saved, taken at once while running. */
class WebSettingsTest {
    @Test
    fun `both switches are off at first`() {
        val s = REauSettings()
        assertFalse(s.webView)
        assertFalse(s.webTouch)
    }

    @Test
    fun `they are kept when saved, each on its own`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(prefs).save(REauSettings(webView = true, webTouch = false))
        assertEquals(true to false, SettingsStore(prefs).load().let { it.webView to it.webTouch })
        SettingsStore(prefs).save(REauSettings(webView = true, webTouch = true))
        assertEquals(true to true, SettingsStore(prefs).load().let { it.webView to it.webTouch })
    }

    @Test
    fun `sanitizing leaves them as the rider set them`() {
        val s = REauSettings(webView = true, webTouch = true).sanitized()
        assertTrue(s.webView && s.webTouch)
    }

    @Test
    fun `the log line of the settings in use says both`() {
        assertTrue(SettingsInUse.line(REauSettings()).contains(" webView=false webTouch=false"))
        assertTrue(SettingsInUse.line(REauSettings(webView = true, webTouch = true)).contains(" webView=true webTouch=true"))
    }

    @Test
    fun `changing either while running is taken at once and alone`() {
        val before = REauSettings()
        assertEquals(emptySet<LiveTuning.Step>(), LiveTuning.steps(before, before.copy()))
        assertEquals(setOf(LiveTuning.Step.WEB), LiveTuning.steps(before, before.copy(webView = true)))
        assertEquals(setOf(LiveTuning.Step.WEB), LiveTuning.steps(before, before.copy(webTouch = true)))
    }
}

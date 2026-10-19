package com.reau.app.web

import com.reau.app.data.KeystoreBackedPrefsTest
import com.reau.app.settings.REauSettings
import com.reau.app.settings.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whenever Android Auto's server on the phone is switched off, the "Ver no navegador" option switches itself off,
 * leaving the server offline until someone explicitly switches it on again.
 */
class WebSwitchTest {
    @Test
    fun `when the projection ends the browser switch goes off and nothing else changes`() {
        val before = REauSettings(webView = true, webTouch = true, density = 200, answerCalls = false)
        val after = WebSwitch.projectionEnded(before)
        assertFalse(after.webView)
        assertEquals(before.copy(webView = false), after)
    }

    @Test
    fun `a switch already off stays as it is`() {
        val off = REauSettings(webView = false, webTouch = true)
        assertEquals(off, WebSwitch.projectionEnded(off))
    }

    @Test
    fun `the stored setting is turned off and only the browser switch`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        val store = SettingsStore(prefs)
        store.save(REauSettings(webView = true, webTouch = true, density = 200))
        assertTrue(WebSwitch.switchOffStored(store))
        val back = store.load()
        assertFalse(back.webView)
        assertTrue("the touch switch is the rider's and stays", back.webTouch)
        assertEquals(200, back.density)
    }

    @Test
    fun `with the switch already off nothing is written`() {
        val store = SettingsStore(KeystoreBackedPrefsTest.FakePrefs())
        store.save(REauSettings(webView = false))
        assertFalse(WebSwitch.switchOffStored(store))
    }

    /** The server once came on by itself on connecting to the bike; it is always off by default. */
    @Test
    fun `a switch left on by a run that died is off when the next one starts, and the rest is kept`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        val store = SettingsStore(prefs)
        store.save(REauSettings(webView = true, webTouch = true, density = 200))
        val said = mutableListOf<String>()
        WebSwitch.atStart(store) { said += it }
        assertFalse(store.load().webView)
        assertTrue(store.load().webTouch)
        assertEquals(200, store.load().density)
        assertEquals("it says so in the log", 1, said.size)
    }

    @Test
    fun `at the start of a run with the switch already off nothing is written and nothing is said`() {
        val store = SettingsStore(KeystoreBackedPrefsTest.FakePrefs())
        store.save(REauSettings(webView = false))
        val said = mutableListOf<String>()
        WebSwitch.atStart(store) { said += it }
        assertTrue(said.isEmpty())
    }

    @Test
    fun `the end of a run that a newer start has replaced does not touch the switch`() {
        assertTrue("the last start is the one being stopped", WebSwitch.resetsAtStop(stopCommand = 5, lastStart = 5))
        assertTrue(WebSwitch.resetsAtStop(stopCommand = 5, lastStart = 3))
        assertFalse("a start came after this stop", WebSwitch.resetsAtStop(stopCommand = 5, lastStart = 6))
    }

    @Test
    fun `the rider can turn it on again after that`() {
        val store = SettingsStore(KeystoreBackedPrefsTest.FakePrefs())
        store.save(REauSettings(webView = true))
        WebSwitch.switchOffStored(store)
        store.save(store.load().copy(webView = true))
        assertTrue(store.load().webView)
    }
}

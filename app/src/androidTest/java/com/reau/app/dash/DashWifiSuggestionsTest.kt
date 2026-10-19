package com.reau.app.dash

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The dash's Wi-Fi suggestion is a function of (switch, name, password), so nothing is saved and restored around
 * each test; the factory prefix alone is no dash name and gets no suggestion.
 */
@RunWith(AndroidJUnit4::class)
class DashWifiSuggestionsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun tearDown() {
        // Leave no suggestion of the test's behind.
        DashWifiSuggestions.apply(context, enabled = false, ssid = "", password = "")
    }

    @Test
    fun noSuggestionWhenToggleIsOff() {
        assertNull(DashWifiSuggestions.suggestionFor(enabled = false, ssid = "RE_TEST1234", password = ""))
    }

    @Test
    fun noSuggestionWhenSsidNotYetKnown() {
        assertNull(DashWifiSuggestions.suggestionFor(enabled = true, ssid = "", password = ""))
    }

    @Test
    fun noSuggestionForTheFactoryPrefixAlone() {
        assertNull(DashWifiSuggestions.suggestionFor(enabled = true, ssid = "RE_", password = "12345678"))
    }

    @Test
    fun suggestionOnceToggleIsOnAndSsidIsKnown() {
        assertNotNull(DashWifiSuggestions.suggestionFor(enabled = true, ssid = "RE_TEST1234", password = "12345678"))
    }

    @Test
    fun syncAgainstTheRealWifiManagerDoesNotThrowInAnyState() {
        DashWifiSuggestions.apply(context, enabled = false, ssid = "RE_TEST1234", password = "") // off, nothing to add
        DashWifiSuggestions.apply(context, enabled = true, ssid = "", password = "") // on, name unknown yet
        DashWifiSuggestions.apply(context, enabled = true, ssid = "RE_TEST1234", password = "12345678") // adds one
        DashWifiSuggestions.apply(context, enabled = false, ssid = "RE_TEST1234", password = "12345678") // removes it
    }
}

package com.reau.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reau.app.settings.REauSettings
import com.reau.app.web.WebAccess
import java.net.InetAddress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The browser section and the sound's choice, as the "Servidor Local do Android Auto" screen has them: off at first,
 * the touch switch and the address only once it is on, the PIN and the address with the port, the count, the way to
 * drop the browsers, and the sound's choice always there. Only compiled here: it is proven on the phone.
 */
@RunWith(AndroidJUnit4::class)
class WebSectionTest {
    @get:Rule val compose = createComposeRule()

    private var settings by mutableStateOf(REauSettings())
    private var kicked = 0

    @Before
    fun setUp() {
        compose.setContent {
            CompositionLocalProvider(LocalWebAddresses provides { listOf(InetAddress.getByName("192.168.0.5")) }) {
                // As the network server's screen holds them: in a column that scrolls (performScrollTo needs one).
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    WebSection(settings) { settings = it }
                    AudioSection(settings) { settings = it }
                }
            }
        }
    }

    @After
    fun tearDown() = WebAccess.clear()

    @Test
    fun offAtFirstWithNothingBelowTheSwitch() {
        compose.onNodeWithTag("web_view").performScrollTo().assertIsOff()
        compose.onNodeWithTag("web_touch").assertDoesNotExist()
        compose.onNodeWithTag("web_access").assertDoesNotExist()
    }

    @Test
    fun theSwitchShowsTheTouchSwitchAndTheWarning() {
        compose.onNodeWithTag("web_view").performScrollTo().tapSwitch()
        assertEquals(true, settings.webView)
        compose.onNodeWithTag("web_touch").performScrollTo().assertIsOff().tapSwitch()
        assertEquals(true, settings.webTouch)
        compose.onNodeWithTag("web_warning").performScrollTo().assertTextContains("mapa", substring = true)
        compose.onNodeWithTag("web_waiting").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theSoundChoiceIsAlwaysThereAndPicksTheModeOfTheSound() {
        compose.onNodeWithTag("web_audio_phone").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("web_audio_both").performScrollTo().performClick()
        assertEquals(com.reau.app.settings.WebAudioMode.BOTH, settings.webAudio)
        compose.onNodeWithTag("web_audio_browser").performScrollTo().performClick()
        assertEquals(com.reau.app.settings.WebAudioMode.BROWSER, settings.webAudio)
    }

    @Test
    fun withTheServerUpThePinAndTheAddressAreShown() {
        settings = settings.copy(webView = true)
        WebAccess.publish(WebAccess.Access(8781, "4821", 0)) { kicked++ }
        compose.waitForIdle()
        compose.onNodeWithTag("web_pin").performScrollTo().assertTextContains("4821")
        compose.onNodeWithTag("web_address_0").performScrollTo().assertTextContains("http://192.168.0.5:8781")
        compose.onNodeWithTag("web_viewers").performScrollTo().assertTextContains("Nenhum", substring = true)
        compose.onNodeWithTag("web_kick").assertDoesNotExist()
    }

    @Test
    fun withViewersTheCountAndTheKickAppear() {
        settings = settings.copy(webView = true)
        WebAccess.publish(WebAccess.Access(8780, "0007", 2)) { kicked++ }
        compose.waitForIdle()
        compose.onNodeWithTag("web_viewers").performScrollTo().assertTextContains("2 navegadores", substring = true)
        compose.onNodeWithTag("web_kick").performScrollTo().performClick()
        assertEquals(1, kicked)
        assertTrue(WebAccess.state.value != null)
    }
}

package com.reau.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reau.app.service.Headline
import com.reau.app.service.Phase
import com.reau.app.service.Projection
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The network server's icon over Android Auto's side of the main screen: it opens the server's
 * screen; and the main screen says the network server is serving, with the dash not looked for.
 */
@RunWith(AndroidJUnit4::class)
class MainScreenServerBadgeTest {
    @get:Rule val compose = createComposeRule()

    private var opened = 0

    private fun show(projection: Projection, networkOn: Boolean) {
        val extras = MainExtras(onOpenServer = { opened++ })
        compose.setContent { MainScreen(MainUiState(projection, server = ServerUi(networkOn = networkOn)), {}, {}, {}, extras) }
    }

    @Test
    fun theIconIsOnTheMainScreenAndOpensTheServerScreen() {
        show(Projection(), networkOn = true)
        compose.onNodeWithTag("server_badge", useUnmergedTree = true).assertIsDisplayed().performClick()
        assertEquals(1, opened)
    }

    @Test
    fun theCardModeHasNoServerIcon() {
        show(Projection(phase = Phase.RUNNING, card = true), networkOn = true)
        compose.onNodeWithTag("server_badge", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun withTheNetworkServerOnTheMainScreenSaysSoAndNotThatTheDashIsWaited() {
        show(Projection(phase = Phase.RUNNING, networkServer = true), networkOn = true)
        compose.onNodeWithTag("status").assertIsDisplayed()
        assertEquals(Headline.NETWORK_SERVER, Projection(phase = Phase.RUNNING, networkServer = true).headline)
    }
}

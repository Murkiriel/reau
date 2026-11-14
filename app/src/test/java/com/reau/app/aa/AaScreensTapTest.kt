package com.reau.app.aa

import com.reau.app.video.PictureLook
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What a tap on the phone's screen does to the process's screen watch ([AaScreens.tapSent]): where it lands on Android Auto's video decides. */
class AaScreensTapTest {
    @After
    fun tearDown() = AaScreens.watch.reset()

    /** An app (Spotify) shown, with [look] as the last look of the picture; the session's first moments are over. */
    private fun spotifyShown(look: PictureLook.Kind? = PictureLook.Kind.MAP): AaScreenWatch {
        val watch = AaScreens.watch
        watch.reset()
        watch.look(PictureLook.Kind.APPS, 1_000)
        for (t in listOf(4_000L, 4_500L, 5_000L)) watch.look(PictureLook.Kind.APPS, t)
        assertEquals(AaScreen.APPS, watch.screen.value)
        look?.let { watch.look(it, 9_500) }
        return watch
    }

    @Test
    fun `a tap on a shortcut of the side bar with an app in front waits, and the picture says the map when it comes`() {
        for (y in listOf(199, 277)) {
            val watch = spotifyShown()
            AaScreens.tapSent(755, y, nowMs = 10_000)
            assertNull("$y", watch.look(PictureLook.Kind.MAP, 10_100))
            assertEquals("$y", AaScreen.NAV, watch.look(PictureLook.Kind.MAP, 10_350))
        }
    }

    @Test
    fun `the media shortcut after a dark picture does not arm the picture, the navigation one does`() {
        val media = spotifyShown(look = PictureLook.Kind.APPS)
        AaScreens.tapSent(755, 277, nowMs = 10_000)
        // not armed: a map seen at once still needs its two looks, but the dark looks are not held back by a settle
        assertNull(media.look(PictureLook.Kind.APPS, 10_100))
        assertEquals(AaScreen.APPS, media.screen.value)
        val navigation = spotifyShown(look = PictureLook.Kind.APPS)
        AaScreens.tapSent(755, 199, nowMs = 10_000)
        navigation.look(PictureLook.Kind.MAP, 10_100)
        assertEquals(AaScreen.NAV, navigation.look(PictureLook.Kind.MAP, 10_350))
    }

    @Test
    fun `a tap on the picture, on the microphone or on the menu's button does not wait`() {
        for ((x, y) in listOf(400 to 200, 755 to 353, 755 to 431)) {
            val watch = spotifyShown()
            AaScreens.tapSent(x, y, nowMs = 10_000)
            assertEquals("$x,$y", AaScreen.APPS, watch.screen.value)
            // Nothing waits: the menu seen three times right after the tap is taken (a wait of the side bar's would hold the looks back 800 ms).
            for (t in listOf(10_100L, 10_200L, 10_300L)) watch.look(PictureLook.Kind.MENU, t)
            assertEquals("$x,$y", AaScreen.MENU, watch.screen.value)
        }
    }

    @Test
    fun `a tap in the menu is a select wherever it lands`() {
        AaScreens.watch.reset()
        AaScreens.watch.homeSent()
        assertEquals(AaScreen.MENU, AaScreens.watch.screen.value)
        AaScreens.tapSent(400, 200, nowMs = 10_000)
        assertEquals(AaScreen.APPS, AaScreens.watch.screen.value)
    }
}

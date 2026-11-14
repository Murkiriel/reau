package com.reau.app.aa

import com.reau.app.video.PictureLook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which of Android Auto's screens is shown, said by the picture alone ([AaScreenWatch.look], the net of PictureLook) and by the keys
 * REau sends (Home, select, a tap on the side bar). Two looks of a map make the navigation, three of the menu or of an app make that one,
 * a look that is not sure starts the count over, and the first 3 s of a session are not read.
 */
class AaScreenTest {
    private val said = mutableListOf<String>()
    private val watch = AaScreenWatch { said += it }

    /** The watch past the first moments of a session: the first look at 1 s, so the picture counts from 4 s on. */
    private fun settled(): AaScreenWatch {
        watch.look(PictureLook.Kind.UNSURE, 1_000)
        assertNull(watch.look(PictureLook.Kind.UNSURE, 4_000))
        return watch
    }

    private fun looks(kind: PictureLook.Kind, vararg at: Long): AaScreen? = at.map { watch.look(kind, it) }.last()

    @Test
    fun `the first 3 seconds of a session are not read, then two looks of a map make the navigation screen`() {
        watch.look(PictureLook.Kind.MAP, 1_000)
        assertNull("inside the first 3 seconds", watch.look(PictureLook.Kind.MAP, 2_500))
        assertNull("the picture starts counting at 4 s", watch.look(PictureLook.Kind.MAP, 4_000))
        assertEquals(AaScreen.NAV, watch.look(PictureLook.Kind.MAP, 4_500))
        assertEquals(AaScreen.NAV, watch.screen.value)
        assertTrue(said.any { "the picture says the screen" in it })
        assertTrue(said.any { "by the picture: a map" in it })
    }

    @Test
    fun `three looks of an app make the apps from unknown, two of a map the navigation, three of an app the apps again`() {
        settled()
        assertNull(watch.look(PictureLook.Kind.APPS, 5_000))
        assertNull(watch.look(PictureLook.Kind.APPS, 5_500))
        assertEquals("an app seen three times from unknown", AaScreen.APPS, watch.look(PictureLook.Kind.APPS, 6_000))
        assertEquals(AaScreen.APPS, watch.screen.value)
        assertTrue(said.any { "apps (by the picture: an app, from unknown)" in it })
        watch.look(PictureLook.Kind.MAP, 7_000)
        assertEquals(AaScreen.NAV, watch.look(PictureLook.Kind.MAP, 7_500))
        assertNull(watch.look(PictureLook.Kind.APPS, 8_000))
        assertNull(watch.look(PictureLook.Kind.APPS, 8_500))
        assertEquals(AaScreen.APPS, watch.look(PictureLook.Kind.APPS, 9_000))
    }

    @Test
    fun `three looks of the menu make the menu, and three of an app or two of a map leave it`() {
        settled()
        assertNull(watch.look(PictureLook.Kind.MENU, 5_000))
        assertNull(watch.look(PictureLook.Kind.MENU, 5_500))
        assertEquals(AaScreen.MENU, watch.look(PictureLook.Kind.MENU, 6_000))
        assertTrue(said.any { "menu (by the picture: the app menu, from unknown)" in it })
        assertNull(watch.look(PictureLook.Kind.MENU, 6_500))
        assertNull(watch.look(PictureLook.Kind.APPS, 7_000))
        assertNull(watch.look(PictureLook.Kind.APPS, 7_500))
        assertEquals("an app taken from the menu", AaScreen.APPS, watch.look(PictureLook.Kind.APPS, 8_000))
        assertNull(watch.look(PictureLook.Kind.MENU, 8_500))
        assertNull(watch.look(PictureLook.Kind.MENU, 9_000))
        assertEquals("the menu back from an app", AaScreen.MENU, watch.look(PictureLook.Kind.MENU, 9_500))
        assertNull(watch.look(PictureLook.Kind.MAP, 10_000))
        assertEquals("a map takes the menu away in two looks", AaScreen.NAV, watch.look(PictureLook.Kind.MAP, 10_500))
    }

    @Test
    fun `the menu and an app in turn never count together, and a look that is not sure starts the count over`() {
        settled()
        val kinds = listOf(PictureLook.Kind.MENU, PictureLook.Kind.APPS, PictureLook.Kind.MENU, PictureLook.Kind.APPS, PictureLook.Kind.MENU)
        for ((i, kind) in kinds.withIndex()) assertNull("alternating at ${5_000 + i * 500}", watch.look(kind, 5_000L + i * 500))
        assertEquals(AaScreen.UNKNOWN, watch.screen.value)
        assertNull(watch.look(PictureLook.Kind.MENU, 8_000))
        assertNull(watch.look(PictureLook.Kind.UNSURE, 8_500))
        assertNull(watch.look(PictureLook.Kind.MENU, 9_000))
        assertNull(watch.look(PictureLook.Kind.MENU, 9_500))
        assertEquals(AaScreen.MENU, watch.look(PictureLook.Kind.MENU, 10_000))
    }

    @Test
    fun `a look that is not sure keeps the screen`() {
        settled()
        looks(PictureLook.Kind.MAP, 5_000, 5_500)
        for (t in listOf(6_000L, 6_500L, 7_000L, 7_500L)) assertNull(watch.look(PictureLook.Kind.UNSURE, t))
        assertEquals(AaScreen.NAV, watch.screen.value)
    }

    @Test
    fun `a screen the picture keeps showing is confirmed once, in the log`() {
        settled()
        looks(PictureLook.Kind.MAP, 5_000, 5_500)
        assertNull(watch.look(PictureLook.Kind.MAP, 6_000))
        watch.look(PictureLook.Kind.MAP, 6_500)
        watch.look(PictureLook.Kind.MAP, 7_000)
        assertEquals(1, said.count { "confirmed by the picture" in it })
    }

    @Test
    fun `dark pictures after a map say it was not the navigation`() {
        settled()
        looks(PictureLook.Kind.MAP, 5_000, 5_500)
        assertNull(watch.look(PictureLook.Kind.APPS, 6_000))
        assertNull(watch.look(PictureLook.Kind.APPS, 6_500))
        assertEquals(AaScreen.APPS, watch.look(PictureLook.Kind.APPS, 7_000))
        assertTrue(said.last(), "by the picture" in said.last() && "an app" in said.last())
    }

    // ── the keys REau sends ──

    @Test
    fun `the Home key takes the menu at once, said once, and a second one changes nothing`() {
        watch.homeSent()
        assertEquals(AaScreen.MENU, watch.screen.value)
        assertEquals(listOf("aa-screen: menu (the Home key)"), said)
        watch.homeSent()
        assertEquals(1, said.size)
    }

    @Test
    fun `after the Home key the map that is still leaving is no new map, and a map after the settle is`() {
        settled()
        looks(PictureLook.Kind.MAP, 5_000, 5_500)
        assertEquals(AaScreen.NAV, watch.screen.value)
        watch.homeSent(nowMs = 6_000)
        assertEquals(AaScreen.MENU, watch.screen.value)
        for (t in listOf(6_100L, 6_350L, 6_600L)) assertNull("the old map at $t", watch.look(PictureLook.Kind.MAP, t))
        assertEquals("still the menu", AaScreen.MENU, watch.screen.value)
        assertNull(watch.look(PictureLook.Kind.MAP, 6_900))
        assertEquals("a map after the settle", AaScreen.NAV, watch.look(PictureLook.Kind.MAP, 7_150))
    }

    @Test
    fun `the Home key does not silence the picture`() {
        settled()
        looks(PictureLook.Kind.MAP, 5_000, 5_500)
        watch.homeSent()
        assertEquals(AaScreen.MENU, watch.screen.value)
        watch.look(PictureLook.Kind.MAP, 6_000)
        assertEquals("the map is back (the menu was left by a tap the app does not see)", AaScreen.NAV, watch.look(PictureLook.Kind.MAP, 6_500))
    }

    @Test
    fun `a select in the menu is the apps at once, and the picture says which app it was`() {
        settled()
        watch.homeSent()
        watch.selectSent(nowMs = 10_000)
        assertEquals(AaScreen.APPS, watch.screen.value)
        assertTrue(said.last(), "a select in the menu" in said.last())
        assertNull(watch.look(PictureLook.Kind.MAP, 10_200))
        assertEquals("two looks of a map, before the 800 ms of the settle", AaScreen.NAV, watch.look(PictureLook.Kind.MAP, 10_450))
    }

    @Test
    fun `after a select the first moments still show the menu, so looks that are not of a map wait for the settle`() {
        settled()
        watch.homeSent()
        watch.selectSent(nowMs = 10_000)
        watch.look(PictureLook.Kind.MENU, 10_100)
        watch.look(PictureLook.Kind.MENU, 10_300)
        assertNull("the menu still leaving", watch.look(PictureLook.Kind.MENU, 10_600))
        assertEquals(AaScreen.APPS, watch.screen.value)
        watch.look(PictureLook.Kind.MENU, 10_900)
        watch.look(PictureLook.Kind.MENU, 11_150)
        assertEquals("three looks of the menu after the settle", AaScreen.MENU, watch.look(PictureLook.Kind.MENU, 11_400))
    }

    @Test
    fun `a select with no clock waits for nothing`() {
        settled()
        watch.homeSent()
        watch.selectSent()
        watch.look(PictureLook.Kind.MAP, 4_100)
        assertEquals(AaScreen.NAV, watch.look(PictureLook.Kind.MAP, 4_200))
    }

    @Test
    fun `a select with the navigation or the apps shown changes nothing`() {
        settled()
        looks(PictureLook.Kind.MAP, 5_000, 5_500)
        watch.selectSent(nowMs = 10_000)
        assertEquals(AaScreen.NAV, watch.screen.value)
        looks(PictureLook.Kind.APPS, 11_000, 11_500, 12_000)
        assertEquals(AaScreen.APPS, watch.screen.value)
        val before = said.size
        watch.selectSent(nowMs = 13_000)
        assertEquals(AaScreen.APPS, watch.screen.value)
        assertEquals(before, said.size)
    }

    // ── a tap on the side bar's shortcuts with an app shown (22/10/2026: the Spotify shortcut with Spotify in front took Android Auto back to the map) ──

    private fun appShown(last: PictureLook.Kind?): AaScreenWatch {
        settled()
        looks(PictureLook.Kind.APPS, 5_000, 5_500, 6_000)
        assertEquals(AaScreen.APPS, watch.screen.value)
        if (last != null) watch.look(last, 9_500)
        said.clear()
        return watch
    }

    @Test
    fun `a tap on the media shortcut with a map picture before it waits for the picture, which says the map`() {
        appShown(PictureLook.Kind.MAP)
        watch.selectSent(nowMs = 10_000, rail = AaRail.Slot.MEDIA)
        assertTrue(said.last(), "side bar" in said.last())
        assertEquals(AaScreen.APPS, watch.screen.value)
        assertNull(watch.look(PictureLook.Kind.MAP, 10_100))
        assertEquals(AaScreen.NAV, watch.look(PictureLook.Kind.MAP, 10_350))
    }

    @Test
    fun `a tap on the media shortcut with a dark picture before it opens Spotify and says nothing (Settings looks like Spotify, 23 10 2026)`() {
        appShown(PictureLook.Kind.APPS)
        watch.selectSent(nowMs = 10_000, rail = AaRail.Slot.MEDIA)
        assertTrue(said.toString(), said.none { "side bar" in it })
        assertEquals(AaScreen.APPS, watch.screen.value)
    }

    @Test
    fun `a tap on the media shortcut with no look of the picture yet changes nothing`() {
        // The apps' screen without any look of the picture: the Home key and a select put it there.
        watch.homeSent()
        watch.selectSent(nowMs = 10_000)
        assertEquals(AaScreen.APPS, watch.screen.value)
        said.clear()
        watch.selectSent(nowMs = 11_000, rail = AaRail.Slot.MEDIA)
        assertTrue(said.toString(), said.isEmpty())
    }

    @Test
    fun `a tap on the navigation shortcut waits for the picture whatever it was`() {
        for (look in listOf(PictureLook.Kind.APPS, PictureLook.Kind.UNSURE, null)) {
            said.clear()
            watch.reset()
            appShown(look)
            watch.selectSent(nowMs = 10_000, rail = AaRail.Slot.NAVIGATION)
            assertTrue("$look: ${said.toString()}", said.any { "side bar" in it })
        }
    }

    @Test
    fun `a tap on a shortcut changes nothing in the navigation or in the menu`() {
        for (slot in AaRail.Slot.entries) {
            watch.reset()
            said.clear()
            settled()
            looks(PictureLook.Kind.MAP, 5_000, 5_500)
            watch.selectSent(nowMs = 10_000, rail = slot)
            assertEquals(AaScreen.NAV, watch.screen.value)
            watch.homeSent()
            watch.selectSent(nowMs = 10_000, rail = slot)
            assertEquals(AaScreen.MENU, watch.screen.value)
            assertTrue("$slot: ${said.toString()}", said.none { "side bar" in it })
        }
    }

    // ── the end of a session ──

    @Test
    fun `a reset forgets the screen, the last look and the first moments, so the next session starts from nothing`() {
        settled()
        looks(PictureLook.Kind.MAP, 5_000, 5_500)
        assertEquals(AaScreen.NAV, watch.screen.value)
        watch.reset()
        assertEquals(AaScreen.UNKNOWN, watch.screen.value)
        watch.look(PictureLook.Kind.MAP, 10_000)
        assertNull("the 3 seconds count from the new session's first look", watch.look(PictureLook.Kind.MAP, 11_000))
        assertNull(watch.look(PictureLook.Kind.MAP, 13_500))
        assertEquals(AaScreen.NAV, watch.look(PictureLook.Kind.MAP, 14_000))
    }

    @Test
    fun `a session over forgets the last look of the picture`() {
        appShown(PictureLook.Kind.MAP)
        watch.reset()
        settled()
        looks(PictureLook.Kind.APPS, 5_000, 5_500, 6_000)
        said.clear()
        watch.selectSent(nowMs = 30_000, rail = AaRail.Slot.MEDIA)
        assertTrue("the map before the reset is forgotten", said.isEmpty())
    }

    @Test
    fun `the end of a projection resets the process's watcher`() {
        AaScreens.watch.homeSent()
        assertEquals(AaScreen.MENU, AaScreens.watch.screen.value)
        AaScreens.projection(false)
        assertEquals("the next session does not start in the menu", AaScreen.UNKNOWN, AaScreens.watch.screen.value)
        assertFalse(AaScreens.watch.screen.value == AaScreen.MENU)
    }
}

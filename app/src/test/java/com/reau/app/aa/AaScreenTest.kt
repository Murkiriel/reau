package com.reau.app.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which Android Auto
 * screen is shown, from the display that has the focused window. The menu is GhAppLauncherService's display; any other
 * of Android Auto's displays is an app (Waze, Google Maps, Spotify, the phone), all with the same fit.
 */
class AaScreenTest {
    private val launcher = "GhostActivityDisplay:ComponentInfo{com.google.android.projection.gearhead/" +
        "com.google.android.projection.gearhead.system.applauncher.GhAppLauncherService}"
    private val waze = "GhostActivityDisplay:ComponentInfo{com.google.android.projection.gearhead/" +
        "com.google.android.projection.gearhead.apphost.TemplateNavigationService}"
    private val maps = "GhostActivityDisplay:ComponentInfo{com.google.android.apps.maps/com.google.android.apps.gmm.car.GmmCarProjectionService}"

    private fun d(id: Int, name: String, focused: Boolean) = AaScreenRule.Display(id, name, focused)

    @Test
    fun `the focused launcher display is the menu`() {
        // what the phone showed with the menu up: display 8 focused, Waze's display 3 not
        assertEquals(AaScreen.MENU, AaScreenRule.of(listOf(d(3, waze, false), d(8, launcher, true), d(0, "Tela integrada", true))))
    }

    @Test
    fun `any other focused display of Android Auto is an app`() {
        assertEquals(AaScreen.APPS, AaScreenRule.of(listOf(d(3, waze, true), d(8, launcher, false))))
        assertEquals(AaScreen.APPS, AaScreenRule.of(listOf(d(16, maps, true), d(8, launcher, false))))
    }

    @Test
    fun `the phone's own screen and the side bar say nothing`() {
        assertEquals(AaScreen.UNKNOWN, AaScreenRule.of(listOf(d(0, "Tela integrada", true), d(4, "GhFacetBar", true))))
        assertEquals(AaScreen.UNKNOWN, AaScreenRule.of(emptyList()))
    }

    @Test
    fun `the display's number never matters, only its name`() {
        assertEquals(AaScreen.MENU, AaScreenRule.of(listOf(d(42, launcher, true))))
    }

    @Test
    fun `a change is taken after it holds, and said once`() {
        val said = mutableListOf<String>()
        val watch = AaScreenWatch(holdMs = 300) { said += it }
        assertNull("the first reading waits too", watch.update(listOf(d(8, launcher, true)), nowMs = 1_000))
        assertEquals("and asks to be read again", true, watch.waiting)
        assertEquals(AaScreen.MENU, watch.update(listOf(d(8, launcher, true)), nowMs = 1_300))
        assertEquals(AaScreen.MENU, watch.screen.value)
        assertEquals(false, watch.waiting)
        assertNull("the same screen again changes nothing", watch.update(listOf(d(8, launcher, true)), nowMs = 2_000))
        assertEquals(1, said.size)
        assertEquals(true, said.single().contains("menu") && said.single().contains("GhAppLauncherService") && said.single().contains("8"))
    }

    /** The shown display's name follows too, from one app to another as well, after the same hold. */
    @Test
    fun `the shown display's name is taken after it holds, from one app to another too`() {
        val watch = AaScreenWatch(holdMs = 300) {}
        watch.update(listOf(d(3, waze, true)), nowMs = 1_000)
        assertNull(watch.shownName.value)
        watch.update(listOf(d(3, waze, true)), nowMs = 1_300)
        assertEquals("TemplateNavigationService", watch.shownName.value)
        watch.update(listOf(d(3, waze, false), d(16, maps, true)), nowMs = 2_000)
        assertEquals("an app to another: the screen stays APPS, the name waits", "TemplateNavigationService", watch.shownName.value)
        assertEquals("and asks to be read again", true, watch.waiting)
        watch.update(listOf(d(3, waze, false), d(16, maps, true)), nowMs = 2_300)
        assertEquals("GmmCarProjectionService", watch.shownName.value)
        assertEquals(false, watch.waiting)
        watch.update(listOf(d(8, launcher, true)), nowMs = 3_000)
        watch.update(listOf(d(16, maps, true)), nowMs = 3_100)
        watch.update(listOf(d(16, maps, true)), nowMs = 3_500)
        assertEquals("a flicker shorter than the hold", "GmmCarProjectionService", watch.shownName.value)
        watch.update(listOf(d(0, "Tela integrada", true)), nowMs = 4_000)
        watch.update(listOf(d(0, "Tela integrada", true)), nowMs = 4_400)
        assertEquals("none of Android Auto's focused: the name stays", "GmmCarProjectionService", watch.shownName.value)
    }

    @Test
    fun `a flicker shorter than the hold changes nothing`() {
        val watch = AaScreenWatch(holdMs = 300) {}
        watch.update(listOf(d(8, launcher, true)), 0)
        watch.update(listOf(d(8, launcher, true)), 300)
        assertNull(watch.update(listOf(d(3, waze, true)), 400))
        assertNull("back to the menu before the hold", watch.update(listOf(d(8, launcher, true)), 500))
        assertNull(watch.update(listOf(d(8, launcher, true)), 900))
        assertEquals(AaScreen.MENU, watch.screen.value)
    }

    /**
     * Since Google Maps
     * is heard, its display may stay focused while another takes the focus too. The display that has just taken it wins;
     * with no new one, the one shown before, while it keeps the focus.
     */
    @Test
    fun `with two displays focused, the one that has just taken the focus is shown`() {
        val template = "GhostActivityDisplay:ComponentInfo{com.google.android.projection.gearhead/" +
            "com.google.android.projection.gearhead.apphost.TemplateService}"
        val watch = AaScreenWatch(holdMs = 400) {}
        fun twice(at: Long, vararg displays: AaScreenRule.Display) {
            watch.update(displays.toList(), at)
            watch.update(displays.toList(), at + 450) // read again after the hold
        }
        twice(0, d(170, maps, true))
        assertEquals(AaScreen.APPS, watch.screen.value)
        twice(2_000, d(170, maps, true), d(173, launcher, true))
        assertEquals("the menu, Maps still focused", AaScreen.MENU, watch.screen.value)
        twice(4_000, d(170, maps, true), d(173, launcher, false), d(177, template, true))
        assertEquals("an app", AaScreen.APPS, watch.screen.value)
        twice(15_000, d(170, maps, true), d(173, launcher, true), d(177, template, false))
        assertEquals("the menu again", AaScreen.MENU, watch.screen.value)
        twice(21_000, d(170, maps, true), d(173, launcher, false), d(177, template, false))
        assertEquals("Maps, the only one focused", AaScreen.APPS, watch.screen.value)
    }

    @Test
    fun `the display that took the focus stays shown while the other keeps its focus too`() {
        // the menu (a newer display) still focused when Maps takes the focus: Maps holds, read after read
        val watch = AaScreenWatch(holdMs = 0) {}
        watch.update(listOf(d(170, maps, false), d(173, launcher, true)), 0)
        assertEquals(AaScreen.MENU, watch.screen.value)
        watch.update(listOf(d(170, maps, true), d(173, launcher, true)), 100)
        assertEquals(AaScreen.APPS, watch.screen.value)
        watch.update(listOf(d(170, maps, true), d(173, launcher, true)), 200)
        assertEquals("the same reading again keeps Maps", AaScreen.APPS, watch.screen.value)
    }

    @Test
    fun `unknown keeps the last screen`() {
        val watch = AaScreenWatch(holdMs = 0) {}
        watch.update(listOf(d(3, waze, true)), 0)
        assertEquals(AaScreen.APPS, watch.screen.value)
        assertNull(watch.update(listOf(d(0, "Tela integrada", true)), 10))
        assertEquals(AaScreen.APPS, watch.screen.value)
    }

    /**
     * A synthetic sequence of readings: the joystick's select opens the menu from Waze's map, a second select picks Waze,
     * and Android Auto leaves the launcher's display focused, reading after reading. Without the select rule the menu's
     * fit stays on the map until Waze's display takes the focus again.
     */
    @Test
    fun `a select in the menu is the apps at once, and the launcher kept focused is no menu`() {
        val said = mutableListOf<String>()
        val watch = AaScreenWatch(holdMs = 400) { said += it }
        fun read(at: Long, wazeFocused: Boolean?, launcherFocused: Boolean) =
            watch.update(listOfNotNull(wazeFocused?.let { d(21, waze, it) }, d(22, launcher, launcherFocused)), at)
        watch.update(listOf(d(21, waze, true)), -60_000)
        watch.update(listOf(d(21, waze, true)), -59_500)
        assertEquals(AaScreen.APPS, watch.screen.value)
        read(800, wazeFocused = true, launcherFocused = false)
        read(850, wazeFocused = true, launcherFocused = true)
        read(1_000, wazeFocused = false, launcherFocused = true)
        read(1_600, wazeFocused = null, launcherFocused = true)
        assertEquals("the menu", AaScreen.MENU, watch.screen.value)

        watch.selectSent() // Waze picked in the menu
        assertEquals("the apps at once", AaScreen.APPS, watch.screen.value)
        assertEquals("aa-screen: apps (a select in the menu)", said.last())
        for ((at, waze) in listOf(12_850L to false, 13_050L to null, 13_150L to false, 13_250L to null, 13_650L to false, 14_250L to null,
            70_000L to false, 70_200L to null, 130_000L to false, 130_200L to null, 131_000L to null)) {
            read(at, wazeFocused = waze, launcherFocused = true)
            assertEquals("the launcher still focused at +$at ms is not the menu", AaScreen.APPS, watch.screen.value)
        }
        watch.selectSent() // a select on the map changes nothing
        assertEquals(AaScreen.APPS, watch.screen.value)
        read(260_000, wazeFocused = true, launcherFocused = true) // Waze's display takes the focus too
        assertEquals(AaScreen.APPS, watch.screen.value)

        // the menu opened again: the launcher's display takes the focus anew, and is the menu after the hold
        read(300_000, wazeFocused = true, launcherFocused = false)
        read(300_100, wazeFocused = false, launcherFocused = true)
        read(300_600, wazeFocused = false, launcherFocused = true)
        assertEquals(AaScreen.MENU, watch.screen.value)
    }

    @Test
    fun `after a select the menu taking the focus anew is the menu again`() {
        val watch = AaScreenWatch(holdMs = 0) {}
        watch.update(listOf(d(3, waze, false), d(8, launcher, true)), 0)
        assertEquals(AaScreen.MENU, watch.screen.value)
        watch.selectSent()
        watch.update(listOf(d(3, waze, false), d(8, launcher, true)), 100)
        assertEquals(AaScreen.APPS, watch.screen.value)
        watch.update(listOf(d(3, waze, false)), 200) // the launcher's window gone for a reading
        watch.update(listOf(d(3, waze, false), d(8, launcher, true)), 300) // and focused again: opened anew
        assertEquals(AaScreen.MENU, watch.screen.value)
    }
}

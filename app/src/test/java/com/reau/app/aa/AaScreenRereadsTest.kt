package com.reau.app.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The screen Android Auto shows is read
 * again without waiting for the system to say a window changed, after each input sent to it and once a second while
 * the projection runs.
 */
class AaScreenRereadsTest {
    private val rereads = AaScreenRereads(afterInputMs = listOf(300L, 800L, 1_500L), everyMs = 1_000L)

    /** The times reads fell due, walking the clock in 10 ms steps from [from] to [to]. */
    private fun dueBetween(from: Long, to: Long): List<Long> = (from..to step 10).filter { rereads.due(it) }

    @Test
    fun `after an input, three reads at 300, 800 and 1500 ms`() {
        rereads.inputSent(10_000)
        assertEquals(10_300L, rereads.nextAt())
        assertEquals(listOf(10_300L, 10_800L, 11_500L), dueBetween(10_000, 13_000))
        assertNull("nothing more without the projection", rereads.nextAt())
    }

    @Test
    fun `inputs in a row do not pile up reads, the last one sets them`() {
        rereads.inputSent(10_000)
        assertEquals(listOf(10_300L), dueBetween(10_000, 10_400))
        rereads.inputSent(10_400)
        assertEquals(listOf(10_700L, 11_200L, 11_900L), dueBetween(10_410, 13_000))
    }

    @Test
    fun `while the projection runs, a read every second, stopped none`() {
        rereads.projection(true, 20_000)
        assertEquals(listOf(21_000L, 22_000L, 23_000L), dueBetween(20_000, 23_500))
        rereads.projection(false, 23_500)
        assertEquals(emptyList<Long>(), dueBetween(23_510, 27_000))
        assertNull(rereads.nextAt())
    }

    @Test
    fun `an input during the projection adds its reads to the second ticks`() {
        rereads.projection(true, 0)
        assertEquals(listOf(1_000L), dueBetween(0, 1_200))
        rereads.inputSent(1_200)
        assertEquals(listOf(1_500L, 2_000L, 2_700L, 3_000L), dueBetween(1_210, 3_000))
    }

    /** The defaults: reads 300, 800 and 1500 ms after an input, and one a second while projecting. */
    @Test
    fun `the defaults are 300, 800 and 1500 ms after an input and a second while projecting`() {
        val defaults = AaScreenRereads()
        defaults.inputSent(0)
        defaults.projection(true, 0)
        assertEquals(listOf(300L, 800L, 1_000L, 1_500L, 2_000L), (0L..2_000L step 10).filter { defaults.due(it) })
    }

    /** Written after the mutation run (survivor "próxima ignora o tique"): the service plans its next read by this. */
    @Test
    fun `the next read while projecting is the second tick`() {
        rereads.projection(true, 5_000)
        assertEquals(6_000L, rereads.nextAt())
        rereads.inputSent(5_900)
        assertEquals(6_000L, rereads.nextAt())
    }

    @Test
    fun `a read that falls due is taken once`() {
        rereads.inputSent(0)
        assertEquals(true, rereads.due(300))
        assertEquals(false, rereads.due(300))
        assertEquals(800L, rereads.nextAt())
    }

    /**
     * The menu shown, the rider picks Waze with the joystick (key 23), the read the system causes 320 ms later still
     * sees the menu focused, then the focus goes to Waze's display with no window event at all. Without the reads after
     * the input the menu's fit stays until something else causes a read; with them, Waze is taken within 1.5 s.
     */
    @Test
    fun `a pick in the menu with no window event ends on the apps within a second and a half`() {
        val watch = AaScreenWatch(AaScreens.HOLD_MS) {}
        fun d(id: Int, name: String, focused: Boolean) = AaScreenRule.Display(id, "GhostActivityDisplay:ComponentInfo{x/y.$name}", focused)
        val bothFocused = listOf(d(478, "TemplateNavigationService", true), d(484, "VoicePlateCarService", false),
            d(493, "ProjectionTrampolineFallbackService", false), d(495, "GhAppLauncherService", true))
        val menuOnly = listOf(d(478, "TemplateNavigationService", false), d(484, "VoicePlateCarService", false),
            d(493, "ProjectionTrampolineFallbackService", false), d(495, "GhAppLauncherService", true))

        // the menu is taken
        watch.update(listOf(d(478, "TemplateNavigationService", true), d(484, "VoicePlateCarService", false)), 0)
        watch.update(bothFocused, 600)
        watch.update(bothFocused, 1_100)
        assertEquals(AaScreen.MENU, watch.screen.value)

        val key = 62_000L // the joystick's select
        rereads.inputSent(key)
        watch.update(menuOnly, key + 320) // the system's read, before the focus moved
        val focusMoved = key + 500 // no window event says this
        var t = key + 330
        while (t <= key + 1_500) {
            if (rereads.due(t)) watch.update(if (t < focusMoved) menuOnly else bothFocused, t)
            t += 10
        }
        assertEquals(AaScreen.APPS, watch.screen.value)
    }
}

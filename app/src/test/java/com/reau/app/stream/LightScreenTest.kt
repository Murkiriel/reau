package com.reau.app.stream

import com.reau.app.stream.StreamGovernor.Action
import com.reau.app.video.DashCompositor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The faster pace while Android Auto's app menu is in front: only the pace, with a hold, never over the dash's limit. */
class LightScreenTest {
    // ── the rule ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the pace goes up at once on a light screen and comes back after the hold when it stops being one`() {
        val light = LightScreen(holdMs = 500)
        assertFalse(light.update(light = false, thermalOk = true, nowMs = 0))
        assertTrue(light.update(light = true, thermalOk = true, nowMs = 1_000))
        assertTrue("held for the flicker of a read", light.update(light = false, thermalOk = true, nowMs = 1_499))
        assertFalse(light.update(light = false, thermalOk = true, nowMs = 1_500))
    }

    @Test
    fun `the menu and the other apps ask for the faster pace, the navigation and a screen not known follow the profile`() {
        assertTrue(LightScreen.wanted(com.reau.app.aa.AaScreen.MENU))
        assertTrue(LightScreen.wanted(com.reau.app.aa.AaScreen.APPS))
        assertFalse("the navigation follows the profiles", LightScreen.wanted(com.reau.app.aa.AaScreen.NAV))
        assertFalse(LightScreen.wanted(com.reau.app.aa.AaScreen.UNKNOWN))
    }

    @Test
    fun `a light screen seen again restarts the hold`() {
        val light = LightScreen(holdMs = 500)
        light.update(true, true, 0)
        light.update(false, true, 400)
        light.update(true, true, 450)
        assertTrue(light.update(false, true, 900))
        assertFalse(light.update(false, true, 950))
    }

    @Test
    fun `a hot phone puts it out at once and a light screen does not bring it back while hot`() {
        val light = LightScreen()
        assertTrue(light.update(true, true, 0))
        assertFalse(light.update(true, false, 100))
        assertFalse(light.update(true, false, 200))
        assertTrue(light.update(true, true, 300))
    }

    @Test
    fun `the light rate is never below the profile's`() {
        assertEquals(8, LightScreen.fps(base = 4, light = 8))
        assertEquals("a profile above it is kept", 16, LightScreen.fps(base = 16, light = 8))
    }

    // ── in the governor ────────────────────────────────────────────────────────────────────────────────────

    private var nowMs = 0L
    private var acks = 0L

    private fun governor(lightFps: Int) =
        StreamGovernor(4, lightScreenFps = lightFps).also { it.start(nowMs) }

    private fun StreamGovernor.poll(light: Boolean, hot: Boolean = false, confirmed: Boolean = true, navSample: StreamGovernor.NavSample? = null, frames: Int = 1): List<Action> {
        nowMs += 250
        acks += frames
        return onPoll(
            StreamGovernor.Poll(
                nowMs = nowMs, compositor = DashCompositor.Stats(frames * 2, frames, 0, 0L), framesOut = frames, bytesOut = 1_000L * frames,
                lateFrames = 0, encodeNs = 0L,
                dashAcks = acks, firstFrameConfirmed = confirmed, thermalOk = !hot, lightScreen = light, navSample = navSample,
            ),
        )
    }

    private fun List<Action>.pace() = filterIsInstance<Action.SetPace>().map { it.pace.activeMs }

    @Test
    fun `on the app menu the tick goes from 250 ms to 125 ms, and back after the hold, with a line each way and no new encoder`() {
        val g = governor(lightFps = 8)
        g.poll(light = false)
        assertEquals(4, g.fps)
        val up = g.poll(light = true)
        assertEquals(listOf(125L), up.pace())
        assertEquals(8, g.fps)
        assertEquals(4, g.encoderFps)
        assertTrue(up.filterIsInstance<Action.Log>().any { "light screen: pace up to 8 fps" in it.line })
        var back = emptyList<Action>()
        repeat(12) { back = back + g.poll(light = false) }
        assertEquals(listOf(250L), back.pace())
        assertEquals(4, g.fps)
        assertTrue(back.filterIsInstance<Action.Log>().any { "light screen: pace back to the profile's 4 fps" in it.line })
        assertTrue("the encoder is never replaced for this", (up + back).none { it is Action.Rebuild })
    }

    @Test
    fun `with the option off the menu changes nothing`() {
        val g = governor(lightFps = 0)
        val all = (1..8).flatMap { g.poll(light = true) }
        assertEquals(4, g.fps)
        assertTrue(all.none { it is Action.Log && "light screen" in it.line })
    }

    @Test
    fun `a hot phone gets no faster pace`() {
        val g = governor(lightFps = 8)
        g.poll(light = true, hot = true)
        assertEquals(4, g.fps)
    }

    @Test
    fun `before the dash confirms the picture the pace is the profile's whatever the screen`() {
        val g = governor(lightFps = 8)
        g.poll(light = true, confirmed = false)
        assertEquals(4, g.fps)
        g.poll(light = true, confirmed = true)
        assertEquals(8, g.fps)
    }

    @Test
    fun `in the reduced profile the menu raises the pace and the bitrate too, and the reduced profile comes back after it`() {
        // 21/10/2026: far from the next maneuver the profile is REDUCED (2 fps, half the bitrate) and the rider's menu was a 2 fps picture.
        val g = governor(lightFps = 8)
        fun far(distance: Int) = StreamGovernor.NavSample(
            maneuverProfileEnabled = true, speedProfileEnabled = false, distanceToManeuverMeters = distance, speedKmh = null,
        )
        repeat(41) { g.poll(light = false, navSample = far(1_200)) } // the first verdict and the dwell after it (10 s)
        g.poll(light = false, navSample = far(1_450))
        assertEquals("reduced profile", 2, g.profileFps)
        assertEquals(2, g.fps)

        val on = g.poll(light = true, navSample = far(1_450))
        assertEquals("the menu leaves the reduced profile's 2 fps behind", 8, g.fps)
        assertEquals(listOf(125L), on.pace())
        assertTrue("and its half bitrate: $on", on.any { it is Action.SetBitrate && it.bps == g.activeBitrateBps })

        var back = emptyList<Action>()
        repeat(4) { back = back + g.poll(light = false, navSample = far(1_450)) }
        assertEquals("the reduced profile comes back when the menu is left", 2, g.fps)
        assertEquals(listOf(500L), back.pace())
        assertTrue("with its own bitrate: $back", back.any { it is Action.SetBitrate && it.bps < g.activeBitrateBps })
        assertTrue("and never a new encoder", (on + back).none { it is Action.Rebuild })
    }

    @Test
    fun `the heat is judged against the profile's pace, never the faster one that the menu raised`() {
        // ThermalCeiling.allows(status, 8, 4) is false at any temperature: judged against the boosted pace the faster pace would
        // switch itself off at the next poll and on again at the next, swinging the pace every 250 ms.
        val g = governor(lightFps = 8)
        g.poll(light = true)
        assertEquals(8, g.fps)
        assertEquals(4, g.profileFps)
        assertTrue(com.reau.app.stream.ThermalCeiling.allows(status = 0, fps = g.profileFps, ceiling = 4))
        assertFalse("the boosted pace is not what the ceiling allows", com.reau.app.stream.ThermalCeiling.allows(status = 0, fps = g.fps, ceiling = 4))
    }

    @Test
    fun `a window at the boosted pace is read against the boosted pace, and the stream's full pace stays 4`() {
        val g = governor(lightFps = 8)
        g.poll(light = true)
        // Two frames in each 250 ms poll: 8 fps sent and drawn: every window is a whole one at the boosted pace.
        val all = (1..120).flatMap { g.poll(light = true, frames = 2) }
        val lines = all.filterIsInstance<Action.Log>().map { it.line }.filter { it.startsWith("video fps") }
        assertTrue("windows at the boosted pace: $lines", lines.isNotEmpty() && lines.all { "target=8" in it && "full=true" in it })
        assertTrue("the full pace stays 4, and the window is judged at 8: $lines", lines.all { "auto=4fps [ok]" in it })
        assertTrue("and the encoder stays as built", all.none { it is Action.Rebuild } && g.encoderFps == 4 && g.profileFps == 4)
    }
}

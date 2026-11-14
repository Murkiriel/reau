package com.reau.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One `resumo …` line when the projection is switched off:
 * how long, how long streaming, drops of the dash and of Android Auto, failed tries to connect the dash, watchdogs, frames sent, mean and lowest fps,
 * the longest gap with no frame, the hottest thermal status, the battery spent and the rider's marks.
 */
class RideSummaryTest {
    @Test
    fun `a projection with a drop each side and a watchdog is summed up in one line`() {
        val s = RideSummary()
        s.start(nowMs = 0, batteryPct = 82)
        s.streaming(on = true, nowMs = 5_000)
        // 10 frames a second for 10 s (polls every 100 ms), then 2 s with none, then 5 frames a second for 5 s.
        var t = 5_000L
        repeat(100) { t += 100; s.frames(1, t) }
        t += 2_000
        repeat(25) { t += 200; s.frames(1, t) }
        s.dashDropped()
        s.aaDropped()
        s.watchdog("dash-rx")
        s.device(thermal = 0, batteryPct = 80)
        s.device(thermal = 2, batteryPct = 78)
        s.device(thermal = 1, batteryPct = 75)
        s.streaming(on = false, nowMs = t)
        assertEquals(
            "resumo duration=22s streaming=17s drops dash=1 aa=1 retries dash=0 watchdogs=1 (dash-rx=1) frames=125 fps avg=7.4 " +
                "min=3.0 gap max=2.2s thermal max=moderate battery 82→75% (-7) marks=2 buttons unknown=0 maneuvers unknown=0 no image=0s",
            s.line(nowMs = 22_000, marks = 2),
        )
    }

    /** A ride once said `drops dash=16` for two drops: the other 14 were tries to get the dash back that failed. */
    @Test
    fun `failed tries to connect the dash are told apart from the drops of a dash that was connected`() {
        val s = RideSummary()
        s.start(nowMs = 0, batteryPct = null)
        s.dashDropped()
        repeat(14) { s.dashTryFailed() }
        s.dashDropped()
        val line = s.line(nowMs = 1_000, marks = 0)
        assertEquals(true, line.contains(" drops dash=2 aa=0 retries dash=14 "))
    }

    @Test
    fun `a projection that never streamed says so`() {
        val s = RideSummary()
        s.start(nowMs = 0, batteryPct = null)
        assertEquals(
            "resumo duration=1m05s streaming=0s drops dash=0 aa=0 retries dash=0 watchdogs=0 frames=0 fps avg=- min=- gap max=- " +
                "thermal max=? battery ? marks=0 buttons unknown=0 maneuvers unknown=0 no image=0s",
            s.line(nowMs = 65_000, marks = 0),
        )
    }

    @Test
    fun `streaming still on when switched off counts up to then, and watchdogs are told by name`() {
        val s = RideSummary()
        s.start(nowMs = 0, batteryPct = 50)
        s.streaming(on = true, nowMs = 1_000)
        s.watchdog("decoder")
        s.watchdog("dash-rx")
        s.watchdog("decoder")
        val line = s.line(nowMs = 4_000, marks = 0)
        assertEquals(true, line.contains(" streaming=3s "))
        assertEquals(true, line.contains(" watchdogs=3 (dash-rx=1 decoder=2) "))
        assertEquals(true, line.contains(" min=- ")) // no whole 5 s window yet
    }

    // ── the picture dark on the dash: from the last frame before the drop to the first after the return ──

    private fun streamingSummary(): RideSummary {
        val s = RideSummary()
        s.start(nowMs = 0, batteryPct = null)
        s.streaming(on = true, nowMs = 1_000)
        for (t in 1_100L..10_000L step 100) s.frames(1, t)
        return s
    }

    @Test
    fun `two interruptions are summed and the longest kept, each from the last frame to the first one back`() {
        val s = streamingSummary()
        s.dashDropped(nowMs = 10_400)               // the last frame went out at 10 000
        s.streaming(on = false, nowMs = 10_450)
        s.streaming(on = true, nowMs = 70_000)
        s.frames(1, 78_000)                         // dark 68 s
        for (t in 78_100L..90_000L step 100) s.frames(1, t)
        s.dashDropped(nowMs = 90_300)               // the last frame at 90 000
        s.streaming(on = false, nowMs = 90_350)
        s.streaming(on = true, nowMs = 100_000)
        s.frames(1, 104_000)                        // dark 14 s
        val line = s.line(nowMs = 104_500, marks = 0)
        assertTrue(line, " dark=2/1m22s (max 1m08s) " in line)
        assertTrue(line, "gap max=" in line && line.indexOf("gap max=") < line.indexOf("dark="))
    }

    @Test
    fun `an interruption still going on counts up to now`() {
        val s = streamingSummary()
        s.dashDropped(nowMs = 10_400)
        val line = s.line(nowMs = 40_000, marks = 0)
        assertTrue(line, " dark=1/30s (max 30s) " in line)
    }

    @Test
    fun `frames that go out between the drop and the video stopping do not end the interruption`() {
        val s = streamingSummary()
        s.dashDropped(nowMs = 10_400)
        s.frames(1, 10_450)                         // the loop was not stopped yet
        s.streaming(on = false, nowMs = 10_500)
        s.streaming(on = true, nowMs = 50_000)
        s.frames(1, 50_200)
        assertTrue(s.line(nowMs = 51_000, marks = 0), " dark=1/40s (max 40s) " in s.line(nowMs = 51_000, marks = 0))
    }

    @Test
    fun `no interruption writes nothing and a drop before any frame is none`() {
        val none = streamingSummary()
        assertEquals(false, "dark=" in none.line(nowMs = 11_000, marks = 0))
        val early = RideSummary()
        early.start(nowMs = 0, batteryPct = null)
        early.dashDropped(nowMs = 500)
        early.streaming(on = true, nowMs = 5_000)
        early.frames(1, 5_100)
        assertEquals(false, "dark=" in early.line(nowMs = 6_000, marks = 0))
    }

    // ── what the line ends with since 11/11/2026: unknown buttons, unknown maneuvers, the time without the picture ──

    private fun fresh() = RideSummary().also { it.start(nowMs = 0, batteryPct = null) }

    @Test
    fun `the button codes nobody explained are counted by code, in hex, and the line says how many in all`() {
        val s = fresh()
        s.unknownButton(0x31)
        s.unknownButton(0x20)
        s.unknownButton(0x31)
        assertTrue(s.line(nowMs = 1_000, marks = 0), s.line(nowMs = 1_000, marks = 0).contains(" buttons unknown=3 (0x20=1 0x31=2) "))
    }

    @Test
    fun `a maneuver number with no glyph counts once for the stretch it stays, and again when it comes back`() {
        val s = fresh()
        repeat(30) { s.maneuver(place = 0, type = 45, known = false) } // 30 seconds of the same number
        s.maneuver(place = 0, type = 7, known = true)
        s.maneuver(place = 0, type = 45, known = false)
        s.maneuver(place = 0, type = 0, known = false)
        val line = s.line(nowMs = 1_000, marks = 0)
        assertTrue(line, " maneuvers unknown=3 (0=1 45=2) " in line)
    }

    @Test
    fun `the maneuver now and the one after are followed apart, so one number in both places counts in each`() {
        val s = fresh()
        s.maneuver(place = 0, type = 45, known = false)
        s.maneuver(place = 1, type = 45, known = false)
        assertTrue(s.line(nowMs = 1_000, marks = 0), " maneuvers unknown=2 (45=2) " in s.line(nowMs = 1_000, marks = 0))
    }

    @Test
    fun `no maneuver yet, and a known one, are not unknown`() {
        val s = fresh()
        s.maneuver(place = 0, type = -1, known = false)
        s.maneuver(place = 0, type = 8, known = true)
        assertTrue(s.line(nowMs = 1_000, marks = 0), " maneuvers unknown=0 no image=" in s.line(nowMs = 1_000, marks = 0))
    }

    @Test
    fun `the time without the picture is the one given, in seconds and minutes`() {
        val s = fresh()
        assertTrue(s.line(nowMs = 1_000, marks = 0, noImageMs = 98_300), s.line(nowMs = 1_000, marks = 0, noImageMs = 98_300).endsWith(" no image=1m38s"))
        assertTrue(s.line(nowMs = 1_000, marks = 0, noImageMs = 20_400).endsWith(" no image=20s"))
    }

    /** The old fields are where they were and read as before: what the new ones add is after them, and the reader finds them by name. */
    @Test
    fun `the fields that were there keep their place and the new ones come after the marks`() {
        val line = fresh().also { it.unknownButton(0x20) }.line(nowMs = 65_000, marks = 2, noImageMs = 5_000)
        val old = "resumo duration=1m05s streaming=0s drops dash=0 aa=0 retries dash=0 watchdogs=0 frames=0 fps avg=- min=- gap max=- " +
            "thermal max=? battery ? marks=2"
        assertEquals("$old buttons unknown=1 (0x20=1) maneuvers unknown=0 no image=5s", line)
    }
}

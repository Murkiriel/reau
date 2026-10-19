package com.reau.app.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoFpsControllerTest {

    private var now = 0L

    /** A window at the controller's current target where the dash decodes [capacity] fps at most. */
    private fun AutoFpsController.feed(
        capacity: Double = 100.0,
        sentCapacity: Double = 100.0,
        kbps: Double = 600.0,
        allActive: Boolean = true,
        thermalOk: Boolean = true,
        decodedFps: Double? = null,
    ): AutoFpsController.Decision {
        now += 5_000
        val target = currentFps
        val sent = minOf(target.toDouble(), sentCapacity)
        val decoded = decodedFps ?: minOf(sent, capacity)
        return onWindow(AutoFpsController.Window(target, sent, decoded, kbps, allActive, thermalOk), now)
    }

    private fun AutoFpsController.run(windows: Int, capacity: Double = 100.0) = repeat(windows) { feed(capacity) }

    @Test
    fun `starts at the given fps, snapped to even`() {
        assertEquals(8, AutoFpsController(8).currentFps)
        assertEquals(6, AutoFpsController(7).currentFps)
        assertEquals(30, AutoFpsController(99).currentFps)
    }

    @Test
    fun `first window after start only settles`() {
        val c = AutoFpsController(8)
        val d = c.feed()
        assertFalse(d.changed)
        assertTrue(d.reason.contains("settling"))
    }

    @Test
    fun `steps up by 2 after three healthy windows`() {
        val c = AutoFpsController(8)
        c.feed() // settle
        assertFalse(c.feed().changed)
        assertFalse(c.feed().changed)
        val d = c.feed()
        assertTrue(d.changed)
        assertEquals(10, d.fps)
        assertEquals(8, c.stableFps)
    }

    @Test
    fun `climbs all the way to 30 when the dash keeps up, and holds there`() {
        val c = AutoFpsController(8)
        c.run(200)
        assertEquals(30, c.currentFps)
        assertEquals(30, c.stableFps)
    }

    /** Share of the next [windows] windows spent at [fps] — the controller periodically retries
     *  the level above once its backoff expires, so "settled" means "almost always there". */
    private fun AutoFpsController.shareAt(fps: Int, windows: Int, capacity: Double = 100.0, sentCapacity: Double = 100.0): Double {
        var at = 0
        repeat(windows) { if (feed(capacity, sentCapacity).fps == fps) at++ }
        return at.toDouble() / windows
    }

    @Test
    fun `the rider's scenario - dash only keeps up with 22 - settles at 22`() {
        val c = AutoFpsController(8)
        c.run(200, capacity = 22.4)
        assertEquals(22, c.stableFps)
        assertTrue(c.shareAt(22, 400, capacity = 22.4) > 0.95)
        assertEquals(22, c.stableFps)
    }

    @Test
    fun `settles on what the phone can send when the phone is the limit`() {
        val c = AutoFpsController(8)
        repeat(200) { c.feed(sentCapacity = 16.5) }
        assertEquals(16, c.stableFps)
        assertTrue(c.shareAt(16, 400, sentCapacity = 16.5) > 0.95)
    }

    @Test
    fun `two short windows drop straight to the delivered rate rounded down to even`() {
        val c = AutoFpsController(24)
        c.feed() // settle
        assertFalse(c.feed(capacity = 17.3).changed)
        val d = c.feed(capacity = 17.3)
        assertTrue(d.changed)
        assertEquals(16, d.fps)
    }

    @Test
    fun `a drop is always at least one step even if delivery was nearly enough`() {
        val c = AutoFpsController(20)
        c.feed()
        c.feed(capacity = 18.9) // below 95% of 20
        assertEquals(18, c.feed(capacity = 18.9).fps)
    }

    @Test
    fun `one short window alone does not drop`() {
        val c = AutoFpsController(20)
        c.feed()
        c.feed(capacity = 10.0)
        c.feed()
        assertEquals(20, c.currentFps)
    }

    @Test
    fun `a failed level is not retried until its backoff expires, then backoff doubles`() {
        val cap = 18.5 // enough for 18 (needs 17.1), not for 20 (needs 19)
        val c = AutoFpsController(20)
        c.feed()
        c.feed(capacity = cap); c.feed(capacity = cap) // 20 fails -> 18, blocked 120 s
        assertEquals(18, c.currentFps)
        val firstDrop = now
        while (now - firstDrop < 115_000) {
            c.feed(capacity = cap)
            assertEquals(18, c.currentFps)
        }
        assertTrue(c.feed(capacity = cap).let { it.changed && it.fps == 20 }) // 120 s: retried
        c.feed(capacity = cap) // settle
        c.feed(capacity = cap); c.feed(capacity = cap) // fails again -> blocked 240 s
        assertEquals(18, c.currentFps)
        val secondDrop = now
        while (now - secondDrop < 235_000) {
            c.feed(capacity = cap)
            assertEquals(18, c.currentFps)
        }
        assertEquals(20, c.feed(capacity = cap).fps)
    }

    @Test
    fun `while blocked the hold says why`() {
        val c = AutoFpsController(20)
        c.feed()
        c.feed(capacity = 18.5); c.feed(capacity = 18.5)
        c.feed(capacity = 18.5) // settle at 18
        repeat(2) { c.feed(capacity = 18.5) }
        assertTrue(c.feed(capacity = 18.5).reason.contains("failed recently"))
    }

    @Test
    fun `idle windows are never judged`() {
        val c = AutoFpsController(8)
        c.feed() // settle
        repeat(10) { assertFalse(c.feed(capacity = 1.0, allActive = false).changed) }
        assertEquals(8, c.currentFps)
    }

    @Test
    fun `thermal throttling counts as unhealthy`() {
        val c = AutoFpsController(12)
        c.feed()
        c.feed(thermalOk = false)
        assertEquals(10, c.feed(thermalOk = false).fps)
    }

    @Test
    fun `sustained bitrate above the ceiling counts as unhealthy`() {
        val c = AutoFpsController(12)
        c.feed()
        c.feed(kbps = 1_200.0)
        assertEquals(10, c.feed(kbps = 1_200.0).fps)
    }

    @Test
    fun `small VBR overshoot within tolerance is fine`() {
        val c = AutoFpsController(12)
        c.feed()
        repeat(3) { c.feed(kbps = 1_080.0) }
        assertEquals(14, c.currentFps)
    }

    @Test
    fun `never drops below 4 fps`() {
        val c = AutoFpsController(8)
        repeat(20) { c.feed(capacity = 0.5, sentCapacity = 0.5) }
        assertEquals(4, c.currentFps)
    }

    @Test
    fun `acks that track frames are trusted and dash shortfall drives the decision`() {
        val c = AutoFpsController(16)
        c.feed() // settle
        c.feed(capacity = 12.0)
        assertEquals(true, c.acksTrusted)
        assertEquals(12, c.feed(capacity = 12.0).fps)
    }

    @Test
    fun `acks that only arrive for keyframes are distrusted and ignored`() {
        val c = AutoFpsController(8)
        c.feed() // settle
        c.feed(decodedFps = 1.0)
        assertNull(c.acksTrusted)
        c.feed(decodedFps = 1.0)
        assertEquals(false, c.acksTrusted)
        repeat(3) { c.feed(decodedFps = 1.0) }
        assertTrue(c.currentFps > 8) // judged on the phone's own send rate only
    }

    @Test
    fun `no acks at all falls back to send-side judging`() {
        val c = AutoFpsController(8)
        c.feed()
        c.feed(decodedFps = 0.0); c.feed(decodedFps = 0.0)
        assertEquals(false, c.acksTrusted)
        assertEquals(8, c.currentFps)
    }

    @Test
    fun `summary reports the best stable level, per-level pass and fail counts and peaks`() {
        val c = AutoFpsController(20)
        c.feed() // settle, not judged
        repeat(3) { c.feed(capacity = 23.0, kbps = 700.0) } // 20 ok x3 -> 22
        c.feed(capacity = 23.0) // settle
        repeat(3) { c.feed(capacity = 23.0, kbps = 810.0) } // 22 ok x3 -> 24
        c.feed(capacity = 23.0) // settle
        repeat(2) { c.feed(capacity = 22.5, kbps = 900.0) } // 24 short x2 (needs 22.8) -> 22
        val s = c.summary()
        assertTrue(s, s.contains("best stable 22 fps"))
        assertTrue(s, s.contains("20:3/0"))
        assertTrue(s, s.contains("22:3/0"))
        assertTrue(s, s.contains("24:0/2"))
        assertTrue(s, s.contains("peak dash 22.5 fps"))
        assertTrue(s, s.contains("peak kbps 900"))
        assertEquals(8, c.judgedWindows)
    }

    @Test
    fun `summary before any judged window says so`() {
        assertTrue(AutoFpsController(8).summary().contains("no judged windows yet"))
    }

    @Test
    fun `a window measured at a different target is ignored`() {
        val c = AutoFpsController(8)
        now += 5_000
        val d = c.onWindow(AutoFpsController.Window(12, 12.0, 12.0, 500.0, allActive = true, thermalOk = true), now)
        assertFalse(d.changed)
        assertEquals(8, c.currentFps)
    }

    @Test
    fun `stableFps follows a drop below it`() {
        val c = AutoFpsController(10)
        c.run(4) // settle + 3 healthy: 10 stable, now trying 12
        assertEquals(10, c.stableFps)
        repeat(20) { c.feed(capacity = 7.0) } // everything above 6 now fails
        assertEquals(6, c.stableFps)
    }
}

package com.reau.app.util

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Durations are measured with a clock the network cannot set (audit of 20/10/2026, bug 3). */
class MonotonicClockTest {
    @Test
    fun `it is above zero and never goes back`() {
        var last = MonotonicClock.nowMs()
        assertTrue("0 stays free to mean never: $last", last > 0)
        repeat(1_000) {
            val now = MonotonicClock.nowMs()
            assertTrue("went back: $last then $now", now >= last)
            last = now
        }
    }

    @Test
    fun `it moves with real time`() {
        val before = MonotonicClock.nowMs()
        Thread.sleep(60)
        val after = MonotonicClock.nowMs()
        assertTrue("moved ${after - before} ms in 60 ms", after - before in 40..2_000)
    }

    private fun root(): File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }

    /**
     * The files whose timers decide that the dash is silent, that a Wi-Fi request is stuck, that a PIN is locked or that the
     * dash has been gone for 20 minutes measure with [MonotonicClock]. What stays on the wall clock is a time of day, not a
     * duration: the telemetry events' stamp.
     */
    @Test
    fun `the timers that decide failures do not read the wall clock`() {
        val main = File(root(), "app/src/main/java/com/reau/app")
        // LiveSides also reads the wall clock for two times of day (the exit history's age and the time zone offset); the joystick's
        // bounce, combo, double back and call silence are durations: a clock set back must not make a repeated tap look like a bounce.
        val files = listOf(
            "dash/DashSession.kt", "dash/DashWifiManager.kt",
            // What was taken out of the manager's file: the guard keeps covering the same code.
            "dash/WifiState.kt", "dash/ReconnectPace.kt", "dash/ScanEvidence.kt", "dash/DashSsid.kt", "dash/JoinRefusal.kt",
            "dash/DashScan.kt", "dash/JoinedWifi.kt", "dash/NetNotes.kt",
            "web/WebGuard.kt", "service/ProjectionController.kt", "service/LiveSides.kt",
        )
        val timesOfDay = listOf("TelemetryEvent(", "ExitHistory.newest(", "getOffset(")
        val offenders = files.flatMap { name ->
            File(main, name).readLines().withIndex()
                .filter { (_, line) -> "currentTimeMillis" in line && timesOfDay.none { it in line } }
                .map { (i, _) -> "$name:${i + 1}" }
        }
        assertTrue("measure durations with MonotonicClock: $offenders", offenders.isEmpty())
    }
}

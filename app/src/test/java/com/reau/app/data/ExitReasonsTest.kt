package com.reau.app.data

import android.app.ActivityManager.RunningAppProcessInfo
import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Why the app's processes ended before: what Android kept (`getHistoricalProcessExitReasons`, Android 11+) turned into
 * one ride-log line per exit, each told once, and the ANR or native-crash trace kept beside the ride log.
 */
class ExitReasonsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val at = stamp.parse("2026-10-03 10:22:17.184")!!.time

    private fun exit(
        reason: Int = ApplicationExitInfo.REASON_CRASH,
        timestampMs: Long = at,
        pid: Int = 4061,
        status: Int = 0,
        importance: Int = RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE,
        pssKb: Long = 0,
        rssKb: Long = 0,
        description: String? = "crash",
    ) = PastExit(timestampMs, "com.reau.app", pid, reason, status, importance, pssKb, rssKb, description)

    @Test
    fun `every reason Android has gets a name`() {
        val names = mapOf(
            ApplicationExitInfo.REASON_UNKNOWN to "unknown",
            ApplicationExitInfo.REASON_EXIT_SELF to "exit self",
            ApplicationExitInfo.REASON_SIGNALED to "signaled",
            ApplicationExitInfo.REASON_LOW_MEMORY to "low memory",
            ApplicationExitInfo.REASON_CRASH to "crash",
            ApplicationExitInfo.REASON_CRASH_NATIVE to "native crash",
            ApplicationExitInfo.REASON_ANR to "ANR",
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE to "initialization failure",
            ApplicationExitInfo.REASON_PERMISSION_CHANGE to "permission change",
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE to "excessive resource usage",
            ApplicationExitInfo.REASON_USER_REQUESTED to "user requested",
            ApplicationExitInfo.REASON_USER_STOPPED to "user stopped",
            ApplicationExitInfo.REASON_DEPENDENCY_DIED to "dependency died",
            ApplicationExitInfo.REASON_OTHER to "other",
            ApplicationExitInfo.REASON_FREEZER to "freezer",
            ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE to "package state change",
            ApplicationExitInfo.REASON_PACKAGE_UPDATED to "package updated",
        )
        names.forEach { (code, name) -> assertEquals("reason $code", name, ExitReasons.reasonName(code)) }
        assertEquals("a reason from a newer Android", "reason 99", ExitReasons.reasonName(99))
    }

    @Test
    fun `the importance the process had is named`() {
        val names = mapOf(
            RunningAppProcessInfo.IMPORTANCE_FOREGROUND to "foreground",
            RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE to "foreground service",
            RunningAppProcessInfo.IMPORTANCE_VISIBLE to "visible",
            RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE to "perceptible",
            RunningAppProcessInfo.IMPORTANCE_SERVICE to "service",
            RunningAppProcessInfo.IMPORTANCE_TOP_SLEEPING to "top sleeping",
            RunningAppProcessInfo.IMPORTANCE_CANT_SAVE_STATE to "can't save state",
            RunningAppProcessInfo.IMPORTANCE_CACHED to "cached",
            RunningAppProcessInfo.IMPORTANCE_GONE to "gone",
        )
        names.forEach { (code, name) -> assertEquals("importance $code", name, ExitReasons.importanceName(code)) }
        assertEquals("importance 777", ExitReasons.importanceName(777))
    }

    @Test
    fun `an exit is one line with the reason, the time, the process and what it was doing`() {
        assertEquals(
            "past exit: user requested (10) at 2026-10-03 10:22:17.184, process com.reau.app pid 4061, " +
                "was foreground service (125), \"stop com.reau.app due to finished inst\"",
            ExitReasons.describe(
                exit(reason = ApplicationExitInfo.REASON_USER_REQUESTED, description = "stop com.reau.app due to finished inst"),
            ),
        )
    }

    @Test
    fun `the status means the signal of a killed process and the code of one that exited`() {
        val killed = ExitReasons.describe(exit(reason = ApplicationExitInfo.REASON_SIGNALED, status = 9))
        assertTrue(killed, killed.contains("signaled (2) at ") && killed.contains(", signal 9,"))
        val exited = ExitReasons.describe(exit(reason = ApplicationExitInfo.REASON_EXIT_SELF, status = 3))
        assertTrue(exited, exited.contains(", exit code 3,"))
        val crashed = ExitReasons.describe(exit(status = 5))
        assertFalse("no status for other reasons: $crashed", crashed.contains("signal") || crashed.contains("exit code"))
    }

    @Test
    fun `memory is told in MB when Android measured it`() {
        val line = ExitReasons.describe(exit(pssKb = 52_224, rssKb = 243_712))
        assertTrue(line, line.contains(", pss 51 MB, rss 238 MB,"))
        // What the emulator's Android 16 hands for a force-stopped process: no pss, only rss.
        val rssOnly = ExitReasons.describe(exit(rssKb = 203_776))
        assertTrue(rssOnly, rssOnly.contains(", pss 0 MB, rss 199 MB,"))
        val none = ExitReasons.describe(exit())
        assertFalse("nothing when not measured: $none", none.contains("pss") || none.contains("rss"))
    }

    @Test
    fun `the description is kept on one short line, and left out when there is none`() {
        val long = ExitReasons.describe(exit(description = "first line\nsecond " + "x".repeat(300)))
        assertTrue(long, long.contains("\"first line second xxx"))
        assertFalse(long, long.contains("\n"))
        assertTrue("cut: $long", long.endsWith("…\"") && long.length < 400)
        val none = ExitReasons.describe(exit(description = null))
        assertTrue(none, none.endsWith("was foreground service (125)"))
        // Android 16 hands an empty description for a process killed by a signal.
        val blank = ExitReasons.describe(exit(description = " "))
        assertTrue(blank, blank.endsWith("was foreground service (125)"))
    }

    @Test
    fun `the trace file is named on the line`() {
        val line = ExitReasons.describe(exit(reason = ApplicationExitInfo.REASON_ANR), traceFile = "anr-x.txt")
        assertTrue(line, line.endsWith(", trace in anr-x.txt"))
    }

    @Test
    fun `only ANRs and native crashes have a trace worth keeping`() {
        assertEquals("anr", ExitReasons.tracePrefix(ApplicationExitInfo.REASON_ANR))
        assertEquals("native-crash", ExitReasons.tracePrefix(ApplicationExitInfo.REASON_CRASH_NATIVE))
        assertNull(ExitReasons.tracePrefix(ApplicationExitInfo.REASON_CRASH))
        assertNull(ExitReasons.tracePrefix(ApplicationExitInfo.REASON_USER_REQUESTED))
    }

    /** Seven installs in an afternoon left seven "package updated" lines in one file. */
    @Test
    fun `exits for a package update in a row are one line with the count and the first and last time`() {
        val updates = (0 until 7).map { exit(reason = ApplicationExitInfo.REASON_PACKAGE_UPDATED, timestampMs = at + it * 60_000L, pid = 100 + it) }
        val lines = ExitReasons.describeRuns(updates)
        assertEquals(lines.toString(), 1, lines.size)
        assertTrue(lines[0], lines[0].startsWith("past exit: package updated (16) ×7 from 2026-10-03 10:22:17.184 to 2026-10-03 10:28:17.184, "))
        assertTrue("the last process is the one told: ${lines[0]}", "pid 106" in lines[0])
    }

    @Test
    fun `a single package update, and every other reason, stays a line of its own`() {
        val crash = ApplicationExitInfo.REASON_CRASH
        val update = ApplicationExitInfo.REASON_PACKAGE_UPDATED
        assertEquals(
            listOf(ExitReasons.describe(exit(reason = update, pid = 1))),
            ExitReasons.describeRuns(listOf(exit(reason = update, pid = 1))),
        )
        val crashes = listOf(exit(reason = crash, timestampMs = at, pid = 1), exit(reason = crash, timestampMs = at + 1, pid = 2))
        assertEquals("crashes keep their own pid and trace", crashes.map { ExitReasons.describe(it) }, ExitReasons.describeRuns(crashes))
        val mixed = listOf(
            exit(reason = update, timestampMs = at, pid = 1),
            exit(reason = update, timestampMs = at + 1, pid = 2),
            exit(reason = crash, timestampMs = at + 2, pid = 3),
            exit(reason = update, timestampMs = at + 3, pid = 4),
        )
        val lines = ExitReasons.describeRuns(mixed)
        assertEquals(lines.toString(), 3, lines.size)
        assertTrue(lines[0], "×2" in lines[0])
        assertTrue(lines[1], lines[1].startsWith("past exit: crash (4)"))
        assertEquals(ExitReasons.describe(mixed[3]), lines[2])
    }

    @Test
    fun `the trace of a collapsed run's last exit is named on its line`() {
        val a = exit(reason = ApplicationExitInfo.REASON_PACKAGE_UPDATED, pid = 1)
        val b = exit(reason = ApplicationExitInfo.REASON_PACKAGE_UPDATED, timestampMs = at + 1, pid = 2)
        assertTrue(ExitReasons.describeRuns(listOf(a, b)) { "anr-x.txt" }.single().endsWith(", trace in anr-x.txt"))
    }

    @Test
    fun `each exit is told once, oldest first`() {
        val dir = File(tmp.root, "diag")
        val book = ExitBook(dir)
        val a = exit(timestampMs = at, pid = 1)
        val b = exit(timestampMs = at + 1_000, pid = 2)
        // Android hands the newest first.
        assertEquals("nothing told yet: all, oldest first", listOf(a, b), book.unreported(listOf(b, a)))
        book.markReported(listOf(a, b))
        assertEquals("told once", emptyList<PastExit>(), ExitBook(dir).unreported(listOf(b, a)))
        val c = exit(timestampMs = at + 2_000, pid = 3)
        assertEquals("only the newer one", listOf(c), ExitBook(dir).unreported(listOf(c, b, a)))
    }

    @Test
    fun `marking nothing or older exits never moves the mark back`() {
        val dir = File(tmp.root, "diag")
        val book = ExitBook(dir)
        val new = exit(timestampMs = at + 5_000)
        book.markReported(listOf(new))
        book.markReported(emptyList())
        book.markReported(listOf(exit(timestampMs = at)))
        assertEquals(emptyList<PastExit>(), book.unreported(listOf(new, exit(timestampMs = at))))
    }

    @Test
    fun `at most the newest few are told at once`() {
        val exits = (1..30).map { exit(timestampMs = at + it * 1_000L, pid = it) }
        val told = ExitBook(File(tmp.root, "diag")).unreported(exits.reversed(), max = 10)
        assertEquals((21..30).toList(), told.map { it.pid })
    }

    @Test
    fun `a damaged mark is taken as nothing told`() {
        val dir = File(tmp.root, "diag").apply { mkdirs() }
        File(dir, "exit-reported").writeText("not a number")
        assertEquals(1, ExitBook(dir).unreported(listOf(exit())).size)
    }

    // The time limit turns a copy loop that never ends (the "no cap" mutation) into a failure instead of a hung run.
    @Test(timeout = 10_000)
    fun `a trace is kept beside the ride log, named by the exit, and cut when too long`() {
        val dir = File(tmp.root, "diag")
        val anr = exit(reason = ApplicationExitInfo.REASON_ANR, pid = 77)
        val file = ExitBook(dir).saveTrace(anr, ByteArrayInputStream("----- pid 77 -----\n".toByteArray()))
        assertNotNull(file)
        assertEquals("anr-20261003-102217-184-77.txt", file!!.name)
        assertEquals(dir, file.parentFile)
        assertEquals("----- pid 77 -----\n", file.readText())

        val big = ExitBook(dir).saveTrace(anr.copy(pid = 78), ByteArrayInputStream(ByteArray(5_000) { 'a'.code.toByte() }), capBytes = 1_000)
        val text = big!!.readText()
        assertTrue("the first bytes kept", text.startsWith("a".repeat(1_000)))
        assertTrue("cut and said so: ${text.takeLast(60)}", text.endsWith("\n… trace cut at 1000 bytes\n"))
    }

    @Test
    fun `no trace file for a reason without one`() {
        assertNull(ExitBook(File(tmp.root, "diag")).saveTrace(exit(), ByteArrayInputStream("x".toByteArray())))
    }

    // ── an install or a forced stop in the middle of a projection leaves Android Auto's server holding the session ──

    @Test
    fun `an install or a forced stop while projecting, minutes ago, can have left Android Auto hanging`() {
        val installed = exit(reason = ApplicationExitInfo.REASON_PACKAGE_UPDATED, importance = RunningAppProcessInfo.IMPORTANCE_FOREGROUND)
        assertTrue(ExitReasons.leftAndroidAutoHanging(installed, at + 13_000))
        assertTrue(ExitReasons.leftAndroidAutoHanging(exit(reason = ApplicationExitInfo.REASON_USER_REQUESTED), at + 60_000))
        assertTrue(ExitReasons.leftAndroidAutoHanging(exit(reason = ApplicationExitInfo.REASON_USER_STOPPED), at + 60_000))
    }

    @Test
    fun `a crash, an exit in the background, an old one or none do not count`() {
        val installed = exit(reason = ApplicationExitInfo.REASON_PACKAGE_UPDATED)
        assertFalse("a crash", ExitReasons.leftAndroidAutoHanging(exit(), at + 13_000))
        assertFalse("in the background", ExitReasons.leftAndroidAutoHanging(
            exit(reason = ApplicationExitInfo.REASON_PACKAGE_UPDATED, importance = RunningAppProcessInfo.IMPORTANCE_CACHED), at + 13_000))
        assertFalse("too long ago", ExitReasons.leftAndroidAutoHanging(installed, at + ExitReasons.HANGING_FOR_MS + 1))
        assertFalse("none", ExitReasons.leftAndroidAutoHanging(null, at))
    }
}

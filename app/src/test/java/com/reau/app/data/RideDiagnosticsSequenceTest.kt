package com.reau.app.data

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The order and the content of the ride log, through the whole life of a file: the process and the projection opening and closing, the rider's
 * deletion, the days kept, the lines other threads log while it goes on (item 22 of the 11/11/2026 list, the disk work taken out of the lock the video
 * threads log through). Uses only what the log offered before that change; the expected text below was produced by the log as it was then (one lock
 * around everything, the disk work inside it), and holds the same now. The clock is taken out of every line: it is the one thing that moves.
 */
class RideDiagnosticsSequenceTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var diag: File

    @Before
    fun setUp() {
        diag = tmp.newFolder("diag")
        RideDiagnostics.testOnlyUseDir(diag, today = { "20261111" })
        OwnLogcat.testOnlyUseDir(diag)
    }

    @After
    fun tearDown() {
        OwnLogcat.testOnlyReset()
        RideDiagnostics.testOnlyReset()
    }

    private fun rides() = diag.listFiles { f -> f.name.startsWith("ride-") }!!.sortedBy { it.lastModified() }

    /** The file's lines as the reader sees them: the time of day gone, the offset's number gone, and the header's app and phone gone. */
    private fun shape(file: File): List<String> = file.readLines().map {
        it.replace(Regex("""^\d\d:\d\d:\d\d\.\d{3}  """), "")
            .replace(Regex("""^ +"""), "_ ") // no offset: its blank
            .replace(Regex("""\+ *\d+ms"""), "+N ms")
            .replace(Regex("""(==== process start: \w+) — .*"""), "$1 ====")
            .replace(Regex("""(==== session start: \w+) — .*"""), "$1 ====")
    }

    @Test
    fun `the process, the projection, the deletion and the days kept read in this order, and the new file starts with what was said of the deletion`() {
        RideDiagnostics.startProcess("app")
        RideDiagnostics.log("ui", "before the projection")
        RideDiagnostics.start("projection")
        RideDiagnostics.log("projection", "during")
        RideDiagnostics.start("projection") // again: a marker only
        RideDiagnostics.stop("projection")
        RideDiagnostics.logAndWait("ui", "after the projection")
        val first = rides().single()
        assertEquals(
            listOf(
                "==== process start: app ====",
                "_ [ui] before the projection",
                "---- projeção ligada: projection ----",
                "+N ms  [projection] during",
                "---- projection (session already active) ----",
                "---- projeção desligada: projection ----",
                "_ [ui] after the projection",
            ),
            shape(first),
        )

        // The rider's deletion: a line another thread logs while the cleaning goes on is written after the new file's header, in order.
        val inside = CountDownLatch(1)
        val other = Thread {
            inside.await(5, TimeUnit.SECONDS)
            RideDiagnostics.log("other", "logged during the cleaning 1")
            RideDiagnostics.log("other", "logged during the cleaning 2")
        }
        other.start()
        val said = RideDiagnostics.clearAndReopen { d ->
            d.listFiles()?.forEach { it.delete() } // what the rider's deletion does: every file of the folder
            inside.countDown()
            Thread.sleep(150)
            "cleaned"
        }
        other.join(5_000)
        assertEquals("cleaned", said)
        RideDiagnostics.log("ui", "after the deletion")
        RideDiagnostics.applyKeepDays(30)
        RideDiagnostics.logAndWait("ui", "last")
        val second = rides().single()
        assertEquals(
            listOf(
                "==== the rider deleted the logs: cleaned ====",
                "_ [other] logged during the cleaning 1",
                "_ [other] logged during the cleaning 2",
                "_ [ui] after the deletion",
                "_ [ui] last",
            ),
            shape(second),
        )
    }
}

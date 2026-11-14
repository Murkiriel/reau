package com.reau.app.data

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The log lives as long as the process does. The projection marks itself on and off inside the same file, so what
 * happened before and after it is there too; a new file starts each day and at [RideDiagnostics.maxFileBytes].
 */
class RideDiagnosticsProcessTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var dir: File
    private var day = "20261004"

    @Before
    fun setUp() {
        dir = tmp.newFolder("diag")
        RideDiagnostics.testOnlyUseDir(dir, today = { day })
    }

    @After
    fun tearDown() = RideDiagnostics.testOnlyReset()

    private fun files() = dir.listFiles { f -> f.name.startsWith("ride-") }!!.sortedBy { it.name }

    @Test
    fun `the process file holds what came before the projection, the projection between its marks, and what came after`() {
        RideDiagnostics.startProcess("app")
        RideDiagnostics.log("ui", "before")
        RideDiagnostics.start("projection")
        assertTrue(RideDiagnostics.isSessionActive())
        RideDiagnostics.log("projection", "during")
        RideDiagnostics.stop("projection")
        assertFalse(RideDiagnostics.isSessionActive())
        RideDiagnostics.logAndWait("ui", "after")

        val text = files().single().readText()
        val order = listOf("process start: app", "[ui] before", "projeção ligada: projection", "[projection] during", "projeção desligada: projection", "[ui] after")
        val at = order.map { text.indexOf(it) }
        assertTrue("all there, in order: $at\n$text", at.all { it >= 0 } && at == at.sorted())
    }

    /**
     * Outside a projection the offset used to count from the process start and inside it from the projection's, so one
     * file read `+419443ms` and, a line later, `+42ms`. Now the offset belongs to the projection.
     */
    @Test
    fun `the offset counts from the projection's start and is not written outside one`() {
        val offset = Regex("""^\d\d:\d\d:\d\d\.\d{3}  (\+ *\d+ms)?""")
        RideDiagnostics.startProcess("app")
        RideDiagnostics.log("ui", "before")
        RideDiagnostics.start("projection")
        RideDiagnostics.log("projection", "during")
        RideDiagnostics.stop("projection")
        RideDiagnostics.logAndWait("ui", "after")

        val lines = files().single().readLines()
        fun offsetOf(text: String) = offset.find(lines.single { text in it })!!.groupValues[1]
        assertEquals("before: none", "", offsetOf("[ui] before"))
        assertTrue("during: from the projection's start", offsetOf("[projection] during").matches(Regex("""\+ *\d+ms""")))
        assertEquals("after: none", "", offsetOf("[ui] after"))
    }

    @Test
    fun `a second projection in the same file counts from its own start`() {
        RideDiagnostics.startProcess("app")
        RideDiagnostics.start("projection")
        Thread.sleep(120)
        RideDiagnostics.log("projection", "first one late")
        RideDiagnostics.stop("projection")
        RideDiagnostics.start("projection")
        RideDiagnostics.logAndWait("projection", "second one early")
        val lines = files().single().readLines()
        val ms = { text: String -> Regex("""\+ *(\d+)ms""").find(lines.single { text in it })!!.groupValues[1].toLong() }
        assertTrue("the first ran for a while: ${ms("first one late")}", ms("first one late") >= 100)
        assertTrue("the second starts again: ${ms("second one early")}", ms("second one early") < 100)
    }

    @Test
    fun `opening the file fits the folder under its ceiling and says what went, after the header`() {
        val old = File(dir, "logcat-20261001-080000.txt").apply { writeBytes(ByteArray(4 * 1024)); setLastModified(1_000) }
        val keep = File(dir, "crash-reported").apply { writeText("x") }
        RideDiagnostics.spaceCapBytes = 2 * 1024L
        RideDiagnostics.startProcess("app")
        // The tidying runs on its own thread; its notes come when it is done.
        RideDiagnostics.testOnlyAwaitTidy()
        RideDiagnostics.logAndWait("ui", "after")
        assertFalse("the old logcat went", old.exists())
        assertTrue("the mark stays", keep.exists())
        val lines = files().single().readLines()
        assertTrue("the header first: $lines", lines.first().contains("process start: app"))
        assertTrue("then what went: $lines", lines[1].contains("diag space: 1 file removed (4 KB)"))
    }

    @Test
    fun `the header says which APK wrote the log`() {
        // Without it, a log's APK had to be worked out from the "package updated" exits; the header says it.
        RideDiagnostics.startProcess("app")
        // The writes are asynchronous: wait for them, as the tests above do (reading at once read too early).
        RideDiagnostics.logAndWait("ui", "after")
        val header = files().single().readLines().first()
        val app = "app ${com.reau.app.BuildConfig.VERSION_NAME} (${com.reau.app.BuildConfig.VERSION_CODE}, ${com.reau.app.BuildConfig.GIT_COMMIT})"
        assertTrue("the header names the APK: $header", header.contains(app))
        assertTrue("the commit is a short hash or '?': ${com.reau.app.BuildConfig.GIT_COMMIT}",
            com.reau.app.BuildConfig.GIT_COMMIT.matches(Regex("[0-9a-f]{7,}|\\?")))
    }

    @Test
    fun `a new file starts when the file passes its size`() {
        RideDiagnostics.maxFileBytes = 300
        RideDiagnostics.startProcess("app")
        repeat(20) { RideDiagnostics.log("ui", "line $it with some text to fill the file up") }
        RideDiagnostics.logAndWait("ui", "last")
        val all = files()
        assertTrue("more than one file: ${all.map { it.name }}", all.size > 1)
        val continued = all.filter { it.readText().contains("continued from ride-") }
        assertTrue("a new file says where it came from", continued.isNotEmpty())
        assertTrue(all.any { it.readText().contains("[ui] last") })
    }

    /** Audit of 20/10/2026: a roll runs inside log(), and tidied the folder (list, sum, delete) on the thread that logged. */
    @Test
    fun `a roll inside log leaves the folder's tidying to its own thread, and the notes come when it is done`() {
        RideDiagnostics.maxFileBytes = 300
        RideDiagnostics.spaceCapBytes = 2 * 1024L
        RideDiagnostics.startProcess("app")
        RideDiagnostics.testOnlyAwaitTidy() // the opening's own tidying is done before the old file is made
        RideDiagnostics.logAndWait("ui", "first")
        val old = File(dir, "logcat-20261001-080000.txt").apply { writeBytes(ByteArray(4 * 1024)); setLastModified(1_000) }
        val release = RideDiagnostics.testOnlyHoldTidy()
        repeat(20) { RideDiagnostics.log("ui", "line $it with some text to fill the file up") }
        assertTrue("a new file was opened", files().size > 1)
        assertTrue("and the caller did not wait to tidy the folder", old.exists())
        release()
        RideDiagnostics.testOnlyAwaitTidy()
        RideDiagnostics.logAndWait("ui", "last")
        assertFalse("the old logcat went once the tidying ran", old.exists())
        assertTrue("and the file says so", files().any { f -> f.readText().contains("diag space:") })
    }

    /**
     * The Application's onCreate opens the process file; the rotation and the trim of the folder used to run there, sorting by
     * lastModified() every file of diag/. Now they run on the tidy thread, after the new file exists, and never take it.
     */
    @Test
    fun `opening the process file leaves the tidying to its own thread, and the new file is never the one it removes`() {
        RideDiagnostics.spaceCapBytes = 5 * 1024L
        val old = File(dir, "logcat-20261001-080000.txt").apply { writeBytes(ByteArray(4 * 1024)); setLastModified(1_000) }
        // More ride logs than are kept, all older than the file about to open, plus one far in the future (a clock that went back).
        val oldRides = (0 until RideDiagnostics.KEEP_FILES + 3).map { i ->
            File(dir, "ride-2026100$i.log").apply { writeBytes(ByteArray(64)); setLastModified(2_000L + i) }
        }
        val release = RideDiagnostics.testOnlyHoldTidy()
        RideDiagnostics.startProcess("app")
        val current = RideDiagnostics.currentFileName()!!
        assertTrue("the new file exists the moment startProcess returns", File(dir, current).exists())
        assertTrue("and the caller did not wait to tidy the folder", old.exists() && oldRides.all { it.exists() })
        // Even a new file that looks the oldest to lastModified() (a clock set back) is kept: the tidying is told which one is open.
        RideDiagnostics.logAndWait("ui", "first") // the header is written (it moves the file's time) before the time is set back
        File(dir, current).setLastModified(0)
        release()
        RideDiagnostics.testOnlyAwaitTidy()
        RideDiagnostics.logAndWait("ui", "last")
        assertTrue("the file just opened is still there", File(dir, current).exists())
        assertFalse("the old logcat went once the tidying ran", old.exists())
        assertEquals("the rides beyond the kept ones went", RideDiagnostics.KEEP_FILES, files().size)
        assertTrue(File(dir, current).readText().contains("[ui] last"))
        assertTrue("the new file says so", File(dir, current).readText().contains("diag space:"))
    }

    @Test
    fun `a new file starts on a new day`() {
        RideDiagnostics.startProcess("app")
        RideDiagnostics.logAndWait("ui", "late at night")
        val first = files().single().name
        day = "20261005"
        RideDiagnostics.logAndWait("ui", "after midnight")
        val all = files()
        assertEquals(2, all.size)
        assertTrue(all.first { it.name != first }.readText().contains("[ui] after midnight"))
    }

    @Test
    fun `without a process file a projection still opens its own file, as before`() {
        RideDiagnostics.start("projection")
        RideDiagnostics.logAndWait("projection", "alone")
        val text = files().single().readText()
        assertTrue(text, text.contains("session start: projection"))
        RideDiagnostics.stop("projection")
    }
}

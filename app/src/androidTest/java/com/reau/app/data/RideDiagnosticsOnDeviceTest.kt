package com.reau.app.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The log as it is pulled off the phone: one file per session under <external files>/diag, readable
 * with `adb pull /sdcard/Android/data/com.reau.app/files/diag`, holding what was logged before the session
 * opened as well as what came after.
 */
@RunWith(AndroidJUnit4::class)
class RideDiagnosticsOnDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var originalEnabled = true
    private lateinit var diagDir: File

    @Before
    fun setUp() {
        RideDiagnostics.init(context)
        originalEnabled = RideDiagnostics.enabled
        // The app opens a process-long file; these tests are about the per-session files of before.
        RideDiagnostics.stopProcess("test setup")
        RideDiagnostics.stop("test setup")
        RideDiagnostics.enabled = true
        diagDir = File(context.filesDir, "diag")
    }

    @After
    fun tearDown() {
        RideDiagnostics.stop("test teardown")
        RideDiagnostics.startProcess("test teardown") // back to how the app runs
        RideDiagnostics.enabled = originalEnabled
    }

    private fun awaitLines(file: File, wanted: Int): List<String> {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && file.readLines().size < wanted) Thread.sleep(20)
        return file.readLines()
    }

    // The app's internal storage, no longer Android/data (read with adb run-as).
    @Test
    fun theLogDirectoryIsTheAppsOwnInternalOne() {
        assertEquals(diagDir, RideDiagnostics.logDir())
        assertTrue(diagDir.path, diagDir.path.endsWith("/com.reau.app/files/diag") && "/Android/data/" !in diagDir.path)
        assertTrue(diagDir.isDirectory)
    }

    @Test
    fun aSessionFileHoldsTheHeaderWhatWasLoggedBeforeItAndWhatCameAfter() {
        RideDiagnostics.log("aa", "head unit server not answering")

        RideDiagnostics.start("connect")
        RideDiagnostics.log("stream", "first frame sent")
        val file = File(diagDir, RideDiagnostics.testOnlyActiveFileName()!!)
        RideDiagnostics.stop("done")

        val lines = awaitLines(file, 5)
        assertTrue(file.name, Regex("""ride-\d{8}-\d{6}(-\d+)?\.log""") /* the suffix: two files in one second */.matches(file.name))
        assertEquals(lines.joinToString("\n"), 5, lines.size)
        assertTrue(lines[0], lines[0].contains("==== session start: connect"))
        assertTrue(lines[1], lines[1].contains("pre-connect events"))
        assertTrue(lines[2], lines[2].endsWith("(pre-connect)  [aa] head unit server not answering"))
        assertTrue(lines[3], Regex(""".*\+\s*\d+ms {2}\[stream] first frame sent$""").matches(lines[3]))
        assertTrue(lines[4], lines[4].endsWith("==== session end: done ===="))
    }
}

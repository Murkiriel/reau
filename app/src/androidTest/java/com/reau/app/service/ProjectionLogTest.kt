package com.reau.app.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reau.app.data.RideDiagnostics
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The ride log on the phone (once found missing: the logger was never opened by the app, so a real
 * ride would leave no file to pull). Switching the projection on opens a log file in `<external files>/diag`, where
 * `adb pull` reaches it, with the projection's own lines in it; switching it off closes the file.
 */
@RunWith(AndroidJUnit4::class)
class ProjectionLogTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val originalSides = ProjectionService.sidesFactory
    private val diag = File(context.filesDir, "diag")

    @After
    fun tearDown() {
        ProjectionService.stop(context)
        await("stopped") { ProjectionService.projection.value.phase == Phase.STOPPED }
        ProjectionService.sidesFactory = originalSides
    }

    private fun await(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue(what, condition())
    }

    private fun newest(): File? = diag.listFiles { f -> f.name.startsWith("ride-") }?.maxByOrNull { it.lastModified() }

    /**
     * Rewritten for the process-long log: it checked that switching on opened a new file and
     * switching off closed it. Now the app's file lives as long as the process, and the projection marks itself on and
     * off inside it, with what came before and after in the same file.
     */
    @Test
    fun switchingOnAndOffMarksTheProjectionInsideTheProcessLog() {
        ProjectionService.sidesFactory = { _, _ -> ProjectionSides.None }
        RideDiagnostics.startProcess("test") // what REauApp does; nothing if the app's file is already open
        RideDiagnostics.log("ui", "before the projection")

        ProjectionService.start(context)
        await("running") { ProjectionService.projection.value.phase == Phase.RUNNING }
        await("the projection marked on") { RideDiagnostics.isSessionActive() }

        ProjectionService.stop(context)
        await("stopped") { ProjectionService.projection.value.phase == Phase.STOPPED }
        await("the projection marked off") { !RideDiagnostics.isSessionActive() }
        RideDiagnostics.logAndWait("ui", "after the projection")

        val text = newest()!!.readText()
        val order = listOf("[ui] before the projection", "projeção ligada: projection", "projection: foreground with wake and Wi-Fi locks",
            "projeção desligada: projection", "[ui] after the projection")
        val at = order.map { text.lastIndexOf(it) }
        assertTrue("all in one file, in order: $at in $text", at.all { it >= 0 } && at == at.sorted())
    }
}

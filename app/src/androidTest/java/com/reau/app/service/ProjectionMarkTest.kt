package com.reau.app.service

import android.app.Notification
import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reau.app.data.RideDiagnostics
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On the emulator: the "Marcar" button of the projection's
 * notification, pressed by the test, writes the numbered mark to the ride log and the notification says it did.
 */
@RunWith(AndroidJUnit4::class)
class ProjectionMarkTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val originalSides = ProjectionService.sidesFactory
    private val diag = File(context.filesDir, "diag")
    private val marker = "mark-test-${System.nanoTime()}"

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

    private fun notification(): Notification? = context.getSystemService(NotificationManager::class.java)
        .activeNotifications.firstOrNull { it.id == ProjectionNotification.ID }?.notification

    private fun logSinceMarker(): String {
        val newest = diag.listFiles { f -> f.name.startsWith("ride-") }?.maxByOrNull { it.lastModified() } ?: return ""
        return newest.readText().substringAfter(marker, "")
    }

    @Test
    fun theMarkButtonWritesANumberedLineAndTheNotificationSaysSo() {
        ProjectionService.sidesFactory = { _, _ -> ProjectionSides.None }
        RideDiagnostics.startProcess("test")
        RideDiagnostics.logAndWait("test", marker)
        ProjectionService.start(context)
        await("running") { ProjectionService.projection.value.phase == Phase.RUNNING }
        await("the notification") { notification() != null }
        Thread.sleep(2_000) // the headline settled: what changes the notification next is the mark itself

        val mark = notification()!!.actions?.firstOrNull { it.title.toString() == "Marcar" }
        assertNotNull("a Marcar action on the notification: ${notification()!!.actions?.map { it.title }}", mark)
        mark!!.actionIntent.send()
        await("the first mark in the log") { "[mark] MARCA DO PILOTO 1" in logSinceMarker() }
        mark.actionIntent.send()
        await("the second, numbered") { "[mark] MARCA DO PILOTO 2" in logSinceMarker() }
        await("the notification says it at once", timeoutMs = 2_000) {
            notification()?.extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.startsWith("Marca 2 gravada às") == true
        }
    }

    @Test
    fun aNewProjectionCountsFromOneAgain() {
        ProjectionService.sidesFactory = { _, _ -> ProjectionSides.None }
        RideDiagnostics.startProcess("test")
        ProjectionService.start(context)
        await("running") { ProjectionService.projection.value.phase == Phase.RUNNING }
        await("the notification") { notification() != null }
        notification()!!.actions!!.first { it.title.toString() == "Marcar" }.actionIntent.send()
        Thread.sleep(1_000) // the first projection's mark written
        ProjectionService.stop(context)
        await("stopped") { ProjectionService.projection.value.phase == Phase.STOPPED }

        RideDiagnostics.logAndWait("test", marker)
        ProjectionService.start(context)
        await("running again") { ProjectionService.projection.value.phase == Phase.RUNNING }
        await("the notification again") { notification()?.actions?.any { it.title.toString() == "Marcar" } == true }
        notification()!!.actions!!.first { it.title.toString() == "Marcar" }.actionIntent.send()
        await("the second projection's first mark is number one") { "[mark] MARCA DO PILOTO 1" in logSinceMarker() }
        assertTrue("and not two: ${logSinceMarker()}", "MARCA DO PILOTO 2" !in logSinceMarker())
    }
}

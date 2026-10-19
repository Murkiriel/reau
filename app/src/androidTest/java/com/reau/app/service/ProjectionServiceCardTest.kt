package com.reau.app.service

import android.app.ActivityManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reau.app.aa.AaLinkState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The service with its two kinds of sides: switched on for the card it builds the card's sides, switched
 * on the usual way it builds Android Auto's; one kind never gets the other's start.
 */
@RunWith(AndroidJUnit4::class)
class ProjectionServiceCardTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val calls = CopyOnWriteArrayList<String>()
    private val built = CopyOnWriteArrayList<String>()
    @Volatile private var report: ((ProjectionEvent) -> Unit)? = null
    private val originalSides = ProjectionService.sidesFactory
    private val originalCardSides = ProjectionService.cardSidesFactory

    private inner class RecordingSides(private val kind: String) : ProjectionSides {
        override fun startAndroidAuto() { calls += "$kind.startAndroidAuto" }
        override fun stopAndroidAuto() { calls += "$kind.stopAndroidAuto" }
        override fun startDash() { calls += "$kind.startDash" }
        override fun stopDash() { calls += "$kind.stopDash" }
        override fun startVideo() { calls += "$kind.startVideo" }
        override fun stopVideo() { calls += "$kind.stopVideo" }
        override fun release() { calls += "$kind.release" }
    }

    @Before
    fun setUp() {
        ProjectionService.sidesFactory = { _, toMachine ->
            built += "aa"
            report = toMachine
            RecordingSides("aa")
        }
        ProjectionService.cardSidesFactory = { _, toMachine ->
            built += "card"
            report = toMachine
            RecordingSides("card")
        }
    }

    @After
    fun tearDown() {
        ProjectionService.stop(context)
        await("stopped after the test") { !serviceRunning() && ProjectionService.projection.value.phase == Phase.STOPPED }
        ProjectionService.sidesFactory = originalSides
        ProjectionService.cardSidesFactory = originalCardSides
    }

    @Suppress("DEPRECATION") // still answers for the app's own services
    private fun serviceRunning(): Boolean =
        context.getSystemService(ActivityManager::class.java).getRunningServices(50)
            .any { it.service.className == ProjectionService::class.java.name }

    private fun await(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue("$what (built: $built, calls: $calls, projection: ${ProjectionService.projection.value})", condition())
    }

    @Test
    fun switchedOnForTheCardItBuildsTheCardsSidesOnly() {
        ProjectionService.startCard(context)
        await("running the card") { ProjectionService.projection.value.phase == Phase.RUNNING && ProjectionService.projection.value.card }
        await("both card sides started") { calls.toList() == listOf("card.startAndroidAuto", "card.startDash") }
        assertEquals(listOf("card"), built.toList())

        report!!(ProjectionEvent.AndroidAutoChanged(AaLinkState.ACTIVE))
        report!!(ProjectionEvent.DashChanged(DashLink.READY))
        await("the card streaming") { ProjectionService.projection.value.headline == Headline.CARD }
    }

    @Test
    fun switchedOnTheUsualWayItBuildsAndroidAutosSides() {
        ProjectionService.start(context)
        await("running") { ProjectionService.projection.value.phase == Phase.RUNNING }
        // The dash is started once Android Auto's session is up.
        report!!(ProjectionEvent.AndroidAutoChanged(AaLinkState.ACTIVE))
        await("both sides started") { calls.toList() == listOf("aa.startAndroidAuto", "aa.startDash") }
        assertEquals(listOf("aa"), built.toList())
        assertFalse(ProjectionService.projection.value.card)
    }

    @Test
    fun theCardAfterAProjectionGetsItsOwnSidesAndTheOldOnesAreLetGo() {
        ProjectionService.start(context)
        await("running") { ProjectionService.projection.value.phase == Phase.RUNNING }
        ProjectionService.stop(context)
        // At once, while the service may still be there: the next start must not reuse Android Auto's sides.
        ProjectionService.startCard(context)

        await("running the card") { ProjectionService.projection.value.phase == Phase.RUNNING && ProjectionService.projection.value.card }
        await("the card's sides started") { "card.startAndroidAuto" in calls && "card.startDash" in calls }
        assertEquals("built in order", listOf("aa", "card"), built.toList())
        assertTrue("Android Auto's sides let go before the card's started: $calls", calls.indexOf("aa.release") in 0 until calls.indexOf("card.startAndroidAuto"))
        assertEquals("nothing started twice on Android Auto's sides", 1, calls.count { it == "aa.startAndroidAuto" })
    }

    @Test
    fun whatTheOldSidesSayAfterTheSwitchNoLongerCounts() {
        ProjectionService.start(context)
        await("running") { ProjectionService.projection.value.phase == Phase.RUNNING }
        val oldReport = report!!
        ProjectionService.stop(context)
        ProjectionService.startCard(context)
        await("running the card") { ProjectionService.projection.value.phase == Phase.RUNNING && ProjectionService.projection.value.card }
        report!!(ProjectionEvent.AndroidAutoChanged(AaLinkState.ACTIVE))
        await("the card up") { ProjectionService.projection.value.androidAuto == AaLinkState.ACTIVE }

        // Android Auto's receiver, already let go, saying its server is off.
        oldReport(ProjectionEvent.AndroidAutoChanged(AaLinkState.SERVER_OFF))
        Thread.sleep(500)
        assertEquals(AaLinkState.ACTIVE, ProjectionService.projection.value.androidAuto)
    }

    @Test
    fun aReportAfterTheServiceWentIsDroppedQuietly() {
        ProjectionService.start(context)
        await("running") { ProjectionService.projection.value.phase == Phase.RUNNING }
        val oldReport = report!!
        ProjectionService.stop(context)
        await("the service gone") { !serviceRunning() }

        // On the phone this comes from the receiver's own thread: it must not blow up there.
        oldReport(ProjectionEvent.AndroidAutoChanged(AaLinkState.DROPPED))
        assertEquals(Phase.STOPPED, ProjectionService.projection.value.phase)
    }

    @Test
    fun startingTheCardTwiceBuildsItsSidesOnce() {
        ProjectionService.startCard(context)
        ProjectionService.startCard(context)
        await("running the card") { ProjectionService.projection.value.phase == Phase.RUNNING }
        Thread.sleep(500)
        assertEquals(listOf("card"), built.toList())
        assertEquals(listOf("card.startAndroidAuto", "card.startDash"), calls.toList())
    }
}

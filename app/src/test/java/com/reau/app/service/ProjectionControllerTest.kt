package com.reau.app.service

import com.reau.app.aa.AaLinkState
import com.reau.app.service.ProjectionEvent.AndroidAutoChanged
import com.reau.app.service.ProjectionEvent.BikeNetworkAvailable
import com.reau.app.service.ProjectionEvent.DashChanged
import com.reau.app.service.ProjectionEvent.Start
import com.reau.app.service.ProjectionEvent.StartCard
import com.reau.app.service.ProjectionEvent.Stop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** The machine's decisions carried out: one event at a time, each effect handed to the port that does it. */
class ProjectionControllerTest {
    private val calls = mutableListOf<String>()
    private val log = mutableListOf<String>()
    private val changes = mutableListOf<Projection>()
    private val scheduled = mutableListOf<Pair<Long, () -> Unit>>()

    private open inner class Recording : ProjectionController.Ports {
        var prepareAnswer: String? = null
        override fun prepare(): String? { calls += "prepare"; return prepareAnswer }
        override fun release() { calls += "release" }
        override fun startAndroidAuto() { calls += "startAndroidAuto" }
        override fun stopAndroidAuto() { calls += "stopAndroidAuto" }
        override fun startDash() { calls += "startDash" }
        override fun stopDash() { calls += "stopDash" }
        override fun startVideo() { calls += "startVideo" }
        override fun stopVideo() { calls += "stopVideo" }
        override fun schedule(delayMs: Long, action: () -> Unit): () -> Unit {
            calls += "schedule $delayMs"
            scheduled += delayMs to action
            return { calls += "cancel $delayMs" }
        }
    }

    private fun controller(ports: ProjectionController.Ports = Recording()) =
        ProjectionController(ports, { log += it }, { changes += it })

    @Test
    fun `switching on prepares, then starts Android Auto, and the dash once it is up`() {
        // The dash waits for Android Auto's session (ProjectionMachineAaFirstTest).
        val c = controller()
        c.submit(Start)
        assertEquals(listOf("prepare", "startAndroidAuto"), calls)
        assertEquals(Phase.RUNNING, c.state.phase)
        c.submit(AndroidAutoChanged(AaLinkState.ACTIVE))
        assertEquals(listOf("prepare", "startAndroidAuto", "startDash"), calls)
    }

    @Test
    fun `a preparation that says no gives back what it took and the reason stays`() {
        val ports = Recording().apply { prepareAnswer = "no wake lock" }
        val c = controller(ports)
        c.submit(Start)
        assertEquals(listOf("prepare", "release"), calls)
        assertEquals(Phase.STOPPED, c.state.phase)
        assertEquals("no wake lock", c.state.problem)
    }

    /**
     * Sides whose compositor did not start say so at the preparation
     * (ProjectionService's prepare reads [ProjectionSides.videoReady]); the start ends stopped with that problem and
     * neither side is connected, so the projection can never read as streaming with nothing to stream.
     */
    @Test
    fun `sides with no compositor refuse the start with their reason, and nothing is connected`() {
        val ports = Recording().apply { prepareAnswer = ProjectionSides.COMPOSITOR_NOT_STARTED }
        val c = controller(ports)
        c.submit(Start)
        assertEquals(listOf("prepare", "release"), calls)
        assertEquals(Phase.STOPPED, c.state.phase)
        assertEquals(ProjectionSides.COMPOSITOR_NOT_STARTED, c.state.problem)
        assertEquals(Headline.PROBLEM, c.state.headline)
        assertTrue("never streaming", changes.none { it.streaming })
    }

    @Test
    fun `sides can make video unless they say otherwise`() {
        assertTrue(ProjectionSides.None.videoReady)
        val noCompositor = object : ProjectionSides by ProjectionSides.None {
            override val videoReady: Boolean get() = false
        }
        assertTrue(!noCompositor.videoReady)
    }

    @Test
    fun `a preparation that throws counts as one that said no`() {
        val c = controller(object : Recording() {
            override fun prepare(): String? = throw IllegalStateException("not allowed now")
        })
        c.submit(Start)
        assertEquals(listOf("release"), calls)
        assertEquals("IllegalStateException: not allowed now", c.state.problem)
    }

    @Test
    fun `every change of state is reported once, and nothing when nothing changed`() {
        val c = controller()
        c.submit(Start)
        c.submit(Start)
        c.submit(AndroidAutoChanged(AaLinkState.ACTIVE))
        c.submit(AndroidAutoChanged(AaLinkState.ACTIVE))
        assertEquals(listOf(Phase.PREPARING, Phase.RUNNING, Phase.RUNNING), changes.map { it.phase })
        assertEquals(AaLinkState.ACTIVE, changes.last().androidAuto)
    }

    /** The machine gets the clock's time, and each retry says its wait in the ride log. */
    @Test
    fun `the clock reaches the machine and every retry is logged with its wait`() {
        var now = 0L
        val c = ProjectionController(Recording(), { log += it }, { changes += it }, clock = { now })
        c.submit(Start)
        c.submit(AndroidAutoChanged(AaLinkState.ACTIVE))
        c.submit(DashChanged(DashLink.DROPPED))
        assertEquals("schedule 2000", calls.last())
        assertTrue("logged: $log", log.contains("projection: the dash is tried again in 2s"))
        scheduled.removeAt(0).second()
        now = 6 * 60_000L
        c.submit(DashChanged(DashLink.DROPPED))
        assertEquals("gone six minutes: once a minute", "schedule 60000", calls.last())
        assertTrue("logged: $log", log.contains("projection: the dash is tried again in 60s"))
    }

    @Test
    fun `a retry asked for by the machine is scheduled and, when due, connects the dash again`() {
        val c = controller()
        // The dash waits for Android Auto's session (ProjectionMachineAaFirstTest).
        c.submit(Start)
        c.submit(AndroidAutoChanged(AaLinkState.ACTIVE))
        c.submit(DashChanged(DashLink.DROPPED))
        assertEquals("schedule 2000", calls.last())

        calls.clear()
        scheduled.single().second()
        assertEquals(listOf("startDash"), calls)
        assertEquals(DashLink.CONNECTING, c.state.dash)
    }

    /**
     * A ride log: `the dash is tried again in 60s` at 22:05:05, `bike network available` at 22:05:12, and the
     * dash only tried at 22:06:05, when the minute ended.
     */
    @Test
    fun `the bike's Wi-Fi coming back cuts the wait, and the dash is tried at once, not when the minute ends`() {
        var now = 0L
        val c = ProjectionController(Recording(), { log += it }, { changes += it }, clock = { now })
        c.submit(Start)
        c.submit(AndroidAutoChanged(AaLinkState.ACTIVE))
        c.submit(DashChanged(DashLink.DROPPED))
        scheduled.removeAt(0).second()
        now = 6 * 60_000L
        c.submit(DashChanged(DashLink.DROPPED))
        assertEquals("schedule 60000", calls.last())
        val minute = scheduled.single().second

        calls.clear()
        now += 7_000L
        c.submit(BikeNetworkAvailable)
        assertEquals("the minute is called off and the dash is started now", listOf("cancel 60000", "startDash"), calls)
        assertEquals(DashLink.CONNECTING, c.state.dash)
        assertTrue("logged: $log", log.any { "Wi-Fi is back" in it })

        // Were the called-off minute to run all the same, it finds the dash being tried and does nothing.
        calls.clear()
        minute()
        assertTrue("got $calls", calls.isEmpty())
        assertEquals(DashLink.CONNECTING, c.state.dash)

        // The try fails: the waits start over from 2 s.
        c.submit(DashChanged(DashLink.DROPPED))
        assertEquals("schedule 2000", calls.last())
    }

    @Test
    fun `a minute called off that runs all the same does not cut the wait of a later drop`() {
        var now = 0L
        val c = ProjectionController(Recording(), { log += it }, { changes += it }, clock = { now })
        c.submit(Start)
        c.submit(AndroidAutoChanged(AaLinkState.ACTIVE))
        c.submit(DashChanged(DashLink.DROPPED))
        val first = scheduled.single().second
        c.submit(BikeNetworkAvailable)
        c.submit(DashChanged(DashLink.DROPPED)) // the early try failed: a new wait is armed
        calls.clear()
        first()
        assertTrue("the old wait is dead: $calls", calls.isEmpty())
        assertEquals(DashLink.DROPPED, c.state.dash)
        scheduled.last().second()
        assertEquals(listOf("startDash"), calls)
    }

    @Test
    fun `the bike's Wi-Fi with no retry waiting does nothing`() {
        val c = controller()
        c.submit(Start)
        c.submit(AndroidAutoChanged(AaLinkState.ACTIVE))
        calls.clear()
        c.submit(BikeNetworkAvailable) // the dash is being connected
        assertTrue("got $calls", calls.isEmpty())
        c.submit(DashChanged(DashLink.READY))
        c.submit(BikeNetworkAvailable)
        assertTrue("got $calls", calls.none { it == "startDash" || it.startsWith("cancel") })
    }

    @Test
    fun `a dash in the scan and refusing is tried again at most every 10 s, one gone keeps the machine's pace`() {
        assertEquals(10_000L, ProjectionController.retryWait(30_000L, dashVisible = true))
        assertEquals(10_000L, ProjectionController.retryWait(16_000L, dashVisible = true))
        assertEquals("the short waits stay", 4_000L, ProjectionController.retryWait(4_000L, dashVisible = true))
        assertEquals("not in the scan: as the machine says", 30_000L, ProjectionController.retryWait(30_000L, dashVisible = false))
        assertEquals("the slow minute of a long absence stays", 60_000L, ProjectionController.retryWait(60_000L, dashVisible = true))
    }

    @Test
    fun `the retry is cut in the schedule and in the log when the ports say the dash is there and refusing`() {
        val ports = object : Recording() {
            override fun dashVisible() = true
        }
        val c = controller(ports)
        c.submit(Start)
        c.submit(AndroidAutoChanged(AaLinkState.ACTIVE))
        repeat(4) { // 2, 4, 8, 16 s: the fifth is the one the cap cuts
            c.submit(DashChanged(DashLink.DROPPED))
            scheduled.lastOrNull()?.second?.invoke()
        }
        c.submit(DashChanged(DashLink.DROPPED))
        assertEquals("schedule 10000", calls.last())
        assertTrue("logged: $log", log.contains("projection: the dash is tried again in 10s (it is in the scan, refusing)"))
    }

    @Test
    fun `switching off stops what was started and releases`() {
        val c = controller()
        c.submit(Start)
        c.submit(DashChanged(DashLink.READY))
        calls.clear()
        c.submit(Stop)
        assertEquals(listOf("stopVideo", "stopAndroidAuto", "stopDash", "release"), calls)
        assertEquals(Projection(), c.state)
    }

    @Test
    fun `a port that fails is logged and the rest of the step still happens`() {
        val c = controller(object : Recording() {
            override fun startAndroidAuto() = throw IllegalStateException("receiver broken")
        })
        // The dash waits for Android Auto's session; the card mode still starts both in one step.
        c.submit(StartCard)
        assertEquals("the dash side is started all the same", listOf("prepare", "startDash"), calls)
        assertTrue("got $log", log.any { "StartAndroidAuto" in it && "receiver broken" in it })
        assertEquals(Phase.RUNNING, c.state.phase)
    }

    @Test
    fun `an event sent from inside a port waits for the step that is running`() {
        lateinit var c: ProjectionController
        c = controller(object : Recording() {
            override fun startAndroidAuto() {
                super.startAndroidAuto()
                // What the receiver does: it reports its state from inside start().
                c.submit(AndroidAutoChanged(AaLinkState.ACTIVE))
                calls += "after submit"
            }
        })
        // The dash waits for Android Auto's session (ProjectionMachineAaFirstTest).
        c.submit(Start)
        assertEquals(listOf("prepare", "startAndroidAuto", "after submit", "startDash"), calls)
        assertEquals(AaLinkState.ACTIVE, c.state.androidAuto)
    }

    @Test
    fun `events from other threads are taken one at a time, never two ports at once`() {
        val inside = AtomicInteger()
        val most = AtomicInteger()
        val entered = CountDownLatch(1)
        val letGo = CountDownLatch(1)
        val ports = object : Recording() {
            override fun startDash() = busy { entered.countDown(); letGo.await(5, TimeUnit.SECONDS) }
            override fun startVideo() = busy { calls += "startVideo" }
            override fun stopVideo() = busy { calls += "stopVideo" }
            fun busy(work: () -> Unit) {
                most.accumulateAndGet(inside.incrementAndGet(), ::maxOf)
                try { work() } finally { inside.decrementAndGet() }
            }
        }
        val c = controller(ports)
        // The card mode still starts the dash in the starting step.
        val first = thread { c.submit(StartCard) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        // While the first thread is inside startDash, another reports the dash ready, then gone, then ready.
        val second = thread {
            c.submit(DashChanged(DashLink.READY))
            c.submit(DashChanged(DashLink.CONNECTING))
            c.submit(DashChanged(DashLink.READY))
        }
        second.join(5_000)
        assertEquals("the second thread only queued its events", DashLink.CONNECTING, c.state.dash)
        letGo.countDown()
        first.join(5_000)

        assertEquals(1, most.get())
        assertEquals(DashLink.READY, c.state.dash)
        assertEquals(listOf("startVideo", "stopVideo", "startVideo"), calls.filter { "Video" in it })
    }
}

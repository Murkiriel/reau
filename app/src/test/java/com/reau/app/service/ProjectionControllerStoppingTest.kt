package com.reau.app.service

import com.reau.app.aa.AaLinkState
import com.reau.app.service.ProjectionEvent.AndroidAutoChanged
import com.reau.app.service.ProjectionEvent.DashChanged
import com.reau.app.service.ProjectionEvent.Start
import com.reau.app.service.ProjectionEvent.Stop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Desligando" carried out: the state is up while the stop's ports run, and it always comes down. */
class ProjectionControllerStoppingTest {
    private val calls = mutableListOf<String>()
    private val log = mutableListOf<String>()
    private val changes = mutableListOf<Projection>()

    private open inner class Recording : ProjectionController.Ports {
        override fun prepare(): String? { calls += "prepare"; return null }
        override fun release() { calls += "release" }
        override fun startAndroidAuto() { calls += "startAndroidAuto" }
        override fun stopAndroidAuto() { calls += "stopAndroidAuto" }
        override fun startDash() { calls += "startDash" }
        override fun stopDash() { calls += "stopDash" }
        override fun startVideo() { calls += "startVideo" }
        override fun stopVideo() { calls += "stopVideo" }
        override fun schedule(delayMs: Long, action: () -> Unit): () -> Unit = {}
    }

    private fun streaming(c: ProjectionController) {
        c.submit(Start)
        c.submit(AndroidAutoChanged(AaLinkState.ACTIVE))
        c.submit(DashChanged(DashLink.READY))
    }

    @Test
    fun `the stop is reported as turning off first and as stopped when its ports are done`() {
        val c = ProjectionController(Recording(), { log += it }, { changes += it })
        streaming(c)
        changes.clear()
        c.submit(Stop)
        assertEquals(listOf(Headline.STOPPING, Headline.STOPPED), changes.map { it.headline })
        assertEquals(Projection(), c.state)
        assertTrue(calls.containsAll(listOf("stopVideo", "stopAndroidAuto", "stopDash", "release")))
    }

    @Test
    fun `while the ports of the stop run, the state says turning off`() {
        lateinit var c: ProjectionController
        val seen = mutableListOf<Headline>()
        val ports = object : Recording() {
            override fun stopAndroidAuto() { seen += c.state.headline }
            override fun stopDash() { seen += c.state.headline }
            override fun release() { seen += c.state.headline }
        }
        c = ProjectionController(ports, { log += it }, { changes += it })
        streaming(c)
        c.submit(Stop)
        assertEquals(listOf(Headline.STOPPING, Headline.STOPPING, Headline.STOPPING), seen)
        assertEquals(Headline.STOPPED, c.state.headline)
    }

    @Test
    fun `a Ligar that arrives while the stop runs is not taken`() {
        lateinit var c: ProjectionController
        val ports = object : Recording() {
            override fun stopAndroidAuto() {
                calls += "stopAndroidAuto"
                c.submit(Start)
            }
        }
        c = ProjectionController(ports, { log += it }, { changes += it })
        streaming(c)
        calls.clear()
        c.submit(Stop)
        assertFalse("no second preparation", "prepare" in calls)
        assertEquals(Projection(), c.state)
        // And once stopped, a Ligar starts as always.
        c.submit(Start)
        assertEquals(Phase.RUNNING, c.state.phase)
    }

    @Test
    fun `a port of the stop that throws costs its own effect and the screen still leaves turning off`() {
        val ports = object : Recording() {
            override fun stopAndroidAuto() = throw IllegalStateException("the receiver would not say bye")
            override fun stopDash() = throw IllegalArgumentException("the session was gone")
            override fun release() = throw RuntimeException("no foreground to give back")
        }
        val c = ProjectionController(ports, { log += it }, { changes += it })
        streaming(c)
        c.submit(Stop)
        assertEquals(Projection(), c.state)
        assertEquals(Headline.STOPPED, changes.last().headline)
        assertTrue("the others went on: $calls", "stopVideo" in calls)
        assertEquals(3, log.count { "failed" in it })
    }

    @Test
    fun `stopping a projection that never ran does not show turning off`() {
        val c = ProjectionController(Recording(), { log += it }, { changes += it })
        c.submit(Stop)
        assertTrue(changes.isEmpty())
        assertTrue(calls.isEmpty())
    }
}

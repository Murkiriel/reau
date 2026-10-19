package com.reau.app.service

import com.reau.app.aa.AaLinkState
import com.reau.app.dash.DashState
import com.reau.app.service.ProjectionEffect.Prepare
import com.reau.app.service.ProjectionEffect.Release
import com.reau.app.service.ProjectionEffect.RetryDashIn
import com.reau.app.service.ProjectionEffect.StartAndroidAuto
import com.reau.app.service.ProjectionEffect.StartDash
import com.reau.app.service.ProjectionEffect.StartVideo
import com.reau.app.service.ProjectionEffect.StopAndroidAuto
import com.reau.app.service.ProjectionEffect.StopDash
import com.reau.app.service.ProjectionEffect.StopVideo
import com.reau.app.service.ProjectionEvent.AndroidAutoChanged
import com.reau.app.service.ProjectionEvent.DashChanged
import com.reau.app.service.ProjectionEvent.DashRetryDue
import com.reau.app.service.ProjectionEvent.PrepareFailed
import com.reau.app.service.ProjectionEvent.Prepared
import com.reau.app.service.ProjectionEvent.Start
import com.reau.app.service.ProjectionEvent.Stop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The life of a projection: the rider switches it on, the service gets
 * ready, then the Android Auto side and the dash side come up side by side and fall each on its own. The machine
 * only decides; what it decides comes out as effects.
 */
class ProjectionMachineTest {
    private var state = Projection()
    private val effects = mutableListOf<ProjectionEffect>()

    private fun on(event: ProjectionEvent): List<ProjectionEffect> {
        val step = ProjectionMachine.step(state, event)
        state = step.state
        effects += step.effects
        return step.effects
    }

    private fun running(): Projection {
        on(Start)
        on(Prepared)
        effects.clear()
        return state
    }

    private fun streaming() {
        running()
        on(AndroidAutoChanged(AaLinkState.ACTIVE))
        on(DashChanged(DashLink.READY))
        effects.clear()
    }

    // ── switching on ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `it starts stopped and says so`() {
        assertEquals(Phase.STOPPED, state.phase)
        assertEquals(Headline.STOPPED, state.headline)
        assertFalse(state.streaming)
    }

    @Test
    fun `switching on prepares the service first`() {
        assertEquals(listOf(Prepare), on(Start))
        assertEquals(Phase.PREPARING, state.phase)
        assertEquals(Headline.PREPARING, state.headline)
    }

    @Test
    fun `once ready Android Auto is started, the dash after it`() {
        // The dash waits for Android Auto's session (ProjectionMachineAaFirstTest).
        on(Start)
        assertEquals(listOf(StartAndroidAuto), on(Prepared))
        assertEquals(Phase.RUNNING, state.phase)
        assertEquals(listOf(StartDash), on(AndroidAutoChanged(AaLinkState.ACTIVE)))
        assertEquals(DashLink.CONNECTING, state.dash)
        assertEquals(Headline.CONNECTING, state.headline)
    }

    @Test
    fun `a preparation that fails ends stopped with the reason and gives back what it took`() {
        on(Start)
        assertEquals(listOf(Release), on(PrepareFailed("no wake lock")))
        assertEquals(Phase.STOPPED, state.phase)
        assertEquals("no wake lock", state.problem)
        assertEquals(Headline.PROBLEM, state.headline)
    }

    @Test
    fun `switching on clears the problem of the last time`() {
        on(Start)
        on(PrepareFailed("no wake lock"))
        on(Start)
        assertNull(state.problem)
    }

    @Test
    fun `switching on twice does nothing the second time`() {
        on(Start)
        assertTrue(on(Start).isEmpty())
        on(Prepared)
        assertTrue(on(Start).isEmpty())
        assertEquals(Phase.RUNNING, state.phase)
    }

    @Test
    fun `being told it is ready when it did not ask is ignored`() {
        assertTrue(on(Prepared).isEmpty())
        assertEquals(Phase.STOPPED, state.phase)
        running()
        assertTrue(on(Prepared).isEmpty())
        assertTrue(on(PrepareFailed("late")).isEmpty())
        assertEquals(Phase.RUNNING, state.phase)
    }

    // ── the dash side ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the video starts when the dash is ready, whether Android Auto is there yet or not`() {
        running()
        assertEquals(listOf(StartVideo), on(DashChanged(DashLink.READY)))
        assertFalse("Android Auto is still connecting", state.streaming)
        assertEquals(Headline.CONNECTING, state.headline)
    }

    @Test
    fun `streaming is both sides up`() {
        running()
        on(AndroidAutoChanged(AaLinkState.ACTIVE))
        assertFalse(state.streaming)
        on(DashChanged(DashLink.READY))
        assertTrue(state.streaming)
        assertEquals(Headline.STREAMING, state.headline)
    }

    @Test
    fun `the dash going ready again does not start a second video`() {
        running()
        on(DashChanged(DashLink.READY))
        assertTrue(on(DashChanged(DashLink.READY)).isEmpty())
    }

    @Test
    fun `a dash that drops stops the video, leaves Android Auto alone and is tried again`() {
        streaming()
        val did = on(DashChanged(DashLink.DROPPED))
        assertEquals(listOf(StopVideo, RetryDashIn(2_000)), did)
        assertFalse("Android Auto is not touched", did.any { it == StopAndroidAuto || it == StartAndroidAuto })
        assertEquals(AaLinkState.ACTIVE, state.androidAuto)
        assertEquals(Headline.DASH_LOST, state.headline)
    }

    @Test
    fun `when the retry is due the dash is connected again`() {
        streaming()
        on(DashChanged(DashLink.DROPPED))
        assertEquals(listOf(StartDash), on(DashRetryDue))
        assertEquals(DashLink.CONNECTING, state.dash)
    }

    @Test
    fun `retries wait longer each time, up to half a minute, and start over once the dash is back`() {
        running()
        val delays = mutableListOf<Long>()
        repeat(7) {
            delays += on(DashChanged(DashLink.DROPPED)).filterIsInstance<RetryDashIn>().single().delayMs
            on(DashRetryDue)
        }
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L), delays)

        on(DashChanged(DashLink.READY))
        assertEquals(listOf(StopVideo, RetryDashIn(2_000)), on(DashChanged(DashLink.DROPPED)))
    }

    @Test
    fun `a dash that drops before it was ever ready has no video to stop`() {
        running()
        assertEquals(listOf(RetryDashIn(2_000)), on(DashChanged(DashLink.DROPPED)))
    }

    @Test
    fun `a drop reported twice is retried once`() {
        running()
        on(DashChanged(DashLink.DROPPED))
        assertTrue(on(DashChanged(DashLink.DROPPED)).isEmpty())
    }

    @Test
    fun `a dash that goes idle on its own while running counts as dropped`() {
        streaming()
        assertEquals(listOf(StopVideo, RetryDashIn(2_000)), on(DashChanged(DashLink.OFF)))
        assertEquals(DashLink.DROPPED, state.dash)
    }

    @Test
    fun `a dash going back to connecting stops the video it had`() {
        streaming()
        assertEquals(listOf(StopVideo), on(DashChanged(DashLink.CONNECTING)))
    }

    @Test
    fun `a retry that is due when the dash is not dropped any more is ignored`() {
        running()
        assertTrue(on(DashRetryDue).isEmpty())
        on(DashChanged(DashLink.DROPPED))
        on(DashChanged(DashLink.READY))
        assertTrue(on(DashRetryDue).isEmpty())
    }

    // ── the Android Auto side ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `Android Auto falling leaves the dash and the video alone`() {
        streaming()
        assertTrue("its receiver dials again by itself", on(AndroidAutoChanged(AaLinkState.DROPPED)).isEmpty())
        assertEquals(DashLink.READY, state.dash)
        assertFalse(state.streaming)
        assertEquals(Headline.CONNECTING, state.headline)
    }

    /** Android Auto accepting and never answering is said, before a lost dash. */
    @Test
    fun `an Android Auto that does not answer is said as such`() {
        running()
        on(AndroidAutoChanged(AaLinkState.UNRESPONSIVE))
        assertEquals(Headline.ANDROID_AUTO_UNRESPONSIVE, state.headline)
        on(DashChanged(DashLink.DROPPED))
        assertEquals(Headline.ANDROID_AUTO_UNRESPONSIVE, state.headline)
        on(AndroidAutoChanged(AaLinkState.ACTIVE))
        assertEquals("the dash was never ready: looking, not lost", Headline.DASH_SEARCHING, state.headline)
    }

    /** Right after REau's own stop Android Auto is still closing the previous session: said so, with no force stop asked. */
    @Test
    fun `an Android Auto still closing the previous session is said as such`() {
        running()
        on(AndroidAutoChanged(AaLinkState.CLOSING_PREVIOUS))
        assertEquals(Headline.ANDROID_AUTO_CLOSING_PREVIOUS, state.headline)
        on(DashChanged(DashLink.DROPPED))
        assertEquals(Headline.ANDROID_AUTO_CLOSING_PREVIOUS, state.headline)
        on(AndroidAutoChanged(AaLinkState.UNRESPONSIVE))
        assertEquals("past the window it is the stuck one", Headline.ANDROID_AUTO_UNRESPONSIVE, state.headline)
        on(AndroidAutoChanged(AaLinkState.CLOSING_PREVIOUS))
        on(AndroidAutoChanged(AaLinkState.ACTIVE))
        assertEquals(Headline.DASH_SEARCHING, state.headline)
    }

    @Test
    fun `no server on the phone is said as such`() {
        running()
        on(AndroidAutoChanged(AaLinkState.SERVER_OFF))
        assertEquals(Headline.ANDROID_AUTO_SERVER_OFF, state.headline)
        on(DashChanged(DashLink.READY))
        assertEquals("the server is what the rider can do something about", Headline.ANDROID_AUTO_SERVER_OFF, state.headline)
        on(DashChanged(DashLink.DROPPED))
        assertEquals("before a lost dash too", Headline.ANDROID_AUTO_SERVER_OFF, state.headline)
    }

    @Test
    fun `a dash that was ready and dropped is lost, said before a connecting Android Auto`() {
        running()
        on(AndroidAutoChanged(AaLinkState.ACTIVE))
        on(DashChanged(DashLink.READY))
        on(AndroidAutoChanged(AaLinkState.CONNECTING))
        on(DashChanged(DashLink.DROPPED))
        assertEquals(Headline.DASH_LOST, state.headline)
    }

    @Test
    fun `a dash that never came up in this session is being looked for, not lost`() {
        running()
        on(DashChanged(DashLink.CONNECTING))
        // Every try of the Wi-Fi request fails (30 s each): the link falls, again and again, with no connection ever made.
        repeat(3) {
            on(DashChanged(DashLink.DROPPED))
            assertEquals(Headline.DASH_SEARCHING, state.headline)
            assertFalse(state.dashWasReady)
            on(DashRetryDue)
            on(DashChanged(DashLink.CONNECTING))
        }
        on(DashChanged(DashLink.DROPPED))
        assertEquals(Headline.DASH_SEARCHING, state.headline)
    }

    @Test
    fun `once the dash was ready, a later fall is a loss, and a new switching on forgets it`() {
        streaming()
        assertTrue(state.dashWasReady)
        on(DashChanged(DashLink.DROPPED))
        assertEquals(Headline.DASH_LOST, state.headline)
        // Tried again and failing: still lost, since the connection did exist.
        on(DashRetryDue)
        on(DashChanged(DashLink.CONNECTING))
        on(DashChanged(DashLink.DROPPED))
        assertEquals(Headline.DASH_LOST, state.headline)
        on(Stop)
        on(ProjectionEvent.StopDone)
        on(Start)
        on(Prepared)
        on(DashChanged(DashLink.DROPPED))
        assertFalse(state.dashWasReady)
        assertEquals(Headline.DASH_SEARCHING, state.headline)
    }

    // ── switching off ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `switching off while streaming stops everything it started and gives back the service`() {
        streaming()
        assertEquals(listOf(StopVideo, StopAndroidAuto, StopDash, Release, ProjectionEffect.FinishStop), on(Stop))
        on(ProjectionEvent.StopDone)
        assertEquals(Projection(), state)
    }

    @Test
    fun `switching off with no video running does not stop one`() {
        running()
        assertEquals(listOf(StopAndroidAuto, StopDash, Release, ProjectionEffect.FinishStop), on(Stop))
    }

    @Test
    fun `switching off while preparing gives back the service and starts nothing`() {
        on(Start)
        assertEquals(listOf(Release, ProjectionEffect.FinishStop), on(Stop))
        assertTrue(on(Prepared).isEmpty())
        assertEquals(Phase.STOPPED, state.phase)
    }

    @Test
    fun `switching off when off does nothing`() {
        assertTrue(on(Stop).isEmpty())
    }

    @Test
    fun `what the sides report after the switch off is ignored`() {
        streaming()
        on(Stop)
        on(ProjectionEvent.StopDone)
        assertTrue(on(AndroidAutoChanged(AaLinkState.IDLE)).isEmpty())
        assertTrue(on(AndroidAutoChanged(AaLinkState.ACTIVE)).isEmpty())
        assertTrue(on(DashChanged(DashLink.DROPPED)).isEmpty())
        assertTrue(on(DashRetryDue).isEmpty())
        assertEquals("nothing of it is kept", Projection(), state)
    }

    // ── from the dash session's own states ─────────────────────────────────────────────────────────────────

    @Test
    fun `the dash session's states are read as the four the machine knows`() {
        assertEquals(DashLink.OFF, ProjectionMachine.linkOf(DashState.IDLE))
        assertEquals(DashLink.CONNECTING, ProjectionMachine.linkOf(DashState.CONNECTING))
        assertEquals(DashLink.CONNECTING, ProjectionMachine.linkOf(DashState.AUTHENTICATING))
        assertEquals(DashLink.READY, ProjectionMachine.linkOf(DashState.READY))
        assertEquals(DashLink.READY, ProjectionMachine.linkOf(DashState.STREAMING))
        assertEquals(DashLink.DROPPED, ProjectionMachine.linkOf(DashState.ERROR))
    }

    @Test
    fun `the retry delays`() {
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), (1..6).map { ProjectionMachine.dashRetryDelayMs(it) })
        assertEquals(30_000L, ProjectionMachine.dashRetryDelayMs(40))
    }
}

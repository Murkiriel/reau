package com.reau.app.service

import android.app.ActivityManager
import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reau.app.aa.AaLinkState
import com.reau.app.settings.SettingsStore
import com.reau.app.video.FrameFit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/** How long the slow sides take to stop Android Auto: longer than the gap between a stop and a start. */
private const val SLOW_STOP_MS = 1_500L
private const val SLOW_HOUSEKEEPING_MS = 2_000L

/**
 * The foreground service on the emulator: it goes up with the foreground and both locks and starts the two sides,
 * and goes down giving back everything. The locks are read from the system's own reports (dumpsys power, dumpsys
 * wifi), not from the app.
 */
@RunWith(AndroidJUnit4::class)
class ProjectionServiceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val calls = CopyOnWriteArrayList<String>()
    @Volatile private var report: ((ProjectionEvent) -> Unit)? = null
    private val originalSides = ProjectionService.sidesFactory
    @Volatile private var slowStop = false

    private inner class RecordingSides : ProjectionSides {
        override fun startAndroidAuto() {
            calls += "startAndroidAuto"
            // As a real session does once it is up (the dash waits for Android Auto;
            // without this report no test here would see the dash started).
            report?.invoke(ProjectionEvent.AndroidAutoChanged(com.reau.app.aa.AaLinkState.ACTIVE))
        }
        override fun stopAndroidAuto() {
            calls += "stopAndroidAuto"
            // As the real receiver on the phone, which takes a couple of seconds to stop.
            if (slowStop) Thread.sleep(SLOW_STOP_MS)
        }
        override fun startDash() { calls += "startDash" }
        override fun stopDash() { calls += "stopDash" }
        override fun startVideo() { calls += "startVideo" }
        override fun stopVideo() { calls += "stopVideo" }
        override fun remap(mapping: FrameFit.Mapping) { calls += "remap ${mapping.dest}" }
    }

    @Before
    fun setUp() {
        ProjectionService.sidesFactory = { _, toMachine ->
            report = toMachine
            RecordingSides()
        }
    }

    @After
    fun tearDown() {
        ProjectionService.stop(context)
        await("stopped after the test") { !serviceRunning() && ProjectionService.projection.value.phase == Phase.STOPPED }
        ProjectionService.sidesFactory = originalSides
        ProjectionService.testOnlyHousekeepingMs = 0
    }

    // ── what the system says ───────────────────────────────────────────────────────────────────────────────

    private fun shell(command: String): String {
        val pipe = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pipe).bufferedReader().use { it.readText() }
    }

    /** How many wake locks with the service's tag the power manager holds. */
    private fun wakeLocks(): Int {
        val section = shell("dumpsys power").substringAfter("Wake Locks: size=").substringBefore("Suspend Blockers")
        return Regex(Regex.escape(ProjectionService.LOCK_TAG)).findAll(section).count()
    }

    /**
     * How many Wi-Fi locks with the service's tag the Wi-Fi service holds: the `WifiLock{…}` lines right under a
     * "Locks held:" heading (there are two such headings; the dump also carries a log where the tag appears on
     * every acquire and release, which must not count).
     */
    private fun wifiLocks(): Int {
        val lines = shell("dumpsys wifi").lines()
        var held = 0
        for ((i, line) in lines.withIndex()) {
            if (line.trim() != "Locks held:") continue
            held += lines.drop(i + 1).takeWhile { it.trim().startsWith("WifiLock{") }.count { "WifiLock{${ProjectionService.LOCK_TAG} " in it }
        }
        return held
    }

    @Suppress("DEPRECATION") // still answers for the app's own services
    private fun runningService(): ActivityManager.RunningServiceInfo? =
        context.getSystemService(ActivityManager::class.java).getRunningServices(50)
            .firstOrNull { it.service.className == ProjectionService::class.java.name }

    private fun serviceRunning() = runningService() != null

    private fun await(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue("$what (calls: $calls, projection: ${ProjectionService.projection.value})", condition())
    }

    private fun startAndWait() {
        ProjectionService.start(context)
        await("running") { ProjectionService.projection.value.phase == Phase.RUNNING }
    }

    // ── the tests ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun startingTakesTheForegroundAndBothLocksAndStartsBothSides() {
        assertEquals("nothing of ours held before", 0 to 0, wakeLocks() to wifiLocks())

        startAndWait()

        assertTrue("a foreground service", runningService()?.foreground == true)
        assertEquals("one wake lock", 1, wakeLocks())
        assertEquals("one Wi-Fi lock", 1, wifiLocks())
        await("both sides started") { calls.toList() == listOf("startAndroidAuto", "startDash") }
    }

    /**
     * The server's cover was slow to appear: Android Auto's side, whose first dial starts the
     * walk behind the cover, no longer waits for the locks and the earlier runs' reports (once 0.4 to 0.8 s on the
     * phone); they follow it, before anything can stop them.
     */
    @Test
    fun androidAutoStartsWithoutWaitingForTheLocksAndTheReports() {
        ProjectionService.testOnlyHousekeepingMs = SLOW_HOUSEKEEPING_MS
        val begun = android.os.SystemClock.elapsedRealtime()
        ProjectionService.start(context)
        await("Android Auto's side started", timeoutMs = SLOW_HOUSEKEEPING_MS / 2) { "startAndroidAuto" in calls }
        val took = android.os.SystemClock.elapsedRealtime() - begun
        assertTrue("Android Auto's side $took ms after the start", took < SLOW_HOUSEKEEPING_MS / 2)
        await("both locks taken after it", timeoutMs = SLOW_HOUSEKEEPING_MS * 3) { wakeLocks() == 1 && wifiLocks() == 1 }
        await("running") { ProjectionService.projection.value.phase == Phase.RUNNING }
    }

    /** A fit changed in the settings reaches the running projection, to adjust it looking at the dash. */
    @Test
    fun aNewFitReachesTheRunningProjection() {
        startAndWait()
        ProjectionService.refit(context)
        val expected = "remap ${SettingsStore.open(context).load().mapping.dest}"
        await("the sides refitted") { expected in calls }
    }

    @Test
    fun withTheProjectionOffARefitStartsNothing() {
        ProjectionService.refit(context)
        Thread.sleep(500)
        assertFalse("no service for a refit", serviceRunning())
        assertTrue(calls.isEmpty())
    }

    @Test
    fun stoppingStopsBothSidesAndLeavesNoLockBehind() {
        startAndWait()

        ProjectionService.stop(context)

        await("the service gone") { !serviceRunning() }
        assertEquals(Phase.STOPPED, ProjectionService.projection.value.phase)
        assertEquals("no lock left", 0 to 0, wakeLocks() to wifiLocks())
        assertEquals(listOf("startAndroidAuto", "startDash", "stopAndroidAuto", "stopDash"), calls.toList())
    }

    @Test
    fun whatTheSidesReportReachesTheMachine() {
        startAndWait()

        report!!(ProjectionEvent.DashChanged(DashLink.READY))
        await("the video started for a ready dash") { "startVideo" in calls }
        report!!(ProjectionEvent.AndroidAutoChanged(AaLinkState.ACTIVE))
        await("streaming") { ProjectionService.projection.value.streaming }
        assertEquals(Headline.STREAMING, ProjectionService.projection.value.headline)
    }

    @Test
    fun aDroppedDashIsTriedAgainByTheServicesOwnTimer() {
        startAndWait()
        report!!(ProjectionEvent.DashChanged(DashLink.DROPPED))

        await("a second startDash two seconds later", timeoutMs = 4_000) { calls.count { it == "startDash" } == 2 }
        assertEquals(DashLink.CONNECTING, ProjectionService.projection.value.dash)
    }

    @Test
    fun startingTwiceHoldsOneOfEachLockAndStartsTheSidesOnce() {
        ProjectionService.start(context)
        ProjectionService.start(context)
        await("running") { ProjectionService.projection.value.phase == Phase.RUNNING }
        Thread.sleep(500)

        assertEquals(1 to 1, wakeLocks() to wifiLocks())
        assertEquals(listOf("startAndroidAuto", "startDash"), calls.toList())
    }

    @Test
    fun switchingOnAndOffFiveTimesInARowLeavesNoLock() {
        repeat(5) {
            ProjectionService.start(context)
            ProjectionService.stop(context)
        }
        await("the service gone") { !serviceRunning() }
        Thread.sleep(500)
        assertFalse(serviceRunning())
        assertEquals(0 to 0, wakeLocks() to wifiLocks())
        // Rewritten when the dash began to wait for Android Auto: a stop can now come before the
        // dash was started at all, and stopping a dash never started is harmless. What must hold: every start is stopped.
        assertTrue("every dash started is stopped", calls.count { it == "stopDash" } >= calls.count { it == "startDash" })
    }

    @Test
    fun theSystemStoppingTheServiceStopsTheSidesAndReleasesTheLocks() {
        startAndWait()

        context.stopService(Intent(context, ProjectionService::class.java))

        await("the service gone") { !serviceRunning() }
        await("stopped") { ProjectionService.projection.value.phase == Phase.STOPPED }
        assertEquals(0 to 0, wakeLocks() to wifiLocks())
        assertTrue("got $calls", calls.containsAll(listOf("stopAndroidAuto", "stopDash")))
    }

    /** The rider's last word counts: a stop that is still being carried out must not end the start after it. */
    @Test
    fun aStartRightAfterAStopWins() {
        startAndWait()

        ProjectionService.stop(context)
        ProjectionService.start(context)

        // The second start itself, not the state from before the stop (which would satisfy "running" at once).
        await("running again", timeoutMs = 8_000) {
            calls.count { it == "startAndroidAuto" } == 2 && ProjectionService.projection.value.phase == Phase.RUNNING && serviceRunning()
        }
        Thread.sleep(1_000)
        assertTrue("still running a second later", serviceRunning() && ProjectionService.projection.value.phase == Phase.RUNNING)
        assertEquals(1 to 1, wakeLocks() to wifiLocks())
    }

    /**
     * Off and on within a second while the stop was still stopping Android Auto's
     * receiver (a couple of seconds on the phone). The stop's release took away the foreground the new start had
     * just taken, and the projection went on as a plain service, whose network Android blocks in the background.
     */
    @Test
    fun aStartRightAfterASlowStopKeepsTheForeground() {
        slowStop = true
        startAndWait()

        ProjectionService.stop(context)
        ProjectionService.start(context)

        await("running again", timeoutMs = 8_000) {
            calls.count { it == "startAndroidAuto" } == 2 && ProjectionService.projection.value.phase == Phase.RUNNING
        }
        Thread.sleep(500)
        assertTrue("still a foreground service after the slow stop", runningService()?.foreground == true)
    }

    /**
     * While the stop is carried out (the receiver's goodbye takes seconds on the phone) the state says "Desligando", and it
     * comes back to "Parado" when the stop is done. Written, not run here: it runs with the other service tests on the bench.
     */
    @Test
    fun whileTheStopIsCarriedOutTheStateSaysTurningOffAndThenStopped() {
        slowStop = true
        startAndWait()

        ProjectionService.stop(context)

        await("turning off") { ProjectionService.projection.value.stopping }
        assertEquals(Headline.STOPPING, ProjectionService.projection.value.headline)
        assertEquals(Phase.STOPPED, ProjectionService.projection.value.phase)
        await("stopped for good", timeoutMs = 8_000) { ProjectionService.projection.value == Projection() }
        assertEquals(Headline.STOPPED, ProjectionService.projection.value.headline)
    }

    /** A Ligar sent while the stop is carried out waits behind it and starts after it: "Desligando" does not eat it. */
    @Test
    fun aStartSentWhileTurningOffRunsAfterTheStopAndLeavesTheStateRunning() {
        slowStop = true
        startAndWait()

        ProjectionService.stop(context)
        await("turning off") { ProjectionService.projection.value.stopping }
        ProjectionService.start(context)

        await("running again", timeoutMs = 8_000) {
            calls.count { it == "startAndroidAuto" } == 2 && ProjectionService.projection.value.phase == Phase.RUNNING
        }
        assertFalse(ProjectionService.projection.value.stopping)
    }

    @Test
    fun theLocksTakenTwiceAreHeldOnceAndGoneWhenReleased() {
        val locks = ProjectionLocks(context)
        try {
            locks.acquire()
            locks.acquire()
            assertEquals(1 to 1, wakeLocks() to wifiLocks())
        } finally {
            locks.release()
        }
        assertEquals(0 to 0, wakeLocks() to wifiLocks())
    }

    @Test
    fun theNotificationSaysWhereTheProjectionStands() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        val notifications = context.getSystemService(android.app.NotificationManager::class.java)
        fun title(): String? = notifications.activeNotifications.firstOrNull { it.id == ProjectionNotification.ID }
            ?.notification?.extras?.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()

        startAndWait()
        await("connecting") { title() == context.getString(com.reau.app.R.string.notif_title_connecting) }

        report!!(ProjectionEvent.DashChanged(DashLink.READY))
        report!!(ProjectionEvent.AndroidAutoChanged(AaLinkState.ACTIVE))
        await("streaming") { title() == context.getString(com.reau.app.R.string.notif_title_streaming) }
        // No app's name ("Waze no painel" said Waze whatever played).
        val text = notifications.activeNotifications.first { it.id == ProjectionNotification.ID }
            .notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()
        assertEquals("Android Auto no painel", text)

        ProjectionService.stop(context)
        await("gone with the service") { title() == null }
    }

    /** The redesign: REau's silhouette, the state's colour, the time since the start. */
    @Test
    fun theNotificationCarriesTheSilhouetteTheColourAndTheTime() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        val notifications = context.getSystemService(android.app.NotificationManager::class.java)
        fun posted(): android.app.Notification? = notifications.activeNotifications.firstOrNull { it.id == ProjectionNotification.ID }?.notification
        val before = System.currentTimeMillis()
        startAndWait()
        await("connecting, with the time") { posted()?.extras?.getBoolean(android.app.Notification.EXTRA_SHOW_CHRONOMETER) == true }
        val n = checkNotNull(posted())
        assertEquals(com.reau.app.R.drawable.ic_stat_reau, n.smallIcon.resId)
        assertEquals(0xFFE1AD01.toInt(), n.color)
        assertTrue("the time counts from the start: ${n.`when`} vs $before", n.`when` in (before - 2_000)..(System.currentTimeMillis() + 2_000))
        // A dash that was never ready is being looked for, not lost: it has to have been ready first.
        report!!(ProjectionEvent.DashChanged(DashLink.READY))
        report!!(ProjectionEvent.DashChanged(DashLink.DROPPED))
        await("the dash lost, in the warning colour") { posted()?.color == 0xFFC26A22.toInt() }
    }

    @Test
    fun stoppingAServiceThatIsNotRunningDoesNothing() {
        ProjectionService.stop(context)
        Thread.sleep(500)
        assertFalse(serviceRunning())
        assertTrue(calls.isEmpty())
        assertEquals(Phase.STOPPED, ProjectionService.projection.value.phase)
    }
}


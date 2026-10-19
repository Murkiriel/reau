package com.reau.app.service

import android.net.Network
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reau.app.aa.AaLinkState
import com.reau.app.aa.AaReceiver
import com.reau.app.aa.FakeAndroidAutoPeer
import com.reau.app.aa.FakeAndroidAutoVideo
import com.reau.app.dash.DashSocket
import com.reau.app.dash.FakeDashPeer
import com.reau.app.aa.Channel
import com.reau.app.aa.proto.Control
import com.reau.app.settings.Fit
import com.reau.app.settings.SettingsStore
import com.reau.app.settings.REauSettings
import com.reau.app.video.H264TestDecoder
import com.reau.app.video.PictureCheck
import com.reau.app.video.TestH264Source
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

/**
 * The whole path on the emulator, inside the service: the fake Android Auto → receiver → decoder → compositor →
 * encoder → RTP → the fake dash. Five checks, one test each.
 */
@RunWith(AndroidJUnit4::class)
class ProjectionEndToEndTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val originalSides = ProjectionService.sidesFactory
    private val aaPort = ServerSocket(0).use { it.localPort }

    @Volatile private var dash = FakeDashPeer().apply { autoAck = true }
    private var androidAuto = FakeAndroidAutoPeer(listenOn = aaPort)
    private var video = FakeAndroidAutoVideo(androidAuto, units)

    /** Stands where the bike's Wi-Fi would be: there at once, with the fake dash's name; or failing, while
     *  [failuresLeft] says so. */
    private val failuresLeft = AtomicInteger()

    private val loopbackLinkUp = object : DashLinkUp {
        override fun start(onReady: (ssid: String, network: Network?) -> Unit, onFailed: (String) -> Unit) {
            if (failuresLeft.getAndUpdate { maxOf(0, it - 1) } > 0) onFailed("Wi-Fi do painel falso recusado") else onReady("DashFake", null)
        }
        override fun stop() = Unit
    }

    /** The bike's Wi-Fi never shows up: Android Auto runs and the dash never connects. */
    private val noDashLinkUp = object : DashLinkUp {
        override fun start(onReady: (ssid: String, network: Network?) -> Unit, onFailed: (String) -> Unit) = Unit
        override fun stop() = Unit
    }

    @get:org.junit.Rule val testName = org.junit.rules.TestName()

    @Before
    fun setUp() {
        // The defaults changed (the joystick page: right is the next song); these tests
        // were written against the first defaults, which an empty store gave until then.
        SettingsStore.open(context).save(com.reau.app.settings.PlanSettings.PLAN)
        // The phone touch is on only for the test that is about it; every other test runs
        // with what Android Auto is told today.
        val noDash = testName.methodName.startsWith("withNoDashYet")
        val touch = testName.methodName.startsWith("withThePhoneTouchOn") || noDash
        val oldCeiling = testName.methodName.startsWith("aFrameRateCeiling")
        ProjectionService.sidesFactory = { ctx, report ->
            LiveSides(
                ctx, report,
                LiveSides.Setup(
                    aaConfig = com.reau.app.aa.AaConfig(touch = touch),
                    aaOptions = AaReceiver.Options(aaPort, AaReceiver.Timing(redialMs = 300, afterDropMs = 200)),
                    // Read when each connection is made: a test may put a new fake dash in place of the old.
                    dashSocket = { DashSocket(null, ctrlDest = dash.ctrlDest, rtpDest = dash.rtpDest) },
                    linkUp = { _, _ -> if (noDash) noDashLinkUp else loopbackLinkUp },
                    // Not the compositor's own default, so the test below can see the setting reach the picture.
                    mapping = FIT,
                    // What a change while running is told apart from, as on the phone. The stream's pace is no longer a
                    // choice (the store always gives the fixed 4 fps), so the one test about a stream change starts the
                    // running stream at the old, higher ceiling: the store's value then differs from it.
                    settings = SettingsStore.open(ctx).load().let { if (oldCeiling) it.copy(fpsCeiling = OLD_CEILING_FPS) else it },
                ),
            )
        }
        ProjectionService.start(context)
        if (noDash) {
            await("Android Auto up with no dash", STREAMING_WAIT_MS) { ProjectionService.projection.value.androidAuto == AaLinkState.ACTIVE }
        } else {
            await("streaming", STREAMING_WAIT_MS) { ProjectionService.projection.value.streaming && dash.frameArrivals.size >= 5 }
        }
    }

    @After
    fun tearDown() {
        ProjectionService.stop(context)
        await("stopped", 10_000) { ProjectionService.projection.value.phase == Phase.STOPPED }
        // Gone, not only stopped: a service still alive would be handed the next test's start and keep the sides it
        // built for this test's fakes.
        await("the service gone", 10_000) { !serviceRunning() }
        video.close()
        androidAuto.close()
        dash.close()
        ProjectionService.sidesFactory = originalSides
    }

    private fun await(what: String, timeoutMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!condition() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
        assertTrue("$what (projection: ${ProjectionService.projection.value}, frames at the dash: ${dash.frameArrivals.size})", condition())
    }

    @Suppress("DEPRECATION") // still answers for the app's own services
    private fun serviceRunning(): Boolean =
        context.getSystemService(android.app.ActivityManager::class.java).getRunningServices(50)
            .any { it.service.className == ProjectionService::class.java.name }

    /** The longest the dash went without a frame between [fromMs] and [toMs] (elapsedRealtime). */
    private fun longestGapMs(peer: FakeDashPeer, fromMs: Long, toMs: Long): Long {
        val arrivals = peer.frameArrivals.toList()
        val before = arrivals.lastOrNull { it < fromMs } ?: fromMs
        val points = listOf(before) + arrivals.filter { it in fromMs..toMs } + toMs
        return points.zipWithNext { a, b -> b - a }.max()
    }

    private fun framesBetween(peer: FakeDashPeer, fromMs: Long, toMs: Long) = peer.frameArrivals.count { it in fromMs..toMs }

    /** SPS, PPS and IDR in one unit, in that order: how the dash must get its keyframes. */
    private fun isKeyframeBundle(unit: ByteArray): Boolean {
        val types = (0..unit.size - 5).filter {
            unit[it].toInt() == 0 && unit[it + 1].toInt() == 0 && unit[it + 2].toInt() == 0 && unit[it + 3].toInt() == 1
        }.map { unit[it + 4].toInt() and 0x1F }
        return types.take(3) == listOf(NAL_SPS, NAL_PPS, NAL_IDR)
    }

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }

    // ── 1 ──────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun theDashGetsVideoAndItsFirstFrameIsAKeyframe() {
        val first = dash.firstUnits.first()
        assertTrue("the first unit is ${first.take(16)}", isKeyframeBundle(first))
        assertTrue(ProjectionService.projection.value.streaming)

        // The session is streaming, not only ready: the projection keep-alive (05 56 55) goes out at 4 Hz then.
        Thread.sleep(2_000)
        val keepAlives = dash.receivedSinceHandshake().count { it.type == 0x05 && it.sub == 0x56 && it.value.firstOrNull() == 0x55.toByte() }
        assertTrue("projection keep-alives: $keepAlives", keepAlives >= 6)
    }

    /** The fit chosen in the settings is where Android Auto's picture lands on the dash: decoded and checked. */
    @Test
    fun theDashGetsThePictureWhereTheChosenFitPutsIt() {
        await("enough frames for Android Auto's picture to be in them", 15_000) { dash.firstUnits.size >= 30 }
        val chunks = dash.firstUnits.mapIndexed { i, unit -> H264TestDecoder.Chunk(unit, i * 62L) }
        val picture = H264TestDecoder().decode(chunks).last()
        PictureCheck.assertShows(picture, FIT)
    }

    @Test
    fun aBikeWiFiThatFailsIsTriedAgainUntilItIsThere() {
        failuresLeft.set(2)
        val gone = dash
        val back = FakeDashPeer().apply { autoAck = true }
        dash = back
        gone.close()

        await("video at the dash once the Wi-Fi lets it through", 90_000) { back.frameArrivals.size >= 5 && ProjectionService.projection.value.streaming }
        assertEquals("both refusals were used up", 0, failuresLeft.get())
        assertTrue("got ${ProjectionService.projection.value}", ProjectionService.projection.value.dashRetries == 0)
    }

    /**
     * The screen "Tela do Android Auto" (the phone's preview) gets Android Auto's
     * picture through the running projection, and the dash keeps its frames while it is open. Written after the code.
     */
    @Test
    fun thePhonesAndroidAutoScreenGetsThePictureAndTheDashKeepsItsFrames() {
        Thread.sleep(1_000)
        val thread = android.os.HandlerThread("e2e-preview").apply { start() }
        val reader = android.media.ImageReader.newInstance(400, 240, android.graphics.PixelFormat.RGBA_8888, 3)
        val previewFrames = AtomicInteger(0)
        reader.setOnImageAvailableListener({ r ->
            r.acquireLatestImage()?.close()
            previewFrames.incrementAndGet()
        }, android.os.Handler(thread.looper))
        try {
            val from = SystemClock.elapsedRealtime()
            com.reau.app.video.PhonePreview.show(reader.surface, 400, 240)
            Thread.sleep(5_000)
            val to = SystemClock.elapsedRealtime()
            com.reau.app.video.PhonePreview.hide()
            assertTrue("the phone's preview got ${previewFrames.get()} pictures in 5 s", previewFrames.get() >= 10)
            val gap = longestGapMs(dash, from, to)
            assertTrue("longest gap at the dash with the preview open: $gap ms", gap <= MAX_GAP_MS)
            assertTrue("frames at the dash with the preview open: ${framesBetween(dash, from, to)}", framesBetween(dash, from, to) >= 20)
        } finally {
            com.reau.app.video.PhonePreview.hide()
            reader.close()
            thread.quitSafely()
        }
    }

    /**
     * With the phone touch switched on, Android Auto is told of a touch screen the size of
     * the video, and a finger on the phone's "Tela do Android Auto" reaches it as a touch, at the same spot of the
     * video, while the dash keeps its frames. Written after the code.
     */
    @Test
    fun withThePhoneTouchOnAFingerOnTheAndroidAutoScreenReachesAndroidAutoAsATouch() {
        val reader = android.media.ImageReader.newInstance(400, 240, android.graphics.PixelFormat.RGBA_8888, 3)
        try {
            com.reau.app.video.PhonePreview.show(reader.surface, 400, 240) // the whole 800x480 picture fills it
            val from = SystemClock.elapsedRealtime()
            com.reau.app.video.PhonePreview.touch(com.reau.app.aa.PhoneTouch(com.reau.app.aa.PhoneTouch.Action.DOWN, 200f, 120f))
            com.reau.app.video.PhonePreview.touch(com.reau.app.aa.PhoneTouch(com.reau.app.aa.PhoneTouch.Action.UP, 200f, 120f))
            dash.pressButton(0x09) // the joystick, right side in the media mode: still a step of the wheel with touch on
            var wheel = 0
            var announced: com.reau.app.aa.proto.Control.Service.InputSourceService? = null
            val touches = mutableListOf<com.reau.app.aa.proto.Input.TouchEvent>()
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while ((touches.size < 2 || wheel == 0) && SystemClock.elapsedRealtime() < deadline) {
                val m = androidAuto.receive(500) ?: continue
                if (m.channel == com.reau.app.aa.Channel.ID_CTR && m.type == com.reau.app.aa.proto.Control.ControlMsgType.MESSAGE_SERVICE_DISCOVERY_RESPONSE_VALUE) {
                    announced = com.reau.app.aa.proto.Control.ServiceDiscoveryResponse.parseFrom(m.body).servicesList
                        .single { it.id == com.reau.app.aa.Channel.ID_INP }.inputSourceService
                }
                if (m.channel == com.reau.app.aa.Channel.ID_INP && m.type == com.reau.app.aa.proto.Input.MsgType.EVENT_VALUE) {
                    val report = com.reau.app.aa.proto.Input.InputReport.parseFrom(m.body)
                    if (report.hasTouchEvent()) touches += report.touchEvent
                    if (report.hasRelativeEvent()) wheel += report.relativeEvent.getData(0).delta
                }
            }
            assertEquals("down and up reached Android Auto", 2, touches.size)
            assertEquals(com.reau.app.aa.proto.Input.TouchEvent.PointerAction.TOUCH_ACTION_DOWN, touches[0].action)
            assertEquals(com.reau.app.aa.proto.Input.TouchEvent.PointerAction.TOUCH_ACTION_UP, touches[1].action)
            assertEquals("the middle of the screen is the middle of the video", 400 to 240, touches[0].getPointerData(0).x to touches[0].getPointerData(0).y)
            announced?.let {
                assertTrue("a touch screen was announced", it.hasTouchscreen())
                assertEquals(800 to 480, it.touchscreen.width to it.touchscreen.height)
            }
            assertEquals("the joystick still turns the wheel with touch on", 1, wheel)
            Thread.sleep(1_000) // a window long enough to hold the dash's frames (the touches arrive in a few ms)
            assertTrue("the dash kept its frames", framesBetween(dash, from, SystemClock.elapsedRealtime()) >= 5)
        } finally {
            com.reau.app.video.PhonePreview.hide()
            reader.close()
        }
    }

    /**
     * Android Auto up and the dash not connected yet, the phone's "Tela do Android Auto"
     * still shows it, and (with the phone touch on) a finger reaches it. Written after the code.
     */
    @Test
    fun withNoDashYetThePhoneSeesAndTouchesAndroidAuto() {
        val thread = android.os.HandlerThread("e2e-no-dash").apply { start() }
        val reader = android.media.ImageReader.newInstance(400, 240, android.graphics.PixelFormat.RGBA_8888, 3)
        val previewFrames = AtomicInteger(0)
        reader.setOnImageAvailableListener({ r ->
            r.acquireLatestImage()?.close()
            previewFrames.incrementAndGet()
        }, android.os.Handler(thread.looper))
        try {
            com.reau.app.video.PhonePreview.show(reader.surface, 400, 240)
            await("Android Auto's picture on the phone with no dash", 10_000) { previewFrames.get() >= 5 }
            com.reau.app.video.PhonePreview.touch(com.reau.app.aa.PhoneTouch(com.reau.app.aa.PhoneTouch.Action.DOWN, 100f, 60f))
            com.reau.app.video.PhonePreview.touch(com.reau.app.aa.PhoneTouch(com.reau.app.aa.PhoneTouch.Action.UP, 100f, 60f))
            val touches = mutableListOf<com.reau.app.aa.proto.Input.TouchEvent>()
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (touches.size < 2 && SystemClock.elapsedRealtime() < deadline) {
                val m = androidAuto.receive(500) ?: continue
                if (m.channel == com.reau.app.aa.Channel.ID_INP && m.type == com.reau.app.aa.proto.Input.MsgType.EVENT_VALUE) {
                    val report = com.reau.app.aa.proto.Input.InputReport.parseFrom(m.body)
                    if (report.hasTouchEvent()) touches += report.touchEvent
                }
            }
            assertEquals("down and up reached Android Auto with no dash", 2, touches.size)
            assertEquals(200 to 120, touches[0].getPointerData(0).x to touches[0].getPointerData(0).y)
            assertEquals("no dash came up", false, ProjectionService.projection.value.streaming)
        } finally {
            com.reau.app.video.PhonePreview.hide()
            reader.close()
            thread.quitSafely()
        }
    }

    // ── 2 ──────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun withAndroidAutoSilentForTenSecondsTheDashKeepsGettingFrames() {
        Thread.sleep(2_000)
        video.paused = true
        val from = SystemClock.elapsedRealtime()
        Thread.sleep(10_000)
        val to = SystemClock.elapsedRealtime()
        video.paused = false

        val gap = longestGapMs(dash, from, to)
        assertTrue("longest gap $gap ms", gap <= MAX_GAP_MS)
        assertTrue("frames during the pause: ${framesBetween(dash, from, to)}", framesBetween(dash, from, to) >= 10)
        assertEquals("Android Auto only paused: the session stays", AaLinkState.ACTIVE, ProjectionService.projection.value.androidAuto)
    }

    // ── 3 ──────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun aDashThatGoesAwayIsConnectedAgainAndTheVideoReopensWithAKeyframe() {
        val gone = dash
        val back = FakeDashPeer().apply { autoAck = true }
        dash = back
        gone.close()

        await("the dash seen as lost", 20_000) { ProjectionService.projection.value.dash == DashLink.DROPPED }
        await("video at the new dash", 60_000) { back.frameArrivals.size >= 5 && ProjectionService.projection.value.streaming }
        assertTrue("the stream to the new dash opens with a keyframe", isKeyframeBundle(back.firstUnits.first()))
    }

    // ── 4 ──────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun androidAutoGoingAwayLeavesTheLastPictureOnTheDashAndTheReceiverConnectsAgain() {
        video.close()
        androidAuto.close()
        val from = SystemClock.elapsedRealtime()
        await("Android Auto seen as gone", 10_000) { ProjectionService.projection.value.androidAuto != AaLinkState.ACTIVE }
        Thread.sleep(5_000)
        val to = SystemClock.elapsedRealtime()

        val gap = longestGapMs(dash, from, to)
        assertTrue("the dash kept getting the last picture; longest gap $gap ms", gap <= MAX_GAP_MS)
        assertNotEquals(AaLinkState.ACTIVE, ProjectionService.projection.value.androidAuto)

        androidAuto = FakeAndroidAutoPeer(listenOn = aaPort)
        video = FakeAndroidAutoVideo(androidAuto, units)
        await("Android Auto back", 20_000) { ProjectionService.projection.value.androidAuto == AaLinkState.ACTIVE }
        val sentBefore = video.sent
        await("its pictures flowing again", 10_000) { video.sent > sentBefore + 10 }
        assertTrue(ProjectionService.projection.value.streaming)
    }

    // ── live settings ──────────────────────────────────────────────────────────────────────────────────────

    /** Saves [change] of the rider's settings, tells the running projection, and puts the settings back after [check]. */
    private fun withSettings(change: (REauSettings) -> REauSettings, check: () -> Unit) {
        val store = SettingsStore.open(context)
        val saved = store.load()
        try {
            store.save(change(saved))
            ProjectionService.refit(context)
            check()
        } finally {
            store.save(saved)
        }
    }

    /** Reads [peer]'s messages until one of [type] comes, for up to [timeoutMs]; null if none did. */
    private fun awaitMessage(peer: FakeAndroidAutoPeer, type: Int, timeoutMs: Long = 10_000): FakeAndroidAutoPeer.Received? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val m = peer.receive(200) ?: continue
            if (m.type == type && m.channel == Channel.ID_CTR) return m
        }
        return null
    }

    /**
     * The joystick page, changed while the projection runs, is taken at the next tap. The middle of the
     * media mode (0x05) confirms by default; set to "home", the next press reaches Android Auto as the home key.
     */
    @Test
    fun aJoystickPageChangedWhileRunningIsTakenAtTheNextTap() {
        val page = com.reau.app.aa.JoystickBindings.DEFAULT.with(
            com.reau.app.aa.JoystickBindings.Slot(
                com.reau.app.aa.JoystickBindings.Mode.MEDIA,
                com.reau.app.aa.JoystickBindings.Control.CENTER,
                com.reau.app.aa.JoystickBindings.Gesture.ONCE,
            ),
            com.reau.app.aa.JoystickBindings.Action.HOME,
        ).encode()
        withSettings({ it.copy(joystick = page) }) {
            Thread.sleep(1_000) // the refit reaches the running sides
            dash.pressButton(0x05)
            var key: Int? = null
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (key == null && SystemClock.elapsedRealtime() < deadline) {
                val m = androidAuto.receive(500) ?: continue
                if (m.channel == Channel.ID_INP && m.type == com.reau.app.aa.proto.Input.MsgType.EVENT_VALUE) {
                    val report = com.reau.app.aa.proto.Input.InputReport.parseFrom(m.body)
                    if (report.hasKeyEvent()) key = report.keyEvent.getKeys(0).keycode
                }
            }
            assertEquals("the middle goes as the page now says: home", com.reau.app.aa.ServiceDiscoveryResponse.KEY_HOME, key)
        }
    }

    /**
     * Four density changes within a second (once seen to open three sessions)
     * reconfigure Android Auto's receiver once, a second after the last change. On the phone every reconfiguration
     * ends the session and Android Auto dials a new one at once; the fake here does not dial again, so the test counts
     * the reconfigurations in the ride log ("AaReceiver: new configuration") instead of the sessions.
     */
    @Test
    fun fourDensityChangesWithinASecondReconfigureAndroidAutoOnce() {
        val store = SettingsStore.open(context)
        val saved = store.load()
        val marker = "burst-${System.nanoTime()}"
        com.reau.app.data.RideDiagnostics.logAndWait("test", marker)
        try {
            repeat(4) { i ->
                store.save(saved.copy(density = saved.density + 10 * (i + 1)))
                ProjectionService.refit(context)
                Thread.sleep(200)
            }
            Thread.sleep(3_000)
            com.reau.app.data.RideDiagnostics.logAndWait("test", "$marker-end")
            val diag = java.io.File(context.filesDir, "diag")
            val log = diag.listFiles { f -> f.name.startsWith("ride-") }!!.maxByOrNull { it.lastModified() }!!.readText()
                .substringAfter(marker)
            val reconfigured = Regex("AaReceiver: new configuration").findAll(log).count()
            assertEquals("one reconfiguration for the four changes:\n$log", 1, reconfigured)
            assertTrue("with the last density", log.contains("density ${saved.density + 40}"))
        } finally {
            store.save(saved)
        }
    }

    @Test
    fun aDensityChangedWhileRunningReachesAndroidAutoAndTheDashStaysConnected() {
        // Every video setting applies while running: the density goes to a new Android Auto session; the dash keeps its connection and its frames meanwhile.
        val wanted = SettingsStore.open(context).load().density + 40
        withSettings({ it.copy(density = wanted) }) {
            assertNotNull("Android Auto's session was ended with a bye", awaitMessage(androidAuto, Control.ControlMsgType.MESSAGE_BYEBYE_REQUEST_VALUE))
            val from = SystemClock.elapsedRealtime()
            video.close()
            androidAuto.close()
            androidAuto = FakeAndroidAutoPeer(listenOn = aaPort)
            video = FakeAndroidAutoVideo(androidAuto, units)
            await("Android Auto's new session", 20_000) { ProjectionService.projection.value.androidAuto == AaLinkState.ACTIVE && androidAuto.awaitHandshake(0) }
            androidAuto.send(
                Channel.ID_CTR, Control.ControlMsgType.MESSAGE_SERVICE_DISCOVERY_REQUEST_VALUE,
                Control.ServiceDiscoveryRequest.newBuilder().setPhoneName("Fake phone").setPhoneBrand("Fake").build().toByteArray(),
            )
            val answer = awaitMessage(androidAuto, Control.ControlMsgType.MESSAGE_SERVICE_DISCOVERY_RESPONSE_VALUE)
            assertNotNull("the new session's discovery response", answer)
            val videoService = Control.ServiceDiscoveryResponse.parseFrom(answer!!.body).servicesList.single { it.id == Channel.ID_VID }
            assertEquals("the new session is told the new density", wanted, videoService.mediaSinkService.videoConfigsList.single().density)
            val to = SystemClock.elapsedRealtime()
            assertEquals("the dash never dropped", com.reau.app.service.DashLink.READY, ProjectionService.projection.value.dash)
            val gap = longestGapMs(dash, from, to)
            assertTrue("the dash kept its frames meanwhile; longest gap $gap ms", gap <= MAX_GAP_MS)
        }
    }

    @Test
    fun aFrameRateCeilingChangedWhileRunningTakesEffectAtOnce() {
        // The stream runs at the old ceiling (setUp); the fake Android Auto's picture changes every 66 ms, so the dash gets
        // the stream's own rate. The store holds the fixed 4 fps (sanitized), which differs from what runs: a new stream.
        val start = SystemClock.elapsedRealtime()
        Thread.sleep(PACE_WINDOW_MS)
        val fast = framesBetween(dash, start, SystemClock.elapsedRealtime())
        withSettings({ it }) {
            val changed = SystemClock.elapsedRealtime()
            await("a new stream (a keyframe) at the dash", 10_000) { dash.keyframeArrivals.any { it > changed } }
            Thread.sleep(1_000)
            val from = SystemClock.elapsedRealtime()
            Thread.sleep(PACE_WINDOW_MS)
            val slow = framesBetween(dash, from, SystemClock.elapsedRealtime())
            assertTrue("about 4 frames a second now: $slow in ${PACE_WINDOW_MS / 1000} s (before: $fast)", slow <= 6 * PACE_WINDOW_MS / 1000 && slow < fast)
            assertTrue(ProjectionService.projection.value.streaming)
            assertEquals("Android Auto untouched", AaLinkState.ACTIVE, ProjectionService.projection.value.androidAuto)
        }
    }

    // ── 5 ──────────────────────────────────────────────────────────────────────────────────────────────────

    /** The app is in the background all along here: the test starts the service, no screen of the app is open. */
    @Test
    fun fiveMinutesWithTheScreenOffAndTheDashNeverGoesASecondWithoutAFrame() {
        shell("input keyevent KEYCODE_SLEEP")
        try {
            val from = SystemClock.elapsedRealtime()
            Thread.sleep(SCREEN_OFF_MS)
            val to = SystemClock.elapsedRealtime()

            val gap = longestGapMs(dash, from, to)
            assertTrue("longest gap $gap ms in ${(to - from) / 1000} s, ${framesBetween(dash, from, to)} frames", gap <= MAX_GAP_MS)
            assertTrue(ProjectionService.projection.value.streaming)
        } finally {
            shell("input keyevent KEYCODE_WAKEUP")
        }
    }

    // ── 6, the ride log's summary ──────────────────────────────────────────────────────────────────────────

    @Test
    fun aShortProjectionWithAnAndroidAutoDropEndsWithTheRightSummary() {
        video.close()
        androidAuto.close()
        await("Android Auto seen as gone", 10_000) { ProjectionService.projection.value.androidAuto != AaLinkState.ACTIVE }
        Thread.sleep(2_000)
        ProjectionService.stop(context)
        await("stopped", 10_000) { ProjectionService.projection.value.phase == Phase.STOPPED }
        Thread.sleep(500) // the last frames on their way to the fake dash

        val diag = java.io.File(context.filesDir, "diag")
        val newest = diag.listFiles { f -> f.name.startsWith("ride-") }?.maxByOrNull { it.lastModified() }
        val line = newest?.readLines()?.lastOrNull { "resumo " in it }
        assertTrue("a summary line at the end: ${newest?.name}", line != null)
        assertTrue("one Android Auto drop, no dash drop: $line", " drops dash=0 aa=1 " in line!!)
        val frames = Regex(""" frames=(\d+) """).find(line)!!.groupValues[1].toInt()
        val arrived = dash.frameArrivals.size
        assertTrue("frames counted ($frames) as the fake dash got them ($arrived)", kotlin.math.abs(frames - arrived) <= 3)
        assertTrue("the phone's battery known: $line", " battery ?" !in line && " thermal max=?" !in line)
        assertTrue("no mark: $line", line.endsWith(" marks=0"))
    }

    /**
     * A dash that goes away is one drop; every try that is refused on the way back is a failed try, not another drop (a
     * ride once said 16 drops for two).
     */
    @Test
    fun aDashThatGoesAwayAndIsRefusedTwiceIsOneDropAndTwoFailedTriesInTheSummary() {
        failuresLeft.set(2)
        val gone = dash
        val back = FakeDashPeer().apply { autoAck = true }
        dash = back
        gone.close()
        await("video at the dash once the Wi-Fi lets it through", 90_000) { back.frameArrivals.size >= 5 && ProjectionService.projection.value.streaming }
        assertEquals("both refusals were used up", 0, failuresLeft.get())
        // One log file holds every projection of the process: this one's summary is the one that comes after the others'.
        val diag = java.io.File(context.filesDir, "diag")
        fun summaries() = diag.listFiles { f -> f.name.startsWith("ride-") }?.maxByOrNull { it.name }
            ?.readLines()?.filter { "resumo " in it }.orEmpty()
        val before = summaries().size
        ProjectionService.stop(context)
        await("stopped", 10_000) { ProjectionService.projection.value.phase == Phase.STOPPED }
        // Written when the service lets go, a moment after the state says stopped.
        await("the summary line", 10_000) { summaries().size > before }
        val line = summaries().last()
        assertTrue("one drop and two failed tries: $line", " drops dash=1 aa=0 retries dash=2 " in line)
    }

    private companion object {
        const val STREAMING_WAIT_MS = 40_000L
        const val MAX_GAP_MS = 1_000L
        const val PACE_WINDOW_MS = 3_000L

        /** The frame-rate ceiling the stream ran at before the pace became fixed at 4 fps. */
        const val OLD_CEILING_FPS = 16
        const val SCREEN_OFF_MS = 5 * 60 * 1000L
        const val NAL_IDR = 5
        const val NAL_SPS = 7
        const val NAL_PPS = 8

        /** The fit the projection runs with here: the whole picture kept inside the round glass. */
        val FIT = REauSettings(fit = Fit.WHOLE_VISIBLE).mapping

        /** One second of moving Android Auto: 15 frames of the test pattern, opening with a keyframe. */
        val units: List<TestH264Source.Unit> by lazy { TestH264Source.encode(frames = 15, keyframeEvery = 15) }
    }
}

package com.reau.app.service

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The service, the loop and the session need Android to run, so the lines that carry each experimental switch to the code it gates are read
 * from the source, as [DashRecoveryIsWiredTest] does. The decisions themselves are in RtpPacingTest, BitrateAdapterTest, DashSessionBindRetryTest,
 * DashSocketDscpTest and ProjectionLocksTest.
 */
class ConnectionExperimentsAreWiredTest {
    private val live = File("src/main/java/com/reau/app/service/LiveSides.kt").readText()
    private val loop = File("src/main/java/com/reau/app/stream/VideoLoop.kt").readText()
    private val service = File("src/main/java/com/reau/app/service/ProjectionService.kt").readText()
    private val session = File("src/main/java/com/reau/app/dash/DashSession.kt").readText()
    private val socket = File("src/main/java/com/reau/app/dash/DashSocket.kt").readText()

    @Test
    fun `the stream's two experiments go from the settings to the loop, at the start and at a new stream, and the bitrate is the effective one`() {
        assertTrue(Regex("""rtpPacing = settings\.experimentRtpPacing""").findAll(live).count() == 2)
        assertTrue(Regex("""adaptiveBitrate = settings\.experimentAdaptiveBitrate""").findAll(live).count() == 2)
        assertTrue(Regex("""bitrateBps = settings\.effectiveStreamBitrateBps""").findAll(live).count() == 2)
        assertTrue(loop.contains("RtpPacing.whenOn(settings.rtpPacing)"))
        assertTrue(loop.contains("BitrateAdapter.whenOn(settings.adaptiveBitrate)"))
        assertTrue(loop.contains("pacing = pacing,"))
    }

    @Test
    fun `the mute encoder watch goes from the settings to the loop, which rebuilds only on its word and only with the switch on`() {
        assertTrue(Regex("""encoderMuteWatch = settings\.experimentEncoderWatch""").findAll(live).count() == 2)
        assertTrue(loop.contains("muteWatch = EncoderMuteWatch.whenOn(settings.encoderMuteWatch)"))
        assertTrue(loop.contains("if (timed != null) watchForAMuteEncoder(g, timed, SystemClock.elapsedRealtime())"))
        val watch = loop.substringAfter("private fun watchForAMuteEncoder").substringBefore("private fun adaptBitrate")
        assertTrue("no watch, nothing done", watch.contains("val watch = muteWatch ?: return"))
        assertTrue(watch.contains("watch.onPoll(nowMs, timed.submitted, timed.frames)"))
        assertTrue(watch.contains("if (verdict == EncoderMuteWatch.Verdict.REBUILD) rebuild(g.rebuildAction())"))
        assertTrue("the watch ends with the stream", loop.substringAfter("fun stop()").substringBefore("private fun poll").contains("muteWatch = null"))
    }

    @Test
    fun `the adjusted bitrate is asked of the running encoder, never by building another`() {
        val adapt = loop.substringAfter("private fun adaptBitrate").substringBefore("private fun askAgainForWithheldKeyframe")
        assertTrue(adapt.contains("encoder?.requestBitrate(wanted)"))
        assertTrue(!adapt.contains("rebuild(") && !adapt.contains("buildEncoder("))
    }

    @Test
    fun `the debug marks and the bind retries read the live settings of the rider`() {
        assertTrue(live.contains("DashSocket(it, markInDebug = SettingsStore.open(context).load().experimentDebugDscp)"))
        assertTrue(live.contains("bindRetry = { tuned?.experimentWifiBind == true }"))
        assertTrue(socket.contains("priorityMarks(markInDebug = markInDebug)"))
    }

    @Test
    fun `the session retries the bind before it tells the holder of the network, and in a coroutine`() {
        assertTrue(session.contains("private suspend fun socketWithBindRetries("))
        assertTrue(session.contains("socketWithBindRetries(mine, network)"))
        assertTrue(session.contains("BindRetry.again(bindRetry(), refusal, retries)"))
    }

    @Test
    fun `the Wi-Fi lock is asked for low latency by the switch, and the log says it`() {
        assertTrue(service.contains("locks.acquire(lowLatency = experiment)"))
        assertTrue(service.contains("ProjectionLocks.logWords(experiment, mode, android.os.Build.VERSION.SDK_INT)"))
        assertTrue(service.contains("SettingsInUse.experimentsLine(inUse)"))
    }
}

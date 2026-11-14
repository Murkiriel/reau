package com.reau.app.service

import com.reau.app.aa.AaAudioFeed
import com.reau.app.aa.AaAudioPlayer
import com.reau.app.aa.StuckDashTone
import com.reau.app.dash.BindRefusals
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three new notices to the rider: Android refusing the dash's network again and again, the alert channel muted or blocked, and the hung dash
 * also in the earphone. The decisions are pure and run here; the lines that wire them to Android are read from the source, as the other
 * `...IsWired` tests do.
 */
class RiderAlertsTest {
    // -- (a) EPERM again and again -----------------------------------------------------------------------------------

    @Test
    fun `the rider is told at the fourth refusal in a row, once, and not before`() {
        val refusals = BindRefusals()
        val told = (1..8).map { refusals.refused("net-$it").tell }
        assertEquals(listOf(false, false, false, true, false, false, false, false), told)
        assertEquals("a limit of four", 4, BindRefusals.TELL_AT)
    }

    @Test
    fun `three refusals, which a network still being set up can give, tell nothing`() {
        val refusals = BindRefusals()
        repeat(3) { assertFalse(refusals.refused("same").tell) }
        assertFalse("nothing told, so nothing to take away", refusals.bound())
    }

    @Test
    fun `the same network refused four times in a row tells as well as four different ones`() {
        val refusals = BindRefusals()
        assertEquals(listOf(false, false, false, true), (1..4).map { refusals.refused("same").tell })
    }

    @Test
    fun `a refusal of a network the phone joined by itself counts in the run too`() {
        val refusals = BindRefusals()
        assertEquals(listOf(false, false, false, true), (1..4).map { refusals.refused("net-$it", phoneJoined = true).tell })
    }

    @Test
    fun `a bind that works starts the count over and says the notice is to go, once`() {
        val refusals = BindRefusals()
        repeat(4) { refusals.refused("net") }
        assertTrue("the rider had been told: the notice goes", refusals.bound())
        assertFalse("and only once", refusals.bound())
        assertEquals("three more are not a new run", listOf(false, false, false), (1..3).map { refusals.refused("net").tell })
        assertTrue("the fourth of the new run tells again", refusals.refused("net").tell)
    }

    @Test
    fun `the callback is told true at the fourth refusal and false at the bind, and nothing in between or after`() {
        val refusals = BindRefusals()
        val calls = mutableListOf<Boolean>()
        refusals.onTold = { calls += it }
        repeat(3) { refusals.refused("net-$it") }
        assertEquals(emptyList<Boolean>(), calls)
        repeat(3) { refusals.refused("net-x$it") }
        assertEquals("told once, not at the fifth or sixth", listOf(true), calls)
        refusals.bound()
        refusals.bound()
        assertEquals("taken away once", listOf(true, false), calls)
    }

    @Test
    fun `without a callback the refusals count the same`() {
        val refusals = BindRefusals()
        assertEquals(listOf(false, false, false, true), (1..4).map { refusals.refused("net").tell })
    }

    @Test
    fun `the verdict still says what it said, the notice only adds to it`() {
        val refusals = BindRefusals()
        val first = refusals.refused("net")
        assertEquals(BindRefusals.Step.ASK_AGAIN, first.step)
        assertEquals(0L, first.backoffMs)
        val fourth = (2..4).map { refusals.refused("net") }.last()
        assertEquals(BindRefusals.Step.BACK_OFF, fourth.step)
        assertEquals(BindRefusals.backoffMs(4), fourth.backoffMs)
        assertTrue(fourth.line("net").contains("#4 in a row"))
    }

    @Test
    fun `the log line of the notice says how many`() {
        assertTrue(BindRefusals.tellLine(4).contains("4 times in a row"))
        assertTrue(BindRefusals.tellLine(4).contains("rider told"))
    }

    @Test
    fun `the refusals reach the rider's notice and go away with the first bind or the switch off`() {
        val live = File("src/main/java/com/reau/app/service/LiveSides.kt").readText()
        val wifi = File("src/main/java/com/reau/app/dash/DashWifiManager.kt").readText()
        assertTrue(live.contains("wifi.onBindRefused = { refused ->"))
        assertTrue(live.contains("ProjectionNotification.showBindRefused(context)"))
        assertTrue(live.contains("ProjectionNotification.clearBindRefused(context)"))
        // The manager hands its callback to the refusals' own, which tell after their log line and take the notice away on a bind or the switch off.
        assertTrue(wifi.contains("var onBindRefused: ((Boolean) -> Unit)? by bindRefusals::onTold"))
        val recovery = File("src/main/java/com/reau/app/dash/DashRecovery.kt").readText()
        assertTrue(recovery.contains("RideDiagnostics.warn(\"wifi\", tellLine(verdict.inARow))") && recovery.contains("onTold?.invoke(true)") && recovery.contains("onTold?.invoke(false)"))
        // A bind that worked and the switch off both go through bound(); nothing else resets the count and loses the notice.
        assertEquals("the switch off and a bind that worked", 2, Regex("""bindRefusals\.bound\(\)""").findAll(wifi).count())
        val notification = File("src/main/java/com/reau/app/service/ProjectionNotification.kt").readText()
        assertTrue("in the alert channel", notification.substringAfter("fun showBindRefused").substringBefore("fun clearBindRefused").contains("Notification.Builder(context, ALERT_CHANNEL_ID)"))
    }

    // -- (b) the alert channel muted or blocked ---------------------------------------------------------------------

    private val low = 2
    private val default = 3
    private val high = 4

    @Test
    fun `a channel with sound at its importance is loud`() {
        assertEquals(AlertChannelState.Level.LOUD, AlertChannelState.levelOf(true, high, sound = true, vibrates = true))
        assertEquals(AlertChannelState.Level.LOUD, AlertChannelState.levelOf(true, default, sound = true, vibrates = false))
        assertEquals("vibration alone is heard", AlertChannelState.Level.LOUD, AlertChannelState.levelOf(true, high, sound = false, vibrates = true))
    }

    @Test
    fun `no sound and no vibration, or an importance below the one that sounds, is muted`() {
        assertEquals(AlertChannelState.Level.MUTED, AlertChannelState.levelOf(true, high, sound = false, vibrates = false))
        assertEquals(AlertChannelState.Level.MUTED, AlertChannelState.levelOf(true, low, sound = true, vibrates = true))
        assertEquals(AlertChannelState.Level.MUTED, AlertChannelState.levelOf(true, 1, sound = true, vibrates = true))
    }

    @Test
    fun `a blocked channel or blocked notifications are blocked, ahead of muted`() {
        assertEquals(AlertChannelState.Level.BLOCKED, AlertChannelState.levelOf(true, 0, sound = false, vibrates = false))
        assertEquals(AlertChannelState.Level.BLOCKED, AlertChannelState.levelOf(false, high, sound = true, vibrates = true))
        assertEquals(AlertChannelState.Level.BLOCKED, AlertChannelState.levelOf(false, null, sound = false, vibrates = false))
        assertEquals(AlertChannelState.Level.BLOCKED, AlertChannelState.levelOf(false, low, sound = false, vibrates = false))
    }

    @Test
    fun `a channel not made yet is the loud one it will be made as, if the app may notify at all`() {
        assertEquals(AlertChannelState.Level.LOUD, AlertChannelState.levelOf(true, null, sound = false, vibrates = false))
        assertEquals("the unspecified importance (-1000) says nothing", AlertChannelState.Level.LOUD, AlertChannelState.levelOf(true, -1000, sound = false, vibrates = false))
    }

    @Test
    fun `the log says it when the alerts would not be heard, and nothing when they would`() {
        assertNull(AlertChannelState.line(AlertChannelState.Level.LOUD))
        assertTrue(AlertChannelState.line(AlertChannelState.Level.MUTED)!!.contains("muted"))
        assertTrue(AlertChannelState.line(AlertChannelState.Level.BLOCKED)!!.contains("blocks"))
        assertTrue(AlertChannelState.line(AlertChannelState.Level.MUTED)!!.startsWith("alerts:"))
    }

    @Test
    fun `the main screen shows a card for a muted channel and another for a blocked one, and none for a loud one`() {
        val stopped = Projection()
        val battery = com.reau.app.ui.BatteryUi()
        fun warnings(level: AlertChannelState.Level) =
            com.reau.app.ui.MainLook.warnings(stopped, battery) + com.reau.app.ui.MainLook.alertWarnings(level)
        assertEquals(emptyList<com.reau.app.ui.MainLook.Warning>(), warnings(AlertChannelState.Level.LOUD))
        assertEquals(listOf(com.reau.app.ui.MainLook.Warning.ALERTS_MUTED), warnings(AlertChannelState.Level.MUTED))
        assertEquals(listOf(com.reau.app.ui.MainLook.Warning.ALERTS_BLOCKED), warnings(AlertChannelState.Level.BLOCKED))
        assertEquals("the default of the state is loud", AlertChannelState.Level.LOUD, com.reau.app.ui.MainUiState().alertLevel)
    }

    @Test
    fun `the screen reads the channel at each return, the service says it in the log at the start, and the card opens Android's settings`() {
        val main = File("src/main/java/com/reau/app/MainActivity.kt").readText()
        assertTrue(main.contains("alertLevel = alertLevel"))
        assertTrue("read at each return, not only defined", Regex("""(?m)^\s+readAlertLevel\(\)$""").containsMatchIn(main))
        assertTrue(main.contains("onOpenAlertSettings = { com.reau.app.ui.AlertSettings.open(this) }"))
        val service = File("src/main/java/com/reau/app/service/ProjectionService.kt").readText()
        assertTrue(service.contains("AlertChannelState.line(AlertChannelState.read(this))?.let { RideDiagnostics.warn(\"projection\", it) }"))
        val parts = File("src/main/java/com/reau/app/ui/MainParts.kt").readText()
        assertTrue(parts.contains("Warning.ALERTS_MUTED, Warning.ALERTS_BLOCKED -> AlertWarning(warning, extras.onOpenAlertSettings)") && parts.contains("extras.onOpenAlertSettings"))
        val screen = File("src/main/java/com/reau/app/ui/MainScreen.kt").readText()
        assertTrue("the cards come after the other warnings", screen.contains("state.locationOff) +\n                        MainLook.alertWarnings(state.alertLevel)"))
        val settings = File("src/main/java/com/reau/app/ui/AlertSettings.kt").readText()
        assertTrue(settings.contains("Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS") && settings.contains("Settings.ACTION_APP_NOTIFICATION_SETTINGS"))
    }

    @Test
    fun `no permission was added for any of it`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val permissions = Regex("""<uses-permission[^>]*android:name="android\.permission\.([A-Z_]+)"""").findAll(manifest).map { it.groupValues[1] }.toSet()
        val before = setOf(
            "ACCESS_COARSE_LOCATION", "ACCESS_FINE_LOCATION", "ACCESS_NETWORK_STATE", "ACCESS_WIFI_STATE", "ANSWER_PHONE_CALLS", "CHANGE_NETWORK_STATE",
            "CHANGE_WIFI_STATE", "FOREGROUND_SERVICE", "FOREGROUND_SERVICE_CONNECTED_DEVICE", "FOREGROUND_SERVICE_LOCATION", "FOREGROUND_SERVICE_MICROPHONE",
            "INTERNET", "MODIFY_AUDIO_SETTINGS", "POST_NOTIFICATIONS", "READ_PHONE_STATE", "RECORD_AUDIO", "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", "WAKE_LOCK",
        )
        assertEquals("the permissions are the ones of before the three alerts (18)", before, permissions)
    }

    // -- (c) the hung dash in the earphone ---------------------------------------------------------------------------

    private fun samples(pcm: ByteArray): List<Int> = (0 until pcm.size / 2).map { i -> ((pcm[2 * i + 1].toInt() shl 8) or (pcm[2 * i].toInt() and 0xFF)).toShort().toInt() }

    @Test
    fun `the tone is three beeps of 180 ms with 120 ms between, in the guidance lane's format, 780 ms in all`() {
        val stream = StuckDashTone.STREAM
        assertEquals(AaAudioFeed.Stream.GUIDANCE, stream)
        val pcm = StuckDashTone.pcm()
        assertEquals("whole 16-bit frames", 0, pcm.size % stream.frameBytes)
        assertEquals(780, StuckDashTone.DURATION_MS)
        assertEquals(stream.sampleRate * 780 / 1000 * stream.frameBytes, pcm.size)
        val s = samples(pcm)
        // Where it is silent and where it sounds: the middle of each beep and of each gap.
        val perMs = stream.sampleRate / 1000
        for (b in 0 until 3) {
            val start = b * 300 * perMs
            assertTrue("beep $b is silent in its middle", s.subList(start + 90 * perMs, start + 100 * perMs).maxOf { abs(it) } > 5_000)
            if (b < 2) assertEquals("gap $b is silence", 0, s.subList(start + 185 * perMs, start + 295 * perMs).maxOf { abs(it) })
        }
    }

    @Test
    fun `the tone is moderate, fades at both ends of each beep and has no sample at full scale`() {
        val s = samples(StuckDashTone.pcm())
        val peak = s.maxOf { abs(it) }
        assertTrue("peak $peak", peak in 8_000..10_000)
        assertTrue("under a third of full scale", peak < 32_767 / 3)
        val perMs = StuckDashTone.STREAM.sampleRate / 1000
        for (b in 0 until 3) {
            val start = b * 300 * perMs
            assertEquals("a beep starts at silence", 0, s[start])
            assertTrue("fades in", s.subList(start, start + 2 * perMs).maxOf { abs(it) } < peak / 4)
            assertTrue("fades out", s.subList(start + 178 * perMs, start + 180 * perMs).maxOf { abs(it) } < peak / 4)
        }
    }

    @Test
    fun `the tone is a pitch of 880 Hz`() {
        val s = samples(StuckDashTone.pcm())
        val rate = StuckDashTone.STREAM.sampleRate
        val window = s.subList(rate * 60 / 1000, rate * 120 / 1000) // 60 ms inside the first beep
        val crossings = window.zipWithNext().count { (a, b) -> a < 0 && b >= 0 }
        // 60 ms of 880 Hz is 52.8 cycles.
        assertTrue("crossings $crossings", crossings in 51..54)
    }

    private class Probe : AaAudioPlayer.PhoneSpeaker {
        val writes = AtomicInteger()
        val written = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val released = CountDownLatch(1)
        override fun play() = Unit
        override fun write(data: ByteArray) { writes.incrementAndGet(); written += data.size }
        override fun pause() = Unit
        override fun flush() = Unit
        override fun stop() = Unit
        override fun release() { released.countDown() }
        override val routedDeviceType: Int? = null
    }

    @Test
    fun `the tone is played once on a player of its own, in the guidance lane, and the player is let go`() {
        val probe = Probe()
        val lanes = mutableListOf<AaAudioFeed.Stream>()
        val log = java.util.Collections.synchronizedList(mutableListOf<String>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        StuckDashTone.play(scope, { log += it }, holdMs = 20) { AaAudioPlayer({ log += it }, { stream -> lanes += stream; probe }) }
        assertTrue("never let go", probe.released.await(3, TimeUnit.SECONDS))
        assertEquals(listOf(AaAudioFeed.Stream.GUIDANCE), lanes)
        assertEquals("written whole, in one piece", listOf(StuckDashTone.pcm().size), probe.written.toList())
        assertTrue(log.toString(), log.any { it.contains("the hung dash's tone") })
    }

    @Test
    fun `a phone that cannot make the player says so in the log and plays nothing`() {
        val log = java.util.Collections.synchronizedList(mutableListOf<String>())
        val done = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        StuckDashTone.play(scope, { log += it; if (it.contains("could not be started")) done.countDown() }) { error("no audio") }
        assertTrue(done.await(3, TimeUnit.SECONDS))
        assertEquals(1, log.size)
    }

    @Test
    fun `the tone goes with the notice of the hung dash, once, and the dash cannot be told by its cards because there is no link to send them on`() {
        val live = File("src/main/java/com/reau/app/service/LiveSides.kt").readText()
        val stuck = live.substringAfter("wifi.onDashStuck = { stuck ->").substringBefore("wifi.onBindRefused")
        assertTrue(stuck.contains("ProjectionNotification.showDashStuck(context)"))
        assertTrue(stuck.contains("com.reau.app.aa.StuckDashTone.play(scope, log = { RideDiagnostics.log(\"audio\", it) })"))
        assertEquals("one play, in the branch that shows the notice", 1, Regex("""StuckDashTone\.play""").findAll(stuck).count())
        assertTrue(stuck.indexOf("showDashStuck") < stuck.indexOf("StuckDashTone.play") && stuck.indexOf("StuckDashTone.play") < stuck.indexOf("} else {"))
        // The tone is no setting and no permission: it goes through the player the app already has.
        val tone = File("src/main/java/com/reau/app/aa/StuckDashTone.kt").readText()
        assertTrue(tone.contains("AaAudioPlayer"))
        assertFalse(tone.contains("ToneGenerator") || tone.contains("MediaPlayer") || tone.contains("TextToSpeech") || tone.contains("Vibrator"))
        assertNotNull(tone)
    }
}

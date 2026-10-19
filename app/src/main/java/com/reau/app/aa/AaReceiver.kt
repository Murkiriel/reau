// Written for REau after OpenCfMoto (AGPL-3.0): app/src/main/java/dev/zanderp/opencfmoto/aa/AaReceiver.kt,
// commit 0abbe2a (itself glue over code ported from headunit-revived, AGPLv3). Kept: dialling out to Android Auto's
// head unit server on 127.0.0.1:5277 with an 800 ms connect timeout and 2 s between dials, one session at a time, and
// the order of a session (handshake, decoder surface, read loop). Not here: the listening socket on 5288 with its NSD
// registration and the AaSelfMode pokes (dead since Android Auto 17.4), the 20-dial limit, BikeWifi's process-wide
// network bind and its workaround (this app binds each dash socket instead), AaVideoBridge, the input sinks and the
// night timer (day and night come from the service through setNight). The microphone came back for Gemini,
// as a voice given in the Options, one AaMicrophone per session.
package com.reau.app.aa

import android.content.Context
import android.os.SystemClock
import android.view.Surface
import com.reau.app.OtherEdition
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Where the link to Android Auto stands. */
enum class AaLinkState {
    /** Not started, or stopped. */
    IDLE,

    /** Nothing answers on the port: the rider has to start the head unit server in Android Auto's developer options. */
    SERVER_OFF,

    /** Dialling for the first time, or connected and in the handshake. */
    CONNECTING,

    /** A session is up: Android Auto's messages are being read and answered. */
    ACTIVE,

    /** The session ended, or its handshake failed. The receiver dials again by itself. */
    DROPPED,

    /** Android Auto's server accepts the connection and has not answered three handshakes in a row. */
    UNRESPONSIVE,

    /**
     * Like [UNRESPONSIVE], but within [PreviousSessionClosing.WINDOW_MS] of REau's own stop, or of where this process
     * began to dial when the session that ended was not REau's to see (the other edition's): Android Auto is still
     * closing the previous session and clears by itself; the receiver keeps dialling.
     */
    CLOSING_PREVIOUS,

    /** Android Auto 17.3 or older was asked to dial in again and again, and never did (see [AaReceiver.CallIn]). */
    NOT_CALLING,
}

/**
 * The link to the phone's own Android Auto. Once started it dials the head unit server Android Auto opens on
 * 127.0.0.1 (the port of its [Options]), runs one session at a time, decoding its video onto [videoSurface], and
 * dials again whenever the server is not there or the session ends, until [stop]. However a session ends (Android
 * Auto leaving, a "bye" from it, a broken socket) the receiver tries again: the app has one job and its own off
 * switch.
 *
 * [onState] gets each change of [state], on the receiver's thread or, for [AaLinkState.IDLE], on the caller of
 * [stop].
 */
// One link's life: start, stop, the dialling loop, one session, and what a running session is told (night, input,
// a new configuration); split further, they would only pass the same session and lock around.
@Suppress("TooManyFunctions")
internal class AaReceiver(
    private val context: Context,
    config: AaConfig,
    private val videoSurface: Surface,
    private val onState: (AaLinkState) -> Unit,
    options: Options = Options(),
) {
    /** [redialMs] between dials while nothing answers; [afterDropMs] before the first dial after a session. */
    data class Timing(
        val redialMs: Long = 2_000,
        val afterDropMs: Long = 1_000,
        /** Between tries while Android Auto accepts and does not answer, so the tries are spaced. */
        val unresponsiveRedialMs: Long = 5_000,
        val dialTimeoutMs: Int = 800,
        /** How often a session writes its `aa-stats` line. */
        val statsEveryMs: Long = STATS_EVERY_MS,
    )

    /**
     * Android Auto 17.3 and older: instead of dialling the server, the receiver listens on [port] (127.0.0.1) and Android Auto
     * dials it. [poke] asks Android Auto to do so, before each wait of [waitMs]; it is not called while a session runs.
     */
    data class CallIn(val port: Int = AaCallsIn.PORT, val waitMs: Int = CALL_IN_WAIT_MS, val poke: () -> Unit)

    /** The debug option of [SessionRecorder]: each session's messages go to a file in [dir], up to [maxBytes]. */
    data class Recording(val dir: File, val maxBytes: Long = DEFAULT_RECORDING_BYTES)

    /**
     * What a test, or a setting, changes. [recording] null: nothing is recorded. [onNavigation] gets Android Auto's
     * turn-by-turn guidance (only sent if [AaConfig.navigation] announced the channel), and
     * [NavGuidance.INACTIVE] when a session ends; null: the channel is not read. [voice] makes the rider's voice for
     * Gemini (behind its switch), one per session; null: a request to open the
     * microphone is refused, as before. [onNowPlaying] and [onCaller] get the song and the ringing call (only
     * sent if [AaConfig.media] and [AaConfig.phone] announced their channels), and null when there is none
     * or a session ends; null: that channel is not read. [onMediaApp] gets the music app Android Auto's playback status
     * names, once per change (for the dash's title; only with the music channel read).
     */
    data class Options(
        val port: Int = HEAD_UNIT_SERVER_PORT,
        val timing: Timing = Timing(),
        val recording: Recording? = null,
        val onNavigation: ((NavGuidance) -> Unit)? = null,
        val voice: (() -> VoiceSource)? = null,
        val onNowPlaying: ((AaNowPlaying?) -> Unit)? = null,
        val onCaller: ((AaCaller?) -> Unit)? = null,
        val onMediaApp: ((String) -> Unit)? = null,
        /** The sound of the media and guidance sinks, when announced ([AaConfig.audio]); null, it is thrown away. */
        val onAudio: ((AaAudioFeed.Pcm) -> Unit)? = null,
        /** REau's last process was ended in the middle: the first unanswered handshake says so ([HandshakeStreak]). */
        val quickUnresponsive: Boolean = false,
        /** A session open with no picture from Android Auto for a while (true), and the picture come or the session over (false). */
        val onVideoSilent: ((Boolean) -> Unit)? = null,
        /** Non-null: Android Auto 17.3 and older, where it dials in ([CallIn]); null: the receiver dials the head unit server. */
        val callIn: CallIn? = null,
    )

    private val port = options.port
    private val timing = options.timing
    private val recording = options.recording
    private val onNavigation = options.onNavigation
    private val voice = options.voice
    private val onNowPlaying = options.onNowPlaying
    private val onCaller = options.onCaller
    private val onMediaApp = options.onMediaApp
    private val onAudio = options.onAudio
    private val quickUnresponsive = options.quickUnresponsive
    private val onVideoSilent = options.onVideoSilent
    private val callIn = options.callIn

    @Volatile var state: AaLinkState = AaLinkState.IDLE
        private set

    // What the last round of unanswered handshakes said (the dialling thread's only), to tell the window running out.
    private var lastUnansweredState: AaLinkState? = null

    // What the next session is told; each session reads it once, when it begins.
    @Volatile private var config: AaConfig = config

    private val lock = Any()

    // Counts the starts: a loop left over from before a stop sees it is no longer the current one.
    private var generation = 0
    private var running = false
    private var worker: Thread? = null

    // A poke cuts short the wait between dials (the server was just switched on).
    private val poke = Semaphore(0)

    @Volatile private var night = false
    @Volatile private var connection: SocketAccessoryConnection? = null
    @Volatile private var listener: ServerSocket? = null
    @Volatile private var session: AapTransport? = null

    fun start() {
        synchronized(lock) {
            if (running) {
                AaLog.i("AaReceiver: already running")
                return
            }
            running = true
            val mine = ++generation
            ConscryptInitializer.initialize()
            AaLog.i("AaReceiver: " + AndroidAuto.describe(AndroidAuto.installedVersion(context)))
            worker = thread(name = THREAD_NAME, isDaemon = true) { loop(mine) }
        }
    }

    /**
     * Day or night for Android Auto's night sensor. Kept across sessions: each new session's sensor starts
     * with it, and a change goes out at once to the session there is. A value equal to the last one sends nothing.
     */
    fun setNight(isNight: Boolean) {
        synchronized(lock) {
            if (night == isNight) return
            night = isNight
        }
        session?.sendNightMode(isNight)
    }

    /**
     * [config] for the sessions from now on (the density and margins apply without switching off). Android Auto
     * reads them only when a session begins, so the session going on is ended with a bye, as switching off does, and
     * the next one, dialled at once as after any drop, is told them.
     */
    fun reconfigure(config: AaConfig) {
        this.config = config
        val current = session ?: return
        AaLog.i("AaReceiver: new configuration (density ${config.density}, ${config.margins}): a new session")
        current.stop()
    }

    /** A key or a wheel step for Android Auto (the dash's joystick); nothing without a session. */
    fun sendInput(command: AaCommand) {
        val transport = session ?: return AaLog.i("no Android Auto session: $command not sent")
        val input = AaInput(transport::send, log = { AaLog.i(it) })
        when (command) {
            is AaCommand.Key -> input.sendKey(command.keycode)
            is AaCommand.Scroll -> input.sendScroll(command.delta)
            is AaCommand.Steps -> command.commands.forEach(::sendInput)
            is AaCommand.Touch -> input.sendTouch(command.action, command.x, command.y)
            // The phone's own business (LiveSides hands them to PhoneCalls); nothing goes to Android Auto.
            AaCommand.AnswerCall, AaCommand.EndCall, is AaCommand.CallVolume -> return AaLog.i("$command is not an Android Auto input")
        }
        // An input may change Android Auto's screen with no window event after it: read the screen again.
        if (command is AaCommand.Key && command.keycode == ServiceDiscoveryResponse.KEY_ENTER) AaScreens.selectSent()
        AaScreens.inputSent()
    }

    /**
     * Dial now instead of at the end of the wait (Android Auto's server was just switched on). Nothing while
     * a session runs.
     */
    fun dialSoon() {
        poke.release()
    }

    /** Ends the session, if there is one, with a "bye", and stops dialling. */
    fun stop() {
        val thread: Thread?
        synchronized(lock) {
            if (!running) return
            running = false
            thread = worker
            worker = null
        }
        // The stop's parts in the log, to see where the time of a slow Desligar goes.
        val begun = SystemClock.elapsedRealtime()
        session?.stop()
        val sessionDone = SystemClock.elapsedRealtime()
        connection?.disconnect()
        closeListener()
        thread?.interrupt()
        if (thread !== Thread.currentThread()) thread?.join(STOP_JOIN_MS)
        val now = SystemClock.elapsedRealtime()
        AaLog.i("AaReceiver: stop took ${now - begun} ms (the bye and the session's end ${sessionDone - begun} ms, the link's thread ${now - sessionDone} ms)")
        state = AaLinkState.IDLE
        AaRestartGap.stopped(context, android.os.SystemClock.elapsedRealtime(), System.currentTimeMillis())
        AaLog.i("AaReceiver: stopped")
        onState(AaLinkState.IDLE)
    }

    private fun isCurrent(mine: Int): Boolean = synchronized(lock) { running && generation == mine }

    private fun report(mine: Int, next: AaLinkState) {
        synchronized(lock) {
            if (!running || generation != mine || state == next) return
            state = next
        }
        onState(next)
    }

    private fun logReceiver(line: String) = AaLog.i("AaReceiver: $line")

    /** Before the first dial: takes the previous process's stop if it is inside the window, and does not dial on top of a stop. */
    private fun waitAfterTheLastStop() {
        // A process that came back inside the window of the previous one's stop still counts it (see AaRestartGap).
        AaRestartGap.restore(context, android.os.SystemClock.elapsedRealtime(), System.currentTimeMillis())?.let(::logReceiver)
        // Not on top of a stop that just happened (Android Auto fell over with "Already connected").
        val wait = AaRestartGap.delayMs(android.os.SystemClock.elapsedRealtime(), AaRestartGap.lastStoppedAtMs)
        if (wait > 0) {
            logReceiver("waiting $wait ms after the stop before dialling")
            Thread.sleep(wait)
        }
    }

    private fun loop(mine: Int) {
        report(mine, AaLinkState.CONNECTING)
        val refused = RefusedDials(callIn != null)
        val unanswered = HandshakeStreak(quickUnresponsive)
        lastUnansweredState = null
        if (quickUnresponsive) AaLog.i("AaReceiver: REau was ended mid-session a moment ago; the first unanswered handshake restarts Android Auto's server")
        // A session may have just ended that this REau did not see (the other edition's, on the same server): the doubt
        // is counted from here. Not for a REau ended mid-session (quick: its server is known to hold the old session
        // and does not clear by itself) nor for the old Android Auto, which dials in and has no such server.
        if (callIn == null && !quickUnresponsive) AaRestartGap.dialBegun(android.os.SystemClock.elapsedRealtime())
        try {
            waitAfterTheLastStop()
            while (isCurrent(mine)) {
                val socket = nextSocket(mine, tries = refused.count)
                if (socket == null) {
                    refused.refused(android.os.SystemClock.elapsedRealtime())
                    continue
                }
                refused.connected(android.os.SystemClock.elapsedRealtime())?.let(::logReceiver)
                // A "still closing the previous session" or a "not responding" already said is not taken back by the next
                // dial being accepted: only an answer (or the port refusing) takes it back.
                val held = synchronized(lock) { state }
                report(
                    mine,
                    PreviousSessionClosing.stateOnAccept(
                        held, android.os.SystemClock.elapsedRealtime(), AaRestartGap.lastStoppedAtMs, unanswered.everAnswered,
                        AaRestartGap.maybeClosingSinceMs,
                    ),
                )
                afterSession(mine, unanswered, handshook = runSession(mine, socket))
            }
        } catch (_: InterruptedException) {
            // stop() woke the loop
        } finally {
            closeListener()
        }
    }

    /**
     * The next connection, or null after a try that found none (and its wait): Android Auto 17.3 and older is asked to dial in
     * ([callIn]); any other is dialled at its developer server, and nothing answering there says the server is off.
     */
    private fun nextSocket(mine: Int, tries: Int): Socket? {
        callIn?.let { calls ->
            val called = acceptCall(calls, logFailure = tries == 0)
            // Asked again and again and never called: said once, and it goes on asking.
            if (called == null && tries + 1 >= CALL_IN_TRIES_BEFORE_SAYING) report(mine, AaLinkState.NOT_CALLING)
            return called
        }
        val socket = dial(logFailure = tries == 0)
        if (socket == null) {
            report(mine, AaLinkState.SERVER_OFF)
            // The server that was closing a session is gone: no doubt about a session left to count.
            AaRestartGap.portRefused()
            pause(timing.redialMs)
        }
        return socket
    }

    /**
     * Asks Android Auto to dial in and waits [CallIn.waitMs] for it: the connection, or null (nothing came, or the port
     * could not be opened, or [stop] closed it). The port is opened once and kept between tries, so a dial that lands
     * just after a wait is still taken by the next one.
     */
    private fun acceptCall(calls: CallIn, logFailure: Boolean): Socket? {
        val server = listener ?: try {
            ServerSocket(calls.port, 1, InetAddress.getByAddress(IPV4_LOOPBACK)).also { listener = it }
        } catch (e: IOException) {
            AaLog.w("AaReceiver: the port ${calls.port} cannot be opened for Android Auto (${e.message})")
            Thread.sleep(calls.waitMs.toLong())
            return null
        }
        if (logFailure) AaLog.i("AaReceiver: listening on 127.0.0.1:${calls.port}; asking Android Auto to dial in")
        server.soTimeout = calls.waitMs
        calls.poke()
        return try {
            server.accept()
        } catch (_: SocketTimeoutException) {
            null
        } catch (_: IOException) {
            // closed by stop()
            null
        }
    }

    private fun closeListener() {
        val server = listener ?: return
        listener = null
        try {
            server.close()
        } catch (_: IOException) {
            // already closed
        }
    }

    /** After a session: dropped and dialled again soon, or, from the third unanswered handshake, unresponsive. */
    private fun afterSession(mine: Int, unanswered: HandshakeStreak, handshook: Boolean) {
        // Stopped: no dial again, so no wait for it (the second before dialling again used to hold the Desligar,
        // the stop's interrupt swallowed by the video decoder waiting on its own thread).
        if (!isCurrent(mine)) return
        if (!unanswered.after(handshook)) {
            report(mine, AaLinkState.DROPPED)
            Thread.sleep(timing.afterDropMs)
            return
        }
        // Right after REau's own stop, or from where this process began to dial when a session it did not see may have just
        // ended, it is Android Auto still closing the previous session (~70 s), not a stuck one.
        val now = android.os.SystemClock.elapsedRealtime()
        val kind = PreviousSessionClosing.unansweredState(
            now, AaRestartGap.lastStoppedAtMs, unanswered.everAnswered, AaRestartGap.maybeClosingSinceMs,
        )
        // The wait for a "closing" that did not clear is said once, when it runs out: the server is stuck (seen 18/10: mute for
        // 185 s and 204 s in two runs), and from here the one restart is due.
        if (PreviousSessionClosing.windowEnded(lastUnansweredState, kind)) {
            AaLog.w(
                "AaReceiver: the wait for the previous session to close ran out (${PreviousSessionClosing.WINDOW_MS / 1_000} s) and " +
                    "Android Auto is still mute: it is stuck, not closing",
            )
        }
        lastUnansweredState = kind
        // Said once when it begins; the state stays until a handshake is answered.
        if (unanswered.inARow == unanswered.threshold) {
            AaLog.w(
                "AaReceiver: Android Auto accepts the connection and does not answer (${unanswered.threshold} handshakes in a row);" +
                    when {
                        kind != AaLinkState.CLOSING_PREVIOUS -> " force-stopping Android Auto clears it"
                        PreviousSessionClosing.within(now, AaRestartGap.lastStoppedAtMs) ->
                            " it is still closing the previous session, which clears by itself"
                        else -> " it may be closing a session this REau did not see end (another app on its server), which clears by itself"
                    } + OtherEdition.logNote(context),
            )
        }
        report(mine, kind)
        pause(timing.unresponsiveRedialMs)
    }

    /** Waits [ms] between dials, or less if [dialSoon] pokes; a poke from before the wait does not count. */
    private fun pause(ms: Long) {
        poke.drainPermits()
        poke.tryAcquire(ms, TimeUnit.MILLISECONDS)
    }

    private fun dial(logFailure: Boolean): Socket? {
        // 127.0.0.1 as a number: Android's "loopback address" is ::1, where Android Auto does not listen.
        val server = InetSocketAddress(InetAddress.getByAddress(IPV4_LOOPBACK), port)
        val socket = Socket()
        return try {
            socket.connect(server, timing.dialTimeoutMs)
            socket
        } catch (e: IOException) {
            socket.close()
            if (logFailure) {
                AaLog.w("AaReceiver: no answer from the head unit server on $server (${e.javaClass.simpleName}: ${e.message}); will keep dialling")
            }
            null
        }
    }

    /** Runs one session on [socket] and returns when it has ended: whether its handshake was answered. */
    @Suppress("LongMethod") // one straight run of setup/serialization; cutting it adds indirection and no clarity
    private fun runSession(mine: Int, socket: Socket): Boolean {
        val config = this.config
        val link = SocketAccessoryConnection(socket)
        val transport = AapTransport(context, protocolMinor = config.protocolMinor)
        val ended = CountDownLatch(1)
        val clean = AtomicBoolean()
        transport.onQuit = { wasClean ->
            clean.set(wasClean)
            ended.countDown()
        }
        // The session counted before the decoder, one aa-stats line every statsEveryMs.
        val stats = AaStats()
        transport.stats = stats
        val sink = AaVideoSink(stats) { transport.triggerFocusCycleRecovery() }
        val statsLine = Executors.newSingleThreadScheduledExecutor { Thread(it, "aa-stats").apply { isDaemon = true } }
        val recorder = recording?.let { option ->
            SessionRecorder(option.dir, SessionRecorder.nameFor(System.currentTimeMillis()), option.maxBytes, { AaLog.i(it) })
        }
        connection = link
        val microphone = voice?.let { AaMicrophone(it(), transport::send, log = { line -> AaLog.i(line) }) }
        var handler: AapMessageHandlerType? = null
        val silence = VideoSilence()
        try {
            AaLog.i("AaReceiver: connected to the head unit server on :$port, starting the handshake")
            if (!transport.startHandshake(link)) {
                AaLog.w("AaReceiver: the handshake failed")
                return false
            }
            // Answered: a later process (this one killed mid-session) has no old stop to count a window from.
            AaRestartGap.answered(context)
            sink.setSurface(videoSurface)
            // The music and phone status channels, read when announced and asked for.
            val cards = if (onNowPlaying != null || onCaller != null) AapCards(onNowPlaying, onCaller, onMediaApp) else null
            val reading = AapMessageHandlerType(transport, config, sink::process, onNavigation, microphone, cards, onAudio)
            handler = reading
            val counted = AaStatsHandler(stats, reading)
            transport.startReading(if (recorder == null) counted else RecordingHandler(recorder, counted))
            statsLine.scheduleWithFixedDelay({ AaLog.i(stats.windowLine()) }, timing.statsEveryMs, timing.statsEveryMs, TimeUnit.MILLISECONDS)
            session = transport
            // The session's day/night: if Android Auto has already started the night sensor (with the transport's
            // default, day) the value goes out now; if not, it is what the sensor will start with. Either way a
            // change made meanwhile is not missed.
            if (transport.nightMode != night) transport.sendNightMode(night)
            report(mine, AaLinkState.ACTIVE)
            onVideoSilent?.let { tell ->
                statsLine.scheduleWithFixedDelay({
                    silence.check(SystemClock.elapsedRealtime(), stats.totals().units)?.let { silent ->
                        if (silent) {
                            AaLog.w("AaReceiver: the session is open and Android Auto has sent no picture for ${VideoSilence.SILENT_AFTER_MS / 1000} s")
                        } else {
                            AaLog.i("AaReceiver: Android Auto's picture came")
                        }
                        tell(silent)
                    }
                }, SILENCE_CHECK_MS, SILENCE_CHECK_MS, TimeUnit.MILLISECONDS)
            }
            ended.await()
            AaLog.i("AaReceiver: session ended (clean=${clean.get()}, userExit=${transport.wasUserExit})")
        } finally {
            session = null
            microphone?.stop("session ended")
            awaitReadLoopGone(transport)
            statsLine.shutdownNow()
            if (silence.isSilent) onVideoSilent?.invoke(false)
            AaLog.i(stats.windowLine() + " (session end)")
            // A turn must never outlive its session (as MOTO-HUB's AaNavigationGuidance.clear).
            onNavigation?.invoke(NavGuidance.INACTIVE)
            // Nor a song or a call card.
            onNowPlaying?.invoke(null)
            onCaller?.invoke(null)
            recorder?.close()
            sink.release()
            link.disconnect()
            connection = null
        }
        return true
    }

    /** The video sink must not be released under a read loop that may still hand it a message. */
    private fun awaitReadLoopGone(transport: AapTransport) {
        val deadline = SystemClock.elapsedRealtime() + READ_LOOP_EXIT_MS
        while (transport.isAlive && SystemClock.elapsedRealtime() < deadline) {
            try {
                Thread.sleep(READ_LOOP_POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    companion object {
        /** The port of Android Auto's head unit server: the one Google's Desktop Head Unit talks to. */
        const val HEAD_UNIT_SERVER_PORT = 5277

        /** How long a try waits for Android Auto 17.3 to dial in after it was asked. */
        const val CALL_IN_WAIT_MS = 8_000

        /** Failed tries in a row after which the rider is told Android Auto is not calling. */
        const val CALL_IN_TRIES_BEFORE_SAYING = 5

        /** A few minutes of an 800×480 session. */
        const val DEFAULT_RECORDING_BYTES = 100L * 1024 * 1024
        private val IPV4_LOOPBACK = byteArrayOf(127, 0, 0, 1)
        private const val SILENCE_CHECK_MS = 1_000L
        private const val THREAD_NAME = "aa-link"
        private const val STOP_JOIN_MS = 3_000L
        private const val READ_LOOP_EXIT_MS = 1_500L
        private const val READ_LOOP_POLL_MS = 10L
        private const val STATS_EVERY_MS = 5_000L
    }
}

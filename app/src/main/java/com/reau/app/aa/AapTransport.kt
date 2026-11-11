// Copied from OpenCfMoto (AGPL-3.0): app/src/main/java/dev/zanderp/opencfmoto/aa/AapTransport.kt, commit 0abbe2a.
// Changes: package. Cut loose from what a session's builder now provides: the constructor no longer takes a VideoDecoder
// nor builds AapVideo, startReading() is given the message handler, quit() no longer releases the video path (the
// onQuit callback's owner does), triggerFocusCycleRecovery() is internal so the video path can call it. The
// microphone hook is cut. From open-headunit (AGPL-3.0, commit 489df5de, aap/AapTransport.kt): the last version
// request waits out the whole handshake budget. From the same project (commit 68c2bbb5): over a socket there is
// one version request, not up to three (the loop is kept, [versionRequests] is AaConfig.VERSION_REQUESTS; open-headunit
// keeps three only for USB, which REau does not have). Video focus changes and sensor requests are counted in
// [stats]. The focus cycle that asks for a keyframe goes through [KeyframeCycleGate] and gives focus back on the send thread.
// Adapted from headunit-revived (AGPLv3): aap/AapTransport.kt
// Trimmed for video-only headless projection: no audio decoder / audio manager / mic recorder /
// notification / Settings. Keeps the send+poll HandlerThreads, the AAP version+SSL handshake,
// and the inbound message loop. gainVideoFocus's Activity broadcast is gone (AapControlMedia
// grants video focus directly).
package com.reau.app.aa

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.SparseIntArray
import com.reau.app.aa.proto.Control
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AapTransport(
    private val context: Context,
    private val externalSsl: AapSslContext? = null,
    private val protocolMajor: Int = 1,
    private val protocolMinor: Int = Messages.MINOR_DEFAULT,
    /** How many times the version request may be sent; the last one waits out the whole budget. */
    private val versionRequests: Int = AaConfig.VERSION_REQUESTS,
    /** How long the video focus stays away in a keyframe cycle, asked at each cycle ([FocusGap]): 100 ms unless the experimental switch is on. */
    private val focusGapMs: () -> Long = { FocusGap.DEFAULT_MS },
) {
    val ssl: AapSsl = externalSsl ?: AapSslContext(SingleKeyKeyManager(context))

    private var sendThread: HandlerThread? = null
    private var pollThread: HandlerThread? = null
    private val sessionIds = SparseIntArray(4)

    // Released when Android Auto answers REau's bye.
    private val byeAnswer = CountDownLatch(1)
    private val quitLock = Any()
    // Written by the read thread (Android Auto starts a sensor) and read by whoever reports the night mode.
    private val startedSensors: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private var connection: AccessoryConnection? = null
    private var aapRead: AapRead? = null

    var ignoreNextStopRequest: Boolean = false
    /** Set when VIDEO_FOCUS_NATIVE triggers a stop (user tapped Exit on the phone). */
    @Volatile var wasUserExit: Boolean = false
    @Volatile var onQuit: ((Boolean) -> Unit)? = null

    /** Where video focus changes and sensor requests are counted; null counts nothing. */
    @Volatile var stats: AaStats? = null

    private var pollHandler: Handler? = null
    private val pollHandlerCallback = Handler.Callback {
        val readInstance = aapRead ?: return@Callback false
        val ret = readInstance.read()
        if (ret < 0) {
            AaLog.i("Quitting because ret < 0 ($ret)")
            this.quit(clean = (ret == -2))
            return@Callback true
        }
        // A stop from another thread quits the looper while this read is on; posting to it then made Android
        // log "sending message to a Handler on a dead thread" at the end of every such session.
        if (!ending) pollHandler?.let { if (!it.hasMessages(MSG_POLL)) it.sendEmptyMessage(MSG_POLL) }
        return@Callback true
    }

    /** Set when [quit] begins: the poll loop stops posting itself. */
    @Volatile private var ending = false

    private var sendHandler: Handler? = null
    private val sendHandlerCallback = Handler.Callback {
        this.sendEncryptedMessage(data = it.obj as ByteArray, length = it.arg2)
        return@Callback true
    }

    val isAlive: Boolean
        get() = pollThread?.isAlive ?: false

    private val keyframeGate = KeyframeCycleGate()
    private val focusTiming = FocusCycleTiming()

    /**
     * Whether the cycle must wait because the compositor the picture goes to is stuck (a keyframe is no use to a consumer that
     * cannot show it). Set by the session; read with every request and tick.
     */
    @Volatile internal var keyframeBlocked: () -> Boolean = { false }

    /**
     * Asks Android Auto for a keyframe by taking video focus away and giving it back, when [keyframeGate] allows it. A request
     * that has to wait is kept and goes out from [keyframeTick].
     */
    internal fun triggerFocusCycleRecovery() {
        val now = SystemClock.elapsedRealtime()
        when (val verdict = keyframeGate.request(now, keyframeBlocked())) {
            KeyframeCycleGate.Verdict.GO -> cycleVideoFocus()
            KeyframeCycleGate.Verdict.WAIT_SPACING ->
                AaLog.i("AapTransport: keyframe cycle waits for the second to pass, ${keyframeGate.waitLeftMs(now)} ms left")
            else -> {
                AaLog.w("AapTransport: keyframe cycle held back: ${holdName(verdict)}, ${keyframeGate.waitLeftMs(now) / 1000} s left")
                com.reau.app.data.ProjectionSummary.current.watchdog("keyframe-gate")
            }
        }
    }

    /** Once a second: lets out a keyframe request that had to wait. */
    internal fun keyframeTick() {
        // Debug builds only: a press of "Focus cycle" asks for the keyframe now, through the same gate as a stalled picture would.
        if (com.reau.app.debug.FaultInjection.takeFocusCycle()) {
            AaLog.w("AapTransport: a keyframe asked for by the debug build's focus cycle trigger")
            triggerFocusCycleRecovery()
        }
        focusTiming.overdue(SystemClock.elapsedRealtime())?.let { AaLog.w("AapTransport: $it") }
        if (keyframeGate.takePending(SystemClock.elapsedRealtime(), keyframeBlocked())) {
            AaLog.i("AapTransport: the keyframe request that waited goes out")
            cycleVideoFocus()
        }
    }

    private fun holdName(verdict: KeyframeCycleGate.Verdict) =
        if (verdict == KeyframeCycleGate.Verdict.WAIT_BLOCKED) "the compositor is stuck" else "storm guard"

    private fun cycleVideoFocus() {
        val gapMs = focusGapMs()
        AaLog.w("AapTransport: Requesting recovery keyframe via focus cycle.${FocusGap.words(gapMs)}")
        focusTiming.cycled(SystemClock.elapsedRealtime(), gapMs)
        send(VideoFocusEvent(gain = false, unsolicited = false))
        // The gain goes out on the send thread, not the main one: a main thread held up (a slow frame, the cold start) would leave Android Auto without focus.
        sendHandler?.postDelayed({
            if (isAlive) send(VideoFocusEvent(gain = true, unsolicited = true))
        }, gapMs)
    }

    /** A keyframe of Android Auto arrived: the first one after a focus cycle says how long it took ([FocusCycleTiming]). */
    internal fun keyframeArrived() {
        focusTiming.keyframe(SystemClock.elapsedRealtime())?.let { AaLog.i("AapTransport: $it") }
    }

    internal fun startSensor(type: Int) {
        startedSensors.add(type)
        stats?.sensor()
    }

    /** The last night value we reported (also the value sent when AA starts the NIGHT sensor). */
    @Volatile var nightMode: Boolean = false

    /**
     * Report day/night to Android Auto so Maps switches its map theme. No-op until AA has actually
     * started the NIGHT sensor (it requests it during channel setup); [AapControlSensor] sends the
     * initial value at that point, and this handles later toggles.
     */
    fun sendNightMode(isNight: Boolean) {
        nightMode = isNight
        if (!startedSensors.contains(SENSOR_NIGHT)) return
        send(NightModeEvent(isNight))
    }

    private fun sendEncryptedMessage(data: ByteArray, length: Int): Int {
        val ba = ssl.encrypt(AapMessage.HEADER_SIZE, length - AapMessage.HEADER_SIZE, data) ?: return -1
        ba.data[0] = data[0]
        ba.data[1] = data[1]
        Utils.intToBytes(ba.limit - AapMessage.HEADER_SIZE, 2, ba.data)
        val size = connection?.sendBlocking(ba.data, ba.limit, 250) ?: -1
        if (AaLog.LOG_VERBOSE) AaLog.v("Sent size: %d", size)
        return 0
    }

    /**
     * Ends the session with a bye and waits up to [BYE_WAIT_MS] for Android Auto's answer before quitting (Android
     * Auto's head unit server crashed, a NullPointerException on its ServerSocket, about a second after REau ended a
     * session, when REau closed a blind 150 ms after its bye). Whether and when the answer came
     * goes to the log, so a next crash shows the order.
     */
    internal fun stop() {
        AaLog.i("AapTransport stopping and sending byebye")
        val byebye = Control.ByeByeRequest.newBuilder().setReason(Control.ByeByeReason.USER_SELECTION).build()
        val sentAt = SystemClock.elapsedRealtime()
        send(AapMessage(Channel.ID_CTR, Control.ControlMsgType.MESSAGE_BYEBYE_REQUEST_VALUE, byebye))
        if (Thread.currentThread() == pollThread) {
            // Called while handling a message (Android Auto's own Exit, AapControl): the answer could only be read by
            // this same thread, so waiting for it would always run out. The short pause of before.
            SystemClock.sleep(BYE_PAUSE_ON_READ_THREAD_MS)
            return quit()
        }
        val answered = byeAnswer.await(BYE_WAIT_MS, TimeUnit.MILLISECONDS)
        if (answered) {
            AaLog.i("AapTransport: bye answered by Android Auto in ${SystemClock.elapsedRealtime() - sentAt} ms")
        } else {
            AaLog.i("AapTransport: no answer to the bye in $BYE_WAIT_MS ms; closing anyway")
        }
        // A bye that Android Auto answered is a clean end (otherwise every stop by the rider is logged as
        // clean=false, and the log reader counts each as Android Auto falling over).
        quit(clean = answered)
    }

    internal fun quit(clean: Boolean = false) {
        // Taken in one step: the read thread, the bye's timeout and a stop from outside can all reach here at once, and a
        // read-then-clear let two of them run the same callback and the same teardown.
        val cb = synchronized(quitLock) { onQuit.also { onQuit = null } } ?: return
        AaLog.i("AapTransport quitting (clean=$clean)")
        ending = true
        cb.invoke(clean)
        pollThread?.quit()
        sendThread?.quit()
        // So the Desligar is quick: the read thread waits on the connection, so without closing
        // it first the wait below ran out every time (1 s, the bye already answered). The session is over either way.
        if (Thread.currentThread() != pollThread) connection?.disconnect()
        val joining = SystemClock.elapsedRealtime()
        try {
            if (Thread.currentThread() != pollThread) pollThread?.join(1000)
            sendThread?.join(1000)
        } catch (e: InterruptedException) {
            AaLog.e("Failed to join threads", e)
        }
        AaLog.i("AapTransport: its threads ended in ${SystemClock.elapsedRealtime() - joining} ms")
        aapRead = null
        ssl.release()
        pollHandler = null
        sendHandler = null
        pollThread = null
        sendThread = null
    }

    /** Phase 1: create send/poll threads and run the version + SSL handshake. */
    internal fun startHandshake(connection: AccessoryConnection): Boolean {
        AaLog.i("Start Aap transport handshake for $connection")
        this.connection = connection
        wasUserExit = false
        answeredVersion = null

        ending = false
        sendThread = HandlerThread("AapTransport:Handler::Send", Process.THREAD_PRIORITY_AUDIO)
        sendThread!!.start()
        sendHandler = Handler(sendThread!!.looper, sendHandlerCallback)

        pollThread = HandlerThread("AapTransport:Handler::Poll", Process.THREAD_PRIORITY_AUDIO)
        pollThread!!.start()
        pollHandler = Handler(pollThread!!.looper, pollHandlerCallback)

        val handshook = handshake(connection)
        // Protocol 6.0, asked on the experimental switch: whether it worked is kept, and the ride log says what was asked and answered (ProtocolAsk).
        ProtocolAsk.settle(ProtocolAsk.Version(protocolMajor, protocolMinor), handshook, answeredVersion) { line, failed ->
            if (failed) AaLog.w(line) else AaLog.i(line)
        }
        if (!handshook) {
            quit()
            AaLog.e("Handshake failed")
            return false
        }
        return true
    }

    /** Phase 2: begin the inbound message loop; [handler] gets every decrypted message. Call after the decoder
     *  surface is set. */
    internal fun startReading(handler: AapMessageHandler) {
        AaLog.i("Start Aap transport read loop")
        // Android Auto's answer to REau's bye is noticed here, whoever handles the message.
        val watching = object : AapMessageHandler {
            override fun handle(message: AapMessage) {
                if (message.channel == Channel.ID_CTR && message.type == Control.ControlMsgType.MESSAGE_BYEBYE_RESPONSE_VALUE) byeAnswer.countDown()
                handler.handle(message)
            }
        }
        aapRead = AapRead.Factory.create(connection!!, ssl, watching)
        pollHandler?.sendEmptyMessage(MSG_POLL)
        sendHandler?.postDelayed(pinger, PING_INTERVAL_MS)
    }

    /**
     * A ping to Android Auto every [PING_INTERVAL_MS] while the session lasts. With its screen still
     * (the app list after "home" from the joystick) Android Auto sends nothing, and the read gives up after 15 s of
     * silence (AapReadSingleMessage): the picture on the dash froze and the session started over.
     * Its answer keeps the read alive; a peer that does not answer even that still ends the session, as before. Runs
     * on the send thread, which ends with the session.
     */
    private val pinger = object : Runnable {
        override fun run() {
            val ping = Control.PingRequest.newBuilder().setTimestamp(SystemClock.elapsedRealtimeNanos()).build()
            send(AapMessage(Channel.ID_CTR, Control.ControlMsgType.MESSAGE_PING_REQUEST_VALUE, ping))
            sendHandler?.postDelayed(this, PING_INTERVAL_MS)
        }
    }

    /** What Android Auto answered to the version request of this handshake ("1.7, status 0"); null before an answer. */
    @Volatile private var answeredVersion: String? = null

    /** Bytes 4 and 5 of a frame header hold the message type; 2 is VERSION_RESPONSE. */
    private fun isVersionResponseType(buffer: ByteArray): Boolean = buffer[4] == 0.toByte() && buffer[5] == 2.toByte()

    @Suppress("TooGenericExceptionCaught") // any handshake failure is logged and ends the attempt
    private fun handshake(connection: AccessoryConnection): Boolean {
        try {
            if (!connection.isSingleMessage) SystemClock.sleep(500)

            val buffer = ByteArray(Messages.DEF_BUFFER_LENGTH)

            if (!connection.isSingleMessage) {
                var drained = 0
                while (true) {
                    val n = try { connection.recvBlocking(buffer, buffer.size, 50, false) } catch (_: Exception) { -1 }
                    if (n <= 0) break
                    drained += n
                }
                if (drained > 0) AaLog.i("Handshake: Drained $drained bytes of stale data before version request")
            }

            AaLog.i("Handshake: Starting version request $protocolMajor.$protocolMinor. TS: ${SystemClock.elapsedRealtime()}")
            val version = Messages.versionRequestFor(protocolMajor, protocolMinor)
            var ret: Int
            var attempt = 0
            var received = false
            val versionDeadline = SystemClock.elapsedRealtime() + HANDSHAKE_TIMEOUT_MS
            while (attempt < versionRequests && connection.isConnected) {
                if (SystemClock.elapsedRealtime() >= versionDeadline) {
                    AaLog.e("Handshake: Version exchange timed out after $attempt attempt(s).")
                    return false
                }
                attempt++
                ret = connection.sendBlocking(version, version.size, 2000)
                AaLog.d("Handshake: Version request sent. ret: $ret. attempt: $attempt.")
                if (ret < 0) {
                    AaLog.w("Handshake: Version request send failed (ret=$ret), attempt $attempt")
                    SystemClock.sleep(200)
                    continue
                }

                // The last request waits out the whole budget: a slow peer can answer after 6 s, and giving up
                // there leaves its answers for the next attempt.
                val recvDeadline = if (attempt < versionRequests) SystemClock.elapsedRealtime() + 2000
                    else maxOf(versionDeadline, SystemClock.elapsedRealtime() + 2000)
                while (SystemClock.elapsedRealtime() < recvDeadline) {
                    val remaining = (recvDeadline - SystemClock.elapsedRealtime()).toInt().coerceAtLeast(100)
                    ret = connection.recvBlocking(buffer, buffer.size, remaining, false)
                    if (ret <= 0) break
                    if (ret >= 6 && buffer[0] == 0.toByte() && isVersionResponseType(buffer)) {
                        AaLog.i("Handshake: Version response received: ${Messages.versionAnswer(buffer, ret)} (ret=$ret, attempt=$attempt).")
                        answeredVersion = Messages.versionAnswer(buffer, ret)
                        Messages.versionRefusal(buffer, ret)?.let { AaLog.w(Messages.versionRefusalLine(it, protocolMajor, protocolMinor)) }
                        received = true
                        break
                    }
                    val ch = buffer[0].toInt() and 0xFF
                    val type = ((buffer[4].toInt() and 0xFF) shl 8) or (buffer[5].toInt() and 0xFF)
                    AaLog.w("Handshake: Ignoring unexpected message (ch=$ch, type=0x${type.toString(16)}, len=$ret). Waiting for VERSION_RESPONSE.")
                }
                if (received) break
                AaLog.w("Handshake: No VERSION_RESPONSE within ${if (attempt < versionRequests) "2s" else "the handshake budget"} (attempt $attempt)")
                SystemClock.sleep(200)
            }

            if (!received) {
                AaLog.e("Handshake: Version request/response failed after $attempt attempt(s).")
                return false
            }

            AaLog.d("Handshake: Starting SSL handshake via performHandshake().")
            if (!ssl.performHandshake(connection)) {
                AaLog.e("Handshake: SSL performHandshake failed.")
                return false
            }
            ssl.postHandshakeReset()
            AaLog.d("Handshake: SSL buffers reset after handshake.")

            val status = Messages.statusOk
            ret = connection.sendBlocking(status, status.size, 2000)
            if (ret < 0) {
                AaLog.e("Handshake: Status OK send failed ret: $ret")
                return false
            }
            AaLog.i("Handshake: Status OK sent (%d). Handshake successful.", ret)
            return true
        } catch (e: Exception) {
            AaLog.e("Handshake failed with exception", e)
            return false
        }
    }

    fun send(message: AapMessage) {
        val handler = sendHandler
        if (handler == null) {
            AaLog.i("Cannot send message, handler is null (quitting?)")
        } else {
            if (AaLog.LOG_VERBOSE) AaLog.v(message.toString())
            if (message is VideoFocusEvent) stats?.focus()
            handler.sendMessage(handler.obtainMessage(MSG_SEND, 0, message.size, message.data))
        }
    }

    internal fun sendMediaAck(channel: Int) = send(MediaAck(channel, sessionIds.get(channel)))

    internal fun setSessionId(channel: Int, sessionId: Int) = sessionIds.put(channel, sessionId)

    companion object {
        private const val MSG_POLL = 1
        private const val MSG_SEND = 2
        private const val HANDSHAKE_TIMEOUT_MS = 10_000L
        /** A third of the 15 s read timeout: two lost answers in a row still leave the session up. */
        internal const val PING_INTERVAL_MS = 5_000L
        /** How long the end of a session waits for Android Auto's answer to the bye. */
        internal const val BYE_WAIT_MS = 1_000L
        private const val BYE_PAUSE_ON_READ_THREAD_MS = 150L

        /** Sensors.SensorType.NIGHT value (see proto). */
        private const val SENSOR_NIGHT = 10
    }
}

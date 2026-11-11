package com.reau.app.stream

import android.os.SystemClock
import com.reau.app.data.RideDiagnostics
import com.reau.app.dash.BitrateMode
import com.reau.app.dash.DashConfig
import com.reau.app.dash.video.DashEncoder
import com.reau.app.dash.video.PendingFrames
import com.reau.app.settings.REauSettings
import com.reau.app.util.CodecErrors
import com.reau.app.util.StageStats
import com.reau.app.video.DashCompositor
import com.reau.app.video.Reading
import com.reau.app.video.StatsFeed
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The video stream to the dash, from the compositor's output to RTP packets:
 *
 *     compositor ──▶ DashEncoder ──▶ RtpOutput (keyframe gate, NAL processing, RTP) ──▶ [Dash.sendRtp]
 *
 * It owns the encoder and the output and nothing else: the compositor (and the Android Auto decoder feeding it)
 * lives on while streams to the dash come and go. Every decision — pace, bitrate, keyframe requests, when to
 * rebuild the encoder, the line logged every 5 s — is [StreamGovernor]'s; this class only measures and does what
 * it is told, four times a second.
 *
 * A stream always opens with a keyframe carrying SPS and PPS: [start] builds a fresh encoder and a fresh
 * [RtpOutput], which lets nothing through before a keyframe. [restartStream] asks the running encoder for one and
 * holds the output until it comes.
 *
 * A hot phone streams under a lower ceiling ([ThermalCeiling]): [thermalStatus] (PowerManager's) is read at
 * the start, which sets the ceiling of the stream, and at every poll; while the rate is above what the status allows,
 * the measurement windows count as short and the automatic rate comes down. Each change of status is logged.
 *
 * [link] is read at every poll for the 5 s line: the bike's speed and Wi-Fi.
 */
// The parameters are the fields of what it builds (wire format / the data it carries); one small function per case.
@Suppress("LongParameterList", "TooManyFunctions")
internal class VideoLoop(
    private val compositor: DashCompositor,
    private val dash: Dash,
    private val settings: Settings = Settings(),
    private val thermalStatus: () -> Int = { THERMAL_UNKNOWN },
    private val log: (String) -> Unit = { RideDiagnostics.log("stream", it) },
    private val link: () -> LinkSample? = { null },
    /** The navigation profiles' input: maneuver distance and route speed, read every poll (same pattern as [link]). */
    private val navSample: () -> StreamGovernor.NavSample? = { null },
    /** Whether Android Auto's screen in front is a light one (its app menu), read every poll. */
    private val lightScreen: () -> Boolean = { false },
    /** The stats text on the dash's video: told when the stream starts and stops, and its numbers each second. */
    private val stats: StatsFeed? = null,
) {
    /** What the loop needs of the dash session. */
    interface Dash {
        fun sendRtp(packet: ByteArray)

        /** A keyframe of [bytes] bytes went out in [packets] RTP packets (only for the dash session's record of what it sent last). */
        fun keyframeSent(bytes: Int, packets: Int) = Unit

        /** Whether the dash has confirmed decoding a frame of the current stream. */
        val firstFrameConfirmed: Boolean

        /** Frames the dash has acknowledged decoding, ever. */
        val decodedFrameCount: Long
    }

    /**
     * [startFps] is where the automatic rate starts (the rate remembered from before, once that is stored) and
     * [ceilingFps] how high it may climb. [clockSlowPercent] is the video clock knob. [bitrateBps], [bitrateMode] and
     * [keyframeSec] (0: automatic) are the rider's; their defaults here are what the stream had before.
     *
     * Optional recording: with [recordFrames] above zero and a [recordDir], the first [recordFrames] units of every
     * stream are also written to a file there ([H264Recorder]); with [recordRolling] the stream goes on in further parts, to its end.
     */
    data class Settings(
        val startFps: Int = DashConfig.AUTO_FPS_START,
        val ceilingFps: Int = DashConfig.AUTO_FPS_CEILING,
        val clockSlowPercent: Int = 0,
        val bitrateBps: Int = DashConfig.MAX_STREAM_BITRATE_BPS,
        val bitrateMode: BitrateMode? = null,
        val keyframeSec: Int = DashConfig.KEYFRAME_INTERVAL_AUTO,
        val windowMs: Long = StreamGovernor.WINDOW_MS,
        val recordFrames: Int = 0,
        val recordDir: File? = null,
        val recordRolling: Boolean = false,
        /** The pace on a light screen of Android Auto ([LightScreen]); 0: off. */
        val lightScreenFps: Int = 0,
        /** Experimental ([com.reau.app.settings.REauSettings.experimentRtpPacing]): the RTP packets leave spaced out ([RtpPacing]). Off, they leave as they are made. */
        val rtpPacing: Boolean = false,
        /** Experimental ([com.reau.app.settings.REauSettings.experimentAdaptiveBitrate]): the bitrate follows what the encoder really puts out ([BitrateAdapter]). */
        val adaptiveBitrate: Boolean = false,
        /** Experimental ([com.reau.app.settings.REauSettings.experimentEncoderWatch]): an encoder gone mute without failing is rebuilt ([EncoderMuteWatch]). */
        val encoderMuteWatch: Boolean = false,
        /** Experimental ([com.reau.app.settings.REauSettings.experimentColorMatrix]): the encoder is asked for BT.601, limited range, SDR ([com.reau.app.dash.video.ColorMatrix]). */
        val colorMatrix: Boolean = false,
    ) {
        /**
         * These settings with what the rider's own settings decide of the stream: the rate ceiling and clock, the bitrate (the experimental
         * adaptive one asks for its own target, [REauSettings.effectiveStreamBitrateBps]), its mode and keyframe interval, the light-screen pace and
         * the experimental switches. What a projection starts with and what a new stream takes up, the same.
         */
        fun withRider(rider: REauSettings): Settings = copy(
            ceilingFps = rider.fpsCeiling,
            clockSlowPercent = rider.clockSlowPercent,
            bitrateBps = rider.effectiveStreamBitrateBps,
            bitrateMode = rider.bitrateMode,
            keyframeSec = rider.keyframeSec,
            lightScreenFps = REauSettings.lightScreenFps(),
            rtpPacing = rider.experimentRtpPacing,
            adaptiveBitrate = rider.experimentAdaptiveBitrate,
            encoderMuteWatch = rider.experimentEncoderWatch,
            colorMatrix = rider.experimentColorMatrix,
        )
    }

    private var encoder: DashEncoder? = null
    private var output: RtpOutput? = null
    private var recorder: H264Recorder? = null
    private var governor: StreamGovernor? = null
    private var poller: ScheduledExecutorService? = null
    private var outputFailing = false
    private var failedPolls = 0
    private var lastThermal = 0
    private var lastWithheldAskMs: Long? = null

    // The experimental live adjustment of the bitrate; null (the default) leaves the bitrate to the governor alone.
    private var adapter: BitrateAdapter? = null
    private var pacing: RtpPacing? = null

    // The experimental watch for an encoder gone mute; null (the default): only an encoder that fails is rebuilt.
    private var muteWatch: EncoderMuteWatch? = null

    // Set by [start] for the encoder it builds, taken by [buildEncoder]: the rebuilt ones do not tell the ride log what the phone offers again.
    private var reportCodecsOfNextEncoder = false

    val isRunning: Boolean get() = synchronized(this) { governor != null }

    /** The running stream's output, for a test to hand it what an encoder would. */
    internal val outputForTest: RtpOutput? get() = synchronized(this) { output }

    /** Builds the encoder and starts streaming. False (nothing started) if the encoder could not be prepared. */
    @Synchronized
    fun start(): Boolean {
        if (governor != null) return true
        val status = thermalStatus()
        lastThermal = status
        val ceiling = ThermalCeiling.capFor(status, settings.ceilingFps)
        if (thermalCapped(ceiling, settings.ceilingFps)) logThermal(status)
        val plan = AutoFpsController.plan(settings.startFps, ceiling)
        val g = StreamGovernor(
            plan, settings.clockSlowPercent, settings.windowMs, settings.bitrateBps, settings.keyframeSec, lightScreenFps = settings.lightScreenFps,
        )
        val rec = recordInto(settings.recordDir, settings.recordFrames)?.let {
            H264Recorder(it, settings.recordFrames, H264Recorder.nameFor(System.currentTimeMillis()), settings.recordRolling, log)
        }
        val pacing = RtpPacing.whenOn(settings.rtpPacing)
        this.pacing = pacing
        val out = RtpOutput(
            tap = rec?.let { it::write },
            onKeyframe = dash::keyframeSent,
            pacing = pacing,
            sendRtp = { packet ->
                dash.sendRtp(packet)
                // Cheap when no touch is being followed (a flag): the line of a touch is written when its first packet goes out.
                InputLatency.packetSent({ g.fps }, { com.reau.app.aa.AaScreens.watch.screen.value.name })
            },
        )
        reportCodecsOfNextEncoder = true
        val fresh = buildEncoder(out, g.encoderFps, g.activeBitrateBps, g.encoderKeyframeSec) ?: return false
        out.switchTo(fresh)
        outputFailing = false
        failedPolls = 0
        lastWithheldAskMs = null
        output = out
        recorder = rec
        encoder = fresh
        governor = g
        adapter = BitrateAdapter.whenOn(settings.adaptiveBitrate)
        muteWatch = EncoderMuteWatch.whenOn(settings.encoderMuteWatch)
        log(
            "video loop start: ${g.fps} fps (ceiling ${plan.ceiling}), keyframe ${g.keyframeSec}s" +
                (if (settings.keyframeSec > 0) " fixed" else " auto") +
                " (encoder asked ${DashEncoder.iFrameIntervalText(g.encoderKeyframeSec, g.encoderFps)}, pace up to ${g.gopFps} fps)" +
                ", ${g.activeBitrateBps / 1000} kbps (${settings.bitrateBps / 1000} chosen) ${settings.bitrateMode ?: "default"}, " +
                "clock ${settings.clockSlowPercent}% slow" +
                (if (settings.rtpPacing) ", RTP packets spaced out (experiment)" else "") +
                (if (settings.adaptiveBitrate) ", bitrate follows the encoder's real output (experiment)" else "") +
                (if (settings.encoderMuteWatch) ", a mute encoder is rebuilt (experiment)" else ""),
        )
        stats?.panelStarted()
        stats?.stream(settings.bitrateMode, g.keyframeSec, settings.keyframeSec > 0)
        apply(g.start(SystemClock.elapsedRealtime()))
        com.reau.app.data.ProjectionSummary.current.streaming(on = true, SystemClock.elapsedRealtime())
        compositor.setOutput(checkNotNull(fresh.inputSurface), onFrameSubmitted = fresh::frameSubmitted)
        poller = Executors.newSingleThreadScheduledExecutor { Thread(it, "video-loop") }.also {
            it.scheduleWithFixedDelay(::poll, POLL_MS, POLL_MS, TimeUnit.MILLISECONDS)
        }
        return true
    }

    /**
     * The dash is (again) at the start of a stream on the running encoder: nothing more goes out until a keyframe,
     * and one is asked for now. [reason] goes to the log.
     */
    @Synchronized
    fun restartStream(reason: String) {
        val g = governor ?: return
        output?.holdUntilKeyframe()
        encoder?.requestSyncFrame()
        g.syncRequested(reason, SystemClock.elapsedRealtime())
        log("stream restart ($reason): keyframe requested, output held until it comes")
    }

    @Synchronized
    fun stop() {
        if (governor == null) return
        governor = null
        com.reau.app.data.ProjectionSummary.current.streaming(on = false, SystemClock.elapsedRealtime())
        poller?.shutdownNow()
        poller = null
        stats?.panelStopped()
        // The encoder is released on the GL thread, after the output is let go: never while a frame can still be drawn on its surface.
        val stopping = encoder
        encoder = null
        compositor.clearOutput { stopping?.release() }
        recorder?.close()
        recorder = null
        pacing?.let { log("RTP pacing (experiment): ${it.waitedNs / NS_PER_MS} ms waited in all, the packet cap reached ${it.capHits} times") }
        pacing = null
        adapter = null
        muteWatch = null
        log("video loop stop (${output?.heldBack ?: 0} frames held back before a keyframe, ${output?.idrWithheld ?: 0} keyframes withheld for lack of SPS/PPS)")
        output = null
    }

    private fun poll() = synchronized(this) {
        val g = governor ?: return@synchronized
        val out = output ?: return@synchronized
        runCatching {
            val encoded = runCatching { encoder?.collectOutput() }
            encoded.exceptionOrNull()?.let { if (!outputFailing) log("${RideDiagnostics.WARN}encoder output failing: ${CodecErrors.describe(it)}") }
            outputFailing = encoded.isFailure
            // A codec that failed stays failed (the output thread is gone), and the poll only logged it once: the dash
            // kept the last picture and the app said "streaming". A few seconds of it, and a new encoder takes over.
            failedPolls = failedPollsAfter(failedPolls, encoded.isFailure)
            if (rebuildDue(failedPolls)) {
                failedPolls = 0
                log("${RideDiagnostics.WARN}encoder failed for ${REBUILD_AFTER_FAILED_POLLS * POLL_MS} ms: rebuilding it")
                rebuild(g.rebuildAction())
            }
            val timed = encoded.getOrNull()
            if (timed != null) watchForAMuteEncoder(g, timed, SystemClock.elapsedRealtime())
            val counts = out.takeCounts()
            adaptBitrate(counts.bytes)
            askAgainForWithheldKeyframe(out, g, SystemClock.elapsedRealtime())
            com.reau.app.data.ProjectionSummary.current.frames(counts.frames, SystemClock.elapsedRealtime())
            val status = thermalStatus()
            if (status != lastThermal) {
                lastThermal = status
                logThermal(status)
            }
            compositor.burstGapMs = burstGap(status)
            apply(
                g.onPoll(
                    StreamGovernor.Poll(
                        nowMs = SystemClock.elapsedRealtime(),
                        compositor = compositor.takeStats(),
                        framesOut = counts.frames,
                        bytesOut = counts.bytes,
                        keyframes = counts.keyframes,
                        keyBytesMax = counts.keyBytesMax,
                        keyPacketsMax = counts.keyPacketsMax,
                        lateFrames = timed?.late ?: 0,
                        encodeNs = timed?.latencySumNs ?: 0L,
                        dashAcks = dash.decodedFrameCount,
                        firstFrameConfirmed = dash.firstFrameConfirmed,
                        // Over the hot ceiling the windows count as short, and the automatic rate comes down to it.
                        thermalOk = ThermalCeiling.allows(status, g.profileFps, settings.ceilingFps),
                        link = runCatching { link() }.getOrNull(),
                        navSample = runCatching { navSample() }.getOrNull(),
                        // The heat stops the faster pace from the first sign of it ('moderate'): the ceiling of a stream fixed at 4 fps has no step under it.
                        lightScreen = lightScreenOn(settings.lightScreenFps, status, lightScreen),
                        encode = timed?.latency ?: StageStats.EMPTY,
                    ),
                ),
            )
        }.onFailure { log("video loop poll failed: $it") }
    }

    /**
     * The experiment ([EncoderMuteWatch]): the encoder took [timed]'s frames in and gave its frames out since the last poll. Frames in and none
     * out for long enough, with no error, and the encoder is rebuilt as a failing one is. Nothing happens with the switch off.
     */
    private fun watchForAMuteEncoder(g: StreamGovernor, timed: PendingFrames.Stats, nowMs: Long) {
        val watch = muteWatch ?: return
        // Debug builds only can hide the frames out (FaultInjection.ENCODER_MUTE): the watch then sees an encoder that gives nothing out.
        val verdict = watch.onPoll(nowMs, timed.submitted, if (com.reau.app.debug.FaultInjection.muteEncoderOutput()) 0 else timed.frames)
        if (verdict == EncoderMuteWatch.Verdict.FINE) return
        com.reau.app.debug.FaultInjection.encoderVerdictGiven()
        log("${RideDiagnostics.WARN}${EncoderMuteWatch.line(verdict, watch.lastWaitFrames, watch.rebuilds)}")
        if (verdict == EncoderMuteWatch.Verdict.REBUILD) rebuild(g.rebuildAction())
    }

    /**
     * The experiment: [bytes] came out since the last poll. When the window of [BitrateAdapter] says the encoder put out too much or too little
     * for the target, the new bitrate is asked of the running encoder (a live parameter, never a rebuild) and the line says what was measured.
     */
    private fun adaptBitrate(bytes: Long) {
        val adjust = adapter ?: return
        val now = SystemClock.elapsedRealtime()
        val wanted = adjust.onOutput(now, bytes) ?: return
        encoder?.requestBitrate(wanted)
        log("bitrate adjusted to $wanted (the encoder put out ${adjust.lastRealBps}, the target is ${adjust.target})")
    }

    /**
     * A keyframe the output withheld (no SPS/PPS before it) leaves the dash without a picture until the encoder's next one: ask for it now,
     * at most once per [DashLogic.WITHHELD_ASK_EVERY_MS]. The flag is taken at every poll, asked for or not, so one withheld keyframe never
     * waits in it for a later poll to pass as a new one.
     */
    private fun askAgainForWithheldKeyframe(out: RtpOutput, g: StreamGovernor, nowMs: Long) {
        if (!out.takeKeyframeWanted() || !DashLogic.withheldAskDue(nowMs, lastWithheldAskMs)) return
        lastWithheldAskMs = nowMs
        encoder?.requestSyncFrame()
        g.syncRequested("withheld", nowMs)
        log("keyframe withheld for lack of SPS/PPS: a new one asked for")
    }

    /**
     * The compositor draws a new picture at once while the rider's input is fresh ([InputBurst]) ([REauSettings.lightScreenFps]), a phone cooler
     * than 'moderate' and the dash showing pictures; 0: off.
     */
    private fun burstGap(thermalStatus: Int): Long = burstGapFor(settings.lightScreenFps, thermalStatus, dash.firstFrameConfirmed)

    private fun apply(actions: List<StreamGovernor.Action>) {
        for (action in actions) {
            when (action) {
                is StreamGovernor.Action.SetPace -> {
                    compositor.setPace(action.pace)
                    log("pace: a tick every ${action.pace.activeMs} ms, a repeat every ${action.pace.idleMs} ms")
                }
                is StreamGovernor.Action.SetBitrate -> {
                    adapter?.setTarget(action.bps)
                    encoder?.requestBitrate(action.bps)
                    log("bitrate=${action.bps}")
                }
                is StreamGovernor.Action.RequestSync -> encoder?.requestSyncFrame()
                is StreamGovernor.Action.Rebuild -> rebuild(action)
                is StreamGovernor.Action.Log -> log(action.line)
                is StreamGovernor.Action.Stats -> stats?.panel(Reading(action.sentFps, action.kbps))
            }
        }
    }

    /** The new encoder is prepared while the old one runs, so a failed prepare costs nothing but the change. */
    private fun rebuild(wanted: StreamGovernor.Action.Rebuild) {
        val out = output ?: return
        val fresh = buildEncoder(out, wanted.fps, wanted.bitrateBps, wanted.encoderKeyframeSec)
        if (fresh == null) {
            log("${RideDiagnostics.WARN}encoder rebuild failed, keeping the running one")
            return
        }
        val old = encoder
        out.switchTo(fresh)
        encoder = fresh
        adapter?.setTarget(wanted.bitrateBps)
        // The old encoder goes on the GL thread, after the new surface is attached and the old one let go (see clearOutput).
        compositor.swapOutputThen(checkNotNull(fresh.inputSurface), onFrameSubmitted = fresh::frameSubmitted, after = { old?.release() })
        stats?.stream(settings.bitrateMode, wanted.keyframeSec, settings.keyframeSec > 0)
        log(
            "encoder rebuilt: ${wanted.fps} fps, keyframe ${wanted.keyframeSec}s (encoder asked " +
                "${DashEncoder.iFrameIntervalText(wanted.encoderKeyframeSec, wanted.fps)}), ${wanted.bitrateBps / 1000} kbps",
        )
    }

    private fun logThermal(status: Int) =
        log("thermal ${ThermalCeiling.nameOf(status)}: ceiling ${ThermalCeiling.capFor(status, settings.ceilingFps)} of ${settings.ceilingFps}")

    /** An encoder whose output thread hands every buffer to [out]; [encoderKeyframeSec] is its own keyframe interval (the rider's, or longer: [DashLogic.encoderKeyframeSec]). */
    private fun buildEncoder(out: RtpOutput, fps: Int, bitrateBps: Int, encoderKeyframeSec: Int): DashEncoder? {
        lateinit var created: DashEncoder
        created = DashEncoder { data, isKey, ptsMs -> out.onEncoded(created, data, isKey, ptsMs) }
        // What the phone offers and what the codec accepted go to the ride log once for a stream: the first encoder tells it, a rebuilt one does not.
        created.reportCodecs = reportCodecsOfNextEncoder
        reportCodecsOfNextEncoder = false
        if (created.prepare(fps, bitrateBps, encoderKeyframeSec, settings.bitrateMode, colorMatrix = settings.colorMatrix)) return created
        log("${RideDiagnostics.ERROR}encoder prepare failed: ${created.lastPrepareError}")
        return null
    }

    companion object {
        /** The stream's rate is held under the ceiling the phone's heat allows: [ceiling] is under the rate the rider [wanted]. */
        internal fun thermalCapped(ceiling: Int, wanted: Int): Boolean = ceiling < DashConfig.snapStreamFps(wanted)

        /** The folder the first frames of the stream are recorded into: only when a folder is given and some frames are asked for. */
        internal fun recordInto(dir: File?, frames: Int): File? = dir?.takeIf { frames > 0 }

        /** The polls in a row with the encoder's output failing, after this poll: a poll that went well starts the count over. */
        internal fun failedPollsAfter(failedBefore: Int, failedNow: Boolean): Int = if (failedNow) failedBefore + 1 else 0

        /** Whether the encoder has failed for long enough to be rebuilt. */
        internal fun rebuildDue(failedPolls: Int): Boolean = failedPolls >= REBUILD_AFTER_FAILED_POLLS

        /**
         * The gap the compositor draws a new picture at once with while the rider's input is fresh ([InputBurst]): only with the faster
         * pace set, a phone cooler than 'moderate' and the dash showing pictures; 0: off.
         */
        internal fun burstGapFor(lightScreenFps: Int, thermalStatus: Int, firstFrameConfirmed: Boolean): Long =
            if (lightScreenFps > 0 && thermalStatus < THERMAL_MODERATE && firstFrameConfirmed) InputBurst.GAP_MS else 0L

        /** Whether the faster pace for a light screen applies now: it is set, the phone is cooler than 'moderate', and the screen says light. */
        internal fun lightScreenOn(lightScreenFps: Int, thermalStatus: Int, lightScreenNow: () -> Boolean): Boolean =
            lightScreenFps > 0 && thermalStatus < THERMAL_MODERATE && runCatching { lightScreenNow() }.getOrDefault(false)

        private const val POLL_MS = 250L
        private const val NS_PER_MS = 1_000_000L

        /** Polls in a row with the encoder's output failing before it is rebuilt (8 × 250 ms = 2 s). */
        private const val REBUILD_AFTER_FAILED_POLLS = 8

        /** No thermal status known: counts as cool. */
        const val THERMAL_UNKNOWN = -1
        private const val THERMAL_MODERATE = 2
    }
}

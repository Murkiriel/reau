package com.reau.app.stream

import android.os.SystemClock
import com.reau.app.data.RideDiagnostics
import com.reau.app.dash.BitrateMode
import com.reau.app.dash.DashConfig
import com.reau.app.dash.video.DashEncoder
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
@Suppress("LongParameterList") // the parameters are the fields of what it builds (wire format / the data it carries)
internal class VideoLoop(
    private val compositor: DashCompositor,
    private val dash: Dash,
    private val settings: Settings = Settings(),
    private val thermalStatus: () -> Int = { THERMAL_UNKNOWN },
    private val log: (String) -> Unit = { RideDiagnostics.log("stream", it) },
    private val link: () -> LinkSample? = { null },
    /** The navigation profiles' input: maneuver distance and route speed, read every poll (same pattern as [link]). */
    private val navSample: () -> StreamGovernor.NavSample? = { null },
    /** The stats text on the dash's video: told when the stream starts and stops, and its numbers each second. */
    private val stats: StatsFeed? = null,
) {
    /** What the loop needs of the dash session. */
    interface Dash {
        fun sendRtp(packet: ByteArray)

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
     * Debug option: with [recordFrames] above zero and a [recordDir], the first [recordFrames] units of every
     * stream are also written to a file there ([H264Recorder]).
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
    )

    private var encoder: DashEncoder? = null
    private var output: RtpOutput? = null
    private var recorder: H264Recorder? = null
    private var governor: StreamGovernor? = null
    private var poller: ScheduledExecutorService? = null
    private var outputFailing = false
    private var failedPolls = 0
    private var lastThermal = 0

    val isRunning: Boolean get() = synchronized(this) { governor != null }

    /** Builds the encoder and starts streaming. False (nothing started) if the encoder could not be prepared. */
    @Synchronized
    fun start(): Boolean {
        if (governor != null) return true
        val status = thermalStatus()
        lastThermal = status
        val ceiling = ThermalCeiling.capFor(status, settings.ceilingFps)
        if (ceiling < DashConfig.snapStreamFps(settings.ceilingFps)) logThermal(status)
        val plan = AutoFpsController.plan(settings.startFps, null, null, ceiling)
        val g = StreamGovernor(plan, settings.clockSlowPercent, settings.windowMs, settings.bitrateBps, settings.keyframeSec)
        val rec = settings.recordDir?.takeIf { settings.recordFrames > 0 }?.let {
            H264Recorder(it, settings.recordFrames, H264Recorder.nameFor(System.currentTimeMillis()), log)
        }
        val out = RtpOutput(tap = rec?.let { it::write }, sendRtp = dash::sendRtp)
        val fresh = buildEncoder(out, g.encoderFps, g.keyframeSec, g.activeBitrateBps) ?: return false
        out.switchTo(fresh)
        outputFailing = false
        failedPolls = 0
        output = out
        recorder = rec
        encoder = fresh
        governor = g
        log(
            "video loop start: ${g.fps} fps (ceiling ${plan.ceiling}), keyframe ${g.keyframeSec}s" +
                (if (settings.keyframeSec > 0) " fixed" else " auto") +
                ", ${g.activeBitrateBps / 1000} kbps (${settings.bitrateBps / 1000} chosen) ${settings.bitrateMode ?: "default"}, " +
                "clock ${settings.clockSlowPercent}% slow",
        )
        stats?.panelStarted()
        stats?.stream(settings.bitrateMode, g.keyframeSec, settings.keyframeSec > 0)
        apply(g.start(SystemClock.elapsedRealtime()))
        com.reau.app.data.ProjectionSummary.current.streaming(on = true, SystemClock.elapsedRealtime())
        compositor.setOutput(checkNotNull(fresh.inputSurface), fresh::frameSubmitted)
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
        compositor.clearOutput()
        encoder?.release()
        encoder = null
        recorder?.close()
        recorder = null
        log("video loop stop (${output?.heldBack ?: 0} frames held back before a keyframe, ${output?.idrWithheld ?: 0} keyframes withheld for lack of SPS/PPS)")
        output = null
    }

    private fun poll() = synchronized(this) {
        val g = governor ?: return@synchronized
        val out = output ?: return@synchronized
        runCatching {
            val encoded = runCatching { encoder?.collectOutput() }
            encoded.exceptionOrNull()?.let { if (!outputFailing) log("${RideDiagnostics.WARN}encoder output failing: ${it.message}") }
            outputFailing = encoded.isFailure
            // A codec that failed stays failed (the output thread is gone), and the poll only logged it once: the dash
            // kept the last picture and the app said "streaming". A few seconds of it, and a new encoder takes over.
            failedPolls = if (encoded.isFailure) failedPolls + 1 else 0
            if (failedPolls >= REBUILD_AFTER_FAILED_POLLS) {
                failedPolls = 0
                log("${RideDiagnostics.WARN}encoder failed for ${REBUILD_AFTER_FAILED_POLLS * POLL_MS} ms: rebuilding it")
                rebuild(StreamGovernor.Action.Rebuild(g.fps, g.keyframeSec, g.activeBitrateBps))
            }
            val timed = encoded.getOrNull()
            val counts = out.takeCounts()
            com.reau.app.data.ProjectionSummary.current.frames(counts.frames, SystemClock.elapsedRealtime())
            val status = thermalStatus()
            if (status != lastThermal) {
                lastThermal = status
                logThermal(status)
            }
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
                        thermalOk = ThermalCeiling.allows(status, g.fps, settings.ceilingFps),
                        link = runCatching { link() }.getOrNull(),
                        navSample = runCatching { navSample() }.getOrNull(),
                    ),
                ),
            )
        }.onFailure { log("video loop poll failed: $it") }
    }

    private fun apply(actions: List<StreamGovernor.Action>) {
        for (action in actions) {
            when (action) {
                is StreamGovernor.Action.SetPace -> {
                    compositor.setPace(action.pace)
                    log("pace: a tick every ${action.pace.activeMs} ms, a repeat every ${action.pace.idleMs} ms")
                }
                is StreamGovernor.Action.SetBitrate -> {
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
        val fresh = buildEncoder(out, wanted.fps, wanted.keyframeSec, wanted.bitrateBps)
        if (fresh == null) {
            log("${RideDiagnostics.WARN}encoder rebuild failed, keeping the running one")
            return
        }
        val old = encoder
        out.switchTo(fresh)
        encoder = fresh
        compositor.setOutput(checkNotNull(fresh.inputSurface), fresh::frameSubmitted)
        old?.release()
        stats?.stream(settings.bitrateMode, wanted.keyframeSec, settings.keyframeSec > 0)
        log("encoder rebuilt: ${wanted.fps} fps, keyframe ${wanted.keyframeSec}s, ${wanted.bitrateBps / 1000} kbps")
    }

    private fun logThermal(status: Int) =
        log("thermal ${ThermalCeiling.nameOf(status)}: ceiling ${ThermalCeiling.capFor(status, settings.ceilingFps)} of ${settings.ceilingFps}")

    /** An encoder whose output thread hands every buffer to [out]. */
    private fun buildEncoder(out: RtpOutput, fps: Int, keyframeSec: Int, bitrateBps: Int): DashEncoder? {
        lateinit var created: DashEncoder
        created = DashEncoder { data, isKey, ptsMs -> out.onEncoded(created, data, isKey, ptsMs) }
        if (created.prepare(fps, bitrateBps, keyframeSec, settings.bitrateMode)) return created
        log("${RideDiagnostics.ERROR}encoder prepare failed: ${created.lastPrepareError}")
        return null
    }

    companion object {
        private const val POLL_MS = 250L

        /** Polls in a row with the encoder's output failing before it is rebuilt (8 × 250 ms = 2 s). */
        private const val REBUILD_AFTER_FAILED_POLLS = 8

        /** No thermal status known: counts as cool. */
        const val THERMAL_UNKNOWN = -1
    }
}

package com.reau.app.stream

import com.reau.app.dash.DashConfig

/**
 * Pure decision helpers of the video stream to the dash: pace, bitrate, keyframes, the video clock and what a
 * dash failure says about the rate. None touch instance state, so they run in a plain JVM unit test
 * ([DashLogicTest]). Their values were found on a real dash.
 */
@Suppress("TooManyFunctions") // flat set of pure helpers, each tested alone
internal object DashLogic {

    /**
     * Whether the stream that ended at [watchdogFailMs] (a watchdog found the dash silent, or never decoding; 0 =
     * none did) says something about the RATE: only if a new stream is starting within [DASH_ALIVE_WINDOW_MS] of
     * it. Ignition off looks the same to the watchdog, but then the dash's Wi-Fi is gone and nothing reconnects;
     * a dash that choked authenticates again in a couple of seconds (about 3 s, measured on a real dash).
     * [countedFailMs] is the failure already acted on: one drop lowers the rate once, however many streams start
     * inside that window.
     */
    internal fun dashFailedWhileReachable(watchdogFailMs: Long, streamStartMs: Long, countedFailMs: Long = 0L): Boolean =
        watchdogFailMs > 0L && watchdogFailMs != countedFailMs && streamStartMs - watchdogFailMs in 0..DASH_ALIVE_WINDOW_MS

    private const val DASH_ALIVE_WINDOW_MS = 30_000L

    /** The limit learned from a dash failure, while it is still in force: it is forgotten after
     *  [DASH_LIMIT_LIFE_MS], so one bad evening (or a false alarm) does not hold the rate down for good. */
    internal fun liveDashLimit(limit: Int, learnedAtMs: Long, nowMs: Long): Int? =
        limit.takeIf { it > 0 && nowMs - learnedAtMs < DASH_LIMIT_LIFE_MS }

    private const val DASH_LIMIT_LIFE_MS = 24 * 60 * 60 * 1000L

    /**
     * Whether the stream runs at its full rate: only with the map changing AND the dash having confirmed the
     * first frame of this stream. Until then it keeps the still-map pace (4 fps, a keyframe every 2 s): on a real
     * dash, every stream that got its first frame confirmed had started at that pace; of the 36 that began at
     * 30 fps in motion, none was.
     */
    internal fun fullRate(mapChangedRecently: Boolean, firstFrameConfirmed: Boolean): Boolean = mapChangedRecently && firstFrameConfirmed

    /**
     * Real fps achieved over a window, from a frame count and the elapsed wall-clock time —
     * kept apart from the encoder's output callback so the arithmetic itself is
     * unit-testable without a real `MediaCodec` pipeline. [elapsedMs] `<= 0` returns `0.0` rather
     * than dividing by zero or a negative window (shouldn't happen — the caller only calls this
     * once [elapsedMs] has crossed its measurement window — but a diagnostic line must
     * never crash the stream over a clock edge case).
     */
    internal fun computeActualFps(frames: Int, elapsedMs: Long): Double =
        if (elapsedMs <= 0) 0.0 else frames * 1000.0 / elapsedMs

    /**
     * The active/idle frame-interval pair for a chosen `DashConfig.streamFps` (rates other than the
     * proven one accept the risk of the picture freezing at extreme values), replacing the fixed
     * `FRAME_MS_ACTIVE`/`FRAME_MS_IDLE` pair the frame loop used to hard-code. Idle is always 2×
     * active — the same ratio the fixed 4fps-active/2fps-idle pair already had (see those
     * constants' own doc for why the dash's decoder never overran at that envelope) — so any
     * chosen base rate keeps the existing motion-adaptive shape (drop to half once the map's been
     * still for `MOTION_HOLD_MS`), just scaled. fps=4 must reproduce the original exact (250L, 500L)
     * pair byte for byte — `DashLogicTest`'s own golden test locks this in; every other fps is
     * genuinely new, untested-on-real-hardware territory outside the proven-stable 4fps envelope
     * the surrounding code's own comments describe at length.
     */
    /**
     * Whether the running encoder has to be replaced: the rate and the keyframe interval are baked in at prepare(),
     * and so is the way its output is read — by its own thread, or by the frame loop waiting for each frame
     * ([com.reau.app.dash.DashConfig.streamWaitEncoder], the older loop kept as a test
     * switch to compare the two on a real dash at the same rate).
     */
    @Suppress("LongParameterList") // three settings, each next to what the encoder was built with
    internal fun encoderRebuildNeeded(fps: Int, encoderFps: Int, keyframeSec: Int, encoderKeyframeSec: Int, waits: Boolean, encoderWaits: Boolean): Boolean =
        fps != encoderFps || keyframeSec != encoderKeyframeSec || waits != encoderWaits

    /**
     * The video's own clock after one more frame: [ptsMs] plus the frame interval, shortened by [slowPercent]
     * (0..[MAX_CLOCK_SLOW_PERCENT]; a test knob, [com.reau.app.dash.DashConfig.streamClockSlowPercent]).
     * At 0 it is the frame interval exactly, as always. Above it the stamps fall behind real time little by little,
     * so every frame reaches the dash later than its own stamp says — which is what the loop that waited for the
     * encoder did by accident (15.4 fps delivered for 16 asked: about 5% slow), when the dash showed
     * no lag. The fraction of a millisecond is kept in the Double; the caller truncates only what it hands the encoder.
     */
    internal fun nextVideoPts(ptsMs: Double, frameMs: Long, slowPercent: Int): Double =
        ptsMs + frameMs * (100 - slowPercent.coerceIn(0, MAX_CLOCK_SLOW_PERCENT)) / 100.0

    internal const val MAX_CLOCK_SLOW_PERCENT = 10

    /** How long `DashEncoder.drain` waits for the frame just submitted: half a frame interval,
     *  never above 40 ms nor below the 10 ms it always waited. Measured on a real dash: with a fixed
     *  10 ms, ~25% of frames at 12 fps came out a loop late, sharing the next frame's RTP time.
     *  The frame loop doesn't call drain() (the encoder's output thread sends each
     *  frame with its own time); this is for the pull mode, which the encoder tests still use. */
    internal fun drainWaitUs(frameMs: Long): Long = (frameMs * 1000 / 2).coerceIn(10_000L, 40_000L)

    internal fun frameIntervalsMsForFps(fps: Int): Pair<Long, Long> {
        val active = kotlin.math.round(1000.0 / fps.coerceAtLeast(1)).toLong().coerceAtLeast(1L)
        // A still map sends identical frames: never more than 4 of them a second, whatever the
        // moving rate (automatic fps can settle at 20+, where "half" would still be 10+).
        return active to maxOf(active * 2, IDLE_MIN_FRAME_MS)
    }

    internal const val IDLE_MIN_FRAME_MS = 250L
    internal const val IDLE_SYNC_EVERY_MS = 2_000L

    /** Whether the loop must ask for keyframes itself while the map is still: the encoder
     *  places them every `hintFps × keyframeIntervalSec` FRAMES, so at the capped idle rate a high
     *  fps hint would space them further than [IDLE_SYNC_EVERY_MS] apart. 10% slack so interval
     *  rounding (6 fps idles at 334 ms → 2004 ms) doesn't trigger duplicate keyframes. */
    internal fun idleSyncNeeded(hintFps: Int, keyframeIntervalSec: Int, idleFrameMs: Long): Boolean =
        hintFps.toLong() * keyframeIntervalSec * idleFrameMs > IDLE_SYNC_EVERY_MS * 11 / 10

    /**
     * No frame gets less than this many bits — the share 1024 kbps gives
     * each frame at 30 fps, the top of the range. Real-hardware
     * testing showed a fixed small budget spread over many frames blurs the map.
     */
    internal const val MIN_BITS_PER_FRAME = DashConfig.MAX_STREAM_BITRATE_BPS / DashConfig.MAX_STREAM_FPS

    /** The ceiling the encoder works under: the Settings slider for a fixed fps, always
     *  [DashConfig.MAX_STREAM_BITRATE_BPS] under automatic fps (the
     *  slider is disabled there, and its stored value comes back when automatic is turned off). */
    internal fun bitrateCeiling(sliderBps: Int, autoFps: Boolean): Int =
        if (autoFps) DashConfig.MAX_STREAM_BITRATE_BPS else sliderBps

    /** Highest even fps a bitrate ceiling feeds at [MIN_BITS_PER_FRAME] — where a fixed fps is
     *  pulled down to when the rider lowers the bitrate slider. */
    internal fun maxAutoFpsForBitrate(bps: Int): Int = DashConfig.snapStreamFps(bps / MIN_BITS_PER_FRAME)

    /** Smallest bitrate slider stop that still gives [fps] its [MIN_BITS_PER_FRAME]. */
    internal fun minBitrateForFps(fps: Int): Int {
        val needed = fps.toLong() * MIN_BITS_PER_FRAME
        return DashConfig.STREAM_BITRATE_STEPS.firstOrNull { it >= needed } ?: DashConfig.MAX_STREAM_BITRATE_BPS
    }

    /** Bitrate actually fed to the encoder while moving: the rider's ceiling, raised if a fixed [fps]
     *  would otherwise starve its frames, never above [DashConfig.MAX_STREAM_BITRATE_BPS]. */
    internal fun effectiveActiveBitrate(ceilingBps: Int, fps: Int): Int =
        maxOf(ceilingBps, minBitrateForFps(fps)).coerceAtMost(DashConfig.MAX_STREAM_BITRATE_BPS)

    /** Idle (still map) bitrate: half the active one. */
    internal fun effectiveIdleBitrate(ceilingBps: Int, fps: Int): Int = effectiveActiveBitrate(ceilingBps, fps) / 2

    /** Bits each frame gets, in kbit, for the Settings hint. */
    internal fun kbitPerFrame(bps: Int, fps: Int): Int = if (fps <= 0) 0 else bps / fps / 1000
}

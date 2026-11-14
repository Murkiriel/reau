package com.reau.app.dash

/**
 * The limits of the video stream to the dash, as found on a real dash.
 * The rules that use them are in `com.reau.app.stream` (DashLogic, StreamGovernor, AutoKeyframeController).
 */
object DashConfig {
    /** Range of the stream rate. At 30 the dash went silent; 4 is the conservative rate it shows well. */
    const val MIN_STREAM_FPS = 4
    const val MAX_STREAM_FPS = 30
    const val STREAM_FPS_STEP = 2
    const val DEFAULT_STREAM_FPS = 4

    /**
     * Where the automatic rate started with no history: 8 fps ran cleanly on a real dash. Nothing in the app reads it since the
     * automatic rate was cut (14/11/2026); it goes with the last clean-up of that cut.
     */
    const val AUTO_FPS_START = 8

    /**
     * The highest rate the dash has been SEEN to hold for minutes on a real bike, with no drop: above it the stretches
     * were much shorter (about a minute at 18 fps), and 30 fps killed the stream. It was the ceiling the automatic rate
     * climbed to (the dash does not acknowledge frames one by one, so nothing could find its limit by itself). The
     * automatic rate was cut on 14/11/2026 and the app's own ceiling is fixed at [PROFILE_FULL_FPS]; this is left as the
     * default of a stream made without the rider's settings (the tests), until the last clean-up of that cut.
     */
    const val AUTO_FPS_CEILING = 16

    /** Hard ceiling for every bitrate path: the value validated on this panel; 1.2 Mbps made the dash hitch. */
    const val MAX_STREAM_BITRATE_BPS = 1_024_000
    const val DEFAULT_STREAM_BITRATE_BPS = MAX_STREAM_BITRATE_BPS

    /** The bitrate stops: 200..900 kbps by 100, then the ceiling. */
    val STREAM_BITRATE_STEPS = (200_000..900_000 step 100_000).toList() + MAX_STREAM_BITRATE_BPS

    /** Nearest stop; zero or negative reads as the ceiling. */
    fun snapStreamBitrate(bps: Int): Int =
        if (bps <= 0) MAX_STREAM_BITRATE_BPS else STREAM_BITRATE_STEPS.minBy { kotlin.math.abs(it - bps) }

    /** Clamps into [MIN_STREAM_FPS]..[MAX_STREAM_FPS] and rounds to an even value; an odd value rounds DOWN
     *  (7 → 6), the safer side. */
    fun snapStreamFps(fps: Int): Int {
        val clamped = fps.coerceIn(MIN_STREAM_FPS, MAX_STREAM_FPS)
        return clamped - (clamped - MIN_STREAM_FPS) % STREAM_FPS_STEP
    }

    /**
     * The two stream profiles, the only ones: profile 1 is 4 fps at 400 kbps, constant, a keyframe every
     * second; profile 2 is half of it, 2 fps at 200 kbps. The encoder is built once for profile 1; profile 2 only feeds it
     * fewer frames, so a change of profile never replaces the encoder.
     */
    const val PROFILE_FULL_FPS = 4
    const val PROFILE_FULL_BPS = 400_000
    const val PROFILE_REDUCED_FPS = 2

    /** The pace on the screens of Android Auto that are not the navigation's (its app menu and the other apps) in every build ([com.reau.app.settings.REauSettings.lightScreenFps]); the encoder stays as built. */
    const val LIGHT_SCREEN_FPS = 6
    const val PROFILE_REDUCED_BPS = 200_000
    const val PROFILE_KEYFRAME_SEC = 1

    /**
     * The experimental lower bitrate ([com.reau.app.settings.REauSettings.experimentAdaptiveBitrate]): the target of the normal stream, 200 kbps instead of the
     * profile's 400, and the least the live adjustment ([com.reau.app.stream.BitrateAdapter]) goes down to.
     */
    const val EXPERIMENT_TARGET_BPS = 200_000
    const val EXPERIMENT_FLOOR_BPS = 64_000

    /** "Automatic": the streaming loop picks 1 or 2 s itself. */
    const val KEYFRAME_INTERVAL_AUTO = 0
    val KEYFRAME_INTERVAL_OPTIONS = listOf(KEYFRAME_INTERVAL_AUTO, 1, 2)

    /** Nearest offered value. */
    fun snapKeyframeIntervalSec(sec: Int): Int = KEYFRAME_INTERVAL_OPTIONS.minBy { kotlin.math.abs(it - sec) }
    const val DEFAULT_KEYFRAME_INTERVAL_SEC = KEYFRAME_INTERVAL_AUTO

    /** How slow the video's clock may be set to run, in percent (0 = on time). 5 is what an earlier frame loop did
     *  by accident (62 ms stamped, 65 ms real), when the dash showed no lag. */
    val STREAM_CLOCK_SLOW_OPTIONS = listOf(0, 3, 5, 8)
}

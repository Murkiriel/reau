package com.reau.app.aa

/**
 * Whether Android Auto, its session open, sends no picture at all. A ride: after its head unit server was restarted,
 * Android Auto's own encoder stuck ("Frame stuck or lost in encoder!" in its log), every session opened with no video
 * unit, and the dash showed black with nothing on the phone saying why; force-stopping Android Auto brought it back.
 * [check] runs about once a second with the session's video units so far: the session is silent from
 * [silentAfterMs] with none, and stops being so at the first unit.
 */
class VideoSilence(private val silentAfterMs: Long = SILENT_AFTER_MS) {
    private var startMs = -1L

    var isSilent = false
        private set

    /** The change to tell at [nowMs] with [units] video units so far (true: silent now, false: the picture came), or null. */
    fun check(nowMs: Long, units: Long): Boolean? {
        if (startMs < 0) startMs = nowMs
        val silent = units == 0L && nowMs - startMs >= silentAfterMs
        if (silent == isSilent) return null
        isSilent = silent
        return silent
    }

    companion object {
        /** A healthy session's first unit comes within a second of the video focus. */
        const val SILENT_AFTER_MS = 10_000L
    }
}

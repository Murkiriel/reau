package com.reau.app.devtools

import com.reau.app.data.RideDiagnostics

/**
 * Debug builds only: faults the rider cannot make happen on purpose, armed from Ajustes → Depuração ([DebugTriggers]) to see how the app
 * recovers and what the ride log says. The release build has a [FaultInjection] of the same shape that never arms and answers no to every
 * question (`src/release/java/com/reau/app/devtools/FaultInjection.kt`), so the three places that ask are plain reads in both builds and
 * nothing in a release can change them. FaultInjectionAbsenceInReleaseTest holds that.
 *
 * - [Fault.DECODER_STALL]: the video decoder's pictures stop counting as output for the stall watch, so it sees a decoder that takes pictures in
 *   and gives none out (3 s later: the restart and the `decoder` watchdog of the summary). One restart, then it is over: the decoder's pictures
 *   count again. Two presses in a row make the stall twice, which the start guard answers with the software decoder, as a real one would.
 * - [Fault.ENCODER_MUTE]: the mute watch hears no frame out of the encoder, so it sees one that takes frames in and gives none out (3 s later: the
 *   rebuild). It is the watch of the experimental switch "mute encoder watch": with that switch off nothing reads this, and the fault stays armed (the log says it was armed and never says it was served) until the app is restarted.
 * - [Fault.FOCUS_CYCLE]: the next once-a-second tick of the Android Auto transport asks for a keyframe by the focus cycle, as a stalled picture
 *   would, through the same gate (spacing, storm guard).
 */
object FaultInjection {
    enum class Fault { DECODER_STALL, ENCODER_MUTE, FOCUS_CYCLE }

    private val armed: MutableSet<Fault> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Arms [fault], and says so in the ride log. */
    fun arm(fault: Fault) {
        armed += fault
        RideDiagnostics.warn("debug", "fault injection armed: $fault (debug build only)")
    }

    /** What is armed now, for the tests and the screen. */
    fun armedNow(): Set<Fault> = armed.toSet()

    /** Nothing armed: a new stream or a test starts clean. */
    fun reset() = armed.clear()

    /** The decoder's output should not count for the stall watch. */
    fun swallowDecoderOutput(): Boolean = Fault.DECODER_STALL in armed

    /** The stall watch restarted the decoder: the fault was served. */
    fun decoderRestarted() {
        if (armed.remove(Fault.DECODER_STALL)) RideDiagnostics.log("debug", "fault injection served: the decoder stall made the watch restart it")
    }

    /** The mute watch should hear no frame out of the encoder. */
    fun muteEncoderOutput(): Boolean = Fault.ENCODER_MUTE in armed

    /** The mute watch gave a verdict other than fine: the fault was served. */
    fun encoderVerdictGiven() {
        if (armed.remove(Fault.ENCODER_MUTE)) RideDiagnostics.log("debug", "fault injection served: the mute watch gave its verdict")
    }

    /** True once for each press of the focus cycle trigger. */
    fun takeFocusCycle(): Boolean = armed.remove(Fault.FOCUS_CYCLE)
}

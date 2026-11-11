package com.reau.app.debug

/**
 * The release build's [FaultInjection] (the real one is in `src/debug`): the same questions, always no, and nothing that arms. Nothing of the
 * debug build's triggers is in a release; FaultInjectionAbsenceInReleaseTest holds that.
 */
object FaultInjection {
    fun swallowDecoderOutput(): Boolean = false

    fun decoderRestarted() = Unit

    fun muteEncoderOutput(): Boolean = false

    fun encoderVerdictGiven() = Unit

    fun takeFocusCycle(): Boolean = false
}

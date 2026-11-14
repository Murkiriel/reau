// Copied from open-headunit (AGPL-3.0): app/src/main/java/com/andrerinas/openheadunit/decoder/video/VideoRecoveryPolicy.kt, commit 93c065dd.
// Changes: package; shouldRequestOnDroppedFrame is cut (nothing called it, and its hasRendered gate does not fit REau: the
// frames dropped while the decoder starts are the ones that break the chain). The comments still name open-headunit classes that are not here.
package com.reau.app.aa

/**
 * Pacing rule for [AapVideo]'s stream recovery.
 *
 * Recovering from a corrupt frame means asking the phone for a fresh keyframe, which
 * [com.reau.app.aa.AapTransport] implements as a gain-only unsolicited focus nudge by default, escalating to a real
 * focus loss/regain cycle only per [KeyframeCycleEscalationPolicy]'s much stricter gates. Either one
 * is expensive and briefly visible on screen, and the phone reacts to it by rebuilding the stream,
 * so a burst of requests costs far more picture than the single frame that triggered the first one.
 * Rate limit them.
 *
 * Suppressing a request is safe to do silently: a corrupt frame is scoped to itself and the stream
 * resumes on the next one either way, so a suppressed request only means recovery waits for the
 * phone's own keyframe cadence rather than being brought forward.
 */
object VideoRecoveryPolicy {

    /** Minimum spacing between keyframe requests, in milliseconds. */
    const val KEYFRAME_REQUEST_THROTTLE_MS = 1000L

    /** Whether enough time has passed since [lastRequestMs] to send another keyframe request. */
    fun canRequestKeyframe(nowMs: Long, lastRequestMs: Long): Boolean =
        nowMs - lastRequestMs > KEYFRAME_REQUEST_THROTTLE_MS
}

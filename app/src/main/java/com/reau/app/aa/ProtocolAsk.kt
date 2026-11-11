package com.reau.app.aa

/**
 * Which protocol version the handshake asks Android Auto for. As always it is 1.[AaConfig.protocolMinor] (1.7). With the experimental switch
 * ([AaConfig.protocolSix], [com.reau.app.settings.REauSettings.experimentProtocolSix]) it is [SIX] (6.0) first, which other receivers already ask. A
 * version request is sent once and never again on the same connection (a duplicate leaves Android Auto's head unit server answering nobody until it
 * is restarted), so a refusal is not answered with a second request: a handshake that asked 6.0 and did not complete, for whatever reason (a refusal,
 * no answer, a failed TLS step), makes every later dial of this process ask 1.7 again, and the log says so. A handshake that completed after asking
 * 6.0 keeps asking it, whatever version Android Auto answered with.
 */
object ProtocolAsk {
    data class Version(val major: Int, val minor: Int) {
        override fun toString(): String = "$major.$minor"
    }

    /** The version the switch asks for first. */
    val SIX = Version(6, 0)

    @Volatile private var sixFailed = false

    /** The version to ask: [SIX] when [wantSix] and no handshake of this process failed after asking it, else 1.[minor]. */
    fun resolve(wantSix: Boolean, minor: Int): Version = if (wantSix && !sixFailed) SIX else Version(1, minor)

    /**
     * A handshake that asked [asked] ended, [ok] if it completed; [answered] is the version Android Auto answered ("1.7, status 0", null: no answer).
     * Only a handshake that asked [SIX] is told about, in [say] (with whether it is a failure): what was asked and answered, and, if it failed, that 1.7 is asked from now on.
     */
    fun settle(asked: Version, ok: Boolean, answered: String?, say: (line: String, failed: Boolean) -> Unit) {
        if (asked != SIX) return
        val answer = answered ?: "no answer"
        if (ok) {
            say("Handshake: asked protocol $asked, answered $answer: the handshake completed", false)
        } else {
            sixFailed = true
            say(
                "Handshake: asked protocol $asked, answered $answer: the handshake did not complete; " +
                    "protocol 1.${Messages.MINOR_NEWER} is asked from now on, until the app is closed",
                true,
            )
        }
    }

    internal fun testOnlyReset() {
        sixFailed = false
    }
}

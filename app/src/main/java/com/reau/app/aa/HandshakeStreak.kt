package com.reau.app.aa

/**
 * Handshakes in a row that Android Auto never answered: its server can accept the connection and answer nothing, dozens
 * of times over minutes, until its own process dies, and REau used to say nothing. From the
 * [threshold]-th in a row the receiver says so ([AaLinkState.UNRESPONSIVE]); an answered handshake starts again.
 *
 * [quick]: REau's last process was ended in the middle (an install or a forced stop): Android Auto's server is then
 * left holding the old session and answers nothing until it is restarted (seen in use: 33 s of three rounds each
 * time), so the first unanswered handshake says so, until one is answered.
 */
class HandshakeStreak(quick: Boolean = false) {
    var inARow = 0
        private set

    /** Whether any handshake of this streak was answered (see [PreviousSessionClosing]). */
    var everAnswered = false
        private set

    /** How many unanswered handshakes in a row say Android Auto does not answer. */
    var threshold = if (quick) 1 else THRESHOLD
        private set

    /** The result of one session's handshake; true when the receiver should say Android Auto does not answer. */
    fun after(handshook: Boolean): Boolean {
        if (handshook) {
            threshold = THRESHOLD
            everAnswered = true
        }
        inARow = if (handshook) 0 else inARow + 1
        return inARow >= threshold
    }

    private companion object {
        const val THRESHOLD = 3
    }
}

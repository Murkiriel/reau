package com.reau.app.service

/**
 * Whether the phone has been without a network for [afterMs] without a break, so that a blip does not tell the
 * dash the signal is lost. Asked once for every guidance message; [online] is the phone's own answer.
 */
internal class NoNetworkWatch(
    private val clockMs: () -> Long,
    private val online: () -> Boolean,
    private val afterMs: Long = AFTER_MS,
) {
    private var since = NONE

    fun without(): Boolean {
        val now = clockMs()
        if (online()) {
            since = NONE
            return false
        }
        if (since == NONE) since = now
        return now - since >= afterMs
    }

    private companion object {
        const val AFTER_MS = 5_000L
        const val NONE = -1L
    }
}

package com.reau.app.aa

import android.os.SystemClock

/**
 * Android Auto closes the session of its head unit server at every change of the phone's USB (a cable plugged or unplugged, the data
 * link configured): the log of 11/11/2026 shows `Closing 1 FDs due to USB disconnect` on its side and, on REau's, `Connection closed (EOF)`
 * 15 to 50 ms after the `[system] usb …` line. Nothing REau did ended it, and the server is ready again at once, so this end is said for what it is
 * ([line]) and the first dial after it is made at once ([waitAfterDropMs]) instead of after the second REau gives other ends.
 *
 * A session end counts only when it comes within [windowMs] of a change, and a change explains one end ([explain] takes it).
 * The clock is a parameter: elapsed realtime in the app, a counter in the tests.
 */
internal class UsbSessionEnd(private val clock: () -> Long, private val windowMs: Long = WINDOW_MS) {
    private val lock = Any()
    private var changedAtMs: Long? = null

    /** The phone's USB changed now. */
    fun changed() = synchronized(lock) { changedAtMs = clock() }

    /** A session ended now: what that means, said through [say] (the log) when it follows a USB change. */
    fun noteEnd(say: (String) -> Unit): Ending = explain().also { ending -> ending.line?.let(say) }

    /** What a session end that happens now means: [Ending.NOT_USB] when no change came just before it. */
    fun explain(): Ending = synchronized(lock) {
        val at = changedAtMs ?: return Ending.NOT_USB
        val since = clock() - at
        if (since < 0 || since > windowMs) return Ending.NOT_USB
        changedAtMs = null
        Ending(sinceMs = since)
    }

    /** How a session ended, with regard to the USB: [sinceMs] after a change of it, or null when it did not follow one. */
    class Ending(val sinceMs: Long?) {
        /** The line for the log, null when the end did not follow a USB change. */
        val line: String?
            get() = sinceMs?.let { line(it) }

        /** The wait before the first dial after the end: none after a USB change, [normalMs] after any other end. */
        fun waitAfterDropMs(normalMs: Long): Long = if (sinceMs != null) 0L else normalMs

        /** Waits through [sleep] what [waitAfterDropMs] says; a wait of none does not call it. */
        fun pauseAfterDrop(normalMs: Long, sleep: (Long) -> Unit) {
            val wait = waitAfterDropMs(normalMs)
            if (wait > 0) sleep(wait)
        }

        companion object {
            val NOT_USB = Ending(null)
        }
    }

    companion object {
        /** A session that ends within this long after a change of the USB ended because of it. */
        const val WINDOW_MS = 1_000L

        /** The one in the app, fed by SystemEvents and read by the receivers. */
        val shared = UsbSessionEnd({ SystemClock.elapsedRealtime() })

        internal fun line(sinceMs: Long) =
            "AaReceiver: the Android Auto session ended $sinceMs ms after the USB changed (Android Auto closes its sessions on every USB change)"
    }
}

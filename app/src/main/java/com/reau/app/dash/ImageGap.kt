package com.reau.app.dash

import java.util.Locale

/**
 * How long the dash went without the picture after the image stopped, written on one line when it is back (08/11/2026: the analysis of the
 * rides of 05 to 07/11 worked the time without image of each drop out by hand, 20.4 s to 86.7 s with the dash on and up to 6 min 21 s in all, the
 * rest being the bike switched off). Two numbers: in all, from the last dash packet before the loss to the stream starting again, and with the
 * dash on, from the moment it came on (its heartbeat age at the first packet, [DashReadiness.ageLine]) or from the loss, whichever is later. Only
 * a log line: nothing reads it. A drop with the Wi-Fi firm and the dash on all along says so ("it stayed on").
 *
 * The heartbeat counter wraps at 256 s, so over a gap of that length the dash's time is a lower bound ("at least").
 *
 * The first number of every gap is also summed ([totalMs]) for the projection's `resumo` line (`no image=`), a gap still open counted up to now.
 */
internal class ImageGapClock {
    private var everStreamed = false
    private var lostAtMs = 0L
    private var dashOnAtMs: Long? = null
    private var closedMs = 0L

    /** The time without the picture in this projection so far: the gaps that ended, and the one going on (if any) up to [nowMs]. */
    @Synchronized
    fun totalMs(nowMs: Long): Long = closedMs + if (lostAtMs != 0L) (nowMs - lostAtMs).coerceAtLeast(0) else 0L

    /** The image stopped: the Wi-Fi was lost or the session failed at [nowMs], the dash last heard [heardAgoMs] before (null: not known). The first of a gap counts. */
    @Synchronized
    fun lost(nowMs: Long, heardAgoMs: Long?) {
        if (!everStreamed || lostAtMs != 0L) return
        lostAtMs = (nowMs - (heardAgoMs ?: 0L)).coerceAtLeast(1L)
    }

    /** The dash's first packet of a session, at [nowMs], said it had been on for [ageSeconds]; the last one before the stream is the one kept. */
    @Synchronized
    fun dashAge(nowMs: Long, ageSeconds: Int) {
        if (lostAtMs != 0L) dashOnAtMs = nowMs - ageSeconds * MS_PER_S
    }

    /** The stream starts at [nowMs]: the line for the gap it ends, or null for the first stream of a projection or one that was never interrupted. */
    @Synchronized
    fun streaming(nowMs: Long): String? {
        val lostAt = lostAtMs
        val onAt = dashOnAtMs
        everStreamed = true
        lostAtMs = 0L
        dashOnAtMs = null
        if (lostAt == 0L) return null
        val total = (nowMs - lostAt).coerceAtLeast(0)
        closedMs += total
        val wraps = total >= WRAP_MS
        val withDash = when {
            onAt == null -> "unknown"
            onAt <= lostAt -> "${seconds(total)} (it stayed on)"
            else -> "${if (wraps) "at least " else ""}${seconds(nowMs - onAt)} (it came on ${seconds(onAt - lostAt)} into the loss)"
        }
        return "image back: ${seconds(total)} without it since the last dash packet before the loss; with the dash on: $withDash"
    }

    /** A new projection starts over. */
    @Synchronized
    fun forget() {
        closedMs = 0L
        everStreamed = false
        lostAtMs = 0L
        dashOnAtMs = null
    }

    private fun seconds(ms: Long) = String.format(Locale.US, "%.1f s", ms / MS_PER_S.toDouble())

    private companion object {
        const val MS_PER_S = 1_000L
        const val WRAP_MS = 256_000L
    }
}

/** The one clock of the process (one projection at a time; [DashHeard] is the same). */
internal object ImageGap {
    val clock = ImageGapClock()
}

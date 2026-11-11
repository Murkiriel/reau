package com.reau.app.ui.components

/** Where one side of the bridge stands: its colour and icon follow it, always with text. */
enum class StatusKind { IDLE, CONNECTING, OK, ERROR }

/** The components' rules that need no screen, tested on the JVM. */
object ComponentLogic {
    /** The bridge line between the two sides: a problem on either wins, then a side still connecting, then both ready. */
    fun bridgeKind(aa: StatusKind, panel: StatusKind): StatusKind = when {
        aa == StatusKind.ERROR || panel == StatusKind.ERROR -> StatusKind.ERROR
        aa == StatusKind.CONNECTING || panel == StatusKind.CONNECTING -> StatusKind.CONNECTING
        aa == StatusKind.OK && panel == StatusKind.OK -> StatusKind.OK
        else -> StatusKind.IDLE
    }

    /** A counter's [value] moved by [delta], kept inside [range]. */
    fun stepped(value: Int, delta: Int, range: IntRange): Int = (value + delta).coerceIn(range)

    /**
     * How many segments of [count] go on one line, [availablePx] wide with [gapPx] between them, when the widest wants [widestPx]:
     * all of them when each equal share holds the widest, else as many as hold it, the lines kept even (four go 2 + 2, not 3 + 1),
     * and at least one. A segment is never cut: the row gets another line first.
     */
    fun segmentColumns(count: Int, widestPx: Int, availablePx: Int, gapPx: Int): Int {
        if (count <= 1) return 1
        val fit = ((availablePx + gapPx) / (widestPx + gapPx).coerceAtLeast(1)).coerceIn(1, count)
        val lines = (count + fit - 1) / fit
        return (count + lines - 1) / lines
    }

    /** Android's "remove animations" sets the animator duration scale to 0: then nothing spins or slides. */
    fun animationsOn(animatorScale: Float): Boolean = animatorScale != 0f

    /** The time since the projection started, as the Desligar button and the notification show it: mm:ss. */
    fun elapsed(ms: Long): String {
        val seconds = (ms / MS_PER_SECOND).coerceAtLeast(0)
        return "%02d:%02d".format(java.util.Locale.ROOT, seconds / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE)
    }

    /** What Desligar shows: the time since [startedAtMs] at [nowMs] while the projection [running], none otherwise or with no start known. */
    fun elapsedSince(running: Boolean, startedAtMs: Long?, nowMs: Long): String? =
        if (running && startedAtMs != null) elapsed(nowMs - startedAtMs) else null

    private const val MS_PER_SECOND = 1000L
    private const val SECONDS_PER_MINUTE = 60L
}

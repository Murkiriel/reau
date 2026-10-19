package com.reau.app.aa

import java.util.Locale
import java.util.TreeMap

/**
 * Android Auto's side of a session counted **before the decoder**, which the log could not see before: the messages
 * by channel, the video's access units and bytes, keyframes, pieces, frames rebuilt from pieces and frames lost (a
 * broken run of pieces counts once), video focus changes, sensor requests, and the decoder's time from input to
 * picture. [windowLine] gives one `aa-stats` line per window and starts the next; the totals go on for
 * the whole session.
 */
@Suppress("TooManyFunctions") // one small counting call per thing counted, the line and the totals
class AaStats(private val nowMs: () -> Long = { android.os.SystemClock.elapsedRealtime() }) {
    data class Totals(
        val units: Long, val keyframes: Long, val assembled: Long, val lost: Long, val decoded: Long, val messages: Long,
        val focus: Long = 0, val sensor: Long = 0,
    )

    private class Counts {
        val byChannel = TreeMap<Int, Long>()
        var units = 0L
        var bytes = 0L
        var keyframes = 0L
        var pieces = 0L
        var assembled = 0L
        var lost = 0L
        var focus = 0L
        var sensor = 0L
        var decoded = 0L
        var decodeSumUs = 0L
        var decodeMaxUs = 0L
        var readHeldNs = 0L
        var readHeldMaxNs = 0L
    }

    private val lock = Any()
    private var window = Counts()
    private val total = Counts()
    private var windowStartMs = nowMs()

    private inline fun both(action: Counts.() -> Unit) = synchronized(lock) {
        window.action()
        total.action()
    }

    fun message(channel: Int) = both { byChannel.merge(channel, 1L, Long::plus) }

    /** An access unit handed to the decoder. */
    fun unit(bytes: Int, keyframe: Boolean) {
        // Once, here: `both` below runs its block for the window and for the total, which counted every unit twice.
        AaVideoMeter.units.incrementAndGet()
        AaVideoMeter.bytes.addAndGet(bytes.toLong())
        both {
            units++
            this.bytes += bytes
            if (keyframe) keyframes++
        }
    }

    /** A piece of a picture (flags 9, 8 or 10) arrived. */
    fun piece() = both { pieces++ }

    /** A picture rebuilt from its pieces went to the decoder. */
    fun assembled() = both { assembled++ }

    /** A picture was given up: a piece went missing, a first piece had no start, it outgrew the buffer. */
    fun lost() = both { lost++ }

    /** REau took video focus away or gave it back (a keyframe asked for is one of each). */
    fun focus() = both { focus++ }

    /**
     * Android Auto's read thread spent [heldNs] inside the decoder's input with one access unit (the read
     * waits for it: the next message is not read meanwhile).
     */
    fun readHeld(heldNs: Long) = both {
        readHeldNs += heldNs
        readHeldMaxNs = maxOf(readHeldMaxNs, heldNs)
    }

    /** Android Auto asked for a sensor. */
    fun sensor() = both { sensor++ }

    /** The decoder gave a picture [latencyUs] after its input went in. */
    fun decoded(latencyUs: Long) = both {
        decoded++
        decodeSumUs += latencyUs
        decodeMaxUs = maxOf(decodeMaxUs, latencyUs)
    }

    /** The line of the window since the last one (or the session's start), and a new window from now. */
    fun windowLine(): String = synchronized(lock) {
        val now = nowMs()
        val w = window
        val seconds = (now - windowStartMs) / MS_PER_S
        window = Counts()
        windowStartMs = now
        val channels = w.byChannel.entries.joinToString(" ") { (ch, n) -> "ch$ch=$n" }
        val decode = if (w.decoded == 0L) "-" else "${w.decodeSumUs / w.decoded / US_PER_MS}/${w.decodeMaxUs / US_PER_MS}ms"
        String.format(Locale.US, "aa-stats %.1fs: ", seconds) +
            "msgs=${w.byChannel.values.sum()} ($channels) nav=${w.byChannel[Channel.ID_NAV] ?: 0} " +
            "units=${w.units} bytes=${w.bytes / BYTES_PER_KB}KB key=${w.keyframes} pieces=${w.pieces} " +
            "assembled=${w.assembled} lost=${w.lost} focus=${w.focus} sensor=${w.sensor} decoded=${w.decoded} " +
            "decode=$decode readHeld=${ms(w.readHeldNs)}/${ms(w.readHeldMaxNs)}ms " +
            "| total units=${total.units} key=${total.keyframes} assembled=${total.assembled} " +
            "lost=${total.lost} decoded=${total.decoded}"
    }

    fun totals(): Totals = synchronized(lock) {
        Totals(total.units, total.keyframes, total.assembled, total.lost, total.decoded, total.byChannel.values.sum(), total.focus, total.sensor)
    }

    fun messagesByChannel(): Map<Int, Long> = synchronized(lock) { TreeMap(total.byChannel) }

    companion object {
        private const val MS_PER_S = 1000.0
        private const val US_PER_MS = 1000
        private const val BYTES_PER_KB = 1024
        private const val NAL_TYPE_MASK = 0x1F
        private const val NAL_IDR = 5
        private const val NS_PER_MS = 1e6

        private fun ms(ns: Long) = String.format(Locale.US, "%.1f", ns / NS_PER_MS)

        /** How far into an access unit an IDR slice is looked for: past an access unit delimiter, SPS and PPS. */
        private const val IDR_SEARCH_BYTES = 512

        /** Whether the access unit in [buffer] has an IDR slice (a keyframe) near its start. */
        fun hasIdr(buffer: ByteArray, offset: Int, length: Int): Boolean {
            val end = minOf(offset + length, offset + IDR_SEARCH_BYTES, buffer.size) - 3
            var i = offset
            while (i < end) {
                if (buffer[i].toInt() == 0 && buffer[i + 1].toInt() == 0 && buffer[i + 2].toInt() == 1) {
                    if (buffer[i + 3].toInt() and NAL_TYPE_MASK == NAL_IDR) return true
                    i += 3
                } else {
                    i++
                }
            }
            return false
        }
    }
}

/** Counts every message of the session by channel, then hands it on. */
internal class AaStatsHandler(
    private val stats: AaStats,
    private val next: AapMessageHandler,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : AapMessageHandler {
    private val video = VideoDetail()

    override fun handle(message: AapMessage) {
        stats.message(message.channel)
        // With the detailed log on, each message summed up (channel, type, flags, size). The
        // video channel's, which were most of the file, are one line a second instead.
        if (!com.reau.app.data.DetailedLog.enabled) {
            video.clear()
        } else if (message.channel == Channel.ID_VID) {
            video.add(message, nowMs())?.let { line -> com.reau.app.data.DetailedLog.offer("aa") { line } }
        } else {
            com.reau.app.data.DetailedLog.offer("aa") {
                "rx ch=${message.channel} type=${message.type} flags=0x%02X size=${message.size}".format(message.flags.toInt() and 0xFF)
            }
        }
        next.handle(message)
    }
}

/**
 * The video messages of the detailed log, one line for each second instead of one for each message
 * (otherwise they are close to half of a ride's file). Only the first piece of a picture and a message that comes
 * whole carry a type of their own (bit 0x01 of the flags); in the pieces that follow, "type" is the picture's own
 * bytes, so it is not counted there.
 */
internal class VideoDetail {
    private var second = Long.MIN_VALUE
    private var messages = 0
    private var firsts = 0
    private var bytes = 0L
    private var biggest = 0
    private val types = TreeMap<Int, Int>()

    /** Counts [message] in the second of [nowMs]; the line of the second that just ended, if this one began another. */
    fun add(message: AapMessage, nowMs: Long): String? {
        val now = nowMs / MS_PER_S
        val ended = if (now != second && messages > 0) line() else null
        if (now != second) {
            clear()
            second = now
        }
        messages++
        bytes += message.size
        biggest = maxOf(biggest, message.size)
        if (message.flags.toInt() and FIRST_PIECE != 0) {
            firsts++
            types.merge(message.type, 1, Int::plus)
        }
        return ended
    }

    fun clear() {
        messages = 0
        firsts = 0
        bytes = 0L
        biggest = 0
        types.clear()
    }

    private fun line() = "rx video $messages msgs ($firsts first or whole, ${messages - firsts} continuation) " +
        "bytes=$bytes max=$biggest types=" + types.entries.joinToString(",") { (type, n) -> "$type:$n" }

    private companion object {
        const val MS_PER_S = 1000L
        const val FIRST_PIECE = 0x01
    }
}

package com.reau.app.dash

/**
 * What the ride log shows of the sequence of commands sent to the dash when it is switched off (Desligar): each command, whether it went to the
 * wire, and when, from the first; and what the dash said back, if anything reached the receive loop meanwhile. Only log: nothing in the sequence
 * (its order, the repeats, the 400 ms the main thread waits for it) is changed by this.
 *
 * The dash's answer to each command is what this was meant to record, and the sequence cannot show it as it is: the receive loop is cancelled
 * before the commands go and the socket is closed right after the last one (a few milliseconds in all), so an answer can only be read if it
 * lands in that window, while the loop is still inside its blocking read. [reply] writes the ones that do (the TLV keys only: no value), and the
 * summary line says in plain words how many were read, so a log with none is not taken for a dash that did not answer.
 */
internal class ExitSequence(private val nowMs: () -> Long) {
    private class Step(val name: String, val sent: Boolean, val atMs: Long)

    private val steps = ArrayList<Step>()
    private var startMs = -1L
    private var replies = 0

    /** A command ([name]) was handed to the socket at the clock's now; [sent] is whether it reached the wire. */
    @Synchronized
    fun step(name: String, sent: Boolean) {
        val now = nowMs()
        if (startMs < 0) startMs = now
        steps += Step(name, sent, now - startMs)
    }

    /**
     * A packet of the dash reached the receive loop while the sequence goes on; [keys] are its TLV keys (`0B02 0902`). The line to write for it, null
     * past the first [MAX_REPLY_LINES] (the rest are only counted, in [summary]).
     */
    @Synchronized
    fun reply(keys: String): String? {
        replies++
        if (replies > MAX_REPLY_LINES) return null
        val after = if (startMs < 0) "before the first" else "${nowMs() - startMs} ms after the first"
        val last = steps.lastOrNull()?.let { ", last command sent: ${it.name}" }.orEmpty()
        return "exit sequence: the dash answered $after exit command ($keys)$last"
    }

    /** The line for the whole sequence, written when it is done (the socket closed at [closedMs] from the first command). */
    @Synchronized
    fun summary(closedMs: Long = if (startMs < 0) 0 else nowMs() - startMs): String {
        val groups = ArrayList<Triple<String, List<Step>, Boolean>>()
        for (s in steps) {
            val last = groups.lastOrNull()
            if (last != null && last.first == s.name && last.third == s.sent) {
                groups[groups.lastIndex] = Triple(s.name, last.second + s, s.sent)
            } else {
                groups += Triple(s.name, listOf(s), s.sent)
            }
        }
        val text = groups.joinToString("; ") { (name, list, sent) ->
            val times = if (list.size == 1) "+${list.first().atMs} ms" else "+${list.first().atMs}..${list.last().atMs} ms"
            "$name ${if (sent) "sent" else "NOT SENT"}${if (list.size > 1) " x${list.size}" else ""} $times"
        }
        val answers = if (replies == 0) {
            "no answer of the dash read (the receive loop is cancelled before the commands and the socket closes right after them)"
        } else {
            "$replies packet(s) of the dash read meanwhile"
        }
        return "exit sequence: ${steps.size} commands, ${steps.count { !it.sent }} not sent: ${text.ifEmpty { "none" }}; socket closed +$closedMs ms; $answers"
    }

    companion object {
        const val MAX_REPLY_LINES = 6
    }
}

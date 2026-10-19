package com.reau.app.aa

/**
 * When to read Android Auto's screen again without waiting for the system to say a window changed. When the rider
 * picked Waze in the menu with the joystick, the system said it once, about 300 ms after the key and before the focus
 * moved; the focus then moved with no event, and the menu's fit stayed on Waze for close to a minute.
 *
 * After each input sent to Android Auto, reads at [afterInputMs] (a new input replaces the reads still waiting, so a
 * burst of inputs does not pile them up); while the projection runs, one every [everyMs] as a safety net. A read that
 * falls due is taken once. Not thread-safe: one thread (the accessibility service's main thread) drives it.
 */
class AaScreenRereads(private val afterInputMs: List<Long> = AFTER_INPUT_MS, private val everyMs: Long = EVERY_MS) {
    private var planned = ArrayDeque<Long>()
    private var nextTick: Long? = null

    /** An input (key, wheel step, touch) was sent to Android Auto at [nowMs]. */
    fun inputSent(nowMs: Long) {
        planned = ArrayDeque(afterInputMs.map { nowMs + it })
    }

    /** The projection started ([on]) or stopped at [nowMs]: the reads every [everyMs] go with it. */
    fun projection(on: Boolean, nowMs: Long) {
        nextTick = if (on) nowMs + everyMs else null
    }

    /** Whether a read is due at [nowMs]; the reads it covers are taken. */
    fun due(nowMs: Long): Boolean {
        var hit = false
        while (planned.isNotEmpty() && planned.first() <= nowMs) {
            planned.removeFirst()
            hit = true
        }
        var tick = nextTick
        if (tick != null && tick <= nowMs) {
            while (tick!! <= nowMs) tick += everyMs
            nextTick = tick
            hit = true
        }
        return hit
    }

    /** When the next read falls due, or null when none is planned. */
    fun nextAt(): Long? = listOfNotNull(planned.firstOrNull(), nextTick).minOrNull()

    companion object {
        val AFTER_INPUT_MS = listOf(300L, 800L, 1_500L)
        const val EVERY_MS = 1_000L
    }
}

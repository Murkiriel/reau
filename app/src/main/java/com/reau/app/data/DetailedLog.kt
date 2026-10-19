package com.reau.app.data

/**
 * The detailed log, behind the "Registro detalhado" switch, off by default. Switched on, each line goes to the ride log
 * under the `detail` tag; switched off, the message is not even built. At most [maxPerSecond] lines a second: the rest
 * is dropped and counted, and the count is said when the next second starts.
 */
class DetailedLogger(private val maxPerSecond: Int, private val nowMs: () -> Long, private val write: (String, String) -> Unit) {
    @Volatile var enabled = false

    private var second = Long.MIN_VALUE
    private var inSecond = 0
    private var dropped = 0

    /** [message] about [about] (dash, aa…), built only if the switch is on and the second has room. */
    @Synchronized
    fun offer(about: String, message: () -> String) {
        if (!enabled) return
        val now = nowMs() / MS_PER_S
        if (now != second) {
            if (dropped > 0) write(TAG, "$dropped lines over the ceiling of $maxPerSecond a second dropped")
            second = now
            inSecond = 0
            dropped = 0
        }
        if (inSecond >= maxPerSecond) {
            dropped++
            return
        }
        inSecond++
        write(TAG, "$about ${message()}")
    }

    private companion object {
        const val TAG = "detail"
        const val MS_PER_S = 1000L
    }
}

/** The app's detailed log: switched with the setting (REauApp at the start, MainActivity on a change). */
object DetailedLog {
    /** About 50 lines a second: the dash's acks at 30 fps and Android Auto's video fit, a ride still fits the file. */
    private const val MAX_PER_SECOND = 50

    @Volatile var logger = DetailedLogger(MAX_PER_SECOND, System::currentTimeMillis) { tag, line -> RideDiagnostics.log(tag, line) }

    var enabled: Boolean
        get() = logger.enabled
        set(value) {
            logger.enabled = value
        }

    /**
     * Inline, so a caller with the switch off (the dash's every packet, Android Auto's 30 stats a second)
     * does not even make the lambda; the switch is read before anything else.
     */
    inline fun offer(about: String, crossinline message: () -> String) {
        val l = logger
        if (!l.enabled) return
        l.offer(about) { message() }
    }
}

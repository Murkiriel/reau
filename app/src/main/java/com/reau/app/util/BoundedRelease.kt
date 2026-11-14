package com.reau.app.util

/**
 * Runs something that may never come back (releasing a hardware codec) on a thread of its own and waits for it only so long.
 *
 * A MediaCodec whose driver hangs does not return from `stop()` or `release()`; called from the session's thread it took the
 * thread, and with it the end of the session, for good. Here the caller gives up after [joinMs] and goes on; the thread of the
 * task stays behind, a daemon, and ends when the driver lets it (or with the process).
 *
 * The idea is that of the ridescreen-aa fork of OpenCfMoto, whose codec release hung on a phone and killed the process. Nothing of
 * its code is copied.
 */
object BoundedRelease {
    /** How long the end of a decoder waits for its codec to be released: a normal release takes tens of milliseconds, the slowest seen 483. */
    const val RELEASE_JOIN_MS = 1_500L

    const val THREAD_NAME = "AaDecoderRelease"
    private const val NANOS_PER_MS = 1_000_000L

    /**
     * Runs [task] on a daemon thread named [threadName] ([THREAD_NAME] unless the caller says what the thread is for) and waits up to [joinMs] for it. True: it finished, without throwing. False: it threw
     * (swallowed: the caller has nothing to do about it; a task that wants to log does so itself), or it was still running when the
     * time ran out, and then [onStuck] is called once, on the caller's thread, and the task is left to its thread.
     *
     * An interrupt on the caller's thread does not cut the wait short (the receiver interrupts the session thread at Desligar, and
     * that thread ends here); it is given back at the end.
     */
    @Suppress("TooGenericExceptionCaught") // whatever the task throws is the task's own business
    fun run(task: () -> Unit, joinMs: Long, onStuck: () -> Unit, threadName: String = THREAD_NAME): Boolean {
        var threw = false
        val thread = Thread({
            try {
                task()
            } catch (_: Throwable) {
                threw = true
            }
        }, threadName).apply {
            isDaemon = true
            start()
        }
        var interrupted = false
        val deadline = System.nanoTime() + joinMs * NANOS_PER_MS
        while (thread.isAlive) {
            val left = (deadline - System.nanoTime()) / NANOS_PER_MS
            if (left <= 0) break
            try {
                thread.join(left)
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
        if (thread.isAlive) {
            onStuck()
            return false
        }
        return !threw
    }
}

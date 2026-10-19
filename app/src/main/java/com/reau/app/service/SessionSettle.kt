package com.reau.app.service

/**
 * A setting that needs a new Android Auto session
 * (resolution, density, margins) waits [waitMs] with no other such change before the session is opened, with the last
 * values. Without the wait, four density changes within a second were seen to open three sessions, each a
 * reconnection of Android Auto. The fit, the crop and the move do not come here: they still apply at once.
 *
 * [later] runs an action after a delay and hands back how to cancel it (a coroutine in the sides, a clock in tests).
 */
class SessionSettle(private val waitMs: Long = WAIT_MS, private val later: (Long, () -> Unit) -> () -> Unit) {
    private var pending: (() -> Unit)? = null

    /** [action] runs [waitMs] from now, unless another offer comes first: then only the newest runs. */
    @Synchronized
    fun offer(action: () -> Unit) {
        pending?.invoke()
        pending = later(waitMs) {
            synchronized(this) { pending = null }
            action()
        }
    }

    companion object {
        const val WAIT_MS = 1_000L
    }
}

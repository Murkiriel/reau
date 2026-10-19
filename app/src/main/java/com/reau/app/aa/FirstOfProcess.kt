package com.reau.app.aa

import java.util.concurrent.atomic.AtomicBoolean

/** True once: the first [take] in this process, false after. */
class FirstOfProcess {
    private val taken = AtomicBoolean(false)

    fun take(): Boolean = taken.compareAndSet(false, true)

    companion object {
        /** The first projection of this REau process (the quick restart rule of [HandshakeStreak] is for it only). */
        val projection = FirstOfProcess()
    }
}

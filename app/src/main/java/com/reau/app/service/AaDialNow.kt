package com.reau.app.service

/**
 * The receiver's "dial now", for the moment the rider is back from Android Auto's settings, where he may have switched its head unit
 * server on by hand: the dial is made then, not at the end of the receiver's wait (a refused dial costs nothing).
 *
 * Each projection has its number ([started]); the end of an older one, arriving after the start of a newer one (off and on again in
 * under 2 s), leaves the newer one's dial alone.
 */
object AaDialNow {
    private val lock = Any()
    private var generation = 0

    @Volatile internal var dialSoon: (() -> Unit)? = null
        private set

    /** The projection switched Android Auto's side on: [dial] makes the receiver dial at once. Returns this projection's number. */
    fun started(dial: () -> Unit): Int = synchronized(lock) {
        generation++
        dialSoon = dial
        generation
    }

    /** Projection [of] switched Android Auto's side off: its dial goes, unless a newer projection already began. */
    fun stopped(of: Int) = synchronized(lock) {
        if (of == generation) dialSoon = null
    }

    /** Dials now, if a projection is running. */
    fun now() {
        dialSoon?.invoke()
    }
}

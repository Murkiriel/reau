package com.reau.app.service

import java.util.concurrent.atomic.AtomicInteger

/**
 * Who may write the projection's process-wide state (the [ProjectionService.projection] and `startedAt` flows) when two instances of the service
 * overlap (A-20 of the independent review of 26/10/2026).
 *
 * The service's `onDestroy` lets go of its sides, its worker and its locks on a thread of its own, up to 5 s after it returns, and Android may
 * create the next instance in the meantime (a Ligar right after a Desligar). The old instance's worker was still writing to the same two flows: a stop
 * finishing late set the state back to "stopped", and the release step of `onDestroy` reset it to idle, over the projection the new instance had
 * started, so the screen and the notification said off while the dash was being connected.
 *
 * Each instance takes a number ([newInstance]) when it is built; the first write of an instance [publish]es its number as the owner's, and a write of
 * an older number after that is dropped. An instance that has written nothing yet does not take the state from the old one (a service made only to
 * be stopped writes nothing, and the state then stays as the old instance left it). With one instance at a time, as nearly always, every write goes
 * through, as before.
 */
internal class StateOwner {
    private val last = AtomicInteger()
    private var owner = 0

    /** A number for a new instance, higher than all before it. */
    fun newInstance(): Int = last.incrementAndGet()

    /** Runs [write] if no newer instance than [instance] has written already; false (nothing done) if one has. */
    @Synchronized
    fun publish(instance: Int, write: () -> Unit): Boolean {
        if (instance < owner) return false
        owner = instance
        write()
        return true
    }

    /** The newest instance that has written; 0 when none has. */
    @Synchronized
    fun owner(): Int = owner
}

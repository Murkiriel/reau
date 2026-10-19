package com.reau.app.service

import com.reau.app.data.RideDiagnostics

/**
 * Carries out what [ProjectionMachine] decides. Events may come from any thread (the screen, Android Auto's
 * receiver, the dash session, a timer); they are taken one at a time, in the order they came, and each effect goes
 * to its port. The thread that finds the queue idle runs it until it is empty; an event sent meanwhile, even from
 * inside a port, waits its turn. So no two ports ever run at once, and a port may report back without deadlock.
 *
 * A port that throws costs its own effect and a line in [log], never the rest of the step: the dash side starts
 * even if Android Auto's receiver could not. A [Ports.prepare] that throws counts as one that said no.
 */
class ProjectionController(
    private val ports: Ports,
    private val log: (String) -> Unit = {},
    private val onChange: (Projection) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** What the effects do. The service implements them; a test records them. */
    interface Ports {
        /** Foreground, locks: null when done, or why not. */
        fun prepare(): String?
        fun release()
        fun startAndroidAuto()
        fun stopAndroidAuto()
        fun startDash()
        fun stopDash()
        fun startVideo()
        fun stopVideo()

        /** Runs [action] once, [delayMs] from now, on any thread. Returns what calls it off (a no-op once it has run). */
        fun schedule(delayMs: Long, action: () -> Unit): () -> Unit

        /** The last try found the dash's Wi-Fi in the scan and was refused (stuck or busy): it is there. */
        fun dashVisible(): Boolean = false
    }

    @Volatile var state = Projection()
        private set

    private val lock = Any()
    private val queue = ArrayDeque<ProjectionEvent>()
    private var draining = false

    // The retry now waiting (only the drain loop touches it): what calls it off, and which one it is, so that a
    // wait called off that runs all the same (it was already on its way) finds it is not the current one.
    private var calledOff: (() -> Unit)? = null
    @Volatile private var retryNumber = 0

    fun submit(event: ProjectionEvent) {
        synchronized(lock) {
            queue.addLast(event)
            if (draining) return
            draining = true
        }
        while (true) {
            val next = synchronized(lock) {
                queue.removeFirstOrNull().also { if (it == null) draining = false }
            } ?: return
            val step = ProjectionMachine.step(state, next, clock())
            val changed = step.state != state
            state = step.state
            if (changed) guarded("reporting the state") { onChange(step.state) }
            step.effects.forEach(::carryOut)
        }
    }

    // Containing a port's failure, whatever it is, is what this class is for (see its comment).
    @Suppress("TooGenericExceptionCaught")
    private fun carryOut(effect: ProjectionEffect) {
        if (effect == ProjectionEffect.Prepare) {
            val refusal = try {
                ports.prepare()
            } catch (e: RuntimeException) {
                "${e.javaClass.simpleName}: ${e.message}"
            }
            submit(if (refusal == null) ProjectionEvent.Prepared else ProjectionEvent.PrepareFailed(refusal))
            return
        }
        if (effect == ProjectionEffect.FinishStop) {
            // After the other effects of the stop, each contained on its own: the screen leaves "Desligando" even if one failed.
            submit(ProjectionEvent.StopDone)
            return
        }
        guarded(effect.toString()) {
            when (effect) {
                ProjectionEffect.Release -> ports.release()
                ProjectionEffect.StartAndroidAuto -> ports.startAndroidAuto()
                ProjectionEffect.StopAndroidAuto -> ports.stopAndroidAuto()
                ProjectionEffect.StartDash -> ports.startDash()
                ProjectionEffect.StopDash -> ports.stopDash()
                ProjectionEffect.StartVideo -> ports.startVideo()
                ProjectionEffect.StopVideo -> ports.stopVideo()
                is ProjectionEffect.RetryDashIn -> {
                    // A dash's Wi-Fi was once seen in the scan but refusing the join for two minutes: a dash
                    // that is there and refusing is tried again at most every VISIBLE_RETRY_MS, to catch it the moment it
                    // is back. The slow minute of the long absence (no dash in the scan) stays.
                    val wait = retryWait(effect.delayMs, ports.dashVisible())
                    // Each try's wait in the ride log, to see the pace when the bike is gone.
                    log("projection: the dash is tried again in ${wait / 1_000}s" + if (wait != effect.delayMs) " (it is in the scan, refusing)" else "")
                    calledOff?.invoke()
                    val mine = ++retryNumber
                    calledOff = ports.schedule(wait) { if (mine == retryNumber) submit(ProjectionEvent.DashRetryDue) }
                }
                ProjectionEffect.CancelDashRetry -> {
                    retryNumber++
                    calledOff?.invoke()
                    calledOff = null
                    log("projection: the bike's Wi-Fi is back: the dash is tried now, not when the wait ends")
                }
                ProjectionEffect.Prepare, ProjectionEffect.FinishStop -> Unit
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // as in carryOut
    private inline fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: RuntimeException) {
            log("${RideDiagnostics.ERROR}projection: $what failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    companion object {
        /** The longest wait between tries while the dash's Wi-Fi is in the scan and refusing the join. */
        const val VISIBLE_RETRY_MS = 10_000L

        /** The machine's slow minute is for a dash that is gone; only its doubling waits (up to 30 s) are cut. */
        private const val SLOW_RETRY_MS = 60_000L

        /** [delayMs] as the machine asked, cut to [VISIBLE_RETRY_MS] when the dash is in the scan and refusing. */
        fun retryWait(delayMs: Long, dashVisible: Boolean): Long =
            if (dashVisible && delayMs < SLOW_RETRY_MS) minOf(delayMs, VISIBLE_RETRY_MS) else delayMs
    }
}

package com.reau.app.web

import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** The real [Delayer]: one daemon thread that runs what was asked after its time; [shutdown] ends it. */
class ExecutorDelayer : Delayer {
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "web-delayer").apply { isDaemon = true }
    }

    override fun after(ms: Long, task: () -> Unit): () -> Unit {
        val job = try {
            executor.schedule(task, ms, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            return {}
        }
        return { job.cancel(false) }
    }

    fun shutdown() {
        executor.shutdownNow()
    }
}

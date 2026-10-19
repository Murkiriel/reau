package com.reau.app.data

import kotlin.concurrent.thread

/**
 * A reader of lines opened again and again on a thread of its own, its lines handed to [sink], until
 * [stop]. On the phone the reader is the app's own logcat ([OwnLogcat]); here it has no Android, for the JVM tests.
 * A reader that ends or fails to open is opened again after [pauseMs]; each failure is said through [log].
 */
class LogcatPump(
    private val open: () -> Sequence<String>,
    private val sink: (String) -> Unit,
    private val pauseMs: Long = 2_000,
    private val log: (String) -> Unit = {},
) {
    @Volatile private var running = false
    private var worker: Thread? = null

    @Synchronized
    fun start() {
        if (running) return
        running = true
        worker = thread(name = "own-logcat", isDaemon = true) {
            while (running) {
                runCatching { open().forEach { if (running) sink(it) } }
                    .onFailure { log("own logcat reader failed: $it") }
                if (running) runCatching { Thread.sleep(pauseMs) }
            }
        }
    }

    @Synchronized
    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
    }
}

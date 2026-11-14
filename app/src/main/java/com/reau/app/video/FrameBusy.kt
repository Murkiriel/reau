package com.reau.app.video

/**
 * How long the compositor has been inside the frame it is drawing, for [DashCompositor.busyMs]: [track] brackets one frame (the start is
 * written on the compositor's thread and cleared when the frame ends, whatever way it ends), [busyMs] is read from any thread. A frame
 * stuck in `eglSwapBuffers` shows as a figure that keeps growing; between frames it is 0.
 */
internal class FrameBusy(private val now: () -> Long) {
    @Volatile private var startedAtMs = 0L

    fun <T> track(frame: () -> T): T {
        startedAtMs = now()
        try {
            return frame()
        } finally {
            startedAtMs = 0L
        }
    }

    fun busyMs(): Long = CompositorRules.busyMs(startedAtMs, now())
}

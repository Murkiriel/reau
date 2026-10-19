// Derived from OpenCfMoto (AGPL-3.0): app/src/main/java/dev/zanderp/opencfmoto/AaCompositor.kt, commit 0abbe2a.
// Kept from it: one dedicated GL thread, an input surface that exists before any output, and repeating the last
// picture when Android Auto goes quiet. The OpenGL part is in CompositorGl.kt.
// Changes: the source and destination are free rectangles (FrameFit.Mapping) instead of FILL/FIT/STRETCH with a
// centred crop; frames are drawn at the ticks of the compositor's own clock (the dash's pace) instead of when a
// frame arrives; the time stamped on each frame is the video clock (DashLogic.nextVideoPts), not System.nanoTime();
// with no picture yet it sends black; the phone preview target (since brought back, see setPreview), the touch
// mapping, the mirror-capture path and the
// dependencies on BikeProfileHolder, ScreenMargins, ScreenFit and PowerMode are cut.
package com.reau.app.video

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import com.reau.app.data.RideDiagnostics
import com.reau.app.stream.DashLogic
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Puts Android Auto's picture on the dash's 526×300 frame, at the dash's pace.
 *
 * The Android Auto decoder renders into [inputSurface]. At every tick of its own clock the compositor takes the
 * most recent picture and draws the [FrameFit.Mapping]'s source rectangle into its destination rectangle, on
 * black, on the encoder's input surface ([setOutput]). With nothing new from Android Auto it repeats the last
 * picture at the idle interval, so the dash keeps receiving video while Android Auto pauses (it does, for seconds,
 * when its app list opens or a call comes in). Before any picture has arrived it sends black.
 *
 * [inputSurface] exists right after [start], before there is any output: Android Auto can reach steady video
 * first. Arriving pictures are always latched, so the decoder is never held back, whatever the output pace.
 *
 * All GL work ([CompositorGl]) happens on this object's own thread.
 */
// The dash's output, its pace and the phone's preview are each a pair of start/stop calls on the one GL thread.
@Suppress("TooManyFunctions")
class DashCompositor(private val log: (String) -> Unit) {

    /**
     * The clock: a tick every [activeMs]; a new picture is drawn at the next tick, and with nothing new the last
     * one is repeated every [idleMs]. [clockSlowPercent] is the video clock knob of [DashLogic.nextVideoPts].
     */
    data class Pace(val activeMs: Long, val idleMs: Long, val clockSlowPercent: Int = 0)

    /** Since the last [takeStats]: pictures that arrived, frames drawn from a new picture, frames that repeated
     *  the last one (or black), and the time spent composing all of them. */
    data class Stats(val sourceFrames: Int, val drawn: Int, val repeated: Int, val composeNs: Long)

    private val thread = HandlerThread("dash-compositor").apply { start() }
    private val handler = Handler(thread.looper)
    private val gl = CompositorGl()

    /** Where the Android Auto decoder renders. Valid after [start]. */
    @Volatile var inputSurface: Surface? = null
        private set

    // Everything below is touched on the GL thread only.
    private var sourceW = 0
    private var sourceH = 0
    private var window = CompositorMath.TexWindow(0f, 0f, 1f, 1f)
    private var viewport = RectPx(0, 0, DashGlass.FRAME_W, DashGlass.FRAME_H)
    private var previewW = 0
    private var previewH = 0
    private var webW = 0
    private var webH = 0
    private var lastWebDrawNs = 0L
    private var pace = Pace(DashLogic.IDLE_MIN_FRAME_MS, DashLogic.IDLE_MIN_FRAME_MS)
    private var hasImage = false
    private var newImage = false
    private var sinceLastFrameMs = 0L
    private var tickSpanMs = 0L
    private var nextTickAtMs = 0L
    private var ptsMs = 0.0
    private var lastStampMs = -1L
    private var onFrameSubmitted: (Long) -> Unit = {}
    private var postFailing = false
    private var overlay = StatsOverlay()
    private var overlayText = StatsOverlay.latest

    /** The words of the "picture stuck" badge on the dash's video, or null for none (Android Auto's picture is alive). */
    private var staleBadge: String? = null

    private val sourceFrames = AtomicInteger()
    private val drawn = AtomicInteger()
    private val repeated = AtomicInteger()
    private val composeNs = AtomicLong()

    /**
     * Brings up OpenGL and the input surface for a [sourceW]×[sourceH] source (the frame size asked of Android
     * Auto). The mapping starts as the whole frame filling the dome ([FrameFit.fillDome]). Returns false if
     * OpenGL could not be set up; the reason goes to [log].
     */
    fun start(sourceW: Int, sourceH: Int): Boolean {
        var ok = false
        onGlThread(wait = true) {
            runCatching {
                gl.start()
                this.sourceW = sourceW
                this.sourceH = sourceH
                val mapping = FrameFit.fillDome(RectPx(0, 0, sourceW, sourceH))
                window = CompositorMath.texWindow(mapping.source, sourceW, sourceH)
                viewport = CompositorMath.viewport(mapping.dest)
                // A picture arrived from the decoder: latch it at once (it must never wait for the dash's pace).
                inputSurface = gl.createInput(sourceW, sourceH, handler) {
                    if (gl.latch()) {
                        hasImage = true
                        newImage = true
                        sourceFrames.incrementAndGet()
                        // No dash yet (no output, no ticks): the phone's preview follows Android Auto's own pictures.
                        if (!gl.hasOutput && gl.hasPreview) drawPreview()
                        // The web viewers' encoder follows Android Auto's own pictures, never the dash's clock.
                        if (gl.hasWeb) drawWeb()
                    }
                }
                ok = true
                log("compositor ready: source ${sourceW}x$sourceH, no output yet")
            }.onFailure { log("${RideDiagnostics.ERROR}compositor init failed: $it") }
        }
        return ok
    }

    /**
     * Which part of the source goes where on the dash's frame. Refused (false, previous mapping kept) if
     * [FrameFit.problems] finds anything wrong with it for this source size.
     */
    fun setMapping(mapping: FrameFit.Mapping): Boolean {
        var ok = false
        onGlThread(wait = true) {
            val problems = FrameFit.problems(mapping, sourceW, sourceH)
            if (problems.isEmpty()) {
                window = CompositorMath.texWindow(mapping.source, sourceW, sourceH)
                viewport = CompositorMath.viewport(mapping.dest)
                ok = true
            } else {
                log("${RideDiagnostics.WARN}compositor mapping refused $problems: $mapping")
            }
        }
        return ok
    }

    /**
     * Android Auto's picture is now [width]×[height] (a new session at another resolution). The mapping in use is
     * kept until the caller gives one for the new size.
     */
    fun setSource(width: Int, height: Int) = onGlThread(wait = true) {
        if (width == sourceW && height == sourceH) return@onGlThread
        sourceW = width
        sourceH = height
        gl.resizeInput(width, height)
        log("compositor: source now ${width}x$height")
    }

    /** Changes the clock. Takes effect at the next tick. */
    fun setPace(pace: Pace) {
        onGlThread(wait = false) {
            this.pace = pace.copy(activeMs = pace.activeMs.coerceAtLeast(1), idleMs = pace.idleMs.coerceAtLeast(pace.activeMs))
        }
    }

    /**
     * Points the compositor at an encoder's input surface (526×300) and starts the clock; a frame goes out at
     * once. [onFrameSubmitted] is called with each frame's time right before the frame is posted (the encoder's
     * `frameSubmitted`). Returns only when the switch is done: from then on nothing more is drawn on a previous
     * output, so its encoder can be released. The video clock carries on across outputs.
     */
    fun setOutput(encoderSurface: Surface, onFrameSubmitted: (Long) -> Unit = {}) {
        onGlThread(wait = true) {
            runCatching {
                dropOutput()
                gl.attachOutput(encoderSurface)
                this.onFrameSubmitted = onFrameSubmitted
                postFailing = false
                // Due now: the first tick sends a frame without waiting for the idle interval.
                sinceLastFrameMs = pace.idleMs
                tickSpanMs = 0L
                nextTickAtMs = 0L
                handler.post(tick)
                log("compositor output set: draw ${viewport.w}x${viewport.h} at (${viewport.x},${viewport.y} from the bottom), pace $pace")
            }.onFailure { log("${RideDiagnostics.ERROR}compositor setOutput failed: $it") }
        }
    }

    /** Stops the clock and lets go of the output surface. Returns when nothing more will be drawn on it. */
    fun clearOutput() {
        onGlThread(wait = true) { runCatching { dropOutput() } }
    }

    /**
     * Also shows Android Auto's whole picture on the phone, on [surface] ([width]×[height]): the screen "Tela do
     * Android Auto". Drawn after each of the dash's frames is out, without waiting for
     * the phone's vsync; with no dash connected yet, at each new picture from Android Auto. A preview that fails is
     * switched off and the dash goes on.
     */
    fun setPreview(surface: Surface, width: Int, height: Int) = onGlThread(wait = false) {
        try {
            gl.attachPreview(surface)
            previewW = width
            previewH = height
            log("compositor: phone preview on, ${width}x$height")
            if (!gl.hasOutput && hasImage) drawPreview() // no dash ticking: show the picture already there
        } catch (e: IllegalStateException) {
            log("compositor: phone preview refused: ${e.message}")
        } catch (e: IllegalArgumentException) {
            // A surface released before it got here (the screen closed at once): EGL refuses it this way.
            log("compositor: phone preview refused: ${e.message}")
        }
    }

    fun clearPreview() = onGlThread(wait = false) {
        if (gl.hasPreview) log("compositor: phone preview off")
        gl.detachPreview()
    }

    /**
     * Also draws Android Auto's whole picture on [surface], the input of the web viewers' encoder, [width]×[height], at each
     * new picture. A surface that fails is switched off and the dash goes on. Returns when the switch is done.
     */
    fun setWebOutput(surface: Surface, width: Int, height: Int) = onGlThread(wait = true) {
        try {
            gl.attachWeb(surface)
            webW = width
            webH = height
            log("compositor: web output on, ${width}x$height")
            if (hasImage) drawWeb()
            handler.removeCallbacks(webIdle)
            handler.postDelayed(webIdle, WEB_IDLE_MS)
        } catch (e: IllegalStateException) {
            log("${RideDiagnostics.WARN}compositor: web output refused: ${e.message}")
        } catch (e: IllegalArgumentException) {
            log("${RideDiagnostics.WARN}compositor: web output refused: ${e.message}")
        }
    }

    /** Stops drawing on the web output; returns when nothing more will be drawn on it, so its encoder can be released. */
    fun clearWebOutput() = onGlThread(wait = true) {
        handler.removeCallbacks(webIdle)
        if (gl.hasWeb) log("compositor: web output off")
        gl.detachWeb()
    }

    /**
     * The stats text on the dash's video: on or off and where, taken at the next
     * frame. Off, nothing is drawn and the text's picture is let go.
     */
    fun setOverlay(overlay: StatsOverlay) = onGlThread(wait = false) {
        this.overlay = overlay
        rebuildOverlay()
    }

    /** What the text says now (the stream's measured rate and bitrate); a new picture of it only while it is on. */
    fun setOverlayText(text: StatsText) = onGlThread(wait = false) {
        if (text == overlayText) return@onGlThread
        overlayText = text
        if (overlay.on) rebuildOverlay()
    }

    /** Shows the "picture stuck" badge saying [text] on the dash's video from the next frame; null takes it off. */
    fun setStaleBadge(text: String?) = onGlThread(wait = false) {
        if (text == staleBadge) return@onGlThread
        staleBadge = text
        runCatching {
            if (text == null) {
                gl.setBadge(null)
            } else {
                val picture = StaleBadgeInk.render(text)
                gl.setBadge(picture)
                picture.recycle()
            }
        }.onFailure { log("compositor: the picture-stuck badge failed and is off: $it") }
    }

    private fun rebuildOverlay() {
        runCatching {
            if (!overlay.on) {
                gl.setOverlay(null)
            } else {
                val picture = StatsOverlayInk.render(overlayText, overlay.size)
                gl.setOverlay(picture)
                picture.recycle()
            }
        }.onFailure { log("compositor: the stats text failed and is off: $it") }
    }

    /** Totals since the previous call, then starts counting again. */
    fun takeStats(): Stats = Stats(sourceFrames.getAndSet(0), drawn.getAndSet(0), repeated.getAndSet(0), composeNs.getAndSet(0))

    fun release() {
        onGlThread(wait = true) {
            runCatching { dropOutput() }
            inputSurface = null
            gl.release()
        }
        thread.quitSafely()
    }

    /** Runs [block] on the GL thread; with [wait], returns when it has run (or the thread is gone). */
    private fun onGlThread(wait: Boolean, block: () -> Unit) {
        if (Thread.currentThread() === thread) {
            block()
            return
        }
        val done = CountDownLatch(1)
        val posted = handler.post {
            try {
                block()
            } finally {
                done.countDown()
            }
        }
        if (wait && posted) done.await(GL_WAIT_MS, TimeUnit.MILLISECONDS)
    }

    private fun dropOutput() {
        handler.removeCallbacks(tick)
        gl.detachOutput()
        onFrameSubmitted = {}
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!gl.hasOutput) return
            sinceLastFrameMs += tickSpanMs
            when (CompositorMath.decide(newImage, sinceLastFrameMs, pace.activeMs, pace.idleMs)) {
                TickAction.DRAW -> emitFrame(repeat = false)
                TickAction.REPEAT -> emitFrame(repeat = true)
                TickAction.WAIT -> Unit
            }
            // An absolute grid, so a slow tick does not push every later one; re-anchored if it fell a whole
            // interval behind, so there is never a burst to catch up.
            val now = SystemClock.uptimeMillis()
            tickSpanMs = pace.activeMs
            nextTickAtMs = if (nextTickAtMs == 0L || nextTickAtMs + tickSpanMs <= now) now + tickSpanMs else nextTickAtMs + tickSpanMs
            handler.postAtTime(this, nextTickAtMs)
        }
    }

    /** Draws the latched picture (or black) on the output and posts it with the next time of the video clock. */
    private fun emitFrame(repeat: Boolean) {
        val startNs = System.nanoTime()
        gl.draw(window.takeIf { hasImage }, viewport)
        if (overlay.on) gl.drawOverlay(overlay.x, overlay.y)
        if (staleBadge != null) gl.drawBadge()

        if (lastStampMs >= 0) ptsMs = DashLogic.nextVideoPts(ptsMs, sinceLastFrameMs, pace.clockSlowPercent)
        val stampMs = CompositorMath.stamp(lastStampMs, ptsMs)
        if (stampMs > ptsMs) ptsMs = stampMs.toDouble()
        onFrameSubmitted(stampMs)
        val posted = gl.post(stampMs)
        if (!posted && !postFailing) log("${RideDiagnostics.WARN}compositor: the output surface refused a frame (encoder gone?)")
        postFailing = !posted
        if (gl.hasPreview && hasImage) drawPreview()

        lastStampMs = stampMs
        sinceLastFrameMs = 0L
        newImage = false
        (if (repeat) repeated else drawn).incrementAndGet()
        composeNs.addAndGet(System.nanoTime() - startNs)
    }

    /**
     * While the web output is on and Android Auto draws nothing new, the last picture is drawn again every [WEB_IDLE_MS], so the
     * encoder keeps giving the browsers a few tiny frames a second (with none, a still map left them waiting for seconds).
     */
    private val webIdle = object : Runnable {
        override fun run() {
            if (!gl.hasWeb) return
            if (hasImage && System.nanoTime() - lastWebDrawNs >= WEB_IDLE_MS * NS_PER_MS) drawWeb()
            handler.postDelayed(this, WEB_IDLE_MS)
        }
    }

    /** The web viewers' frame; a failure switches it off, never the dash. */
    private fun drawWeb() {
        lastWebDrawNs = System.nanoTime()
        val shown = runCatching { gl.drawWeb(webW, webH, lastWebDrawNs) }
        if (shown.getOrDefault(false)) return
        runCatching { gl.detachWeb() }
        val why = shown.exceptionOrNull()?.message ?: "surface gone"
        log("${RideDiagnostics.WARN}compositor: the web output failed and was switched off; the dash goes on ($why)")
    }

    /** The phone's preview, after the dash's frame; a failure switches the preview off, never the dash. */
    private fun drawPreview() {
        val shown = runCatching { gl.drawPreview(previewW, previewH, CompositorMath.previewViewport(sourceW, sourceH, previewW, previewH)) }
        if (shown.getOrDefault(false)) return
        runCatching { gl.detachPreview() }
        val error = shown.exceptionOrNull()
        // No exception: the screen was left and its surface went away a frame before the screen's own clear arrived. Not a failure.
        if (error == null) return log("compositor: the phone preview surface is gone (screen left); the preview is off, the dash goes on")
        log("${RideDiagnostics.WARN}compositor: the phone preview failed and was switched off; the dash goes on (${error.message})")
    }

    private companion object {
        const val GL_WAIT_MS = 2_000L
        const val WEB_IDLE_MS = 100L
        const val NS_PER_MS = 1_000_000L
    }
}

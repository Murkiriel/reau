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
import com.reau.app.util.StageClock
import com.reau.app.util.StageStats
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
    data class Stats(
        val sourceFrames: Int,
        val drawn: Int,
        val repeated: Int,
        val composeNs: Long,
        /** How long each new picture drawn since the last take waited for the tick, in ms ([TickWait]). */
        val tickWaitsMs: List<Double> = emptyList(),
        /** Each frame's compose time (the same time [composeNs] sums), for its worst and 95th percentile. */
        val compose: StageStats = StageStats.EMPTY,
        /** The picture looks ([PictureLook]: the shrunk read-back and the classifier), apart from the compose. */
        val look: StageStats = StageStats.EMPTY,
        /** The latch of each new picture (`updateTexImage`), apart from the compose. */
        val latch: StageStats = StageStats.EMPTY,
        /** The pictures from Android Auto that woke the compositor (`onFrameAvailable`), drawn or not. */
        val wakeups: Int = 0,
    )

    private val thread = HandlerThread("dash-compositor").apply { start() }
    private val handler = Handler(thread.looper)
    private val gl = CompositorGl()

    /**
     * The least time between two frames drawn at once on a new picture while the rider's input is fresh
     * ([com.reau.app.stream.InputBurst]); 0 (the default): off, every picture waits for the tick. Set by the video loop.
     */
    @Volatile var burstGapMs = 0L

    /** Told what Android Auto's picture looks like ([PictureLook]), about every [com.reau.app.stream.InputBurst.lookEveryMs], on the compositor's thread; null: no look is taken. */
    @Volatile var onLook: ((PictureLook.Kind) -> Unit)? = null
    private var lastLookAtMs = 0L

    // The net's own answer of the last look, and the debug build's log of it (LookAudit): the class and p are not otherwise kept.
    private val lookResult = ScreenNet.Result()
    private val lookAudit = LookAudit()

    /** Whether the net's class and confidence go to the ride log (the debug build only; a test may set it). */
    @Volatile var auditLooks: Boolean = com.reau.app.BuildConfig.DEBUG

    /** Where the Android Auto decoder renders. Valid after [start]. */
    @Volatile var inputSurface: Surface? = null
        private set

    // Everything below is touched on the GL thread only.
    private var sourceW = 0
    private var sourceH = 0
    private var window = CompositorMath.TexWindow(0f, 0f, 1f, 1f)
    private var viewport = RectPx(0, 0, DashGlass.FRAME_W, DashGlass.FRAME_H)
    private var cover = emptyList<RectPx>()
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
    private var lastFrameAtMs = 0L
    private var burstPending = false
    private var ptsMs = 0.0
    private var lastStampMs = -1L
    private var onFrameSubmitted: (Long) -> Unit = {}
    private var postFailing = false

    // When the frame being drawn began, read by [busyMs] from any thread (written by the compositor's).
    private val frameBusy = FrameBusy(SystemClock::uptimeMillis)
    private var overlay = StatsOverlay()
    private var overlayText = StatsOverlay.latest
    private var buttonOverlay = ButtonOverlay()
    private var buttonText: ButtonText? = null

    /** The words of the "picture stuck" badge on the dash's video, or null for none (Android Auto's picture is alive). */
    private var staleBadge: String? = null

    /** What the info ring draws on the dash's video now, or null for no ring. */
    private var infoRing: com.reau.app.weather.RingFrame? = null

    private val sourceFrames = AtomicInteger()
    private val drawn = AtomicInteger()
    private val repeated = AtomicInteger()
    private val waitClock = TickWaitClock() // the compositor's own thread only
    private val tickWaits = TickWaitLog()
    private val composeNs = AtomicLong()
    private val wakeups = AtomicInteger()
    private val composeClock = StageClock()
    private val lookClock = StageClock()
    private val latchClock = StageClock()

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
                    wakeups.incrementAndGet()
                    val latchedAtNs = System.nanoTime()
                    val latched = gl.latch()
                    latchClock.add(System.nanoTime() - latchedAtNs)
                    if (latched) {
                        hasImage = true
                        newImage = true
                        waitClock.arrived(System.nanoTime())
                        takeLook()
                        com.reau.app.stream.InputLatency.pictureArrived()
                        sourceFrames.incrementAndGet()
                        if (gl.hasOutput && com.reau.app.stream.InputBurst.fresh(SystemClock.uptimeMillis())) drawSoon()
                        // No dash yet (no output, no ticks): the phone's preview follows Android Auto's own pictures.
                        if (!gl.hasOutput && gl.hasPreview) drawPreview()
                        // The web viewers' encoder follows Android Auto's own pictures, never the dash's clock.
                        if (gl.hasWeb) drawWeb()
                    }
                }
                ok = true
                handler.postDelayed(lookTick, CompositorMath.LOOK_POLL_MS)
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
                cover = CompositorMath.coverRects(mapping.cover)
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
            this.pace = CompositorRules.normalized(pace)
        }
    }

    /**
     * Points the compositor at an encoder's input surface (526×300) and starts the clock; a frame goes out at
     * once. [onFrameSubmitted] is called with each frame's time right before the frame is posted (the encoder's
     * `frameSubmitted`). Returns only when the switch is done: from then on nothing more is drawn on a previous
     * output, so its encoder can be released. The video clock carries on across outputs. A caller's trailing lambda
     * (`setOutput(surface) { pts -> … }`) is the frame callback; to release the encoder that was there, [swapOutputThen].
     */
    fun setOutput(encoderSurface: Surface, onFrameSubmitted: (Long) -> Unit = {}) =
        switchOutput(encoderSurface, onFrameSubmitted, after = {})

    /**
     * [setOutput] that also runs [after] on the GL thread right after the previous output is let go, so what it releases (the
     * encoder whose surface this was) is never released while a frame can still be drawn on it. Two functions of their own, with
     * no default for either callback: a trailing lambda written to release the old encoder used to compile, with the one-function
     * form, as the frame callback.
     */
    fun swapOutputThen(encoderSurface: Surface, onFrameSubmitted: (Long) -> Unit, after: () -> Unit) =
        switchOutput(encoderSurface, onFrameSubmitted, after)

    private fun switchOutput(encoderSurface: Surface, onFrameSubmitted: (Long) -> Unit, after: () -> Unit) {
        onGlThreadThen(after) {
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

    /**
     * Stops the clock and lets go of the output surface. Returns when nothing more will be drawn on it, or after
     * [GL_WAIT_MS] if the GL thread does not answer; [after] runs on the GL thread right after the output is let go, whenever
     * that is, so what it releases (the encoder whose surface this was) is never released while a frame can still be drawn on it.
     */
    fun clearOutput(after: () -> Unit = {}) {
        onGlThreadThen(after) { runCatching { dropOutput() } }
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

    /** The last-button text on the dash's video: on or off and where, taken at the next frame. Off, nothing is drawn and the picture is let go. */
    fun setButtonOverlay(overlay: ButtonOverlay) = onGlThread(wait = false) {
        buttonOverlay = overlay
        rebuildButton()
        redrawSoon()
    }

    /** The button the dash sent last ([ButtonOverlay.textFor]); it stays until the next one. Drawn again at once, as a press is rare and the video may be at the idle pace. */
    fun setButtonText(text: ButtonText?) = onGlThread(wait = false) {
        if (text == buttonText) return@onGlThread
        buttonText = text
        if (buttonOverlay.on) {
            rebuildButton()
            redrawSoon()
        }
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

    /** Lays the info ring in the place of the dash's manoeuvre bubble ([inPlace]) or on the right; drawn again at once. */
    fun setInfoRingPlace(inPlace: Boolean) = onGlThread(wait = false) {
        if (gl.ringInPlace == inPlace) return@onGlThread
        gl.ringInPlace = inPlace
        redrawSoon()
    }

    /** Shows the info ring ([frame]) on the right of the dash's video from the next frame; null takes it off. The same frame again does nothing. */
    fun setInfoRing(frame: com.reau.app.weather.RingFrame?) = onGlThread(wait = false) {
        if (frame == infoRing) return@onGlThread
        infoRing = frame
        runCatching {
            if (frame == null) {
                gl.setRing(null)
            } else {
                val picture = InfoRingInk.render(frame)
                gl.setRing(picture)
                picture.recycle()
            }
        }.onFailure {
            infoRing = null
            gl.setRing(null)
            log("compositor: the info ring failed and is off: $it")
        }
        redrawSoon()
    }

    private fun rebuildButton() {
        runCatching {
            val text = buttonText
            if (!buttonOverlay.on || text == null) {
                gl.setButton(null)
            } else {
                val picture = ButtonOverlayInk.render(text, buttonOverlay.size)
                gl.setButton(picture)
                picture.recycle()
            }
        }.onFailure { log("compositor: the button text failed and is off: $it") }
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
    fun takeStats(): Stats =
        Stats(
            sourceFrames.getAndSet(0), drawn.getAndSet(0), repeated.getAndSet(0), composeNs.getAndSet(0), tickWaits.take(),
            composeClock.take(), lookClock.take(), latchClock.take(), wakeups.getAndSet(0),
        )

    fun release() {
        onGlThread(wait = true) {
            handler.removeCallbacks(lookTick)
            runCatching { dropOutput() }
            inputSurface = null
            gl.release()
        }
        thread.quitSafely()
    }

    /**
     * Runs [block] on the GL thread and then [after], on the same thread, in that order, and waits for them up to [GL_WAIT_MS].
     * Giving up the wait does not give up the order: a GL thread stuck for longer than that (audit of 20/10/2026) used to let the
     * caller go on and release the encoder while the surface was still in use (EGL errors, a native crash). With the thread gone
     * (the looper quit) nothing can draw any more, and [after] runs on the caller.
     */
    private fun onGlThreadThen(after: () -> Unit, block: () -> Unit) {
        val done = CountDownLatch(1)
        val posted = handler.post {
            try {
                block()
            } finally {
                runCatching(after).onFailure { log("${RideDiagnostics.ERROR}compositor: the step after the output failed: $it") }
                done.countDown()
            }
        }
        if (!posted) {
            runCatching(after)
            return
        }
        if (!done.await(GL_WAIT_MS, TimeUnit.MILLISECONDS)) {
            log("${RideDiagnostics.WARN}compositor: the GL thread did not answer in $GL_WAIT_MS ms; what follows runs on it when it does")
        }
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
        handler.removeCallbacks(burstDraw)
        burstPending = false
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
            nextTickAtMs = CompositorRules.nextTickAt(nextTickAtMs, tickSpanMs, now)
            handler.postAtTime(this, nextTickAtMs)
        }
    }

    /**
     * A new picture arrived while the rider's input is fresh: drawn at once (at least the burst gap after the last frame), and the tick
     * grid re-anchored on it, so the video clock keeps the real time between frames and the next tick is a whole interval away.
     */
    private fun drawSoon() {
        val wait = CompositorMath.burstWaitMs(newImage, lastFrameAtMs, SystemClock.uptimeMillis(), burstGapMs) ?: return
        if (burstPending) return
        burstPending = true
        handler.postDelayed(burstDraw, wait)
    }

    /**
     * The info ring changed (it came or went): the picture drawn again at once, with no wait for the next tick, which at the idle pace is up
     * to a quarter of a second away: the dash's own maneuver ring changes the moment the dash gets its card, and this one rides on the video.
     */
    private fun redrawSoon() {
        if (!gl.hasOutput || burstPending) return
        burstPending = true
        handler.postDelayed(ringDraw, CompositorMath.redrawWaitMs(lastFrameAtMs, SystemClock.uptimeMillis()))
    }

    private val ringDraw = Runnable {
        burstPending = false
        if (!gl.hasOutput) return@Runnable
        val now = SystemClock.uptimeMillis()
        handler.removeCallbacks(tick)
        sinceLastFrameMs = now - lastFrameAtMs
        emitFrame(repeat = !newImage)
        tickSpanMs = pace.activeMs
        nextTickAtMs = now + tickSpanMs
        handler.postAtTime(tick, nextTickAtMs)
    }

    private val burstDraw = Runnable {
        burstPending = false
        if (!gl.hasOutput || !newImage) return@Runnable
        val now = SystemClock.uptimeMillis()
        handler.removeCallbacks(tick)
        sinceLastFrameMs = now - lastFrameAtMs
        emitFrame(repeat = false)
        tickSpanMs = pace.activeMs
        nextTickAtMs = now + tickSpanMs
        handler.postAtTime(tick, nextTickAtMs)
    }

    /**
     * The look on the clock too, not only on a new picture: a screen that stands still (the app menu) sends next to no pictures, and the
     * screen needs three looks of it in a row to be sure ([com.reau.app.aa.PictureGate]); with a look only per new picture the screen stayed on
     * the map for as long as the menu stood still (25/10/2026, the Lite edition). Same pace limit as the looks on a new picture.
     */
    private val lookTick = object : Runnable {
        override fun run() {
            takeLook()
            handler.postDelayed(this, CompositorMath.lookPollMs(onLook != null, hasImage))
        }
    }

    /** The picture just latched, shrunk and read, once every [com.reau.app.stream.InputBurst.lookEveryMs] while somebody listens ([onLook]). */
    private fun takeLook() {
        val listener = onLook ?: return
        if (!hasImage) return
        val now = SystemClock.uptimeMillis()
        if (!CompositorRules.lookDue(now, lastLookAtMs, com.reau.app.stream.InputBurst.lookEveryMs(com.reau.app.stream.InputBurst.lookQuick(now)))) return
        lastLookAtMs = now
        // What a look costs, counted apart from the compose: the read-back of the shrunk picture and the classifier, not the listener.
        val startNs = System.nanoTime()
        val pixels = runCatching { gl.look() }.getOrNull()
        val kind = pixels?.let { PictureLook.of(it, PictureLook.SAMPLE_W, PictureLook.SAMPLE_H, lookResult) }
        lookClock.add(System.nanoTime() - startNs)
        // Debug build only: the net's class and p when the picture changes or the net is not sure, to audit what the offline replica cannot reproduce.
        if (kind != null && auditLooks) lookAudit.line(kind, lookResult.confidence, now)?.let(log)
        listener(kind ?: return)
    }

    /**
     * How long the compositor has been inside the frame it is drawing now, in ms; 0 when it is between frames. A thread stuck in
     * `eglSwapBuffers` (the encoder gives back no buffer) shows here, and the decoder that draws into this compositor, which then
     * gives nothing out, is not itself at fault ([com.reau.app.aa.DecoderStallWatch.restartDue]). Readable from any thread.
     */
    fun busyMs(): Long = frameBusy.busyMs()

    /** Draws the latched picture (or black) on the output and posts it with the next time of the video clock. */
    private fun emitFrame(repeat: Boolean) {
        frameBusy.track { drawAndPost(repeat) }
    }

    private fun drawAndPost(repeat: Boolean) {
        val startNs = System.nanoTime()
        gl.draw(window.takeIf { hasImage }, viewport)
        gl.fillBlack(cover)
        if (overlay.on) gl.drawOverlay(overlay.x, overlay.y)
        if (buttonOverlay.on) gl.drawButton(buttonOverlay.x, buttonOverlay.y)
        if (infoRing != null) gl.drawRing()
        if (staleBadge != null) gl.drawBadge()

        val clock = CompositorRules.advance(lastStampMs, ptsMs, sinceLastFrameMs, pace.clockSlowPercent)
        ptsMs = clock.ptsMs
        val stampMs = clock.stampMs
        onFrameSubmitted(stampMs)
        val posted = gl.post(stampMs)
        if (CompositorRules.refusalToTell(posted, postFailing)) log("${RideDiagnostics.WARN}compositor: the output surface refused a frame (encoder gone?)")
        postFailing = !posted
        if (gl.hasPreview && hasImage) drawPreview()

        lastStampMs = stampMs
        lastFrameAtMs = SystemClock.uptimeMillis()
        sinceLastFrameMs = 0L
        newImage = false
        if (!repeat) {
            waitClock.drawn(System.nanoTime())?.let { tickWaits.add(it) }
            com.reau.app.stream.InputLatency.pictureDrawn()
        }
        (if (repeat) repeated else drawn).incrementAndGet()
        val composedNs = System.nanoTime() - startNs
        composeNs.addAndGet(composedNs)
        composeClock.add(composedNs)
    }

    /**
     * While the web output is on and Android Auto draws nothing new, the last picture is drawn again every [WEB_IDLE_MS], so the
     * encoder keeps giving the browsers a few tiny frames a second (with none, a still map left them waiting for seconds).
     */
    private val webIdle = object : Runnable {
        override fun run() {
            if (!gl.hasWeb) return
            if (CompositorRules.webDue(hasImage, System.nanoTime(), lastWebDrawNs, WEB_IDLE_MS * NS_PER_MS)) drawWeb()
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

/** What the compositor decides about its clock and its frames, kept away from OpenGL and the handler so a plain JVM test can run it. */
internal object CompositorRules {
    /** How long a frame begun at [startedAtMs] has been running at [nowMs]: 0 when none is (0 is "between frames") or the clock went back. */
    fun busyMs(startedAtMs: Long, nowMs: Long): Long = if (startedAtMs == 0L) 0L else (nowMs - startedAtMs).coerceAtLeast(0L)

    /** A pace as the clock can use it: a tick of at least a millisecond, and a repeat never sooner than the tick. */
    fun normalized(pace: DashCompositor.Pace): DashCompositor.Pace =
        pace.copy(activeMs = pace.activeMs.coerceAtLeast(1), idleMs = pace.idleMs.coerceAtLeast(pace.activeMs))

    /**
     * The next tick on an absolute grid, so a slow tick does not push every later one; re-anchored on [nowMs] when the grid was never set
     * ([nextTickAtMs] 0) or fell a whole interval behind, so there is never a burst to catch up.
     */
    fun nextTickAt(nextTickAtMs: Long, tickSpanMs: Long, nowMs: Long): Long =
        if (nextTickAtMs == 0L || nextTickAtMs + tickSpanMs <= nowMs) nowMs + tickSpanMs else nextTickAtMs + tickSpanMs

    /** The time of a frame ([stampMs], strictly after the last) and where the video clock stands after it ([ptsMs]). */
    class VideoClock(val ptsMs: Double, val stampMs: Long)

    /**
     * The video clock for the frame about to go out: it moves on by [sinceLastFrameMs] (less [clockSlowPercent]) from the second frame
     * on; the frame's stamp is whole milliseconds and always after the last one ([lastStampMs], negative before the first), and the
     * clock never stays behind the stamp.
     */
    fun advance(lastStampMs: Long, ptsMs: Double, sinceLastFrameMs: Long, clockSlowPercent: Int): VideoClock {
        var pts = ptsMs
        if (lastStampMs >= 0) pts = DashLogic.nextVideoPts(pts, sinceLastFrameMs, clockSlowPercent)
        val stamp = CompositorMath.stamp(lastStampMs, pts)
        if (stamp > pts) pts = stamp.toDouble()
        return VideoClock(pts, stamp)
    }

    /** A refused frame is told once: only the first of a run of refusals goes to the log. */
    fun refusalToTell(posted: Boolean, alreadyFailing: Boolean): Boolean = !posted && !alreadyFailing

    /** While the web output is on and nothing new comes, the last picture is drawn again every [everyNs]. */
    fun webDue(hasImage: Boolean, nowNs: Long, lastDrawNs: Long, everyNs: Long): Boolean = hasImage && nowNs - lastDrawNs >= everyNs

    /** The look at the picture is taken at most once every [everyMs]. */
    fun lookDue(nowMs: Long, lastLookMs: Long, everyMs: Long): Boolean = nowMs - lastLookMs >= everyMs
}

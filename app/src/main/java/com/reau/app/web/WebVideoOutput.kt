package com.reau.app.web

import com.reau.app.video.DashCompositor

/**
 * The web viewers' video, for [WebStream]: a [WebEncoder] fed by the compositor's web output. The encoder and the
 * output exist only between [start] and [stop], so with no browser watching nothing runs. What the encoder gives goes to
 * [onOutput] (the stream's cache and broadcast). Only compiled by the build; it is proved on a device.
 */
internal class WebVideoOutput(
    private val compositor: DashCompositor,
    private val onOutput: (ByteArray, Boolean, Boolean) -> Unit,
    private val log: (String) -> Unit,
) : WebVideoSource {
    private var encoder: WebEncoder? = null

    override fun start(): Boolean {
        val e = WebEncoder(onOutput)
        val surface = if (e.prepare()) e.inputSurface else null
        if (surface == null) {
            log("web: encoder did not start (${e.lastError ?: "no input surface"})")
            e.release()
            return false
        }
        compositor.setWebOutput(surface, WebEncoder.WIDTH, WebEncoder.HEIGHT)
        encoder = e
        log("web: encoder on, ${WebEncoder.WIDTH}x${WebEncoder.HEIGHT}")
        return true
    }

    override fun stop() {
        compositor.clearWebOutput()
        encoder?.release()
        encoder = null
        log("web: encoder off")
    }

    override fun requestKeyFrame() {
        encoder?.requestSyncFrame()
    }
}

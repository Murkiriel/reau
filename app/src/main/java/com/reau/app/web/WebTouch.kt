package com.reau.app.web

import com.reau.app.aa.AaVideo
import com.reau.app.aa.PhoneTouch
import com.reau.app.video.RectPx

/**
 * The touch the page sends: `{"a":"d","x":10,"y":20}`, the action as one letter (d down, m move, u up, c cancel)
 * and the point in the video's own pixels. The format is fixed, so it is read with one pattern instead of a JSON library
 * (org.json is not there on the JVM tests). Anything else, or a point outside the video, is null.
 */
object WebTouch {
    private val FORMAT = Regex("""^\s*\{\s*"a"\s*:\s*"([dmuc])"\s*,\s*"x"\s*:\s*(-?\d{1,6})\s*,\s*"y"\s*:\s*(-?\d{1,6})\s*\}\s*$""")

    private val ACTIONS = mapOf(
        "d" to PhoneTouch.Action.DOWN,
        "m" to PhoneTouch.Action.MOVE,
        "u" to PhoneTouch.Action.UP,
        "c" to PhoneTouch.Action.CANCEL,
    )

    fun parse(text: String, width: Int = AaVideo.WIDTH, height: Int = AaVideo.HEIGHT): PhoneTouch.Sent? {
        val (letter, x, y) = FORMAT.matchEntire(text)?.destructured ?: return null
        val px = x.toIntOrNull()?.takeIf { it in 0 until width } ?: return null
        val py = y.toIntOrNull()?.takeIf { it in 0 until height } ?: return null
        return ACTIONS[letter]?.let { PhoneTouch.Sent(it, px, py) }
    }
}

/**
 * The strokes of the browsers on their way to Android Auto: one at a time, the points of the browser's picture taken into Android Auto's
 * video (a stroke that did not start on it is dropped whole), under one lock since two viewers can send at once. [cancel] ends a stroke
 * left down by a browser that went away.
 */
internal class WebStrokes(private val send: (PhoneTouch.Sent) -> Unit) {
    private val stroke = PhoneTouch.Stroke()

    /** One text message of a browser: a touch, or nothing at all if it is not one. */
    @Synchronized
    fun take(text: String) {
        val sent = WebTouch.parse(text) ?: return
        val inVideo = RectPx(0, 0, AaVideo.WIDTH, AaVideo.HEIGHT)
        stroke.next(PhoneTouch(sent.action, sent.x.toFloat(), sent.y.toFloat()), inVideo, AaVideo.WIDTH, AaVideo.HEIGHT)?.let(send)
    }

    /** A finger left down by a browser that went, or by a switch turned off mid-stroke: Android Auto is told it was cancelled. */
    @Synchronized
    fun cancel() {
        stroke.cancel()?.let(send)
    }
}

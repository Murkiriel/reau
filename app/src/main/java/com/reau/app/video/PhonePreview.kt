package com.reau.app.video

/**
 * The link between the screen that shows Android Auto on the phone and the running compositor (a shortcut to
 * Android Auto's screen running in the background). The screen says what it shows
 * ([show], [hide]); the compositor, while the projection runs, listens ([attach], [detach]) and gets the current
 * surface at once, so a screen opened before the projection starts still gets the picture. The surface is `Any` so
 * the link is testable on the JVM; the compositor's side knows it is an android.view.Surface.
 */
object PhonePreview {
    data class Shown(val surface: Any, val width: Int, val height: Int)

    private var shown: Shown? = null
    private var target: ((Shown?) -> Unit)? = null
    private var touchTarget: ((com.reau.app.aa.PhoneTouch, Shown?) -> Unit)? = null

    internal val attachedForTest: ((Shown?) -> Unit)? get() = target

    @Synchronized
    fun show(surface: Any, width: Int, height: Int) {
        shown = Shown(surface, width, height)
        target?.invoke(shown)
    }

    @Synchronized
    fun hide() {
        shown = null
        target?.invoke(null)
    }

    @Synchronized
    fun attach(target: (Shown?) -> Unit) {
        this.target = target
        target(shown)
    }

    /** A finger on the screen, with the screen's current size; dropped when the projection does not listen. */
    @Synchronized
    fun touch(touch: com.reau.app.aa.PhoneTouch) {
        touchTarget?.invoke(touch, shown)
    }

    /** The projection listens for fingers (only with the phone touch switched on). */
    @Synchronized
    fun attachTouch(target: (com.reau.app.aa.PhoneTouch, Shown?) -> Unit) {
        touchTarget = target
    }

    @Synchronized
    fun detachTouch(target: (com.reau.app.aa.PhoneTouch, Shown?) -> Unit) {
        if (touchTarget === target) touchTarget = null
    }

    @Synchronized
    fun detach(target: (Shown?) -> Unit) {
        if (this.target === target) this.target = null
    }
}

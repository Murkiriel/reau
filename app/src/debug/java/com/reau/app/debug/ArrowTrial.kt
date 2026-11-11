package com.reau.app.debug

import com.reau.app.dash.nav.DashManeuverGlyphs
import com.reau.app.data.RideDiagnostics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Debug builds only: the arrow test. The field roteiro has the rider photograph the dash, parked, with each arrow of [DashManeuverGlyphs.testSequence]
 * (the debug card that was meant for it is on no screen); this sends them. With the dash connected, [next] and [previous] step through the sequence and
 * the dash's session sends that byte in the maneuver bubble (05 02), by the path it sends the real arrow ([com.reau.app.dash.DashSession.navPacketNow]
 * and the route card), with or without guidance; [stop] gives the real arrow back. [label] is what the screen writes for the photo: the byte in hex and
 * its expected name. The release build has an [ArrowTrial] that answers none (`src/release`), so nothing in a release can change the arrow.
 */
object ArrowTrial {
    private val current = MutableStateFlow<Int?>(null)

    /** The byte being sent now, or null when the test is off; what the screen shows. */
    val now: StateFlow<Int?> = current

    /** The byte for the dash's session to send in place of the real arrow; null: the real one. */
    fun byte(): Int? = current.value

    /** The first byte of the sequence when the test is off, else the one after the byte now (the last wraps to the first). */
    fun next() = go(DashManeuverGlyphs.nextCandidate(current.value ?: -1))

    /** The last byte of the sequence when the test is off, else the one before the byte now (the first wraps to the last). */
    fun previous() = go(DashManeuverGlyphs.previousCandidate(current.value ?: Int.MAX_VALUE))

    /** Ends the test: the dash gets the real arrow again at its next second. */
    fun stop() {
        if (current.value == null) return
        current.value = null
        RideDiagnostics.log("debug", "arrow test ended: the real arrow goes back (debug build only)")
    }

    private fun go(code: Int) {
        current.value = code
        RideDiagnostics.log("debug", "arrow test: ${label(code)} (debug build only)")
    }

    /** What the screen and the log say of [code]: `0x2D  Bear off to the right  (Abrir à direita)`; a byte with no name says it has none yet. */
    fun label(code: Int): String {
        val en = DashManeuverGlyphs.describe(code)
        val pt = DashManeuverGlyphs.describePt(code)
        val name = when {
            en == null && code >= DashManeuverGlyphs.HIDDEN_BUBBLE -> "hides the bubble"
            en == null -> "no name yet: write down what the panel draws"
            else -> en
        }
        return "0x%02X  %s%s".format(code, name, if (pt != null) "  ($pt)" else "")
    }
}

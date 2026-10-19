package com.reau.app.web

import com.reau.app.settings.REauSettings
import com.reau.app.settings.SettingsStore

/**
 * The browser switch is never left on behind the rider's back: when the projection ends, "Ver no navegador"
 * goes off by itself, the server is offline, and it only comes back when the rider goes to the screen and turns it on again.
 * The touch switch is the rider's and stays as it is (it is only offered, and only used, while the other is on).
 */
object WebSwitch {
    fun projectionEnded(settings: REauSettings): REauSettings = if (settings.webView) settings.copy(webView = false) else settings

    /**
     * Every run starts with the browser switch off (it goes on only when the rider turns it on on the screen): a
     * switch left on by a run that ended with no release (the process died, the Wi-Fi dropped) is
     * turned off here. Said in the log once.
     */
    fun atStart(store: SettingsStore, log: (String) -> Unit) {
        if (switchOffStored(store)) log("the browser switch was left on by the last run: it is off for this one")
    }

    /** Whether the end of the run stopped by [stopCommand] may turn the switch off: not when a newer start ([lastStart]) has replaced it. */
    fun resetsAtStop(stopCommand: Int, lastStart: Int): Boolean = lastStart <= stopCommand

    /** Writes [projectionEnded] to the stored settings; false when the switch was already off and nothing was written. */
    fun switchOffStored(store: SettingsStore): Boolean {
        val now = store.load()
        val after = projectionEnded(now)
        if (after == now) return false
        store.save(after)
        return true
    }
}

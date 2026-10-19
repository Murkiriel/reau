package com.reau.app.web

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * What the screen shows of the web server: where it listens, the PIN of this run and how many
 * browsers are in. Only here, in memory: the PIN never goes to a log, a diagnostic or the disk. Null while the server is off.
 */
object WebAccess {
    data class Access(val port: Int, val pin: String, val viewers: Int)

    private val current = MutableStateFlow<Access?>(null)
    @Volatile private var kicker: (() -> Unit)? = null

    val state: StateFlow<Access?> get() = current

    internal fun publish(access: Access, kick: () -> Unit) {
        kicker = kick
        current.value = access
    }

    /** Too many wrong PINs changed it: the screen shows the new one. */
    internal fun pin(pin: String) {
        current.update { it?.copy(pin = pin) }
    }

    internal fun viewers(count: Int) {
        current.update { it?.copy(viewers = count) }
    }

    /** The screen's "Derrubar todos": drops every browser; the server goes on. */
    fun kickAll() {
        kicker?.invoke()
    }

    internal fun clear() {
        kicker = null
        current.value = null
    }
}

package com.reau.app.util

import java.util.concurrent.ConcurrentHashMap

/**
 * The codec chosen for a format, looked up in MediaCodecList once per process instead of at every session
 * and every rebuild (the list does not change while the app lives). The key says what was asked (encoder or decoder,
 * and the format); "none found" is kept too.
 */
object CodecNames {
    private val chosen = ConcurrentHashMap<String, Choice>()

    private class Choice(val name: String?)

    /** The codec [search] found for [key] the first time it was asked. */
    fun pick(key: String, search: () -> String?): String? = chosen.getOrPut(key) { Choice(search()) }.name

    /** Test-only: nothing chosen yet. */
    internal fun testOnlyForget() = chosen.clear()
}

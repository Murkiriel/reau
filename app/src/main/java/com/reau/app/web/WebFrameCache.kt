package com.reau.app.web

/**
 * What a viewer who comes in the middle of the stream must receive first: the parameter sets (SPS/PPS), the
 * last key frame and every frame after it, so its decoder starts at once without asking the encoder to wait for the
 * next key frame. A new key frame starts a new group and the old frames go. A group that grows past [maxFrames] without
 * a key frame is dropped whole (half a group would not decode), and the joiner then gets only the parameters until the
 * next key frame. Used from one lock: the caller serialises it.
 */
class WebFrameCache(private val maxFrames: Int = DEFAULT_MAX_FRAMES) {
    private var config: ByteArray? = null
    private val group = ArrayList<ByteArray>()
    private var hasKey = false

    fun setConfig(bytes: ByteArray) {
        config = bytes
    }

    fun addFrame(bytes: ByteArray, key: Boolean) {
        if (key) {
            group.clear()
            hasKey = true
        }
        if (!hasKey) return
        group += bytes
        if (group.size > maxFrames) {
            group.clear()
            hasKey = false
        }
    }

    /** The messages to send a new viewer, in order: the parameters, then the key frame and what came after it. */
    fun snapshot(): List<ByteArray> = listOfNotNull(config) + group

    fun clear() {
        config = null
        group.clear()
        hasKey = false
    }

    private companion object {
        const val DEFAULT_MAX_FRAMES = 150
    }
}

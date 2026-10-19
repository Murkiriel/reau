package com.reau.app.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The codec chosen for a format is looked up in MediaCodecList once per process, not at every session and every
 * rebuild. The search runs once per key, its answer (even "none") is kept, and another key searches on its own.
 */
class CodecNamesTest {
    private var searches = 0

    @After
    fun tearDown() = CodecNames.testOnlyForget()

    private fun search(answer: String?): () -> String? = {
        searches++
        answer
    }

    @Test
    fun `the same key searches once`() {
        assertEquals("c2.vendor.avc.encoder", CodecNames.pick("encoder {mime=video/avc}", search("c2.vendor.avc.encoder")))
        assertEquals("c2.vendor.avc.encoder", CodecNames.pick("encoder {mime=video/avc}", search("something else")))
        assertEquals("one search", 1, searches)
    }

    @Test
    fun `no codec found is kept too`() {
        assertNull(CodecNames.pick("decoder video/hevc", search(null)))
        assertNull(CodecNames.pick("decoder video/hevc", search("c2.late.hevc.decoder")))
        assertEquals(1, searches)
    }

    @Test
    fun `another key searches on its own`() {
        CodecNames.pick("decoder video/avc hardware", search("c2.hw.avc.decoder"))
        assertEquals("c2.sw.avc.decoder", CodecNames.pick("decoder video/avc software", search("c2.sw.avc.decoder")))
        assertEquals(2, searches)
    }
}

package com.reau.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the ride log keeps of a codec that failed: the error code, whether it recovers, whether it is transient and the driver's own words. */
class CodecErrorsTest {
    @Test
    fun `a codec exception gives all four, with the codec's name`() {
        val detail = CodecErrors.Detail(errorCode = 1100, recoverable = false, transient = true, diagnosticInfo = "android.media.MediaCodec.error_neg_38")
        assertEquals(
            "CodecException: Error 0x44c [codec=c2.qti.avc.encoder errorCode=1100(insufficient resource) recoverable=false transient=true " +
                "diagnostic=android.media.MediaCodec.error_neg_38]",
            CodecErrors.describe("CodecException", "Error 0x44c", detail, "c2.qti.avc.encoder"),
        )
    }

    @Test
    fun `the reclaimed code is named, a driver's own code is only a number`() {
        assertEquals(
            "CodecException [errorCode=1101(reclaimed) recoverable=true transient=false diagnostic=-]",
            CodecErrors.describe("CodecException", null, CodecErrors.Detail(1101, recoverable = true, transient = false, diagnosticInfo = null), null),
        )
        assertEquals(
            "CodecException: x [errorCode=-38 recoverable=false transient=false diagnostic=-]",
            CodecErrors.describe("CodecException", "x", CodecErrors.Detail(-38, recoverable = false, transient = false, diagnosticInfo = "  "), null),
        )
    }

    @Test
    fun `a long diagnostic is cut`() {
        val text = CodecErrors.describe("CodecException", "x", CodecErrors.Detail(0, true, false, "d".repeat(500)), null)
        assertEquals("d".repeat(160) + "]", text.substringAfter("diagnostic="))
    }

    @Test
    fun `a plain state error has only its message, and the codec's name when it is known`() {
        assertEquals("IllegalStateException: boom", CodecErrors.describe(IllegalStateException("boom")))
        assertEquals("IllegalStateException: boom [codec=OMX.x]", CodecErrors.describe(IllegalStateException("boom"), "OMX.x"))
        assertEquals("IllegalStateException", CodecErrors.describe(IllegalStateException()))
        assertNull(CodecErrors.detailOf(IllegalStateException("outer", IllegalArgumentException("inner"))))
        assertNull(CodecErrors.detailOf(null))
    }
}

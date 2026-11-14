package com.reau.app.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The debug build's log of the picture classifier (item 15a (iii), 08/11/2026): the class when it changes, the p when the net is not sure, never a flood. */
class LookAuditTest {
    @Test
    fun `the first look is written, with nothing before it`() {
        assertEquals("picture look: MAP p=0.995 (was nothing)", LookAudit().line(PictureLook.Kind.MAP, 0.995f, 0))
    }

    @Test
    fun `a sure look of the same class says nothing`() {
        val audit = LookAudit()
        audit.line(PictureLook.Kind.MAP, 0.999f, 0)
        assertNull(audit.line(PictureLook.Kind.MAP, 0.99f, 250))
        assertNull(audit.line(PictureLook.Kind.MAP, 0.98f, 500))
    }

    @Test
    fun `a change of class is written at once, with the class it was and the p`() {
        val audit = LookAudit()
        audit.line(PictureLook.Kind.MAP, 0.999f, 0)
        assertEquals("picture look: MENU p=0.981 (was MAP)", audit.line(PictureLook.Kind.MENU, 0.981f, 250))
        assertEquals("picture look: UNSURE p=0.640 (was MENU, p under 0.98)", audit.line(PictureLook.Kind.UNSURE, 0.64f, 500))
    }

    @Test
    fun `a look below 0 98 is written even when the class is the same, once a second at most, with the count of the ones left out`() {
        val audit = LookAudit()
        audit.line(PictureLook.Kind.MAP, 0.999f, 0)
        assertEquals("picture look: MAP p=0.940 (p under 0.98)", audit.line(PictureLook.Kind.MAP, 0.94f, 1_000))
        assertNull(audit.line(PictureLook.Kind.MAP, 0.93f, 1_250))
        assertNull(audit.line(PictureLook.Kind.MAP, 0.95f, 1_750))
        assertEquals("picture look: MAP p=0.920 (p under 0.98; 2 looks not written)", audit.line(PictureLook.Kind.MAP, 0.92f, 2_000))
    }

    @Test
    fun `a black picture gated by the net says its class once, not at every look`() {
        val audit = LookAudit()
        audit.line(PictureLook.Kind.MAP, 0.999f, 0)
        assertEquals("picture look: UNSURE p=0.000 (was MAP)", audit.line(PictureLook.Kind.UNSURE, 0f, 250))
        assertNull(audit.line(PictureLook.Kind.UNSURE, 0f, 500))
        assertNull(audit.line(PictureLook.Kind.UNSURE, 0f, 5_000))
    }

    @Test
    fun `the lines are limited and the last one says so`() {
        val audit = LookAudit(maxLines = 2)
        assertTrue(audit.line(PictureLook.Kind.MAP, 0.999f, 0)!!.startsWith("picture look: MAP"))
        assertTrue(audit.line(PictureLook.Kind.MENU, 0.999f, 250)!!.startsWith("picture look: MENU"))
        assertEquals("picture look: 2 lines written, no more in this session", audit.line(PictureLook.Kind.MAP, 0.999f, 500))
        assertNull(audit.line(PictureLook.Kind.MENU, 0.999f, 750))
        assertNull(audit.line(PictureLook.Kind.MAP, 0.5f, 5_000))
    }

    @Test
    fun `the limit is 0 98, the study's candidate for the net, not the abstention limit of 0 9`() {
        assertEquals(0.98f, LookAudit.LOW_P, 0f)
        assertTrue(LookAudit.LOW_P > ScreenNet.MIN_CONFIDENCE)
    }

    @Test
    fun `PictureLook hands the net's own answer back, class and confidence`() {
        val into = ScreenNet.Result()
        val kind = PictureLook.of(ByteArray(PictureLook.SAMPLE_W * PictureLook.SAMPLE_H * 4), PictureLook.SAMPLE_W, PictureLook.SAMPLE_H, into)
        assertEquals("a black picture is gated", PictureLook.Kind.UNSURE, kind)
        assertEquals(0f, into.confidence, 0f)
        assertEquals(PictureLook.Kind.UNSURE, PictureLook.of(ByteArray(3), 2, 2, into))
    }
}

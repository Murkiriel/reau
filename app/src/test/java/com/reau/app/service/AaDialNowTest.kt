package com.reau.app.service

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The projection turned off and on again in under 2 s. Each projection has its number ([AaDialNow.started], kept by LiveSides); the old
 * one's end, arriving after the new one's start, must leave the new one's dial alone.
 */
class AaDialNowTest {
    @After
    fun tearDown() {
        AaDialNow.stopped(Int.MAX_VALUE)
        AaDialNow.started {}.also { AaDialNow.stopped(it) }
    }

    @Test
    fun `the old projection's end leaves the new one's dial`() {
        var dialled = ""
        val old = AaDialNow.started { dialled = "old" }
        val new = AaDialNow.started { dialled = "new" }
        assertNotEquals(old, new)
        AaDialNow.stopped(old)
        AaDialNow.now()
        assertEquals("the new projection still dials soon", "new", dialled)
    }

    @Test
    fun `the new projection's own end clears its dial`() {
        val old = AaDialNow.started {}
        val new = AaDialNow.started {}
        AaDialNow.stopped(old)
        AaDialNow.stopped(new)
        assertNull(AaDialNow.dialSoon)
    }

    @Test
    fun `now dials only while a projection runs`() {
        var calls = 0
        val number = AaDialNow.started { calls++ }
        AaDialNow.now()
        assertEquals(1, calls)
        AaDialNow.stopped(number)
        AaDialNow.now()
        assertEquals("no projection, no dial", 1, calls)
    }
}

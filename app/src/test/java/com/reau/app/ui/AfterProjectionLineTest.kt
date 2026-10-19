package com.reau.app.ui

import com.reau.app.aa.ServerResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Servidor do Android Auto desligado." under the start screen's wallpaper link is not wanted once the projection is
 * off. A server that was switched off as it should be says nothing; the one left on (the phone was locked, so the walk
 * could not switch it off) still tells the rider.
 */
class AfterProjectionLineTest {
    @Test
    fun `a server switched off as it should be says nothing once the projection is off`() {
        assertFalse(saidAfterProjection(ServerResult.STOPPED))
    }

    @Test
    fun `a server left on is still told`() {
        assertTrue(saidAfterProjection(ServerResult.LEFT_ON))
    }

    @Test
    fun `no other result is told after the projection`() {
        assertEquals(setOf(ServerResult.LEFT_ON), ServerResult.entries.filter { saidAfterProjection(it) }.toSet())
    }
}

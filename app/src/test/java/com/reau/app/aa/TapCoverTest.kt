package com.reau.app.aa

import com.reau.app.aa.ServerMenuRun.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The cover at the rider's tap, only when its walk is sure to come. */
class TapCoverTest {
    private fun mode(on: Boolean, switchOn: Boolean = true, last: ServerResult = ServerResult.NONE, seenUp: Boolean = false, stopKept: Boolean = false) =
        TapCover.mode(on, switchOn, last, seenUp, stopKept)

    @Test
    fun `Desligar with the server seen up puts the stop cover up at once`() {
        assertEquals(Mode.STOP, mode(on = false, seenUp = true))
    }

    @Test
    fun `Desligar with a server never seen up, or with the notification's Stop kept, has no walk to cover`() {
        assertNull(mode(on = false, seenUp = false))
        assertNull(mode(on = false, seenUp = true, stopKept = true))
    }

    @Test
    fun `Ligar after REau switched the server off puts the start cover up at once`() {
        assertEquals(Mode.START, mode(on = true, last = ServerResult.STOPPED))
    }

    @Test
    fun `Ligar with the server's state unknown or left on waits for the first dial`() {
        for (last in ServerResult.entries - ServerResult.STOPPED) assertNull("$last", mode(on = true, last = last))
    }

    @Test
    fun `Ligar right after a Desligar finds the server still on`() {
        assertNull(mode(on = true, last = ServerResult.STOPPED, seenUp = true))
    }

    @Test
    fun `without the automatic switch nothing is covered`() {
        assertNull(mode(on = false, switchOn = false, seenUp = true))
        assertNull(mode(on = true, switchOn = false, last = ServerResult.STOPPED))
    }
}

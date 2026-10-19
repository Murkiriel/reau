package com.reau.app.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What reaches the dash when a stream to it starts or restarts: nothing until a keyframe. A dash that is handed
 * a partial frame first shows black with video flowing (OpenCfMoto paid for that one).
 */
class KeyframeGateTest {
    private val config = bytes(0, 0, 0, 1, 0x67, 0x42, 0, 0x29, 0, 0, 0, 1, 0x68, 0x01)
    private val pFrame = bytes(0, 0, 0, 1, 0x41, 0x11, 0x22)
    private val idr = bytes(0, 0, 0, 1, 0x65, 0x11, 0x22)

    @Test
    fun `a new gate lets no frame through until a keyframe comes`() {
        val gate = KeyframeGate()
        assertFalse(gate.admit(pFrame, isKey = false))
        assertFalse(gate.admit(pFrame, isKey = false))
        assertTrue(gate.admit(idr, isKey = true))
        assertTrue(gate.admit(pFrame, isKey = false))
        assertEquals(2, gate.dropped)
    }

    @Test
    fun `the parameter sets always pass, or the keyframe after them could not be decoded`() {
        val gate = KeyframeGate()
        assertTrue(gate.admit(config, isKey = false))
        assertFalse("the gate is still closed for frames", gate.admit(pFrame, isKey = false))
        assertEquals(1, gate.dropped)
    }

    @Test
    fun `closing the gate again drops frames until the next keyframe`() {
        val gate = KeyframeGate()
        gate.admit(idr, isKey = true)
        gate.close()
        assertFalse(gate.admit(pFrame, isKey = false))
        assertTrue(gate.admit(config, isKey = false))
        assertTrue(gate.admit(idr, isKey = true))
        assertTrue(gate.admit(pFrame, isKey = false))
        assertEquals(1, gate.dropped)
    }

    @Test
    fun `a keyframe that carries its own parameter sets opens the gate`() {
        val gate = KeyframeGate()
        assertTrue(gate.admit(config + idr, isKey = true))
        assertTrue(gate.admit(pFrame, isKey = false))
    }

    @Test
    fun `three-byte start codes are read as well`() {
        val gate = KeyframeGate()
        assertTrue("parameter sets", gate.admit(bytes(0, 0, 1, 0x68, 0x01), isKey = false))
        assertFalse("a frame", gate.admit(bytes(0, 0, 1, 0x41, 0x11), isKey = false))
    }

    @Test
    fun `a buffer that is not a NAL unit at all does not pass a closed gate`() {
        val gate = KeyframeGate()
        assertFalse(gate.admit(ByteArray(0), isKey = false))
        assertFalse(gate.admit(bytes(1, 2, 3, 4, 5), isKey = false))
        assertFalse(gate.admit(bytes(0, 0, 0, 0, 0, 0), isKey = false))
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}

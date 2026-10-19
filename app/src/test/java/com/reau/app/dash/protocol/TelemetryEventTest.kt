package com.reau.app.dash.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TelemetryEvent]'s computed display properties (used directly by TelemetryLabScreen's list rows,
 * and indirectly by [com.reau.app.viewmodel.TelemetryLabViewModel.buildCsv] for
 * `hex`/`decodedHex`) plus its deliberately reference-identity `equals`/`hashCode` override — see
 * the class's own doc for why content comparison isn't used.
 */
class TelemetryEventTest {

    @Test
    fun `label formats type and sub as zero-padded hex`() {
        val e = TelemetryEvent(atMs = 0L, type = 0x0F, sub = 0x02, raw = byteArrayOf())
        assertEquals("type=0x0F sub=0x02", e.label)
    }

    @Test
    fun `label pads single-digit hex values with a leading zero`() {
        val e = TelemetryEvent(atMs = 0L, type = 0x01, sub = 0x0A, raw = byteArrayOf())
        assertEquals("type=0x01 sub=0x0A", e.label)
    }

    @Test
    fun `hex renders raw bytes as space-separated uppercase pairs`() {
        val e = TelemetryEvent(atMs = 0L, type = 0, sub = 0, raw = byteArrayOf(0x00, 0xAB.toByte(), 0xFF.toByte()))
        assertEquals("00 AB FF", e.hex)
    }

    @Test
    fun `hex is empty for an empty raw payload`() {
        val e = TelemetryEvent(atMs = 0L, type = 0, sub = 0, raw = byteArrayOf())
        assertEquals("", e.hex)
    }

    @Test
    fun `decodedHex is null when no decoded payload is present`() {
        val e = TelemetryEvent(atMs = 0L, type = 0, sub = 0, raw = byteArrayOf(0x01))
        assertNull(e.decodedHex)
    }

    @Test
    fun `decodedHex renders the decoded payload same as hex does for raw`() {
        val e = TelemetryEvent(atMs = 0L, type = 0, sub = 0, raw = byteArrayOf(0x01), decoded = byteArrayOf(0x11, 0x22))
        assertEquals("11 22", e.decodedHex)
    }

    @Test
    fun `equals and hashCode are reference identity, not content equality`() {
        val a = TelemetryEvent(atMs = 1L, type = 1, sub = 1, raw = byteArrayOf(1))
        val b = TelemetryEvent(atMs = 1L, type = 1, sub = 1, raw = byteArrayOf(1))

        // Same field values, but NOT the same instance -> must NOT be equal (content comparison is
        // deliberately not meaningful here, see the class doc).
        assertFalse(a == b)
        assertTrue(a == a)
        assertEquals(System.identityHashCode(a), a.hashCode())
    }
}

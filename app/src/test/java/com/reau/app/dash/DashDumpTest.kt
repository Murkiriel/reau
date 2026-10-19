package com.reau.app.dash

import com.reau.app.dash.protocol.DashCommands
import com.reau.app.dash.protocol.K1GPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * A dash packet byte by byte for the detailed log, TLV by TLV, with the text of
 * the route card (05 01) and the key exchange (08 00) left out (no street, no destination, no key).
 */
class DashDumpTest {
    @Test
    fun `a packet the phone sends is shown TLV by TLV in hex`() {
        val pkt = K1GPacket.build(K1GPacket.tlv(0x05, 0x56, 0x55), K1GPacket.tlv(0x06, 0x08, 0x05))
        assertEquals("0556:55 0608:05", DashDump.outgoing(pkt))
    }

    @Test
    fun `a packet from the dash is shown the same way, without the packet header`() {
        val pkt = K1GPacket.build(K1GPacket.tlv(0x09, 0x06, 0x55))
        assertEquals("0906:55", DashDump.incoming(pkt))
    }

    @Test
    fun `the route card's title and the key exchange are left out, their size kept`() {
        val card = DashCommands.routeCard("Rua Augusta", projectionOn = true)
        val shown = DashDump.outgoing(card)
        assertFalse(shown, shown.contains("527561")) // "Rua" in hex
        assertEquals(true, shown.contains("0501:<12B left out>"))
        val key = K1GPacket.build(K1GPacket.tlv(0x08, 0x00, ByteArray(128) { 7 }))
        assertEquals("0800:<128B left out>", DashDump.outgoing(key))
    }

    @Test
    fun `the music card and the call card are left out, their size kept`() {
        val music = DashDump.outgoing(DashCommands.nowPlaying("Song", "Album", "Band"))
        assertFalse(music, music.contains("536F6E67")) // "Song" in hex
        assertEquals(music, true, music.contains("050D:<15B left out>"))
        // The caller's name goes encrypted (IV and one block), and is still left out.
        val call = DashDump.outgoing(DashCommands.callNotify("Ana", ByteArray(32), ByteArray(16)))
        assertFalse(call, call.contains("416E61")) // "Ana" in hex
        assertEquals("0522:<32B left out>", call)
    }

    @Test
    fun `the album picture's slices are left out, their size kept`() {
        val slices = DashCommands.albumArtChunks(ByteArray(1_500) { 0x41 })
        val shown = DashDump.outgoing(slices[0])
        assertFalse(shown, shown.contains("4141"))
        assertEquals(shown, true, shown.startsWith("0540:<1003B left out>"))
    }
}

package com.reau.app.dash.protocol

import com.reau.app.service.PhoneStatusReader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The volume of the music and of the call (05 4C and 05 1B, on the wire 0x10 + level, level 0..10) and the heading of the GPS (05 2D, two bytes)
 * in the 1 Hz heartbeat and in the burst's last packet: what is known goes in; what is not keeps the template's bytes, so a phone that cannot say
 * is the heartbeat of before.
 */
class HeartbeatLevelsTest {
    private val base = DashCommands.PhoneStatus(batteryPercent = 62, charging = false, gpsOn = true, signalPresent = true)

    private fun tlvs(pkt: ByteArray): List<Tlv> {
        val out = mutableListOf<Tlv>()
        var i = 17
        while (i + 4 <= pkt.size) {
            val len = ((pkt[i + 2].toInt() and 0xFF) shl 8) or (pkt[i + 3].toInt() and 0xFF)
            out += Tlv(pkt[i].toInt() and 0xFF, pkt[i + 1].toInt() and 0xFF, pkt.copyOfRange(i + 4, i + 4 + len))
            i += 4 + len
        }
        return out
    }

    private fun value(pkt: ByteArray, type: Int, sub: Int): String =
        tlvs(pkt).single { it.type == type && it.sub == sub }.value.joinToString("") { "%02X".format(it) }

    @Test
    fun `a stream's volume is the nearest of eleven steps, mute is 0 and the top is 10`() {
        assertEquals(0, DashCommands.volumeLevel(0, 15))
        assertEquals(10, DashCommands.volumeLevel(15, 15))
        assertEquals(5, DashCommands.volumeLevel(8, 15)) // 5.33
        assertEquals(6, DashCommands.volumeLevel(9, 15)) // 6.0
        assertEquals(1, DashCommands.volumeLevel(1, 15)) // 0.67
        assertEquals("a call stream of 7 steps reaches the top", 10, DashCommands.volumeLevel(7, 7))
        assertEquals(4, DashCommands.volumeLevel(3, 7)) // 4.29
        assertEquals("a volume above the maximum is the top", 10, DashCommands.volumeLevel(40, 15))
        assertEquals("one step of 100 is level 0 (rounds down)", 0, DashCommands.volumeLevel(1, 100))
        assertEquals(10, DashCommands.volumeLevel(100, 100))
    }

    @Test
    fun `a stream that cannot be read has no level`() {
        assertEquals(DashCommands.LEVEL_UNKNOWN, DashCommands.volumeLevel(5, 0))
        assertEquals(DashCommands.LEVEL_UNKNOWN, DashCommands.volumeLevel(-1, 15))
        assertEquals(DashCommands.LEVEL_UNKNOWN, DashCommands.volumeLevel(5, -3))
    }

    @Test
    fun `the heartbeat carries 0x10 plus the level in 05 4C and 05 1B`() {
        val pkt = DashCommands.heartbeat(status = base.copy(musicLevel = 7, callLevel = 0))
        assertEquals("17", value(pkt, 5, 0x4C))
        assertEquals("10", value(pkt, 5, 0x1B))
        val top = DashCommands.heartbeat(status = base.copy(musicLevel = 10, callLevel = 10))
        assertEquals("1A", value(top, 5, 0x4C))
        assertEquals("1A", value(top, 5, 0x1B))
    }

    @Test
    fun `the volumes touch no other byte of the heartbeat`() {
        val plain = tlvs(DashCommands.heartbeat(status = base))
        val loud = tlvs(DashCommands.heartbeat(status = base.copy(musicLevel = 9, callLevel = 2)))
        assertEquals(plain.size, loud.size)
        for ((a, b) in plain.zip(loud)) {
            if (a.type == 5 && (a.sub == 0x4C || a.sub == 0x1B)) continue
            assertEquals("${a.type}/${a.sub}", a.value.toList(), b.value.toList())
        }
    }

    @Test
    fun `without a level the heartbeat keeps the template's bytes, as before`() {
        val none = DashCommands.heartbeat(status = base)
        assertEquals("13", value(none, 5, 0x4C))
        assertEquals("19", value(none, 5, 0x1B))
        assertEquals("0000", value(none, 5, 0x2D))
        assertArrayEquals(DashCommands.heartbeat(), DashCommands.heartbeat(status = null))
        // A level out of 0..10 is not written either (never a byte a bar cannot draw).
        val bad = DashCommands.heartbeat(status = base.copy(musicLevel = 11, callLevel = -5))
        assertEquals("13", value(bad, 5, 0x4C))
        assertEquals("19", value(bad, 5, 0x1B))
    }

    @Test
    fun `the heading is two bytes, most significant first`() {
        assertEquals("0000", value(DashCommands.heartbeat(status = base.copy(bearingDeg = 0)), 5, 0x2D))
        assertEquals("005A", value(DashCommands.heartbeat(status = base.copy(bearingDeg = 90)), 5, 0x2D))
        assertEquals("010E", value(DashCommands.heartbeat(status = base.copy(bearingDeg = 270)), 5, 0x2D))
        assertEquals("0167", value(DashCommands.heartbeat(status = base.copy(bearingDeg = 359)), 5, 0x2D))
    }

    @Test
    fun `no heading, or one out of 0 to 359, is the template's zero`() {
        assertEquals("0000", value(DashCommands.heartbeat(status = base.copy(bearingDeg = DashCommands.BEARING_NONE)), 5, 0x2D))
        assertEquals("0000", value(DashCommands.heartbeat(status = base.copy(bearingDeg = 360)), 5, 0x2D))
        assertEquals("0000", value(DashCommands.heartbeat(status = base.copy(bearingDeg = 1_000)), 5, 0x2D))
    }

    @Test
    fun `the heading touches no other byte and keeps the packet's length`() {
        val plain = DashCommands.heartbeat(status = base)
        val turned = DashCommands.heartbeat(status = base.copy(bearingDeg = 200))
        assertEquals(plain.size, turned.size)
        val differing = plain.indices.filter { plain[it] != turned[it] }
        assertEquals("only the two bytes of 05 2D", 1, differing.size) // 200 = 0x00C8: the high byte stays 0
        assertEquals(0xC8, turned[differing.single()].toInt() and 0xFF)
    }

    @Test
    fun `the burst's last packet carries the same levels and heading`() {
        val last = DashCommands.initialBurst("DashTest", status = base.copy(musicLevel = 3, callLevel = 8, bearingDeg = 45)).last()
        assertEquals("13", value(last, 5, 0x4C))
        assertEquals("18", value(last, 5, 0x1B))
        assertEquals("002D", value(last, 5, 0x2D))
    }

    @Test
    fun `the phone's status keeps the levels and the heading it was given`() {
        val s = PhoneStatusReader.statusOf(80, 100, 3, true, 3, musicLevel = 4, callLevel = 9, bearingDeg = 123)!!
        assertEquals(4, s.musicLevel)
        assertEquals(9, s.callLevel)
        assertEquals(123, s.bearingDeg)
        val unknown = PhoneStatusReader.statusOf(80, 100, 3, true, 3)!!
        assertEquals(DashCommands.LEVEL_UNKNOWN, unknown.musicLevel)
        assertEquals(DashCommands.LEVEL_UNKNOWN, unknown.callLevel)
        assertEquals(DashCommands.BEARING_NONE, unknown.bearingDeg)
        assertFalse(unknown.bearingDeg in 0..359)
        assertTrue(DashCommands.VOLUME_LEVEL_MAX == 10)
    }
}

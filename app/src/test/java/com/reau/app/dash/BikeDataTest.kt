package com.reau.app.dash

import com.reau.app.dash.protocol.DashCommands
import com.reau.app.service.PhoneStatusReader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bike data: what the dash says in words ([DashReport]), the live memory of it
 * ([DashReadings]), and the packets that came with it (decimal separator, proximity bar, street, album slices).
 */
class BikeDataTest {

    private fun reading(hex: String) = DashReading(hex, 1_000L, 1)

    /** The ASCII bytes of [text] as hex. */
    private fun ascii(text: String) = text.map { "%02X".format(it.code) }.joinToString("")

    // ── DashReport ──────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the settings a dash said on every connection read as words`() {
        // Values observed on a real dash: 0303:55 0304:55 0305:55 0306:10 0308:55 0309:04 030A:AAAAAAAAAA 030B:55555555.
        val all = mapOf(
            "0303" to reading("55"), "0304" to reading("55"), "0305" to reading("55"), "0306" to reading("10"),
            "0308" to reading("55"), "0309" to reading("04"), "030A" to reading("AAAAAAAAAA"), "030B" to reading("55555555"),
        )
        val s = DashReport.settings(all)
        assertEquals(DashReport.TempUnit.CELSIUS, s.temperature)
        assertEquals(DashReport.DistanceUnit.KM, s.distance)
        assertEquals(DashReport.Theme.AUTO, s.theme)
        assertEquals(DashReport.ConsumptionUnit.KM_PER_L, s.consumption)
        assertEquals(DashReport.ClockFormat.H24, s.clock)
        assertEquals(DashReport.Language.PT, s.language)
        assertEquals(listOf(true, true, true, true, true), s.notifications)
        assertEquals(listOf(false, false, false, false), s.pois)
    }

    @Test
    fun `a setting the dash never said, or said in a way nobody knows, is null`() {
        val s = DashReport.settings(mapOf("0305" to reading("77"), "030A" to reading("AA55")))
        assertNull(s.theme)
        assertNull(s.notifications)
        assertNull(s.temperature)
        assertEquals(DashReport.Theme.DARK, DashReport.theme("AA"))
    }

    @Test
    fun `the firmware partitions are read from the 0B02 text, and the second one is the version shown`() {
        // AA 00, then "00.02.07" "01.08.05" "02.01.01" as ASCII, then zeros: the shape the dash sends.
        val hex = "AA00" + ascii("00.02.07") + ascii("01.08.05") + ascii("02.01.01") + "0000"
        val parts = DashReport.partitions(hex)
        assertEquals(listOf("2.07", "8.05", "1.01"), parts.map { it.version })
        assertEquals(listOf(0, 1, 2), parts.map { it.block })
        assertTrue(DashReport.partitions(null).isEmpty())
        assertTrue(DashReport.partitions("AA00").isEmpty())
    }

    @Test
    fun `safe mode and the text of a 0F value (the variant)`() {
        assertEquals(false, DashReport.safeMode("55"))
        assertEquals(true, DashReport.safeMode("AA"))
        assertNull(DashReport.safeMode("01"))
        assertEquals("ABC", DashReport.text(ascii("ABC") + "0000"))
        assertNull(DashReport.text("0000"))
    }

    @Test
    fun `the bike's state, the ABS modes and the low fuel reminder`() {
        assertEquals(DashReport.Abs("Preferred", true), DashReport.abs("00"))
        assertEquals(DashReport.Abs("Preferred", false), DashReport.abs("01"))
        assertEquals(DashReport.Abs("Eco", true), DashReport.abs("02"))
        assertEquals(DashReport.Abs("Eco", false), DashReport.abs("03"))
        assertNull(DashReport.abs("09"))
        assertEquals(true, DashReport.lowFuel("55"))
        assertEquals(false, DashReport.lowFuel("AA"))
        assertNull(DashReport.lowFuel(null))
    }

    @Test
    fun `the range is two bytes, most significant first, in kilometres, as a real dash says it`() {
        // Values observed on a real dash ("dash rx 0C07:004C"): the range only falls, from 0xCA (202) down to 0x25 (37) in one run.
        assertEquals(76, DashReport.rangeKm("004C"))
        assertEquals(202, DashReport.rangeKm("00CA"))
        assertEquals(37, DashReport.rangeKm("0025"))
        assertEquals(209, DashReport.rangeKm("00D1"))
        assertEquals(0, DashReport.rangeKm("0000"))
        assertEquals(300, DashReport.rangeKm("012c"))
    }

    @Test
    fun `no reading, or a value that is not two bytes of hex, is no range at all`() {
        assertNull(DashReport.rangeKm(null))
        assertNull(DashReport.rangeKm(""))
        assertNull(DashReport.rangeKm("4C"))
        assertNull(DashReport.rangeKm("00004C"))
        assertNull(DashReport.rangeKm("-001"))
        assertNull(DashReport.rangeKm("00ZZ"))
        assertNull(DashReport.rangeKm("+04C"))
    }

    @Test
    fun `the range follows the dash's distance unit, and kilometres stay in brackets in miles`() {
        assertEquals("76 km", DashReport.rangeText(76, DashReport.DistanceUnit.KM))
        // Not said yet: the unit observed on a real dash, kilometres.
        assertEquals("76 km", DashReport.rangeText(76, null))
        assertEquals("47 mi (76 km)", DashReport.rangeText(76, DashReport.DistanceUnit.MILES))
        assertEquals("126 mi (202 km)", DashReport.rangeText(202, DashReport.DistanceUnit.MILES))
        assertEquals("0 mi (0 km)", DashReport.rangeText(0, DashReport.DistanceUnit.MILES))
    }

    @Test
    fun `the range is read from the kept readings with the unit of the dash's own setting`() {
        val all = mapOf("0C07" to reading("004C"), "0304" to reading("AA"))
        assertEquals("47 mi (76 km)", DashReport.rangeText(DashReport.rangeKm(all["0C07"]?.hex)!!, DashReport.settings(all).distance))
        assertNull(DashReport.rangeKm(emptyMap<String, DashReading>()["0C07"]?.hex))
    }

    @Test
    fun `only the codes the screen shows are kept, the rest of what the dash says is not`() {
        for (key in listOf("0303", "0305", "030B", "0B02", "0F04", "0F0B", "0C05", "0C07", "0C14")) assertTrue(key, DashReport.isShown(key))
        for (key in listOf("0F01", "0F02", "0F05", "0F0A", "0C24", "0C26", "0908", "0907")) assertFalse(key, DashReport.isShown(key))
        assertEquals("0C07", DashReport.key(0x0C, 0x07))
        DashReadings.clear()
        DashReadings.onValue(0x0C, 0x07, byteArrayOf(0x00, 0xCA.toByte()), 1_000L)
        DashReadings.onValue(0x0C, 0x26, byteArrayOf(0xE8.toByte()), 1_000L)
        DashReadings.onValue(0x0F, 0x01, "9C2TESTVIN0000001".toByteArray(), 1_000L)
        DashReadings.onValue(0x0C, 0x14, byteArrayOf(0x00), 1_000L)
        DashReadings.flush()
        assertEquals(setOf("0C07", "0C14"), DashReadings.all.value.keys)
        assertEquals(202, DashReport.rangeKm(DashReadings.all.value.getValue("0C07").hex))
        DashReadings.clear()
    }

    // ── DashReadings ────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a reading keeps the last value, when it came and how many times`() {
        DashReadings.clear()
        DashReadings.onValue(0x0C, 0x14, byteArrayOf(0x00), 10_000L)
        DashReadings.onValue(0x0C, 0x14, byteArrayOf(0x03), 12_500L)
        DashReadings.flush()
        val r = DashReadings.all.value.getValue("0C14")
        assertEquals("03", r.hex)
        assertEquals(12_500L, r.atMs)
        assertEquals(2, r.count)
        assertEquals(DashReport.Settings(), DashReadings.settings.value)
        DashReadings.clear()
        assertTrue(DashReadings.all.value.isEmpty())
    }

    @Test
    fun `the settings flow follows the readings when they are published`() {
        DashReadings.clear()
        DashReadings.onValue(0x03, 0x05, byteArrayOf(0xAA.toByte()), 50_000L)
        DashReadings.flush()
        assertEquals(DashReport.Theme.DARK, DashReadings.settings.value.theme)
        DashReadings.clear()
        assertNull(DashReadings.settings.value.theme)
    }

    // ── packets ─────────────────────────────────────────────────────────────────────────────────────────────────────

    /** The value of the first TLV (type, sub) of a packet built by `K1GPacket.build` or the nav packet; null when absent. */
    private fun valueOf(pkt: ByteArray, type: Int, sub: Int): ByteArray? {
        val needle = byteArrayOf(type.toByte(), sub.toByte())
        for (i in 17 until pkt.size - 4) {
            if (pkt[i] == needle[0] && pkt[i + 1] == needle[1]) {
                val len = ((pkt[i + 2].toInt() and 0xFF) shl 8) or (pkt[i + 3].toInt() and 0xFF)
                if (len in 1..2_000 && i + 4 + len <= pkt.size) return pkt.copyOfRange(i + 4, i + 4 + len)
            }
        }
        return null
    }

    @Test
    fun `the proximity bar follows the distance in metres`() {
        val table = mapOf(0 to 4, 20 to 4, 21 to 3, 40 to 3, 41 to 2, 60 to 2, 61 to 1, 80 to 1, 81 to 0, 100 to 0, 101 to 6, 500 to 6, 501 to 5, 5_000 to 5)
        table.forEach { (m, level) -> assertEquals("$m m", level, DashCommands.proximityLevel(m)) }
        // A keep-left or keep-right is 4 from 0 to 100 m.
        for (m in listOf(10, 21, 60, 100)) assertEquals("keep at $m m", 4, DashCommands.proximityLevel(m, keep = true))
        assertEquals(6, DashCommands.proximityLevel(300, keep = true))
        assertEquals(5, DashCommands.proximityLevel(900, keep = true))
    }

    @Test
    fun `the nav packet carries the proximity bar only when it is given, and its count says so`() {
        val without = DashCommands.activeNavPacket()
        val with = DashCommands.activeNavPacket(proximity = 3)
        assertNull(valueOf(without, 0x05, 0x0C))
        assertArrayEquals(byteArrayOf(3), valueOf(with, 0x05, 0x0C))
        assertEquals(without.size + 5, with.size)
        val declared = ((with[0].toInt() and 0xFF) shl 8) or (with[1].toInt() and 0xFF)
        assertEquals(with.size, declared)
    }

    @Test
    fun `the decimal separator is a point unless the phone's is a comma`() {
        assertArrayEquals(byteArrayOf(0x55), valueOf(DashCommands.activeNavPacket(), 0x05, 0x0A))
        assertArrayEquals(byteArrayOf(0xAA.toByte()), valueOf(DashCommands.activeNavPacket(decimalComma = true), 0x05, 0x0A))
        // The route card (sent as often) says the same, or the two would flip the separator.
        assertArrayEquals(byteArrayOf(0x55), valueOf(DashCommands.routeCard("Casa", projectionOn = true), 0x05, 0x0A))
        assertArrayEquals(byteArrayOf(0xAA.toByte()), valueOf(DashCommands.routeCard("Casa", projectionOn = true, decimalComma = true), 0x05, 0x0A))
    }

    @Test
    fun `the street goes in Latin-1, 19 characters at most, ended by a zero, and with none there is no TLV`() {
        val pkt = DashCommands.activeNavPacket(road = "Avenida Brigadeiro Faria Lima, 1500")
        val v = valueOf(pkt, 0x05, 0x01)!!
        assertEquals(20, v.size)
        assertEquals(0, v.last().toInt())
        assertEquals("Avenida Brigadeiro ", String(v, 0, 19, Charsets.ISO_8859_1))
        assertEquals("São João".toByteArray(Charsets.ISO_8859_1).toList(), DashCommands.latin1("São João", 19).toList())
        assertEquals('?'.code.toByte(), DashCommands.latin1("a→b", 19)[1])
        assertNull(valueOf(DashCommands.activeNavPacket(road = "  "), 0x05, 0x01))
    }

    @Test
    fun `the album picture goes in slices of 1000 bytes, the first with the total, the last numbered FF`() {
        val jpeg = ByteArray(2_500) { (it % 251).toByte() }
        val slices = DashCommands.albumArtChunks(jpeg).map { valueOf(it, 0x05, 0x40)!! }
        assertEquals(3, slices.size)
        // First: [0][total, 2 bytes][1000 bytes of data].
        assertEquals(0, slices[0][0].toInt())
        assertEquals(2_500, ((slices[0][1].toInt() and 0xFF) shl 8) or (slices[0][2].toInt() and 0xFF))
        assertArrayEquals(jpeg.copyOfRange(0, 1_000), slices[0].copyOfRange(3, slices[0].size))
        // Middle: [1][1000 bytes]. Last: [FF][what is left].
        assertEquals(1, slices[1][0].toInt())
        assertArrayEquals(jpeg.copyOfRange(1_000, 2_000), slices[1].copyOfRange(1, slices[1].size))
        assertEquals(0xFF, slices[2][0].toInt() and 0xFF)
        assertArrayEquals(jpeg.copyOfRange(2_000, 2_500), slices[2].copyOfRange(1, slices[2].size))
    }

    @Test
    fun `a picture that fits one slice is followed by an empty last slice, and none or a huge one is no slice at all`() {
        val small = DashCommands.albumArtChunks(ByteArray(300) { 9 })
        assertEquals(2, small.size)
        assertEquals(0xFF, valueOf(small[1], 0x05, 0x40)!![0].toInt() and 0xFF)
        assertTrue(DashCommands.albumArtChunks(ByteArray(0)).isEmpty())
        assertTrue(DashCommands.albumArtChunks(ByteArray(DashCommands.MAX_COVER_BYTES + 1)).isEmpty())
    }

    // ── the phone's status and the picture ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `the phone's status comes from the battery, the GPS switch and the cell signal`() {
        val s = PhoneStatusReader.statusOf(level = 50, scale = 200, batteryStatus = 2, gpsOn = false, signalLevel = 0)!!
        assertEquals(25, s.batteryPercent)
        assertTrue(s.charging)
        assertFalse(s.gpsOn)
        assertFalse(s.signalPresent)
        val unknown = PhoneStatusReader.statusOf(level = 80, scale = 100, batteryStatus = 3, gpsOn = null, signalLevel = null)!!
        assertFalse(unknown.charging)
        assertTrue("GPS and signal the template says when unreadable", unknown.gpsOn && unknown.signalPresent)
        assertNull(PhoneStatusReader.statusOf(level = -1, scale = 100, batteryStatus = 1, gpsOn = true, signalLevel = 3))
        assertNull(PhoneStatusReader.statusOf(level = 10, scale = 0, batteryStatus = 1, gpsOn = true, signalLevel = 3))
    }

    @Test
    fun `the picture is read at the power of two that leaves the short side at 75 or more`() {
        assertEquals(1, AlbumArt.sampleSize(60))
        assertEquals(1, AlbumArt.sampleSize(149))
        assertEquals(2, AlbumArt.sampleSize(150))
        assertEquals(8, AlbumArt.sampleSize(600))
        assertEquals(16, AlbumArt.sampleSize(1_200))
    }
}

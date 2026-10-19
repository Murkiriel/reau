package com.reau.app.dash.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DashCommandsTest {

    @Test
    fun `authSendKey requires exactly 128 bytes of RSA ciphertext`() {
        assertThrows(IllegalArgumentException::class.java) {
            DashCommands.authSendKey(ByteArray(127))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DashCommands.authSendKey(ByteArray(129))
        }
        DashCommands.authSendKey(ByteArray(128)) // must NOT throw
    }

    @Test
    fun `authSendKey appends the ciphertext verbatim after the fixed header`() {
        val cipher = ByteArray(128) { it.toByte() }
        val pkt = DashCommands.authSendKey(cipher)
        assertArrayEquals(cipher, pkt.copyOfRange(pkt.size - 128, pkt.size))
    }

    @Test
    fun `hostnameAnnounce declares outer_len equal to the actual packet size`() {
        val pkt = DashCommands.hostnameAnnounce("MyPhone")
        val declared = ((pkt[0].toInt() and 0xFF) shl 8) or (pkt[1].toInt() and 0xFF)
        assertEquals(pkt.size, declared)
    }

    @Test
    fun `hostnameAnnounce embeds the hostname bytes NUL-terminated`() {
        val pkt = DashCommands.hostnameAnnounce("Rider")
        val text = String(pkt.copyOfRange(pkt.size - 1 - 5, pkt.size - 1), Charsets.UTF_8)
        assertEquals("Rider", text)
        assertEquals(0, pkt[pkt.size - 1].toInt())
    }

    @Test
    fun `hostnameAnnounce truncates names over 200 bytes so the dash never gets an oversized field`() {
        val longName = "x".repeat(500)
        val pkt = DashCommands.hostnameAnnounce(longName)
        val declared = ((pkt[0].toInt() and 0xFF) shl 8) or (pkt[1].toInt() and 0xFF)
        assertEquals(pkt.size, declared)
        assertTrue(pkt.size < 12 + longName.length) // proof it didn't just dump the whole 500 bytes
    }

    @Test
    fun `buttonAck echoes the button code back in a 06 80 TLV`() {
        val pkt = DashCommands.buttonAck(0x09.toByte())
        val tlvs = outgoingTlvs(pkt)
        assertEquals(1, tlvs.size)
        assertEquals(6, tlvs[0].type)
        assertEquals(0x80, tlvs[0].sub)
        assertArrayEquals(byteArrayOf(9), tlvs[0].value)
    }

    @Test
    fun `exitBack sends a 06 80 TLV with the dash's own BACK code, not a received button`() {
        val tlvs = outgoingTlvs(DashCommands.exitBack())
        assertEquals(1, tlvs.size)
        assertEquals(6, tlvs[0].type)
        assertEquals(0x80, tlvs[0].sub)
        assertArrayEquals(byteArrayOf(0x12), tlvs[0].value)
    }

    @Test
    fun `exitProjectionIdle sends a 06 05 = AA TLV`() {
        val tlvs = outgoingTlvs(DashCommands.exitProjectionIdle())
        assertEquals(1, tlvs.size)
        assertEquals(6, tlvs[0].type)
        assertEquals(0x05, tlvs[0].sub)
        assertArrayEquals(byteArrayOf(0xAA.toByte()), tlvs[0].value)
    }

    @Test
    fun `exitSessionBoundary sends a 05 17 = AA TLV`() {
        val tlvs = outgoingTlvs(DashCommands.exitSessionBoundary())
        assertEquals(1, tlvs.size)
        assertEquals(5, tlvs[0].type)
        assertEquals(0x17, tlvs[0].sub)
        assertArrayEquals(byteArrayOf(0xAA.toByte()), tlvs[0].value)
    }

    @Test
    fun `heartbeat patches the temperature byte as degrees C plus 40`() {
        val pkt25 = DashCommands.heartbeat(DashCommands.Weather(25, 5))
        val pkt0 = DashCommands.heartbeat(DashCommands.Weather(0, 5))
        val marker = byteArrayOf(6, 0x10, 0, 1)
        val i25 = indexOfBytes(pkt25, marker)
        val i0 = indexOfBytes(pkt0, marker)
        assertTrue(i25 >= 0)
        assertEquals(65, pkt25[i25 + 4].toInt() and 0xFF) // 25 + 40
        assertEquals(40, pkt0[i0 + 4].toInt() and 0xFF)   // 0 + 40
    }

    @Test
    fun `heartbeat with no weather has no temperature and says no data in the icon`() {
        val f = heartbeatFields(DashCommands.heartbeat())
        assertEquals("no 06 10 at all", null, f[0x06 to 0x10])
        assertEquals("the icon says no data", DashCommands.WEATHER_NONE, f[0x06 to 0x08])
        // The packet is whole: its outer length and segment count follow the TLV that went.
        val pkt = DashCommands.heartbeat()
        assertEquals(pkt.size, ((pkt[0].toInt() and 0xFF) shl 8) or (pkt[1].toInt() and 0xFF))
        assertEquals(outgoingTlvs(pkt).size, ((pkt[2].toInt() and 0xFF) shl 8) or (pkt[3].toInt() and 0xFF))
        assertEquals(outgoingTlvs(DashCommands.heartbeat(DashCommands.Weather(20, 3))).size, outgoingTlvs(pkt).size + 1)
    }

    @Test
    fun `heartbeat with a weather carries its icon and the temperature clamped to what a byte holds`() {
        val f = heartbeatFields(DashCommands.heartbeat(DashCommands.Weather(21, 3)))
        assertEquals(61, f[0x06 to 0x10])
        assertEquals(3, f[0x06 to 0x08])
        assertEquals(0, heartbeatFields(DashCommands.heartbeat(DashCommands.Weather(-90, 1)))[0x06 to 0x10])
        assertEquals(140, heartbeatFields(DashCommands.heartbeat(DashCommands.Weather(300, 1)))[0x06 to 0x10])
    }

    // ── sendRealPhoneStatus's golden tests — status=null must NOT change a single byte ──

    @Test
    fun `heartbeat with status null (sendRealPhoneStatus off) returns exactly today's bytes`() {
        // The mandatory golden regression: today's real call site
        // (DashSession) passes no status at all -- this locks that call in byte for byte, forever.
        assertArrayEquals(DashCommands.heartbeat(), DashCommands.heartbeat(weather = null, status = null))
        val w = DashCommands.Weather(17, 3)
        assertArrayEquals(DashCommands.heartbeat(w), DashCommands.heartbeat(weather = w, status = null))
    }

    private fun heartbeatFields(pkt: ByteArray): Map<Pair<Int, Int>, Int> =
        outgoingTlvs(pkt).associate { (it.type to it.sub) to (it.value.firstOrNull()?.toInt()?.and(0xFF) ?: -1) }

    private val baselineStatus = DashCommands.PhoneStatus(
        batteryPercent = 62, charging = false, gpsOn = true, signalPresent = true,
    )

    @Test
    fun `initial burst without a status keeps the captured placeholder packet byte for byte`() {
        val a = DashCommands.initialBurst("DashTest")
        val b = DashCommands.initialBurst("DashTest", status = null)
        assertEquals(a.size, b.size)
        a.indices.filter { it != 2 }.forEach { assertArrayEquals(a[it], b[it]) } // [2] is the live clock
    }

    @Test
    fun `initial burst with a status carries the real battery instead of 62 percent`() {
        val burst = DashCommands.initialBurst("DashTest", status = baselineStatus.copy(batteryPercent = 81))
        val statusPkt = burst.last()
        val fields = heartbeatFields(statusPkt)
        assertEquals(181, fields[0x06 to 0x04])
    }

    @Test
    fun `metadata 0030 matches the layout the dash accepts`() {
        val pkt = DashCommands.metadata0030(weatherIcon = 0xA0)
        val expected = ("0030000600000000020100054b31472000" + "060800" + "01a0" +
            "054c000113052d00020000051b0001190521000132054d000132")
        assertEquals(expected, pkt.joinToString("") { "%02x".format(it) })
        val fields = heartbeatFields(pkt)
        assertEquals(0xA0, fields[0x06 to 0x08])
        assertEquals(((pkt[0].toInt() and 0xFF) shl 8) or (pkt[1].toInt() and 0xFF), pkt.size)
    }

    @Test
    fun `heartbeat with a status matching today's placeholder values reproduces the same bytes`() {
        // Confirms baselineStatus's own field values are read from HB_0049's real placeholder
        // bytes correctly (62%, not charging, GPS on, signal present, strength 5, 25C) -- not
        // guessed by eye, cross-checked against the no-status call.
        assertArrayEquals(DashCommands.heartbeat(), DashCommands.heartbeat(status = baselineStatus))
    }

    @Test
    fun `heartbeat status changes only its own byte -- battery 0 percent and 100 percent`() {
        val f0 = heartbeatFields(DashCommands.heartbeat(status = baselineStatus.copy(batteryPercent = 0)))
        val f100 = heartbeatFields(DashCommands.heartbeat(status = baselineStatus.copy(batteryPercent = 100)))
        assertEquals(100, f0[0x06 to 0x04]) // 0 + 100
        assertEquals(200, f100[0x06 to 0x04]) // 100 + 100
        // every OTHER field stays at the baseline's encoding, proving isolation
        for (key in listOf(0x06 to 0x0F, 0x06 to 0x03, 0x06 to 0x01, 0x06 to 0x08, 0x06 to 0x10)) {
            assertEquals("field $key must not move when only battery changes", heartbeatFields(DashCommands.heartbeat(status = baselineStatus))[key], f0[key])
        }
    }

    @Test
    fun `heartbeat status changes only its own byte -- charging`() {
        val on = heartbeatFields(DashCommands.heartbeat(status = baselineStatus.copy(charging = true)))
        val off = heartbeatFields(DashCommands.heartbeat(status = baselineStatus.copy(charging = false)))
        assertEquals(0x55, on[0x06 to 0x0F])
        assertEquals(0xAA, off[0x06 to 0x0F])
    }

    @Test
    fun `heartbeat status changes only its own byte -- GPS on and off`() {
        val on = heartbeatFields(DashCommands.heartbeat(status = baselineStatus.copy(gpsOn = true)))
        val off = heartbeatFields(DashCommands.heartbeat(status = baselineStatus.copy(gpsOn = false)))
        assertEquals(0x55, on[0x06 to 0x03])
        assertEquals(0xAA, off[0x06 to 0x03])
    }

    @Test
    fun `heartbeat status changes only its own byte -- signal present and absent`() {
        val present = heartbeatFields(DashCommands.heartbeat(status = baselineStatus.copy(signalPresent = true)))
        val absent = heartbeatFields(DashCommands.heartbeat(status = baselineStatus.copy(signalPresent = false)))
        assertEquals(0x01, present[0x06 to 0x01])
        assertEquals(0x00, absent[0x06 to 0x01])
    }

    @Test
    fun `heartbeat with a status leaves 06 08, the weather icon, as the weather says`() {
        // 06 08 is the WEATHER icon, not the signal strength it was labelled: no status may write there.
        val template = heartbeatFields(DashCommands.heartbeat())[0x06 to 0x08]
        for (status in listOf(baselineStatus, baselineStatus.copy(batteryPercent = 5, charging = true, gpsOn = false, signalPresent = false))) {
            assertEquals(template, heartbeatFields(DashCommands.heartbeat(status = status))[0x06 to 0x08])
        }
    }

    @Test
    fun `heartbeat status battery percent clamps out-of-range values instead of wrapping the byte`() {
        assertEquals("negative battery clamps to 0 percent (wire byte 100)", 100, heartbeatFields(DashCommands.heartbeat(status = baselineStatus.copy(batteryPercent = -10)))[0x06 to 0x04])
        assertEquals("over 100 percent clamps to 100 (wire byte 200)", 200, heartbeatFields(DashCommands.heartbeat(status = baselineStatus.copy(batteryPercent = 200)))[0x06 to 0x04])
    }

    @Test
    fun `activeNavPacket declares an outer length matching the actual byte count`() {
        val pkt = DashCommands.activeNavPacket()
        val declared = ((pkt[0].toInt() and 0xFF) shl 8) or (pkt[1].toInt() and 0xFF)
        assertEquals(pkt.size, declared)
    }

    @Test
    fun `activeNavPacket maneuver byte reflects whatever glyph code is passed`() {
        // Pins the exact mechanism a DEBUG-only maneuver-glyph test tool relies on: whatever candidate
        // byte the rider steps to must actually land in the TLV the dash reads, not get silently
        // dropped/clamped somewhere.
        val marker = byteArrayOf(5, 2, 0, 1)
        for (candidate in listOf(0x00, 0x09, 0x0B, 0x14, 0x20, 0x59, 0x5A, 0xFF)) {
            val pkt = DashCommands.activeNavPacket(maneuver = candidate)
            val i = indexOfBytes(pkt, marker)
            assertTrue("marker not found for candidate=$candidate", i >= 0)
            assertEquals(candidate, pkt[i + 4].toInt() and 0xFF)
        }
    }

    @Test
    fun `every possible arrow byte lands intact in the packet, next to its distance`() {
        for (code in 0x00..0xFF) {
            val pkt = DashCommands.activeNavPacket(maneuver = code, primaryDist = 250, primaryUnit = DashCommands.NAV_UNIT_METERS)
            val i = indexOfBytes(pkt, byteArrayOf(5, 2, 0, 1))
            assertEquals("code 0x%02X".format(code), code, pkt[i + 4].toInt() and 0xFF)
            val d = indexOfBytes(pkt, byteArrayOf(5, 4, 0, 2))
            assertEquals(250, ((pkt[d + 4].toInt() and 0xFF) shl 8) or (pkt[d + 5].toInt() and 0xFF))
            assertEquals("outer length", pkt.size, ((pkt[0].toInt() and 0xFF) shl 8) or (pkt[1].toInt() and 0xFF))
        }
    }

    @Test
    fun `without an arrow given the packet draws the straight arrow`() {
        val pkt = DashCommands.activeNavPacket()
        assertEquals(0x09, pkt[indexOfBytes(pkt, byteArrayOf(5, 2, 0, 1)) + 4].toInt() and 0xFF)
        assertEquals(0x09, DashCommands.NAV_MANEUVER_CONTINUE)
    }

    @Test
    fun `with no guidance the route card hides the bubble instead of the templates arrival pin`() {
        val card = DashCommands.idleRouteCard(com.reau.app.dash.DashIdleCard.HIDDEN, "Destino", projectionOn = true, clockHHMM = "1230")
        assertArrayEquals(DashCommands.standbyRouteCard("Destino", projectionOn = true), card)
        assertEquals(0xFF, card[indexOfBytes(card, byteArrayOf(5, 2, 0, 1)) + 4].toInt() and 0xFF)
        assertEquals(0xFF, DashCommands.NAV_MANEUVER_NONE)
        // the projection flag and everything else of the card are still the normal ones
        val normal = DashCommands.routeCard("Destino", projectionOn = true)
        assertEquals(normal.size, card.size)
        assertEquals(0x55, card[indexOfBytes(card, byteArrayOf(6, 5, 0, 1)) + 4].toInt() and 0xFF)
        assertEquals(0x3C, normal[indexOfBytes(normal, byteArrayOf(5, 2, 0, 1)) + 4].toInt() and 0xFF)   // the template glyph this replaces
    }

    /**
     * With the hidden bubble the dash stops passing its joystick on in its map mode (observed in ride logs);
     * with no route the card carries the template's arrival pin again, the clock as its ETA.
     */
    @Test
    fun `with no route the default card shows the arrival pin and the clock as the ETA`() {
        val card = DashCommands.idleRouteCard(com.reau.app.dash.DashIdleCard.PIN, "Destino", projectionOn = true, clockHHMM = "1234")
        assertEquals(0x3C, card[indexOfBytes(card, byteArrayOf(5, 2, 0, 1)) + 4].toInt() and 0xFF)
        assertEquals(DashCommands.NAV_MANEUVER_ARRIVAL_PIN, 0x3C)
        val eta = indexOfBytes(card, byteArrayOf(5, 8, 0, 4))
        assertEquals("1234", String(card.copyOfRange(eta + 4, eta + 8), Charsets.US_ASCII))
        assertEquals("no bogus total distance (no field at all)", -1, indexOfBytes(card, byteArrayOf(5, 9, 0, 2)))
        assertEquals(0x55, card[indexOfBytes(card, byteArrayOf(6, 5, 0, 1)) + 4].toInt() and 0xFF)
    }

    /**
     * The glyph 0xFF is never sent; with no route or a maneuver the dash cannot draw, the glyph field (05 02) is left
     * out. The ring is hidden that way; the
     * packets stay whole: the field count in the header and the length follow.
     */
    @Test
    fun `with the glyph left out the nav packet has no 05 02 and its counts follow`() {
        val pkt = DashCommands.activeNavPacket(maneuver = DashCommands.NAV_MANEUVER_OMIT)
        val tlvs = outgoingTlvs(pkt)
        assertTrue(tlvs.none { it.type == 5 && it.sub == 2 })
        assertEquals(7, tlvs.size)
        assertEquals("segment count = 1 + fields", 8, ((pkt[2].toInt() and 0xFF) shl 8) or (pkt[3].toInt() and 0xFF))
        assertEquals(pkt.size, ((pkt[0].toInt() and 0xFF) shl 8) or (pkt[1].toInt() and 0xFF))
        assertEquals("the rest as with a glyph", outgoingTlvs(DashCommands.activeNavPacket()).filterNot { it.sub == 2 && it.type == 5 }
            .map { it.type to it.sub }, tlvs.map { it.type to it.sub })
    }

    @Test
    fun `with the glyph left out the route card has no 05 02 and its counts follow`() {
        val full = DashCommands.routeCard("Casa", projectionOn = true, maneuver = 0x15)
        val pkt = DashCommands.routeCard("Casa", projectionOn = true, maneuver = DashCommands.NAV_MANEUVER_OMIT)
        val tlvs = outgoingTlvs(pkt)
        assertTrue(tlvs.none { it.type == 5 && it.sub == 2 })
        assertEquals(outgoingTlvs(full).size - 1, tlvs.size)
        assertEquals(full.size - 5, pkt.size)
        assertEquals(pkt.size, ((pkt[0].toInt() and 0xFF) shl 8) or (pkt[1].toInt() and 0xFF))
        assertEquals(((full[2].toInt() and 0xFF) shl 8 or (full[3].toInt() and 0xFF)) - 1, ((pkt[2].toInt() and 0xFF) shl 8) or (pkt[3].toInt() and 0xFF))
        assertEquals("Casa", String(tlvs.first { it.type == 5 && it.sub == 1 }.value, Charsets.UTF_8).trimEnd('\u0000'))
    }

    /** The flags-only idle card, never tried on the bike, is gone. */
    @Test
    fun `the idle card is the pin or the hidden bubble`() {
        assertEquals(listOf(com.reau.app.dash.DashIdleCard.PIN, com.reau.app.dash.DashIdleCard.HIDDEN), com.reau.app.dash.DashIdleCard.entries)
    }

    @Test
    fun `the route card carries the same arrow the active nav packet does`() {
        for (code in listOf(0x00, 0x09, 0x14, 0x15, 0x0C, 0x1C)) {
            val card = DashCommands.routeCard("Destino", projectionOn = true, maneuver = code)
            val i = indexOfBytes(card, byteArrayOf(5, 2, 0, 1))
            assertEquals("code 0x%02X".format(code), code, card[i + 4].toInt() and 0xFF)
        }
    }

    @Test
    fun `activeNavPacket projection flag reflects the projectionOn argument`() {
        val on = DashCommands.activeNavPacket(projectionOn = true)
        val off = DashCommands.activeNavPacket(projectionOn = false)
        val marker = byteArrayOf(6, 5, 0, 1)
        val iOn = indexOfBytes(on, marker)
        val iOff = indexOfBytes(off, marker)
        assertEquals(0x55, on[iOn + 4].toInt() and 0xFF)
        assertEquals(0xAA, off[iOff + 4].toInt() and 0xFF)
    }

    /**
     * The music card: album, title and artist, 19 characters each, and around the text three flags, each a 1-byte
     * TLV: 05 17 music on (0x55) or none (0xAA), 05 19 playing (0x55) or paused (0xAA), 05 58 album art follows (0x55)
     * or none (0xAA). These are the dash's packets byte for byte.
     */
    @Test
    fun `the music flags are the dash's packets byte for byte`() {
        fun hex(b: ByteArray) = b.joinToString("") { "%02X".format(it) }
        assertEquals("0016000200000000020100054B314720000517000155", hex(DashCommands.musicActive(true)))
        assertEquals("0016000200000000020100054B3147200005170001AA", hex(DashCommands.musicActive(false)))
        assertEquals("0016000200000000020100054B314720000519000155", hex(DashCommands.musicPlaying(true)))
        assertEquals("0016000200000000020100054B3147200005190001AA", hex(DashCommands.musicPlaying(false)))
        assertEquals("0016000200000000020100054B314720000558000155", hex(DashCommands.musicCover(true)))
        assertEquals("0016000200000000020100054B3147200005580001AA", hex(DashCommands.musicCover(false)))
    }

    @Test
    fun `nowPlaying sends album, title and artist, 19 characters each`() {
        val pkt = DashCommands.nowPlaying(title = "A Really Quite Long Song Title", album = "Dois", artist = "Legião Urbana")
        val value = outgoingTlvs(pkt).single { it.type == 5 && it.sub == 0x0D }.value
        assertEquals(listOf("Dois", "A Really Quite Long", "Legião Urbana"), splitOnNul(value))
    }

    /** The music text goes one byte a character (Latin-1), as the caller's name does. */
    @Test
    fun `nowPlaying writes accents as one Latin-1 byte, and what Latin-1 lacks as a question mark`() {
        val pkt = DashCommands.nowPlaying(title = "Coração", album = "Mãe → Terra", artist = "João")
        val value = outgoingTlvs(pkt).single { it.type == 5 && it.sub == 0x0D }.value
        assertEquals("one byte a character", "Mãe ? Terra".length + 1 + "Coração".length + 1 + "João".length, value.size)
        assertEquals(listOf("Mãe ? Terra", "Coração", "João"), splitOnNul(value))
        assertEquals("ç is one byte, 0xE7", 0xE7, value[value.indexOf(0xE7.toByte())].toInt() and 0xFF)
    }

    @Test
    fun `nowPlaying cuts at 19 characters, not 19 bytes, with accents`() {
        val pkt = DashCommands.nowPlaying(title = "ãããããããããããããããããããã", album = "", artist = "")
        val value = outgoingTlvs(pkt).single { it.type == 5 && it.sub == 0x0D }.value
        assertEquals(19, splitOnNul(value)[1].length)
    }

    @Test
    fun `hostnameAnnounce writes accents as one Latin-1 byte`() {
        val pkt = DashCommands.hostnameAnnounce("Moto do João")
        val text = String(pkt.copyOfRange(pkt.size - 1 - 12, pkt.size - 1), Charsets.ISO_8859_1)
        assertEquals("Moto do João", text)
        assertEquals(0, pkt[pkt.size - 1].toInt())
    }

    @Test
    fun `nowPlaying truncates each field to the dash display width and NUL-separates them`() {
        val pkt = DashCommands.nowPlaying(
            title = "A Really Quite Long Song Title That Overflows",
            album = "Album",
            artist = "Artist",
        )
        val tlvs = outgoingTlvs(pkt)
        val value = tlvs.single { it.type == 5 && it.sub == 0x0D }.value

        // Fields are NUL(0)-separated, not space-separated -- the title text itself has spaces,
        // so split on the actual separator byte rather than decoding-then-splitting on ' '.
        val parts = splitOnNul(value)

        assertEquals(3, parts.size)
        // 19 characters and the album first; changed on purpose.
        assertEquals("Album", parts[0])
        assertTrue(parts[1].length <= 19)
        assertEquals("A Really Quite Long", parts[1]) // first 19 chars of the title, truncated
        assertEquals("Artist", parts[2])
    }

    /**
     * A call goes to the dash as its state, 05 21 (0A ringing, 14 in a call, 1E calling out,
     * 32 none), with 05 4D = 32 after it; and the name in 05 22 ENCRYPTED: AES/CBC under the session key, a random IV
     * of 16 bytes first, the text in Latin-1 (a char from 255 up is 0xFF) with a NUL at its end, cut to 19 characters
     * when longer than 20. Its state packets, byte for byte:
     */
    @Test
    fun `the call state packets are the dash's`() {
        fun hex(b: ByteArray) = b.joinToString("") { "%02X".format(it) }
        assertEquals("0016000200000000020100054B31472000052100010A", hex(DashCommands.callState(DashCommands.CallState.RINGING)))
        assertEquals("0016000200000000020100054B314720000521000114", hex(DashCommands.callState(DashCommands.CallState.IN_CALL)))
        assertEquals("0016000200000000020100054B31472000052100011E", hex(DashCommands.callState(DashCommands.CallState.CALLING_OUT)))
        assertEquals("0016000200000000020100054B314720000521000132", hex(DashCommands.callState(DashCommands.CallState.NONE)))
        assertEquals("0016000200000000020100054B31472000054D000132", hex(DashCommands.callStateAfter()))
    }

    @Test
    fun `the caller's name goes encrypted under the session key`() {
        val key = ByteArray(32) { it.toByte() }
        val iv = ByteArray(16) { (0xA0 + it).toByte() }
        val value = outgoingTlvs(DashCommands.callNotify("Mãe", key, iv)).single { it.type == 5 && it.sub == 0x22 }.value
        assertArrayEquals("the IV first", iv, value.copyOfRange(0, 16))
        assertEquals("one AES block for 4 bytes", 32, value.size)
        val plain = com.reau.app.dash.DashSession.aesDecryptCbc(value, key)!!
        assertArrayEquals("Latin-1 and a NUL", byteArrayOf('M'.code.toByte(), 0xE3.toByte(), 'e'.code.toByte(), 0), plain)

        fun named(name: String) = com.reau.app.dash.DashSession.aesDecryptCbc(
            outgoingTlvs(DashCommands.callNotify(name, key, iv)).single { it.sub == 0x22 }.value, key,
        )!!.let { String(it, Charsets.ISO_8859_1).trimEnd('\u0000') }
        assertEquals("20 characters stay whole", "12345678901234567890", named("12345678901234567890"))
        assertEquals("longer ones are cut to 19", "1234567890123456789", named("123456789012345678901"))
        assertEquals("a character past Latin-1 is 0xFF", "A\u00FF", named("A\u4E2D"))
    }

    /**
     * The 1 Hz status packets carry the call state too, set from the call (0521 0A while ringing, 14 in a call, else 32,
     * then 054D 32): a fixed 32 every second would tell the dash "no call" right over its ringing card.
     */
    @Test
    fun `the status packets say the call as it is`() {
        fun call(pkt: ByteArray) = outgoingTlvs(pkt).single { it.type == 5 && it.sub == 0x21 }.value.single().toInt() and 0xFF
        assertEquals(0x32, call(DashCommands.heartbeat()))
        assertEquals(0x0A, call(DashCommands.heartbeat(call = DashCommands.CallState.RINGING)))
        assertEquals(0x14, call(DashCommands.heartbeat(call = DashCommands.CallState.IN_CALL)))
        assertEquals(0x32, call(DashCommands.metadata0030(3)))
        assertEquals(0x0A, call(DashCommands.metadata0030(3, call = DashCommands.CallState.RINGING)))
        assertEquals(0x32, outgoingTlvs(DashCommands.heartbeat(call = DashCommands.CallState.RINGING))
            .single { it.type == 5 && it.sub == 0x4D }.value.single().toInt() and 0xFF)
    }

    @Test
    fun `a fresh IV for every name`() {
        val key = ByteArray(32)
        val a = outgoingTlvs(DashCommands.callNotify("Ana", key)).single { it.sub == 0x22 }.value
        val b = outgoingTlvs(DashCommands.callNotify("Ana", key)).single { it.sub == 0x22 }.value
        assertTrue(!a.copyOfRange(0, 16).contentEquals(b.copyOfRange(0, 16)))
    }

    /**
     * The zoom state, 06 0C = 30 (both icons), goes when a route starts and after each zoom or recentre; 20 and 10
     * would show one icon each, near a zoom's two ends. Android Auto's ends are not known here: both icons, always.
     */
    @Test
    fun `the zoom icons packet is 06 0C = 30`() {
        val pkt = DashCommands.zoomIcons()
        val tlvs = outgoingTlvs(pkt)
        assertEquals(1, tlvs.size)
        assertEquals(6 to 0x0C, tlvs[0].type to tlvs[0].sub)
        assertArrayEquals(byteArrayOf(0x30), tlvs[0].value)
        val expected = "0016000200000000020100054B31472000060C000130".hexToBytes()
        assertArrayEquals("all but the sequence byte", expected.copyOfRange(0, 16), pkt.copyOfRange(0, 16))
        assertArrayEquals(expected.copyOfRange(17, expected.size), pkt.copyOfRange(17, pkt.size))
    }

    /**
     * Waze never tells Android Auto the distance left, and with none the fields stay out (05 09 when not above 0, 05 46
     * when 0), or the dash's flag says "0 m". The nav packet's counts follow; a known total still goes.
     */
    @Test
    fun `with no total distance the nav packet leaves 05 09 and 05 46 out`() {
        val known = DashCommands.activeNavPacket(maneuver = 0x14, totalDist = 230, totalUnit = DashCommands.NAV_UNIT_KM_TENTHS)
        val pkt = DashCommands.activeNavPacket(maneuver = 0x14, totalDist = 0, totalUnit = DashCommands.NAV_UNIT_METERS)
        val tlvs = outgoingTlvs(pkt)
        assertTrue(tlvs.none { it.type == 5 && (it.sub == 9 || it.sub == 0x46) })
        assertEquals(outgoingTlvs(known).filterNot { it.type == 5 && (it.sub == 9 || it.sub == 0x46) }.map { it.type to it.sub }, tlvs.map { it.type to it.sub })
        assertEquals("segment count = 1 + fields", tlvs.size + 1, ((pkt[2].toInt() and 0xFF) shl 8) or (pkt[3].toInt() and 0xFF))
        assertEquals(pkt.size, ((pkt[0].toInt() and 0xFF) shl 8) or (pkt[1].toInt() and 0xFF))
        val total = outgoingTlvs(known).single { it.type == 5 && it.sub == 9 }.value
        assertEquals("a known total goes", 230, ((total[0].toInt() and 0xFF) shl 8) or (total[1].toInt() and 0xFF))
        assertTrue(outgoingTlvs(known).any { it.type == 5 && it.sub == 0x46 })
    }

    /** The route card the same way; its template's stale secondary distance (05 05, always 0 here) goes too. */
    @Test
    fun `with no total distance the route card leaves 05 09, 05 46 and 05 05 out`() {
        val known = DashCommands.routeCard("Casa", projectionOn = true, maneuver = 0x14, totalDist = 12, totalUnit = DashCommands.NAV_UNIT_KM_TENTHS)
        for (card in listOf(
            DashCommands.routeCard("Casa", projectionOn = true, maneuver = 0x14, totalDist = 0, totalUnit = DashCommands.NAV_UNIT_METERS),
            DashCommands.routeCard("Casa", projectionOn = true, maneuver = 0x14),
            DashCommands.routeCard("Casa", projectionOn = true, maneuver = DashCommands.NAV_MANEUVER_OMIT),
        )) {
            val tlvs = outgoingTlvs(card)
            assertTrue(tlvs.none { it.type == 5 && (it.sub == 9 || it.sub == 0x46 || it.sub == 5) })
            assertEquals("segment count = 1 + fields", tlvs.size + 1, ((card[2].toInt() and 0xFF) shl 8) or (card[3].toInt() and 0xFF))
            assertEquals(card.size, ((card[0].toInt() and 0xFF) shl 8) or (card[1].toInt() and 0xFF))
            assertEquals("Casa", String(tlvs.first { it.type == 5 && it.sub == 1 }.value, Charsets.UTF_8).trimEnd('\u0000'))
        }
        val tlvs = outgoingTlvs(known)
        val total = tlvs.single { it.type == 5 && it.sub == 9 }.value
        assertEquals("a known total goes", 12, ((total[0].toInt() and 0xFF) shl 8) or (total[1].toInt() and 0xFF))
        assertEquals(DashCommands.NAV_UNIT_KM_TENTHS, tlvs.single { it.type == 5 && it.sub == 0x46 }.value.single().toInt() and 0xFF)
        assertTrue("05 05 never", tlvs.none { it.type == 5 && it.sub == 5 })
        assertEquals("segment count = 1 + fields", tlvs.size + 1, ((known[2].toInt() and 0xFF) shl 8) or (known[3].toInt() and 0xFF))
        assertEquals(known.size, ((known[0].toInt() and 0xFF) shl 8) or (known[1].toInt() and 0xFF))
    }

    // -- the time left and the maneuver after the next ------------------------------------------------------------------

    @Test
    fun `the time left is HHMMDD in ASCII with its unit byte`() {
        // days > 0 -> 30; 1 to 23 hours -> 10; otherwise (minutes only) -> 20.
        assertEquals("001000" to 0x20, DashCommands.remainingTime(600).let { it.text to it.units })
        assertEquals("013500" to 0x10, DashCommands.remainingTime(3600 + 35 * 60L).let { it.text to it.units })
        assertEquals("230000" to 0x10, DashCommands.remainingTime(23 * 3600L).let { it.text to it.units })
        assertEquals("020101" to 0x30, DashCommands.remainingTime(86_400 + 2 * 3600L + 60).let { it.text to it.units })
        assertEquals("000000" to 0x20, DashCommands.remainingTime(0).let { it.text to it.units })
    }

    @Test
    fun `the route card carries the real time left and the next maneuver when it is asked to`() {
        val pkt = DashCommands.routeCard("Waze", projectionOn = true, remainingSeconds = 3600 + 35 * 60L, nextManeuver = 0x14)
        val tlvs = outgoingTlvs(pkt)
        assertEquals("013500", String(tlvs.single { it.type == 5 && it.sub == 0x0B }.value, Charsets.US_ASCII))
        assertEquals(0x10, tlvs.single { it.type == 5 && it.sub == 0x55 }.value.single().toInt() and 0xFF)
        assertEquals(0x14, tlvs.single { it.type == 5 && it.sub == 0x03 }.value.single().toInt() and 0xFF)
        assertEquals("segment count = 1 + fields", tlvs.size + 1, ((pkt[2].toInt() and 0xFF) shl 8) or (pkt[3].toInt() and 0xFF))
    }

    @Test
    fun `with no time left known the route card leaves the two fields out`() {
        val pkt = DashCommands.routeCard("Waze", projectionOn = true, remainingSeconds = DashCommands.REMAINING_OMIT, nextManeuver = DashCommands.NEXT_MANEUVER_NONE)
        val tlvs = outgoingTlvs(pkt)
        assertTrue(tlvs.none { it.type == 5 && it.sub == 0x0B })
        assertTrue(tlvs.none { it.type == 5 && it.sub == 0x55 })
        assertEquals(0xFF, tlvs.single { it.type == 5 && it.sub == 0x03 }.value.single().toInt() and 0xFF)
        assertEquals("segment count = 1 + fields", tlvs.size + 1, ((pkt[2].toInt() and 0xFF) shl 8) or (pkt[3].toInt() and 0xFF))
        assertEquals(pkt.size, ((pkt[0].toInt() and 0xFF) shl 8) or (pkt[1].toInt() and 0xFF))
    }

    @Test
    fun `asked for nothing, the route card is the template as it always was`() {
        val old = DashCommands.routeCard("Waze", projectionOn = true)
        val tlvs = outgoingTlvs(old)
        assertEquals("001000", String(tlvs.single { it.type == 5 && it.sub == 0x0B }.value, Charsets.US_ASCII))
        assertEquals(0x34, tlvs.single { it.type == 5 && it.sub == 0x03 }.value.single().toInt() and 0xFF)
    }

    @Test
    fun `the nav packet carries the maneuver after the next only when asked`() {
        val without = outgoingTlvs(DashCommands.activeNavPacket())
        assertTrue(without.none { it.type == 5 && it.sub == 0x03 })
        val pkt = DashCommands.activeNavPacket(nextManeuver = 0x15)
        val tlvs = outgoingTlvs(pkt)
        assertEquals(0x15, tlvs.single { it.type == 5 && it.sub == 0x03 }.value.single().toInt() and 0xFF)
        assertEquals(without.size + 1, tlvs.size)
        assertEquals(tlvs.size + 1, ((pkt[2].toInt() and 0xFF) shl 8) or (pkt[3].toInt() and 0xFF))
        assertEquals(pkt.size, ((pkt[0].toInt() and 0xFF) shl 8) or (pkt[1].toInt() and 0xFF))
    }

    // -- the arrival time Waze says reaches the card, and the proximity bar is the nav packet's ----------------------

    private fun etaOf(pkt: ByteArray) = String(outgoingTlvs(pkt).single { it.type == 5 && it.sub == 0x08 }.value, Charsets.US_ASCII)

    @Test
    fun `the arrival time as Waze says it, with a colon, is the four digits of the card`() {
        assertEquals("0801", etaOf(DashCommands.routeCard("Waze", projectionOn = true, etaHHMM = "08:01")))
        assertEquals("0905", etaOf(DashCommands.routeCard("Waze", projectionOn = true, etaHHMM = "9:05")))
        assertEquals("1845", etaOf(DashCommands.routeCard("Waze", projectionOn = true, etaHHMM = "1845")))
        assertEquals("2359", etaOf(DashCommands.routeCard("Waze", projectionOn = true, etaHHMM = "23:59")))
    }

    @Test
    fun `an arrival time that is no time leaves the template's, as always`() {
        for (bad in listOf("", "soon", "25:99", "8:5", "12345", "ab:cd")) {
            assertEquals(bad, "0303", etaOf(DashCommands.routeCard("Waze", projectionOn = true, etaHHMM = bad)))
        }
        assertEquals("0303", etaOf(DashCommands.routeCard("Waze", projectionOn = true)))
    }

    @Test
    fun `the route card carries the proximity bar it is given, and the template's 04 when none`() {
        fun bar(pkt: ByteArray) = outgoingTlvs(pkt).single { it.type == 5 && it.sub == 0x0C }.value.single().toInt() and 0xFF
        assertEquals(6, bar(DashCommands.routeCard("Waze", projectionOn = true, proximity = 6)))
        assertEquals(5, bar(DashCommands.routeCard("Waze", projectionOn = true, proximity = 5)))
        assertEquals(4, bar(DashCommands.routeCard("Waze", projectionOn = true)))
    }

    // -- helpers -------------------------------------------------------------

    /** Parses a K1GPacket.build()-shaped packet's TLVs (segments start at offset 17, after fixed header + seq). */
    private fun outgoingTlvs(pkt: ByteArray): List<Tlv> {
        val tlvs = mutableListOf<Tlv>()
        var i = 17
        while (i + 4 <= pkt.size) {
            val type = pkt[i].toInt() and 0xFF
            val sub = pkt[i + 1].toInt() and 0xFF
            val len = ((pkt[i + 2].toInt() and 0xFF) shl 8) or (pkt[i + 3].toInt() and 0xFF)
            i += 4
            val end = (i + len).coerceAtMost(pkt.size)
            tlvs += Tlv(type, sub, pkt.copyOfRange(i, end))
            i = end
        }
        return tlvs
    }

    /** Splits a 05 0d value on its NUL separators, decoding each field as UTF-8. */
    private fun splitOnNul(value: ByteArray): List<String> {
        val parts = mutableListOf<String>()
        var start = 0
        for (i in value.indices) {
            if (value[i].toInt() == 0) {
                parts += String(value.copyOfRange(start, i), Charsets.ISO_8859_1)
                start = i + 1
            }
        }
        parts += String(value.copyOfRange(start, value.size), Charsets.ISO_8859_1)
        return parts
    }

    private fun indexOfBytes(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    @Test
    fun `the version query is the one the initial burst sends, apart from the seq byte`() {
        val inBurst = DashCommands.initialBurst("x")[7]
        val query = DashCommands.versionQuery()
        // the byte after "K1G " is the rolling seq, patched at send time
        val seq = 16
        assertEquals(inBurst.size, query.size)
        assertArrayEquals(inBurst.copyOf().also { it[seq] = 0 }, query.copyOf().also { it[seq] = 0 })
        assertEquals("0A 02, 8 bytes: AA 55 and six zeros", listOf(0x0A, 0x02), listOf(query[17].toInt(), query[18].toInt()))
    }
}

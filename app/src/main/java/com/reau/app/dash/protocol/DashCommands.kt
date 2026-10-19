package com.reau.app.dash.protocol

import java.io.ByteArrayOutputStream

/**
 * K1G control-plane commands. The packet layouts are interoperability facts, cross-checked
 * against the open-source better-dash reference (Apache-2.0) and against traffic observed
 * on firmware 11.63; some builders are ported from better-dash and say so where they are
 * (see NOTICE for what comes from each project).
 */
@Suppress("TooManyFunctions") // a flat catalog: one builder per K1G packet
object DashCommands {

    // ── Auth ──────────────────────────────────────────────────────────────
    /** "Request auth / send me your RSA public key". */
    fun authRequest() = "0016000200000000020100054b314720000804000101".hexToBytes()

    /** The session key: RSA-encrypted (SSID ‖ AES-256 key). Ciphertext must be 128 B. */
    fun authSendKey(ciphertext: ByteArray): ByteArray {
        require(ciphertext.size == 128) { "the session key expects 128B RSA ciphertext, got ${ciphertext.size}" }
        return "0095000200000000020100054B3147200008000080".hexToBytes() + ciphertext
    }

    // ── Initial burst (sent right after the socket opens) ─────────────────
    // The dash only answers with its RSA pubkey on UDP/2002 after seeing
    // this burst on UDP/2000. The order and the content are those of the captured burst.
    // [status] (sendRealPhoneStatus on): the captured status packet at the end carries a fixed 62%
    // battery and full signal; with a status those fields are patched with the phone's real ones,
    // so every (re)connect stops flashing "62%" on the dash before the first heartbeat lands.
    fun initialBurst(hostname: String, status: PhoneStatus? = null): List<ByteArray> = listOf(
        authRequest(),
        hostnameAnnounce(hostname),
        timeSync(),
        "0016000200000000020100054b314720030557000155".hexToBytes(),
        "0016000200000000020100054b3147200405560001aa".hexToBytes(),
        "0016000200000000020100054b3147200506050001aa".hexToBytes(),
        "0016000200000000020100054b3147200605170001aa".hexToBytes(),
        "001d000200000000020100054b314720080a020008aa55000000000000".hexToBytes(),
        ("0044000a00000000020100054b3147200906080001ff060300015506040001a2060f0001aa" +
         "0601000101054c000113052d00020000051b0001190521000132054d000132").hexToBytes()
            .also { pkt -> status?.let { applyStatus(pkt, it) } },
    )

    /**
     * 0A 02 — "read the version": the only request the app makes of the dash for its data (the burst above sends the
     * same one at every connection). The dash answers with the firmware partitions (`0B 02`);
     * the settings (group 03) and the bike's state (group 0C) it says by itself. DashSession asks again every
     * [DashSession.DATA_REFRESH_TICKS] s while connected, so the screen "Dados da moto" stays fresh.
     */
    fun versionQuery(): ByteArray =
        K1GPacket.build(K1GPacket.tlv(0x0A, 0x02, 0xAA, 0x55, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))

    /**
     * 06 06 — time-of-day sync (hour, minute, second). The dash has no clock source
     * of its own: it shows whatever the phone last fed it, so the dash keeps
     * feeding it. better-dash replays a hardcoded capture time (0x0e 0x33 0x34 =
     * 14:51:52) once, which left the dash clock wrong AND frozen. Build it from the
     * real clock; DashSession re-sends it every 30 s.
     */
    fun timeSync(): ByteArray {
        val cal = java.util.Calendar.getInstance()
        return K1GPacket.build(
            K1GPacket.tlv(
                0x06, 0x06,
                cal.get(java.util.Calendar.HOUR_OF_DAY),
                cal.get(java.util.Calendar.MINUTE),
                cal.get(java.util.Calendar.SECOND),
            )
        )
    }

    /** Device announce — device name shown on the dash's "Connected to X" screen. */
    fun hostnameAnnounce(hostname: String): ByteArray {
        // Latin-1, one byte a character, which is how the dash reads names; 200 characters at most.
        val raw = latin1(hostname, HOSTNAME_MAX)
        val out = ByteArrayOutputStream()
        out.write("0021000200000000020100054b314720".hexToBytes())
        out.write(byteArrayOf(0x01, 0x06, 0x0B, 0x00, (raw.size + 1).toByte()))
        out.write(raw)
        out.write(0x00)
        val bytes = out.toByteArray()
        bytes[0] = ((bytes.size shr 8) and 0xFF).toByte()
        bytes[1] = (bytes.size and 0xFF).toByte()
        return bytes
    }

    // ── Navigation mode ────────────────────────────────────────────────────
    /** Nav context. */
    fun navContext() = "0016000200000000020100054B31472000052E00011E".hexToBytes()

    /** Empty favourite lists. */
    fun emptyLists() =
        "002A000600000000020100054B31472000052F0001000530000100053100010005320001000533000100"
            .hexToBytes()

    /** Start navigation. Send ONCE, after the route card. */
    fun navStart() = "0016000200000000020100054B31472000068000010B".hexToBytes()

    /** Placeholder sent between the projection frame and the start of navigation in the captured sequence. */
    fun navPlaceholder() = K1GPacket.build(K1GPacket.tlv(0x06, 0x0A, 0x00, 0x00))

    // ── Projection control ────────────────────────────────────────────────
    /** Projection keep-alive; MUST repeat at the encoder frame rate (4 Hz). */
    fun projectionFrame() = "0016000200000000020100054B314720000556000155".hexToBytes()
    fun projectionOn()    = "0016000200000000020100054B314720000605000155".hexToBytes()
    fun projectionStop()  = "0016000200000000020100054B3147200005560001AA".hexToBytes()
    fun projectionOff()   = "0016000200000000020100054B3147200006050001AA".hexToBytes()

    // ── Frame-decoded acknowledgements ────────────────────────────────────
    /** Mandatory reply to the dash's 09 06 55 per-IDR "frame decoded" notify. */
    fun frameDecodedIdr() = "0016000200000000020100054B314720000611000155".hexToBytes()
    /** Reply to 09 04 55. */
    fun frameDecodedP()   = "0016000200000000020100054B314720000612000155".hexToBytes()

    // ── Button / event acknowledgement: echo the code back in 06 80 ───────
    fun buttonAck(code: Byte) =
        K1GPacket.build(K1GPacket.tlv(0x06, 0x80, code.toInt() and 0xFF))

    /**
     * The zoom state, sent when a route starts and after each zoom or recentre, so the dash's "ZOOM" shows its "-" and
     * "+": 06 0C = 30, both icons. 20 and 10 would show one icon each, near a zoom's two ends, which Android Auto's are
     * not known here.
     */
    fun zoomIcons(): ByteArray = K1GPacket.build(K1GPacket.tlv(0x06, 0x0C, ZOOM_BOTH_ICONS))

    private const val ZOOM_BOTH_ICONS = 0x30

    // ── Extended exit/teardown sequence (unverified on real hardware) ─────
    // Another open implementation of this link documents an exit sequence beyond
    // projectionStop()/projectionOff():
    // a BACK/exit event (06 80 = 0x12 — the same "06 80" shape as buttonAck, but the code the
    // dash's own UI sends for BACK, not one we received), then 06 05 = 0xAA x4 (projection
    // active->idle) and 05 17 = 0xAA x2 (a session-boundary marker). Their hypothesis: without
    // this, the dash can stay "hung" until a power-cycle. Sent best-effort, in ADDITION to (not
    // instead of) the existing projectionStop/projectionOff — never confirmed against a real
    // dash by either project, so this is deliberately extra, not a replacement.
    fun exitBack()          = K1GPacket.build(K1GPacket.tlv(0x06, 0x80, 0x12))
    fun exitProjectionIdle() = K1GPacket.build(K1GPacket.tlv(0x06, 0x05, 0xAA))
    fun exitSessionBoundary() = K1GPacket.build(K1GPacket.tlv(0x05, 0x17, 0xAA))

    // Joystick codes seen in the 09 00 event family are mapped in
    // com.reau.app.dash.protocol.DashButtonEvent — see that class for the single
    // source of truth (this used to duplicate a divergent copy of the same codes, unused).

    // ── 1 Hz status heartbeat (0049, fixed temp) ──────────────────────────
    private val HB_0049 =
        ("0049000b00000000020100054b3147200006080001050610000139060300015506040001a2060f0001aa" +
         "0601000101054c000113052d00020000051b0001190521000132054d000132").hexToBytes()

    /**
     * Phone-derived fields for the 1 Hz heartbeat, behind the rider's switch "Mandar o estado do celular ao painel"
     * (on by default). Without a status, [heartbeat] always sends the same
     * placeholder bytes it always has (battery 62%, not charging, GPS on, signal present) — the values of the capture
     * the template came from, which the dash showed as if they were the phone's. The byte encodings match this captured
     * heartbeat's own placeholder bytes below (a2=98=162-100=62%, aa=not charging, 55=GPS on, 01=signal present).
     *
     * There is NO signal-strength field: `06 08` is the WEATHER icon (1 cloudy, 2 thunder, 3 rain, 4 snow, 5 sun, 0xFF no
     * data), which is [Weather]'s, not this one's; it was labelled "signal strength" before and a 0..255 byte there would
     * have drawn some random weather.
     */
    data class PhoneStatus(
        /** 0..100 — on wire as value+100 (06 04). Out-of-range values are clamped, never wrapped. */
        val batteryPercent: Int,
        /** 06 0F: 0x55 charging (or full) / 0xAA not. */
        val charging: Boolean,
        /** 06 03: 0x55 the phone's GPS is on / 0xAA off (LocationManager.isProviderEnabled("gps")). */
        val gpsOn: Boolean,
        /** 06 01: 0x01 the phone has cell signal (level above 0) / 0x00 none. */
        val signalPresent: Boolean,
    )

    /**
     * The weather for the heartbeat (behind the switch "Clima no painel"): [tempC] in
     * degrees Celsius (`06 10`, on the wire as value + 40) and [icon] (`06 08`: 1 cloudy, 2 thunder, 3 rain, 4 snow,
     * 5 sun). With none, [heartbeat] says "no data": no `06 10` and the icon [WEATHER_NONE].
     */
    data class Weather(val tempC: Int, val icon: Int)

    /** `06 08` when there is no weather ("no data"; the captured burst's last packet says it too). */
    const val WEATHER_NONE = 0xFF

    /**
     * The 1 Hz status heartbeat — on-wire temp byte = °C + 40.
     *
     * [status] is additive and defaults to null: passing nothing keeps the capture's own battery, GPS and signal.
     * [weather] null: no `06 10` at all and the icon "no data", instead of the capture's 25 degrees and sun,
     * which were never true.
     */
    fun heartbeat(weather: Weather? = null, status: PhoneStatus? = null, call: CallState = CallState.NONE): ByteArray {
        val pkt = HB_0049.copyOf()
        patchHeartbeatByte(pkt, 0x06, 0x08, weather?.icon ?: WEATHER_NONE)
        if (weather != null) patchHeartbeatByte(pkt, 0x06, 0x10, weather.tempC.coerceIn(-40, 100) + 40)
        if (status != null) applyStatus(pkt, status)
        // The call as it is (a fixed "no call" every second hid the ringing from the dash).
        patchHeartbeatByte(pkt, 0x05, 0x21, call.code)
        return if (weather == null) withoutTlv(pkt, 0x06, 0x10, 1) else pkt
    }

    /** Patches the phone-derived fields present in [pkt]; a field the packet doesn't carry is skipped. */
    private fun applyStatus(pkt: ByteArray, status: PhoneStatus) {
        patchHeartbeatByte(pkt, 0x06, 0x04, status.batteryPercent.coerceIn(0, 100) + 100)
        patchHeartbeatByte(pkt, 0x06, 0x0F, if (status.charging) 0x55 else 0xAA)
        patchHeartbeatByte(pkt, 0x06, 0x03, if (status.gpsOn) 0x55 else 0xAA)
        patchHeartbeatByte(pkt, 0x06, 0x01, if (status.signalPresent) 0x01 else 0x00)
    }

    /**
     * A second 1 Hz status packet the dash accepts (better-dash documents it): the weather icon (`06 08`, [weatherIcon]: 1 cloudy, 2 thunder, 3 rain, 4 snow, 5 sun,
     * 0xFF no data) plus the same music-volume, distance, alarm-volume and idle-call fields the heartbeat already
     * carries, with those same values. REau does not send it: its heartbeat already carries the icon.
     */
    fun metadata0030(weatherIcon: Int, call: CallState = CallState.NONE): ByteArray {
        val pkt = ("0030000600000000020100054b31472000" + "0608000100" +
            "054c000113052d00020000051b0001190521000132054d000132").hexToBytes()
        patchHeartbeatByte(pkt, 0x06, 0x08, weatherIcon.coerceIn(0, 255))
        patchHeartbeatByte(pkt, 0x05, 0x21, call.code) // as in the heartbeat
        return pkt
    }

    private fun patchHeartbeatByte(pkt: ByteArray, type: Int, sub: Int, value: Int) {
        val marker = byteArrayOf(type.toByte(), sub.toByte(), 0x00, 0x01)
        val i = indexOf(pkt, marker)
        if (i >= 0 && i + 4 < pkt.size) pkt[i + 4] = (value and 0xFF).toByte()
    }

    /**
     * The four digits of an arrival time for the card (05 08): "1845" as it is, or the clock as Waze says it ("08:01", "9:05"); anything
     * that is no time of day is null. Waze says the second form; read as the first only, the card would keep the
     * template's "03:03" for the whole ride.
     */
    fun etaDigits(text: String?): String? {
        if (text == null) return null
        val (h, m) = (ETA_PLAIN.matchEntire(text) ?: ETA_CLOCK.matchEntire(text))?.destructured ?: return null
        if (h.toInt() > ETA_MAX_HOUR || m.toInt() > ETA_MAX_MINUTE) return null
        return h.padStart(2, '0') + m
    }

    private val ETA_PLAIN = Regex("""(\d{2})(\d{2})""")
    private val ETA_CLOCK = Regex("""(\d{1,2}):(\d{2})""")
    private const val ETA_MAX_HOUR = 23
    private const val ETA_MAX_MINUTE = 59

    /** The time left as the dash reads it (05 0B, 05 55): [text] is HHMMDD in ASCII, [units] 0x10, 0x20 or 0x30. */
    data class Remaining(val text: String, val units: Int)

    /** "No time left" for [routeCard]: both fields stay out when the time is not known. */
    const val REMAINING_OMIT = -1L

    /** The maneuver after the next, when there is none (05 03): 0xFF. */
    const val NEXT_MANEUVER_NONE = 0xFF

    private const val SECONDS_PER_DAY = 86_400L
    private const val SECONDS_PER_HOUR = 3_600L
    private const val SECONDS_PER_MINUTE = 60L
    private const val HOURS_PER_DAY = 24
    private const val UNITS_DAYS = 0x30
    private const val UNITS_HOURS = 0x10
    private const val UNITS_MINUTES = 0x20

    /**
     * The time left [seconds] as the dash reads it: two digits each of hours, minutes and days,
     * and the unit byte: 0x30 with a day or more, 0x10 with one to 23 hours, 0x20 with minutes only.
     */
    fun remainingTime(seconds: Long): Remaining {
        val days = seconds / SECONDS_PER_DAY
        val hours = ((seconds % SECONDS_PER_DAY) / SECONDS_PER_HOUR).toInt()
        val minutes = ((seconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE).toInt()
        val units = when {
            days > 0 -> UNITS_DAYS
            hours in 1 until HOURS_PER_DAY -> UNITS_HOURS
            else -> UNITS_MINUTES
        }
        return Remaining("%02d%02d%02d".format(hours, minutes, days.coerceAtMost(99)), units)
    }

    // ── Route card (0x007E) ───────────────────────────────────────────────
    // Template of the dash's route card: K1G header + TLV(05,01,title+NUL) (the title is a placeholder, replaced on send)
    // + a fixed suffix of sub-TLVs. The byte after marker 06 05 00 01 is the
    // projection flag (0x55 on / 0xAA off).
    private val NAV_TEMPLATE =
        ("007e001100000000020100054b314720250501001444657374696e6f20646520746573746520303100" +
         "050200013c050300013405050002000a05060001300507000130050800043033303305540001300509" +
         "0002004f0546000110050a000155050c000104050b0006303031303030055500012006050001aa060d0001aa")
            .hexToBytes()

    private val navPrefix: ByteArray  // header up to (not incl.) the seq byte
    private val navSuffix: ByteArray  // everything after the title TLV

    init {
        val magic = indexOf(NAV_TEMPLATE, "4b314720".hexToBytes())
        require(magic >= 0) { "K1G marker missing in nav template" }
        val seqOff = magic + 4
        val titleLen = ((NAV_TEMPLATE[seqOff + 3].toInt() and 0xFF) shl 8) or
                        (NAV_TEMPLATE[seqOff + 4].toInt() and 0xFF)
        navPrefix = NAV_TEMPLATE.copyOfRange(0, seqOff)
        navSuffix = NAV_TEMPLATE.copyOfRange(seqOff + 5 + titleLen, NAV_TEMPLATE.size)
    }

    /**
     * Full 0x007E route card. Must be sent BEFORE navStart (sets the destination the
     * dash needs to open its decoder), then re-sent at ~1 Hz while streaming
     * or the dash's destination watchdog tears the decoder down after ~15 s.
     *
     * The template's nav fields carry placeholder values — total distance 0x004F
     * (79 km-tenths = "7.9 km"), glyph 0x3C, ETA "03:03". Re-sending them unpatched
     * at 1 Hz stomps the live figures from [activeNavPacket]. Pass the live values to
     * overwrite them (field meanings per better-dash: 0502 glyph, 0506 unit, 0508 ETA
     * HH:MM ASCII, 0509 total, 0546 total unit).
     */
    // one branch per field or case of the format it reads or writes; the parameters are the fields of what it builds (wire format / the data it carries)
    @Suppress("CyclomaticComplexMethod", "LongParameterList")
    fun routeCard(
        title: String,
        projectionOn: Boolean = false,
        maneuver: Int? = null,
        primaryUnit: Int? = null,
        totalDist: Int? = null,
        totalUnit: Int? = null,
        etaHHMM: String? = null,   // 4 ASCII digits, e.g. "1845"
        decimalComma: Boolean = false,
        /** The time left in seconds (05 0B, 05 55); null keeps the template's, [REMAINING_OMIT] leaves both fields
         *  out. */
        remainingSeconds: Long? = null,
        /** The maneuver after the next (05 03), [NEXT_MANEUVER_NONE] for none; null keeps the template's. */
        nextManeuver: Int? = null,
        /** The proximity bar (05 0C), the one the nav packet carries; null keeps the template's (the card with no route). */
        proximity: Int? = null,
    ): ByteArray {
        // Latin-1, how the dash reads every name it draws (the street's name goes here too).
        val rt = latin1(title, 60) + 0x00.toByte()

        val out = ByteArrayOutputStream()
        out.write(navPrefix)
        out.write(0x00)                                  // seq, patched at send
        out.write(byteArrayOf(0x05, 0x01))
        out.write((rt.size shr 8) and 0xFF)
        out.write(rt.size and 0xFF)
        out.write(rt)
        out.write(navSuffix)

        val bytes = out.toByteArray()
        // All patched fields live in the suffix (after the title), so search from
        // the end — the title text can never collide with a marker that way.
        fun patch1(t: Int, s: Int, v: Int) {
            val m = indexOf(bytes, byteArrayOf(t.toByte(), s.toByte(), 0x00, 0x01), fromEnd = true)
            if (m >= 0 && m + 4 < bytes.size) bytes[m + 4] = (v and 0xFF).toByte()
        }
        fun patch2(t: Int, s: Int, v: Int) {
            val m = indexOf(bytes, byteArrayOf(t.toByte(), s.toByte(), 0x00, 0x02), fromEnd = true)
            if (m >= 0 && m + 5 < bytes.size) {
                bytes[m + 4] = ((v shr 8) and 0xFF).toByte()
                bytes[m + 5] = (v and 0xFF).toByte()
            }
        }
        patch1(0x06, 0x05, if (projectionOn) 0x55 else 0xAA)
        // Same separator as the nav packet says, or the two (both sent every second) would flip it.
        patch1(0x05, 0x0A, if (decimalComma) DECIMAL_COMMA else DECIMAL_POINT)
        maneuver?.takeIf { it != NAV_MANEUVER_OMIT }?.let { patch1(0x05, 0x02, it) }
        primaryUnit?.let { patch1(0x05, 0x06, it) }
        nextManeuver?.let { patch1(0x05, 0x03, it) }
        proximity?.let { patch1(0x05, 0x0C, it) }
        remainingSeconds?.takeIf { it >= 0 }?.let { patchRemaining(bytes, remainingTime(it)) }
        // The template carries placeholder figures (total 0x004F =
        // "7.9 km", secondary 0x000A). Zero them by DEFAULT so a card sent with no live
        // route shows 0, not a bogus "7.9 km". Live callers pass real values.
        patch2(0x05, 0x09, totalDist ?: 0)
        totalUnit?.let { patch1(0x05, 0x46, it) }
        etaDigits(etaHHMM)?.let { eta ->
            val m = indexOf(bytes, byteArrayOf(0x05, 0x08, 0x00, 0x04), fromEnd = true)
            if (m >= 0 && m + 8 < bytes.size) {
                for (i in 0 until 4) bytes[m + 4 + i] = eta[i].code.toByte()
            }
        }
        bytes[0] = ((bytes.size shr 8) and 0xFF).toByte()
        bytes[1] = (bytes.size and 0xFF).toByte()
        // No total distance, no total fields; the secondary distance (05 05, never known here) always goes out.
        var card = withoutTlv(bytes, 0x05, 0x05, 2)
        if ((totalDist ?: 0) <= 0) card = withoutTlv(withoutTlv(card, 0x05, 0x09, 2), 0x05, 0x46, 1)
        if (remainingSeconds != null && remainingSeconds < 0) card = withoutTlv(withoutTlv(card, 0x05, 0x0B, 6), 0x05, 0x55, 1)
        return if (maneuver == NAV_MANEUVER_OMIT) withoutTlv(card, 0x05, 0x02, 1) else card
    }

    /** The template's time left (05 0B, six ASCII digits) and its unit (05 55) replaced by [left]. */
    private fun patchRemaining(bytes: ByteArray, left: Remaining) {
        val time = indexOf(bytes, byteArrayOf(0x05, 0x0B, 0x00, 0x06), fromEnd = true)
        if (time >= 0 && time + 9 < bytes.size) left.text.forEachIndexed { i, c -> bytes[time + 4 + i] = c.code.toByte() }
        val unit = indexOf(bytes, byteArrayOf(0x05, 0x55, 0x00, 0x01), fromEnd = true)
        if (unit >= 0 && unit + 4 < bytes.size) bytes[unit + 4] = left.units.toByte()
    }

    // ── Active navigation info (0x007E-family, ~1 Hz while guiding) ───────
    // Ported from better-dash build_active_nav_packet. Drives the dash's
    // instruction bubble: primary maneuver glyph + distance-to-turn + total.
    /** Straight arrow. NOT 0x0B (draws roundabout exit 1): see [com.reau.app.dash.nav.DashManeuverGlyphs]. */
    const val NAV_MANEUVER_CONTINUE = 0x09

    /** Hides the nav bubble entirely (any byte from 0x5A up does, field-verified on another project's dash): "no maneuver". */
    const val NAV_MANEUVER_NONE = 0xFF
    /**
     * No glyph field at all for a route with no maneuver the dash can draw (05 02 left out), which hides the ring; it
     * never sends [NAV_MANEUVER_NONE].
     */
    const val NAV_MANEUVER_OMIT = -1

    /**
     * The route card sent at 1 Hz while there is NO active guidance: the captured template's own glyph (0x3C, an arrival pin) would
     * sit in the bubble for as long as the panel is connected, so the bubble is hidden instead.
     */
    fun standbyRouteCard(title: String, projectionOn: Boolean): ByteArray =
        routeCard(title, projectionOn, maneuver = NAV_MANEUVER_NONE)

    /** The captured template's own glyph, an arrival pin: what the dash showed while its joystick worked with no route. */
    const val NAV_MANEUVER_ARRIVAL_PIN = 0x3C

    /**
     * The route card sent at 1 Hz while there is no route, as [choice] says ([com.reau.app.dash.DashIdleCard]):
     * the arrival pin with [clockHHMM] as the ETA (the dash keeps passing its joystick on in its map mode), or the hidden
     * bubble.
     */
    fun idleRouteCard(choice: com.reau.app.dash.DashIdleCard, title: String, projectionOn: Boolean, clockHHMM: String): ByteArray =
        when (choice) {
            com.reau.app.dash.DashIdleCard.PIN -> routeCard(title, projectionOn, maneuver = NAV_MANEUVER_ARRIVAL_PIN, etaHHMM = clockHHMM)
            com.reau.app.dash.DashIdleCard.HIDDEN -> standbyRouteCard(title, projectionOn)
        }
    const val NAV_UNIT_KM_TENTHS = 0x10   // distance field = km × 10
    const val NAV_UNIT_METERS    = 0x30
    private const val NAV_HDR = "00000000020100054B31472000"

    /**
     * @param maneuver  dash glyph code (0x09 = straight ahead; every byte: [com.reau.app.dash.nav.DashManeuverGlyphs])
     * @param primaryDistM  distance to next turn (metres if [primaryUnit]=METERS,
     *                      or km×10 if KM_TENTHS)
     * @param totalDistM    remaining distance, same unit convention via [totalUnit]
     */
    @Suppress("LongParameterList") // the parameters are the fields of what it builds (wire format / the data it carries)
    fun activeNavPacket(
        maneuver: Int = NAV_MANEUVER_CONTINUE,
        primaryDist: Int = 500,
        primaryUnit: Int = NAV_UNIT_METERS,
        totalDist: Int = 500,
        totalUnit: Int = NAV_UNIT_METERS,
        projectionOn: Boolean = true,
        road: String = "",
        decimalComma: Boolean = false,
        proximity: Int? = null,
        /** The maneuver after the next (05 03, 0xFF for none); null leaves the field out. */
        nextManeuver: Int? = null,
    ): ByteArray {
        fun u16(v: Int) = "%04X".format(v and 0xFFFF)
        fun u8(v: Int) = "%02X".format(v and 0xFF)
        val tlvs = StringBuilder()
        var segments = 1 // the segment count counts the header too
        fun put(hex: String) {
            tlvs.append(hex)
            segments++
        }
        // The street of the next maneuver, first: 05 01, Latin-1, 19 characters at
        // most, ended by a zero. Only the dash hears it: it never reaches the ride log.
        val street = if (road.isBlank()) null else latin1(road.trim(), ROAD_MAX) + 0x00.toByte()
        if (street != null) put("0501" + u16(street.size) + street.joinToString("") { u8(it.toInt()) })
        if (maneuver != NAV_MANEUVER_OMIT) put("05020001" + u8(maneuver)) // primary maneuver (or none)
        if (nextManeuver != null) put("05030001" + u8(nextManeuver))      // the maneuver after the next
        put("05040002" + u16(primaryDist))  // primary distance
        put("05060001" + u8(primaryUnit))   // primary unit
        // Waze never says the distance left; with none the two fields stay out, so the dash's flag does not say "0 m".
        if (totalDist > 0) {
            put("05090002" + u16(totalDist))    // total distance
            put("05460001" + u8(totalUnit))     // total unit
        }
        put("050A0001" + u8(if (decimalComma) DECIMAL_COMMA else DECIMAL_POINT)) // decimal separator
        // The proximity bar beside the glyph (05 0C), 0..6, as [proximityLevel] says; none, no TLV.
        if (proximity != null) put("050C0001" + u8(proximity))
        put("06050001" + if (projectionOn) "55" else "AA") // projection flag
        put("060D0001AA")                                   // no intermediate stops (06 0D)

        val innerHex = "%04X".format(segments) + NAV_HDR + tlvs.toString()
        val innerBytes = innerHex.length / 2
        val outerLen = innerBytes + 2
        return ("%04X".format(outerLen) + innerHex).hexToBytes()
    }

    /** The decimal separator's byte (05 0A): `AA` a comma, `55` a point. */
    private const val DECIMAL_COMMA = 0xAA
    private const val DECIMAL_POINT = 0x55

    /**
     * The proximity bar the dash draws beside the glyph (05 0C): from the distance to the turn in METRES
     * whatever the unit shown: 4 up to 20 m, 3 to 40, 2 to 60, 1 to 80, 0 to 100 (the bar fills as the turn nears), 6 up
     * to 500, 5 beyond; a keep-left/keep-right is 4 from 21 to 100 m.
     */
    fun proximityLevel(meters: Int, keep: Boolean = false): Int = when {
        meters <= 20 -> 4
        meters <= 100 -> if (keep) 4 else 3 - (meters - 21) / 20
        meters <= 500 -> 6
        else -> 5
    }

    /** The street's name in the nav packet: 19 characters, what the dash's field holds. */
    const val ROAD_MAX = 19

    /**
     * [text] as the dash reads names: ISO-8859-1, one byte a character, [max] characters at most; what Latin-1 has no
     * letter for is a question mark (the low byte of such a character would draw something else). Never reaches the
     * log.
     */
    fun latin1(text: String, max: Int): ByteArray {
        val chars = text.take(max)
        return ByteArray(chars.length) { i -> chars[i].code.let { if (it < 0x100) it.toByte() else '?'.code.toByte() } }
    }

    /**
     * The album's picture for the music card (05 40): [jpeg] (75x75, quality 50) in slices
     * of at most [COVER_SLICE] bytes, each its own K1G packet with one TLV 05 40. The first slice's value is
     * `[0][total size, 2 bytes][data]`, the next ones `[index][data]`, and the last one has index 0xFF; a picture that fits
     * one slice is followed by an empty 0xFF slice. The caller waits [COVER_PAUSE_MS] between packets.
     */
    fun albumArtChunks(jpeg: ByteArray): List<ByteArray> {
        if (jpeg.isEmpty() || jpeg.size > MAX_COVER_BYTES) return emptyList()
        val pieces = jpeg.asList().chunked(COVER_SLICE).map { it.toByteArray() }.toMutableList()
        val values = mutableListOf<ByteArray>()
        values += byteArrayOf(0, (jpeg.size shr 8).toByte(), jpeg.size.toByte()) + pieces[0]
        if (pieces.size == 1) {
            values += byteArrayOf(LAST_SLICE)
        } else {
            for (i in 1 until pieces.size - 1) values += byteArrayOf(i.toByte()) + pieces[i]
            values += byteArrayOf(LAST_SLICE) + pieces.last()
        }
        return values.map { K1GPacket.build(K1GPacket.tlv(0x05, 0x40, it)) }
    }

    /** Data bytes in one slice of the picture. */
    const val COVER_SLICE = 1_000

    /** The pause between two slices. */
    const val COVER_PAUSE_MS = 10L

    /** A 75x75 JPEG at quality 50 is a few kilobytes; a larger one is not what the dash expects (and the size field is 2 bytes). */
    const val MAX_COVER_BYTES = 0xFFFF
    private const val LAST_SLICE: Byte = 0xFF.toByte()

    // ── Media now-playing (05 0d) + telephony (05 22) ─────────────────────
    // The packet layout:
    //   05 0d value = three NUL-separated text fields [album]\0[title]\0[artist], each cut at 19 characters,
    //   with the flags 05 17 (music on / none), 05 19 (playing / paused) and 05 58 (album art follows / none) around it.
    //   05 22 value = caller display name, NUL-terminated, re-sent ~1 Hz while ringing.
    // K1GPacket.build emits the exact captured frame: [len][segcount][..]"K1G "[seq] TT SS LL value.

    private const val MEDIA_FIELD_MAX = 19   // each field is cut at 19 characters
    private const val HOSTNAME_MAX = 200

    // Latin-1: each character in one byte, as the dash reads it; UTF-8 gave "ç" two bytes.
    private fun mediaField(s: String): ByteArray = latin1(s, MEDIA_FIELD_MAX)

    /** 05 0d now-playing card: album / title / artist, NUL-separated, in that order. */
    fun nowPlaying(title: String, album: String, artist: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(mediaField(album)); out.write(0x00)
        out.write(mediaField(title)); out.write(0x00)
        out.write(mediaField(artist))
        return K1GPacket.build(K1GPacket.tlv(0x05, 0x0D, out.toByteArray()))
    }

    /** 05 17: a song is on (0x55), or there is none (0xAA). */
    fun musicActive(on: Boolean): ByteArray = musicFlag(0x17, on)

    /** 05 19: the song plays (0x55) or is paused (0xAA). */
    fun musicPlaying(playing: Boolean): ByteArray = musicFlag(0x19, playing)

    /** 05 58: album art follows (0x55) or there is none (0xAA). */
    fun musicCover(has: Boolean): ByteArray = musicFlag(0x58, has)

    private fun musicFlag(sub: Int, yes: Boolean): ByteArray = K1GPacket.build(K1GPacket.tlv(0x05, sub, if (yes) 0x55 else 0xAA))

    /** A call's state, as the dash reads it in 05 21. */
    enum class CallState(val code: Int) { RINGING(0x0A), IN_CALL(0x14), CALLING_OUT(0x1E), NONE(0x32) }

    /** 05 21: the call's state; [callStateAfter] goes right after each. */
    fun callState(state: CallState): ByteArray = K1GPacket.build(K1GPacket.tlv(0x05, 0x21, state.code))

    /** 05 4D = 32, sent after every call state. */
    fun callStateAfter(): ByteArray = K1GPacket.build(K1GPacket.tlv(0x05, 0x4D, 0x32))

    private const val CALLER_MAX = 20
    private const val CALLER_CUT = 19
    private const val IV_BYTES = 16
    private const val LATIN_1_TOP = 255

    /**
     * 05 22, the caller's name, as the dash reads it: longer than 20 characters it is
     * cut to 19; each character below 255 is its Latin-1 byte, the others 0xFF; a NUL ends it; and it is ENCRYPTED,
     * AES/CBC (PKCS#7) under the session key [key], with [iv] first. Sent in the clear, the dash shows no name.
     */
    fun callNotify(callerName: String, key: ByteArray, iv: ByteArray = randomIv()): ByteArray {
        val name = if (callerName.length > CALLER_MAX) callerName.substring(0, CALLER_CUT) else callerName
        val plain = ByteArray(name.length + 1)
        name.forEachIndexed { i, c -> plain[i] = if (c.code < LATIN_1_TOP) c.code.toByte() else 0xFF.toByte() }
        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.IvParameterSpec(iv))
        return K1GPacket.build(K1GPacket.tlv(0x05, 0x22, iv + cipher.doFinal(plain)))
    }

    private fun randomIv(): ByteArray = ByteArray(IV_BYTES).also { java.security.SecureRandom().nextBytes(it) }

    /** [pkt] without its (type, sub) TLV of one byte, its segment count and outer length fixed. */
    private fun withoutTlv(pkt: ByteArray, type: Int, sub: Int, length: Int): ByteArray {
        val at = indexOf(pkt, byteArrayOf(type.toByte(), sub.toByte(), 0x00, length.toByte()), fromEnd = true)
        if (at < 0) return pkt
        val out = pkt.copyOfRange(0, at) + pkt.copyOfRange(at + 4 + length, pkt.size)
        val segments = (((out[2].toInt() and 0xFF) shl 8) or (out[3].toInt() and 0xFF)) - 1
        out[2] = ((segments shr 8) and 0xFF).toByte()
        out[3] = (segments and 0xFF).toByte()
        out[0] = ((out.size shr 8) and 0xFF).toByte()
        out[1] = (out.size and 0xFF).toByte()
        return out
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, fromEnd: Boolean = false): Int {
        val range = if (fromEnd) (haystack.size - needle.size downTo 0) else (0..haystack.size - needle.size)
        outer@ for (i in range) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}

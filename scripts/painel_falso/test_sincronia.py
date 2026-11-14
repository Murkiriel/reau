"""The bench panel against the app it stands in for: every constant of the protocol that the Python holds (protocolo.py, k1g.py) is read
here from the Kotlin sources and compared. When the app changes a port, a button's code, a packet or the exit sequence, one of these tests
fails until the panel follows.

Run from this folder:  python3 -B -m unittest discover -p "test_*.py"
"""
import os
import re
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import celular_minimo  # noqa: E402
import k1g  # noqa: E402
import protocolo as p  # noqa: E402
import rtp  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(HERE))
MAIN = "app/src/main/java/com/reau/app/dash/"
SHARED = "app/src/sharedTest/java/com/reau/app/dash/"


def kotlin(path):
    with open(os.path.join(ROOT, path), encoding="utf-8") as f:
        return f.read()


def constant(text, name):
    """The value of `const val NAME = …` in [text]: a string, or a number (hex, or with `_` between the digits)."""
    found = re.search(r"\bconst val %s(?:\s*:\s*\w+)?\s*=\s*(\"[^\"]*\"|0x[0-9A-Fa-f]+|[0-9_]+)" % re.escape(name), text)
    if not found:
        raise AssertionError("const val %s is no longer in the Kotlin source" % name)
    value = found.group(1)
    return value[1:-1] if value.startswith('"') else int(value.replace("_", ""), 0)


def command(text, name):
    """The bytes of `fun NAME() = …` of DashCommands: a hex string, or one TLV built with K1GPacket.build."""
    found = re.search(r"fun %s\(\)\s*(?::\s*ByteArray\s*)?=\s*\n?\s*(.+)" % re.escape(name), text)
    if not found:
        raise AssertionError("DashCommands.%s() is no longer in the Kotlin source" % name)
    body = found.group(1)
    hex_text = re.match(r'"([0-9A-Fa-f]+)"\.hexToBytes\(\)', body)
    if hex_text:
        return bytes.fromhex(hex_text.group(1))
    tlv = re.match(r"K1GPacket\.build\(K1GPacket\.tlv\(([^)]*)\)\)", body)
    if not tlv:
        raise AssertionError("DashCommands.%s() is built in a way this test does not read: %s" % (name, body))
    return k1g.build(k1g.tlv(*(int(part, 0) for part in tlv.group(1).split(","))))


def tlv_calls(text):
    """The (type, sub) of every `K1GPacket.tlv(0x.., 0x..` in [text]."""
    return {(int(a, 16), int(b, 16)) for a, b in re.findall(r"K1GPacket\.tlv\(\s*0x([0-9A-Fa-f]{2}),\s*0x([0-9A-Fa-f]{2})", text)}


class AddressesTest(unittest.TestCase):
    def test_the_ports_and_addresses_are_the_dash_sockets(self):
        socket_kt = kotlin(MAIN + "DashSocket.kt")
        self.assertEqual(constant(socket_kt, "DASH_IP"), p.DASH_IP)
        self.assertEqual(constant(socket_kt, "BROADCAST"), p.BROADCAST)
        self.assertEqual(constant(socket_kt, "CTRL_PORT"), p.CTRL_PORT)
        self.assertEqual(constant(socket_kt, "RX_PORT"), p.RX_PORT)
        self.assertEqual(constant(socket_kt, "RTP_PORT"), p.RTP_PORT)
        self.assertIn("const val DASH_RX_PORT = DashSocket.RX_PORT", kotlin(MAIN + "DashPorts.kt"))

    def test_the_wifi_name_prefix_and_the_factory_passphrase_are_the_pairings(self):
        pairing = kotlin(MAIN + "DashPairing.kt")
        self.assertEqual(constant(pairing, "DEFAULT_PREFIX"), p.SSID_PREFIX)
        self.assertEqual(constant(pairing, "DEFAULT_PASSWORD"), p.DEFAULT_PASSWORD)

    def test_the_age_from_which_the_dash_takes_a_greeting(self):
        self.assertEqual(constant(kotlin(MAIN + "DashReadiness.kt"), "READY_COUNT"), p.READY_COUNT)
        self.assertIn(">= 26", kotlin(SHARED + "FakeDashPeer.kt"))

    def test_the_phones_name(self):
        self.assertEqual(constant(kotlin(MAIN + "DashSession.kt"), "HOSTNAME"), celular_minimo.HOSTNAME)


class PacketLayoutTest(unittest.TestCase):
    def test_the_fixed_header_is_the_apps(self):
        packet_kt = kotlin(MAIN + "protocol/K1GPacket.kt")
        fixed = re.search(r"private val FIXED = byteArrayOf\(([^)]*)\)", packet_kt, re.S).group(1)
        header = bytes(int(b, 16) for b in re.findall(r"0x([0-9A-Fa-f]{2})", re.sub(r"//.*", "", fixed)))
        self.assertEqual(12, len(header))
        self.assertEqual(header, k1g._FIXED)
        self.assertEqual(header + b"\x00", k1g.build()[4:])

    def test_the_segments_start_where_the_apps_parser_starts(self):
        self.assertIn("var i = %d" % k1g.SEGMENTS_AT, kotlin(MAIN + "protocol/K1GPacket.kt"))
        self.assertIn("it.type == 0x%02X && it.sub == 0x%02X" % k1g.HEADER, kotlin(MAIN + "DashDump.kt"))

    def test_what_the_log_leaves_out_is_what_the_apps_dump_leaves_out(self):
        left_out = re.search(r"LEFT_OUT = setOf\(([^)]*)\)", kotlin(MAIN + "DashDump.kt")).group(1)
        pairs = {(int(a, 16), int(b, 16)) for a, b in re.findall(r"0x([0-9A-Fa-f]{2}) to 0x([0-9A-Fa-f]{2})", left_out)}
        self.assertEqual(pairs, set(p.LEFT_OUT))


class PhonePacketsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.commands = kotlin(MAIN + "protocol/DashCommands.kt")

    def test_the_packets_copied_whole(self):
        for name, ours in (("authRequest", p.AUTH_REQUEST), ("projectionFrame", p.PROJECTION_FRAME), ("projectionOn", p.PROJECTION_ON),
                           ("projectionStop", p.PROJECTION_STOP), ("projectionOff", p.PROJECTION_OFF),
                           ("frameDecodedIdr", p.FRAME_DECODED_IDR), ("frameDecodedP", p.FRAME_DECODED_P)):
            self.assertEqual(command(self.commands, name), bytes.fromhex(ours), name)

    def test_their_keys(self):
        def key(name):
            (tlv,) = k1g.parse(command(self.commands, name)).tlvs
            return tlv.key, tlv.value
        self.assertEqual((p.GREETING, b"\x01"), key("authRequest"))
        self.assertEqual((p.PROJECTION, bytes([p.ON])), key("projectionFrame"))
        self.assertEqual((p.PROJECTION, bytes([p.OFF])), key("projectionStop"))
        self.assertEqual((p.PROJECTION_FLAG, bytes([p.ON])), key("projectionOn"))
        self.assertEqual((p.PROJECTION_FLAG, bytes([p.OFF])), key("projectionOff"))
        self.assertEqual((p.REPLY_IDR, bytes([p.ON])), key("frameDecodedIdr"))
        self.assertEqual((p.REPLY_P, bytes([p.ON])), key("frameDecodedP"))
        self.assertEqual((p.BUTTON_ECHO, bytes([p.NAV_START])), key("navStart"))

    def test_the_session_key_packet(self):
        prefix = re.search(r'return "([0-9A-Fa-f]+)"\.hexToBytes\(\) \+ ciphertext', self.commands).group(1)
        self.assertEqual(bytes.fromhex(prefix), k1g.build(k1g.tlv(*p.SESSION_KEY, bytes(128)))[:-128])
        self.assertIn("require(ciphertext.size == 128)", self.commands)

    def test_the_button_echo_the_zoom_icons_and_the_callers_name(self):
        self.assertIn("K1GPacket.build(K1GPacket.tlv(0x%02X, 0x%02X, code.toInt() and 0xFF))" % p.BUTTON_ECHO, self.commands)
        self.assertIn("K1GPacket.build(K1GPacket.tlv(0x%02X, 0x%02X, ZOOM_BOTH_ICONS))" % p.ZOOM_ICONS, self.commands)
        self.assertEqual(constant(self.commands, "ZOOM_BOTH_ICONS"), p.ZOOM_BOTH_ICONS)
        self.assertIn("K1GPacket.build(K1GPacket.tlv(0x%02X, 0x%02X, iv + cipher.doFinal(plain)))" % p.CALLER_NAME, self.commands)
        self.assertIn('getInstance("AES/CBC/PKCS5Padding")', self.commands)

    def test_the_phones_name_goes_in_06_0b(self):
        self.assertIn("byteArrayOf(0x01, 0x%02X, 0x%02X, 0x00, (raw.size + 1).toByte())" % p.HOSTNAME, self.commands)

    def test_every_tlv_the_apps_commands_build_has_a_name_in_the_log(self):
        keys = set(tlv_calls(self.commands))
        for hex_text in re.findall(r'"([0-9A-Fa-f]{34,})"', self.commands):
            packet = bytes.fromhex(hex_text)
            if packet[12:16] == k1g.MAGIC and len(packet) > 17:
                keys |= {tlv.key for tlv in k1g.parse(k1g.patch_seq(packet, 0)).tlvs}
        template = re.search(r"NAV_TEMPLATE =\s*\(([^)]*)\)", self.commands, re.S).group(1)
        keys |= {tlv.key for tlv in k1g.parse(bytes.fromhex("".join(re.findall(r'"([0-9A-Fa-f]+)"', template)))).tlvs}
        heartbeat = re.search(r"HB_0049 =\s*\(([^)]*)\)", self.commands, re.S).group(1)
        keys |= {tlv.key for tlv in k1g.parse(bytes.fromhex("".join(re.findall(r'"([0-9A-Fa-f]+)"', heartbeat)))).tlvs}
        keys |= {(int(k[:2], 16), int(k[2:], 16)) for k in re.findall(r'put\("([0-9A-F]{4})', self.commands)}
        keys |= {(0x05, int(sub, 16)) for sub in re.findall(r"musicFlag\(0x([0-9A-Fa-f]{2}),", self.commands)}
        self.assertGreater(len(keys), 40)
        self.assertEqual(set(), keys - set(p.PHONE_TLVS))


class ExitSequenceTest(unittest.TestCase):
    def test_the_exit_sequence_is_the_one_disconnect_sends_in_its_order(self):
        session = kotlin(MAIN + "DashSession.kt")
        commands = kotlin(MAIN + "protocol/DashCommands.kt")
        start = session.index("val goodbye = Runnable {")
        block = session[start:session.index("sock.close()", start)]
        steps = []
        for repeat, name, function in re.findall(r'(?:repeat\((\d+)\) \{ )?send\("([a-z-]+)", DashCommands\.(\w+)\(\)\)', block):
            (tlv,) = k1g.parse(command(commands, function)).tlvs
            steps += [(name, tlv.type, tlv.sub, tlv.value[0])] * int(repeat or 1)
        self.assertEqual(9, len(steps))
        self.assertEqual(tuple(steps), p.EXIT_SEQUENCE)

    def test_the_names_are_the_ones_the_apps_exit_log_is_given(self):
        session = kotlin(MAIN + "DashSession.kt")
        self.assertIn("ExitSequence(MonotonicClock::nowMs)", session)
        self.assertIn("fun step(name: String, sent: Boolean)", kotlin(MAIN + "ExitSequence.kt"))
        for name in {step[0] for step in p.EXIT_SEQUENCE}:
            self.assertIn('send("%s", DashCommands.' % name, session)


class ButtonsTest(unittest.TestCase):
    def test_the_codes_are_the_dash_button_events(self):
        events = kotlin(MAIN + "protocol/DashButtonEvent.kt")
        names = {"ZOOM_IN": "zoom-in", "ZOOM_OUT": "zoom-out", "NEXT_TRACK": "next", "PREV_TRACK": "prev", "JOYSTICK_IN": "in",
                 "JOYSTICK_BACK": "back", "PLAY_PAUSE": "play", "VOLUME_UP": "vol-up", "VOLUME_DOWN": "vol-down"}
        declared = dict(re.findall(r"private const val (\w+) = (0x[0-9A-Fa-f]{2})", events))
        self.assertEqual(set(names), set(declared))
        for kotlin_name, ours in names.items():
            self.assertEqual(int(declared[kotlin_name], 16), p.BUTTONS[ours], kotlin_name)
        # The one button with no event of its own in the app: the map mode's "down", which the session and the ring know by its code.
        self.assertEqual(set(names.values()) | {"recentre"}, set(p.BUTTONS))

    def test_the_recentre_and_the_zoom_codes_are_the_sessions(self):
        zoom = re.search(r"private val ZOOM_CODES = setOf\(([^)]*)\)", kotlin(MAIN + "DashSession.kt")).group(1)
        codes = {int(code, 16) for code in re.findall(r"0x([0-9A-Fa-f]{2})", zoom)}
        self.assertEqual(codes, set(p.ZOOM_CODES))
        self.assertEqual({p.BUTTONS["recentre"]}, codes - {p.BUTTONS["zoom-in"], p.BUTTONS["zoom-out"]})

    def test_the_sides_of_the_joystick_are_the_ring_by_press_ones(self):
        ring = kotlin(MAIN + "RingByPress.kt")
        self.assertIn("Left 0x%02X, right 0x%02X, down 0x%02X, up (the dash's back) 0x%02X" % tuple(
            p.BUTTONS[p.BUTTON_ALIASES[side]] for side in ("left", "right", "down", "up")), ring)
        self.assertEqual(constant(ring, "MIDDLE"), p.BUTTONS[p.BUTTON_ALIASES["middle"]])

    def test_the_session_takes_the_code_from_the_last_byte_of_09_00_and_echoes_it(self):
        session = kotlin(MAIN + "DashSession.kt")
        self.assertIn("tlv.type == 0x%02X && tlv.sub == 0x%02X && tlv.value.isNotEmpty()" % p.BUTTON, session)
        self.assertIn("val btn = tlv.value.last()  // 0900 0001 <code>", session)
        self.assertIn("listOf(DashCommands.buttonAck(code), DashCommands.zoomIcons())", session)


class DashSideTest(unittest.TestCase):
    """What the dash sends: the app's reading of it (DashAuth, DashSession) and the fake dash of its tests."""

    def test_the_key_exchange(self):
        auth = kotlin(MAIN + "DashAuth.kt")
        self.assertIn("if (tlv.type != 0x%02X) return AuthEvent.None" % p.KEY_MODULUS[0], auth)
        self.assertIn("0x%02X -> {\n                if (modulus == null)" % p.KEY_MODULUS[1], auth)
        self.assertIn("0x%02X -> {\n                if (exponent == null)" % p.KEY_EXPONENT[1], auth)
        self.assertIn("0x%02X -> if (tlv.value.firstOrNull() == 0x%02X.toByte()) AuthEvent.Confirmed else AuthEvent.Rejected" % (
            p.KEY_VERDICT[1], p.VERDICT_CONFIRMED), auth)
        self.assertIn("val payload = ssid.toByteArray(Charsets.UTF_8) + aes", auth)
        self.assertIn("ByteArray(32)", auth)
        self.assertIn('Cipher.getInstance("RSA/ECB/PKCS1Padding")', auth)

    def test_the_fake_dash_of_the_apps_tests_sends_the_same_tlvs(self):
        fake = kotlin(SHARED + "FakeDashPeer.kt")
        sent = tlv_calls(fake)
        self.assertEqual({p.KEY_MODULUS, p.KEY_EXPONENT, p.KEY_VERDICT, p.BUTTON, p.NOTICE_IDR, p.NOTICE_P, p.HEARTBEAT}, sent)
        self.assertIn("K1GPacket.tlv(0x07, 0x01, 0x%02X))       // confirmed" % p.VERDICT_CONFIRMED, fake)
        self.assertIn("K1GPacket.tlv(0x07, 0x01, 0x%02X))   // rejected" % p.VERDICT_REJECTED, fake)
        self.assertIn("K1GPacket.tlv(0x09, 0x06, 0x%02X)" % p.ON, fake)
        self.assertIn("K1GPacket.tlv(0x09, 0x04, 0x%02X)" % p.ON, fake)
        self.assertIn("byteArrayOf(0x4B, 0x31, 0x47, (counter shr 8).toByte(), counter.toByte())", fake)
        self.assertIn("if (tlv.type != 0x%02X) return" % p.GREETING[0], fake)
        self.assertIn("0x%02X -> if (++greetings > ignoreFirstGreetings && youngReady()) sendPubkey()" % p.GREETING[1], fake)
        self.assertIn("0x%02X -> onSessionKey(tlv.value)" % p.SESSION_KEY[1], fake)
        self.assertIn("KeyPairGenerator.getInstance(\"RSA\").apply { initialize(1024) }", fake)

    def test_the_notices_and_the_answers_the_session_gives_them(self):
        session = kotlin(MAIN + "DashSession.kt")
        for notice, answer in ((p.NOTICE_IDR, "frameDecodedIdr"), (p.NOTICE_P, "frameDecodedP")):
            at = session.index("tlv.type == 0x%02X && tlv.sub == 0x%02X &&\n                tlv.value.firstOrNull()?.toInt() == 0x%02X" % (notice + (p.ON,)))
            self.assertIn("sock.send(DashCommands.%s())" % answer, session[at:at + 700])

    def test_the_heartbeat_and_the_secure_values_as_the_app_reads_them(self):
        session = kotlin(MAIN + "DashSession.kt")
        self.assertIn("tlv.type == 0x%02X && tlv.sub == 0x%02X) noteHeartbeat(tlv.value)" % p.HEARTBEAT, session)
        self.assertIn("if (tlv.type == 0x%02X) {\n                val key = auth?.sessionKey\n                val plain = key?.let { aesDecryptCbc(tlv.value, it) }" % p.SECURE,
                      session)
        self.assertIn("ivAndCt.copyOfRange(0, 16)", session)


class VideoTest(unittest.TestCase):
    def test_the_packet_limit_and_the_payload_type_are_the_packetizers(self):
        packetizer = kotlin(MAIN + "video/RtpPacketizer.kt")
        self.assertEqual(constant(packetizer, "MAX_PAYLOAD"), p.MAX_PAYLOAD)
        self.assertEqual(constant(packetizer, "PT"), p.RTP_PAYLOAD_TYPE)
        self.assertIn("wallClockMs * 90L", packetizer)
        self.assertEqual(90, rtp.CLOCK_HZ // 1000)

    def test_the_sinks_numbers_are_the_test_sinks(self):
        sink = kotlin(SHARED + "RtpSink.kt")
        self.assertEqual(constant(sink, "MAX_PAYLOAD"), rtp.MAX_PAYLOAD)
        self.assertEqual(constant(sink, "HEADER"), rtp.HEADER)
        self.assertEqual(constant(sink, "FU_A"), rtp.FU_A)
        self.assertIn("AGGREGATION = %d..%d" % (rtp.AGGREGATION[0], rtp.AGGREGATION[-1]), sink)
        fake = kotlin(SHARED + "FakeDashPeer.kt")
        self.assertEqual(constant(fake, "NAL_IDR"), rtp.NAL_IDR)
        self.assertEqual(constant(fake, "NAL_SPS"), rtp.NAL_SPS)


if __name__ == "__main__":
    unittest.main()
